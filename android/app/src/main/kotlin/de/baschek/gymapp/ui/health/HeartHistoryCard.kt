package de.baschek.gymapp.ui.health

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.baschek.gymapp.GymApplication
import de.baschek.gymapp.core.health.HeartRecording
import de.baschek.gymapp.core.health.Phase
import de.baschek.gymapp.core.health.RecordingProfile
import de.baschek.gymapp.core.health.SampleQuality
import de.baschek.gymapp.core.health.correlatePhases
import de.baschek.gymapp.data.Profile
import de.baschek.gymapp.core.model.Session
import de.baschek.gymapp.health.HealthConnectExporter
import de.baschek.gymapp.health.HealthExportConsent
import de.baschek.gymapp.health.HealthExportWorker
import de.baschek.gymapp.health.HealthExportResult
import de.baschek.gymapp.health.publishHealthStatus
import de.baschek.gymapp.health.watchStatus
import de.baschek.gymapp.health.PrivateHeartStore
import de.baschek.gymapp.telemetry.Telemetry
import de.baschek.gymapp.ui.components.GymCardSurface
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/** Real measurements only: gaps and uncertain samples never become interpolated pulse values. */
@Composable
fun HeartHistoryCard(sessionId: String, session: Session) {
    val context = LocalContext.current.applicationContext
    val repository = (context as GymApplication).container.repository
    val profile by repository.activeProfile.collectAsStateWithLifecycle(initialValue = Profile.PERSONAL)
    var recording by remember(sessionId, profile) { mutableStateOf<HeartRecording?>(null) }
    var selected by remember(sessionId) { mutableStateOf<Int?>(null) }
    var message by remember(sessionId) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val exporter = remember(context) { HealthConnectExporter(context) }
    val consent = remember(context) { HealthExportConsent(context) }
    val exportNow: () -> Unit = {
        scope.launch {
            val store = PrivateHeartStore(context)
            val latest = store.read(RecordingProfile.PERSONAL, sessionId) ?: run {
                val start = runCatching { Instant.parse(session.startedAt).toEpochMilli() }.getOrNull()
                val end = runCatching { Instant.parse(session.completedAt).toEpochMilli() }.getOrNull()
                if (start == null || end == null || start < 0 || end <= start || end - start > 86_400_000L) null
                else HeartRecording(RecordingProfile.PERSONAL, sessionId, start, end, 0, emptyList()).also {
                    store.save(it); recording = it
                }
            }
            if (latest == null) message = "Trainingszeit fehlt – Export nicht möglich"
            else {
                val result = exporter.export(sessionId, RecordingProfile.PERSONAL, latest.version, true)
                publishHealthStatus(context, sessionId, latest.version, result.watchStatus())
                when (result) {
                HealthExportResult.Exported -> message = "In Health Connect gespeichert"
                HealthExportResult.UnsupportedHistory -> message = "Ältere Daten können auf diesem Gerät nicht abgeglichen werden"
                is HealthExportResult.PermissionRequired -> message = "Berechtigung fehlt – Training bleibt gespeichert"
                else -> { message = "Gesundheitsexport ausstehend"; HealthExportWorker.enqueue(context, sessionId) }
                }
            }
        }
    }
    val launcher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
        if (granted.containsAll(exporter.requiredPermissions) && profile == Profile.PERSONAL) {
            consent.set(sessionId, true)
            exportNow()
        } else message = "Berechtigung fehlt – Training bleibt gespeichert"
    }
    LaunchedEffect(sessionId, profile) {
        recording = runCatching { PrivateHeartStore(context).read(if (profile == Profile.PERSONAL) RecordingProfile.PERSONAL else RecordingProfile.TEST, sessionId) }.getOrNull()
    }
    val data = recording
    GymCardSurface {
        Text("Pulsverlauf", style = MaterialTheme.typography.titleMedium)
        if (data == null || data.samples.none { it.quality == SampleQuality.RELIABLE }) {
            Text("Kein Puls aufgezeichnet", style = MaterialTheme.typography.bodyMedium)
        } else {
            val reliable = data.samples.filter { it.quality == SampleQuality.RELIABLE }
            val phases = remember(session, data) { correlatePhases(session, data.startMillis, data.endMillis) }
            val brackets = remember(session, phases) {
                val names = session.exercises.flatMap { exercise -> exercise.sets.map { it.id to exercise.name } }.toMap()
                val groups = mutableListOf<Triple<Long, Long, String>>()
                phases.filter { it.phase == Phase.SET }.forEach { phase ->
                    val name = phase.setId?.let { names[it] } ?: return@forEach
                    val previous = groups.lastOrNull()
                    if (previous != null && previous.third == name)
                        groups[groups.lastIndex] = Triple(previous.first, phase.endMillis, name)
                    else groups += Triple(phase.startMillis, phase.endMillis, name)
                }
                groups
            }
            val low = minOf(90, reliable.minOf { it.bpm } - 5).coerceAtLeast(0)
            val high = maxOf(170, reliable.maxOf { it.bpm } + 5)
            val line = MaterialTheme.colorScheme.primary
            val shade = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
            val guide = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
            Canvas(Modifier.fillMaxWidth().height(160.dp).padding(vertical = 8.dp).pointerInput(data) {
                detectTapGestures { point ->
                    val at = data.startMillis + ((point.x / size.width).coerceIn(0f, 1f) * (data.endMillis - data.startMillis)).toLong()
                    selected = reliable.indices.minByOrNull { abs(reliable[it].timeMillis - at) }
                }
            }) {
                fun x(time: Long) = ((time - data.startMillis).toFloat() / (data.endMillis - data.startMillis)) * size.width
                val plotTop = 25.dp.toPx()
                fun y(bpm: Int) = size.height - (bpm - low).toFloat() / (high - low) * (size.height - plotTop)
                val bracketPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = guide.toArgb(); textSize = 10.dp.toPx()
                }
                brackets.forEach { (start, end, name) ->
                    val left = x(start); val right = x(end); val width = right - left
                    val bracketY = 17.dp.toPx()
                    drawLine(guide, Offset(left, bracketY), Offset(right, bracketY), 1.dp.toPx())
                    drawLine(guide, Offset(left, bracketY), Offset(left, plotTop), 1.dp.toPx())
                    drawLine(guide, Offset(right, bracketY), Offset(right, plotTop), 1.dp.toPx())
                    if (width >= 40.dp.toPx()) {
                        val available = (width - 4.dp.toPx()).coerceAtLeast(0f)
                        val count = bracketPaint.breakText(name, true, available, null)
                        val label = if (count < name.length && count > 1) name.take(count - 1) + "…" else name.take(count)
                        drawIntoCanvas { it.nativeCanvas.drawText(label, left + 2.dp.toPx(), 12.dp.toPx(), bracketPaint) }
                    }
                }
                phases.filter { it.phase == Phase.SET }.forEach { p ->
                    drawRect(shade, Offset(x(p.startMillis), plotTop), Size(x(p.endMillis) - x(p.startMillis), size.height - plotTop))
                }
                listOf(100, 130, 160).forEach { bpm -> drawLine(guide, Offset(0f, y(bpm)), Offset(size.width, y(bpm)), 1.dp.toPx()) }
                var gapIndex = 0
                data.samples.zipWithNext().forEach { (a, b) ->
                    while (gapIndex < data.gaps.size && data.gaps[gapIndex].endMillis <= a.timeMillis) gapIndex++
                    val crossesGap = gapIndex < data.gaps.size && data.gaps[gapIndex].startMillis < b.timeMillis
                    val missing = b.timeMillis - a.timeMillis > 30_000 || crossesGap
                    if (!missing && a.quality == SampleQuality.RELIABLE && b.quality == SampleQuality.RELIABLE)
                        drawLine(line, Offset(x(a.timeMillis), y(a.bpm)), Offset(x(b.timeMillis), y(b.bpm)), 2.dp.toPx())
                }
                if (reliable.size == 1) reliable.single().let { drawCircle(line, 3.dp.toPx(), Offset(x(it.timeMillis), y(it.bpm))) }
                selected?.let { index -> reliable.getOrNull(index)?.let { drawCircle(line, 3.dp.toPx(), Offset(x(it.timeMillis), y(it.bpm))) } }
            }
            Text("Ø ${reliable.map { it.bpm }.average().toInt()} · Max ${reliable.maxOf { it.bpm }} bpm", style = MaterialTheme.typography.bodyMedium)
            Text("Hilfslinien: 100 · 130 · 160 bpm", style = MaterialTheme.typography.bodySmall)
            if (data.gaps.isNotEmpty() || data.samples.zipWithNext().any { (a, b) -> b.timeMillis - a.timeMillis > 30_000 } || data.samples.any { it.quality == SampleQuality.UNRELIABLE })
                Text("Messwerte fehlen", style = MaterialTheme.typography.bodySmall)
            selected?.let { index -> reliable.getOrNull(index)?.let { sample ->
                val phase = phases.firstOrNull { sample.timeMillis >= it.startMillis && sample.timeMillis < it.endMillis }
                val exercise = session.exercises.firstOrNull { ex -> ex.sets.any { it.id == phase?.setId } }
                val set = exercise?.sets?.firstOrNull { it.id == phase?.setId }
                val time = DateTimeFormatter.ofPattern("HH:mm:ss").format(Instant.ofEpochMilli(sample.timeMillis).atZone(ZoneId.systemDefault()))
                Text("$time · ${sample.bpm} bpm" + (if (exercise != null && set != null) " · ${exercise.name} · Satz ${set.order}" else " · Zeitabschnitt unbekannt"), style = MaterialTheme.typography.bodySmall)
            } }
        }
        if (profile == Profile.PERSONAL) {
                Text("Mit deiner Freigabe werden Training und gegebenenfalls Puls an Health Connect übertragen. Zum Abgleichen liest die App nur eigene Pulsdaten.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = {
                    Telemetry.track("health", "export")
                    scope.launch {
                        val missing = runCatching { exporter.missingPermissions() }.getOrNull()
                        if (missing == null) message = "Health Connect ist nicht verfügbar"
                        else if (missing.isNotEmpty()) launcher.launch(exporter.requiredPermissions)
                        else { consent.set(sessionId, true); exportNow() }
                    }
                }) { Text("In Health Connect speichern") }
                if (consent.allowed(sessionId)) TextButton(onClick = {
                    Telemetry.track("health", "revoke_export")
                    consent.set(sessionId, false); message = "Weitere Exporte für dieses Training gestoppt"
                    scope.launch { recording?.let { publishHealthStatus(context, sessionId, it.version, "NOT_ENABLED") } }
                }) { Text("Weitere Exporte stoppen") }
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        } else Text("Testprofil: kein Gesundheitsexport", style = MaterialTheme.typography.bodySmall)
    }
}
