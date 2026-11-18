package de.baschek.gymapp.watch.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w192dp-h192dp-round")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WatchHandoffUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val set = WatchCurrentSet(
        "exercise", "set-one", "Testübung", 1, 3, 20.0, 10.0, "Wdh.",
        weightSteps = listOf(15.0, 20.0, 25.0), addOns = listOf(1.25),
    )
    private val workout = WatchPresentation(sessionId = "test-workout", sessionActive = true, current = set, navigationKey = "initial")

    /**
     * With the clock paused a state write (the test's own or one made by an effect) only reaches the composition once
     * the apply notifications are flushed and a frame follows; a single frame right after the write is not enough, so
     * the effect that starts the quiet window began late or, for the write at its end, never became visible. Every
     * step therefore ends with a few flush-and-frame rounds (3 x 16 ms, small against the 450 ms window).
     */
    private fun pump() = repeat(3) {
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
    }

    private fun emit(change: () -> Unit) {
        compose.runOnIdle(change)
        pump()
    }

    private fun advance(millis: Long) {
        compose.mainClock.advanceTimeBy(millis)
        pump()
    }

    @Test fun phoneBurstShowsOnlyTheFinalPhaseAfterTheQuietWindow() {
        var state by mutableStateOf(workout)
        val phases = mutableListOf<String>()
        val observer = object : WatchNavObserver {
            override fun onScreen(screen: String, cause: String) {
                if (cause == "auto:phase") phases += screen
            }
        }
        compose.setContent { WatchUi(state, observer = observer) {} }
        compose.onNodeWithContentDescription("Satz geschafft").assertIsDisplayed()
        phases.clear()
        compose.mainClock.autoAdvance = false

        emit { state = workout.copy(phoneLeads = true, navigationKey = "done", current = set.copy(setId = ""), exerciseFinished = true) }
        advance(80)
        compose.onNodeWithContentDescription("Satz geschafft").assertIsDisplayed()
        emit { state = workout.copy(phoneLeads = true, navigationKey = "next", current = set.copy(setId = "set-two", number = 2)) }
        advance(80)
        compose.onNodeWithText("Satz 1 von 3").assertIsDisplayed()
        emit { state = state.copy(navigationKey = "rest", restRemainingSeconds = 60, restTotalSeconds = 90) }
        advance(250)
        compose.onNodeWithContentDescription("Satz geschafft").assertIsDisplayed()
        assertTrue("no phase change inside the quiet window: $phases", phases.isEmpty())

        // Remaining seconds are not an event and do not restart the quiet window.
        emit { state = state.copy(restRemainingSeconds = 59) }
        advance(250)
        assertEquals(listOf("training_rest"), phases)
        compose.onNodeWithContentDescription("Weiter").assertIsDisplayed()
    }

    @Test fun pendingPhoneEventKeepsTheCurrentPageAndLocalEventsTakeOverImmediately() {
        var state by mutableStateOf(workout)
        val events = mutableListOf<String>()
        val observer = object : WatchNavObserver {
            override fun onScreen(screen: String, cause: String) {
                if (cause == "auto:event") events += screen
            }
        }
        compose.setContent { WatchUi(state, observer = observer) {} }
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("training-left").assertIsDisplayed()
        compose.mainClock.autoAdvance = false

        emit { state = workout.copy(phoneLeads = true, navigationKey = "phone-rest", restRemainingSeconds = 60) }
        advance(80)
        compose.onNodeWithTag("training-left").assertIsDisplayed()
        assertTrue(events.isEmpty())

        emit { state = workout.copy(navigationKey = "watch-setup", setupRemainingSeconds = 40) }
        advance(80)
        assertEquals(listOf("training_setup"), events)
        advance(600)
        compose.onNodeWithText("Gerät einstellen").assertIsDisplayed()
        assertEquals(listOf("training_setup"), events)
    }

    @Test fun phoneFocusChangeDoesNotCloseAnEditedDraftAndTheDraftStaysWithItsSet() {
        var state by mutableStateOf(workout)
        val actions = mutableListOf<WatchAction>()
        compose.setContent { WatchUi(state) { actions += it } }
        compose.onNodeWithContentDescription("Gewicht ändern").performClick()
        compose.onNodeWithContentDescription("Wert erhöhen").performClick()
        compose.onNodeWithText("Gesamt 25 kg").assertIsDisplayed()
        compose.mainClock.autoAdvance = false

        emit { state = workout.copy(phoneLeads = true, navigationKey = "other-set", current = set.copy(setId = "set-two", number = 2)) }
        advance(600)
        compose.onNodeWithText("Gesamt 25 kg").assertIsDisplayed()
        compose.onNodeWithContentDescription("Fertig").performClick()
        advance(600)
        compose.onNodeWithText("Satz 2 von 3").assertIsDisplayed()

        emit { state = workout.copy(phoneLeads = true, navigationKey = "reopened", current = set.copy(weight = 15.0)) }
        advance(600)
        compose.mainClock.autoAdvance = true
        compose.onNodeWithContentDescription("Satz geschafft").performClick()
        compose.onNodeWithContentDescription("1–2 übrig", substring = true).performClick()
        val logged = actions.single() as WatchAction.Log
        assertEquals("set-one", logged.set)
        assertEquals(25.0, logged.weight!!, 0.001)
    }
}
