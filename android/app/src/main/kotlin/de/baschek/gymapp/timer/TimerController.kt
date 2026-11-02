package de.baschek.gymapp.timer

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TimerPhase { SET, REST, MACHINE_SETUP }

data class TimerState(
    val phase: TimerPhase,
    /** Wall clock start (epoch millis); the notification chronometer and the UI derive elapsed time from it. */
    val startedAtMillis: Long,
    val label: String,
    val suggestedRestSeconds: Int = 0,
) {
    fun elapsedSeconds(nowMillis: Long = System.currentTimeMillis()): Int =
        ((nowMillis - startedAtMillis) / 1000).toInt().coerceAtLeast(0)
}

/** Durations measured before a set started; consumed when the set is logged. */
data class BeforeSet(val restSeconds: Int?, val setupSeconds: Int?)

/** In-process timer state; [TimerService] mirrors it as a foreground notification. */
class TimerController(private val context: Context) {
    private val _state = MutableStateFlow<TimerState?>(null)
    val state: StateFlow<TimerState?> = _state.asStateFlow()

    private var pendingRest: Int? = null
    private var pendingSetup: Int? = null

    @Synchronized
    fun start(phase: TimerPhase, label: String, suggestedRestSeconds: Int = 0) {
        val previous = _state.value
        if (previous != null) rememberBeforeSet(previous)
        _state.value = TimerState(phase, System.currentTimeMillis(), label, suggestedRestSeconds)
        ContextCompat.startForegroundService(context, Intent(context, TimerService::class.java))
    }

    /** Stops the running timer and returns the elapsed seconds (null if none was running). */
    @Synchronized
    fun stop(): Int? {
        val current = _state.value ?: return null
        rememberBeforeSet(current)
        _state.value = null
        return current.elapsedSeconds()
    }

    @Synchronized
    fun consumeBeforeSet(): BeforeSet {
        val out = BeforeSet(pendingRest, pendingSetup)
        pendingRest = null
        pendingSetup = null
        return out
    }

    private fun rememberBeforeSet(s: TimerState) {
        when (s.phase) {
            TimerPhase.REST -> pendingRest = s.elapsedSeconds()
            TimerPhase.MACHINE_SETUP -> pendingSetup = s.elapsedSeconds()
            TimerPhase.SET -> Unit
        }
    }
}
