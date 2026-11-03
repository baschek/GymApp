package de.baschek.gymapp.ui.workout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.baschek.gymapp.core.model.Session
import de.baschek.gymapp.core.model.SetStatus
import de.baschek.gymapp.core.model.SetType
import de.baschek.gymapp.core.progression.totalVolume
import de.baschek.gymapp.telemetry.Telemetry
import de.baschek.gymapp.ui.MuscleDetailSheet
import de.baschek.gymapp.ui.MuscleShareSheet
import de.baschek.gymapp.ui.components.BadgeKind
import de.baschek.gymapp.ui.components.BodySide
import de.baschek.gymapp.ui.components.Group
import de.baschek.gymapp.ui.components.GymIcons
import de.baschek.gymapp.ui.components.ListRow
import de.baschek.gymapp.ui.components.Hairline
import de.baschek.gymapp.ui.components.MostLoadedCard
import de.baschek.gymapp.ui.components.MuscleMapCard
import de.baschek.gymapp.ui.components.PinnedBottom
import de.baschek.gymapp.ui.components.PrimaryButton
import de.baschek.gymapp.ui.components.SoftButton
import de.baschek.gymapp.ui.components.StatusBadge
import de.baschek.gymapp.ui.components.topInset
import de.baschek.gymapp.ui.fmtMinutes
import de.baschek.gymapp.ui.fmtNum
import de.baschek.gymapp.ui.gymLabel
import de.baschek.gymapp.ui.SyncKind
import de.baschek.gymapp.ui.theme.GymTextStyles
import de.baschek.gymapp.ui.theme.Spacing
import de.baschek.gymapp.ui.tracked
import de.baschek.gymapp.ui.fmtInt
import de.baschek.gymapp.ui.tracked1

private const val SCREEN = "zusammenfassung"

/** After the workout: volume, sets, new weights, the muscles trained today (shareable), sync state. */
@Composable
internal fun SummaryScreen(sessionId: String, session: Session, vm: WorkoutViewModel, onDone: () -> Unit) {
    val gyms by vm.gyms.collectAsStateWithLifecycle()
    val muscles by vm.todayMuscles.collectAsStateWithLifecycle()
    val newWeights by vm.newWeights.collectAsStateWithLifecycle()
    val sync by vm.sync.collectAsStateWithLifecycle()
    var side by rememberSaveable { mutableStateOf(BodySide.FRONT) }
    var detail by rememberSaveable { mutableStateOf<String?>(null) }
    var share by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) { Telemetry.track(SCREEN, "view") }

    val volume = session.exercises.sumOf { totalVolume(it) }
    val sets = session.exercises.sumOf { ex -> ex.sets.count { it.status == SetStatus.COMPLETED && it.setType == SetType.WORKING } }
    val minutes = ((session.durationSeconds ?: 0) / 60)

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(top = topInset() + 8.dp, start = Spacing.Gutter, end = Spacing.Gutter, bottom = 140.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Geschafft", style = MaterialTheme.typography.headlineLarge)
            Text(
                "${session.planName} · ${fmtMinutes(minutes)} · ${gymLabel(session.gymId, gyms)}",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Group {
                SummaryRow("Volumen", "${fmtInt(volume.toLong())} kg")
                Hairline()
                SummaryRow("Sätze", "$sets")
                Hairline()
                SummaryRow("Neue Gewichte", "$newWeights")
            }
            MuscleMapCard(
                "Trainierte Muskeln heute", muscles, side, { side = it },
                onMuscleTap = tracked1(SCREEN, "muscle") { detail = it },
            )
            MostLoadedCard(muscles, onMuscleTap = tracked1(SCREEN, "muscle_row") { detail = it })
            SoftButton("Karte teilen", tracked(SCREEN, "share_open") { share = true }, icon = GymIcons.Share)
            when (sync.kind) {
                SyncKind.OFFLINE, SyncKind.DISCONNECTED, SyncKind.NOT_PAIRED, SyncKind.TEST_LOCAL, SyncKind.UNAUTHORIZED ->
                    StatusBadge(BadgeKind.WARN, GymIcons.Offline, "Gespeichert, wird später synchronisiert")
                SyncKind.SYNCING -> StatusBadge(BadgeKind.NEUTRAL, GymIcons.Sync, "Gespeichert, wird synchronisiert")
                else -> StatusBadge(
                    BadgeKind.OK, GymIcons.Sync,
                    if (sync.pending > 0) "Gespeichert, wird synchronisiert" else "Synchronisiert",
                )
            }
        }
        PinnedBottom(Modifier.align(Alignment.BottomCenter)) {
            PrimaryButton("Fertig", tracked(SCREEN, "done", onDone), height = Spacing.TouchPrimary)
        }
    }

    detail?.let { group ->
        MuscleDetailSheet(group, muscles, "heute", onDismiss = { detail = null })
    }
    if (share) {
        MuscleShareSheet(
            title = "Trainierte Muskeln heute", subtitle = "${session.planName} · ${gymLabel(session.gymId, gyms)}",
            summary = muscles, onDismiss = { share = false },
        )
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    ListRow(label, trailing = { Text(value, style = GymTextStyles.number.copy(fontSize = MaterialTheme.typography.titleMedium.fontSize), color = MaterialTheme.colorScheme.onSurface) })
}
