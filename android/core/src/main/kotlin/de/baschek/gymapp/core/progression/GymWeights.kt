package de.baschek.gymapp.core.progression

import de.baschek.gymapp.core.model.LoadDirection
import de.baschek.gymapp.core.model.Metric
import de.baschek.gymapp.core.model.Session
import de.baschek.gymapp.core.model.SessionExercise
import de.baschek.gymapp.core.model.SessionStatus
import de.baschek.gymapp.core.model.SetLog
import de.baschek.gymapp.core.model.SetStatus
import de.baschek.gymapp.core.model.SetType
import de.baschek.gymapp.core.model.SuggestionReason
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.floor

/*
 * Per-gym working weights and start estimates (contract v1 amendment A).
 * All functions are pure; sessions are passed as (documentId, session) pairs.
 */

data class GymExerciseKey(val gymId: String, val exerciseId: String)

data class WorkingWeight(val weightKg: Double, val sourceSessionId: String, val updatedAt: String)

data class StartSuggestion(val weightKg: Double, val reason: String, val sourceGymId: String? = null)

private fun completedWithGym(sessions: List<Pair<String, Session>>) =
    sessions.filter { (_, s) -> s.status == SessionStatus.COMPLETED && s.gymId != null }

/** `working_weight_source_session_id` of a weight the user or the assistant set by hand (contract v1 amendment D). */
const val MANUAL_WEIGHT_SOURCE = "manual"

private fun isLater(timeIso: String, baselineIso: String): Boolean {
    val a = runCatching { java.time.Instant.parse(timeIso) }.getOrNull()
    val b = runCatching { java.time.Instant.parse(baselineIso) }.getOrNull()
    return if (a != null && b != null) a.isAfter(b) else timeIso > baselineIso
}

/**
 * Replays the completed sessions of each gym (oldest first) and returns the
 * proven working weight per gym x exercise. Only sessions with the same gym
 * feed a key. With [only], just those keys are computed (absent = null).
 *
 * [baselines] are weights set by hand (source [MANUAL_WEIGHT_SOURCE], timestamp = `updatedAt`): the replay of that key
 * counts sessions up to the timestamp as before, continues from the manual weight with the first later session that
 * contains the exercise, and keeps the manual weight when there is no later session (same rule as the server).
 */
fun recalculateWorkingWeights(
    sessions: List<Pair<String, Session>>,
    only: Set<GymExerciseKey>? = null,
    baselines: Map<GymExerciseKey, WorkingWeight> = emptyMap(),
): Map<GymExerciseKey, WorkingWeight?> {
    val current = HashMap<GymExerciseKey, WorkingWeight>()
    val lastSeen = HashMap<GymExerciseKey, String>()
    val manual = HashMap(baselines)
    val ordered = completedWithGym(sessions).sortedWith(compareBy({ it.second.effectiveTime }, { it.first }))
    for ((id, session) in ordered) {
        for (performed in session.exercises) {
            val key = GymExerciseKey(session.gymId!!, performed.exerciseId)
            if (only != null && key !in only) continue
            manual[key]?.let { base ->
                if (isLater(session.effectiveTime, base.updatedAt)) {
                    current[key] = base
                    manual.remove(key)
                }
            }
            val longBreak = lastSeen[key]?.let { daysBetween(it, session.effectiveTime)?.let { d -> d > LONG_BREAK_DAYS } } ?: false
            lastSeen[key] = session.effectiveTime
            val next = provenWorkingWeight(performed, current[key]?.weightKg, longBreak) ?: continue
            current[key] = WorkingWeight(next, id, session.effectiveTime)
        }
    }
    for ((key, base) in manual) if (only == null || key in only) current[key] = base
    return if (only != null) only.associateWith { current[it] } else current
}

/** Whole days between the dates of two ISO timestamps (date part), or null when one cannot be read. */
internal fun daysBetween(earlierIso: String, laterIso: String): Long? {
    val a = runCatching { LocalDate.parse(earlierIso.take(10)) }.getOrNull() ?: return null
    val b = runCatching { LocalDate.parse(laterIso.take(10)) }.getOrNull() ?: return null
    return ChronoUnit.DAYS.between(a, b)
}

/**
 * Changes the gym of a completed session and returns the recalculated working
 * weights for every affected gym x exercise (old and new gym). Unaffected keys
 * are not part of the result.
 */
fun reassignSessionGym(
    sessions: List<Pair<String, Session>>,
    sessionId: String,
    newGymId: String,
    baselines: Map<GymExerciseKey, WorkingWeight> = emptyMap(),
): Map<GymExerciseKey, WorkingWeight?> {
    val target = sessions.firstOrNull { it.first == sessionId }?.second ?: return emptyMap()
    val gyms = listOfNotNull(target.gymId, newGymId).toSet()
    val keys = gyms.flatMap { g -> target.exercises.map { GymExerciseKey(g, it.exerciseId) } }.toSet()
    val updated = sessions.map { (id, s) -> if (id == sessionId) id to s.copy(gymId = newGymId) else id to s }
    return recalculateWorkingWeights(updated, keys, baselines)
}

private fun median(values: List<Double>): Double {
    val s = values.sorted()
    val n = s.size
    return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2
}

private fun workingSetWeights(ex: SessionExercise): List<Double> =
    ex.sets.filter { it.status == SetStatus.COMPLETED && it.setType == SetType.WORKING && (it.weightKg ?: 0.0) > 0.0 }
        .map { it.weightKg!! }

private fun dayOf(s: Session): LocalDate? = runCatching { LocalDate.parse(s.startedAt.take(10)) }.getOrNull()

/** Largest level not above [value]; the smallest level if all are above; 0.5 kg steps without levels. */
fun roundDownToLevel(value: Double, levels: List<Double>?): Double {
    if (levels.isNullOrEmpty()) return maxOf(0.0, floor(value / 0.5 + 1e-9) * 0.5)
    val sorted = levels.sorted()
    return sorted.lastOrNull { it <= value + 1e-9 } ?: sorted.first()
}

/**
 * Cross-gym start estimate for the first time [exerciseId] is done in [targetGymId]
 * (no same-gym history). Returns null when it does not apply or no data exists.
 *
 * a) Ratio: paired observations (sessions <= 14 days apart) of exercises that share
 *    equipment and load basis with the target exercise, between the target gym and the
 *    gym G of the most recent other-gym data; estimate = median(target/G) x working weight in G.
 * b) Otherwise working weight in G x 0.85.
 * c) The result is rounded DOWN to the target gym's levels.
 * The working weight in G is the proven one, else the most frequent weight of the latest session there.
 */
fun estimateStartWeight(
    exerciseId: String,
    targetGymId: String,
    sessions: List<Pair<String, Session>>,
    exercises: List<ExerciseInfo>,
    targetLevels: List<Double>?,
): StartSuggestion? {
    val completed = completedWithGym(sessions)
    fun hasWeights(s: Session) = s.exercises.any { it.exerciseId == exerciseId && workingSetWeights(it).isNotEmpty() }
    if (completed.any { it.second.gymId == targetGymId && hasWeights(it.second) }) return null
    val latestOther = completed.filter { it.second.gymId != targetGymId && hasWeights(it.second) }
        .maxWithOrNull(compareBy({ it.second.effectiveTime }, { it.first })) ?: return null
    val sourceGym = latestOther.second.gymId!!

    val workInSource = recalculateWorkingWeights(completed, setOf(GymExerciseKey(sourceGym, exerciseId)))
        .values.firstOrNull()?.weightKg
        ?: historicalWorkingWeight(exerciseId, completed.map { it.second }, sourceGym)?.weightKg
        ?: return null

    val target = exercises.firstOrNull { it.id == exerciseId }
    val sharing = exercises.filter { target != null && it.loadBasis == target.loadBasis && it.equipment == target.equipment }
        .map { it.id }.toSet() + exerciseId

    val ratios = mutableListOf<Double>()
    val inTarget = completed.filter { it.second.gymId == targetGymId }
    val inSource = completed.filter { it.second.gymId == sourceGym }
    for ((_, st) in inTarget) {
        val dt = dayOf(st) ?: continue
        for ((_, sg) in inSource) {
            val dg = dayOf(sg) ?: continue
            if (abs(ChronoUnit.DAYS.between(dt, dg)) > 14) continue
            for (ex in sharing) {
                val wt = st.exercises.filter { it.exerciseId == ex }.flatMap(::workingSetWeights)
                val wg = sg.exercises.filter { it.exerciseId == ex }.flatMap(::workingSetWeights)
                if (wt.isNotEmpty() && wg.isNotEmpty()) ratios += median(wt) / median(wg)
            }
        }
    }
    val estimate = if (ratios.isNotEmpty()) median(ratios) * workInSource else workInSource * 0.85
    return StartSuggestion(roundDownToLevel(estimate, targetLevels), SuggestionReason.CROSS_GYM_ESTIMATE, sourceGym)
}

private fun reachedLowerBound(set: SetLog, ex: SessionExercise): Boolean {
    if (set.status != SetStatus.COMPLETED) return false
    val value = when (ex.target.metric) {
        Metric.SECONDS -> set.seconds?.toDouble()
        Metric.METERS -> set.meters
        else -> set.reps?.toDouble()
    } ?: return false
    return value >= ex.target.min
}

/** The exercise was tried at (or beyond) [weightKg] and no such working set reached the lower bound of the target. */
private fun failedAt(ex: SessionExercise, weightKg: Double, lower: Boolean): Boolean {
    val attempts = ex.sets.filter { set ->
        val w = set.weightKg
        set.setType == SetType.WORKING && w != null &&
            (set.status == SetStatus.COMPLETED || set.status == SetStatus.FAILED) &&
            (if (lower) w <= weightKg + 1e-9 else w >= weightKg - 1e-9)
    }
    return attempts.isNotEmpty() && attempts.none { reachedLowerBound(it, ex) }
}

/**
 * Whether the last two completed sessions in [gymId] that contain [exerciseId] both failed at the working weight
 * [workingWeightKg] (watch design spec K: after a miss hold, after two sessions in a row one step lower). A session in
 * which the weight was not tried at all does not count as a failure.
 */
fun failedTwiceAtWorkingWeight(
    exerciseId: String,
    gymId: String,
    sessions: List<Pair<String, Session>>,
    workingWeightKg: Double,
    lower: Boolean = false,
): Boolean {
    val recent = completedWithGym(sessions)
        .filter { (_, s) -> s.gymId == gymId && s.exercises.any { it.exerciseId == exerciseId } }
        .sortedWith(compareByDescending<Pair<String, Session>> { it.second.effectiveTime }.thenByDescending { it.first })
        .take(2)
    if (recent.size < 2) return false
    return recent.all { (_, s) -> s.exercises.filter { it.exerciseId == exerciseId }.any { failedAt(it, workingWeightKg, lower) } }
}

/**
 * Start weight for an exercise in a gym, in priority order: the gym's stored working weight (one step lighter after two
 * sessions in a row that failed at it, [failedTwiceAtWorkingWeight], reason `deload_after_failures`),
 * the latest same-gym workout, the cross-gym estimate, the guided related-exercise estimate,
 * and finally the legacy plan weight. Always rounded to the gym's levels (cross-gym: down).
 */
fun suggestStartWeight(
    exerciseId: String,
    gymId: String,
    sessions: List<Pair<String, Session>>,
    exercises: List<ExerciseInfo>,
    levels: List<Double>?,
    storedWorkingWeightKg: Double?,
    legacyPlanWeightKg: Double?,
): StartSuggestion? {
    storedWorkingWeightKg?.let { working ->
        val lower = exercises.firstOrNull { it.id == exerciseId }?.loadDirection == LoadDirection.LOWER_ASSISTANCE
        if (failedTwiceAtWorkingWeight(exerciseId, gymId, sessions, working, lower)) {
            val lighter = if (lower) nextStepUp(working, levels.orEmpty()) else nextStepDown(working, levels.orEmpty())
            if (lighter != null) return StartSuggestion(nearestAvailableWeight(lighter, levels), SuggestionReason.DELOAD_AFTER_FAILURES)
        }
        return StartSuggestion(nearestAvailableWeight(working, levels), SuggestionReason.GYM_WORKING_WEIGHT)
    }
    val plain = sessions.map { it.second }
    historicalWorkingWeight(exerciseId, plain.filter { it.gymId != null }, gymId)?.let {
        return StartSuggestion(nearestAvailableWeight(it.weightKg, levels), SuggestionReason.GYM_HISTORY)
    }
    estimateStartWeight(exerciseId, gymId, sessions, exercises, levels)?.let { return it }
    exercises.firstOrNull { it.id == exerciseId }?.let { info ->
        guidedStartingEstimate(info, plain, exercises, levels)?.let {
            return StartSuggestion(it.weightKg, SuggestionReason.GUIDED_ESTIMATE)
        }
    }
    return legacyPlanWeightKg?.let { StartSuggestion(nearestAvailableWeight(it, levels), SuggestionReason.PLAN_WEIGHT) }
}

/** Values the workout screen (and the notification action) fill in for the next pending set. */
data class SetPrefill(val weightKg: Double?, val value: Double, val effort: String)

fun prefillSet(ex: SessionExercise, availableWeights: List<Double>): SetPrefill {
    val weighted = ex.measurementType in WEIGHTED_TYPES
    val last = ex.sets.lastOrNull { it.status == SetStatus.COMPLETED && it.weightKg != null }?.weightKg
    // a weight the assistant planned for the next open set wins until the next log (amendment D)
    val planned = ex.sets.firstOrNull { it.status == SetStatus.PENDING && it.setType == SetType.WORKING }?.weightKg
    val weight = if (weighted) planned ?: last ?: ex.suggestedWeightKg ?: availableWeights.minOrNull() else null
    return SetPrefill(weight, ex.target.max, targetEffortBand(ex.target))
}

/** The effort band that matches the target reserve (the preselection of the effort pickers): rir_min 1 gives "one_two". */
fun targetEffortBand(target: de.baschek.gymapp.core.model.Target): String = when {
    target.rirMin >= 5 -> "five_plus"
    target.rirMin >= 3 -> "three_four"
    target.rirMin >= 1 -> "one_two"
    else -> "limit"
}

val WEIGHTED_TYPES = setOf(
    "load_reps", "added_weight_reps", "assisted_reps", "load_duration", "load_distance_duration",
)
