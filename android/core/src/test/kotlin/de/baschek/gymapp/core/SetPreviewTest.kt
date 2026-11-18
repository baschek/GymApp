package de.baschek.gymapp.core

import de.baschek.gymapp.core.model.EffortSource
import de.baschek.gymapp.core.model.MachineProfile
import de.baschek.gymapp.core.model.Metric
import de.baschek.gymapp.core.model.Session
import de.baschek.gymapp.core.model.SessionExercise
import de.baschek.gymapp.core.model.SetLog
import de.baschek.gymapp.core.model.SetStatus
import de.baschek.gymapp.core.model.SetupParameter
import de.baschek.gymapp.core.model.Target
import de.baschek.gymapp.core.training.effortChoices
import de.baschek.gymapp.core.training.nextSetPreview
import de.baschek.gymapp.core.training.resolveEffort
import de.baschek.gymapp.core.training.setupText
import de.baschek.gymapp.core.training.targetLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SetPreviewTest {
    private val target = Target(Metric.REPS, 8.0, 12.0, 1.0, 2.0)
    private fun done(id: String, w: Double, reps: Int) = SetLog(id = id, status = SetStatus.COMPLETED, weightKg = w, reps = reps, effort = "one_two")
    private fun open(id: String) = SetLog(id = id, status = SetStatus.PENDING)
    private fun ex(id: String, vararg sets: SetLog) =
        SessionExercise(id = id, exerciseId = "x-$id", name = "Übung $id", target = target, suggestedWeightKg = 160.0, sets = sets.toList())

    private val machines = mapOf(
        "x-1" to MachineProfile(gymId = "g", exerciseId = "x-1", availableWeightsKg = listOf(160.0, 170.0, 180.0)),
        "x-2" to MachineProfile(gymId = "g", exerciseId = "x-2", availableWeightsKg = listOf(40.0, 50.0), setupParameters = listOf(SetupParameter("p", "Fußplatte", "4.5"))),
    )

    @Test
    fun targetAndSetupTexts() {
        assertEquals("Ziel 8–12", targetLine(target))
        assertEquals("Ziel 60–90 s", targetLine(Target(Metric.SECONDS, 60.0, 90.0)))
        assertEquals("Ziel 10", targetLine(Target(Metric.REPS, 10.0, 10.0)))
        assertEquals("", targetLine(Target()))
        assertEquals("Fußplatte 4,5 · Sitz 3", setupText(listOf(SetupParameter("a", "Fußplatte", "4.5"), SetupParameter("b", "Sitz", "3"))))
    }

    @Test
    fun nextSetOfTheSameExerciseCarriesTheSuggestion() {
        val session = Session(gymId = "g", exercises = listOf(ex("1", done("a", 170.0, 12), open("b")), ex("2", open("c"))))
        val p = nextSetPreview(session, "1", "1") { machines[it] }!!
        assertEquals("1", p.exerciseId)
        assertEquals(2, p.setNumber)
        assertEquals(2, p.setTotal)
        assertEquals(170.0, p.weightKg)
        assertEquals("170 kg × 8–12", p.valueText)
        assertEquals("Ziel 8–12", p.targetText)
        assertFalse(p.newExercise)
        assertEquals(180.0, p.hint?.weightKg)
        assertEquals("170 → 180 kg ▲", p.suggestionText)
    }

    @Test
    fun aNewExerciseShowsItsMachineSetup() {
        val session = Session(gymId = "g", exercises = listOf(ex("1", done("a", 170.0, 10)), ex("2", open("c"))))
        val p = nextSetPreview(session, "1", "1") { machines[it] }!!
        assertEquals("2", p.exerciseId)
        assertTrue(p.newExercise)
        assertEquals("Fußplatte 4,5", p.setupText)
        assertNull(p.suggestionText)
        assertNull(nextSetPreview(Session(exercises = listOf(ex("1", done("a", 170.0, 10)))), null, "1") { null })
    }

    @Test
    fun effortPickerPreselectsTheTargetAndMarksTheSource() {
        val choices = effortChoices(target)
        assertEquals(listOf("Am Limit", "1–2 übrig", "3–4 übrig", "5+ locker"), choices.map { it.label })
        assertEquals("one_two", choices.single { it.preselected }.band)
        assertEquals(EffortSource.AUTO, resolveEffort(null, target).source)
        assertEquals("one_two", resolveEffort(null, target).effort)
        assertEquals(EffortSource.USER, resolveEffort("one_two", target).source)
        assertEquals("three_four", resolveEffort("three_four", target).effort)
    }
}
