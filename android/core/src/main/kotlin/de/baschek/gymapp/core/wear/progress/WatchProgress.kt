package de.baschek.gymapp.core.wear.progress

import de.baschek.gymapp.core.model.*
import de.baschek.gymapp.core.progression.WEIGHTED_TYPES
import de.baschek.gymapp.core.wear.events.WatchCatalog
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlinx.serialization.json.JsonObject

/** A local calendar week, starting Monday. Counts are sessions, not sets or training days. */
data class WeekCount(val start: LocalDate, val count: Int)
data class GymWorkingWeight(val gymId: String, val gymName: String, val exerciseId: String, val exerciseName: String, val weightKg: Double)
data class PersonalBest(
    val gymId: String?, val gymName: String?, val exerciseId: String, val exerciseName: String,
    val measurementType: String, val metric: String, val loadBasis: String, val loadDirection: String,
    val value: Double, val unit: String, val sessionId: String, val setId: String,
)
data class HistoryRow(val sessionId: String, val session: Session, val gymName: String?)
data class WatchProgress(
    /** As in the phone progress overview, today and the preceding six local calendar days. */
    val last7DaysCount: Int,
    /** Oldest first, including the current partial calendar week. */
    val weeklySessions: List<WeekCount>,
    val workingWeights: List<GymWorkingWeight>,
    val personalBests: List<PersonalBest>,
    val history: List<HistoryRow>,
)

private inline fun <reified T> parse(raw: JsonObject): T? = runCatching { DocumentCodec.decode<T>(raw) }.getOrNull()
private fun day(session: Session, zone: ZoneId): LocalDate? =
    runCatching { Instant.parse(session.effectiveTime).atZone(zone).toLocalDate() }.getOrNull()

/**
 * Projects only the provided profile's already cached documents. Does not access storage or start a workout.
 * A weighted best is grouped by gym, type, metric, basis and direction; unknown gyms are kept separate
 * per session because machine loads cannot be compared safely. Only completed working sets with an
 * actual recorded target metric contribute. A set's elapsed timer is not a substitute for measured seconds.
 */
fun watchProgress(catalog: WatchCatalog, today: LocalDate, zone: ZoneId = ZoneId.systemDefault(), weekCount: Int = 4): WatchProgress {
    val gyms = catalog.gyms.mapNotNull { (id, raw) -> parse<Gym>(raw)?.let { id to it } }.toMap()
    val exercises = catalog.exercises.mapNotNull { (id, raw) -> parse<Exercise>(raw)?.let { id to it } }.toMap()
    val history = catalog.sessions.mapNotNull { (id, raw) ->
        parse<Session>(raw)?.takeIf { it.status == SessionStatus.COMPLETED }?.let { HistoryRow(id, it, gyms[it.gymId]?.name) }
    }.sortedWith(compareByDescending<HistoryRow> { runCatching { Instant.parse(it.session.effectiveTime) }.getOrNull() ?: Instant.MIN }.thenBy { it.sessionId })
    val dated = history.mapNotNull { row -> day(row.session, zone)?.let { row to it } }
    val from = today.minusDays(6)
    val lastSeven = dated.count { (_, date) -> date >= from && date <= today }
    val monday = today.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
    val weeks = (weekCount.coerceIn(0, 52) - 1 downTo 0).map { offset ->
        val start = monday.minusWeeks(offset.toLong())
        WeekCount(start, dated.count { (_, date) -> date >= start && date <= today && date < start.plusWeeks(1) })
    }
    // Duplicate machine profiles are not combined: use the latest stored working-weight update.
    val profiles = catalog.machineProfiles.mapNotNull { (id, raw) -> parse<MachineProfile>(raw)?.let { id to it } }
        .filter { (_, p) -> p.gymId.isNotBlank() && p.exerciseId.isNotBlank() && p.workingWeightKg?.let { it.isFinite() && it >= 0.0 } == true }
        .groupBy { (_, p) -> p.gymId to p.exerciseId }
        .values.map { group -> group.maxWithOrNull(compareBy<Pair<String, MachineProfile>> { it.second.workingWeightUpdatedAt ?: "" }.thenBy { it.first })!!.second }
        .map { p -> GymWorkingWeight(p.gymId, gyms[p.gymId]?.name ?: p.gymId, p.exerciseId, exercises[p.exerciseId]?.name ?: p.exerciseId, p.workingWeightKg!!) }
        .sortedWith(compareBy({ it.gymName }, { it.exerciseName }, { it.gymId }, { it.exerciseId }))
    data class Key(val gym: String, val exercise: String, val type: String, val metric: String, val basis: String, val direction: String)
    val best = linkedMapOf<Key, PersonalBest>()
    for (row in history) for (ex in row.session.exercises) {
        if (ex.exerciseId.isBlank()) continue
        for (set in ex.sets) {
            if (set.status != SetStatus.COMPLETED || set.setType != SetType.WORKING || set.id.isBlank()) continue
            val measured = when (ex.target.metric) {
                Metric.REPS -> set.reps?.toDouble()
                Metric.SECONDS -> set.seconds?.toDouble()
                Metric.METERS -> set.meters
                else -> null
            }?.takeIf { it.isFinite() && it > 0.0 } ?: continue
            val weighted = ex.measurementType in WEIGHTED_TYPES
            val value = (if (weighted) set.weightKg else measured)?.takeIf { it.isFinite() && if (weighted) it >= 0.0 else it > 0.0 } ?: continue
            // Do not infer a direction for newer unknown load models.
            if (weighted && ex.loadDirection !in setOf(LoadDirection.HIGHER, LoadDirection.LOWER_ASSISTANCE)) continue
            if (weighted && (ex.measurementType == MeasurementType.ASSISTED_REPS || ex.loadBasis == LoadBasis.ASSISTANCE) &&
                ex.loadDirection != LoadDirection.LOWER_ASSISTANCE) continue
            val unit = if (weighted) "kg" else when (ex.target.metric) { Metric.REPS -> "reps"; Metric.SECONDS -> "s"; Metric.METERS -> "m"; else -> continue }
            val key = Key(row.session.gymId ?: "missing:${row.sessionId}", ex.exerciseId, ex.measurementType, ex.target.metric, ex.loadBasis, ex.loadDirection)
            val candidate = PersonalBest(row.session.gymId, row.gymName, ex.exerciseId, ex.name.ifBlank { exercises[ex.exerciseId]?.name ?: ex.exerciseId },
                ex.measurementType, ex.target.metric, ex.loadBasis, ex.loadDirection, value, unit, row.sessionId, set.id)
            val previous = best[key]
            if (previous == null || if (weighted && ex.loadDirection == LoadDirection.LOWER_ASSISTANCE) value < previous.value else value > previous.value) best[key] = candidate
        }
    }
    return WatchProgress(lastSeven, weeks, profiles, best.values.sortedWith(compareBy({ it.exerciseName }, { it.gymName ?: "" }, { it.measurementType }, { it.metric }, { it.loadBasis }, { it.loadDirection }, { it.sessionId })), history)
}
