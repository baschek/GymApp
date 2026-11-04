package de.baschek.gymapp.ui.workout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import de.baschek.gymapp.core.training.currentExerciseIndex
import de.baschek.gymapp.telemetry.Telemetry
import de.baschek.gymapp.timer.TimerState
import de.baschek.gymapp.ui.components.ExercisePickerSheet
import de.baschek.gymapp.ui.components.Overline
import de.baschek.gymapp.ui.components.PinnedBottom
import de.baschek.gymapp.ui.components.PrimaryButton
import de.baschek.gymapp.ui.components.RestTimerCard
import de.baschek.gymapp.ui.components.topInset
import de.baschek.gymapp.ui.fmtNum
import de.baschek.gymapp.ui.theme.Spacing
import de.baschek.gymapp.ui.tracked
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
    val now = rememberNow(250)
    var pickExercise by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) { Telemetry.track(SCREEN, "view") }

    val suggested = timer.suggestedRestSeconds
    val elapsed = timer.elapsedSeconds(now)
    val remaining = if (suggested > 0) ceil(((timer.endsAtMillis ?: now) - now) / 1000.0).toInt() else elapsed
    val progress = if (suggested > 0) elapsed.toFloat() / suggested else 0f

    // Vibration at the end of the rest is done by the Doze-safe alarm (timer/RestAlarmReceiver), not here.

    val exIndex = currentExerciseIndex(session.exercises)
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
    val nextText = when {
        nextEx == null || nextSet == null -> null
        newExercise -> listOfNotNull(nextEx.name, machineSetting(machines[nextEx.exerciseId])).joinToString(": ")
        else -> {
            val last = nextEx.sets.lastOrNull { it.status == SetStatus.COMPLETED && it.setType == SetType.WORKING }
            val weight = last?.weightKg ?: nextEx.suggestedWeightKg
            val reps = last?.reps ?: nextEx.target.max.toInt()
            if (weight != null) "Dann: ${fmtNum(weight)} kg × $reps" else "Dann: $reps Wdh."
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
                .padding(top = topInset() + 8.dp, start = Spacing.Gutter, end = Spacing.Gutter, bottom = 140.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            RestTimerCard(
                caption = caption, remainingSeconds = remaining, progress = progress, nextText = nextText,
                onPlus15 = tracked(SCREEN, "plus15", vm::plus15), badge = badge,
            )
            Overline("Noch im Workout", Modifier.padding(start = 4.dp))
            ExerciseListCard(
                session, editable = true, onMove = vm::moveExercise, onToggleSkip = vm::toggleSkipExercise,
                onAdd = { pickExercise = true }, screen = SCREEN,
            )
        }
        PinnedBottom(Modifier.align(Alignment.BottomCenter)) {
            PrimaryButton("Weiter", tracked(SCREEN, "next", vm::skipRest), height = Spacing.TouchPrimary)
        }
    }
    if (pickExercise) {
        ExercisePickerSheet(catalog, onPick = { vm.addExercise(it.id); pickExercise = false }, onDismiss = { pickExercise = false })
    }
}
