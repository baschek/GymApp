package de.baschek.gymapp.recorder.shared

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import de.baschek.gymapp.core.recorder.NavEvent
import de.baschek.gymapp.core.recorder.Recording
import java.io.File
import java.io.IOException
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

/** Single FIFO for event writes and export snapshots. The switch is deliberately process-local. */
abstract class DeviceRecorder(private val device: String) {
    protected lateinit var appContext: Context
        private set
    protected lateinit var root: File
        private set
    private val writer = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val fileScope = CoroutineScope(writer)
    private var lastCapture = -2_000L
    private val timer = Handler(Looper.getMainLooper())
    private val timeout = Runnable { checkDeadline() }
    private val enabled = MutableStateFlow(false)
    val recording = enabled.asStateFlow()
    private var session: Recording? = null
    private var directory: File? = null
    private var activeWorkout: String? = null
    private var screenVersion = 0L
    @Volatile internal var screenshot: ScreenCapture? = null

    fun install(context: Context) {
        appContext = context.applicationContext
        root = File(appContext.filesDir, "navrec")
    }

    @Synchronized fun start() {
        if (session != null) return
        val dir = File(root, "$device-${UUID.randomUUID()}")
        val newSession = Recording(device, SystemClock.elapsedRealtime())
        directory = dir
        session = newSession
        writer.executor.execute {
            try {
                check(dir.mkdirs())
                check(File(dir, "events.jsonl").createNewFile())
            } catch (_: Exception) { stopIfCurrent(newSession) }
        }
        enabled.value = true
        timer.postDelayed(timeout, Recording.LIMIT_MS)
    }

    @Synchronized fun stop() {
        session = null
        directory = null
        enabled.value = false
        timer.removeCallbacks(timeout)
    }

    fun setEnabled(value: Boolean) { if (value) start() else stop() }

    @Synchronized fun workoutChanged(activeId: String?) {
        if (activeWorkout != null && activeWorkout != activeId) stop()
        activeWorkout = activeId
    }

    @Synchronized internal fun claimCapture(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastCapture < 2_000) return false
        lastCapture = now
        return true
    }

    @Synchronized fun checkDeadline() {
        if (session?.expired(SystemClock.elapsedRealtime()) == true) stop()
    }

    @Synchronized private fun stopIfCurrent(previous: Recording) { if (session === previous) stop() }

    fun log(screen: String, element: String) = record(NavEvent.TAP, screen, element)
    fun tap(element: String) = record(NavEvent.TAP, null, element)
    fun screen(name: String) = record(NavEvent.SCREEN, name, "")
    fun auto(cause: String) = record(NavEvent.AUTO, null, cause)
    fun action(element: String) = record(NavEvent.ACTION, null, element)

    @Synchronized private fun record(type: NavEvent, screen: String?, element: String) {
        val current = session ?: return
        val dir = directory ?: return
        val now = SystemClock.elapsedRealtime()
        if (current.expired(now)) { stop(); return }
        val entry = current.event(type, screen, element, System.currentTimeMillis(), now) ?: return
        if (type == NavEvent.SCREEN) screenVersion++
        val version = screenVersion
        val shot = if (entry.capture) screenshot?.request {
            synchronized(this) { current === session && version == screenVersion && !current.expired(SystemClock.elapsedRealtime()) }
        } else null
        writer.executor.execute {
            try {
                val saved = shot != null && saveShot(shot, File(dir, entry.shotName))
                File(dir, "events.jsonl").appendText(entry.line(saved))
            } catch (_: IOException) { stopIfCurrent(current) }
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

    /** Called only from an explicit export gesture. Writes queue before or after this entire snapshot. */
    protected suspend fun <T> snapshot(block: (List<File>) -> T): T = withContext(writer) { block(directories()) }

    /** Seal and queue the list under the same lock as start/log. A later start cannot enter this transfer. */
    @Synchronized protected fun finishForTransfer(): Deferred<List<File>> {
        stop()
        return fileScope.async { directories() }
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
        val SESSION_DIR = Regex("(phone|watch)-[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")
        val ENTRY_NAME = Regex("""events\.jsonl|screen-\d{1,8}\.webp""")
    }
}
