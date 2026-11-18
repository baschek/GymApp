package de.baschek.gymapp.watch.ui

import de.baschek.gymapp.core.model.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Navigation rules of spec B and small pure helpers; synthetic data only. */
class WatchNavRulesTest {
    private val training = NavState(listOf(Route.TRAINING), TrainingPage.MIDDLE)

    @Test fun backClosesSubViewFirst() {
        val withEditor = NavState(listOf(Route.TRAINING, Route.EDITOR), TrainingPage.MIDDLE)
        assertEquals(training, WatchNavRules.back(withEditor))
    }

    @Test fun backGoesOnePageBackAndNothingOnTheLeftPage() {
        assertEquals(training, WatchNavRules.back(training.copy(page = TrainingPage.RIGHT)))
        assertEquals(training.copy(page = TrainingPage.LEFT), WatchNavRules.back(training))
        val left = training.copy(page = TrainingPage.LEFT)
        assertEquals("the left page stays in the training", left, WatchNavRules.back(left))
    }

    @Test fun backOnStartScreenIsLeftToTheSystem() {
        assertNull(WatchNavRules.back(NavState(listOf(Route.HOME))))
        assertEquals(NavState(listOf(Route.HOME)), WatchNavRules.back(NavState(listOf(Route.HOME, Route.SETTINGS))))
    }

    @Test fun everyEventInAnActiveWorkoutShowsTheMiddlePageWithoutSubViews() {
        val deep = NavState(listOf(Route.TRAINING, Route.SETTINGS, Route.HEALTH), TrainingPage.RIGHT)
        assertEquals(training, WatchNavRules.afterEvent(deep, sessionActive = true, finished = false, celebrations = false))
    }

    @Test fun finishedWorkoutShowsCelebrationOrSummary() {
        assertEquals(listOf(Route.CELEBRATION), WatchNavRules.afterEvent(training, false, finished = true, celebrations = true).stack)
        assertEquals(listOf(Route.SUMMARY), WatchNavRules.afterEvent(training, false, finished = true, celebrations = false).stack)
    }

    @Test fun withoutWorkoutTheStartScreenKeepsItsPlace() {
        val settings = NavState(listOf(Route.HOME, Route.SETTINGS))
        assertEquals(settings, WatchNavRules.afterEvent(settings, false, false, false))
        assertEquals(NavState(listOf(Route.HOME)), WatchNavRules.afterEvent(NavState(listOf(Route.TRAINING, Route.NOTE)), false, false, false))
    }

    @Test fun stateSurvivesEncoding() {
        val state = NavState(listOf(Route.TRAINING, Route.EXERCISE_MENU), TrainingPage.LEFT)
        assertEquals(state, NavState.decode(state.encode()))
        assertNull(NavState.decode(""))
    }

    @Test fun middlePhaseFollowsTheWorkoutNotATimer() {
        val set = WatchCurrentSet("e", "s", "Testübung", 1, 3, 20.0, 10.0, "Wdh.")
        assertEquals(SetPhase.SET, WatchNavRules.phase(WatchPresentation(sessionActive = true, current = set), false))
        assertEquals(SetPhase.EFFORT, WatchNavRules.phase(WatchPresentation(sessionActive = true, current = set), true))
        assertEquals(SetPhase.REST, WatchNavRules.phase(WatchPresentation(sessionActive = true, current = set, restRemainingSeconds = -40), false))
        assertEquals(SetPhase.DONE, WatchNavRules.phase(WatchPresentation(sessionActive = true, current = set.copy(setId = ""), exerciseFinished = true), false))
        assertEquals(SetPhase.SETUP, WatchNavRules.phase(WatchPresentation(sessionActive = true, current = set, setupRemainingSeconds = 0), false))
    }

    @Test fun screenNamesAreTelemetrySafe() {
        assertEquals("training_left", training.copy(page = TrainingPage.LEFT).screen())
        assertEquals("training_rest", training.screen(SetPhase.REST))
        assertEquals("satz_geschafft", elementName("Satz geschafft"))
        assertEquals("naechste_uebung", elementName("Nächste Übung"))
        assertEquals("tap", elementName("✓"))
        Route.entries.forEach { assert(it.screen.matches(Regex("[a-z_]{1,32}"))) }
    }

    @Test fun quickNotesAreAppended() {
        assertEquals("Schmerz", appendNote("", "Schmerz"))
        assertEquals("Sitz 3 · Schmerz", appendNote("Sitz 3", "Schmerz"))
    }

    @Test fun startSuggestsThePlanTrainedLongestAgoAndTheLastGym() {
        val history = listOf(
            Session(planId = "a", gymId = "g1", startedAt = "2026-01-03T10:00:00Z"),
            Session(planId = "b", gymId = "g2", startedAt = "2026-01-05T10:00:00Z"),
        )
        assertEquals("a", suggestPlanId(listOf("a", "b"), history))
        assertEquals("c", suggestPlanId(listOf("a", "b", "c"), history))
        assertEquals("g2", lastGymId(history))
    }

    @Test fun recorderUsesFixedIdentifiersOnly() {
        assertEquals("train_center", recorderScreen(training, SetPhase.SET))
        assertEquals("train_left", recorderScreen(training.copy(page = TrainingPage.LEFT), SetPhase.SET))
        assertEquals("train_right", recorderScreen(training.copy(page = TrainingPage.RIGHT), SetPhase.SET))
        assertEquals("pause", recorderScreen(training, SetPhase.REST))
        assertEquals("editor_weight", recorderScreen(NavState(listOf(Route.TRAINING, Route.EDITOR)), SetPhase.SET, EditorKind.WEIGHT.name))
        assertEquals("editor_rest", recorderScreen(NavState(listOf(Route.TRAINING, Route.EDITOR)), SetPhase.REST, EditorKind.REST.name))
        assertEquals("settings", recorderScreen(NavState(listOf(Route.TRAINING, Route.SETTINGS)), SetPhase.SET))
        assertEquals("log_set", recorderElement(elementName("Satz geschafft")))
        assertEquals("skip_rest", recorderElement(elementName("Weiter")))
        assertEquals("effort", recorderElement(elementName("1–2 übrig")))
        assertEquals("tap", recorderElement(elementName("Etwas ganz Neues")))
    }
}
