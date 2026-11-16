package de.baschek.gymapp.watch.runtime

import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import de.baschek.gymapp.core.model.SessionStatus
import de.baschek.gymapp.core.model.SetStatus
import de.baschek.gymapp.watch.MainActivity
import de.baschek.gymapp.watch.WatchApplication
import kotlinx.coroutines.launch

/** A tap opens the existing watch workout; it never logs a set from the watch face. */
class WorkoutComplication : ComplicationDataSourceService() {
    override fun getPreviewData(type: ComplicationType): ComplicationData? = if (type == ComplicationType.SHORT_TEXT) data("Start", false) else null
    override fun onComplicationRequest(request: ComplicationRequest, listener: ComplicationRequestListener) {
        if (request.complicationType != ComplicationType.SHORT_TEXT) { listener.onComplicationData(null); return }
        val app = application as WatchApplication
        app.scope.launch {
            val state = runCatching { app.restoreRepository()?.state?.value }.getOrNull()
            val session = state?.session
            val exercise = session?.exercises?.firstOrNull { it.id == state?.focusExerciseId }
            val pending = exercise?.sets?.indexOfFirst { it.status == SetStatus.PENDING } ?: -1
            val label = if (session?.status == SessionStatus.ACTIVE && exercise != null && pending >= 0) "${pending + 1}/${exercise.sets.size}" else "Start"
            Handler(Looper.getMainLooper()).post { listener.onComplicationData(data(label, true)) }
        }
    }
    private fun data(label: String, clickable: Boolean): ComplicationData {
        val text = PlainComplicationText.Builder(label).build()
        val description = PlainComplicationText.Builder(if (label == "Start") "GymApp öffnen" else "GymApp Satz $label").build()
        val builder = ShortTextComplicationData.Builder(text, description)
        if (clickable) builder.setTapAction(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        return builder.build()
    }
}
