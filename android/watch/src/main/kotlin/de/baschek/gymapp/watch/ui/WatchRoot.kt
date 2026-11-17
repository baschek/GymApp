package de.baschek.gymapp.watch.ui

import androidx.compose.runtime.*
import androidx.activity.compose.LocalActivity
import de.baschek.gymapp.core.health.RecordingProfile
import de.baschek.gymapp.core.model.*
import de.baschek.gymapp.core.wear.events.WorkoutProfile
import de.baschek.gymapp.core.wear.progress.watchProgress
import de.baschek.gymapp.core.wear.progress.WatchProgress
import de.baschek.gymapp.watch.update.WatchUpdater
import de.baschek.gymapp.watch.update.WatchUpdatePhase
import de.baschek.gymapp.core.stats.PackState
import de.baschek.gymapp.core.training.SetCorrection
import de.baschek.gymapp.core.progression.totalVolume
import de.baschek.gymapp.core.progression.splitTotal
import de.baschek.gymapp.core.progression.selectedAddonIndices
import de.baschek.gymapp.wear.backend.workout.WatchSetInput
import de.baschek.gymapp.wear.backend.workout.WatchWorkoutRepository
import de.baschek.gymapp.wear.backend.workout.WatchWorkoutState
import de.baschek.gymapp.watch.runtime.WatchRuntime
import de.baschek.gymapp.watch.runtime.WatchRuntimeState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.time.LocalDate
import kotlin.math.roundToInt

@Composable
fun WatchRoot(repository: WatchWorkoutRepository, runtime: WatchRuntime) {
    val workout by repository.state.collectAsState()
    val runtimeState by runtime.state.collectAsState()
    val update by WatchUpdater.state.collectAsState()
    val activity = LocalActivity.current
    var clock by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(workout.timer, runtimeState.ambient) { while (true) { clock = System.currentTimeMillis(); delay(if (runtimeState.ambient) 60_000 else 1_000) } }
    LaunchedEffect(workout.session?.status) { if (workout.session?.status == SessionStatus.COMPLETED) runtime.stopRecording() }
    val today = LocalDate.now()
    val progress = remember(workout.catalog, today) { watchProgress(workout.catalog, today) }
    val presentation = present(workout, runtimeState, repository, clock, progress).copy(error = error, phoneHealthEnabled = runtimeState.phoneHealthEnabled, phoneHealthBackground = runtimeState.phoneHealthBackground, updateStatus = update.message.ifBlank { "Wird geprüft" }, updateCanInstall = update.phase in setOf(WatchUpdatePhase.READY, WatchUpdatePhase.INSTALL_PERMISSION_REQUIRED))
    CompositionLocalProvider(LocalInteraction provides { runtime.trackInteraction("watch", "tap") }) { WatchUi(presentation) { action ->
        runtime.trackInteraction("watch", action.javaClass.simpleName.lowercase(java.util.Locale.ROOT))
        error = null
        scope.launch {
            try {
                val ok: Boolean = when (action) {
                    is WatchAction.StartPlan -> repository.startPlan(action.plan, action.gym, action.mode) != null
                    is WatchAction.StartDay -> repository.startDayWorkout(action.day, action.gym, action.mode) != null
                    is WatchAction.StartSpontaneous -> repository.startSpontaneous(action.exercise, action.gym, action.mode) != null
                    is WatchAction.Log -> repository.logSet(action.exercise, action.set, WatchSetInput(action.weight, action.amount, action.effort, action.addons))
                    is WatchAction.Focus -> repository.setFocus(action.exercise)
                    is WatchAction.Move -> repository.moveExercise(action.exercise, action.delta)
                    is WatchAction.ToggleExercise -> if (action.skipped) repository.resumeSkipped(action.exercise) else repository.toggleSkipExercise(action.exercise)
                    is WatchAction.CorrectSet -> repository.correctSet(action.exercise, action.set, SetCorrection(action.weight, action.amount, action.effort.takeIf { it.isNotEmpty() }))
                    is WatchAction.DeleteSet -> repository.reopenSet(action.exercise, action.set)
                    WatchAction.FinishSetup -> repository.finishSetupTimer()
                    WatchAction.StartSetTimer -> repository.startSetTimer()
                    is WatchAction.SetDurationRing -> { repository.setDurationRing(action.enabled); true }
                    is WatchAction.TogglePack -> {
                        val dayItem = action.item.startsWith("day-item:")
                        val key = if (dayItem) "day:${action.day}" else action.gym
                        val items = if (dayItem) decodeMap<DayWorkout>(workout.catalog.dayWorkouts)[action.day]?.packlist.orEmpty() else decodeMap<Gym>(workout.catalog.gyms)[action.gym]?.packlist.orEmpty()
                        val defaults = items.filter { it.doneDefault == true }.map { if (dayItem) "day-item:${it.id}" else it.id }.toSet()
                        repository.togglePackItem(key, LocalDate.now().toString(), action.item, defaults); true
                    }
                    WatchAction.StartRecording -> { workout.sessionId?.let { runtime.startRecording(it, if (workout.profile == WorkoutProfile.TEST) RecordingProfile.TEST else RecordingProfile.PERSONAL) }; true }
                    is WatchAction.AddExercise -> repository.addExercise(action.exercise)
                    is WatchAction.AddSet -> repository.addSet(action.exercise)
                    is WatchAction.Warmup -> repository.addWarmup(action.exercise)
                    is WatchAction.SkipSet -> repository.skipSet(action.exercise, action.set)
                    WatchAction.Undo -> repository.undo()
                    WatchAction.NextExercise -> repository.nextExercise()
                    WatchAction.Finish -> repository.finish()
                    is WatchAction.ChangeRest -> repository.changeRest(action.seconds)
                    WatchAction.SkipRest -> repository.skipRest()
                    WatchAction.Retry -> { runtime.retrySync(); true }
                    is WatchAction.Resolve -> { runtime.resolveConflict(action.latest); true }
                    WatchAction.OpenPhoneHealthSettings -> { runtime.openPhoneHealthSettings(); true }
                    WatchAction.CheckUpdate -> { WatchUpdater.checkNow(); true }
                    WatchAction.InstallUpdate -> { if (activity != null) WatchUpdater.install(activity) else error = "Installation nicht verfügbar"; true }
                    WatchAction.OpenPhone -> { runtime.openPhone(); true }
                }
                if (!ok) error = "Aktion nicht möglich"
                if (ok && (action is WatchAction.Focus || action is WatchAction.AddExercise || action == WatchAction.NextExercise || action is WatchAction.StartPlan || action is WatchAction.StartDay || action is WatchAction.StartSpontaneous)) {
                    val latest = repository.state.value
                    val ex = latest.session?.exercises?.firstOrNull { it.id == latest.focusExerciseId } ?: latest.session?.exercises?.firstOrNull()
                    val m = decodeMap<MachineProfile>(latest.catalog.machineProfiles).values.firstOrNull { it.gymId == latest.session?.gymId && it.exerciseId == ex?.exerciseId }
                    val setup = m?.setupParameters.orEmpty().joinToString(" · ") { "${it.name} ${it.value}" }
                    if (setup.isNotBlank()) repository.startSetupTimer(setup)
                }
                if (ok && action == WatchAction.Finish) runtime.stopRecording()
            } catch (_: Exception) { error = "Änderung konnte nicht gespeichert werden" }
        }
    } }
}

private inline fun <reified T> decode(raw: JsonObject): T? = runCatching { DocumentCodec.decode<T>(raw) }.getOrNull()
private inline fun <reified T> decodeMap(raw: Map<String, JsonObject>): Map<String, T> = raw.mapNotNull { (id, value) -> decode<T>(value)?.let { id to it } }.toMap()

private fun present(s: WatchWorkoutState, rt: WatchRuntimeState, repository: WatchWorkoutRepository, now: Long, progress: WatchProgress): WatchPresentation {
    val gyms = decodeMap<Gym>(s.catalog.gyms).filterValues { it.archived != true }
    val plans = decodeMap<Plan>(s.catalog.plans).filterValues { it.archived != true }
    val library = decodeMap<Exercise>(s.catalog.exercises)
    val machines = decodeMap<MachineProfile>(s.catalog.machineProfiles).values
    val day = LocalDate.now().toString()
    val days = decodeMap<DayWorkout>(s.catalog.dayWorkouts).filterValues { it.date == day && it.status == DayWorkoutStatus.OFFERED }
    val session = s.session
    val expectedHeartProfile = if (s.profile == WorkoutProfile.TEST) RecordingProfile.TEST else RecordingProfile.PERSONAL
    val heartMatches = s.sessionId != null && rt.heartSessionId == s.sessionId && rt.heartProfile == expectedHeartProfile
    val active = session?.status == SessionStatus.ACTIVE
    val focus = session?.exercises?.firstOrNull { it.id == s.focusExerciseId } ?: session?.exercises?.firstOrNull { ex -> ex.sets.any { it.status == SetStatus.PENDING } }
    val pending = focus?.sets?.firstOrNull { it.status == SetStatus.PENDING }
    val machine = machines.firstOrNull { it.gymId == session?.gymId && it.exerciseId == focus?.exerciseId }
    val prefill = focus?.let { repository.prefill(it.id) }
    val logged = focus?.sets.orEmpty().filter { it.status == SetStatus.COMPLETED }
    val completedFocus = focus != null && pending == null
    val next = session?.exercises?.firstOrNull { it.id != focus?.id && it.sets.any { set -> set.status == SetStatus.PENDING } }
    val current = if (focus != null) {
        val amount = prefill?.value ?: 0.0
        val unit = when (focus.target.metric) { Metric.SECONDS -> "Sek."; Metric.METERS -> "m"; else -> "Wdh." }
        val noWeight = focus.measurementType in listOf(MeasurementType.BODYWEIGHT_REPS, MeasurementType.REPS_ONLY, MeasurementType.DURATION_ONLY)
        val addons = machine?.effectiveAddons().orEmpty()
        val split = prefill?.weightKg?.let { splitTotal(it, machine?.effectiveWeights().orEmpty(), addons) }
        WatchCurrentSet(focus.id, pending?.id.orEmpty(), focus.name, focus.sets.count { it.setType == SetType.WORKING && it.status == SetStatus.COMPLETED } + 1,
            focus.sets.count { it.setType == SetType.WORKING }, if (noWeight) null else split?.levelKg ?: prefill?.weightKg,
            amount, unit, when (focus.loadBasis) { LoadBasis.PER_HAND -> "kg pro Hand"; LoadBasis.ASSISTANCE -> "kg Hilfe"; else -> "kg" },
            "Ziel ${focus.target.min.roundToInt()}–${focus.target.max.roundToInt()}",
            machine?.setupParameters.orEmpty().joinToString(" · ") { "${it.name} ${it.value}" }, focus.notes ?: library[focus.exerciseId]?.notes.orEmpty(),
            machine?.effectiveWeights().orEmpty(), addons, selectedAddOns = selectedAddonIndices(split?.addonsKg.orEmpty(), addons), effort = prefill?.effort, hasWeight = !noWeight, isDuration = focus.target.metric == Metric.SECONDS)
    } else null
    val timer = s.timer?.takeIf { it.phase == "REST" }
    val remaining = timer?.endsAtMillis?.let { ((it - now) / 1000).toInt() }
    val summary = session?.takeIf { it.status == SessionStatus.COMPLETED }?.let { done ->
        val sets = done.exercises.flatMap { it.sets }.filter { it.status == SetStatus.COMPLETED && it.setType == SetType.WORKING }
        WatchSummary("${done.exercises.sumOf { totalVolume(it) }.roundToInt()} kg", sets.size.toString(), "${(done.durationSeconds ?: 0) / 60} min", if (heartMatches && rt.averageBpm != null && rt.maxBpm != null) "${rt.averageBpm} / ${rt.maxBpm}" else "– –")
    }
    fun number(value: Double): String = if (value == value.toLong().toDouble()) value.toLong().toString() else "%.2f".format(java.util.Locale.GERMANY, value).trimEnd('0').trimEnd(',')
    val frequency = listOf(WatchProgressRow("last7", "Letzte 7 Tage", "${progress.last7DaysCount} Trainings")) + progress.weeklySessions.map { WatchProgressRow(it.start.toString(), "Woche ab ${it.start}", "${it.count} Trainings") }
    val weights = progress.workingWeights.map { WatchProgressRow("${it.gymId}:${it.exerciseId}", it.exerciseName, "${number(it.weightKg)} kg · ${it.gymName}") }
    val bests = progress.personalBests.map { best ->
        val unit = if (best.unit == "reps") "Wdh." else best.unit
        val basis = when (best.loadBasis) { LoadBasis.PER_HAND -> " · pro Hand"; LoadBasis.ASSISTANCE -> " · Hilfe"; else -> "" }
        WatchProgressRow("${best.sessionId}:${best.setId}", best.exerciseName, "${number(best.value)} $unit$basis · ${best.gymName ?: "Studio unbekannt"}")
    }
    val history = progress.history.map { row ->
        val completedSets = row.session.exercises.flatMap { it.sets }.count { it.status == SetStatus.COMPLETED }
        val info = listOfNotNull(row.gymName, row.session.durationSeconds?.let { "${it / 60} min" }, "$completedSets Sätze").joinToString(" · ")
        val sections = row.session.exercises.sortedBy { it.order }.map { ex ->
            val logged = ex.sets.filter { it.status == SetStatus.COMPLETED }.map { set ->
                val value = listOfNotNull(set.weightKg?.let { "${number(it)} kg" }, set.reps?.let { "$it Wdh." }, set.seconds?.let { "$it s" }, set.meters?.let { "${number(it)} m" }).joinToString(" × ")
                (if (set.setType == SetType.WARMUP) "W · " else "") + value
            }
            ex.name to logged.joinToString("\n").ifBlank { "Kein Satz gespeichert" }
        }
        WatchHistoryEntry(row.sessionId, row.session.planName.ifBlank { "Training" }, listOfNotNull(row.session.effectiveTime.take(10).takeIf { it.isNotBlank() }, info).joinToString(" · "), sections)
    }
    return WatchPresentation(
        frequency = frequency, weights = weights, bests = bests, history = history,
        accent = s.catalog.accent,
        gyms = gyms.map { (id, gym) -> WatchOption(id, gym.name, gym.subtitle.orEmpty(), gym.color?.removePrefix("#")?.toLongOrNull(16)?.or(0xff000000)) },
        plans = plans.map { (id, plan) -> WatchOption(id, plan.name) }, dayWorkouts = days.map { (id, d) -> WatchOption(id, d.title) },
        library = library.map { (id, ex) -> WatchOption(id, ex.name) }.sortedBy { it.name },
        packChecked = gyms.mapValues { (id, gym) -> PackState.decodeOrNull(s.packChecks[id], day) ?: gym.packlist.orEmpty().filter { it.doneDefault == true }.map { it.id }.toSet() } + days.map { (id, workout) -> "day:$id" to (PackState.decodeOrNull(s.packChecks["day:$id"], day) ?: workout.packlist.orEmpty().filter { it.doneDefault == true }.map { "day-item:${it.id}" }.toSet()) }, durationRingEnabled = s.durationRing,
        packsByDay = days.mapValues { (_, workout) -> workout.packlist.orEmpty().sortedBy { it.order }.map { WatchOption("day-item:${it.id}", it.text) } },
        packsByGym = gyms.mapValues { (_, gym) -> gym.packlist.orEmpty().sortedBy { it.order }.map { WatchOption(it.id, it.text) } },
        suggestion = days.values.firstOrNull()?.title ?: "Training wählen", suggestionDetail = "",
        exercises = session?.exercises.orEmpty().sortedBy { it.order }.map { ex ->
            val done = ex.sets.count { it.status == SetStatus.COMPLETED && it.setType == SetType.WORKING }
            val total = ex.sets.count { it.setType == SetType.WORKING }
            val skipped = ex.sets.isNotEmpty() && ex.sets.none { it.status == SetStatus.PENDING || it.status == SetStatus.COMPLETED }
            WatchExerciseRow(ex.id, ex.name, "$done/$total", when { skipped -> "Übersprungen"; ex.id == focus?.id -> "Aktuell"; ex.sets.none { it.status == SetStatus.PENDING } -> "Fertig"; else -> "Offen" }, ex.id == focus?.id, ex.sets.none { it.status == SetStatus.PENDING }, skipped)
        },
        sets = logged.mapIndexed { i, set -> WatchSetRow(set.id, if (set.setType == SetType.WARMUP) "W" else "${logged.take(i + 1).count { it.setType == SetType.WORKING }}", listOfNotNull(set.weightKg?.let { "$it kg" }, set.reps?.let { "$it Wdh." }, set.seconds?.let { "$it s" }, set.meters?.let { "$it m" }).joinToString(" × "), set.effort.orEmpty(), set.weightKg, set.reps?.toDouble() ?: set.seconds?.toDouble() ?: set.meters ?: 0.0, when (focus?.target?.metric) { Metric.SECONDS -> "Sek."; Metric.METERS -> "m"; else -> "Wdh." }) },
        current = current, sessionActive = active, restRemainingSeconds = remaining, restTotalSeconds = timer?.let { ((it.endsAtMillis!! - it.startedAtMillis) / 1000).toInt() } ?: 0,
        exerciseFinished = completedFocus && active, nextExerciseName = next?.name, undoAvailable = s.undo != null,
        connection = rt.connectionLabel, pendingCount = s.pendingEvents, conflict = s.conflicts.firstOrNull()?.let { "Training wurde auf beiden Geräten geändert." },
        bpm = rt.bpm.takeIf { heartMatches }, pulseSamples = if (heartMatches) rt.pulseSamples.map { it.timeMillis to it.bpm } else emptyList(), pulsePermission = rt.permissionGranted, recording = if (heartMatches) rt.recordingLabel else "Puls nicht aufgezeichnet", export = rt.exportLabel, phoneLeads = rt.phoneLeads, ambient = rt.ambient,
        durationRing = session?.startedAt?.let { runCatching { ((now - java.time.Instant.parse(it).toEpochMilli()).coerceAtLeast(0L) % 3600000L) / 3600000f }.getOrNull() },
        ambientOffsetX = ((now / 60_000) % 5).toInt() - 2, ambientOffsetY = ((now / 300_000) % 5).toInt() - 2,
        testProfile = s.profile == WorkoutProfile.TEST, setupRemainingSeconds = s.timer?.takeIf { it.phase == "MACHINE_SETUP" }?.let { (45 - ((now - it.startedAtMillis).coerceAtLeast(0) / 1000).toInt()).coerceAtLeast(0) }, timedElapsedSeconds = s.timer?.takeIf { it.phase == "SET" }?.let { ((now - it.startedAtMillis).coerceAtLeast(0) / 1000).toInt() }, summary = summary,
        celebrations = s.milestones.values.filter { it.sessionId == s.sessionId && it.previousWeightKg != null }.map { WatchCelebration(it.exerciseName.orEmpty(), "${it.previousWeightKg} kg", "${it.newWeightKg} kg") },
    )
}
