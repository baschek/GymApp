package de.baschek.gymapp.recorder

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import de.baschek.gymapp.recorder.shared.DeviceRecorder
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object NavRecorder : DeviceRecorder("phone") {
    /** Deletes all recordings except the one being written; returns how many sessions were removed. */
    suspend fun deleteAll(): Int = deleteClosed()

    /** Shares phone and received watch sessions as one ZIP. The current session keeps recording. */
    suspend fun export(context: Context = appContext) {
        val archive = snapshot { sessions ->
            if (sessions.isEmpty()) throw NoSuchElementException()
            val shared = File(root, "shared").apply { mkdirs() }
            val file = File.createTempFile("gymapp-navrec-", ".zip", shared)
            try {
                zip(sessions, file)
                file
            } catch (e: Exception) {
                file.delete()
                throw e
            }
        }
        withContext(Dispatchers.Main) {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", archive)
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri("Navigationsaufzeichnung", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(share, "Aufzeichnung teilen")
            if (context !is android.app.Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
        }
    }
}
