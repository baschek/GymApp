package de.baschek.gymapp.watch.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import java.io.File
import java.io.FileOutputStream
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertTrue
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Synthetic data only; the viewport approximates the small round watch's dp envelope. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w192dp-h192dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WatchUiTest {
    @get:Rule val compose = createComposeRule()

    private val exercise = WatchCurrentSet(
        exerciseId = "exercise", setId = "set", exerciseName = "Testübung",
        number = 1, total = 3, weight = 20.0, amount = 10.0, unit = "Wdh.",
        weightSteps = listOf(15.0, 20.0, 25.0),
    )


    private fun assertRestTargetsInsideRoundScreen(size: Int) {
        val actions = mutableListOf<WatchAction>()
        compose.setContent { WatchUi(WatchPresentation(sessionActive = true, current = exercise, restRemainingSeconds = 70, restTotalSeconds = 90, undoAvailable = true)) { actions.add(it) } }
        compose.waitForIdle()
        val screen = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val radius = minOf(screen.width, screen.height) / 2f
        val cx = screen.center.x; val cy = screen.center.y
        fun inside(rect: Rect) {
            val minSize = with(compose.density) { 48.dp.toPx() }
            assertTrue("target smaller than 48dp: $rect", rect.width >= minSize - 0.5f && rect.height >= minSize - 0.5f)
            for (x in listOf(rect.left, rect.right)) for (y in listOf(rect.top, rect.bottom)) {
                assertTrue("target corner ($x, $y) outside round viewport $screen", (x-cx)*(x-cx)+(y-cy)*(y-cy) <= radius*radius + 0.5f)
            }
        }
        for (label in listOf("Rückgängig", "+15 s", "Weiter")) {
            val node = compose.onNodeWithContentDescription(label).assertIsDisplayed()
            inside(node.fetchSemanticsNode().boundsInRoot)
        }
        val timer = compose.onNode(hasContentDescription("Pause ändern", substring = true)).assertIsDisplayed()
        inside(timer.fetchSemanticsNode().boundsInRoot)
        System.getenv("WATCH_SCREENSHOT_DIR")?.let { directory ->
            val file = File(directory, "watch-rest-$size.png")
            file.parentFile?.mkdirs()
            FileOutputStream(file).use { output -> compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, output) }
        }
        compose.onNodeWithContentDescription("+15 s").performClick()
        compose.onNodeWithContentDescription("Weiter").performClick()
        compose.onNodeWithContentDescription("Rückgängig").performClick()
        assertEquals(listOf(WatchAction.ChangeRest(15), WatchAction.SkipRest, WatchAction.Undo), actions)
    }

    @Test @Config(qualifiers = "w192dp-h192dp-round") fun restTargetsFitSmallRoundScreen() = assertRestTargetsInsideRoundScreen(192)
    @Test @Config(qualifiers = "w216dp-h216dp-round") fun restTargetsFitLargerRoundScreen() = assertRestTargetsInsideRoundScreen(216)


    @Test fun workoutSwipesReachLabeledInfoAndControls() {
        compose.setContent { WatchUi(WatchPresentation(sessionActive = true, current = exercise)) {} }
        compose.onRoot().performTouchInput { swipeRight(startX = width * .2f, endX = width * .9f) }
        compose.onNodeWithText("Info").assertIsDisplayed()
        compose.onNodeWithText("Übungen").assertIsDisplayed().performClick()
        compose.onNodeWithText("Keine Übungen").assertIsDisplayed()
        compose.onNodeWithText("Zurück").performClick()
        compose.onNodeWithTag("watch-list").performScrollToNode(hasText("Zum Satz"))
        compose.onNodeWithText("Zum Satz").performClick()
        compose.onRoot().performTouchInput { swipeLeft(startX = width * .8f, endX = width * .1f) }
        compose.onNodeWithText("Steuerung").assertIsDisplayed()
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

    @Test fun settingsAreReachableFromHomeWithoutStartingWorkout() {
        compose.setContent { WatchUi(WatchPresentation()) {} }
        compose.onNodeWithTag("watch-list").performScrollToNode(hasText("Einstellungen"))
        compose.onNodeWithText("Einstellungen").performClick()
        compose.onNodeWithText("Rand im Training").assertIsDisplayed()
    }


    @Test fun settingsDistinguishWatchUpdatesFromWorkoutSync() {
        val actions = mutableListOf<WatchAction>()
        compose.setContent { WatchUi(WatchPresentation(updateStatus = "Update bereit zur Installation", updateCanInstall = true)) { actions.add(it) } }
        compose.onNodeWithTag("watch-list").performScrollToNode(hasText("Einstellungen"))
        compose.onNodeWithText("Einstellungen").performClick()
        compose.onNodeWithTag("watch-list").performScrollToNode(hasText("Uhr-App-Update"))
        compose.onNodeWithText("Uhr-App-Update").assertExists()
        compose.onNodeWithTag("watch-list").performScrollToNode(hasText("Update installieren"))
        compose.onNodeWithText("Update installieren").performClick()
        assertEquals(listOf(WatchAction.InstallUpdate), actions)
    }

    @Test fun mainSetHasAccessibleLargeConfirmAndSendsExactlyOneLog() {
        val actions = mutableListOf<WatchAction>()
        compose.setContent { WatchUi(WatchPresentation(sessionActive = true, current = exercise), { actions.add(it) }) }
        compose.onNodeWithText("Testübung").assertIsDisplayed()
        System.getenv("WATCH_SCREENSHOT_DIR")?.let { directory ->
            val file = File(directory, "watch-current-set.png")
            file.parentFile?.mkdirs()
            FileOutputStream(file).use { output -> compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, output) }
        }
        compose.onNodeWithContentDescription("Satz geschafft").assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp).performClick()
        assertEquals(listOf(WatchAction.Log("exercise", "set", 20.0, 10.0)), actions)
    }

    @Test fun twoLineExerciseKeepsConfirmAtLeast48Dp() {
        compose.setContent { WatchUi(WatchPresentation(sessionActive = true, current = exercise.copy(exerciseName = "Lange Testübung am Gerät"))) {} }
        compose.onNodeWithText("Lange Testübung am Gerät").assertIsDisplayed()
        compose.onNodeWithContentDescription("Satz geschafft").assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
    }

    @Test fun weightEditorOffersTouchAlternativeAndUsesMachineSteps() {
        val actions = mutableListOf<WatchAction>()
        compose.setContent { WatchUi(WatchPresentation(sessionActive = true, current = exercise), { actions.add(it) }) }
        compose.onNodeWithContentDescription("Gewicht ändern").assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithContentDescription("Wert erhöhen").assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp).performClick()
        compose.onNodeWithContentDescription("Fertig").performClick()
        compose.onNodeWithContentDescription("Satz geschafft").performClick()
        assertEquals(25.0, (actions.single() as WatchAction.Log).weight!!, 0.001)
    }

    @Test fun durationSetUsesRealStartAndStopAction() {
        val actions = mutableListOf<WatchAction>()
        compose.setContent { WatchUi(WatchPresentation(sessionActive = true, current = exercise.copy(amount = 45.0, unit = "Sek.", isDuration = true), timedElapsedSeconds = 31), { actions.add(it) }) }
        compose.onNodeWithText("Stopp und speichern").assertIsDisplayed().performClick()
        assertEquals(31.0, (actions.single() as WatchAction.Log).amount, 0.001)
    }
}
