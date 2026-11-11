package de.baschek.gymapp.ui.workout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.baschek.gymapp.core.model.Session
import de.baschek.gymapp.core.model.SetStatus
import de.baschek.gymapp.core.model.SetType
import de.baschek.gymapp.core.stats.estimateTimeLeft
import de.baschek.gymapp.core.stats.finishClock
import de.baschek.gymapp.core.training.canAddSet
import de.baschek.gymapp.core.training.upNextExerciseIndex
import de.baschek.gymapp.core.training.weightHint
import de.baschek.gymapp.telemetry.Telemetry
import de.baschek.gymapp.timer.TimerState
import de.baschek.gymapp.ui.components.ExercisePickerSheet
import de.baschek.gymapp.ui.components.GymCardSurface
import de.baschek.gymapp.ui.components.GymIcons
import de.baschek.gymapp.ui.components.LinkButton
import de.baschek.gymapp.ui.components.Overline
import de.baschek.gymapp.ui.components.PinnedBottom
import de.baschek.gymapp.ui.components.PrimaryButton
import de.baschek.gymapp.ui.components.RestTimerCard
import de.baschek.gymapp.ui.components.SoftButton
import de.baschek.gymapp.ui.components.topInset
import de.baschek.gymapp.ui.fmtNum
import de.baschek.gymapp.ui.theme.GymTextStyles
import de.baschek.gymapp.ui.theme.Spacing
import de.baschek.gymapp.ui.tracked
import java.time.Instant
import kotlin.math.ceil

private const val SCREEN = "pause"

/**
 * Rest between sets: the large timer card on top (countdown, progress, next set, +15 s) and below it the
 * editable list "Noch im Workout" (up/down/skip per exercise, add an exercise). Changes are saved in the session.
 */
@Composable
internal fun RestScreen(session: Session, timer: TimerState, vm: WorkoutViewModel) {
    val machines by vm.machines.collectAsStateWithLifecycle()
    val previous by vm.previousWeights.collectAsStateWithLifecycle()
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val focus by vm.focus.collectAsStateWithLifecycle()
    val undoLabel by vm.undoLabel.collectAsStateWithLifecycle()
    val accepted by vm.acceptedWeight.collectAsStateWithLifecycle()
    val repsOverride by vm.repsOverride.collectAsStateWithLifecycle()
    val bodyWeight by vm.bodyWeight.collectAsStateWithLifecycle()
    val now = rememberNow(250)
    var pickExercise by rememberSaveable { mutableStateOf(false) }
    var setupOpen by rememberSaveable { mutableStateOf(false) }
    var showTimeLeft by rememberSaveable { mutableStateOf(false) }
    val timingModel by vm.timingModel.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { Telemetry.track(SCREEN, "view") }

    val suggested = timer.suggestedRestSeconds
    val elapsed = timer.elapsedSeconds(now)
    val remaining = if (suggested > 0) ceil(((timer.endsAtMillis ?: now) - now) / 1000.0).toInt() else elapsed
    val progress = if (suggested > 0) elapsed.toFloat() / suggested else 0f

    // Vibration at the end of the rest is done by the Doze-safe alarm (timer/RestAlarmReceiver), not here.

    val exIndex = upNextExerciseIndex(session.exercises, focus)
    val nextEx = session.exercises.getOrNull(exIndex)
    val nextSet = nextEx?.sets?.firstOrNull { it.status == SetStatus.PENDING }
    val lastLogged = session.exercises.flatMap { ex -> ex.sets.map { ex to it } }
        .filter { it.second.status == SetStatus.COMPLETED && it.second.completedAt != null }
        .maxByOrNull { it.second.completedAt!! }
    val newExercise = nextEx != null && lastLogged != null && lastLogged.first.id != nextEx.id

    val working = nextEx?.sets?.filter { it.setType == SetType.WORKING }.orEmpty()
    val caption = when {
        nextEx == null || nextSet == null -> "Pause"
        newExercise -> "Gerät einstellen"
        nextSet.setType == SetType.WARMUP -> "Pause · als Nächstes Aufwärmsatz"
        else -> "Pause · als Nächstes Satz ${working.count { it.status != SetStatus.PENDING } + 1} von ${working.size}"
    }
    val nextInfo = nextEx?.let { machines[it.exerciseId] }
    val acceptedForNext = accepted?.takeIf { nextEx != null && it.first == nextEx.id }?.second
    val hint = if (nextEx == null || nextSet == null || nextSet.setType == SetType.WARMUP) null
    else weightHint(nextEx, nextInfo?.candidates.orEmpty(), nextInfo?.workingWeightKg, bodyWeight)
    val nextText = when {
        nextEx == null || nextSet == null -> null
        newExercise -> nextEx.name
        else -> {
            val last = nextEx.sets.lastOrNull { it.status == SetStatus.COMPLETED && it.setType == SetType.WORKING }
            val weight = acceptedForNext ?: nextSet.weightKg?.takeIf { nextSet.setType == SetType.WORKING } ?: last?.weightKg ?: nextEx.suggestedWeightKg
            val lastWeight = last?.weightKg
            val arrow = if (acceptedForNext != null && lastWeight != null) {
                when { acceptedForNext > lastWeight + 1e-6 -> " ▲"; acceptedForNext < lastWeight - 1e-6 -> " ▼"; else -> "" }
            } else ""
            val reps = vm.repsFor(repsOverride, nextEx.id, nextSet.id)?.toInt() ?: last?.reps ?: nextEx.target.max.toInt()
            if (weight != null) "Dann: ${fmtNum(weight)} kg$arrow × $reps" else "Dann: $reps Wdh."
        }
    }
    val badge = lastLogged?.let { (ex, set) ->
        val prev = previous[ex.exerciseId]
        val firstWorking = ex.sets.filter { it.setType == SetType.WORKING && it.status == SetStatus.COMPLETED }.size == 1
        val w = set.weightKg
        if (prev != null && w != null && firstWorking && set.setType == SetType.WORKING && w > prev + 1e-6) "Neues Gewicht: +${fmtNum(w - prev)} kg" else null
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(top = topInset() + 8.dp, start = Spacing.Gutter, end = Spacing.Gutter, bottom = 190.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            RestTimerCard(
                caption = caption, remainingSeconds = remaining, progress = progress, nextText = nextText,
                onPlus15 = tracked(SCREEN, "plus15", vm::plus15), badge = badge,
            )
            // "Wie lange noch?": hidden until asked for (tap to reveal, tap again to hide)
            if (showTimeLeft) {
                val left = remember(timingModel, session, now / 1000, nextEx?.id) {
                    estimateTimeLeft(timingModel, session, Instant.ofEpochMilli(now), nextEx?.id)
                }
                GymCardSurface(Modifier.fillMaxWidth(), onClick = tracked(SCREEN, "time_left_hide") { showTimeLeft = false }) {
                    Overline("Noch zu trainieren")
                    Text(
                        left.text(), Modifier.padding(top = 4.dp),
                        style = GymTextStyles.number.copy(fontSize = MaterialTheme.typography.headlineMedium.fontSize),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (left.pendingSets > 0) {
                        Text(
                            "Fertig gegen ${finishClock(Instant.ofEpochMilli(now), left.seconds)} Uhr · ${left.pendingSets} Sätze in ${left.exercisesRemaining} Übungen",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        listOfNotNull(left.paceNote(), left.basis()).joinToString(" "),
                        Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                SoftButton(
                    "Wie lange noch?", tracked(SCREEN, "time_left_show") { showTimeLeft = true }, Modifier.fillMaxWidth(),
                    icon = GymIcons.History, height = Spacing.TouchMin,
                )
            }
            if (lastLogged != null && canAddSet(session, lastLogged.first.id)) {
                SoftButton(
                    "Noch ein Satz: ${lastLogged.first.name}", tracked(SCREEN, "add_set") { vm.addSet(lastLogged.first.id) }, Modifier.fillMaxWidth(),
                    icon = GymIcons.Plus, height = Spacing.TouchMin,
                )
            }
            if (nextEx != null && nextSet != null) {
                if (newExercise) SetupLine(nextInfo?.setup, tracked(SCREEN, "setup_open") { setupOpen = true }, Modifier.padding(horizontal = 4.dp))
                if (hint != null) {
                    WeightHintBlock(
                        hint, applied = acceptedForNext != null && kotlin.math.abs(acceptedForNext - hint.weightKg) < 1e-6,
                        onApply = if (hint.trend == 0) null else ({ vm.acceptWeight(nextEx.id, hint.weightKg) }),
                        screen = SCREEN, modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
            Overline("Noch im Workout · antippen zum Springen", Modifier.padding(start = 4.dp))
            ExerciseListCard(
                session, editable = true, onMove = vm::moveExercise, onToggleSkip = vm::toggleSkipExercise,
                onAdd = { pickExercise = true }, screen = SCREEN,
                onJump = vm::focusExercise, focusedId = nextEx?.id, onRemove = vm::removeExercise,
            )
        }
        PinnedBottom(Modifier.align(Alignment.BottomCenter)) {
            PrimaryButton("Weiter", tracked(SCREEN, "next", vm::skipRest), height = Spacing.TouchPrimary)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                LinkButton(
                    undoLabel?.let { "Rückgängig: $it" } ?: "Rückgängig", tracked(SCREEN, "undo") { vm.undo() },
                    height = 48.dp, enabled = undoLabel != null,
                )
            }
        }
    }
    if (setupOpen && nextEx != null) {
        SetupSheet(
            exerciseName = nextEx.name, initial = nextInfo?.setup, screen = SCREEN,
            onSave = { params -> session.gymId?.let { vm.saveSetup(it, nextEx.exerciseId, params) }; setupOpen = false },
            onDismiss = { setupOpen = false },
        )
    }
    if (pickExercise) {
        ExercisePickerSheet(catalog, onPick = { vm.addExercise(it.id); pickExercise = false }, onDismiss = { pickExercise = false })
    }
}
