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
