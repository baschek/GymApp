package de.baschek.gymapp.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.lifecycle.viewmodel.compose.viewModel
import de.baschek.gymapp.core.model.SessionExercise
import de.baschek.gymapp.core.model.SetLog
import de.baschek.gymapp.core.model.SetStatus
import de.baschek.gymapp.core.model.SetType
import de.baschek.gymapp.core.progression.totalVolume
import de.baschek.gymapp.telemetry.Telemetry
import de.baschek.gymapp.ui.Navigator
import de.baschek.gymapp.ui.appViewModelFactory
import de.baschek.gymapp.ui.components.BackBar
import de.baschek.gymapp.ui.components.BadgeKind
import de.baschek.gymapp.ui.components.Banner
import de.baschek.gymapp.ui.components.GhostIconButton
import de.baschek.gymapp.ui.components.Group
import de.baschek.gymapp.ui.components.GymCardSurface
import de.baschek.gymapp.ui.components.GymIcons
import de.baschek.gymapp.ui.components.Hairline
import de.baschek.gymapp.ui.components.ListRow
import de.baschek.gymapp.ui.components.Overline
import de.baschek.gymapp.ui.components.PrimaryButton
import de.baschek.gymapp.ui.components.StepRow
import de.baschek.gymapp.ui.components.ScreenList
import de.baschek.gymapp.ui.components.SoftButton
import de.baschek.gymapp.ui.components.StatusBadge
import de.baschek.gymapp.ui.components.TickIcon
import de.baschek.gymapp.ui.fmtDayLong
import de.baschek.gymapp.ui.fmtInt
import de.baschek.gymapp.ui.fmtMinutes
import de.baschek.gymapp.ui.fmtNum
import de.baschek.gymapp.ui.gymChoices
import de.baschek.gymapp.ui.gymLabel
import de.baschek.gymapp.ui.parseInstant
import de.baschek.gymapp.ui.theme.GymTextStyles
import de.baschek.gymapp.ui.theme.Spacing
import de.baschek.gymapp.ui.tracked

private const val SCREEN = "verlauf_detail"

private fun effortLabel(effort: String?): String? = when (effort) {
    "limit" -> "Am Limit"
    "one", "one_two" -> "1–2 übrig"
    "two_three", "three_four" -> "3–4 übrig"
    "four_plus", "five_plus" -> "5+ locker"
    else -> null
}

private fun statusLabel(status: String): String? = when (status) {
    SetStatus.SKIPPED_TIME -> "übersprungen (Zeit)"
    SetStatus.SKIPPED_EQUIPMENT -> "übersprungen (Gerät besetzt)"
    SetStatus.SKIPPED_OTHER -> "übersprungen"
    SetStatus.PAIN -> "Schmerz"
    SetStatus.FAILED -> "nicht geschafft"
    else -> null
}

/** A finished workout: sets, edit mode with steppers, gym change and the "zu prüfen" flag. */
@Composable
fun SessionDetailScreen(sessionId: String, nav: Navigator) {
    val vm: SessionDetailViewModel = viewModel(key = "session-$sessionId", factory = appViewModelFactory { SessionDetailViewModel(it, sessionId) })
    val stored by vm.session.collectAsStateWithLifecycle()
    val gyms by vm.gyms.collectAsStateWithLifecycle()
    val activeGyms by vm.activeGyms.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()
    val machines by vm.machines.collectAsStateWithLifecycle()
    var editing by rememberSaveable { mutableStateOf(false) }
    var pendingGym by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { Telemetry.track(SCREEN, "view") }

    val session = stored?.value
    ScreenList {
        item {
            BackBar(
                session?.let { "${it.planName}${parseInstant(it.effectiveTime)?.let { t -> " · " + fmtDayLong(t).dropLast(5) } ?: ""}" } ?: "Training",
                onBack = tracked(SCREEN, "back") { nav.pop() },
            )
        }
        if (session == null) {
            item { Text("Dieses Training wurde nicht gefunden.", style = MaterialTheme.typography.bodyLarge) }
        } else {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            listOfNotNull(gymLabel(session.gymId, gyms), session.durationSeconds?.let { fmtMinutes(it / 60) }).joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "Volumen ${fmtInt(session.exercises.sumOf { totalVolume(it) }.toLong())} kg",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    SoftButton(
                        if (editing) "Fertig" else "Bearbeiten", tracked(SCREEN, if (editing) "edit_done" else "edit_start") { editing = !editing },
                        icon = if (editing) GymIcons.Check else GymIcons.Edit,
                    )
                }
            }
            if (stored != null && stored!!.id in pending) {
                item { StatusBadge(BadgeKind.WARN, GymIcons.Sync, "Bearbeitet, wird synchronisiert") }
            }
            if (!session.reviewFlags.isNullOrEmpty()) {
                item {
                    Banner(
                        BadgeKind.WARN, GymIcons.Warn, "Studio zu prüfen",
                        text = "Die Gewichte passen nicht sicher zu diesem Studio. Bitte prüfe das Studio unten.",
                    ) { de.baschek.gymapp.ui.components.LinkButton("Als geprüft markieren", tracked(SCREEN, "mark_reviewed", vm::markReviewed)) }
                }
            }
            session.exercises.forEach { ex ->
                item(key = ex.id) {
                    GymCardSurface {
                        Overline(ex.name, Modifier.padding(bottom = 4.dp))
                        ex.sets.forEachIndexed { i, set ->
                            if (i > 0) Hairline()
                            SetRow(ex, set, editing, onWeight = { d -> vm.stepWeight(ex.id, set.id, d, machines[ex.exerciseId]) }, onReps = { d -> vm.stepReps(ex.id, set.id, d) })
                        }
                    }
                }
            }
            item {
                Column {
                    Overline("Studio ändern", Modifier.padding(start = 4.dp, bottom = 8.dp))
                    Group {
                        val choices = gymChoices(activeGyms)
                        choices.forEachIndexed { i, id ->
                            if (i > 0) Hairline()
                            ListRow(
                                gymLabel(id, activeGyms), onClick = tracked(SCREEN, "pick_gym") { pendingGym = id },
                                trailing = { if ((pendingGym ?: session.gymId) == id) TickIcon() },
                            )
                        }
                    }
                    val target = pendingGym
                    if (target != null && target != session.gymId) {
                        Text(
                            "Die Arbeitsgewichte des alten und des neuen Studios werden neu berechnet.", Modifier.padding(top = 8.dp, start = 4.dp),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        PrimaryButton(
                            "Studio übernehmen: ${gymLabel(target, activeGyms)}",
                            tracked(SCREEN, "change_gym") { vm.changeGym(target); pendingGym = null },
                            Modifier.padding(top = Spacing.s3), height = 52.dp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SetRow(ex: SessionExercise, set: SetLog, editing: Boolean, onWeight: (Int) -> Unit, onReps: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val done = set.status == SetStatus.COMPLETED
    val label = if (set.setType == SetType.WARMUP) "Aufwärmen" else "Satz ${set.order}"
    val extra = if (done) effortLabel(set.effort) else statusLabel(set.status)
    if (editing && done) {
        Column(Modifier.fillMaxWidth().padding(vertical = Spacing.s2)) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            if (set.weightKg != null) {
                StepRow("Gewicht (kg)", fmtNum(set.weightKg!!), { onWeight(-1) }, { onWeight(1) }, minusDescription = "${ex.name}, $label: Gewicht verringern", plusDescription = "${ex.name}, $label: Gewicht erhöhen")
            }
            StepRow("Wiederholungen", "${set.reps ?: 0}", { onReps(-1) }, { onReps(1) }, minusEnabled = (set.reps ?: 0) > 0, minusDescription = "${ex.name}, $label: Wiederholungen verringern", plusDescription = "${ex.name}, $label: Wiederholungen erhöhen")
        }
    } else {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s2)) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyLarge)
                if (extra != null) {
                    Text(extra, style = MaterialTheme.typography.bodySmall, color = if (set.status == SetStatus.PAIN) MaterialTheme.colorScheme.error else cs.onSurfaceVariant)
                }
            }
            if (done) {
                val weight = set.weightKg?.let { fmtNum(it) + " kg × " }.orEmpty()
                Text(
                    weight + (set.reps ?: set.seconds ?: 0), style = GymTextStyles.number.copy(fontSize = MaterialTheme.typography.bodyLarge.fontSize),
                    color = cs.onSurface,
                )
            }
        }
    }
}
