package de.baschek.gymapp.wear.transport

import android.content.Context
import android.os.SystemClock
import android.util.Log
import de.baschek.gymapp.core.wear.events.WorkoutEvent
import de.baschek.gymapp.core.wear.events.WorkoutPublicationGrace
import de.baschek.gymapp.core.wear.events.afterWorkoutQuietWindow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Coalesces Data Layer callbacks. All scans use the durable inbox, including after a missed callback. */
internal class WorkoutInbox(
    private val context: Context,
    private val canReceive: suspend () -> Boolean,
    private val pollWhenVisible: suspend () -> Boolean = { false },
    private val onFailure: () -> Unit = {},
    private val receive: suspend (List<WorkoutEvent>) -> Unit,
) {
    private val scanMutex = Mutex()
    private val changes = Channel<Unit>(Channel.CONFLATED)
    private val scans = Channel<Unit>(Channel.CONFLATED)
    private val grace = WorkoutPublicationGrace<Pair<String?, String?>>()
    private val rescanAt = MutableStateFlow<Long?>(null)
    private val hasPending = MutableStateFlow(false)
    private val visible = MutableStateFlow(0)

    fun start(scope: CoroutineScope) = scope.launch {
        launch {
            changes.receiveAsFlow().afterWorkoutQuietWindow().collect { scans.trySend(Unit) }
        }
        launch {
            rescanAt.collectLatest { deadline ->
                if (deadline != null) {
                    delay((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
                    scans.trySend(Unit)
                }
            }
        }
        launch {
            while (true) {
                delay(30_000L)
                reportFailure {
                    if (hasPending.value || (visible.value > 0 && pollWhenVisible())) scans.trySend(Unit)
                }
            }
        }
        catchUp()
        for (ignored in scans) catchUp()
    }

    fun changed() { changes.trySend(Unit) }

    suspend fun catchUpWhileVisible() {
        visible.update { it + 1 }
        scans.trySend(Unit)
        try { awaitCancellation() } finally { visible.update { it - 1 } }
    }

    suspend fun catchUp(): Unit = withContext(Dispatchers.IO) {
        scanMutex.withLock {
            reportFailure {
                if (canReceive()) {
                    val result = WorkoutDataItems.ready(context, grace)
                    rescanAt.value = result.rescanAt
                    hasPending.value = result.hasPending
                    if (result.events.isNotEmpty()) receive(result.events)
                } else {
                    rescanAt.value = null
                    hasPending.value = false
                }
            }
        }
    }

    private suspend inline fun reportFailure(block: () -> Unit) {
        try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) {
            Log.w("WorkoutInbox", "Could not reconcile workout inbox (${e.javaClass.simpleName})")
            onFailure()
        }
    }
}
