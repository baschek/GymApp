package de.baschek.gymapp.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import de.baschek.gymapp.BuildConfig
import de.baschek.gymapp.core.update.ReleaseParser
import de.baschek.gymapp.core.update.UpdateChecker
import de.baschek.gymapp.core.update.UpdateInfo
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * In-app updater for sideloaded installs: looks for a newer `android-v*`
 * release on GitHub, downloads the APK and hands it to the package installer.
 * Android only installs it if it is signed with the same key as this app.
 */
class AppUpdater(private val context: Context, httpClient: HttpClient) {
    private val checker = UpdateChecker(httpClient)

    suspend fun checkForUpdate(): UpdateInfo? = checker.check(BuildConfig.VERSION_NAME)

    /** Downloads the APK into the cache (verifying the published digest when there is one). */
    suspend fun download(info: UpdateInfo): File = withContext(Dispatchers.IO) {
        require(ReleaseParser.isTrustedDownloadUrl(info.apkUrl)) { "untrusted download url" }
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, "gymapp-update.apk")
        val connection = URL(info.apkUrl).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 60_000
        connection.instanceFollowRedirects = true
        try {
            check(connection.responseCode == 200) { "download failed" }
            val digest = MessageDigest.getInstance("SHA-256")
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        digest.update(buffer, 0, n)
                        output.write(buffer, 0, n)
                    }
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (info.sha256 != null && !info.sha256.equals(actual, ignoreCase = true)) {
                target.delete()
                error("checksum mismatch")
            }
        } finally {
            connection.disconnect()
        }
        target
    }

    /** Returns false if the user first has to allow installs from this app (settings screen is opened). */
    fun install(apk: File): Boolean {
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return true
    }
}
