package de.baschek.gymapp.ui.workout

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.baschek.gymapp.core.model.Metric
import de.baschek.gymapp.core.live.LiveUpdateStatus
import de.baschek.gymapp.timer.LiveUpdateSupport
import de.baschek.gymapp.core.model.SelectableEffortBands
import de.baschek.gymapp.core.model.Session
import de.baschek.gymapp.core.model.SessionExercise
import de.baschek.gymapp.core.model.SessionStatus
import de.baschek.gymapp.core.model.SetLog
import de.baschek.gymapp.core.model.SetStatus
import de.baschek.gymapp.core.model.SetType
import de.baschek.gymapp.core.progression.WEIGHTED_TYPES
import de.baschek.gymapp.core.progression.freeWeights
import de.baschek.gymapp.core.progression.nearestLevelIndex
import de.baschek.gymapp.core.progression.splitTotal
import de.baschek.gymapp.core.training.SkipReason
import de.baschek.gymapp.core.training.currentExerciseIndex
import de.baschek.gymapp.core.training.warmupProposal
import de.baschek.gymapp.data.MachineInfo
import de.baschek.gymapp.telemetry.Telemetry
import de.baschek.gymapp.timer.TimerPhase
import de.baschek.gymapp.ui.Navigator
import de.baschek.gymapp.ui.Routes
import de.baschek.gymapp.ui.appViewModelFactory
import de.baschek.gymapp.ui.components.AddonOption
import de.baschek.gymapp.ui.components.EffortPicker
import de.baschek.gymapp.ui.components.ExercisePickerSheet
import de.baschek.gymapp.ui.components.GhostIconButton
import de.baschek.gymapp.ui.components.GymIcons
import de.baschek.gymapp.ui.components.GymField
import de.baschek.gymapp.ui.components.GymSheet
import de.baschek.gymapp.ui.components.LinkButton
import de.baschek.gymapp.ui.components.PinnedBottom
import de.baschek.gymapp.ui.components.PrimaryButton
import de.baschek.gymapp.ui.components.RepsCard
import de.baschek.gymapp.ui.components.SetMeter
import de.baschek.gymapp.ui.components.SoftButton
import de.baschek.gymapp.ui.components.Tag
import de.baschek.gymapp.ui.components.WeightCard
import de.baschek.gymapp.ui.components.formatClock
import de.baschek.gymapp.ui.components.topInset
import de.baschek.gymapp.ui.fmtNum
import de.baschek.gymapp.ui.gymColorOf
import de.baschek.gymapp.ui.gymLabel
import de.baschek.gymapp.ui.parseInstant
import de.baschek.gymapp.ui.theme.GymTextStyles
import de.baschek.gymapp.ui.theme.Spacing
import de.baschek.gymapp.ui.tracked
import de.baschek.gymapp.workout.SetInput
import kotlinx.coroutines.delay

private const val SCREEN = "training"

/** The running (or just finished) workout: training, rest and summary. Logic lives in [WorkoutViewModel]. */
@Composable
fun WorkoutScreen(sessionId: String, nav: Navigator) {
    val vm: WorkoutViewModel = viewModel(key = "workout-$sessionId", factory = appViewModelFactory { WorkoutViewModel(it, sessionId) })
    val load by vm.load.collectAsStateWithLifecycle()
    val keepOn by vm.keepScreenOn.collectAsStateWithLifecycle()

    NotificationPermissionPrompt()
    LaunchedEffect(Unit) { Telemetry.track(SCREEN, "view") }

    val view = LocalView.current
    DisposableEffect(keepOn) {
        view.keepScreenOn = keepOn
        onDispose { view.keepScreenOn = false }
    }

    when (val l = load) {
        SessionLoad.Loading -> Box(Modifier.fillMaxSize())
        SessionLoad.Missing -> Column(Modifier.padding(Spacing.s4).padding(top = topInset())) {
            Text("Dieses Training wurde nicht gefunden.", style = MaterialTheme.typography.bodyLarge)
            LinkButton("Zurück", tracked(SCREEN, "missing_back") { nav.resetTo(Routes.TODAY) })
        }
        is SessionLoad.Ready -> {
            val session = l.session
            LaunchedEffect(session) { vm.finishIfDone(session) }
            if (session.status == SessionStatus.COMPLETED) {
                // Just finished: one short animation per exercise whose weight went up, then the summary.
                var celebrated by rememberSaveable(l.id) { mutableStateOf(false) }
                val increases by vm.celebrations.collectAsStateWithLifecycle()
                val shown = increases
                when {
                    celebrated || !vm.sawActive || (shown != null && shown.isEmpty()) ->
                        SummaryScreen(l.id, session, vm, onDone = { nav.resetTo(Routes.TODAY) })
                    shown == null -> Box(Modifier.fillMaxSize())
                    else -> WeightIncreaseCelebration(shown, onDone = { celebrated = true })
                }
            } else {
                val timer by vm.timer.collectAsStateWithLifecycle()
                val hasPending = currentExerciseIndex(session.exercises) >= 0
                Box(Modifier.fillMaxSize()) {
                    if (hasPending && timer?.phase == TimerPhase.REST) {
                        RestScreen(session, timer!!, vm)
                    } else {
                        TrainingScreen(l.id, session, vm, nav)
                    }
                    AdjustmentBanner(vm, Modifier.align(Alignment.TopCenter))
                }
            }
        }
    }
}

// ---- helpers shared by the training and rest screens ---------------------------------------

internal fun bandIndex(effort: String?): Int = when (effort) {
    "limit" -> 0
    "one", "one_two" -> 1
    "two_three", "three_four" -> 2
    "four_plus", "five_plus" -> 3
    else -> -1
}

internal fun prefillBand(ex: SessionExercise): Int = when {
    ex.target.rirMin >= 5 -> 3
    ex.target.rirMin >= 3 -> 2
    ex.target.rirMin >= 1 -> 1
    else -> 0
}

internal fun lastWorking(ex: SessionExercise): SetLog? =
    ex.sets.lastOrNull { it.status == SetStatus.COMPLETED && it.setType == SetType.WORKING }

/** Total weight a set starts with: the last working weight of the exercise, else the suggestion. */
internal fun prefillTotal(ex: SessionExercise, set: SetLog, levels: List<Double>): Double {
    // a weight the assistant planned for the open sets wins until the next set is logged (amendment D)
    val planned = ex.sets.firstOrNull { it.status == SetStatus.PENDING && it.setType == SetType.WORKING }?.weightKg
    val base = planned ?: lastWorking(ex)?.weightKg ?: ex.suggestedWeightKg ?: levels.minOrNull() ?: 20.0
    return if (set.setType == SetType.WARMUP) warmupProposal(base, levels).weightKg else base
}

internal fun machineSetting(info: MachineInfo?): String? =
    info?.setup?.filter { it.name.isNotBlank() }?.joinToString(", ") { "${it.name} ${it.value}".trim() }?.takeIf { it.isNotBlank() }

internal fun valueStep(metric: String): Double = when (metric) {
    Metric.SECONDS -> 5.0
    Metric.METERS -> 10.0
    else -> 1.0
}

internal fun valueUnit(metric: String): String = when (metric) {
    Metric.SECONDS -> "Sek."
    Metric.METERS -> "m"
    else -> "Wdh."
}

@Composable
internal fun rememberNow(intervalMs: Long = 500): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(intervalMs)
        }
    }
    return now
}

internal fun formatElapsed(seconds: Long): String {
    val s = seconds.coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
}

/**
 * Android 13+: explains why notifications are needed before asking, and points to the system
 * settings if the user declined. The ongoing workout notification works without it only as a hidden service.
 */
@Composable
private fun NotificationPermissionPrompt() {
    if (Build.VERSION.SDK_INT < 33) return
    val context = LocalContext.current
    fun granted() = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    // Step 3: one-time hint for the Android 16+ Live Update (Now Bar) switch.
    fun liveHintNeeded(): Boolean {
        if (LiveUpdateSupport.status(context) != LiveUpdateStatus.NOT_ALLOWED) return false
        return runCatching { !context.getSharedPreferences("hints", Context.MODE_PRIVATE).getBoolean("live_update_hint", false) }.getOrDefault(false)
    }
    fun markLiveHintSeen() {
        runCatching { context.getSharedPreferences("hints", Context.MODE_PRIVATE).edit().putBoolean("live_update_hint", true).apply() }
    }
    var step by rememberSaveable { mutableIntStateOf(if (!granted()) 1 else if (liveHintNeeded()) 3 else 0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        step = if (!ok) 2 else if (liveHintNeeded()) 3 else 0
    }
    when (step) {
        1 -> AlertDialog(
            onDismissRequest = { step = 0 },
            title = { Text("Benachrichtigungen erlauben") },
            text = {
                Text(
                    "GymApp zeigt während des Trainings den aktuellen Satz und die Pausenzeit dauerhaft in der Statusleiste " +
                        "und lässt dich Sätze direkt dort abhaken. Dafür braucht Android deine Erlaubnis.",
                )
            },
            confirmButton = { TextButton(onClick = tracked(SCREEN, "notif_allow") { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Erlauben") } },
            dismissButton = { TextButton(onClick = tracked(SCREEN, "notif_later") { step = 0 }) { Text("Später") } },
        )
        2 -> AlertDialog(
            onDismissRequest = { step = 0 },
            title = { Text("Benachrichtigungen erlauben") },
            text = { Text("Ohne Benachrichtigungen funktioniert der Timer nur in der App. Du kannst sie in den Android-Einstellungen aktivieren.") },
            confirmButton = {
                TextButton(
                    onClick = tracked(SCREEN, "notif_settings") {
                        step = 0
                        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                    },
                ) { Text("Einstellungen") }
            },
            dismissButton = { TextButton(onClick = tracked(SCREEN, "notif_cancel") { step = 0 }) { Text("Abbrechen") } },
        )
        3 -> AlertDialog(
            onDismissRequest = { markLiveHintSeen(); step = 0 },
            title = { Text("Live-Benachrichtigung (Now Bar)") },
            text = {
                Text(
                    "Damit Satz und Pausenzeit auch auf dem Sperrbildschirm und in der Now Bar erscheinen, muss Android " +
                        "\"Live-Benachrichtigungen\" für GymApp erlauben. Du findest den Schalter auch unter Mehr > Einstellungen.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = tracked(SCREEN, "live_update_settings") {
                        markLiveHintSeen()
                        step = 0
                        LiveUpdateSupport.open(context)
                    },
                ) { Text("Einstellungen") }
            },
            dismissButton = { TextButton(onClick = tracked(SCREEN, "live_update_later") { markLiveHintSeen(); step = 0 }) { Text("Später") } },
        )
    }
}
