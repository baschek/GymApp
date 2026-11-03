package de.baschek.gymapp.ui.plans

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.baschek.gymapp.AppContainer
import de.baschek.gymapp.core.model.Exercise
import de.baschek.gymapp.core.model.Plan
import de.baschek.gymapp.core.model.PlanExercise
import de.baschek.gymapp.core.model.Target
import de.baschek.gymapp.core.training.DefaultModeSets
import de.baschek.gymapp.data.Stored
import de.baschek.gymapp.ui.SyncUi
import de.baschek.gymapp.ui.SyncUiSource
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

/** Plan list. */
class PlansViewModel(private val c: AppContainer) : ViewModel() {
    private val repo = c.repository
    private fun <T> kotlinx.coroutines.flow.Flow<T>.state(initial: T): StateFlow<T> =
        stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), initial)

    val sync: StateFlow<SyncUi> = SyncUiSource(c, viewModelScope).state
    val plans: StateFlow<List<Stored<Plan>>> = repo.observePlans().state(emptyList())
    val seen: StateFlow<Map<String, String>> = repo.plansSeen.state(emptyMap())

    fun createPlan(name: String, onCreated: (String) -> Unit) {
        viewModelScope.launch { onCreated(repo.createPlan(name.trim().ifEmpty { "Neuer Plan" })) }
    }

    fun syncNow() {
        viewModelScope.launch { c.syncManager.runSync(manual = true) }
    }

    fun resolveConflicts(items: List<String>, keepMine: Boolean) {
        viewModelScope.launch { items.forEach { repo.resolveConflict(it, keepMine) } }
    }
}

/** One plan in the editor; every change is saved at once and queued for sync. */
class PlanEditorViewModel(private val c: AppContainer, private val planId: String) : ViewModel() {
    private val repo = c.repository
    private fun <T> kotlinx.coroutines.flow.Flow<T>.state(initial: T): StateFlow<T> =
        stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), initial)

    val plan: StateFlow<Stored<Plan>?> = repo.observePlan(planId).state(null)
    val catalog: StateFlow<List<Stored<Exercise>>> = repo.observeExercises().state(emptyList())
    val seen: StateFlow<Map<String, String>> = repo.plansSeen.state(emptyMap())

    fun markSeen(changedAt: String) {
        viewModelScope.launch { repo.markPlanSeen(planId, changedAt) }
    }

    fun rename(name: String) {
        viewModelScope.launch { repo.updatePlan(planId) { it.copy(name = name.trim().ifEmpty { it.name }) } }
    }

    fun archive() {
        viewModelScope.launch { repo.archivePlan(planId) }
    }

    /** Changes one plan exercise. */
    fun edit(planExerciseId: String, change: (PlanExercise) -> PlanExercise) {
        viewModelScope.launch {
            repo.updatePlan(planId) { p -> p.copy(exercises = p.exercises.map { if (it.id == planExerciseId) change(it) else it }) }
        }
    }

    /** Swaps an exercise with its neighbour and renumbers the order. */
    fun move(planExerciseId: String, delta: Int) {
        viewModelScope.launch {
            repo.updatePlan(planId) { p ->
                val list = p.exercises.sortedBy { it.order }.toMutableList()
                val from = list.indexOfFirst { it.id == planExerciseId }
                val to = from + delta
                if (from < 0 || to < 0 || to >= list.size) return@updatePlan p
                val item = list.removeAt(from)
                list.add(to, item)
                p.copy(exercises = list.mapIndexed { i, e -> e.copy(order = i + 1) })
            }
        }
    }

    fun remove(planExerciseId: String) {
        viewModelScope.launch {
            repo.updatePlan(planId) { p ->
                p.copy(exercises = p.exercises.filter { it.id != planExerciseId }.sortedBy { it.order }.mapIndexed { i, e -> e.copy(order = i + 1) })
            }
        }
    }

    fun add(exerciseId: String, onAdded: (String) -> Unit) {
        val id = UUID.randomUUID().toString()
        viewModelScope.launch {
            repo.updatePlan(planId) { p ->
                p.copy(
                    exercises = p.exercises + PlanExercise(
                        id = id, exerciseId = exerciseId, order = p.exercises.size + 1, modeSets = DefaultModeSets,
                        target = Target("reps", 8.0, 12.0, 1.0, 2.0), restSeconds = 90,
                    ),
                )
            }
            onAdded(id)
        }
    }
}
