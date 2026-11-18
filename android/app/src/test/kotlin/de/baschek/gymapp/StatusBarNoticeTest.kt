package de.baschek.gymapp

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import de.baschek.gymapp.core.wear.events.ReplicaState
import de.baschek.gymapp.core.wear.events.WorkoutProfile
import de.baschek.gymapp.ui.LocalSnack
import de.baschek.gymapp.ui.health.WatchConflictNotice
import kotlinx.serialization.json.JsonObject
import de.baschek.gymapp.ui.NoticeColumn
import de.baschek.gymapp.ui.components.topInset
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApplication::class)
class StatusBarNoticeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun failedChoiceReportsTheErrorAndAllowsRetry() {
        var message = ""
        val replica = ReplicaState(WorkoutProfile.PERSONAL, "session", JsonObject(emptyMap()))
        compose.setContent {
            CompositionLocalProvider(LocalSnack provides { message = it }) {
                WatchConflictNotice(replica) { _, _ -> false }
            }
        }
        compose.onNodeWithText("Prüfen").performClick()
        compose.onNodeWithText("Neueste").performClick()
        compose.runOnIdle { assertEquals("Änderungen konnten nicht aufgelöst werden. Bitte erneut prüfen.", message) }
        compose.onNodeWithText("Prüfen").performClick()
        compose.onNodeWithText("Meine").assertExists()
    }

    @Test fun firstVisibleContentClearsStatusBarExactlyOnce() {
        var test by mutableStateOf(false)
        var conflict by mutableStateOf(false)
        val insets = WindowInsets(top = 40)
        compose.setContent {
            NoticeColumn(test, conflict, insets) {
                if (test) Spacer(Modifier.size(20.dp).testTag("test"))
                else if (conflict) Spacer(Modifier.size(20.dp).testTag("conflict"))
                Box(Modifier.padding(top = topInset())) { Spacer(Modifier.size(20.dp).testTag("screen")) }
            }
        }
        fun top(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top
        for (profile in listOf(false, true)) for (pending in listOf(false, true)) {
            compose.runOnIdle { test = profile; conflict = pending }
            if (profile || pending) {
                val banner = if (profile) "test" else "conflict"
                assertEquals(40f, top(banner), 0.1f)
                val bottom = compose.onNodeWithTag(banner).fetchSemanticsNode().boundsInRoot.bottom
                assertEquals(bottom, top("screen"), 0.1f)
            } else assertEquals(40f, top("screen"), 0.1f)
        }
    }
}
