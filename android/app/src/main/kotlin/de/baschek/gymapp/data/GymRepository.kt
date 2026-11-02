package de.baschek.gymapp.data

import androidx.room.withTransaction
import de.baschek.gymapp.core.model.BodyWeight
import de.baschek.gymapp.core.model.DocumentCodec
import de.baschek.gymapp.core.model.Entity
import de.baschek.gymapp.core.model.Exercise
import de.baschek.gymapp.core.model.GOAL_ID
import de.baschek.gymapp.core.model.Goal
import de.baschek.gymapp.core.model.GoalAuthor
import de.baschek.gymapp.core.model.Gym
import de.baschek.gymapp.core.model.Milestone
import de.baschek.gymapp.core.model.SetupParameter
import de.baschek.gymapp.core.model.effectiveAddons
import de.baschek.gymapp.core.progression.weightCandidates
import de.baschek.gymapp.core.stats.PackState
import de.baschek.gymapp.core.model.IndexColumns
import de.baschek.gymapp.core.model.MachineProfile
import de.baschek.gymapp.core.model.ModeSets
import de.baschek.gymapp.core.model.Origin
import de.baschek.gymapp.core.model.Plan
import de.baschek.gymapp.core.model.PlanExercise
import de.baschek.gymapp.core.model.PushOp
import de.baschek.gymapp.core.model.Session
import de.baschek.gymapp.core.model.SessionExercise
import de.baschek.gymapp.core.model.SessionStatus
import de.baschek.gymapp.core.model.SetLog
import de.baschek.gymapp.core.model.SetStatus
import de.baschek.gymapp.core.model.SetType
import de.baschek.gymapp.core.model.Target
import de.baschek.gymapp.core.model.WorkoutMode
import de.baschek.gymapp.core.model.effectiveWeights
import de.baschek.gymapp.core.progression.finishSession as coreFinishSession
import de.baschek.gymapp.core.model.OTHER_GYM_ID
import de.baschek.gymapp.core.model.ReviewFlag
import de.baschek.gymapp.core.progression.ExerciseInfo
import de.baschek.gymapp.core.progression.GymExerciseKey
import de.baschek.gymapp.core.progression.StartSuggestion
import de.baschek.gymapp.core.progression.WorkingWeight
import de.baschek.gymapp.core.progression.reassignSessionGym as coreReassignSessionGym
import de.baschek.gymapp.core.progression.suggestStartWeight
import de.baschek.gymapp.core.sync.ReconnectBackoff
import de.baschek.gymapp.core.sync.SyncStore
import de.baschek.gymapp.core.sync.TelemetryStore
import de.baschek.gymapp.data.db.DocumentEntity
import de.baschek.gymapp.data.db.GymDatabase
import de.baschek.gymapp.data.db.OutboxEntity
import de.baschek.gymapp.data.db.SettingEntity
import de.baschek.gymapp.data.db.TelemetryEntity
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** What the training screen needs to know about a machine. */
data class MachineInfo(
    val levels: List<Double>,
    val addons: List<Double>,
    val setup: List<SetupParameter>,
    val brand: String?,
    val model: String?,
    val workingWeightKg: Double?,
) {
    /** Every selectable total: main levels plus add-on combinations (progression rounding). */
    val candidates: List<Double> get() = weightCandidates(levels, addons)
}

internal fun MachineProfile.toInfo() = MachineInfo(
    effectiveWeights(), effectiveAddons(), setupParameters.orEmpty(), machineBrand, machineModel, workingWeightKg,
)

/** The goal document plus its last change time. */
data class GoalState(val goal: Goal, val updatedAt: String?)

/**
 * The ONLY access path to the database. ViewModels, workers and services talk
 * to this class; nobody else touches DAOs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GymRepository(
    private val db: GymDatabase,
    private val clock: () -> Instant = { Instant.now() },
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val dao = db.dao()

    internal val syncStore: SyncStore = RoomSyncStore(db)
    internal val telemetryStore: TelemetryStore = RoomTelemetryStore(db, newId)

    // ---- profile & settings -------------------------------------------------

    val activeProfile: Flow<String> =
        dao.observeSetting(SettingKeys.PROFILE).map { it ?: Profile.PERSONAL }.distinctUntilChanged()

    suspend fun currentProfile(): String = dao.getSetting(SettingKeys.PROFILE) ?: Profile.PERSONAL

    suspend fun setProfile(profile: String) {
        require(profile == Profile.PERSONAL || profile == Profile.TEST)
        dao.putSetting(SettingEntity(SettingKeys.PROFILE, profile))
    }

    fun observeSetting(name: String): Flow<String?> = dao.observeSetting(name)
    suspend fun getSetting(name: String): String? = dao.getSetting(name)
    suspend fun setSetting(name: String, value: String?) {
        if (value == null) dao.deleteSetting(name) else dao.putSetting(SettingEntity(name, value))
    }

    /** Time of the last fully successful sync (null = never). */
    val lastSyncAt: Flow<Instant?> =
        dao.observeSetting(SettingKeys.LAST_SYNC_AT).map { it?.let { s -> runCatching { Instant.parse(s) }.getOrNull() } }

    val syncNotices: Flow<List<String>> =
        dao.observeSetting(SettingKeys.SYNC_NOTICES).map { it?.lines()?.filter(String::isNotBlank).orEmpty() }

    val keepScreenOn: Flow<Boolean> =
        dao.observeSetting(SettingKeys.KEEP_SCREEN_ON).map { it != "0" }.distinctUntilChanged()

    val authError: Flow<Boolean> = dao.observeSetting(SettingKeys.AUTH_ERROR).map { it != null }

    suspend fun recordSyncSuccess(notices: List<String>) {
        db.withTransaction {
            dao.putSetting(SettingEntity(SettingKeys.LAST_SYNC_AT, clock().toString()))
            dao.deleteSetting(SettingKeys.AUTH_ERROR)
            dao.deleteSetting(SettingKeys.CONNECTION_LOST)
            if (notices.isEmpty()) dao.deleteSetting(SettingKeys.SYNC_NOTICES)
            else dao.putSetting(SettingEntity(SettingKeys.SYNC_NOTICES, notices.joinToString("\n")))
        }
    }

    /** True after the server stopped recognising this device following a successful sync. */
    val connectionLost: Flow<Boolean> = dao.observeSetting(SettingKeys.CONNECTION_LOST).map { it != null }

    /** Epoch millis before which background syncs must not retry, or null if the connection is fine. */
    suspend fun nextReconnectAttemptAt(): Long? =
        dao.getSetting(SettingKeys.CONNECTION_LOST)?.substringAfter(':')?.toLongOrNull()

    suspend fun hasSyncedBefore(): Boolean = dao.getSetting(SettingKeys.LAST_SYNC_AT) != null

    /** Records a lost connection and returns the time of the next allowed automatic attempt (exponential backoff). */
    suspend fun recordConnectionLost(nowMillis: Long): Long = db.withTransaction {
        val attempts = (dao.getSetting(SettingKeys.CONNECTION_LOST)?.substringBefore(':')?.toIntOrNull() ?: 0) + 1
        val next = nowMillis + ReconnectBackoff.delayMillis(attempts)
        dao.putSetting(SettingEntity(SettingKeys.CONNECTION_LOST, "$attempts:$next"))
        next
    }

    suspend fun clearConnectionLost() = dao.deleteSetting(SettingKeys.CONNECTION_LOST)

    suspend fun recordAuthError() = dao.putSetting(SettingEntity(SettingKeys.AUTH_ERROR, "1"))

    /** Forces the next sync to start from a full snapshot (used after pairing). */
    suspend fun resetSyncState() {
        db.withTransaction {
            dao.deleteSetting(SettingKeys.SYNC_CURSOR)
            dao.deleteSetting(SettingKeys.AUTH_ERROR)
            dao.deleteSetting(SettingKeys.CONNECTION_LOST)
            dao.deleteSetting(SettingKeys.LAST_SYNC_AT)
            dao.deleteSetting(SettingKeys.SYNC_NOTICES)
        }
    }

    // ---- reading ------------------------------------------------------------

    private fun <T> DocumentEntity.toStored(s: KSerializer<T>): Stored<T>? = runCatching {
        val raw = parseObject(data)
        Stored(id, rev, DocumentCodec.decode(s, raw), raw)
    }.getOrNull()

    private fun <T> observeAll(entity: String, s: KSerializer<T>): Flow<List<Stored<T>>> =
        activeProfile.flatMapLatest { p -> dao.observeDocs(p, entity) }
            .map { rows -> rows.mapNotNull { it.toStored(s) } }

    fun observePlans(): Flow<List<Stored<Plan>>> =
        observeAll(Entity.PLAN, Plan.serializer()).map { list -> list.filter { it.value.archived != true } }

    fun observeGyms(): Flow<List<Stored<Gym>>> =
        observeAll(Entity.GYM, Gym.serializer()).map { list ->
            list.filter { it.value.archived != true }.sortedBy { it.value.sortOrder ?: Int.MAX_VALUE }
        }

    fun observeExercises(): Flow<List<Stored<Exercise>>> = observeAll(Entity.EXERCISE, Exercise.serializer())

    fun observeBodyWeights(): Flow<List<Stored<BodyWeight>>> = observeAll(Entity.BODY_WEIGHT, BodyWeight.serializer())

    fun observeSession(id: String): Flow<Stored<Session>?> =
        activeProfile.flatMapLatest { p -> dao.observeDoc(p, Entity.SESSION, id) }
            .map { it?.toStored(Session.serializer()) }

    fun observeActiveSession(): Flow<Stored<Session>?> =
        activeProfile.flatMapLatest { p -> dao.observeActiveSession(p) }
            .map { it?.toStored(Session.serializer()) }

    /** Selectable weights per exercise id for the given gym. */
    fun observeMachineWeights(gymId: String?): Flow<Map<String, List<Double>>> =
        activeProfile.flatMapLatest { p ->
            if (gymId == null) kotlinx.coroutines.flow.flowOf(emptyList<DocumentEntity>())
            else dao.observeDocsByGym(p, Entity.MACHINE_PROFILE, gymId)
        }.map { rows ->
            rows.mapNotNull { it.toStored(MachineProfile.serializer()) }
                .associate { it.value.exerciseId to it.value.effectiveWeights() }
        }

    /** Levels, add-ons and setup values of every machine profile of the gym, by exercise id. */
    fun observeMachineInfo(gymId: String?): Flow<Map<String, MachineInfo>> =
        activeProfile.flatMapLatest { p ->
            if (gymId == null) flowOf(emptyList<DocumentEntity>())
            else dao.observeDocsByGym(p, Entity.MACHINE_PROFILE, gymId)
        }.map { rows ->
            rows.mapNotNull { it.toStored(MachineProfile.serializer()) }
                .associate { it.value.exerciseId to it.value.toInfo() }
        }

    suspend fun getMachineInfo(gymId: String?): Map<String, MachineInfo> {
        if (gymId == null) return emptyMap()
        return dao.getDocsByGym(currentProfile(), Entity.MACHINE_PROFILE, gymId)
            .mapNotNull { it.toStored(MachineProfile.serializer()) }
            .associate { it.value.exerciseId to it.value.toInfo() }
    }

    fun observeMachineProfiles(): Flow<List<Stored<MachineProfile>>> = observeAll(Entity.MACHINE_PROFILE, MachineProfile.serializer())

    fun observeMilestones(): Flow<List<Stored<Milestone>>> = observeAll(Entity.MILESTONE, Milestone.serializer())

    /** Every gym incl. archived ones (needed to label old sessions). */
    fun observeAllGyms(): Flow<List<Stored<Gym>>> =
        observeAll(Entity.GYM, Gym.serializer()).map { list -> list.sortedBy { it.value.sortOrder ?: Int.MAX_VALUE } }

    fun observePlan(id: String): Flow<Stored<Plan>?> =
        activeProfile.flatMapLatest { p -> dao.observeDoc(p, Entity.PLAN, id) }.map { it?.toStored(Plan.serializer()) }

    fun observeGoal(): Flow<GoalState?> =
        activeProfile.flatMapLatest { p -> dao.observeDoc(p, Entity.GOAL, GOAL_ID) }
            .map { row -> row?.toStored(Goal.serializer())?.let { GoalState(it.value, row?.updatedAt) } }

    // ---- writing ------------------------------------------------------------

    private fun nowIso(): String = clock().truncatedTo(ChronoUnit.MILLIS).toString()

    /**
     * Writes a document. Unknown fields of the existing raw JSON are preserved;
     * in the personal profile the change is queued for sync (replacing an older
     * pending change of the same document).
     */
    private suspend fun <T> put(profile: String, entity: String, id: String, value: T, s: KSerializer<T>) {
        val existing = dao.getDoc(profile, entity, id)
        val raw = existing?.takeIf { !it.deleted }?.let { parseObject(it.data) }
        val merged = DocumentCodec.encodeMerged(s, value, raw)
        val ts = nowIso()
        val idx = IndexColumns.extract(entity, merged)
        dao.upsertDoc(
            DocumentEntity(
                profile, entity, id, existing?.rev, ts, false, existing?.origin ?: Origin.APP, merged.toString(),
                idx.planId, idx.gymId, idx.exerciseId, idx.status, idx.sortAt,
            ),
        )
        if (profile == Profile.PERSONAL) enqueue(entity, id, PushOp.UPSERT, existing?.rev, ts, merged)
    }

    private suspend fun enqueue(entity: String, id: String, op: String, baseRev: Long?, ts: String, data: JsonObject) {
        dao.putOutbox(OutboxEntity(opId = newId(), entity = entity, id = id, op = op, baseRev = baseRev, clientUpdatedAt = ts, data = data.toString()))
    }

    suspend fun deleteDocument(entity: String, id: String) = db.withTransaction {
        val p = currentProfile()
        val existing = dao.getDoc(p, entity, id) ?: return@withTransaction
        if (existing.rev == null) {
            // never reached the server: nothing to tell it
            dao.deleteDoc(p, entity, id)
            dao.deleteOutboxFor(entity, id)
            return@withTransaction
        }
        val ts = nowIso()
        dao.upsertDoc(existing.copy(deleted = true, data = "{}", updatedAt = ts))
        if (p == Profile.PERSONAL) enqueue(entity, id, PushOp.DELETE, existing.rev, ts, JsonObject(emptyMap()))
    }

    // ---- sessions -----------------------------------------------------------

    suspend fun getSession(id: String): Session? =
        dao.getDoc(currentProfile(), Entity.SESSION, id)?.takeIf { !it.deleted }?.toStored(Session.serializer())?.value

    suspend fun getExercise(id: String): Exercise? =
        dao.getDoc(currentProfile(), Entity.EXERCISE, id)?.takeIf { !it.deleted }?.toStored(Exercise.serializer())?.value

    suspend fun getActiveSession(): Stored<Session>? =
        dao.getActiveSession(currentProfile())?.toStored(Session.serializer())

    suspend fun getMachineWeights(gymId: String?): Map<String, List<Double>> {
        if (gymId == null) return emptyMap()
        return dao.getDocsByGym(currentProfile(), Entity.MACHINE_PROFILE, gymId)
            .mapNotNull { it.toStored(MachineProfile.serializer()) }
            .associate { it.value.exerciseId to it.value.effectiveWeights() }
    }

    fun observeCompletedSessions(): Flow<List<Stored<Session>>> =
        observeAll(Entity.SESSION, Session.serializer())
            .map { list -> list.filter { it.value.status == SessionStatus.COMPLETED }.sortedByDescending { it.value.effectiveTime } }

    private suspend fun allSessions(profile: String): List<Pair<String, Session>> =
        dao.getDocs(profile, Entity.SESSION).mapNotNull { it.toStored(Session.serializer()) }.map { it.id to it.value }

    /**
     * Starts a session from a plan in the given gym (the gym is mandatory; [OTHER_GYM_ID] is allowed).
     * Returns the id of the new or already running active session.
     */
    suspend fun startSession(planId: String, mode: String = WorkoutMode.NORMAL, gymId: String): String? =
        db.withTransaction {
            val p = currentProfile()
            dao.getActiveSession(p)?.let { return@withTransaction it.id }
            val plan = dao.getDoc(p, Entity.PLAN, planId)?.takeIf { !it.deleted }?.toStored(Plan.serializer())?.value
                ?: return@withTransaction null
            val exercises = dao.getDocs(p, Entity.EXERCISE).mapNotNull { it.toStored(Exercise.serializer()) }.associate { it.id to it.value }
            val infos = exercises.map { (id, e) ->
                ExerciseInfo(id, e.name, e.measurementType, e.loadDirection, e.loadBasis, e.movementFamily, e.equipment)
            }
            val machines = dao.getDocsByGym(p, Entity.MACHINE_PROFILE, gymId)
                .mapNotNull { it.toStored(MachineProfile.serializer()) }.associate { it.value.exerciseId to it.value }
            val history = allSessions(p)

            val sessionExercises = plan.exercises.sortedBy { it.order }
                .filter { it.modeSets.forMode(mode) > 0 }
                .mapIndexed { index, pe ->
                    val machine = machines[pe.exerciseId]
                    val suggestion = suggestStartWeight(
                        pe.exerciseId, gymId, history, infos,
                        machine?.let { weightCandidates(it.effectiveWeights(), it.effectiveAddons()) }?.takeIf { it.isNotEmpty() },
                        machine?.workingWeightKg, pe.currentWeightKg ?: pe.startingWeightKg,
                    )
                    buildSessionExercise(pe, index + 1, mode, exercises[pe.exerciseId], suggestion)
                }

            val id = newId()
            val session = Session(
                gymId = gymId, planId = planId, planName = plan.name, mode = mode,
                status = SessionStatus.ACTIVE, startedAt = nowIso(), exercises = sessionExercises,
            )
            put(p, Entity.SESSION, id, session, Session.serializer())
            id
        }

    private fun buildSessionExercise(
        pe: PlanExercise,
        order: Int,
        mode: String,
        exercise: Exercise?,
        suggestion: StartSuggestion?,
    ): SessionExercise {
        val count = pe.modeSets.forMode(mode)
        return SessionExercise(
            id = newId(),
            sourcePlanExerciseId = pe.id,
            exerciseId = pe.exerciseId,
            name = exercise?.name ?: pe.exerciseId,
            order = order,
            measurementType = exercise?.measurementType ?: "load_reps",
            loadDirection = exercise?.loadDirection ?: "higher",
            loadBasis = exercise?.loadBasis ?: "stack",
            target = pe.target,
            restSeconds = pe.restSeconds,
            supersetGroup = pe.supersetGroup,
            plannedSets = count,
            suggestedWeightKg = suggestion?.weightKg,
            suggestionReason = suggestion?.reason,
            suggestionSourceGymId = suggestion?.sourceGymId,
            sets = (1..count).map { SetLog(id = newId(), order = it, setType = SetType.WORKING, status = SetStatus.PENDING) },
        )
    }

    suspend fun updateSession(id: String, transform: (Session) -> Session) = db.withTransaction {
        val p = currentProfile()
        val current = dao.getDoc(p, Entity.SESSION, id)?.takeIf { !it.deleted }?.toStored(Session.serializer()) ?: return@withTransaction
        put(p, Entity.SESSION, id, transform(current.value), Session.serializer())
    }

    /** Completes the session; pending sets get [pendingReason]; the gym's working weights follow the progression rules. */
    suspend fun finishSession(sessionId: String, pendingReason: String = SetStatus.SKIPPED_OTHER) = db.withTransaction {
        val p = currentProfile()
        val session = dao.getDoc(p, Entity.SESSION, sessionId)?.takeIf { !it.deleted }?.toStored(Session.serializer())?.value
            ?: return@withTransaction
        if (session.status == SessionStatus.COMPLETED) return@withTransaction
        val now = clock()
        val started = runCatching { Instant.parse(session.startedAt) }.getOrDefault(now)
        val result = coreFinishSession(
            sessionId, session, pendingReason, now.truncatedTo(ChronoUnit.MILLIS).toString(),
            Duration.between(started, now).seconds.toInt(), newId, allSessions(p).filter { it.first != sessionId },
        )
        put(p, Entity.SESSION, sessionId, result.session, Session.serializer())
        result.workingWeights.forEach { (key, w) -> setWorkingWeight(p, key, w) }
        result.milestones.forEach { put(p, Entity.MILESTONE, it.id, it.milestone, de.baschek.gymapp.core.model.Milestone.serializer()) }
    }

    /**
     * Changes the gym of a completed session and recalculates the working weights of the
     * old and new gym. The user confirmed the gym, so review flags about it are cleared.
     */
    suspend fun reassignSessionGym(sessionId: String, newGymId: String) = db.withTransaction {
        val p = currentProfile()
        val all = allSessions(p)
        val session = all.firstOrNull { it.first == sessionId }?.second ?: return@withTransaction
        if (session.status != SessionStatus.COMPLETED || session.gymId == newGymId) return@withTransaction
        val weights = coreReassignSessionGym(all, sessionId, newGymId)
        val flags = session.reviewFlags.orEmpty().filter { it != ReviewFlag.GYM_SUSPECT && it != ReviewFlag.GYM_MISSING }
        put(p, Entity.SESSION, sessionId, session.copy(gymId = newGymId, reviewFlags = flags.ifEmpty { null }), Session.serializer())
        weights.forEach { (key, w) -> setWorkingWeight(p, key, w) }
    }

    /** Removes all review flags ("geprüft"). */
    suspend fun clearReviewFlags(sessionId: String) = updateSession(sessionId) { it.copy(reviewFlags = null) }

    /**
     * Existing machine profile of a gym x exercise, including ones the server created with its own
     * id. A profile is only ever created when this finds none; with duplicates the lowest id wins.
     */
    private suspend fun findMachineProfile(profile: String, gymId: String, exerciseId: String): Stored<MachineProfile>? =
        dao.getDocsByGym(profile, Entity.MACHINE_PROFILE, gymId)
            .mapNotNull { it.toStored(MachineProfile.serializer()) }
            .filter { it.value.exerciseId == exerciseId }
            .minByOrNull { it.id }

    /** Stores (or clears, with null) the working weight on the machine profile of a gym x exercise. */
    private suspend fun setWorkingWeight(profile: String, key: GymExerciseKey, weight: WorkingWeight?) {
        val existing = findMachineProfile(profile, key.gymId, key.exerciseId)
        if (existing == null) {
            if (weight == null) return
            put(
                profile, Entity.MACHINE_PROFILE, newId(),
                MachineProfile(
                    gymId = key.gymId, exerciseId = key.exerciseId, workingWeightKg = weight.weightKg,
                    workingWeightUpdatedAt = weight.updatedAt, workingWeightSourceSessionId = weight.sourceSessionId,
                ),
                MachineProfile.serializer(),
            )
            return
        }
        val updated = existing.value.copy(
            workingWeightKg = weight?.weightKg,
            workingWeightUpdatedAt = weight?.updatedAt,
            workingWeightSourceSessionId = weight?.sourceSessionId,
        )
        if (updated != existing.value) put(profile, Entity.MACHINE_PROFILE, existing.id, updated, MachineProfile.serializer())
    }

    // ---- plans, exercises, machines, goals -------------------------------------

    suspend fun updatePlan(id: String, transform: (Plan) -> Plan) = db.withTransaction {
        val p = currentProfile()
        val current = dao.getDoc(p, Entity.PLAN, id)?.takeIf { !it.deleted }?.toStored(Plan.serializer()) ?: return@withTransaction
        val updated = transform(current.value)
        if (updated != current.value) put(p, Entity.PLAN, id, updated, Plan.serializer())
    }

    suspend fun createPlan(name: String): String = db.withTransaction {
        val id = newId()
        put(currentProfile(), Entity.PLAN, id, Plan(name = name, createdAt = nowIso()), Plan.serializer())
        id
    }

    suspend fun archivePlan(id: String) = updatePlan(id) { it.copy(archived = true) }

    suspend fun updateExercise(id: String, transform: (Exercise) -> Exercise) = db.withTransaction {
        val p = currentProfile()
        val current = dao.getDoc(p, Entity.EXERCISE, id)?.takeIf { !it.deleted }?.toStored(Exercise.serializer()) ?: return@withTransaction
        val updated = transform(current.value)
        if (updated != current.value) put(p, Entity.EXERCISE, id, updated, Exercise.serializer())
    }

    /** Creates or changes the machine profile of a gym x exercise. */
    suspend fun saveMachineProfile(gymId: String, exerciseId: String, transform: (MachineProfile) -> MachineProfile) = db.withTransaction {
        val p = currentProfile()
        val existing = findMachineProfile(p, gymId, exerciseId)
        val base = existing?.value ?: MachineProfile(gymId = gymId, exerciseId = exerciseId)
        val updated = transform(base)
        if (existing == null) put(p, Entity.MACHINE_PROFILE, newId(), updated, MachineProfile.serializer())
        else if (updated != base) put(p, Entity.MACHINE_PROFILE, existing.id, updated, MachineProfile.serializer())
    }

    suspend fun saveGoal(transform: (Goal) -> Goal) = db.withTransaction {
        val p = currentProfile()
        val base = dao.getDoc(p, Entity.GOAL, GOAL_ID)?.takeIf { !it.deleted }?.toStored(Goal.serializer())?.value ?: Goal()
        val updated = transform(base).copy(updatedBy = GoalAuthor.APP)
        put(p, Entity.GOAL, GOAL_ID, updated, Goal.serializer())
    }

    // ---- local-only UI state --------------------------------------------------

    /** Plan id -> `changed_by_assistant_at` the user has already seen (never synced). */
    val plansSeen: Flow<Map<String, String>> =
        dao.observeSetting(SettingKeys.PLANS_SEEN).map { raw ->
            raw?.lines()?.mapNotNull { l -> l.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }?.toMap().orEmpty()
        }

    suspend fun markPlanSeen(planId: String, changedAt: String) = db.withTransaction {
        val current = dao.getSetting(SettingKeys.PLANS_SEEN)?.lines()?.filter { it.isNotBlank() && !it.startsWith("$planId=") }.orEmpty()
        dao.putSetting(SettingEntity(SettingKeys.PLANS_SEEN, (current + "$planId=$changedAt").joinToString("\n")))
    }

    fun observePackChecked(gymId: String): Flow<String?> = dao.observeSetting(SettingKeys.PACK_PREFIX + gymId)

    /** Checks or unchecks one packing-list item; [defaults] are the items checked at the start of the day. */
    suspend fun togglePackItem(gymId: String, itemId: String, day: String, defaults: Set<String>) = db.withTransaction {
        val key = SettingKeys.PACK_PREFIX + gymId
        val current = PackState.decodeOrNull(dao.getSetting(key), day) ?: defaults
        dao.putSetting(SettingEntity(key, PackState.encode(day, PackState.toggle(current, itemId))))
    }

    // ---- sync conflicts ---------------------------------------------------------

    /**
     * Resolves a conflict notice ("conflict:entity:id"). [keepMine] writes the local version that lost
     * against the server back as a new edit (it is then newer than the server copy); otherwise the
     * server's version stays.
     */
    suspend fun resolveConflict(notice: String, keepMine: Boolean) = db.withTransaction {
        val parts = notice.split(':', limit = 3)
        if (parts.size == 3 && parts[0] == "conflict") {
            val key = SettingKeys.CONFLICT_PREFIX + parts[1] + ":" + parts[2]
            val backup = dao.getSetting(key)
            if (keepMine && backup != null) putRaw(currentProfile(), parts[1], parts[2], parseObject(backup))
            dao.deleteSetting(key)
        }
        val left = dao.getSetting(SettingKeys.SYNC_NOTICES)?.lines()?.filter { it.isNotBlank() && it != notice }.orEmpty()
        if (left.isEmpty()) dao.deleteSetting(SettingKeys.SYNC_NOTICES) else dao.putSetting(SettingEntity(SettingKeys.SYNC_NOTICES, left.joinToString("\n")))
    }

    private suspend fun putRaw(profile: String, entity: String, id: String, data: JsonObject) {
        val existing = dao.getDoc(profile, entity, id)
        val ts = nowIso()
        val idx = IndexColumns.extract(entity, data)
        dao.upsertDoc(
            DocumentEntity(
                profile, entity, id, existing?.rev, ts, false, existing?.origin ?: Origin.APP, data.toString(),
                idx.planId, idx.gymId, idx.exerciseId, idx.status, idx.sortAt,
            ),
        )
        if (profile == Profile.PERSONAL) enqueue(entity, id, PushOp.UPSERT, existing?.rev, ts, data)
    }

    // ---- demo data (test profile only) ---------------------------------------

    /** Creates a small synthetic data set. Only allowed in the test profile. */
    suspend fun seedDemoData(): Boolean = db.withTransaction {
        val p = currentProfile()
        if (p != Profile.TEST) return@withTransaction false
        if (dao.getDocs(p, Entity.PLAN).isNotEmpty()) return@withTransaction true
        val gym = "demo-gym"
        put(p, Entity.GYM, gym, Gym(name = "Testgym", shortName = "Test", sortOrder = 1), Gym.serializer())
        val ex = listOf(
            Triple("demo-ex-1", "Testgerät Brustpresse", listOf(10.0, 15.0, 20.0, 25.0, 30.0, 35.0, 40.0)),
            Triple("demo-ex-2", "Testgerät Rudern", listOf(15.0, 20.0, 25.0, 30.0, 35.0, 40.0, 45.0)),
            Triple("demo-ex-3", "Testgerät Beinpresse", listOf(40.0, 50.0, 60.0, 70.0, 80.0, 90.0, 100.0)),
        )
        ex.forEach { (id, name, weights) ->
            put(p, Entity.EXERCISE, id, Exercise(name = name, equipment = "machine", measurementType = "load_reps", loadDirection = "higher", loadBasis = "stack"), Exercise.serializer())
            put(p, Entity.MACHINE_PROFILE, "demo-mp-$id", MachineProfile(gymId = gym, exerciseId = id, availableWeightsKg = weights), MachineProfile.serializer())
        }
        val target = Target("reps", 8.0, 12.0, 1.0, 2.0)
        put(
            p, Entity.PLAN, "demo-plan",
            Plan(
                name = "Testplan Ganzkörper", createdAt = nowIso(),
                exercises = ex.mapIndexed { i, (id, _, weights) ->
                    PlanExercise(id = "demo-pe-${i + 1}", exerciseId = id, order = i + 1, modeSets = ModeSets(3, 2, 1), target = target, startingWeightKg = weights[1], restSeconds = 90)
                },
            ),
            Plan.serializer(),
        )
        dao.putSetting(SettingEntity(SettingKeys.CURRENT_GYM, gym))
        true
    }

    // ---- telemetry ----------------------------------------------------------

    suspend fun trackTelemetry(day: String, screen: String, element: String) = db.withTransaction {
        if (dao.bumpTelemetry(day, screen, element) == 0) dao.insertTelemetry(TelemetryEntity(day, screen, element, 1))
    }
}
