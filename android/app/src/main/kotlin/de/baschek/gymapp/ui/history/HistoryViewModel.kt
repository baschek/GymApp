package de.baschek.gymapp.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.baschek.gymapp.AppContainer
import de.baschek.gymapp.core.model.Gym
import de.baschek.gymapp.core.model.Session
import de.baschek.gymapp.core.progression.splitTotal
import de.baschek.gymapp.data.MachineInfo
import de.baschek.gymapp.data.Stored
import de.baschek.gymapp.telemetry.Telemetry
import de.baschek.gymapp.ui.SyncUi
import de.baschek.gymapp.ui.SyncUiSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** History list and calendar. */
class HistoryViewModel(private val c: AppContainer) : ViewModel() {
    private val repo = c.repository
    private fun <T> kotlinx.coroutines.flow.Flow<T>.state(initial: T): StateFlow<T> =
        stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), initial)

    val sync: StateFlow<SyncUi> = SyncUiSource(c, viewModelScope).state
    val sessions: StateFlow<List<Stored<Session>>> = repo.observeCompletedSessions().state(emptyList())
    val gyms: StateFlow<List<Stored<Gym>>> = repo.observeAllGyms().state(emptyList())
    val pending: StateFlow<Set<String>> = repo.pendingSessionIds.state(emptySet())

    fun syncNow() {
        viewModelScope.launch { c.syncManager.runSync(manual = true) }
    }

    fun resolveConflicts(items: List<String>, keepMine: Boolean) {
        viewModelScope.launch { items.forEach { repo.resolveConflict(it, keepMine) } }
    }
}

/** One finished workout: detail, editing, gym change. */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionDetailViewModel(private val c: AppContainer, private val sessionId: String) : ViewModel() {
    private val repo = c.repository
    private fun <T> kotlinx.coroutines.flow.Flow<T>.state(initial: T): StateFlow<T> =
        stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), initial)

    val session: StateFlow<Stored<Session>?> = repo.observeSession(sessionId).state(null)
    val gyms: StateFlow<List<Stored<Gym>>> = repo.observeAllGyms().state(emptyList())
    val activeGyms: StateFlow<List<Stored<Gym>>> = repo.observeGyms().state(emptyList())
    val pending: StateFlow<Set<String>> = repo.pendingSessionIds.state(emptySet())
    val machines: StateFlow<Map<String, MachineInfo>> = repo.observeSession(sessionId)
        .map { it?.value?.gymId }
        .flatMapLatest { g -> if (g == null) flowOf(emptyMap()) else repo.observeMachineInfo(g) }
        .state(emptyMap())

    /** Steps the total weight of a set to the neighbouring selectable total of the machine (or by 2.5 kg). */
    fun stepWeight(exerciseId: String, setId: String, direction: Int, info: MachineInfo?) {
        Telemetry.track("verlauf_detail", "edit_weight")
        viewModelScope.launch {
            repo.editCompletedSession(sessionId) { s ->
                s.copy(
                    exercises = s.exercises.map { ex ->
                        if (ex.id != exerciseId) ex
                        else ex.copy(
                            sets = ex.sets.map { set ->
                                if (set.id != setId) set
                                else {
                                    val current = set.weightKg ?: 0.0
                                    val options = info?.candidates.orEmpty()
                                    val next = if (options.isEmpty()) (current + 2.5 * direction).coerceAtLeast(0.0)
                                    else if (direction > 0) options.firstOrNull { it > current + 1e-9 } ?: options.last()
                                    else options.lastOrNull { it < current - 1e-9 } ?: options.first()
                                    val split = if (info != null && info.levels.isNotEmpty()) splitTotal(next, info.levels, info.addons) else null
                                    set.copy(
                                        weightKg = next,
                                        addonWeightsKg = split?.takeIf { Math.abs(it.totalKg - next) < 1e-6 }?.addonsKg?.takeIf { it.isNotEmpty() },
                                    )
                                }
                            },
                        )
                    },
                )
            }
        }
    }

    fun stepReps(exerciseId: String, setId: String, direction: Int) {
        Telemetry.track("verlauf_detail", "edit_reps")
        viewModelScope.launch {
            repo.editCompletedSession(sessionId) { s ->
                s.copy(
                    exercises = s.exercises.map { ex ->
                        if (ex.id != exerciseId) ex
                        else ex.copy(sets = ex.sets.map { if (it.id == setId) it.copy(reps = ((it.reps ?: 0) + direction).coerceIn(0, 200)) else it })
                    },
                )
            }
        }
    }

    fun changeGym(gymId: String) {
        viewModelScope.launch { repo.reassignSessionGym(sessionId, gymId) }
    }

    fun markReviewed() {
        viewModelScope.launch { repo.clearReviewFlags(sessionId) }
    }
}
