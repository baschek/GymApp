package de.baschek.gymapp

import android.net.Uri
import com.google.android.gms.wearable.DataMap
import de.baschek.gymapp.core.wear.events.WorkoutEvent
import de.baschek.gymapp.core.wear.events.WorkoutProfile
import de.baschek.gymapp.wear.transport.WorkoutDataItems
import de.baschek.gymapp.wear.transport.WorkoutPublication
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApplication::class)
class WorkoutPublicationTest {
    @Test fun fragmentsWaitAcrossCallbacksWhileLegacyEventsRemainReadable() {
        val doc = JsonObject(emptyMap())
        val events = listOf("a", "b", "c").map { WorkoutEvent(it, "watch", 1, WorkoutProfile.PERSONAL, "s", doc, doc) }
        val publication = WorkoutPublication(events)
        val members = events.map { event ->
            WorkoutDataItems.Member(Uri.parse("wear://watch/gymapp/event/v1/${event.eventId}"), DataMap().also(publication::mark))
        }
        val legacy = WorkoutDataItems.Member(Uri.parse("wear://watch/gymapp/event/v1/d"), DataMap())
        assertEquals(listOf(listOf(legacy)), WorkoutDataItems.completePublications(members.drop(1) + legacy))
        assertEquals(listOf(members.reversed()), WorkoutDataItems.completePublications(members.reversed()))
    }
}
