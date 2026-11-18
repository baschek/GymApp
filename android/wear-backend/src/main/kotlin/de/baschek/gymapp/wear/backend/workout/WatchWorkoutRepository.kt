package de.baschek.gymapp.wear.backend.workout

import android.content.Context
import android.util.AtomicFile
import de.baschek.gymapp.core.model.*
import de.baschek.gymapp.core.progression.*
import de.baschek.gymapp.core.stats.PackState
import de.baschek.gymapp.core.training.*
import de.baschek.gymapp.core.wear.events.*
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject

/** One set as entered on the watch. [effortSource]: [EffortSource.USER] when the band was chosen, [EffortSource.AUTO] for the preselection (see `resolveEffort`). */
@Serializable data class WatchSetInput(val weightKg: Double?, val value: Double?, val effort: String?, val addonWeightsKg: List<Double>? = null,
    val effortSource: String? = null)
@Serializable data class WatchUndo(val exerciseId: String, val exercise: SessionExercise, val index: Int, val label: String)
@Serializable data class WatchFinishUndo(val sessionId: String, val machineBefore: Map<String, JsonObject?>,
    val milestoneIds: List<String>, val previousHistory: JsonObject?)

/** Raw documents retain fields introduced by other app versions. */
@Serializable data class WatchWorkoutState(
    val profile: WorkoutProfile,
    val catalog: WatchCatalog,
    val replica: ReplicaState? = null,
    val outbox: List<WorkoutEvent> = emptyList(),
    val undo: WatchUndo? = null,
    val finishUndo: WatchFinishUndo? = null,
    val archivedReplicas: Map<String, ReplicaState> = emptyMap(),
    val packChecks: Map<String, String> = emptyMap(),
    val milestones: Map<String, Milestone> = emptyMap(),
    val durationRing: Boolean = false,
    val peerEventProtocol: Int = 1,
    val catalogSessionIds: Set<String> = emptySet(),
    val catalogSessionIdsInitialized: Boolean = false,
    val deletedSessionIds: Set<String> = emptySet(),
    /** "Puls bei jedem Training aufzeichnen" (spec J): null until the one-time question is answered. */
    val heartAutoConsent: Boolean? = null,
) {
    val sessionId: String? get() = replica?.sessionId
    val session: Session? get() = replica?.document?.let { runCatching { DocumentCodec.decode(Session.serializer(), it) }.getOrNull() }
    val timer: WorkoutTimer? get() = replica?.timer
    val focusExerciseId: String? get() = replica?.focusExerciseId
    val pendingEvents: Int get() = outbox.size
    val lastDeviceId: String? get() = replica?.lastOperation?.deviceId
    val conflicts: List<WorkoutConflict> get() = replica?.conflicts.orEmpty()
}

/**
 * One profile-scoped local authority; each personal event and its new replica are persisted together.
 * Transport must authenticate peers and never send TEST data. All instances for a file share a lock.
 */
class WatchWorkoutRepository private constructor(
    file: File, private val profile: WorkoutProfile, private val deviceId: String,
    private val now: () -> Long = System::currentTimeMillis, private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val file = file.canonicalFile
    private val atomic = AtomicFile(this.file)
    private val mutex = synchronized(locks) { locks.getOrPut(this.file.path) { Mutex() } }
    private val mutable = MutableStateFlow(WatchWorkoutState(profile, WatchCatalog(profile = profile)))
    val state: StateFlow<WatchWorkoutState> = mutable.asStateFlow()

    private suspend fun <T> locked(block: (WatchWorkoutState) -> Pair<WatchWorkoutState, T>): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            val before = read() ?: mutable.value
            val (after, result) = block(before)
            if (after != before) write(after)
            mutable.value = after
            result
        }
    }
    private fun read(): WatchWorkoutState? {
        val stream = try { atomic.openRead() } catch (e: FileNotFoundException) {
            if (file.exists() || File(file.path + ".bak").exists()) throw e else return null
        }
        val bytes = stream.use { source ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = source.read(buffer)
                if (n < 0) break
                if (output.size() + n > MAX_BYTES) throw IOException("Workout cache too large")
                output.write(buffer, 0, n)
            }
            output.toByteArray()
        }
        val result = try { GymJson.decodeFromString(WatchWorkoutState.serializer(), bytes.toString(Charsets.UTF_8)) }
            catch (e: Exception) { throw IOException("Invalid workout cache", e) }
        if (result.profile != profile || result.catalog.profile != profile || result.replica?.profile?.let { it != profile } == true ||
            result.outbox.any { it.profile != profile } || (profile == WorkoutProfile.TEST && result.outbox.isNotEmpty()) ||
            result.replica?.let { WorkoutEvents.validate(it) != null } == true ||
            result.archivedReplicas.any { (id, replica) -> replica.sessionId != id || replica.profile != profile || WorkoutEvents.validate(replica) != null }) throw IOException("Invalid workout profile or replica")
        return result
    }
    private fun write(s: WatchWorkoutState) {
        val bytes = GymJson.encodeToString(s).toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_BYTES) throw IOException("Workout cache too large")
        val parent = file.parentFile ?: throw IOException("Missing cache directory")
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Cannot create cache directory")
        val output = atomic.startWrite()
        try { output.write(bytes); atomic.finishWrite(output) } catch (e: Exception) { atomic.failWrite(output); throw e }
    }
    suspend fun reload() = locked { it to Unit }
    suspend fun replaceCatalog(catalog: WatchCatalog, peerEventProtocol: Int = 1, allSessionIds: Set<String>? = null): Boolean = locked { s ->
        if (catalog.profile != profile || catalog.schemaVersion != 1 ||
            (allSessionIds != null && (allSessionIds.any { it.isBlank() || it.length > 128 } || !allSessionIds.containsAll(catalog.sessions.keys)))) return@locked s to false
        // Without the complete ID list (older phone), omission can also mean an active session.
        val known = if (s.catalogSessionIdsInitialized) s.catalogSessionIds else s.catalog.sessions.keys
        val deleted = if (allSessionIds == null) s.deletedSessionIds else
            (s.deletedSessionIds + (known - allSessionIds)) - allSessionIds
        val pending = s.outbox.map { it.sessionId }.toSet()
        val local = s.catalog.sessions.filterKeys { id ->
            val replica = s.replica?.takeIf { it.sessionId == id } ?: s.archivedReplicas[id]
            id !in catalog.sessions || id in pending || !replica?.conflicts.isNullOrEmpty() ||
                (s.replica?.sessionId == id && s.session?.status == SessionStatus.ACTIVE)
        }
        val merged = catalog.copy(sessions = catalog.sessions + local)
        val updated = s.copy(catalog = merged, peerEventProtocol = peerEventProtocol.coerceIn(1, 2),
            catalogSessionIds = if (allSessionIds == null) s.catalogSessionIds else known + allSessionIds,
            catalogSessionIdsInitialized = s.catalogSessionIdsInitialized || allSessionIds != null, deletedSessionIds = deleted)
        discardDeletedWhenSafe(updated) to true
    }

    /** Do not discard a workout with local work, active training, or an unresolved conflict. */
    private fun discardDeletedWhenSafe(s: WatchWorkoutState): WatchWorkoutState {
        val pending = s.outbox.map { it.sessionId }.toSet()
        val removable = s.deletedSessionIds.filter { id ->
            val replica = s.replica?.takeIf { it.sessionId == id } ?: s.archivedReplicas[id]
            id !in pending && replica?.conflicts.isNullOrEmpty() &&
                !(s.replica?.sessionId == id && s.session?.status == SessionStatus.ACTIVE)
        }.toSet()
        val current = s.replica?.takeIf { it.sessionId in removable }
        return s.copy(catalog = s.catalog.copy(sessions = s.catalog.sessions - removable),
            replica = if (current != null) null else s.replica,
            archivedReplicas = if (current != null) s.archivedReplicas + (current.sessionId to current) else s.archivedReplicas,
            milestones = s.milestones.filterValues { it.sessionId !in removable })
    }
    suspend fun setDurationRing(enabled: Boolean) = locked { it.copy(durationRing = enabled) to Unit }
    /** Stores the answer to "Puls bei jedem Training aufzeichnen" (device only, never synced). */
    suspend fun setHeartAutoConsent(enabled: Boolean) = locked { it.copy(heartAutoConsent = enabled) to Unit }
    suspend fun togglePackItem(key: String, day: String, itemId: String, defaults: Set<String> = emptySet()) = locked { s ->
        val selected = PackState.decodeOrNull(s.packChecks[key], day) ?: defaults
        s.copy(packChecks = s.packChecks + (key to PackState.encode(day, PackState.toggle(selected, itemId)))) to Unit
    }

    private inline fun <reified T> decode(raw: JsonObject?): T? = raw?.let { runCatching { DocumentCodec.decode<T>(it) }.getOrNull() }
    private inline fun <reified T> docs(map: Map<String, JsonObject>): Map<String, T> = map.mapNotNull { (id, raw) -> decode<T>(raw)?.let { id to it } }.toMap()
    private fun iso(millis: Long = now()) = Instant.ofEpochMilli(millis).toString()
    private fun session(s: WatchWorkoutState): Session? = s.session
    private fun event(s: WatchWorkoutState, before: ReplicaState, document: JsonObject, timer: WorkoutTimer?, focus: String?): WatchWorkoutState {
        val stamp = maxOf(now(), (before.lastOperation?.occurredAtMillis ?: -1L) + 1L)
        val event = WorkoutEvent(newId(), deviceId, stamp, profile, before.sessionId, before.document, document, timer, focus,
            predecessorEventId = if (s.peerEventProtocol >= 2) s.outbox.lastOrNull { it.sessionId == before.sessionId }?.eventId else null)
        val result = WorkoutEvents.apply(before, event)
        require(result is EventResult.Applied) { "Workout event rejected: $result" }
        return s.copy(replica = result.state, outbox = if (profile == WorkoutProfile.PERSONAL) s.outbox + event else s.outbox)
    }
    private fun start(s: WatchWorkoutState, plan: Plan, planId: String?, gymId: String, mode: String, dayId: String? = null): Pair<WatchWorkoutState, String?> {
        if (s.session?.status == SessionStatus.ACTIVE || gymId.isBlank() || (gymId != OTHER_GYM_ID && gymId !in s.catalog.gyms)) return s to null
        if (mode !in setOf(WorkoutMode.NORMAL, WorkoutMode.FAST, WorkoutMode.ULTRA)) return s to null
        val exercises = docs<Exercise>(s.catalog.exercises)
        val infos = exercises.map { (id, e) -> ExerciseInfo(id, e.name, e.measurementType, e.loadDirection, e.loadBasis, e.movementFamily, e.equipment) }
        val machines = docs<MachineProfile>(s.catalog.machineProfiles).values.filter { it.gymId == gymId }.associateBy { it.exerciseId }
        val history = docs<Session>(s.catalog.sessions).toList()
        val list = plan.exercises.sortedBy { it.order }.filter { it.modeSets.forMode(mode) > 0 }.mapIndexed { i, pe ->
            val ex = exercises[pe.exerciseId]; val machine = machines[pe.exerciseId]
            val levels = machine?.let { weightCandidates(it.effectiveWeights(), it.effectiveAddons()) }?.takeIf { it.isNotEmpty() }
            val suggestion = suggestStartWeight(pe.exerciseId, gymId, history, infos, levels, machine?.workingWeightKg, pe.currentWeightKg ?: pe.startingWeightKg)
            val n = pe.modeSets.forMode(mode)
            SessionExercise(id = newId(), sourcePlanExerciseId = pe.id, exerciseId = pe.exerciseId, name = ex?.name ?: pe.exerciseId,
                order = i + 1, measurementType = ex?.measurementType ?: MeasurementType.LOAD_REPS,
                loadDirection = ex?.loadDirection ?: LoadDirection.HIGHER, loadBasis = ex?.loadBasis ?: LoadBasis.STACK,
                target = pe.target, restSeconds = pe.restSeconds, supersetGroup = pe.supersetGroup, plannedSets = n,
                suggestedWeightKg = suggestion?.weightKg, suggestionReason = suggestion?.reason, suggestionSourceGymId = suggestion?.sourceGymId,
                sets = (1..n).map { SetLog(id = newId(), order = it, setType = SetType.WORKING, status = SetStatus.PENDING) })
        }
        if (list.isEmpty()) return s to null
        val id = newId(); val document = DocumentCodec.encodeMerged(Session.serializer(), Session(gymId = gymId, planId = planId,
            planName = plan.name, mode = mode, startedAt = iso(), exercises = list, source = dayId?.let { SOURCE_DAY_WORKOUT }, dayWorkoutId = dayId), null)
        val replica = ReplicaState(profile, id, document, focusExerciseId = list.first().id)
        val archived = s.replica?.let { s.archivedReplicas + (it.sessionId to it) } ?: s.archivedReplicas
        val updated = event(s.copy(replica = replica, undo = null, finishUndo = null, archivedReplicas = pruneArchived(archived, s.outbox)), replica, document, null, list.first().id)
        return updated to id
    }
    suspend fun startPlan(planId: String, gymId: String, mode: String): String? = locked { s ->
        val plan = decode<Plan>(s.catalog.plans[planId]) ?: return@locked s to null
        if (plan.archived == true) return@locked s to null
        start(s, plan, planId, gymId, mode)
    }
    suspend fun startDayWorkout(dayId: String, gymId: String, mode: String = WorkoutMode.NORMAL): String? = locked { s ->
        val day = decode<DayWorkout>(s.catalog.dayWorkouts[dayId]) ?: return@locked s to null
        if (day.status != DayWorkoutStatus.OFFERED) return@locked s to null
        val (next, id) = start(s, day.asPlan(), null, gymId, mode, dayId)
        if (id == null) next to null else next.copy(catalog = next.catalog.copy(dayWorkouts = next.catalog.dayWorkouts +
            (dayId to DocumentCodec.encodeMerged(day.markStarted(id, iso()), s.catalog.dayWorkouts[dayId])))) to id
    }
    suspend fun startSpontaneous(exerciseId: String, gymId: String, mode: String = WorkoutMode.NORMAL): String? = locked { s ->
        val ex = decode<Exercise>(s.catalog.exercises[exerciseId]) ?: return@locked s to null
        val pe = PlanExercise(id = newId(), exerciseId = exerciseId, order = 1, modeSets = ModeSets(1, 1, 1),
            target = if (ex.measurementType == MeasurementType.LOAD_DURATION || ex.measurementType == MeasurementType.DURATION_ONLY) Target(metric = Metric.SECONDS) else Target(Metric.REPS, 8.0, 12.0, 1.0, 2.0))
        start(s, Plan(name = "Spontan", exercises = listOf(pe)), null, gymId, mode)
    }

    /** Thrown inside a transform to refuse an action with a reason (spec L). */
    private class Reject(val reason: RejectReason) : Exception(null, null, false, false)
    private fun reject(reason: RejectReason): Nothing = throw Reject(reason)

    /**
     * Applies one action. [ActionOutcome.Unchanged] when it changes nothing (to be ignored silently), [ActionOutcome.Rejected]
     * with a reason for a real refusal, [ActionOutcome.Changed] otherwise.
     */
    private suspend fun change(exerciseId: String?, label: String, transform: (WatchWorkoutState, Session) -> Triple<Session, WorkoutTimer?, String?>): ActionOutcome = locked { s ->
        val r = s.replica ?: return@locked s to ActionOutcome.Rejected(RejectReason.NO_ACTIVE_WORKOUT)
        val before = session(s) ?: return@locked s to ActionOutcome.Rejected(RejectReason.NO_ACTIVE_WORKOUT)
        if (before.status != SessionStatus.ACTIVE) return@locked s to ActionOutcome.Rejected(RejectReason.NO_ACTIVE_WORKOUT)
        val (after, timer, focus) = try { transform(s, before) } catch (e: Reject) { return@locked s to ActionOutcome.Rejected(e.reason) }
        if (after == before && timer == r.timer && focus == r.focusExerciseId) return@locked s to ActionOutcome.Unchanged
        val raw = DocumentCodec.encodeMerged(Session.serializer(), after.copy(focusExerciseId = focus), r.document)
        val index = before.exercises.indexOfFirst { it.id == exerciseId }
        val undo = if (index >= 0) WatchUndo(exerciseId!!, before.exercises[index], index, label) else null
        val next = event(s.copy(undo = undo, finishUndo = null), r, raw, timer, focus)
        val (state, _) = if (label == "Satz geloggt" && after.exercises.none { ex -> ex.sets.any { it.status == SetStatus.PENDING } }) finishState(next, preserveUndo = true) else next to true
        state to ActionOutcome.Changed
    }
    private fun exercise(session: Session, exerciseId: String): SessionExercise =
        session.exercises.firstOrNull { it.id == exerciseId } ?: reject(RejectReason.EXERCISE_GONE)
    fun prefill(exerciseId: String): SetPrefill? {
        val s = state.value; val ex = s.session?.exercises?.firstOrNull { it.id == exerciseId } ?: return null
        val profile = docs<MachineProfile>(s.catalog.machineProfiles).values.firstOrNull { it.gymId == s.session?.gymId && it.exerciseId == ex.exerciseId }
        return prefillSet(ex, profile?.let { weightCandidates(it.effectiveWeights(), it.effectiveAddons()) }.orEmpty())
    }
    /** Logs a set; the effort is stored with its [WatchSetInput.effortSource] (spec E). */
    suspend fun logSet(exerciseId: String, setId: String, input: WatchSetInput): ActionOutcome = change(exerciseId, "Satz geloggt") { s, session ->
        val ex = exercise(session, exerciseId)
        val set = ex.sets.firstOrNull { it.id == setId } ?: reject(RejectReason.SET_ALREADY_DONE)
        if (set.status != SetStatus.PENDING) reject(RejectReason.SET_ALREADY_DONE)
        if (input.value?.let { !it.isFinite() || it <= 0.0 } == true || input.weightKg?.let { !it.isFinite() || it < 0.0 } == true) reject(RejectReason.INVALID_VALUE)
        val clock = now(); val timer = s.timer
        val elapsed = if (timer?.phase == "SET" && timer.sessionId == s.sessionId && timer.exerciseId == exerciseId && timer.setId == setId) ((clock - timer.startedAtMillis).coerceAtLeast(0L) / 1000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else null
        val timerForSession = timer?.takeIf { it.sessionId == s.sessionId }
        val setTimerMatches = timerForSession == null || timerForSession.phase != "SET" || (timerForSession.exerciseId == exerciseId && timerForSession.setId == setId)
        val beforeRest = if (!setTimerMatches) null else timerForSession?.pendingRestSeconds ?: if (timerForSession?.phase == "REST") ((clock - timerForSession.startedAtMillis).coerceAtLeast(0L) / 1000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else null
        val beforeSetup = if (!setTimerMatches) null else timerForSession?.pendingSetupSeconds ?: if (timerForSession?.phase == "MACHINE_SETUP" && timerForSession.exerciseId == exerciseId) ((clock - timerForSession.startedAtMillis).coerceAtLeast(0L) / 1000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else null
        val updated = session.copy(exercises = session.exercises.map { item -> if (item.id != exerciseId) item else clearPlannedWeights(item.copy(sets = item.sets.map { entry ->
            if (entry.id != setId) entry else entry.copy(status = SetStatus.COMPLETED, weightKg = input.weightKg,
                addonWeightsKg = input.addonWeightsKg?.takeIf { it.isNotEmpty() },
                reps = if (item.target.metric == Metric.REPS) input.value?.toInt() else entry.reps,
                seconds = if (item.target.metric == Metric.SECONDS) input.value?.toInt() else entry.seconds,
                meters = if (item.target.metric == Metric.METERS) input.value else entry.meters, effort = input.effort,
                effortSource = input.effortSource?.takeIf { input.effort != null && it in setOf(EffortSource.USER, EffortSource.AUTO) },
                setDurationSeconds = elapsed, restBeforeSeconds = beforeRest, setupBeforeSeconds = beforeSetup, completedAt = iso(clock))
        }), setId) })
        val rest = if (updated.exercises.any { it.sets.any { entry -> entry.status == SetStatus.PENDING } }) WorkoutTimer("REST", clock, clock + ex.restSeconds * 1000L, ex.name, sessionId = s.sessionId, exerciseId = exerciseId, setId = setId) else null
        Triple(updated, rest, exerciseId)
    }
    suspend fun correctSet(exerciseId: String, setId: String, correction: SetCorrection): ActionOutcome = change(exerciseId, "Satz geändert") { s, session ->
        val set = exercise(session, exerciseId).sets.firstOrNull { it.id == setId } ?: reject(RejectReason.EXERCISE_GONE)
        if (set.status != SetStatus.COMPLETED) reject(RejectReason.SET_ALREADY_DONE)
        Triple(de.baschek.gymapp.core.training.correctSet(session, exerciseId, setId, correction), s.timer, s.focusExerciseId)
    }
    suspend fun reopenSet(exerciseId: String, setId: String): ActionOutcome = change(exerciseId, "Satz gelöscht") { s, session -> exercise(session, exerciseId); Triple(de.baschek.gymapp.core.training.reopenSet(session, exerciseId, setId), s.timer, exerciseId) }
    suspend fun skipSet(exerciseId: String, setId: String, reason: SkipReason = SkipReason.OTHER): ActionOutcome = change(exerciseId, "Satz übersprungen") { s, session ->
        val set = exercise(session, exerciseId).sets.firstOrNull { it.id == setId }
        if (set == null || set.status != SetStatus.PENDING) reject(RejectReason.SET_ALREADY_DONE)
        Triple(de.baschek.gymapp.core.training.skipSet(session, exerciseId, setId, reason), s.timer, exerciseId)
    }
    suspend fun addSet(exerciseId: String): ActionOutcome = change(exerciseId, "Satz hinzugefügt") { s, session ->
        exercise(session, exerciseId)
        if (!canAddSet(session, exerciseId)) reject(RejectReason.SET_LIMIT)
        Triple(de.baschek.gymapp.core.training.addSet(session, exerciseId, newId), s.timer, exerciseId)
    }
    suspend fun addWarmup(exerciseId: String): ActionOutcome = change(exerciseId, "Aufwärmsatz hinzugefügt") { s, session -> exercise(session, exerciseId); Triple(insertWarmup(session, exerciseId, newId), s.timer, exerciseId) }
    /** Moving the first exercise up or the last one down changes nothing and is ignored. */
    suspend fun moveExercise(exerciseId: String, delta: Int): ActionOutcome = change(exerciseId, "Übung verschoben") { s, session -> exercise(session, exerciseId); Triple(de.baschek.gymapp.core.training.moveExercise(session, exerciseId, delta), s.timer, s.focusExerciseId) }
    suspend fun toggleSkipExercise(exerciseId: String): ActionOutcome = change(exerciseId, "Übung übersprungen") { s, session -> exercise(session, exerciseId); Triple(de.baschek.gymapp.core.training.toggleSkipExercise(session, exerciseId), s.timer, s.focusExerciseId) }
    suspend fun resumeSkipped(exerciseId: String): ActionOutcome = change(exerciseId, "Übung wieder aufgenommen") { s, session -> exercise(session, exerciseId); Triple(resumeSkippedSets(session, exerciseId), s.timer, exerciseId) }
    suspend fun setNote(exerciseId: String, note: String): ActionOutcome = change(exerciseId, "Notiz geändert") { s, session -> exercise(session, exerciseId); Triple(setExerciseNote(session, exerciseId, note), s.timer, s.focusExerciseId) }
    /** "Jetzt": the current exercise again is ignored; an exercise without open sets is refused. */
    suspend fun setFocus(exerciseId: String): ActionOutcome = change(null, "Übung gewählt") { s, session ->
        val ex = exercise(session, exerciseId)
        if (ex.id == s.focusExerciseId) return@change Triple(session, s.timer, s.focusExerciseId)
        if (ex.sets.none { set -> set.status == SetStatus.PENDING }) reject(RejectReason.EXERCISE_DONE)
        Triple(session, s.timer, ex.id)
    }
    /** Goes to [nextAfter] the focus: the first exercise with open sets (spec A6), the same one [nextExerciseName] names. */
    suspend fun nextExercise(): ActionOutcome = change(null, "Nächste Übung") { s, session ->
        Triple(session, s.timer, nextExerciseAfter(session.exercises, s.focusExerciseId)?.id ?: s.focusExerciseId)
    }
    /** Name of the exercise [nextExercise] would go to, or null when no other exercise has open sets. */
    fun nextExerciseName(): String? {
        val s = state.value
        return s.session?.let { nextExerciseAfter(it.exercises, s.focusExerciseId)?.name }
    }
    suspend fun addExercise(catalogExerciseId: String): ActionOutcome = change(null, "Übung hinzugefügt") { s, session ->
        val ex = decode<Exercise>(s.catalog.exercises[catalogExerciseId]) ?: reject(RejectReason.EXERCISE_GONE)
        val updated = de.baschek.gymapp.core.training.addExercise(session, templateFor(catalogExerciseId, ex.name, ex.measurementType, ex.loadDirection, ex.loadBasis), 1, newId)
        Triple(updated, s.timer, updated.exercises.lastOrNull()?.id)
    }
    suspend fun startSetTimer(): ActionOutcome = change(null, "Satz gestartet") { s, session ->
        val clock = now(); val prior = s.timer
        val exercise = session.exercises.firstOrNull { it.id == s.focusExerciseId } ?: return@change Triple(session, prior, s.focusExerciseId)
        val set = exercise.sets.firstOrNull { it.status == SetStatus.PENDING } ?: return@change Triple(session, prior, s.focusExerciseId)
        if (prior?.phase == "SET" && prior.sessionId == s.sessionId && prior.exerciseId == exercise.id && prior.setId == set.id) return@change Triple(session, prior, s.focusExerciseId)
        val rest = prior?.pendingRestSeconds ?: if (prior?.phase == "REST") ((clock - prior.startedAtMillis).coerceAtLeast(0L) / 1000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else null
        val setup = prior?.pendingSetupSeconds ?: if (prior?.phase == "MACHINE_SETUP") ((clock - prior.startedAtMillis).coerceAtLeast(0L) / 1000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else null
        Triple(session, WorkoutTimer("SET", clock, pendingRestSeconds = rest, pendingSetupSeconds = setup, sessionId = s.sessionId, exerciseId = exercise.id, setId = set.id), s.focusExerciseId)
    }
    suspend fun startSetupTimer(label: String): ActionOutcome = change(null, "Gerät einstellen") { s, session ->
        Triple(session, WorkoutTimer("MACHINE_SETUP", now(), label = label, pendingRestSeconds = s.timer?.pendingRestSeconds, sessionId = s.sessionId, exerciseId = s.focusExerciseId), s.focusExerciseId)
    }
    suspend fun finishSetupTimer(): ActionOutcome = change(null, "Gerät eingestellt") { s, session ->
        val t = s.timer
        if (t?.phase != "MACHINE_SETUP") Triple(session, t, s.focusExerciseId)
        else Triple(session, t.copy(phase = null, endsAtMillis = null, pendingSetupSeconds = ((now() - t.startedAtMillis).coerceAtLeast(0L) / 1000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()), s.focusExerciseId)
    }
    /** +/-15 s; refused with "Pause schon beendet" when no rest runs any more. */
    suspend fun changeRest(seconds: Int): ActionOutcome = change(null, "Pause geändert") { s, session ->
        val timer = s.timer
        val end = timer?.endsAtMillis
        if (timer?.phase != "REST" || end == null) reject(RejectReason.REST_ALREADY_OVER)
        Triple(session, timer.copy(endsAtMillis = (end + seconds * 1000L).coerceAtLeast(timer.startedAtMillis)), s.focusExerciseId)
    }
    /** "Weiter": without a running rest (pressed twice, or ended on the phone) nothing happens. */
    suspend fun skipRest(): ActionOutcome = change(null, "Pause beendet") { s, session ->
        val t = s.timer
        if (t?.phase != "REST") Triple(session, t, s.focusExerciseId)
        else Triple(session, t.copy(phase = null, endsAtMillis = null, pendingRestSeconds = ((now() - t.startedAtMillis).coerceAtLeast(0) / 1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()), s.focusExerciseId)
    }
    suspend fun undo(): ActionOutcome = locked { s ->
        val r = s.replica ?: return@locked s to ActionOutcome.Rejected(RejectReason.NO_ACTIVE_WORKOUT); val current = session(s) ?: return@locked s to ActionOutcome.Rejected(RejectReason.NO_ACTIVE_WORKOUT)
        val u = s.undo ?: return@locked s to ActionOutcome.Rejected(RejectReason.NOTHING_TO_UNDO)
        val finish = s.finishUndo
        if (current.status != SessionStatus.ACTIVE && (current.status != SessionStatus.COMPLETED || finish?.sessionId != r.sessionId)) return@locked s to ActionOutcome.Rejected(RejectReason.NOTHING_TO_UNDO)
        val reopened = if (finish != null) current.copy(status = SessionStatus.ACTIVE, completedAt = null, durationSeconds = null) else current
        val restored = applyUndo(reopened, UndoEntry(u.label, u.exerciseId, u.exercise, u.index))
        val raw = DocumentCodec.encodeMerged(Session.serializer(), restored.copy(focusExerciseId = u.exerciseId), r.document)
        var updated = event(s.copy(undo = null, finishUndo = null), r, raw, null, u.exerciseId)
        if (finish != null) {
            val machines = updated.catalog.machineProfiles.toMutableMap()
            finish.machineBefore.forEach { (id, old) -> if (old == null) machines.remove(id) else machines[id] = old }
            val history = updated.catalog.sessions.toMutableMap()
            if (finish.previousHistory == null) history.remove(finish.sessionId) else history[finish.sessionId] = finish.previousHistory
            updated = updated.copy(catalog = updated.catalog.copy(machineProfiles = machines, sessions = history), milestones = updated.milestones - finish.milestoneIds.toSet())
        }
        updated to ActionOutcome.Changed
    }
    suspend fun finish(): ActionOutcome = locked { s ->
        val (next, ok) = finishState(s)
        next to if (ok) ActionOutcome.Changed else ActionOutcome.Rejected(RejectReason.NO_ACTIVE_WORKOUT)
    }

    /** Machine profile of a catalog exercise in the gym of the running session. */
    private fun machineFor(s: WatchWorkoutState, exerciseId: String): MachineProfile? =
        docs<MachineProfile>(s.catalog.machineProfiles).values.firstOrNull { it.gymId == s.session?.gymId && it.exerciseId == exerciseId }

    /**
     * The next set for the set and rest screens (spec D/F): values, "Ziel 8–12", the suggestion ("170 → 180 kg ▲") and,
     * before a new exercise, its machine setup. [justDoneExerciseId] is the exercise of the set just logged (null: the focus).
     */
    fun nextSetPreview(justDoneExerciseId: String? = state.value.timer?.exerciseId): SetPreview? {
        val s = state.value
        val session = s.session?.takeIf { it.status == SessionStatus.ACTIVE } ?: return null
        return de.baschek.gymapp.core.training.nextSetPreview(session, s.focusExerciseId, justDoneExerciseId ?: s.focusExerciseId) { machineFor(s, it) }
    }

    /** Weight suggestion from the last working set of an exercise (spec K), null if there is none. */
    fun weightHint(exerciseId: String): WeightHint? {
        val s = state.value
        val ex = s.session?.exercises?.firstOrNull { it.id == exerciseId } ?: return null
        val machine = machineFor(s, ex.exerciseId)
        return de.baschek.gymapp.core.training.weightHint(ex, machine?.effectiveWeights().orEmpty(), machine?.workingWeightKg, addons = machine?.effectiveAddons().orEmpty())
    }
    private fun finishState(s: WatchWorkoutState, preserveUndo: Boolean = false): Pair<WatchWorkoutState, Boolean> {
        val r = s.replica ?: return s to false; val session = session(s) ?: return s to false
        if (session.status != SessionStatus.ACTIVE) return s to false
        val clock = now(); val started = runCatching { Instant.parse(session.startedAt).toEpochMilli() }.getOrDefault(clock)
        val machines = docs<MachineProfile>(s.catalog.machineProfiles)
        val baselines = machines.values.filter { it.workingWeightSourceSessionId == "manual" && it.workingWeightKg != null && it.workingWeightUpdatedAt != null }.associate {
            GymExerciseKey(it.gymId, it.exerciseId) to WorkingWeight(it.workingWeightKg!!, "manual", it.workingWeightUpdatedAt!!)
        }
        val result = de.baschek.gymapp.core.progression.finishSession(r.sessionId, session, SetStatus.SKIPPED_OTHER, iso(clock), ((clock - started).coerceAtLeast(0) / 1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), newId, docs<Session>(s.catalog.sessions).toList(), baselines)
        val profiles = s.catalog.machineProfiles.toMutableMap()
        for ((key, weight) in result.workingWeights) {
            val existing = machines.entries.firstOrNull { it.value.gymId == key.gymId && it.value.exerciseId == key.exerciseId }
            val id = existing?.key ?: newId()
            val machine = existing?.value ?: MachineProfile(gymId = key.gymId, exerciseId = key.exerciseId)
            profiles[id] = DocumentCodec.encodeMerged(machine.copy(workingWeightKg = weight.weightKg,
                workingWeightUpdatedAt = weight.updatedAt, workingWeightSourceSessionId = weight.sourceSessionId), profiles[id])
        }
        val raw = DocumentCodec.encodeMerged(Session.serializer(), result.session.copy(focusExerciseId = null), r.document)
        val updated = event(s, r, raw, null, null)
        val previous = profiles.keys.filter { profiles[it] != s.catalog.machineProfiles[it] }.associateWith { s.catalog.machineProfiles[it] }
        val finishUndo = if (preserveUndo && s.undo != null) WatchFinishUndo(r.sessionId, previous,
            result.milestones.map { it.id }, s.catalog.sessions[r.sessionId]) else null
        return updated.copy(catalog = updated.catalog.copy(sessions = updated.catalog.sessions + (r.sessionId to raw), machineProfiles = profiles),
            milestones = updated.milestones + result.milestones.associate { it.id to it.milestone },
            undo = if (finishUndo != null) s.undo else null, finishUndo = finishUndo) to true
    }
    /** Keeps archived replicas that still matter (conflicts, unsent events) plus the newest few; the rest only grows the state file. */
    private fun pruneArchived(archived: Map<String, ReplicaState>, outbox: List<WorkoutEvent>): Map<String, ReplicaState> {
        if (archived.size <= MAX_ARCHIVED_REPLICAS) return archived
        val pendingSessions = outbox.map { it.sessionId }.toSet()
        val (needed, rest) = archived.entries.partition { it.value.conflicts.isNotEmpty() || it.key in pendingSessions }
        val recent = rest.sortedByDescending { it.value.lastOperation?.occurredAtMillis ?: Long.MIN_VALUE }.take(MAX_ARCHIVED_REPLICAS)
        return (needed + recent).associate { it.key to it.value }
    }
    /** Acknowledge only IDs durably accepted by the receiver. */
    suspend fun acknowledge(eventIds: Set<String>) = locked { s -> discardDeletedWhenSafe(s.copy(outbox = s.outbox.filterNot { it.eventId in eventIds })) to Unit }
    suspend fun receive(event: WorkoutEvent): EventResult = locked { s ->
        if (profile != WorkoutProfile.PERSONAL || event.profile != profile) return@locked s to EventResult.Rejected("profile_local_only")
        val active = s.replica
        val archived = s.archivedReplicas[event.sessionId]
        val retained = active?.takeIf { it.sessionId == event.sessionId } ?: archived
        if (event.sessionId in s.deletedSessionIds && event.sessionId !in s.catalog.sessions && retained?.conflicts.isNullOrEmpty() && s.outbox.none { it.sessionId == event.sessionId }) {
            val replay = retained?.let { WorkoutEvents.apply(it, event) }
            return@locked s to if (replay is EventResult.Replayed) replay else EventResult.Rejected("deleted_session")
        }
        val current = when {
            active?.sessionId == event.sessionId -> active
            archived != null -> archived
            active == null || s.session?.status == SessionStatus.COMPLETED -> ReplicaState(profile, event.sessionId, event.before)
            else -> return@locked s to EventResult.Rejected("different_active_session")
        }
        when (val result = WorkoutEvents.apply(current, event)) {
            is EventResult.Applied -> {
                if (archived != null && active?.sessionId != event.sessionId) {
                    val decoded = decode<Session>(result.state.document)
                    val catalog = if (decoded?.status == SessionStatus.COMPLETED) s.catalog.copy(sessions = s.catalog.sessions + (event.sessionId to result.state.document)) else s.catalog
                    s.copy(archivedReplicas = pruneArchived(s.archivedReplicas + (event.sessionId to result.state), s.outbox), catalog = catalog) to result
                } else {
                    val old = if (active != null && active.sessionId != event.sessionId) s.archivedReplicas + (active.sessionId to active) else s.archivedReplicas
                    s.copy(replica = result.state, archivedReplicas = pruneArchived(old, s.outbox), undo = null, finishUndo = null) to result
                }
            }
            else -> s to result
        }
    }
    suspend fun resolve(choice: ConflictChoice): EventResult = locked { s ->
        val current = s.replica ?: return@locked s to EventResult.Rejected("no_session")
        val stamp = maxOf(now(), (current.lastOperation?.occurredAtMillis ?: -1L) + 1L)
        val resolution = WorkoutEvents.createResolution(current, choice, newId(), deviceId, stamp)
            .copy(predecessorEventId = if (s.peerEventProtocol >= 2) s.outbox.lastOrNull { it.sessionId == current.sessionId }?.eventId else null)
        when (val result = WorkoutEvents.apply(current, resolution)) {
            is EventResult.Applied -> s.copy(replica = result.state, undo = null, outbox = if (profile == WorkoutProfile.PERSONAL) s.outbox + resolution else s.outbox) to result
            else -> s to result
        }
    }
    companion object {
        private const val MAX_BYTES = 8 * 1024 * 1024
        internal const val MAX_ARCHIVED_REPLICAS = 5
        private val locks = mutableMapOf<String, Mutex>()
        suspend fun open(context: Context, profile: WorkoutProfile, deviceId: String): WatchWorkoutRepository =
            at(File(context.noBackupFilesDir, "wear/workout-${profile.name.lowercase()}.json"), profile, deviceId)
        suspend fun at(file: File, profile: WorkoutProfile, deviceId: String, now: () -> Long = System::currentTimeMillis,
                       newId: () -> String = { UUID.randomUUID().toString() }): WatchWorkoutRepository {
            require(deviceId.isNotBlank() && deviceId.length <= 128 && deviceId.none { it.isISOControl() })
            return WatchWorkoutRepository(file, profile, deviceId, now, newId).also { it.reload() }
        }
    }
}
