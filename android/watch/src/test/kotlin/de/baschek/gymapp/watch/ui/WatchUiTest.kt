package de.baschek.gymapp.watch.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import de.baschek.gymapp.core.model.EffortSource
import de.baschek.gymapp.core.training.SetPreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/** Synthetic data only; the viewport approximates the small round watch's dp envelope. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w192dp-h192dp-round")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WatchUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val exercise = WatchCurrentSet(
        exerciseId = "exercise", setId = "set", exerciseName = "Testübung",
        number = 1, total = 3, weight = 20.0, amount = 10.0, unit = "Wdh.",
        target = "Ziel 8–12", weightSteps = listOf(15.0, 20.0, 25.0),
    )
    private val rows = listOf(
        WatchExerciseRow("exercise", "Testübung", "0/3", "Aktuell", current = true),
        WatchExerciseRow("other", "Zweite Übung", "0/2", "Offen"),
    )

    private fun screenshot(name: String) {
        System.getenv("WATCH_SCREENSHOT_DIR")?.let { directory ->
            val file = File(directory, "$name.png")
            file.parentFile?.mkdirs()
            FileOutputStream(file).use { output -> compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, output) }
        }
    }

    /** Every corner of a touch target lies inside the round screen and the target is at least 48 dp (spec A7). */
    private fun assertInsideRound(node: SemanticsNodeInteraction) {
        val screen = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val radius = minOf(screen.width, screen.height) / 2f
        val cx = screen.center.x; val cy = screen.center.y
        val rect: Rect = node.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val minSize = with(compose.density) { 48.dp.toPx() }
        assertTrue("target smaller than 48dp: $rect", rect.width >= minSize - 0.5f && rect.height >= minSize - 0.5f)
        for (x in listOf(rect.left, rect.right)) for (y in listOf(rect.top, rect.bottom)) {
            assertTrue("target corner ($x, $y) outside round viewport $screen", (x - cx) * (x - cx) + (y - cy) * (y - cy) <= radius * radius + 0.5f)
        }
    }

    private fun assertRestTargetsInsideRoundScreen(size: Int) {
        val actions = mutableListOf<WatchAction>()
        compose.setContent { WatchUi(WatchPresentation(sessionActive = true, current = exercise, restRemainingSeconds = 70, restTotalSeconds = 90, undoAvailable = true)) { actions.add(it) } }
        compose.waitForIdle()
        assertInsideRound(compose.onNodeWithContentDescription("Rückgängig"))
        assertInsideRound(compose.onNodeWithContentDescription("Weiter"))
        assertInsideRound(compose.onNode(hasContentDescription("Pause ändern", substring = true)))
        screenshot("watch-rest-$size")
        compose.onNodeWithContentDescription("Weiter").performClick()
        compose.onNodeWithContentDescription("Rückgängig").performClick()
        assertEquals(listOf(WatchAction.SkipRest, WatchAction.Undo), actions)
    }

    @Test fun restTargetsFitSmallRoundScreen() = assertRestTargetsInsideRoundScreen(192)
    @Test @Config(qualifiers = "w216dp-h216dp-round") fun restTargetsFitLargerRoundScreen() = assertRestTargetsInsideRoundScreen(216)

    @Test fun setTargetsFitSmallRoundScreen() {
        compose.setContent { WatchUi(WatchPresentation(sessionActive = true, current = exercise)) {} }
        compose.onNodeWithText("Ziel 8–12").assertIsDisplayed()
        assertInsideRound(compose.onNodeWithContentDescription("Satz geschafft"))
        assertInsideRound(compose.onNodeWithContentDescription("Gewicht ändern"))
        assertInsideRound(compose.onNodeWithContentDescription("Wdh. ändern"))
        screenshot("watch-current-set")
    }

    @Test fun restSecondLevelOffersRestChangesAndOneMoreSet() {
        val actions = mutableListOf<WatchAction>()
        compose.setContent { WatchUi(WatchPresentation(sessionActive = true, current = exercise, restRemainingSeconds = 70, restTotalSeconds = 90, undoAvailable = true)) { actions.add(it) } }
        compose.onNodeWithTag("rest-pager").performTouchInput { swipeUp() }
        compose.onNodeWithText("+15 s").assertIsDisplayed().performClick()
        compose.onNodeWithText("−15 s").performClick()
        compose.onNodeWithTag("rest-more").performScrollToNode(hasText("Noch ein Satz"))
        compose.onNodeWithText("Noch ein Satz").performClick()
        assertEquals(listOf(WatchAction.ChangeRest(15), WatchAction.ChangeRest(-15), WatchAction.AddSet("exercise")), actions)
    }

    @Test fun restNeverGoesOnByItself() {
        val actions = mutableListOf<WatchAction>()
        compose.mainClock.autoAdvance = false
        compose.setContent { WatchUi(WatchPresentation(sessionActive = true, current = exercise, restRemainingSeconds = -7, restTotalSeconds = 90, navigationKey = "rest")) { actions.add(it) } }
        compose.mainClock.advanceTimeBy(120_000)
        compose.onNodeWithText("+0:07").assertIsDisplayed()
        compose.onNodeWithText("Los").assertIsDisplayed()
        assertTrue("no automatic action at the end of a rest: $actions", actions.isEmpty())
    }

    @Test fun finishedExerciseWaitsForTheCommand() {
        val actions = mutableListOf<WatchAction>()
        compose.mainClock.autoAdvance = false
        compose.setContent { WatchUi(WatchPresentation(sessionActive = true, current = exercise.copy(setId = ""), exerciseFinished = true, nextExerciseName = "Zweite Übung")) { actions.add(it) } }
        compose.mainClock.advanceTimeBy(60_000)
        assertTrue(actions.isEmpty())
        compose.onNodeWithText("Nächste Übung: Zweite Übung").assertIsDisplayed().performClick()
        compose.mainClock.advanceTimeBy(100)
        assertEquals(listOf(WatchAction.NextExercise), actions)
    }

    @Test fun checkOpensEffortPickerAndSavesTheChosenBandAsUser() {
        val actions = mutableListOf<WatchAction>()
        compose.setContent { WatchUi(WatchPresentation(sessionActive = true, current = exercise)) { actions.add(it) } }
        compose.onNodeWithContentDescription("Satz geschafft").assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp).performClick()
        assertTrue("the set is saved only after the effort choice", actions.isEmpty())
        compose.onNodeWithText("Wie anstrengend?").assertIsDisplayed()
        assertInsideRound(compose.onNodeWithContentDescription("3–4 übrig"))
        compose.onNodeWithContentDescription("3–4 übrig").performClick()
        assertEquals(listOf(WatchAction.Log("exercise", "set", 20.0, 10.0, effort = "three_four", effortSource = EffortSource.USER)), actions)
    }

    @Test fun effortPickerTimeoutSavesThePreselectionAsAuto() {
        val actions = mutableListOf<WatchAction>()
        compose.setContent { WatchUi(WatchPresentation(sessionActive = true, current = exercise)) { actions.add(it) } }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("Satz geschafft").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.mainClock.advanceTimeBy(3_500)
        assertTrue("nothing before 5 s: $actions", actions.isEmpty())
        compose.mainClock.advanceTimeBy(1_500)
        assertEquals(listOf(WatchAction.Log("exercise", "set", 20.0, 10.0, effort = "one_two", effortSource = EffortSource.AUTO)), actions)
    }

    @Test fun weightEditorOffersTouchAlternativeAndUsesMachineSteps() {
        val actions = mutableListOf<WatchAction>()
        compose.setContent { WatchUi(WatchPresentation(sessionActive = true, current = exercise)) { actions.add(it) } }
        compose.onNodeWithContentDescription("Gewicht ändern").assertHeightIsAtLeast(48.dp).performClick()
        assertInsideRound(compose.onNodeWithContentDescription("Wert erhöhen"))
        assertInsideRound(compose.onNodeWithContentDescription("Wert verringern"))
        assertInsideRound(compose.onNodeWithContentDescription("Fertig"))
        compose.onNodeWithContentDescription("Wert erhöhen").performClick()
        compose.onNodeWithContentDescription("Fertig").performClick()
        compose.onNodeWithContentDescription("Satz geschafft").performClick()
        compose.onNodeWithContentDescription("1–2 übrig", substring = true).performClick()
        assertEquals(25.0, (actions.single() as WatchAction.Log).weight!!, 0.001)
    }

    @Test fun addOnChipsShowTheTotal() {
        val actions = mutableListOf<WatchAction>()
        compose.setContent { WatchUi(WatchPresentation(sessionActive = true, current = exercise.copy(addOns = listOf(1.25, 2.5)))) { actions.add(it) } }
        compose.onNodeWithContentDescription("Gewicht ändern").performClick()
        compose.onNodeWithContentDescription("Zusatzgewicht 2,5", substring = true).performClick()
        compose.onNodeWithText("Gesamt 22,5 kg").assertIsDisplayed()
        compose.onNodeWithContentDescription("Fertig").performClick()
        compose.onNodeWithContentDescription("Satz geschafft").performClick()
        compose.onNodeWithContentDescription("1–2 übrig", substring = true).performClick()
        val log = actions.single() as WatchAction.Log
        assertEquals(22.5, log.weight!!, 0.001)
        assertEquals(listOf(2.5), log.addons)
    }

    @Test fun durationSetStopsAndSavesTheMeasuredTime() {
        val actions = mutableListOf<WatchAction>()
        compose.setContent { WatchUi(WatchPresentation(sessionActive = true, current = exercise.copy(amount = 45.0, unit = "Sek.", isDuration = true), timedElapsedSeconds = 31)) { actions.add(it) } }
        compose.onNodeWithContentDescription("Stopp und speichern").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("1–2 übrig", substring = true).performClick()
        assertEquals(31.0, (actions.single() as WatchAction.Log).amount, 0.001)
    }

    @Test fun suggestionRowTakesOverTheSuggestedWeight() {
        val actions = mutableListOf<WatchAction>()
        val preview = SetPreview("exercise", "Testübung", "set", 2, 3, false, 20.0, 10.0, "reps", "20 kg × 8–12", "Ziel 8–12", false, "", null, "20 → 25 kg ▲")
        var state by mutableStateOf(WatchPresentation(sessionActive = true, current = exercise, restRemainingSeconds = 60, restTotalSeconds = 90, nextSet = preview, suggestedWeight = 25.0, navigationKey = "rest"))
        compose.setContent { WatchUi(state) { actions.add(it) } }
        compose.onNodeWithText("20 → 25 kg ▲").assertIsDisplayed()
        assertInsideRound(compose.onNode(hasContentDescription("Vorschlag übernehmen", substring = true)))
        compose.onNode(hasContentDescription("Vorschlag übernehmen", substring = true)).performClick()
        state = state.copy(restRemainingSeconds = null, navigationKey = "set")
        compose.onNodeWithContentDescription("Satz geschafft").performClick()
        compose.onNodeWithContentDescription("1–2 übrig", substring = true).performClick()
        assertEquals(25.0, (actions.single() as WatchAction.Log).weight!!, 0.001)
    }

    @Test fun pagesAreLeftExercisesAndRightTraining() {
        compose.setContent { WatchUi(WatchPresentation(sessionActive = true, current = exercise, exercises = rows)) {} }
        compose.onNodeWithTag("training-pager").performTouchInput { swipeRight(startX = width * .3f, endX = width * .95f) }
        compose.onNodeWithTag("training-left").assertIsDisplayed()
        compose.onNodeWithText("Zweite Übung", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("training-left").performScrollToNode(hasText("Übung hinzufügen"))
        compose.onNodeWithText("Übung hinzufügen").assertIsDisplayed()
        compose.onNodeWithText("Zurück").assertDoesNotExist()
    }

    @Test fun backGoesOnePageBackAndNothingOnTheLeftPage() {
        var exited = false
        compose.setContent { WatchUi(WatchPresentation(sessionActive = true, current = exercise, exercises = rows), onExit = { exited = true }) {} }
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("training-left").assertIsDisplayed()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("training-left").assertIsDisplayed()
        compose.onNodeWithTag("training-pager").performTouchInput { swipeRight(startX = width * .02f, endX = width * .95f) }
        compose.onNodeWithTag("training-left").assertIsDisplayed()
        assertTrue("the training never closes by back or swipe", !exited)
    }

    @Test fun realEventReturnsToTheMiddlePage() {
        var state by mutableStateOf(WatchPresentation(sessionActive = true, current = exercise, exercises = rows, navigationKey = "a"))
        compose.setContent { WatchUi(state) {} }
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("training-left").assertIsDisplayed()
        // Per-second values do not move the person (spec A4) ...
        state = state.copy(timedElapsedSeconds = null, pendingCount = 3)
        compose.onNodeWithTag("training-left").assertIsDisplayed()
        // ... a real event (here: set logged on the phone, rest started) does.
        state = state.copy(navigationKey = "b", restRemainingSeconds = 90, restTotalSeconds = 90)
        compose.onNodeWithContentDescription("Weiter").assertIsDisplayed()
    }

    @Test fun exerciseMenuDisablesNoOpActions() {
        val actions = mutableListOf<WatchAction>()
        compose.setContent { WatchUi(WatchPresentation(sessionActive = true, current = exercise, exercises = rows)) { actions.add(it) } }
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("Testübung", substring = true).performClick()
        compose.onNodeWithText("Jetzt").assertIsNotEnabled().performClick()
        compose.onNodeWithTag("watch-list").performScrollToNode(hasText("Nach oben"))
        compose.onNodeWithText("Nach oben").assertIsNotEnabled().performClick()
        assertTrue("disabled actions send nothing: $actions", actions.isEmpty())
        compose.onNodeWithTag("watch-list").performScrollToNode(hasText("Nach unten"))
        compose.onNodeWithText("Nach unten").performClick()
        assertEquals(listOf(WatchAction.Move("exercise", 1)), actions)
    }

    @Test fun noteQuickTextIsAppended() {
        val actions = mutableListOf<WatchAction>()
        compose.setContent { WatchUi(WatchPresentation(sessionActive = true, current = exercise.copy(exerciseNote = "Sitz 3", note = "Sitz 3"), exercises = rows)) { actions.add(it) } }
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("training-left").performScrollToNode(hasText("Notiz"))
        compose.onNodeWithText("Notiz").performClick()
        compose.onNodeWithTag("watch-list").performScrollToNode(hasText("Schmerz"))
        compose.onNodeWithText("Schmerz").performClick()
        assertEquals(listOf(WatchAction.SetNote("exercise", "Sitz 3 · Schmerz")), actions)
    }

    @Test fun startFlowAsksGymModeAndPackingList() {
        val actions = mutableListOf<WatchAction>()
        val state = WatchPresentation(
            plans = listOf(WatchOption("plan-a", "Testplan A"), WatchOption("plan-b", "Testplan B")),
            homePlan = WatchOption("plan-a", "Testplan A", "Teststudio Nord · Normal ≈ 50 min"),
            gyms = listOf(WatchOption("gym-1", "Teststudio Süd"), WatchOption("gym-2", "Teststudio Nord")), lastGymId = "gym-2",
            packsByGym = mapOf("gym-2" to listOf(WatchOption("towel", "Handtuch"))),
            modeOptions = mapOf("plan:plan-a" to listOf(WatchOption("normal", "Normal", "≈ 50 min"), WatchOption("fast", "Schnell", "≈ 35 min"))),
        )
        compose.setContent { WatchUi(state) { actions.add(it) } }
        compose.onNodeWithTag("watch-list").performScrollToNode(hasText("Start ▶"))
        compose.onNodeWithText("Start ▶").performClick()
        compose.onNodeWithText("Teststudio Nord").assertIsDisplayed().performClick()
        compose.onNodeWithText("Schnell").performClick()
        compose.onNodeWithText("Handtuch", substring = true).performClick()
        assertInsideRound(compose.onNodeWithContentDescription("Los"))
        compose.onNodeWithContentDescription("Los").performClick()
        assertEquals(listOf(WatchAction.TogglePack("gym-2", "", "towel"), WatchAction.StartPlan("plan-a", "gym-2", "fast")), actions)
    }

    @Test fun homeShowsProgressAndHistoryWithoutStartingTraining() {
        val actions = mutableListOf<WatchAction>()
        val history = WatchHistoryEntry("id", "Testtraining", "2026-01-01 · Teststudio", listOf("Testübung" to "20 kg × 10 Wdh."))
        compose.setContent { WatchUi(WatchPresentation(history = listOf(history))) { actions.add(it) } }
        compose.onNodeWithTag("watch-list").performScrollToNode(hasText("Fortschritt & Verlauf"))
        compose.onNodeWithText("Fortschritt & Verlauf").performClick()
        compose.onNodeWithTag("watch-list").performScrollToNode(hasText("Verlauf"))
        compose.onNodeWithText("Verlauf").performClick()
        compose.onNodeWithText("Testtraining").performClick()
        compose.onNodeWithText("20 kg × 10 Wdh.").assertIsDisplayed()
        assertTrue("Browsing must not start a workout", actions.none { it is WatchAction.StartPlan || it is WatchAction.StartDay || it is WatchAction.StartSpontaneous })
    }

    @Test fun settingsOfferSwitchesAndSeparateWatchUpdate() {
        val actions = mutableListOf<WatchAction>()
        compose.setContent { WatchUi(WatchPresentation(updateStatus = "Update bereit zur Installation", updateCanInstall = true)) { actions.add(it) } }
        compose.onNodeWithTag("watch-list").performScrollToNode(hasText("Einstellungen"))
        compose.onNodeWithText("Einstellungen").performClick()
        compose.onNodeWithText("Dauer am Rand").assertIsDisplayed().performClick()
        compose.onNodeWithTag("watch-list").performScrollToNode(hasText("Update installieren"))
        compose.onNodeWithText("Update installieren").performClick()
        assertEquals(listOf(WatchAction.SetDurationRing(true), WatchAction.InstallUpdate), actions)
    }

    @Test fun settingsOfferTheTemporaryRecorderSwitchAndSendButton() {
        compose.setContent { WatchUi(WatchPresentation()) {} }
        compose.onNodeWithTag("watch-list").performScrollToNode(hasText("Einstellungen"))
        compose.onNodeWithText("Einstellungen").performClick()
        compose.onNodeWithTag("watch-list").performScrollToNode(hasText("Navigation aufzeichnen (Test, nur diese Version)"))
        compose.onNodeWithText("Navigation aufzeichnen (Test, nur diese Version)").assertIsDisplayed()
        compose.onNodeWithTag("watch-list").performScrollToNode(hasText("Aufzeichnung ans Handy senden"))
        compose.onNodeWithText("Aufzeichnung ans Handy senden").assertIsDisplayed()
    }

    @Test fun endingTheTrainingAsksFirst() {
        val actions = mutableListOf<WatchAction>()
        compose.setContent { WatchUi(WatchPresentation(sessionActive = true, current = exercise)) { actions.add(it) } }
        compose.onNodeWithTag("training-pager").performTouchInput { swipeLeft(startX = width * .9f, endX = width * .05f) }
        compose.onNodeWithText("Training beenden").performClick()
        assertTrue(actions.isEmpty())
        compose.onNodeWithText("Training beenden?").assertIsDisplayed()
        assertInsideRound(compose.onNodeWithContentDescription("Beenden"))
        assertInsideRound(compose.onNodeWithContentDescription("Abbrechen"))
        compose.onNodeWithContentDescription("Beenden").performClick()
        assertEquals(listOf(WatchAction.Finish), actions)
    }

    @Test fun tapsAndScreensGoThroughTheObserver() {
        val screens = mutableListOf<String>()
        val taps = mutableListOf<Pair<String, String>>()
        val observer = object : WatchNavObserver {
            override fun onScreen(screen: String, cause: String) { screens += "$screen/$cause" }
            override fun onTap(screen: String, element: String) { taps += screen to element }
        }
        compose.setContent { WatchUi(WatchPresentation(sessionActive = true, current = exercise), observer = observer) {} }
        compose.onNodeWithContentDescription("Gewicht ändern").performClick()
        assertTrue(screens.toString(), screens.contains("training_set/auto:start"))
        assertTrue(screens.toString(), screens.contains("editor/user:tap"))
        assertTrue(taps.toString(), taps.contains("training_set" to "gewicht_aendern"))
    }
}
