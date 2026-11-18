package de.baschek.gymapp.core

import de.baschek.gymapp.core.wear.events.WorkoutEvent
import de.baschek.gymapp.core.wear.events.WorkoutProfile
import de.baschek.gymapp.core.wear.events.WorkoutPublicationGate
import de.baschek.gymapp.core.wear.events.afterWorkoutQuietWindow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutPublicationScheduleTest {
    @Test fun overlappingSendsReadTheirSnapshotOnlyAfterTheAcknowledgement() = runTest {
        val gate = WorkoutPublicationGate()
        val first = event("initial")
        val second = event("second")
        val third = event("third")
        var durable = listOf(first, second)
        val snapshots = mutableListOf<List<WorkoutEvent>>()
        val release = CompletableDeferred<Unit>()

        launch {
            gate.publish(read = { durable }) { snapshot ->
                snapshots += snapshot
                release.await()
            }
        }
        runCurrent()
        launch { gate.acknowledge { durable = durable.filterNot { it == first } } }
        runCurrent()
        launch { gate.publish(read = { durable }) { snapshots += it } }
        runCurrent()
        durable = durable + third

        assertEquals(listOf(listOf(first, second)), snapshots)
        release.complete(Unit)
        runCurrent()
        assertEquals(listOf(listOf(first, second), listOf(second, third)), snapshots)
    }

    @Test fun transportCallbacksInsideTheQuietWindowDeliverOneDurableBatch() = runTest {
        val changes = Channel<Unit>(Channel.CONFLATED)
        val events = listOf(event("first"), event("second"), event("third"))
        val durable = mutableListOf<WorkoutEvent>()
        val receivedBatches = mutableListOf<List<WorkoutEvent>>()
        backgroundScope.launch {
            changes.receiveAsFlow().afterWorkoutQuietWindow().collect { receivedBatches += durable.toList() }
        }

        for (event in events) {
            durable += event
            changes.send(Unit)
            advanceTimeBy(100L)
        }
        assertEquals(emptyList(), receivedBatches)
        advanceTimeBy(300L)
        runCurrent()
        assertEquals(listOf(events), receivedBatches)
    }

    private fun event(id: String): WorkoutEvent {
        val document = JsonObject(emptyMap())
        return WorkoutEvent(id, "device", 1L, WorkoutProfile.PERSONAL, "session", document, document)
    }
}
