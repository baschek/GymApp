package de.baschek.gymapp.ui.plans

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.baschek.gymapp.core.model.PlanExercise
import de.baschek.gymapp.telemetry.Telemetry
import de.baschek.gymapp.ui.Navigator
import de.baschek.gymapp.ui.appViewModelFactory
import de.baschek.gymapp.ui.components.BackBar
import de.baschek.gymapp.ui.components.BadgeKind
import de.baschek.gymapp.ui.components.Banner
import de.baschek.gymapp.ui.components.ExercisePickerSheet
import de.baschek.gymapp.ui.components.GhostIconButton
import de.baschek.gymapp.ui.components.Group
import de.baschek.gymapp.ui.components.GymField
import de.baschek.gymapp.ui.components.GymIcons
import de.baschek.gymapp.ui.components.GymSheet
import de.baschek.gymapp.ui.components.Hairline
import de.baschek.gymapp.ui.components.LinkButton
import de.baschek.gymapp.ui.components.PlanExerciseRow
import de.baschek.gymapp.ui.components.PrimaryButton
import de.baschek.gymapp.ui.components.ScreenList
import de.baschek.gymapp.ui.components.SegmentItem
import de.baschek.gymapp.ui.components.Segmented
import de.baschek.gymapp.ui.components.StepRow
import de.baschek.gymapp.ui.fmtNum
import de.baschek.gymapp.ui.fmtDay
import de.baschek.gymapp.ui.parseInstant
import de.baschek.gymapp.ui.theme.Spacing
import de.baschek.gymapp.ui.tracked

private const val SCREEN = "plan_editor"

private fun summary(e: PlanExercise): String =
    "N ${e.modeSets.normal} · S ${e.modeSets.fast} · U ${e.modeSets.ultra} · ${e.target.min.toInt()}–${e.target.max.toInt()} Wdh." +
        (e.startingWeightKg ?: e.currentWeightKg)?.let { " · ${fmtNum(it)} kg" }.orEmpty()

/** Plan editor: exercises with up/down arrows, a stepper sheet per exercise, the assistant notice. */
@Composable
fun PlanEditorScreen(planId: String, nav: Navigator) {
    val vm: PlanEditorViewModel = viewModel(key = "plan-$planId", factory = appViewModelFactory { PlanEditorViewModel(it, planId) })
    val stored by vm.plan.collectAsStateWithLifecycle()
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val seen by vm.seen.collectAsStateWithLifecycle()
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    var picking by rememberSaveable { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var confirmArchive by rememberSaveable { mutableStateOf(false) }

    val plan = stored?.value
    // The notice stays visible during this visit; it is marked as seen when the plan has been opened once.
    val changedAt = plan?.changedByAssistantAt
    val showNotice = remember(planId, changedAt != null) { changedAt != null && seen[planId] != changedAt }
    var noticeHidden by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(changedAt) {
        Telemetry.track(SCREEN, "view")
        if (changedAt != null) vm.markSeen(changedAt)
    }

    val names = catalog.associate { it.id to it.value.name }

    ScreenList {
        item {
            BackBar(
                plan?.name ?: "Plan", onBack = tracked(SCREEN, "back") { nav.pop() },
                trailing = { GhostIconButton(GymIcons.Edit, "Plan umbenennen", tracked(SCREEN, "rename_open") { renaming = true }) },
            )
        }
        if (plan == null) {
            item { Text("Dieser Plan wurde nicht gefunden.", style = MaterialTheme.typography.bodyLarge) }
        } else {
            if (showNotice && !noticeHidden) {
                item {
                    val day = parseInstant(changedAt)?.let { fmtDay(it) }
                    Banner(
                        BadgeKind.NEUTRAL, GymIcons.Edit, "Vom Assistenten geändert" + (day?.let { " am $it" } ?: ""),
                        text = plan.changeSummary,
                    ) { LinkButton("Gesehen", tracked(SCREEN, "notice_seen") { noticeHidden = true }) }
                }
            }
            val sorted = plan.exercises.sortedBy { it.order }
            item {
                Group {
                    if (sorted.isEmpty()) {
                        Text(
                            "Dieser Plan hat noch keine Übungen.", Modifier.padding(Spacing.s4), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    sorted.forEachIndexed { i, e ->
                        if (i > 0) Hairline()
                        PlanExerciseRow(
                            name = names[e.exerciseId] ?: "Unbekannte Übung",
                            summary = summary(e),
                            supersetTag = e.supersetGroup?.takeIf { it.isNotBlank() },
                            canMoveUp = i > 0, canMoveDown = i < sorted.lastIndex,
                            onMoveUp = tracked(SCREEN, "move_up") { vm.move(e.id, -1) },
                            onMoveDown = tracked(SCREEN, "move_down") { vm.move(e.id, 1) },
                            onClick = tracked(SCREEN, "open_exercise") { openId = e.id },
                        )
                    }
                    Hairline()
                    LinkButton("Übung hinzufügen", tracked(SCREEN, "add_exercise") { picking = true }, Modifier.fillMaxWidth(), height = 52.dp)
                }
            }
            item {
                Text(
                    "Reihenfolge mit den Pfeilen ändern. Änderungen werden synchronisiert.", Modifier.padding(horizontal = 4.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item { LinkButton("Plan archivieren", tracked(SCREEN, "archive_open") { confirmArchive = true }, color = MaterialTheme.colorScheme.error) }
        }
    }

    val open = plan?.exercises?.firstOrNull { it.id == openId }
    if (open != null) {
        PlanExerciseSheet(
            title = names[open.exerciseId] ?: "Übung", e = open, onEdit = { vm.edit(open.id, it) },
            onRemove = { vm.remove(open.id); openId = null }, onDismiss = { openId = null },
        )
    }
    if (picking) {
        ExercisePickerSheet(catalog, onPick = { picked -> picking = false; vm.add(picked.id) { openId = it } }, onDismiss = { picking = false })
    }
    if (renaming && plan != null) {
        var name by remember { mutableStateOf(plan.name) }
        GymSheet({ renaming = false }, title = "Plan umbenennen") {
            GymField(name, { name = it }, "Name")
            PrimaryButton("Speichern", tracked(SCREEN, "rename_save") { vm.rename(name); renaming = false }, height = 52.dp)
        }
    }
    if (confirmArchive) {
        AlertDialog(
            onDismissRequest = { confirmArchive = false },
            title = { Text("Plan archivieren") },
            text = { Text("Der Plan verschwindet aus den Listen. Bisherige Trainings bleiben im Verlauf.") },
            confirmButton = {
                TextButton(onClick = tracked(SCREEN, "archive_confirm") { confirmArchive = false; vm.archive(); nav.pop() }) { Text("Archivieren") }
            },
            dismissButton = { TextButton(onClick = tracked(SCREEN, "archive_cancel") { confirmArchive = false }) { Text("Abbrechen") } },
        )
    }
}

/** Steppers for one plan exercise: sets per mode, rep range, reserve, rest, superset, starting weight. */
@Composable
private fun PlanExerciseSheet(title: String, e: PlanExercise, onEdit: ((PlanExercise) -> PlanExercise) -> Unit, onRemove: () -> Unit, onDismiss: () -> Unit) {
    GymSheet(onDismiss, title = title) {
        SetsRow("Sätze Normal", e.modeSets.normal) { v -> onEdit { it.copy(modeSets = it.modeSets.copy(normal = v)) } }
        SetsRow("Sätze Schnell", e.modeSets.fast) { v -> onEdit { it.copy(modeSets = it.modeSets.copy(fast = v)) } }
        SetsRow("Sätze Ultra", e.modeSets.ultra) { v -> onEdit { it.copy(modeSets = it.modeSets.copy(ultra = v)) } }
        val min = e.target.min.toInt()
        val max = e.target.max.toInt()
        StepRow(
            "Wdh. von", "$min",
            onMinus = tracked(SCREEN, "step") { onEdit { it.copy(target = it.target.copy(min = (min - 1).coerceAtLeast(1).toDouble())) } },
            onPlus = tracked(SCREEN, "step") {
                onEdit { it.copy(target = it.target.copy(min = (min + 1).toDouble(), max = maxOf(max, min + 1).toDouble())) }
            },
        )
        StepRow(
            "Wdh. bis", "$max",
            onMinus = tracked(SCREEN, "step") {
                onEdit { it.copy(target = it.target.copy(max = (max - 1).coerceAtLeast(1).toDouble(), min = minOf(min, max - 1).coerceAtLeast(1).toDouble())) }
            },
            onPlus = tracked(SCREEN, "step") { onEdit { it.copy(target = it.target.copy(max = (max + 1).coerceAtMost(60).toDouble())) } },
        )
        val rirMin = e.target.rirMin.toInt()
        val rirMax = e.target.rirMax.toInt()
        StepRow(
            "Reserve von (RIR)", "$rirMin",
            onMinus = tracked(SCREEN, "step") { onEdit { it.copy(target = it.target.copy(rirMin = (rirMin - 1).coerceAtLeast(0).toDouble())) } },
            onPlus = tracked(SCREEN, "step") {
                onEdit { it.copy(target = it.target.copy(rirMin = (rirMin + 1).coerceAtMost(5).toDouble(), rirMax = maxOf(rirMax, rirMin + 1).coerceAtMost(5).toDouble())) }
            },
        )
        StepRow(
            "Reserve bis (RIR)", "$rirMax",
            onMinus = tracked(SCREEN, "step") {
                onEdit { it.copy(target = it.target.copy(rirMax = (rirMax - 1).coerceAtLeast(0).toDouble(), rirMin = minOf(rirMin, rirMax - 1).coerceAtLeast(0).toDouble())) }
            },
            onPlus = tracked(SCREEN, "step") { onEdit { it.copy(target = it.target.copy(rirMax = (rirMax + 1).coerceAtMost(5).toDouble())) } },
        )
        StepRow(
            "Pause (s)", "${e.restSeconds}",
            onMinus = tracked(SCREEN, "step") { onEdit { it.copy(restSeconds = (e.restSeconds - 15).coerceAtLeast(15)) } },
            onPlus = tracked(SCREEN, "step") { onEdit { it.copy(restSeconds = (e.restSeconds + 15).coerceAtMost(600)) } },
        )
        val start = e.startingWeightKg ?: e.currentWeightKg
        StepRow(
            "Startgewicht (kg)", start?.let { fmtNum(it) } ?: "–",
            onMinus = tracked(SCREEN, "step") { onEdit { it.copy(startingWeightKg = ((start ?: 0.0) - 2.5).coerceAtLeast(0.0)) } },
            onPlus = tracked(SCREEN, "step") { onEdit { it.copy(startingWeightKg = ((start ?: 0.0) + 2.5).coerceAtMost(300.0)) } },
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Superset", style = MaterialTheme.typography.bodyLarge)
            val groups = listOf("", "A", "B")
            Segmented(
                groups.map { SegmentItem(it.ifEmpty { "–" }) },
                groups.indexOf(e.supersetGroup.orEmpty()).coerceAtLeast(0),
                { i -> Telemetry.track(SCREEN, "superset"); onEdit { it.copy(supersetGroup = groups[i].ifEmpty { null }) } },
            )
        }
        LinkButton("Übung entfernen", tracked(SCREEN, "remove_exercise", onRemove), color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun SetsRow(label: String, value: Int, onChange: (Int) -> Unit) {
    StepRow(
        label, "$value",
        onMinus = tracked(SCREEN, "step") { onChange((value - 1).coerceAtLeast(0)) },
        onPlus = tracked(SCREEN, "step") { onChange((value + 1).coerceAtMost(8)) },
        minusEnabled = value > 0,
    )
}
