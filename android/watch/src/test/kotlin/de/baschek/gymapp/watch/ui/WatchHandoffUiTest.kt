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

    private fun emit(change: () -> Unit) {
        compose.runOnIdle(change)
        compose.mainClock.advanceTimeByFrame()
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
        compose.mainClock.advanceTimeBy(80)
        compose.onNodeWithContentDescription("Satz geschafft").assertIsDisplayed()
        emit { state = workout.copy(phoneLeads = true, navigationKey = "next", current = set.copy(setId = "set-two", number = 2)) }
        compose.mainClock.advanceTimeBy(80)
        compose.onNodeWithText("Satz 1 von 3").assertIsDisplayed()
        emit { state = state.copy(navigationKey = "rest", restRemainingSeconds = 60, restTotalSeconds = 90) }
        compose.mainClock.advanceTimeBy(368)
        compose.onNodeWithContentDescription("Satz geschafft").assertIsDisplayed()

        // Remaining seconds are not an event and do not restart the quiet window.
        emit { state = state.copy(restRemainingSeconds = 59) }
        compose.mainClock.advanceTimeBy(224)
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
        compose.mainClock.advanceTimeBy(80)
        compose.onNodeWithTag("training-left").assertIsDisplayed()
        assertTrue(events.isEmpty())

        emit { state = workout.copy(navigationKey = "watch-setup", setupRemainingSeconds = 40) }
        compose.mainClock.advanceTimeBy(80)
        assertEquals(listOf("training_setup"), events)
        compose.mainClock.advanceTimeBy(600)
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
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText("Gesamt 25 kg").assertIsDisplayed()
        compose.onNodeWithContentDescription("Fertig").performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText("Satz 2 von 3").assertIsDisplayed()

        emit { state = workout.copy(phoneLeads = true, navigationKey = "reopened", current = set.copy(weight = 15.0)) }
        compose.mainClock.advanceTimeBy(600)
        compose.mainClock.autoAdvance = true
        compose.onNodeWithContentDescription("Satz geschafft").performClick()
        compose.onNodeWithContentDescription("1–2 übrig", substring = true).performClick()
        val logged = actions.single() as WatchAction.Log
        assertEquals("set-one", logged.set)
        assertEquals(25.0, logged.weight!!, 0.001)
    }
}
