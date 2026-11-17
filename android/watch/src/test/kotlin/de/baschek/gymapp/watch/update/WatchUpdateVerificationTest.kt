package de.baschek.gymapp.watch.update

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.security.MessageDigest
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class WatchUpdateVerificationTest {
    @Test fun rejectsTamperedAndTruncatedApkBeforeInstaller() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val file = java.io.File(context.cacheDir, "test-watch-update.apk")
        file.writeText("synthetic APK data")
        val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        val size = file.length()
        file.appendText("modified")
        assertThrows(IllegalArgumentException::class.java) { WatchUpdater.verifyArchive(context, file, "9999.0", digest, size) }
        val changedLength = file.length()
        assertThrows(IllegalArgumentException::class.java) { WatchUpdater.verifyArchive(context, file, "9999.0", digest, changedLength) }
        file.delete()
    }
}
