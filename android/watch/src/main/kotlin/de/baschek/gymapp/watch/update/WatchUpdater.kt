package de.baschek.gymapp.watch.update

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import de.baschek.gymapp.watch.BuildConfig
import de.baschek.gymapp.core.update.AppVersion
import de.baschek.gymapp.core.update.ReleaseParser
import de.baschek.gymapp.core.update.UpdateInfo
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** App APK updates are independent of the workout Data Layer. */
enum class WatchUpdatePhase { CHECKING, UP_TO_DATE, DOWNLOADING, READY, INSTALL_PERMISSION_REQUIRED, INSTALLING, UNSUPPORTED_MANUAL_INSTALL, ERROR }
data class WatchUpdateState(val phase: WatchUpdatePhase, val version: String? = null, val message: String = "")

object WatchUpdater {
    private const val MAX_APK = 100L * 1024 * 1024
    private const val MAX_JSON = 4 * 1024 * 1024
    private const val RELEASES = "https://api.github.com/repos/baschek/GymApp/releases?per_page=30"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(WatchUpdateState(WatchUpdatePhase.CHECKING, message = "Noch nicht geprüft"))
    val state = mutableState.asStateFlow()
    @Volatile private var app: Context? = null
    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    private fun prefs(context: Context) = context.getSharedPreferences("watch-app-update", Context.MODE_PRIVATE)
    private fun apk(context: Context) = File(context.filesDir, "updates/watch-update.apk")

    /** Schedule automatic checks; downloading is local, installing always needs user action. */
    fun initialize(context: Context) {
        val application = context.applicationContext
        if (app != null) return
        synchronized(this) {
            if (app != null) return
            app = application
            val version = prefs(application).getString("version", null)
            if (version != null && apk(application).isFile && isNewer(version)) {
                scope.launch {
                    mutex.withLock {
                        try {
                            val digest = requireNotNull(prefs(application).getString("sha256", null))
                            verifyArchive(application, apk(application), version, digest, prefs(application).getLong("size", -1))
                            mutableState.value = WatchUpdateState(WatchUpdatePhase.READY, version, "Update bereit zur Installation")
                        } catch (_: Exception) {
                            mutableState.value = WatchUpdateState(WatchUpdatePhase.ERROR, version, "Gespeichertes Update ist ungültig")
                        }
                    }
                }
            }
            val periodic = PeriodicWorkRequestBuilder<WatchUpdateWorker>(12, TimeUnit.HOURS).setConstraints(constraints).build()
            WorkManager.getInstance(application).enqueueUniquePeriodicWork("watch-apk-update-periodic", ExistingPeriodicWorkPolicy.KEEP, periodic)
            checkNow()
        }
    }
    fun checkNow() {
        val context = app ?: return
        val request = OneTimeWorkRequestBuilder<WatchUpdateWorker>().setConstraints(constraints).build()
        WorkManager.getInstance(context).enqueueUniqueWork("watch-apk-update-check", ExistingWorkPolicy.REPLACE, request)
    }

    internal suspend fun checkAndDownload(context: Context) = withContext(Dispatchers.IO) { mutex.withLock {
        val oldVersion = prefs(context).getString("version", null)
        if (oldVersion == null || !apk(context).isFile || !isNewer(oldVersion))
            mutableState.value = WatchUpdateState(WatchUpdatePhase.CHECKING, message = "Suche nach Uhr-Updates")
        try {
            val info = ReleaseParser.newestWatchUpdate(fetchReleases(), BuildConfig.VERSION_NAME)
            if (info == null) {
                mutableState.value = WatchUpdateState(WatchUpdatePhase.UP_TO_DATE, message = "Uhr-App ist aktuell")
                UpdateNotifications.dismiss(context)
                return@withLock
            }
            val expected = ReleaseParser.requireDigest(info)
            val size = info.sizeBytes ?: error("missing size")
            require(size in 1..MAX_APK)
            val prefs = prefs(context)
            if (prefs.getString("version", null) == info.versionName && prefs.getString("sha256", null) == expected && apk(context).isFile) {
                verifyArchive(context, apk(context), info.versionName, expected, size)
            } else {
                mutableState.value = WatchUpdateState(WatchUpdatePhase.DOWNLOADING, info.versionName, "Uhr-Update wird geladen")
                download(context, info, expected, size)
                check(prefs.edit().putString("version", info.versionName).putString("sha256", expected).putLong("size", size).commit())
            }
            mutableState.value = WatchUpdateState(WatchUpdatePhase.READY, info.versionName, "Update bereit zur Installation")
            UpdateNotifications.ready(context, info.versionName)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) {
            mutableState.value = WatchUpdateState(WatchUpdatePhase.ERROR, oldVersion, "Uhr-Update konnte nicht geprüft oder geladen werden")
            throw e
        }
    } }

    /** The caller must be a visible Activity; Android still shows its own installation confirmation. */
    fun install(activity: Activity) {
        val context = app ?: return
        if (InstallResultReceiver.showConfirmation(activity)) return
        if (!context.packageManager.canRequestPackageInstalls()) {
            mutableState.value = WatchUpdateState(WatchUpdatePhase.INSTALL_PERMISSION_REQUIRED, message = "Installation aus dieser Quelle erlauben")
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            if (intent.resolveActivity(activity.packageManager) == null) {
                mutableState.value = WatchUpdateState(WatchUpdatePhase.UNSUPPORTED_MANUAL_INSTALL, message = "Installationsfreigabe nicht verfügbar. Uhr-App manuell aktualisieren.")
                return
            }
            try { activity.startActivity(intent) }
            catch (_: Exception) { mutableState.value = WatchUpdateState(WatchUpdatePhase.UNSUPPORTED_MANUAL_INSTALL, message = "Installationsfreigabe nicht verfügbar. Uhr-App manuell aktualisieren.") }
            return
        }
        InstallResultReceiver.setForegroundActivity(activity)
        scope.launch {
            mutex.withLock {
                var sessionId: Int? = null
                try {
                    val preferences = prefs(context)
                    val version = requireNotNull(preferences.getString("version", null))
                    val digest = requireNotNull(preferences.getString("sha256", null))
                    val size = preferences.getLong("size", -1)
                    verifyArchive(context, apk(context), version, digest, size)
                    val installer = context.packageManager.packageInstaller
                    val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                    params.setAppPackageName(context.packageName)
                    if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
                    sessionId = installer.createSession(params)
                    installer.openSession(sessionId).use { session ->
                        session.openWrite("watch.apk", 0, size).use { out -> apk(context).inputStream().use { it.copyTo(out) }; session.fsync(out) }
                        val callback = Intent(context, InstallResultReceiver::class.java).setAction("de.baschek.gymapp.watch.UPDATE_RESULT")
                        val pending = PendingIntent.getBroadcast(context, sessionId, callback, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                        mutableState.value = WatchUpdateState(WatchUpdatePhase.INSTALLING, version, "Installation bestätigen")
                        session.commit(pending.intentSender)
                    }
                } catch (e: Exception) {
                    sessionId?.let { runCatching { context.packageManager.packageInstaller.abandonSession(it) } }
                    mutableState.value = WatchUpdateState(WatchUpdatePhase.ERROR, message = "Installation konnte nicht gestartet werden")
                }
            }
        }
    }

    internal fun result(state: WatchUpdateState) { mutableState.value = state }
    private fun isNewer(version: String): Boolean {
        val candidate = AppVersion.parse(version) ?: return false
        val current = AppVersion.parse(BuildConfig.VERSION_NAME) ?: return false
        return candidate > current
    }
    private fun fetchReleases(): String {
        val connection = open(RELEASES)
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            check(connection.responseCode == 200)
            return connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    require(output.size() + n <= MAX_JSON)
                    output.write(buffer, 0, n)
                }
                output.toString(Charsets.UTF_8.name())
            }
        } finally { connection.disconnect() }
    }
    private fun download(context: Context, info: UpdateInfo, digest: String, size: Long) {
        require(ReleaseParser.isWatchReleaseUrl(info.apkUrl, info.tag, info.apkName))
        val dir = requireNotNull(apk(context).parentFile)
        check(dir.isDirectory || dir.mkdirs())
        val tmp = File(dir, "watch-download-pending.apk")
        var connection: HttpURLConnection? = null
        try {
            var url = info.apkUrl
            for (redirects in 0..5) {
                val next = open(url)
                connection = next
                if (next.responseCode in listOf(301, 302, 303, 307, 308)) {
                    check(redirects < 5)
                    val location = requireNotNull(next.getHeaderField("Location"))
                    val resolved = URL(URL(url), location).toString()
                    require(ReleaseParser.isTrustedDownloadUrl(resolved))
                    next.disconnect(); connection = null; url = resolved
                    continue
                }
                check(next.responseCode == 200)
                var count = 0L
                next.inputStream.use { input -> tmp.outputStream().use { out ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        count += n
                        require(count <= size && count <= MAX_APK)
                        out.write(buffer, 0, n)
                    }
                    out.fd.sync()
                } }
                require(count == size)
                verifyArchive(context, tmp, info.versionName, digest, size)
                Files.move(tmp.toPath(), apk(context).toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                return
            }
            error("too many redirects")
        } finally { connection?.disconnect(); tmp.delete() }
    }
    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000; readTimeout = 60_000; instanceFollowRedirects = false
    }
    internal fun verifyArchive(context: Context, file: File, version: String, expected: String, size: Long) {
        require(expected.matches(Regex("[0-9a-fA-F]{64}")) && size in 1..MAX_APK && file.isFile && file.length() == size && isNewer(version))
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(32 * 1024); while (true) { val n = input.read(buffer); if (n < 0) break; md.update(buffer, 0, n) } }
        require(md.digest().joinToString("") { "%02x".format(it) }.equals(expected, ignoreCase = true))
        val pm = context.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        @Suppress("DEPRECATION") val archive = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: error("invalid APK")
        @Suppress("DEPRECATION") val installed = pm.getPackageInfo(context.packageName, flags)
        require(archive.packageName == context.packageName && archive.versionName == version && archive.longVersionCode > installed.longVersionCode)
        val signing = archive.signingInfo ?: error("missing APK signature")
        val ours = installed.signingInfo ?: error("missing installed signature")
        val candidate = signing.apkContentsSigners.map { it.toByteArray().toList() }.toSet()
        val current = ours.apkContentsSigners.map { it.toByteArray().toList() }.toSet()
        require(candidate.isNotEmpty() && candidate == current) { "signer mismatch" }
    }
}
