package de.baschek.gymapp.core.wear.events

import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A sender and its ACK handler share this lock so an in-flight send cannot resurrect an ACKed item. */
class WorkoutPublicationGate {
    private val mutex = Mutex()

    suspend fun publish(
        read: suspend () -> List<WorkoutEvent>,
        send: suspend (List<WorkoutEvent>) -> Unit,
    ): Unit = mutex.withLock {
        val pending = read()
        if (pending.isNotEmpty()) send(pending)
    }

    suspend fun acknowledge(remove: suspend () -> Unit) = mutex.withLock { remove() }
}

/** Small delivery gaps keep a burst together. Old partial publications eventually become readable. */
class WorkoutPublicationGrace<K>(private val graceMillis: Long = 2_500L) {
    private val firstIncomplete = mutableMapOf<K, Long>()

    data class Selection<K>(val ready: List<K>, val rescanAt: Long?)

    fun select(groups: Map<K, Boolean>, nowMillis: Long): Selection<K> {
        firstIncomplete.keys.retainAll(groups.keys)
        var rescanAt: Long? = null
        val ready = groups.keys.filter { key ->
            if (groups.getValue(key)) {
                firstIncomplete.remove(key)
                true
            } else {
                val deadline = firstIncomplete.getOrPut(key) { nowMillis } + graceMillis
                if (deadline <= nowMillis) true else {
                    rescanAt = minOf(rescanAt ?: deadline, deadline)
                    false
                }
            }
        }
        return Selection(ready, rescanAt)
    }
}

@OptIn(FlowPreview::class)
fun <T> Flow<T>.afterWorkoutQuietWindow(): Flow<T> = debounce(400L)
