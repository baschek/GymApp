package de.baschek.gymapp.recorder.shared

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import de.baschek.gymapp.core.recorder.NavEvent
import de.baschek.gymapp.core.recorder.RecorderConfig
import de.baschek.gymapp.core.recorder.Recording
import de.baschek.gymapp.core.recorder.Retention
import de.baschek.gymapp.core.recorder.StoredSession
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal fun interface ScreenCapture {
    fun request(valid: () -> Boolean): Deferred<Bitmap?>
}

/**
 * Always-on recorder. [start] opens the first session; a session ends only by rolling over to a new one (workout change,
 * [Recording.LIMIT_MS], transfer). Single FIFO for event writes, retention and export snapshots. A session folder is
 * created with its first write, so idle starts leave nothing behind. Disk trouble never reaches the app.
 */
abstract class DeviceRecorder(private val device: String) {
    protected lateinit var appContext: Context
        private set
    protected lateinit var root: File
        private set
    protected open val config: RecorderConfig = RecorderConfig.PHONE
    private val writer = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val fileScope = CoroutineScope(writer)
    private var lastCapture = -10_000L
    private val timer = Handler(Looper.getMainLooper())
    private val timeout = Runnable { checkDeadline() }
    private val active = MutableStateFlow(false)
    /** True once [start] ran; the recorder never switches off again. */
    val recording = active.asStateFlow()
    private var session: Recording? = null
    private var directory: File? = null
    private var activeWorkout: String? = null
    private var screenVersion = 0L
    @Volatile internal var screenshot: ScreenCapture? = null

    fun install(context: Context) {
        appContext = context.applicationContext
        root = File(appContext.filesDir, "navrec")
    }

    /** Whether a screenshot may be taken now (the watch says no in ambient mode or with the screen off). */
    protected open fun shotsAllowed(): Boolean = true

    @Synchronized fun start() {
        if (session != null) return
        openSession()
    }

    /** Starts a new session and drops the old one from memory; its folder stays on disk until retention removes it. */
    @Synchronized private fun openSession() {
        val dir = File(root, "$device-${UUID.randomUUID()}")
        val cfg = config
        session = Recording(device, SystemClock.elapsedRealtime(), cfg.shotIntervalMs, cfg.maxShotsPerSession)
        directory = dir
        active.value = true
        timer.removeCallbacks(timeout)
        timer.postDelayed(timeout, Recording.LIMIT_MS)
        pruneLater(dir.name)
    }

    /** Closes the current session and opens a new one on the same screen. Does nothing for an empty session. */
    @Synchronized private fun rollOver(force: Boolean = false) {
        val old = session ?: return
        if (old.eventCount == 0 && !force) return
        val screen = old.screen
        openSession()
        if (screen != null) record(NavEvent.SCREEN, screen, "")
    }

    @Synchronized fun workoutChanged(activeId: String?) {
        if (activeWorkout == activeId) return
        activeWorkout = activeId
        rollOver()
    }

    @Synchronized internal fun claimCapture(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastCapture < config.shotIntervalMs) return false
        lastCapture = now
        return true
    }

    @Synchronized fun checkDeadline() {
        val current = session ?: return
        val now = SystemClock.elapsedRealtime()
        if (current.expired(now)) {
            rollOver(force = true)
        } else {
            timer.removeCallbacks(timeout)
            timer.postDelayed(timeout, current.remainingMs(now))
        }
    }

    fun log(screen: String, element: String) = record(NavEvent.TAP, screen, element)
    fun tap(element: String) = record(NavEvent.TAP, null, element)
    fun screen(name: String) = record(NavEvent.SCREEN, name, "")
    fun auto(cause: String) = record(NavEvent.AUTO, null, cause)
    fun action(element: String) = record(NavEvent.ACTION, null, element)

    @Synchronized private fun record(type: NavEvent, screen: String?, element: String) {
        var current = session ?: return
        val now = SystemClock.elapsedRealtime()
        if (current.expired(now)) {
            rollOver(force = true)
            current = session ?: return
        }
        val entry = current.event(type, screen, element, System.currentTimeMillis(), now, shotsAllowed()) ?: return
        val dir = directory ?: return
        if (type == NavEvent.SCREEN) screenVersion++
        val version = screenVersion
        val shot = if (entry.capture) screenshot?.request {
            synchronized(this) {
                current === session && version == screenVersion && !current.expired(SystemClock.elapsedRealtime()) && shotsAllowed()
            }
        } else null
        writer.executor.execute {
            try {
                dir.mkdirs()
                val saved = shot != null && saveShot(shot, File(dir, entry.shotName))
                File(dir, "events.jsonl").appendText(entry.line(saved))
            } catch (_: Exception) {
                shot?.cancel() // Recording is best effort; a full disk or a deleted folder must never affect the app.
            }
        }
    }

    @Suppress("DEPRECATION") // WEBP is supported on the phone's minimum API (29).
    private fun saveShot(shot: Deferred<Bitmap?>, file: File): Boolean {
        return try {
            val bitmap = runBlocking { withTimeoutOrNull(2_000) { shot.await() } } ?: return false
            val saved = file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.WEBP, 80, it) }
            if (!saved) file.delete()
            saved
        } catch (_: Exception) {
            file.delete()
            false
        } finally { shot.cancel() }
    }

    @Synchronized private fun currentName() = directory?.name

    private fun pruneLater(current: String?) {
        writer.executor.execute { prune(current) }
    }

    /** Re-applies retention, for example after a recording arrived from the watch. */
    fun prune() = pruneLater(currentName())

    /**
     * Rolling retention on the writer thread: count, age and size, oldest first, never [current]. Also removes stale
     * share archives and unfinished receive folders. Never throws.
     */
    private fun prune(current: String?) {
        try {
            if (!::root.isInitialized || !root.isDirectory) return
            val now = System.currentTimeMillis()
            val sessions = directories().map { StoredSession(it.name, lastActivity(it), sizeOf(it)) }
            for (name in Retention.expired(sessions, now, config.retention, current)) File(root, name).deleteRecursively()
            for (file in root.listFiles().orEmpty()) {
                val stale = now - file.lastModified() > STALE_MS
                if (stale && file.name.startsWith(".receiving-")) file.deleteRecursively()
                if (stale && file.isFile && file.name.startsWith("gymapp-navrec-")) file.delete()
            }
            for (file in File(root, "shared").listFiles().orEmpty()) if (now - file.lastModified() > STALE_MS) file.deleteRecursively()
        } catch (_: Exception) { /* retention is best effort */ }
    }

    private fun lastActivity(dir: File) = maxOf(dir.lastModified(), File(dir, "events.jsonl").lastModified())

    private fun sizeOf(dir: File) = dir.listFiles().orEmpty().sumOf { if (it.isFile) it.length() else 0L }

    /** Called only from an explicit export gesture. Writes queue before or after this entire snapshot; recording goes on. */
    protected suspend fun <T> snapshot(block: (List<File>) -> T): T = withContext(writer) { block(directories()) }

    /**
     * Closes the current session and starts a new one under the same lock as record, then lists everything except the
     * new current session on the writer, so queued writes of the closed sessions are complete.
     */
    @Synchronized protected fun closeForTransfer(): Deferred<List<File>> {
        rollOver()
        val keep = directory?.name
        return fileScope.async { directories().filter { it.name != keep } }
    }

    /** Deletes every session folder except the current one; returns how many were removed. */
    protected suspend fun deleteClosed(): Int {
        val keep = currentName()
        return withContext(writer) {
            val removed = directories().filter { it.name != keep }.count { it.deleteRecursively() }
            File(root, "shared").deleteRecursively()
            removed
        }
    }

    private fun directories() = root.listFiles().orEmpty()
        .filter { it.isDirectory && SESSION_DIR.matches(it.name) }
        .sortedBy { it.name }

    protected fun zip(directories: List<File>, target: File) {
        ZipOutputStream(target.outputStream().buffered()).use { out ->
            for (dir in directories) {
                for (file in dir.listFiles().orEmpty().sortedBy { it.name }) {
                    if (!file.isFile || !ENTRY_NAME.matches(file.name)) continue
                    out.putNextEntry(ZipEntry("${dir.name}/${file.name}"))
                    file.inputStream().use { it.copyTo(out) }
                    out.closeEntry()
                }
            }
        }
    }

    companion object {
        const val TRANSFER = "/gymapp/navrec/v1"
        private const val STALE_MS = 24 * 60 * 60 * 1000L
        val SESSION_DIR = Regex("(phone|watch)-[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")
        val ENTRY_NAME = Regex("""events\.jsonl|screen-\d{1,8}\.webp""")
    }
}
