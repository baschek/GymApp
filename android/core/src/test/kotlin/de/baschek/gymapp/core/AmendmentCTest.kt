package de.baschek.gymapp.core

import de.baschek.gymapp.core.model.DocumentCodec
import de.baschek.gymapp.core.model.Exercise
import de.baschek.gymapp.core.model.Goal
import de.baschek.gymapp.core.model.Gym
import de.baschek.gymapp.core.model.GymJson
import de.baschek.gymapp.core.model.MachineProfile
import de.baschek.gymapp.core.model.ModeSets
import de.baschek.gymapp.core.model.MuscleGroup
import de.baschek.gymapp.core.model.MuscleMapping
import de.baschek.gymapp.core.model.Plan
import de.baschek.gymapp.core.model.PlanExercise
import de.baschek.gymapp.core.model.RadarArea
import de.baschek.gymapp.core.model.Session
import de.baschek.gymapp.core.model.SessionExercise
import de.baschek.gymapp.core.model.SessionStatus
import de.baschek.gymapp.core.model.SetLog
import de.baschek.gymapp.core.model.SetStatus
import de.baschek.gymapp.core.model.SetType
import de.baschek.gymapp.core.model.TargetSets
import de.baschek.gymapp.core.model.effectiveAddons
import de.baschek.gymapp.core.model.RangeSpec
import de.baschek.gymapp.core.model.levelsMatch
import de.baschek.gymapp.core.model.rangeSpec
import de.baschek.gymapp.core.model.WeightRange
import de.baschek.gymapp.core.model.forArea
import de.baschek.gymapp.core.progression.freeWeights
import de.baschek.gymapp.core.progression.nearestLevelIndex
import de.baschek.gymapp.core.progression.splitTotal
import de.baschek.gymapp.core.stats.PackState
import de.baschek.gymapp.core.stats.StatsPeriod
import de.baschek.gymapp.core.stats.estimateMinutes
import de.baschek.gymapp.core.stats.formatSets
import de.baschek.gymapp.core.stats.goalFactor
import de.baschek.gymapp.core.stats.leadingBlanks
import de.baschek.gymapp.core.stats.lowAreas
import de.baschek.gymapp.core.stats.radarAxes
import de.baschek.gymapp.core.stats.radarRadius
import de.baschek.gymapp.core.stats.recommendPlans
import de.baschek.gymapp.core.stats.sessionsByDay
import de.baschek.gymapp.core.stats.start
import de.baschek.gymapp.core.stats.TrendStatus
import de.baschek.gymapp.core.stats.buildTrend
import de.baschek.gymapp.core.stats.summarizeMuscles
import de.baschek.gymapp.core.training.SkipReason
import de.baschek.gymapp.core.training.addExercise
import de.baschek.gymapp.core.training.canMoveExercise
import de.baschek.gymapp.core.training.insertWarmup
import de.baschek.gymapp.core.training.isSkipped
import de.baschek.gymapp.core.training.moveExercise
import de.baschek.gymapp.core.training.skipSet
import de.baschek.gymapp.core.training.templateFor
import de.baschek.gymapp.core.training.toggleSkipExercise
import de.baschek.gymapp.core.training.warmupProposal
import kotlinx.serialization.json.jsonObject
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Contract v1 amendment C and the pure logic behind the new screens (synthetic data only). */
class AmendmentCTest {
    private fun set(id: String, status: String = SetStatus.COMPLETED, type: String = SetType.WORKING, w: Double? = 20.0) =
        SetLog(id = id, order = 1, setType = type, status = status, weightKg = if (status == SetStatus.PENDING) null else w, reps = 10, effort = "one_two")

    private fun ex(id: String, exerciseId: String, vararg sets: SetLog) =
        SessionExercise(id = id, exerciseId = exerciseId, name = "Ex $exerciseId", sets = sets.toList())

    private fun session(
        at: String,
        planId: String? = "p1",
        status: String = SessionStatus.COMPLETED,
        vararg exs: SessionExercise,
    ) = Session(gymId = "g", planId = planId, planName = "Plan", status = status, startedAt = at, completedAt = at, exercises = exs.toList())

    // ---- documents ---------------------------------------------------------

    @Test
    fun newFieldsRoundTripAndUnknownFieldsSurvive() {
        val gymRaw = GymJson.parseToJsonElement("""{"name":"Synthetic gym","color":"#112233","subtitle":"Line","logo":"keep"}""").jsonObject
        val gym = DocumentCodec.decode<Gym>(gymRaw)
        assertEquals("#112233", gym.color)
        assertEquals("Line", gym.subtitle)
        assertEquals("keep", DocumentCodec.encodeMerged(gym.copy(color = "#445566"), gymRaw)["logo"]!!.toString().trim('"'))

        val mp = DocumentCodec.decode<MachineProfile>(
            GymJson.parseToJsonElement("""{"gym_id":"g","exercise_id":"e","addon_weights_kg":[2.5,1.25,2.5,0]}""").jsonObject,
        )
        assertEquals(listOf(1.25, 2.5), mp.effectiveAddons())

        val setRaw = GymJson.parseToJsonElement("""{"id":"s","order":1,"weight_kg":27.5,"addon_weights_kg":[2.5]}""").jsonObject
        assertEquals(listOf(2.5), DocumentCodec.decode<SetLog>(setRaw).addonWeightsKg)

        val plan = DocumentCodec.decode<Plan>(
            GymJson.parseToJsonElement("""{"name":"P","changed_by_assistant_at":"2026-10-28T10:00:00Z","change_summary":"Mehr Sätze."}""").jsonObject,
        )
        assertEquals("2026-10-28T10:00:00Z", plan.changedByAssistantAt)
        assertEquals("Mehr Sätze.", plan.changeSummary)

        val goal = DocumentCodec.decode<Goal>(
            GymJson.parseToJsonElement("""{"training_days_per_week":4,"target_sets":{"brust":12,"schultern":8,"arme":12,"beine":20,"bauch":8,"ruecken":12},"updated_by":"server"}""").jsonObject,
        )
        assertEquals(4, goal.trainingDaysPerWeek)
        assertEquals(20, goal.targetSets.beine)
        assertEquals("server", goal.updatedBy)
    }

    // ---- muscle mapping ----------------------------------------------------

    @Test
    fun mappingFileCoversTheLegacyCatalogAndOnlyUsesTheFixedGroups() {
        val legacy = listOf(
            "chest", "lats", "middle back", "lower back", "shoulders", "biceps", "triceps", "forearms", "abdominals",
            "quadriceps", "hamstrings", "glutes", "calves", "traps", "neck", "adductors", "abductors",
        )
        for (name in legacy) assertNotNull(MuscleMapping.table[name], "missing mapping for $name")
        assertTrue(MuscleMapping.table.values.all { it in MuscleGroup.ALL })
    }

    @Test
    fun groupsAreDerivedFromLegacyMusclesWhenMissingAndOwnGroupsWin() {
        val legacy = Exercise(name = "x", primaryMuscles = listOf("chest"), secondaryMuscles = listOf("triceps", "shoulders", "Chest"))
        assertEquals(listOf("brust", "trizeps", "schultern"), MuscleMapping.groupsOf(legacy))
        val own = legacy.copy(muscleGroups = listOf("bauch", "unbekannt"))
        assertEquals(listOf("bauch"), MuscleMapping.groupsOf(own))
        assertEquals(emptyList(), MuscleMapping.groupsOf(Exercise(name = "none", primaryMuscles = listOf("unknown muscle"))))
    }

    @Test
    fun radarAreasPartitionTheTwelveGroups() {
        assertEquals(MuscleGroup.ALL.sorted(), RadarArea.entries.flatMap { it.groups }.sorted())
        assertEquals(RadarArea.ARME, RadarArea.of("trizeps"))
        assertEquals(RadarArea.BAUCH, RadarArea.of("unterer_ruecken"))
    }

    // ---- aggregation -------------------------------------------------------

    private val groups = mapOf("bench" to listOf("brust", "trizeps", "schultern"), "row" to listOf("ruecken", "bizeps"))
    private val zone = ZoneId.of("Europe/Berlin")
    private val now = Instant.parse("2026-11-02T12:00:00Z")

    @Test
    fun primaryCountsOneAndFurtherGroupsHalf() {
        val s = session(
            "2026-11-02T09:00:00Z", "p1", SessionStatus.COMPLETED,
            ex("a", "bench", set("1"), set("2"), set("3", type = SetType.WARMUP), set("4", status = SetStatus.SKIPPED_TIME)),
            ex("b", "row", set("5")),
        )
        val sum = summarizeMuscles(listOf(s), { groups[it].orEmpty() }, StatsPeriod.TODAY.start(now, zone))
        assertEquals(2.0, sum.totals["brust"])
        assertEquals(1.0, sum.totals["trizeps"])
        assertEquals(1.0, sum.totals["schultern"])
        assertEquals(1.0, sum.totals["ruecken"])
        assertEquals(0.5, sum.totals["bizeps"])
        assertEquals("brust", sum.top(1).single().first)
        assertEquals(listOf(2), sum.details["brust"]!!.map { it.sets })
        assertEquals("2026-11-02", sum.details["trizeps"]!!.single().lastDate)
    }

    @Test
    fun periodsFilterByLocalDayAndStatus() {
        val old = session("2026-10-20T09:00:00Z", "p1", SessionStatus.COMPLETED, ex("a", "bench", set("1")))
        val yesterday = session("2026-11-01T09:00:00Z", "p1", SessionStatus.COMPLETED, ex("a", "bench", set("1")))
        val active = session("2026-11-02T09:00:00Z", "p1", SessionStatus.ACTIVE, ex("a", "bench", set("1")))
        val all = listOf(old, yesterday, active)
        fun brust(p: StatsPeriod) = summarizeMuscles(all, { groups[it].orEmpty() }, p.start(now, zone)).totals["brust"]
        assertNull(brust(StatsPeriod.TODAY))
        assertEquals(1.0, brust(StatsPeriod.WEEK))
        assertEquals(2.0, brust(StatsPeriod.MONTH))
        // local midnight: 23:30Z on Nov 1st is already Nov 2nd in Berlin
        val late = session("2026-11-01T23:30:00Z", "p1", SessionStatus.COMPLETED, ex("a", "bench", set("1")))
        assertEquals(1.0, summarizeMuscles(listOf(late), { groups[it].orEmpty() }, StatsPeriod.TODAY.start(now, zone)).totals["brust"])
    }

    @Test
    fun radarComparesToScaledGoalsAndFindsGaps() {
        val targets = TargetSets(brust = 12, schultern = 8, arme = 12, beine = 20, bauch = 8, ruecken = 12)
        val totals = mapOf("brust" to 12.0, "schultern" to 2.0, "bizeps" to 4.0, "trizeps" to 5.0, "unterarme" to 1.0, "ruecken" to 10.0)
        val axes = radarAxes(totals, targets, StatsPeriod.WEEK, 3)
        assertEquals(10.0, axes.first { it.area == RadarArea.ARME }.value)
        assertEquals(0.0, axes.first { it.area == RadarArea.BEINE }.value)
        assertEquals(listOf(RadarArea.BEINE, RadarArea.BAUCH, RadarArea.SCHULTERN), lowAreas(axes).map { it.area })
        assertEquals(4.0, radarAxes(totals, targets, StatsPeriod.TODAY, 3).first { it.area == RadarArea.BRUST }.goal, 1e-9)
        assertEquals(1.0 / 3, goalFactor(StatsPeriod.TODAY, 3), 1e-9)
        assertEquals(4.0, goalFactor(StatsPeriod.MONTH, 3))
        assertEquals(12.0 / (20.0 * 1.05), radarRadius(12.0, axes), 1e-9)
        assertEquals(RadarArea.entries.size, axes.size)
        assertEquals(12, targets.forArea(RadarArea.BRUST))
    }

    @Test
    fun setCountsAreFormattedWithAGermanComma() {
        assertEquals("4", formatSets(4.0))
        assertEquals("4,5", formatSets(4.5))
    }

    // ---- recommendation & estimate ----------------------------------------

    @Test
    fun theLongestAgoPlanIsRecommendedAndNeverDoneComesFirst() {
        val plans = listOf("a" to "Push", "b" to "Pull", "c" to "Beine")
        val sessions = listOf(
            session("2026-10-30T10:00:00Z", "a"),
            session("2026-10-25T10:00:00Z", "b"),
            session("2026-10-01T10:00:00Z", "a"),
            session("2026-10-31T10:00:00Z", null),
        )
        val r = recommendPlans(plans, sessions, now)
        assertEquals(listOf("c", "b", "a"), r.map { it.planId })
        assertNull(r[0].daysAgo)
        assertEquals(8, r[1].daysAgo)
        assertEquals(3, r[2].daysAgo)
        // ties and no history keep the order
        assertEquals(listOf("a", "b", "c"), recommendPlans(plans, emptyList(), now).map { it.planId })
    }

    @Test
    fun estimatedMinutesFollowSetsRestAndMode() {
        val e = PlanExercise(id = "1", exerciseId = "x", modeSets = ModeSets(3, 2, 1), restSeconds = 90)
        val normal = estimateMinutes(listOf(e), "normal")
        val fast = estimateMinutes(listOf(e), "fast")
        val ultra = estimateMinutes(listOf(e), "ultra")
        assertTrue(normal > fast && fast > ultra && ultra > 0)
        assertEquals(8, normal) // 90 + 3 * 135 = 495 s
        assertEquals(0, estimateMinutes(listOf(e.copy(modeSets = ModeSets(0, 0, 0))), "normal"))
    }

    // ---- misc --------------------------------------------------------------

    @Test
    fun packStateResetsOnANewDay() {
        val raw = PackState.encode("2026-11-02", setOf("b", "a"))
        assertEquals(setOf("a", "b"), PackState.decode(raw, "2026-11-02"))
        assertEquals(emptySet(), PackState.decode(raw, "2026-11-03"))
        assertEquals(emptySet(), PackState.decode(null, "2026-11-03"))
        assertEquals(setOf("a"), PackState.toggle(setOf("a", "b"), "b"))
        assertEquals(emptySet(), PackState.decode(PackState.encode("2026-11-02", emptySet()), "2026-11-02"))
    }

    @Test
    fun packStateFallsBackToDefaultsOnlyWhenNothingWasSavedToday() {
        assertNull(PackState.decodeOrNull(null, "2026-11-02"))
        assertNull(PackState.decodeOrNull(PackState.encode("2026-11-01", setOf("a")), "2026-11-02"))
        assertEquals(emptySet(), PackState.decodeOrNull(PackState.encode("2026-11-02", emptySet()), "2026-11-02"))
    }

    @Test
    fun machineRangeIsReadFromRangesOrInferredFromLevels() {
        val ranged = MachineProfile(weightRanges = listOf(WeightRange("r", 5.0, 100.0, 5.0)))
        assertEquals(RangeSpec(5.0, 100.0, 5.0), ranged.rangeSpec())
        assertEquals(20, ranged.rangeSpec()!!.count)
        assertTrue(ranged.levelsMatch(ranged.rangeSpec()!!))
        val listed = MachineProfile(availableWeightsKg = listOf(10.0, 20.0, 30.0, 35.0, 40.0))
        assertEquals(RangeSpec(10.0, 40.0, 5.0), listed.rangeSpec())
        assertFalse(listed.levelsMatch(listed.rangeSpec()!!))
        assertNull(MachineProfile().rangeSpec())
    }

    @Test
    fun calendarGroupsSessionsByLocalDay() {
        val s = listOf(session("2026-10-30T10:00:00Z"), session("2026-10-30T18:00:00Z"), session("2026-11-02T10:00:00Z"))
        val byDay = sessionsByDay(s, YearMonth.of(2026, 10), zone)
        assertEquals(setOf(30), byDay.keys)
        assertEquals(2, byDay[30]!!.size)
        assertEquals(3, leadingBlanks(YearMonth.of(2026, 10))) // 1 Oct 2026 is a Thursday
    }

    @Test
    fun freeStepsAndNearestLevel() {
        val free = freeWeights(10.0)
        assertEquals(listOf(2.5, 5.0, 7.5, 10.0), free)
        assertEquals(1, nearestLevelIndex(listOf(5.0, 10.0, 15.0), 9.0))
        assertEquals(-1, nearestLevelIndex(emptyList(), 9.0))
        assertEquals(25.0, splitTotal(27.5, listOf(20.0, 25.0), listOf(2.5))!!.levelKg)
    }

    // ---- trend -------------------------------------------------------------

    private fun trendSession(day: Int, weight: Double, gym: String = "g") = Session(
        gymId = gym, planName = "P", status = SessionStatus.COMPLETED, startedAt = "2026-10-%02dT10:00:00Z".format(day),
        completedAt = "2026-10-%02dT11:00:00Z".format(day),
        exercises = listOf(ex("e", "x", set("s1", w = weight), set("s2", w = weight - 5))),
    )

    @Test
    fun trendNeedsThreeSessionsAndSeparatesGyms() {
        val two = listOf(trendSession(1, 40.0), trendSession(2, 45.0))
        assertEquals(TrendStatus.NOT_ENOUGH_DATA, buildTrend(two, "x").status)
        val three = two + trendSession(3, 50.0) + trendSession(4, 100.0, gym = "other")
        val t = buildTrend(three, "x", "g")
        assertEquals(listOf(40.0, 45.0, 50.0), t.weights)
        assertEquals(TrendStatus.RISING, t.status)
        assertEquals(50.0, t.best)
        assertEquals(4, buildTrend(three, "x").weights.size)
        assertTrue(t.oneRepMax.all { it > 0 })
    }

    @Test
    fun noGainInTheLastThreeSessionsIsStagnation() {
        val s = listOf(trendSession(1, 40.0), trendSession(2, 50.0), trendSession(3, 50.0), trendSession(4, 47.5), trendSession(5, 50.0))
        assertEquals(TrendStatus.STAGNATING, buildTrend(s, "x").status)
        val rising = s.dropLast(1) + trendSession(5, 52.5)
        assertEquals(TrendStatus.RISING, buildTrend(rising, "x").status)
    }

    // ---- session edits -----------------------------------------------------

    private fun running(): Session = Session(
        gymId = "g", planName = "P", status = SessionStatus.ACTIVE, startedAt = "2026-11-02T09:00:00Z",
        exercises = listOf(
            ex("e1", "x1", set("a1"), set("a2", SetStatus.PENDING)),
            ex("e2", "x2", set("b1", SetStatus.PENDING), set("b2", SetStatus.PENDING)),
            ex("e3", "x3", set("c1", SetStatus.PENDING)),
            ex("e4", "x4", set("d1", SetStatus.PENDING)),
        ).mapIndexed { i, e -> e.copy(order = i + 1) },
    )

    @Test
    fun exercisesWithOpenSetsCanBeMovedAlsoInFrontOfTheCurrentOne() {
        val s = running()
        // finished exercises stay where they are; e1 still has an open set, so it can move
        assertTrue(canMoveExercise(s, "e1", 1))
        assertFalse(canMoveExercise(s, "e1", -1)) // already first
        val moved = moveExercise(s, "e3", 1)
        assertEquals(listOf("e1", "e2", "e4", "e3"), moved.exercises.map { it.id })
        assertEquals(listOf(1, 2, 3, 4), moved.exercises.map { it.order })
        assertFalse(canMoveExercise(moved, "e3", 1))
        // in front of the current exercise: it becomes the current one
        val front = moveExercise(s, "e2", -1)
        assertEquals(listOf("e2", "e1", "e3", "e4"), front.exercises.map { it.id })
    }

    @Test
    fun skippingAnExerciseIsReversibleUntilItStarts() {
        val s = running()
        val skipped = toggleSkipExercise(s, "e3")
        assertTrue(isSkipped(skipped.exercises[2]))
        assertFalse(isSkipped(toggleSkipExercise(skipped, "e3").exercises[2]))
        // started exercises are untouched
        assertEquals(s.exercises[0], toggleSkipExercise(s, "e1").exercises[0])
    }

    @Test
    fun skippedSetsKeepTheirReason() {
        val s = skipSet(running(), "e2", "b1", SkipReason.PAIN)
        assertEquals(SetStatus.PAIN, s.exercises[1].sets[0].status)
        assertEquals(SetStatus.PENDING, s.exercises[1].sets[1].status)
        assertEquals(SetStatus.SKIPPED_EQUIPMENT, skipSet(running(), "e2", "b1", SkipReason.EQUIPMENT).exercises[1].sets[0].status)
        // a completed set cannot be skipped afterwards
        assertEquals(SetStatus.COMPLETED, skipSet(running(), "e1", "a1", SkipReason.TIME).exercises[0].sets[0].status)
    }

    @Test
    fun warmupGoesBeforeTheFirstPendingSetAndRenumbers() {
        var n = 0
        val s = insertWarmup(running(), "e2", { "w${++n}" })
        val sets = s.exercises[1].sets
        assertEquals(listOf(SetType.WARMUP, SetType.WORKING, SetType.WORKING), sets.map { it.setType })
        assertEquals(listOf(1, 2, 3), sets.map { it.order })
        assertEquals(SetStatus.PENDING, sets[0].status)
    }

    @Test
    fun addedExercisesAreAppendedWithPendingSets() {
        var n = 0
        val s = addExercise(running(), templateFor("x9", "Nine", "load_reps", "higher", "stack"), 3, { "n${++n}" })
        val added = s.exercises.last()
        assertEquals(5, added.order)
        assertEquals(3, added.sets.size)
        assertTrue(added.sets.all { it.status == SetStatus.PENDING })
    }

    @Test
    fun warmupProposalIsHalfTheWeightOnALevel() {
        assertEquals(30.0, warmupProposal(60.0, listOf(10.0, 20.0, 30.0, 40.0)).weightKg)
        assertEquals(27.5, warmupProposal(55.0, emptyList()).weightKg)
        assertEquals(12, warmupProposal(55.0, emptyList()).reps)
    }
}
