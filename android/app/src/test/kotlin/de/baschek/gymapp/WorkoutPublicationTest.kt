package de.baschek.gymapp

import android.net.Uri
import com.google.android.gms.wearable.DataMap
import de.baschek.gymapp.core.wear.events.WorkoutEvent
import de.baschek.gymapp.core.wear.events.WorkoutProfile
import de.baschek.gymapp.core.wear.events.WorkoutPublicationGrace
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
        val members = publication("a", "b", "c")
        val legacy = WorkoutDataItems.Member(Uri.parse("wear://watch/gymapp/event/v1/d"), DataMap())
        val grace = WorkoutPublicationGrace<Pair<String?, String?>>()
        assertEquals(listOf(listOf(legacy)) to 2_600L, WorkoutDataItems.readablePublications(members.drop(1) + legacy, grace, 100L))
        assertEquals(listOf(members.reversed()) to null, WorkoutDataItems.readablePublications(members.reversed(), grace, 200L))
    }

    @Test fun tailOfPartiallyAcknowledgedPublicationBecomesReadable() {
        val members = publication("a", "b", "c")
        val grace = WorkoutPublicationGrace<Pair<String?, String?>>()
        assertEquals(listOf(members) to null, WorkoutDataItems.readablePublications(members, grace, 100L))
        val tail = members.drop(1)
        assertEquals(emptyList<List<WorkoutDataItems.Member>>() to 2_700L, WorkoutDataItems.readablePublications(tail, grace, 200L))
        assertEquals(emptyList<List<WorkoutDataItems.Member>>() to 2_700L, WorkoutDataItems.readablePublications(tail, grace, 2_699L))
        assertEquals(listOf(tail) to null, WorkoutDataItems.readablePublications(tail, grace, 2_700L))
    }

    @Test fun overlappingLegacyPublicationsWithStaleSizesCannotBlockEachOther() {
        val first = publication("a", "b")
        val replacement = publication("a", "b", "c")
        val observed = listOf(first.first(), replacement[1], replacement.last())
        val grace = WorkoutPublicationGrace<Pair<String?, String?>>()
        assertEquals(emptyList<List<WorkoutDataItems.Member>>() to 2_500L, WorkoutDataItems.readablePublications(observed, grace, 0L))
        val expected = listOf(observed.take(1), observed.drop(1))
        assertEquals(expected to null, WorkoutDataItems.readablePublications(observed, grace, 2_500L))
    }

    private fun publication(vararg ids: String): List<WorkoutDataItems.Member> {
        val doc = JsonObject(emptyMap())
        val events = ids.map { WorkoutEvent(it, "watch", 1, WorkoutProfile.PERSONAL, "s", doc, doc) }
        val publication = WorkoutPublication(events)
        return events.map { event ->
            WorkoutDataItems.Member(Uri.parse("wear://watch/gymapp/event/v1/${event.eventId}"), DataMap().also(publication::mark))
        }
    }
}
