package de.baschek.gymapp.recorder

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.test.core.app.ApplicationProvider
import de.baschek.gymapp.recorder.shared.DeviceRecorder
import de.baschek.gymapp.recorder.shared.Region
import de.baschek.gymapp.recorder.shared.redact
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class RecorderExportTest {
    private val watch = "watch-00000000-0000-4000-8000-000000000001"

    private fun asset(files: Map<String, ByteArray>): ByteArrayInputStream {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            for ((name, content) in files) {
                zip.putNextEntry(ZipEntry("$watch/$name"))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return bytes.toByteArray().inputStream()
    }

    @Test fun shareIncludesPhoneAndReceivedFilesOnceAfterRepeatedTransfer() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val root = File(app.filesDir, "navrec")
        val phone = object : DeviceRecorder("phone") {
            suspend fun archive(file: File) = snapshot { zip(it, file) }
        }
        phone.install(app)
        phone.start()
        phone.screen("home")
        val watchFiles = mapOf("events.jsonl" to """{"dev":"watch"}""".encodeToByteArray(),
            "screen-0.webp" to byteArrayOf(1, 2, 3))
        repeat(2) { receiveRecording(root, watch, asset(watchFiles)) }
        val archive = File(app.cacheDir, "synthetic.zip")
        phone.archive(archive)
        ZipFile(archive).use { zip ->
            assertEquals(3, zip.size())
            assertTrue(zip.entries().asSequence().any { it.name.startsWith("phone-") && it.name.endsWith("/events.jsonl") })
            for ((name, content) in watchFiles) {
                assertArrayEquals(content, zip.getInputStream(zip.getEntry("$watch/$name")).use { it.readBytes() })
            }
        }
    }

    @Test fun incompleteOrUnexpectedEntriesAreNeverVisibleToAnExport() {
        val root = File(ApplicationProvider.getApplicationContext<Application>().filesDir, "navrec")
        for (names in listOf(listOf("screen-1.webp"), listOf("events.jsonl", "extra.txt"))) {
            assertThrows(IllegalArgumentException::class.java) {
                receiveRecording(root, watch, asset(names.associateWith { byteArrayOf(0) }))
            }
            assertTrue(root.listFiles().orEmpty().isEmpty())
        }
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun privatePixelsAndUnregisteredCaptionsCannotReachTheSavedImage() {
        fun frame(text: String): Bitmap {
            val bitmap = Bitmap.createBitmap(180, 60, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.BLACK)
            val paint = Paint().apply { color = Color.WHITE; textSize = 16f }
            Canvas(bitmap).drawText(text, 2f, 38f, paint)
            return bitmap
        }
        val first = frame("81.25 kg × 17")
        val second = frame("synthetic note")
        assertFalse(first.sameAs(second))
        val bounds = RectF(0f, 10f, 180f, 50f)
        redact(first, listOf(Region(bounds, false, "81.25 kg × 17")))
        redact(second, listOf(Region(bounds, false, "synthetic note")))
        assertTrue(first.sameAs(second))
    }
}
