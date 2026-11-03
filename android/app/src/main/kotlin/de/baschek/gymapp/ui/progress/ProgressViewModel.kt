package de.baschek.gymapp.ui.progress

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.baschek.gymapp.AppContainer
import de.baschek.gymapp.core.model.Exercise
import de.baschek.gymapp.core.model.Gym
import de.baschek.gymapp.core.model.MuscleMapping
import de.baschek.gymapp.core.model.Session
import de.baschek.gymapp.core.stats.ExerciseTrend
import de.baschek.gymapp.core.stats.MuscleSummary
import de.baschek.gymapp.core.stats.StatsPeriod
import de.baschek.gymapp.core.stats.buildTrend
import de.baschek.gymapp.core.stats.start
import de.baschek.gymapp.core.stats.summarizeMuscles
import de.baschek.gymapp.data.GoalState
import de.baschek.gymapp.data.Stored
import de.baschek.gymapp.ui.SyncUi
import de.baschek.gymapp.ui.SyncUiSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

/** Fortschritt: exercise trend, muscle map and balance, all from local data. */
class ProgressViewModel(private val c: AppContainer) : ViewModel() {
    private val repo = c.repository
    private fun <T> kotlinx.coroutines.flow.Flow<T>.state(initial: T): StateFlow<T> =
        stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), initial)

    val sync: StateFlow<SyncUi> = SyncUiSource(c, viewModelScope).state
    val sessions: StateFlow<List<Stored<Session>>> = repo.observeCompletedSessions().state(emptyList())
    val catalog: StateFlow<List<Stored<Exercise>>> = repo.observeExercises().state(emptyList())
    val gyms: StateFlow<List<Stored<Gym>>> = repo.observeAllGyms().state(emptyList())
    val goal: StateFlow<GoalState?> = repo.observeGoal().state(null)

    val period = MutableStateFlow(StatsPeriod.WEEK)
    val exerciseId = MutableStateFlow<String?>(null)
    val chartGym = MutableStateFlow<String?>(null)

    fun setPeriod(p: StatsPeriod) {
        period.value = p
    }

    fun pickExercise(id: String) {
        exerciseId.value = id
        chartGym.value = null
    }

    fun pickGym(id: String?) {
        chartGym.value = id
    }

    private val groups: StateFlow<(String) -> List<String>> = catalog.let { flow ->
        flow.combineMap { list ->
            val map = list.associate { it.id to MuscleMapping.groupsOf(it.value) }
            val lookup: (String) -> List<String> = { map[it].orEmpty() }
            lookup
        }
    }

    private fun <A, B> StateFlow<A>.combineMap(transform: (A) -> B): StateFlow<B> =
        kotlinx.coroutines.flow.flow { collect { emit(transform(it)) } }.state(transform(value))

    /** Weighted sets per muscle group in the selected period. */
    val muscles: StateFlow<MuscleSummary> = combine(sessions, groups, period) { s, g, p ->
        summarizeMuscles(s.map { it.value }, g, p.start(Instant.now(), ZoneId.systemDefault()))
    }.state(MuscleSummary.EMPTY)

    /** The last 7 days, independent of the period switch (overview on the first segment). */
    val week: StateFlow<MuscleSummary> = combine(sessions, groups) { s, g ->
        summarizeMuscles(s.map { it.value }, g, StatsPeriod.WEEK.start(Instant.now(), ZoneId.systemDefault()))
    }.state(MuscleSummary.EMPTY)

    val weekSessions: StateFlow<Int> = sessions.combineMap { list ->
        val from = StatsPeriod.WEEK.start(Instant.now(), ZoneId.systemDefault())
        list.count { s -> runCatching { Instant.parse(s.value.effectiveTime) }.getOrNull()?.let { it >= from } == true }
    }

    /** Exercises that have at least one finished workout, most recently trained first. */
    val trained: StateFlow<List<Stored<Exercise>>> = combine(sessions, catalog) { s, cat ->
        val lastSeen = HashMap<String, String>()
        s.forEach { st -> st.value.exercises.forEach { ex -> lastSeen.merge(ex.exerciseId, st.value.effectiveTime) { a, b -> maxOf(a, b) } } }
        cat.filter { it.id in lastSeen }.sortedByDescending { lastSeen[it.id] }
    }.state(emptyList())

    /** The exercise shown in the chart: the picked one, else the most recently trained. */
    val selectedExercise: StateFlow<Stored<Exercise>?> = combine(trained, exerciseId) { list, id ->
        list.firstOrNull { it.id == id } ?: list.firstOrNull()
    }.state(null)

    /** Gyms in which the selected exercise was trained (newest first). */
    val exerciseGyms: StateFlow<List<String>> = combine(sessions, selectedExercise) { s, ex ->
        if (ex == null) emptyList()
        else s.filter { st -> st.value.exercises.any { it.exerciseId == ex.id } }.mapNotNull { it.value.gymId }.distinct()
    }.state(emptyList())

    /** The chart works per gym because weights of different gyms are not comparable. */
    val activeGym: StateFlow<String?> = combine(exerciseGyms, chartGym) { list, picked -> picked?.takeIf { it in list } ?: list.firstOrNull() }.state(null)

    val trend: StateFlow<ExerciseTrend?> = combine(sessions, selectedExercise, activeGym) { s, ex, g ->
        ex?.let { buildTrend(s.map { it.value }, it.id, g) }
    }.state(null)

    fun syncNow() {
        viewModelScope.launch { c.syncManager.runSync(manual = true) }
    }

    fun resolveConflicts(items: List<String>, keepMine: Boolean) {
        viewModelScope.launch { items.forEach { repo.resolveConflict(it, keepMine) } }
    }
}
