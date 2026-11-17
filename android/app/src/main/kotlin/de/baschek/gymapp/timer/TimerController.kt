package de.baschek.gymapp.timer

import de.baschek.gymapp.core.model.GymJson
import de.baschek.gymapp.core.wear.events.WorkoutTimer
import de.baschek.gymapp.data.GymRepository
import de.baschek.gymapp.data.SettingKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

enum class TimerPhase { SET, REST, MACHINE_SETUP }

data class TimerState(
    val phase: TimerPhase,
    /** Wall clock start (epoch millis); elapsed time and the notification chronometer derive from it. */
    val startedAtMillis: Long,
    val label: String,
    val suggestedRestSeconds: Int = 0,
    val sessionId: String? = null, val exerciseId: String? = null, val setId: String? = null,
) {
    /** Planned end of a rest (epoch millis), if the rest has a suggested duration. */
    val endsAtMillis: Long?
        get() = if (phase == TimerPhase.REST && suggestedRestSeconds > 0) startedAtMillis + suggestedRestSeconds * 1000L else null

    /** A SET stopwatch persisted before timers carried IDs has none; it belongs to whichever set is logged. */
    private val isLegacySet: Boolean get() = sessionId == null && exerciseId == null && setId == null

    fun matchesSet(sessionId: String, exerciseId: String, setId: String): Boolean =
        phase == TimerPhase.SET && (isLegacySet ||
            (this.sessionId == sessionId && this.exerciseId == exerciseId && this.setId == setId))

    fun elapsedSeconds(nowMillis: Long = System.currentTimeMillis()): Int =
        ((nowMillis - startedAtMillis) / 1000).toInt().coerceAtLeast(0)
}

/** Durations measured before a set started; consumed when the set is logged. */
data class BeforeSet(val restSeconds: Int?, val setupSeconds: Int?)

@Serializable
internal data class PersistedTimer(
    val phase: String? = null,
    val startedAtMillis: Long = 0,
    val label: String = "",
    val suggestedRestSeconds: Int = 0,
    val pendingRest: Int? = null,
    val pendingSetup: Int? = null,
    val sessionId: String? = null, val exerciseId: String? = null, val setId: String? = null,
)

/**
 * Timer state of the active workout. Every change is persisted (phase and absolute
 * timestamps) so it survives process death; [restore] reads it back at app start.
 */
class TimerController(
    private val repository: GymRepository,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow<TimerState?>(null)
    val state: StateFlow<TimerState?> = _state.asStateFlow()

    private var pendingRest: Int? = null
    private var pendingSetup: Int? = null
    private val persistMutex = Mutex()
    private var generation = 0L

    @Synchronized
    fun start(phase: TimerPhase, label: String, suggestedRestSeconds: Int = 0,
              sessionId: String? = null, exerciseId: String? = null, setId: String? = null) {
        _state.value?.let(::rememberBeforeSet)
        _state.value = TimerState(phase, clock(), label, suggestedRestSeconds, sessionId, exerciseId, setId)
        persist()
    }

    /** Adds [seconds] to a running rest ("+15 s"); a count-up rest becomes a countdown from its elapsed time. */
    @Synchronized
    fun extendRest(seconds: Int) {
        val s = _state.value ?: return
        if (s.phase != TimerPhase.REST) return
        val base = if (s.suggestedRestSeconds > 0) s.suggestedRestSeconds else s.elapsedSeconds(clock())
        _state.value = s.copy(suggestedRestSeconds = base + seconds)
        persist()
    }

    /** Stops the running timer and returns the elapsed seconds (null if none was running). */
    @Synchronized
    fun stop(): Int? {
        val current = _state.value ?: return null
        rememberBeforeSet(current)
        _state.value = null
        persist()
        return current.elapsedSeconds(clock())
    }

    @Synchronized
    fun consumeBeforeSet(): BeforeSet {
        val out = BeforeSet(pendingRest, pendingSetup)
        pendingRest = null
        pendingSetup = null
        persist()
        return out
    }

    /** Clears everything, e.g. when the session ended. */
    @Synchronized
    fun reset() {
        _state.value = null
        pendingRest = null
        pendingSetup = null
        persist()
    }

    /** Restores the persisted timer if a session is still active and the data is not stale. */
    suspend fun restore() {
        val stored = repository.getSetting(SettingKeys.TIMER_STATE)
            ?.let { runCatching { GymJson.decodeFromString(PersistedTimer.serializer(), it) }.getOrNull() }
        val active = repository.getActiveSession()
        synchronized(this) {
            if (stored == null) return
            if (active == null || clock() - stored.startedAtMillis > MAX_AGE_MILLIS && stored.phase != null) {
                _state.value = null
                pendingRest = null
                pendingSetup = null
            } else {
                _state.value = stored.phase?.let { name ->
                    runCatching { TimerPhase.valueOf(name) }.getOrNull()?.let {
                        TimerState(it, stored.startedAtMillis, stored.label, stored.suggestedRestSeconds, stored.sessionId, stored.exerciseId, stored.setId)
                    }
                }
                pendingRest = stored.pendingRest
                pendingSetup = stored.pendingSetup
            }
        }
        persist(publish = false)
    }

    /** Applied after a watch event wins; never creates a phone-origin echo. */
    @Synchronized
    fun applyRemote(timer: WorkoutTimer?) {
        val phase = timer?.phase?.let { runCatching { TimerPhase.valueOf(it) }.getOrNull() }
        val end = timer?.endsAtMillis
        _state.value = if (phase == null || timer == null) null else TimerState(
            phase, timer.startedAtMillis, timer.label,
            if (phase == TimerPhase.REST && end != null)
                ((end - timer.startedAtMillis).coerceAtLeast(0) / 1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else 0,
            timer.sessionId, timer.exerciseId, timer.setId,
        )
        pendingRest = timer?.pendingRestSeconds
        pendingSetup = timer?.pendingSetupSeconds
        persist(publish = false)
    }

    private fun rememberBeforeSet(s: TimerState) {
        when (s.phase) {
            TimerPhase.REST -> pendingRest = s.elapsedSeconds(clock())
            TimerPhase.MACHINE_SETUP -> pendingSetup = s.elapsedSeconds(clock())
            TimerPhase.SET -> Unit
        }
    }

    /** Writes the latest state (read when the write runs, so rapid changes cannot reorder). */
    @Synchronized
    private fun persist(publish: Boolean = true) {
        val sequence = ++generation
        scope.launch {
            persistMutex.withLock {
                val json = synchronized(this@TimerController) {
                    if (sequence != generation) return@withLock
                    snapshot()
                }
                repository.setSetting(SettingKeys.TIMER_STATE, json)
                if (publish) repository.publishPhoneTimerEvent()
            }
        }
    }

    @Synchronized
    private fun snapshot(): String? {
        val s = _state.value
        if (s == null && pendingRest == null && pendingSetup == null) return null
        return GymJson.encodeToString(
            PersistedTimer.serializer(),
            PersistedTimer(s?.phase?.name, s?.startedAtMillis ?: 0, s?.label.orEmpty(), s?.suggestedRestSeconds ?: 0, pendingRest, pendingSetup, s?.sessionId, s?.exerciseId, s?.setId),
        )
    }

    private companion object {
        const val MAX_AGE_MILLIS = 12L * 60 * 60 * 1000
    }
}
