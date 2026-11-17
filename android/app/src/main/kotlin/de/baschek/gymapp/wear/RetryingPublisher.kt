package de.baschek.gymapp.wear

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.retryWhen

/** Re-observe after a source failure and retry the latest durable snapshot even without a new change. */
internal suspend fun <T> publishWhileActive(source: Flow<T>, intervalMillis: Long = 30_000, publish: suspend (T) -> Unit) {
    require(intervalMillis > 0)
    val retried = source.retryWhen { cause, _ ->
        if (cause is CancellationException) throw cause
        delay(intervalMillis)
        true
    }
    val ticks = flow { while (true) { emit(Unit); delay(intervalMillis) } }
    retried.combine(ticks) { value, _ -> value }.conflate().collect { value ->
        try { publish(value) } catch (e: CancellationException) { throw e } catch (_: Exception) {
            // The authoritative local state is retried on the next tick, even if it never changes.
        }
    }
}
