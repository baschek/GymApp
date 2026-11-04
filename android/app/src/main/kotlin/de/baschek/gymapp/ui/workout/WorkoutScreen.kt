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
import de.baschek.gymapp.timer.LiveUpdateStatus
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
                SummaryScreen(l.id, session, vm, onDone = { nav.resetTo(Routes.TODAY) })
            } else {
                val timer by vm.timer.collectAsStateWithLifecycle()
                val hasPending = currentExerciseIndex(session.exercises) >= 0
                if (hasPending && timer?.phase == TimerPhase.REST) {
                    RestScreen(session, timer!!, vm)
                } else {
                    TrainingScreen(l.id, session, vm, nav)
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

private fun lastWorking(ex: SessionExercise): SetLog? =
    ex.sets.lastOrNull { it.status == SetStatus.COMPLETED && it.setType == SetType.WORKING }

/** Total weight a set starts with: the last working weight of the exercise, else the suggestion. */
internal fun prefillTotal(ex: SessionExercise, set: SetLog, levels: List<Double>): Double {
    val base = lastWorking(ex)?.weightKg ?: ex.suggestedWeightKg ?: levels.minOrNull() ?: 20.0
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

private fun formatElapsed(seconds: Long): String {
    val s = seconds.coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
}

// ---- training ---------------------------------------------------------------------------

/** Editable values of the current set. Lives as long as the set is on screen. */
private class SetDraft(val initialTotal: Double, level: Double, addons: Set<Double>, value: Double, effort: Int) {
    var level by mutableDoubleStateOf(level)
    var addons by mutableStateOf(addons)
    var value by mutableDoubleStateOf(value)
    var effort by mutableIntStateOf(effort)

    val total: Double get() = Math.round((level + addons.sum()) * 1000.0) / 1000.0
}

private fun buildDraft(ex: SessionExercise?, set: SetLog?, levels: List<Double>, addons: List<Double>, wheel: List<Double>): SetDraft {
    if (ex == null || set == null) return SetDraft(0.0, wheel.first(), emptySet(), 10.0, 1)
    val total = prefillTotal(ex, set, levels)
    val warm = set.setType == SetType.WARMUP
    val split = if (levels.isNotEmpty() && !warm) splitTotal(total, levels, addons) else null
    val level = split?.levelKg ?: wheel[nearestLevelIndex(wheel, total)]
    val last = lastWorking(ex)
    val lastValue = last?.let {
        when (ex.target.metric) {
            Metric.SECONDS -> it.seconds?.toDouble()
            Metric.METERS -> it.meters
            else -> it.reps?.toDouble()
        }
    }
    val value = if (warm) 12.0 else lastValue ?: ex.target.max
    val effort = if (warm) 1 else bandIndex(last?.effort).takeIf { it >= 0 } ?: prefillBand(ex)
    return SetDraft(total, level, split?.addonsKg?.toSet() ?: emptySet(), value.coerceAtLeast(valueStep(ex.target.metric)), effort)
}

private fun reasonText(ex: SessionExercise, total: Double): String? {
    val suggested = ex.suggestedWeightKg ?: return null
    if (Math.abs(total - suggested) > 1e-6) return "Eigene Wahl. Vorschlag war ${fmtNum(suggested)} kg."
    return when (ex.suggestionReason) {
        "gym_working_weight" -> "Dein Arbeitsgewicht in diesem Studio."
        "gym_history" -> "Wie beim letzten Training hier."
        "cross_gym_estimate" -> "Schätzung aus einem anderen Studio. Vorsichtig testen."
        "guided_estimate" -> "Vorsichtiger Startwert aus einer verwandten Übung."
        "plan_weight" -> "Startgewicht aus dem Plan."
        else -> null
    }
}

private fun targetCaption(ex: SessionExercise): String {
    val min = ex.target.min.toInt()
    val max = ex.target.max.toInt()
    val what = when (ex.target.metric) {
        Metric.SECONDS -> "Sekunden"
        Metric.METERS -> "Meter"
        else -> "Wiederholungen"
    }
    return if (max > 0) "$what · Ziel $min–$max" else what
}

@Composable
private fun TrainingScreen(sessionId: String, session: Session, vm: WorkoutViewModel, nav: Navigator) {
    val machines by vm.machines.collectAsStateWithLifecycle()
    val gyms by vm.gyms.collectAsStateWithLifecycle()
    val previous by vm.previousWeights.collectAsStateWithLifecycle()
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val now = rememberNow(1000)

    var sheet by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmFinish by rememberSaveable { mutableStateOf(false) }
    var pickExercise by rememberSaveable { mutableStateOf(false) }

    val exIndex = currentExerciseIndex(session.exercises)
    val ex = session.exercises.getOrNull(exIndex)
    val set = ex?.sets?.firstOrNull { it.status == SetStatus.PENDING }
    val warm = set?.setType == SetType.WARMUP

    val info = ex?.let { machines[it.exerciseId] }
    val levels = info?.levels.orEmpty()
    val addonList = info?.addons.orEmpty()
    val hasProfile = levels.isNotEmpty()
    val wheel = if (hasProfile) levels else remember { freeWeights() }
    val weighted = ex != null && ex.measurementType in WEIGHTED_TYPES
    val draft = remember(set?.id, hasProfile) { buildDraft(ex, set, levels, addonList, wheel) }

    val workingTotal = ex?.sets?.count { it.setType == SetType.WORKING } ?: 0
    val workingDone = ex?.sets?.count { it.setType == SetType.WORKING && it.status != SetStatus.PENDING } ?: 0
    val newWeight = remember(set?.id) {
        val prev = ex?.let { previous[it.exerciseId] }
        ex != null && !warm && workingDone == 0 && prev != null && draft.initialTotal > prev + 1e-6
    }
    val logSet = tracked(SCREEN, "log_set") {
        if (ex != null && set != null) {
            vm.logSet(
                ex.id, set.id,
                SetInput(
                    weightKg = if (weighted) draft.total else null,
                    value = draft.value,
                    effort = if (warm) null else SelectableEffortBands[draft.effort],
                    addonWeightsKg = draft.addons.sorted().takeIf { it.isNotEmpty() && weighted },
                ),
            )
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(top = topInset() + 4.dp, start = Spacing.Gutter, end = Spacing.Gutter, bottom = 196.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val started = parseInstant(session.startedAt)?.toEpochMilli() ?: now
            val position = when {
                ex == null -> "Alle Sätze erledigt"
                warm -> "Übung ${exIndex + 1}/${session.exercises.size} · Aufwärmsatz"
                else -> "Übung ${exIndex + 1}/${session.exercises.size} · Satz ${workingDone + 1}/$workingTotal"
            }
            TrainingHeader(
                planName = session.planName, position = position, gymName = gymLabel(session.gymId, gyms),
                gymColor = gymColorOf(session.gymId, gyms), elapsed = formatElapsed((now - started) / 1000),
                onList = tracked(SCREEN, "open_list") { sheet = "list" },
            )
            SetMeter(workingTotal, workingDone)

            if (ex == null || set == null) {
                Text(
                    "In diesem Training gibt es keine offenen Sätze.", style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column {
                    Text(ex.name, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
                    Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s2)) {
                        machineSetting(info)?.let {
                            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f, fill = false))
                        }
                        ex.supersetGroup?.takeIf { it.isNotBlank() }?.let { Tag("Superset $it") }
                        if (warm) Tag("Aufwärmsatz")
                    }
                    ex.notes?.takeIf { it.isNotBlank() }?.let {
                        Text(it, Modifier.padding(top = 2.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                if (weighted) {
                    val i = nearestLevelIndex(wheel, draft.level)
                    WeightCard(
                        totalText = fmtNum(draft.total),
                        previousLevel = wheel.getOrNull(i - 1)?.let(::fmtNum),
                        currentLevel = fmtNum(wheel[i]),
                        nextLevel = wheel.getOrNull(i + 1)?.let(::fmtNum),
                        onStep = { d ->
                            Telemetry.track(SCREEN, "weight_step")
                            draft.level = wheel[(i + d).coerceIn(0, wheel.lastIndex)]
                        },
                        addons = if (warm) emptyList() else addonList.map { AddonOption("+ ${fmtNum(it)} kg", it in draft.addons) },
                        onToggleAddon = { idx ->
                            Telemetry.track(SCREEN, "toggle_addon")
                            val a = addonList[idx]
                            draft.addons = if (a in draft.addons) draft.addons - a else draft.addons + a
                        },
                        reason = if (warm) null else reasonText(ex, draft.total),
                        hasProfile = hasProfile,
                        onCreateProfile = tracked(SCREEN, "create_profile") { session.gymId?.let { nav.push(Routes.machine(it, ex.exerciseId)) } },
                        newWeight = newWeight,
                        minusEnabled = i > 0, plusEnabled = i < wheel.lastIndex,
                    )
                }

                val metric = ex.target.metric
                val step = valueStep(metric)
                RepsCard(
                    caption = targetCaption(ex),
                    previous = (draft.value - step).takeIf { it >= step }?.let(::fmtNum),
                    current = fmtNum(draft.value),
                    next = fmtNum(draft.value + step),
                    unit = valueUnit(metric),
                    onStep = { d ->
                        Telemetry.track(SCREEN, "reps_step")
                        draft.value = (draft.value + d * step).coerceIn(step, 999.0)
                    },
                    label = when (metric) { Metric.SECONDS -> "Sekunden"; Metric.METERS -> "Meter"; else -> "Wiederholungen" },
                    minusEnabled = draft.value > step,
                )
                if (!warm) {
                    EffortPicker(draft.effort, { Telemetry.track(SCREEN, "effort"); draft.effort = it })
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                LinkButton("Training beenden", tracked(SCREEN, "finish_open") { confirmFinish = true }, height = Spacing.TouchMin)
            }
        }

        PinnedBottom(Modifier.align(Alignment.BottomCenter)) {
            PrimaryButton("Satz geschafft", onClick = logSet, icon = GymIcons.Check, height = Spacing.TouchPrimary, enabled = ex != null && set != null)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                LinkButton("Überspringen", tracked(SCREEN, "skip_open") { sheet = "skip" }, height = Spacing.TouchMin, enabled = set != null)
                LinkButton("Aufwärmsatz", tracked(SCREEN, "warmup_open") { sheet = "warmup" }, height = Spacing.TouchMin, enabled = set != null && !warm)
                LinkButton("Notiz", tracked(SCREEN, "note_open") { sheet = "note" }, height = Spacing.TouchMin, enabled = ex != null)
            }
        }
    }

    when (sheet) {
        "skip" -> if (ex != null && set != null) SkipSheet(
            onPick = { reason ->
                Telemetry.track(SCREEN, "skip_${reason.name.lowercase()}")
                vm.skipSet(ex.id, set.id, reason)
                sheet = if (reason == SkipReason.PAIN) "pain-note" else null
            },
            onDismiss = { sheet = null },
        )
        "pain-note", "note" -> if (ex != null) NoteSheet(
            title = if (sheet == "pain-note") "Was ist passiert? (Schmerz)" else "Notiz zu ${ex.name}",
            initial = ex.notes.orEmpty(),
            onSave = { vm.setNote(ex.id, it); sheet = null },
            onDismiss = { sheet = null },
        )
        "warmup" -> if (ex != null && set != null) {
            val workBase = lastWorking(ex)?.weightKg ?: ex.suggestedWeightKg ?: levels.minOrNull() ?: 20.0
            val proposal = warmupProposal(workBase, levels)
            GymSheet({ sheet = null }, title = "Aufwärmsatz") {
                Text(
                    "Leichter Satz vor dem ersten Arbeitssatz, zum Beispiel ${fmtNum(proposal.weightKg)} kg × ${proposal.reps}. Er zählt nicht für den Fortschritt.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PrimaryButton("Aufwärmsatz einfügen", tracked(SCREEN, "warmup_insert") { vm.addWarmup(ex.id); sheet = null }, height = 52.dp)
            }
        }
        "list" -> GymSheet({ sheet = null }, title = "Aktuelles Workout") {
            ExerciseListCard(session, editable = true, onMove = vm::moveExercise, onToggleSkip = vm::toggleSkipExercise, onAdd = { pickExercise = true }, screen = SCREEN)
        }
    }
    if (pickExercise) {
        ExercisePickerSheet(catalog, onPick = { vm.addExercise(it.id); pickExercise = false }, onDismiss = { pickExercise = false })
    }
    if (confirmFinish) {
        AlertDialog(
            onDismissRequest = { confirmFinish = false },
            title = { Text("Training beenden") },
            text = { Text("Offene Sätze werden als übersprungen markiert.") },
            confirmButton = { TextButton(onClick = tracked(SCREEN, "finish_confirm") { confirmFinish = false; vm.finishNow() }) { Text("Beenden") } },
            dismissButton = { TextButton(onClick = tracked(SCREEN, "finish_cancel") { confirmFinish = false }) { Text("Abbrechen") } },
        )
    }
}

/** Compact top bar: gym stripe + names on the left, elapsed time and the workout list on the right. */
@Composable
private fun TrainingHeader(planName: String, position: String, gymName: String, gymColor: androidx.compose.ui.graphics.Color, elapsed: String, onList: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s3)) {
        Box(Modifier.width(4.dp).height(44.dp).background(gymColor, androidx.compose.foundation.shape.RoundedCornerShape(2.dp)))
        Column(Modifier.weight(1f)) {
            Text(planName, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), maxLines = 1)
            Text("$position · $gymName", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1)
        }
        SoftButton("$elapsed", onList, height = Spacing.TouchMin, icon = GymIcons.Menu)
    }
}

@Composable
private fun SkipSheet(onPick: (SkipReason) -> Unit, onDismiss: () -> Unit) {
    GymSheet(onDismiss, title = "Satz überspringen") {
        listOf(
            SkipReason.TIME to "Zeit",
            SkipReason.EQUIPMENT to "Gerät besetzt",
            SkipReason.PAIN to "Schmerz",
            SkipReason.OTHER to "Sonstiges",
        ).forEach { (reason, label) ->
            SoftButton(label, { onPick(reason) }, Modifier.fillMaxWidth(), height = Spacing.TouchMin)
        }
    }
}

@Composable
private fun NoteSheet(title: String, initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    GymSheet(onDismiss, title = title) {
        GymField(text, { text = it }, "Notiz", singleLine = false, placeholder = "Zum Beispiel: Sitz eine Stufe höher")
        PrimaryButton("Speichern", tracked(SCREEN, "note_save") { onSave(text) }, height = 52.dp)
    }
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
