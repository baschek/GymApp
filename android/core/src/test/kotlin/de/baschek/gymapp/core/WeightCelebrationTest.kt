package de.baschek.gymapp.core

import de.baschek.gymapp.core.model.LoadDirection
import de.baschek.gymapp.core.model.Milestone
import de.baschek.gymapp.core.model.Session
import de.baschek.gymapp.core.model.SessionExercise
import de.baschek.gymapp.core.training.weightIncreases
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WeightCelebrationTest {
    private val session = Session(
        exercises = listOf(
            SessionExercise(id = "s1", exerciseId = "press", name = "Beinpresse"),
            SessionExercise(id = "s2", exerciseId = "row", name = "Rudern"),
            SessionExercise(id = "s3", exerciseId = "pull", name = "Klimmzug assistiert"),
        ),
    )

    private fun m(exercise: String, from: Double?, to: Double, sessionId: String = "S", direction: String = LoadDirection.HIGHER, name: String? = null) =
        Milestone(sessionId = sessionId, exerciseId = exercise, exerciseName = name, previousWeightKg = from, newWeightKg = to, direction = direction)

    @Test
    fun listsIncreasesOfThisSessionInExerciseOrder() {
        val out = weightIncreases(
            listOf(m("row", 50.0, 52.5, name = "Rudern"), m("press", 60.0, 65.0, name = "Beinpresse"), m("press", 40.0, 45.0, sessionId = "other")),
            "S", session,
        )
        assertEquals(listOf("Beinpresse 60 → 65 kg", "Rudern 50 → 52,5 kg"), out.map { it.label() })
        assertEquals("Neues Arbeitsgewicht", out.first().caption())
    }

    @Test
    fun aFirstWorkingWeightIsNoIncrease() {
        assertTrue(weightIncreases(listOf(m("press", null, 60.0)), "S", session).isEmpty())
    }

    @Test
    fun onlyMovesInTheProgressDirectionCount() {
        val out = weightIncreases(
            listOf(m("press", 65.0, 60.0), m("row", 50.0, 50.0), m("pull", 30.0, 25.0, direction = LoadDirection.LOWER_ASSISTANCE), m("x", 20.0, 25.0, direction = LoadDirection.LOWER_ASSISTANCE)),
            "S", session,
        )
        assertEquals(listOf("pull"), out.map { it.exerciseId })
        assertEquals("Weniger Hilfe", out.single().caption())
        assertEquals("Klimmzug assistiert 30 → 25 kg", out.single().label())
    }

    @Test
    fun namesFallBackToTheSessionAndDuplicatesCollapse() {
        val out = weightIncreases(listOf(m("press", 60.0, 62.5), m("press", 60.0, 62.5), m("ghost", 10.0, 12.5)), "S", session)
        assertEquals(listOf("Beinpresse 60 → 62,5 kg", "Übung 10 → 12,5 kg"), out.map { it.label() })
    }
}
