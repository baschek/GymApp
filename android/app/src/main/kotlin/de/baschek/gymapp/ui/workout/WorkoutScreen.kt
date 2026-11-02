@file:OptIn(ExperimentalMaterial3Api::class)

package de.baschek.gymapp.ui.workout

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.baschek.gymapp.R
import de.baschek.gymapp.core.model.MeasurementType
import de.baschek.gymapp.core.model.Metric
import de.baschek.gymapp.core.model.SelectableEffortBands
import de.baschek.gymapp.core.model.SessionExercise
import de.baschek.gymapp.core.model.SetLog
import de.baschek.gymapp.core.model.SetStatus
import de.baschek.gymapp.telemetry.Telemetry
import de.baschek.gymapp.timer.TimerPhase
import de.baschek.gymapp.timer.TimerState
import de.baschek.gymapp.ui.appViewModelFactory
import kotlinx.coroutines.delay

private val WEIGHTED = setOf(
    MeasurementType.LOAD_REPS, MeasurementType.ADDED_WEIGHT_REPS, MeasurementType.ASSISTED_REPS,
    MeasurementType.LOAD_DURATION, MeasurementType.LOAD_DISTANCE_DURATION,
)

/** Placeholder workout screen. Logic (timers, saving, progression) is in [WorkoutViewModel]/the repository. */
@Composable
fun WorkoutScreen(sessionId: String, onClose: () -> Unit) {
    val vm: WorkoutViewModel = viewModel(key = "workout-$sessionId", factory = appViewModelFactory { WorkoutViewModel(it, sessionId) })
    val load by vm.load.collectAsStateWithLifecycle()
    val weights by vm.weights.collectAsStateWithLifecycle()
    val timer by vm.timer.collectAsStateWithLifecycle()
    val keepOn by vm.keepScreenOn.collectAsStateWithLifecycle()
    var confirmFinish by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        Telemetry.track("workout", "view")
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val view = LocalView.current
    DisposableEffect(keepOn) {
        view.keepScreenOn = keepOn
        onDispose { view.keepScreenOn = false }
    }

    when (val l = load) {
        SessionLoad.Loading -> Unit
        SessionLoad.Missing -> Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.workout_missing))
            TextButton(onClick = onClose) { Text(stringResource(R.string.common_back)) }
        }
        is SessionLoad.Ready -> {
            val session = l.session
            val readOnly = session.status != "active"
            val current = session.exercises.firstOrNull { ex -> ex.sets.any { it.status == SetStatus.PENDING } }
            LazyColumn(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { Text(stringResource(R.string.workout_title, session.planName), style = MaterialTheme.typography.headlineSmall) }
                if (!readOnly) {
                    item {
                        TimerBar(
                            timer = timer,
                            onStart = { vm.startTimer(it, current) },
                            onStop = vm::stopTimer,
                        )
                    }
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Switch(checked = keepOn, onCheckedChange = vm::setKeepScreenOn)
                            Text(stringResource(R.string.workout_keep_screen_on))
                        }
                    }
                }
                items(session.exercises, key = { it.id }) { ex ->
                    ExerciseCard(
                        ex = ex,
                        weights = weights[ex.exerciseId].orEmpty(),
                        editable = !readOnly,
                        onLog = { setId, input -> vm.logSet(ex.id, setId, input) },
                    )
                }
                if (!readOnly) {
                    item {
                        Button(onClick = { confirmFinish = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.workout_finish))
                        }
                    }
                } else {
                    item { TextButton(onClick = onClose) { Text(stringResource(R.string.common_back)) } }
                }
            }
            if (confirmFinish) {
                AlertDialog(
                    onDismissRequest = { confirmFinish = false },
                    title = { Text(stringResource(R.string.workout_finish)) },
                    text = { Text(stringResource(R.string.workout_finish_confirm)) },
                    confirmButton = {
                        TextButton(onClick = { confirmFinish = false; vm.finish(onClose) }) { Text(stringResource(R.string.common_ok)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { confirmFinish = false }) { Text(stringResource(R.string.common_cancel)) }
                    },
                )
            }
        }
    }
}

@Composable
private fun TimerBar(timer: TimerState?, onStart: (TimerPhase) -> Unit, onStop: () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(timer?.startedAtMillis) {
        while (timer != null) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (timer == null) {
                Text(stringResource(R.string.workout_timer_idle))
            } else {
                val seconds = timer.elapsedSeconds(now)
                val name = when (timer.phase) {
                    TimerPhase.SET -> R.string.workout_timer_set
                    TimerPhase.REST -> R.string.workout_timer_rest
                    TimerPhase.MACHINE_SETUP -> R.string.workout_timer_setup
                }
                Text("${stringResource(name)}  %d:%02d".format(seconds / 60, seconds % 60), style = MaterialTheme.typography.titleLarge)
                if (timer.phase == TimerPhase.REST && timer.suggestedRestSeconds > 0) {
                    Text(stringResource(R.string.workout_suggested_rest, "%d:%02d".format(timer.suggestedRestSeconds / 60, timer.suggestedRestSeconds % 60)))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onStart(TimerPhase.SET) }) { Text(stringResource(R.string.workout_timer_start_set)) }
                OutlinedButton(onClick = { onStart(TimerPhase.REST) }) { Text(stringResource(R.string.workout_timer_start_rest)) }
                OutlinedButton(onClick = { onStart(TimerPhase.MACHINE_SETUP) }) { Text(stringResource(R.string.workout_timer_start_setup)) }
                if (timer != null) TextButton(onClick = onStop) { Text(stringResource(R.string.workout_timer_stop)) }
            }
        }
    }
}

@Composable
private fun ExerciseCard(ex: SessionExercise, weights: List<Double>, editable: Boolean, onLog: (String, SetInput) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(ex.name, style = MaterialTheme.typography.titleMedium)
            Text(targetText(ex), style = MaterialTheme.typography.bodySmall)
            ex.sets.filter { it.status != SetStatus.PENDING }.forEach { set -> Text(doneText(ex, set)) }
            val next = ex.sets.firstOrNull { it.status == SetStatus.PENDING }
            if (editable && next != null) SetEntry(ex, next, weights, onLog)
        }
    }
}

@Composable
private fun targetText(ex: SessionExercise): String {
    val min = ex.target.min.toInt()
    val max = ex.target.max.toInt()
    return when (ex.target.metric) {
        Metric.SECONDS -> stringResource(R.string.workout_target_seconds, min, max)
        Metric.METERS -> stringResource(R.string.workout_target_meters, min, max)
        else -> stringResource(R.string.workout_target_reps, min, max)
    }
}

@Composable
private fun doneText(ex: SessionExercise, set: SetLog): String {
    val label = stringResource(R.string.workout_set_label, set.order)
    if (set.status != SetStatus.COMPLETED) return "$label: ${stringResource(R.string.workout_set_skipped)}"
    val value = when (ex.target.metric) {
        Metric.SECONDS -> set.seconds?.toString()
        Metric.METERS -> set.meters?.let(::fmt)
        else -> set.reps?.toString()
    } ?: "-"
    val detail = if (set.weightKg != null) stringResource(R.string.workout_set_done, fmt(set.weightKg!!), value)
    else stringResource(R.string.workout_set_done_nokg, value)
    return "$label: $detail"
}

@Composable
private fun SetEntry(ex: SessionExercise, set: SetLog, weights: List<Double>, onLog: (String, SetInput) -> Unit) {
    val weighted = ex.measurementType in WEIGHTED
    val lastWeight = ex.sets.lastOrNull { it.status == SetStatus.COMPLETED && it.weightKg != null }?.weightKg
    val startWeight = lastWeight ?: ex.suggestedWeightKg ?: weights.firstOrNull()
    var weight by remember(set.id) { mutableStateOf(startWeight?.let(::fmt).orEmpty()) }
    var value by remember(set.id) { mutableStateOf(fmt(ex.target.max)) }
    var effort by remember(set.id) { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.workout_set_label, set.order), style = MaterialTheme.typography.titleSmall)
        if (weighted) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { weight = fmt(stepWeight(parse(weight), weights, -1)) }) { Text("−") }
                OutlinedTextField(
                    value = weight, onValueChange = { weight = it }, singleLine = true,
                    label = { Text(stringResource(R.string.workout_weight)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = { weight = fmt(stepWeight(parse(weight), weights, 1)) }) { Text("+") }
            }
        }
        OutlinedTextField(
            value = value, onValueChange = { value = it }, singleLine = true,
            label = {
                Text(
                    stringResource(
                        when (ex.target.metric) {
                            Metric.SECONDS -> R.string.workout_seconds
                            Metric.METERS -> R.string.workout_meters
                            else -> R.string.workout_reps
                        },
                    ),
                )
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(stringResource(R.string.workout_effort), style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SelectableEffortBands.forEach { band ->
                FilterChip(selected = effort == band, onClick = { effort = band }, label = { Text(effortLabel(band)) })
            }
        }
        Button(
            onClick = { onLog(set.id, SetInput(if (weighted) parse(weight) else null, parse(value), effort)) },
            enabled = effort != null && parse(value) != null && (!weighted || parse(weight) != null),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.workout_save_set)) }
    }
}

@Composable
private fun effortLabel(band: String): String = stringResource(
    when (band) {
        "limit" -> R.string.effort_limit
        "one_two" -> R.string.effort_one_two
        "three_four" -> R.string.effort_three_four
        else -> R.string.effort_five_plus
    },
)

private fun parse(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()

private fun fmt(value: Double): String =
    if (value == Math.floor(value) && Math.abs(value) < 1e9) value.toLong().toString() else value.toString()

/** Moves to the neighbouring available weight, or by 2.5 kg when the machine has no list. */
private fun stepWeight(current: Double?, weights: List<Double>, direction: Int): Double {
    val base = current ?: weights.firstOrNull() ?: 0.0
    if (weights.isEmpty()) return (base + 2.5 * direction).coerceAtLeast(0.0)
    val sorted = weights.sorted()
    return if (direction > 0) sorted.firstOrNull { it > base + 1e-9 } ?: sorted.last()
    else sorted.lastOrNull { it < base - 1e-9 } ?: sorted.first()
}
