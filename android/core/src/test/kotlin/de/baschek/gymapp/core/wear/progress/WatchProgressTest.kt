package de.baschek.gymapp.core.wear.progress

import de.baschek.gymapp.core.model.*
import de.baschek.gymapp.core.wear.events.WatchCatalog
import de.baschek.gymapp.core.wear.events.WorkoutProfile
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WatchProgressTest {
    private inline fun <reified T> raw(v: T) = DocumentCodec.encodeMerged(v, null)
    private val today = LocalDate.parse("2026-11-17")
    private val zone = ZoneId.of("Europe/Berlin")
    private fun session(date: String, gym: String? = "a", vararg exercises: SessionExercise) = Session(
        gymId = gym, status = SessionStatus.COMPLETED, startedAt = date, completedAt = date, exercises = exercises.toList())
    private fun ex(id: String, weight: Double?, value: Int?, type: String = MeasurementType.LOAD_REPS,
                   metric: String = Metric.REPS, basis: String = LoadBasis.STACK, direction: String = LoadDirection.HIGHER,
                   setType: String = SetType.WORKING, timer: Int? = null) = SessionExercise(
        id = "exercise-$id", exerciseId = id, name = id, measurementType = type, loadDirection = direction,
        loadBasis = basis, target = Target(metric = metric), sets = listOf(SetLog(id = "set-$id", setType = setType,
            status = SetStatus.COMPLETED, weightKg = weight, reps = value.takeIf { metric == Metric.REPS },
            seconds = value.takeIf { metric == Metric.SECONDS }, setDurationSeconds = timer)))
    private fun catalog(sessions: Map<String, Session>, machines: Map<String, MachineProfile> = emptyMap()) = WatchCatalog(
        profile = WorkoutProfile.TEST, gyms = mapOf("a" to raw(Gym(name = "A")), "b" to raw(Gym(name = "B"))),
        sessions = sessions.mapValues { raw(it.value) }, machineProfiles = machines.mapValues { raw(it.value) })

    @Test fun `frequency counts completed sessions by local day and history is read only`() {
        val c = catalog(mapOf(
            "today" to session("2026-11-16T23:30:00Z"), // Tuesday in Berlin
            "six" to session("2026-11-11T12:00:00Z"),
            "older" to session("2026-11-10T12:00:00Z"),
            "future" to session("2026-11-18T12:00:00Z"),
            "active" to Session(status = SessionStatus.ACTIVE, startedAt = "2026-11-17T12:00:00Z")))
        val before = c.sessions.toMap(); val result = watchProgress(c, today, zone, 2)
        assertEquals(2, result.last7DaysCount)
        assertEquals(listOf(2, 1), result.weeklySessions.map { it.count })
        assertEquals(listOf("future", "today", "six", "older"), result.history.map { it.sessionId })
        assertEquals(before, c.sessions)
    }
    @Test fun `working weights come from each gym and latest stored profile`() {
        val m = mapOf("a1" to MachineProfile(gymId = "a", exerciseId = "e", workingWeightKg = 20.0, workingWeightUpdatedAt = "2026-01-01T00:00:00Z"),
            "a2" to MachineProfile(gymId = "a", exerciseId = "e", workingWeightKg = 25.0, workingWeightUpdatedAt = "2026-02-01T00:00:00Z"),
            "b1" to MachineProfile(gymId = "b", exerciseId = "e", workingWeightKg = 60.0))
        assertEquals(listOf("a" to 25.0, "b" to 60.0), watchProgress(catalog(emptyMap(), m), today, zone).workingWeights.map { it.gymId to it.weightKg })
    }
    @Test fun `assistance uses lowest value and gyms and load types stay separate`() {
        val assistedA = ex("pull", 30.0, 10, MeasurementType.ASSISTED_REPS, basis = LoadBasis.ASSISTANCE, direction = LoadDirection.LOWER_ASSISTANCE)
        val assistedB = ex("pull", 10.0, 10, MeasurementType.ASSISTED_REPS, basis = LoadBasis.ASSISTANCE, direction = LoadDirection.LOWER_ASSISTANCE)
        val assistanceBadDirection = ex("bad", 99.0, 10, MeasurementType.ASSISTED_REPS, basis = LoadBasis.ASSISTANCE)
        val result = watchProgress(catalog(mapOf("one" to session("2026-11-14T12:00:00Z", "a", assistedA),
            "two" to session("2026-11-15T12:00:00Z", "a", assistedB, assistanceBadDirection),
            "three" to session("2026-11-16T12:00:00Z", "b", assistedA))), today, zone)
        assertEquals(mapOf<String?, Double>("a" to 10.0, "b" to 30.0), result.personalBests.associate { it.gymId to it.value })
        assertTrue(result.personalBests.all { it.loadDirection == LoadDirection.LOWER_ASSISTANCE })
    }
    @Test fun `missing timed result and warmups do not become personal bests`() {
        val missing = ex("hold", 20.0, null, MeasurementType.LOAD_DURATION, Metric.SECONDS, timer = 40)
        val warmup = ex("press", 100.0, 10, setType = SetType.WARMUP)
        val measured = ex("measured", 20.0, 35, MeasurementType.LOAD_DURATION, Metric.SECONDS)
        val result = watchProgress(catalog(mapOf("s" to session("2026-11-16T12:00:00Z", "a", missing, warmup, measured))), today, zone)
        assertEquals(listOf("measured"), result.personalBests.map { it.exerciseId })
        assertEquals("kg", result.personalBests.single().unit)
    }
}
