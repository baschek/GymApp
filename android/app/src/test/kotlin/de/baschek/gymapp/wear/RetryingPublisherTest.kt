package de.baschek.gymapp.wear

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RetryingPublisherTest {
    @Test fun failedSnapshotIsRetriedWithoutAnotherChange() = runTest {
        val attempts = mutableListOf<Int>()
        val job = launch { publishWhileActive(MutableStateFlow(7), 100) { attempts.add(it); if (attempts.size == 1) error("temporary") } }
        runCurrent()
        assertEquals(listOf(7), attempts)
        advanceTimeBy(100); runCurrent()
        assertEquals(listOf(7, 7), attempts)
        job.cancel()
    }

    @Test fun failedSourceIsObservedAgainAndLatestQueueIsPublished() = runTest {
        var subscriptions = 0
        val observed = mutableListOf<Int>()
        val source = flow { subscriptions++; if (subscriptions == 1) error("temporary"); emit(3) }
        val job = launch { publishWhileActive(source, 100) { observed.add(it) } }
        runCurrent(); advanceTimeBy(100); runCurrent()
        assertEquals(2, subscriptions)
        assertEquals(listOf(3), observed)
        job.cancel()
    }
}
