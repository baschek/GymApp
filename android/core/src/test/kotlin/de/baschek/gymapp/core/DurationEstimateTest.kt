package de.baschek.gymapp.core

import de.baschek.gymapp.core.model.ModeSets
import de.baschek.gymapp.core.model.PackItem
import de.baschek.gymapp.core.model.PlanExercise
import de.baschek.gymapp.core.model.Session
import de.baschek.gymapp.core.model.SessionStatus
import de.baschek.gymapp.core.stats.PackState
import de.baschek.gymapp.core.stats.estimateDuration
import de.baschek.gymapp.core.stats.estimateMinutes
import de.baschek.gymapp.core.stats.formatEstimate
import de.baschek.gymapp.core.stats.packRows
import kotlin.test.Test
import kotlin.test.assertEquals

class DurationEstimateTest {
    private val plan = listOf(
        PlanExercise(id = "1", exerciseId = "a", modeSets = ModeSets(4, 2, 1), restSeconds = 90),
        PlanExercise(id = "2", exerciseId = "b", modeSets = ModeSets(2, 2, 1), restSeconds = 60),
    )

    private fun s(day: Int, minutes: Int?, mode: String = "normal", planId: String = "p", status: String = SessionStatus.COMPLETED) = Session(
        planId = planId, mode = mode, status = status,
        startedAt = "2026-10-%02dT10:00:00Z".format(day), completedAt = "2026-10-%02dT11:00:00Z".format(day),
        durationSeconds = minutes?.let { it * 60 },
    )

    @Test
    fun formulaIsRealisticAndUsedWithoutHistory() {
        // normal: ex1 90 + 4*135 = 630; ex2 90 + 2*105 = 300 -> 930 s = 15.5 -> 16
        assertEquals(EXPECTED_FORMULA_MIN, estimateDuration("p", plan, "normal", emptyList()).minutes)
        assertEquals(0, estimateDuration("p", plan, "normal", emptyList()).learnedFrom)
        assertEquals(estimateMinutes(plan, "fast"), estimateDuration(null, plan, "fast", listOf(s(1, 50))).minutes)
    }

    @Test
    fun medianOfLastFiveSamePlanAndMode() {
        // newest first: 50, 60, 40, 70, 55 are the last five; the older 5-minute... outlier day 1 is ignored anyway
        val sessions = listOf(s(10, 50), s(9, 60), s(8, 40), s(7, 70), s(6, 55), s(5, 20), s(4, 20))
        val e = estimateDuration("p", plan, "normal", sessions)
        assertEquals(55, e.minutes)
        assertEquals(5, e.learnedFrom)
    }

    @Test
    fun evenCountUsesMeanOfMiddleValues() {
        val e = estimateDuration("p", plan, "normal", listOf(s(2, 40), s(1, 50)))
        assertEquals(45, e.minutes)
        assertEquals(2, e.learnedFrom)
    }

    @Test
    fun ignoresImplausibleMissingActiveAndOtherPlans() {
        val sessions = listOf(
            s(9, 9), s(8, 181), s(7, null), s(6, 60, status = SessionStatus.ACTIVE), s(5, 60, planId = "other"), s(4, 52),
        )
        val e = estimateDuration("p", plan, "normal", sessions)
        assertEquals(52, e.minutes)
        assertEquals(1, e.learnedFrom)
    }

    @Test
    fun boundariesAreInclusive() {
        val e = estimateDuration("p", plan, "normal", listOf(s(2, 10), s(1, 180)))
        assertEquals(2, e.learnedFrom)
        assertEquals(95, e.minutes)
    }

    @Test
    fun otherModesAreScaledBySetRatio() {
        // normal has 6 sets, fast 4: a 60 min normal session -> 40 min fast
        val e = estimateDuration("p", plan, "fast", listOf(s(2, 60, "normal"), s(1, 60, "normal")))
        assertEquals(40, e.minutes)
        assertEquals(2, e.learnedFrom)
    }

    @Test
    fun sameModeBeatsScaledOtherMode() {
        val e = estimateDuration("p", plan, "fast", listOf(s(2, 60, "normal"), s(1, 33, "fast")))
        assertEquals(33, e.minutes)
        assertEquals(1, e.learnedFrom)
    }

    @Test
    fun scalingNeedsSetsInSourceMode() {
        val none = listOf(PlanExercise(id = "1", exerciseId = "a", modeSets = ModeSets(0, 3, 0), restSeconds = 60))
        val e = estimateDuration("p", none, "fast", listOf(s(1, 60, "normal")))
        assertEquals(0, e.learnedFrom)
    }

    @Test
    fun formatsEstimate() {
        assertEquals("≈ 52 min", formatEstimate(52))
        assertEquals("≈ 1 h 5 min", formatEstimate(65))
    }

    @Test
    fun packRowsKeepSyncedOrderAndTextsAndResetPerDay() {
        val items = listOf(
            PackItem("b", "Handtuch  groß", 2, true), PackItem("a", "Trinkflasche", 1), PackItem("c", "Schloss", 3),
        )
        val fresh = packRows(items, null, "2026-11-03")
        assertEquals(listOf("Trinkflasche", "Handtuch  groß", "Schloss"), fresh.map { it.text })
        assertEquals(listOf(false, true, false), fresh.map { it.checked })
        val saved = PackState.encode("2026-11-03", setOf("a", "c"))
        assertEquals(listOf(true, false, true), packRows(items, saved, "2026-11-03").map { it.checked })
        // next day: back to the defaults
        assertEquals(listOf(false, true, false), packRows(items, saved, "2026-11-04").map { it.checked })
    }

    private companion object {
        const val EXPECTED_FORMULA_MIN = 16
    }
}
