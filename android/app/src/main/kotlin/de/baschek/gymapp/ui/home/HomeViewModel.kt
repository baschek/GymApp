package de.baschek.gymapp.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.baschek.gymapp.AppContainer
import de.baschek.gymapp.core.model.Gym
import de.baschek.gymapp.core.model.Plan
import de.baschek.gymapp.core.model.Session
import de.baschek.gymapp.core.model.WorkoutMode
import de.baschek.gymapp.core.stats.PackState
import de.baschek.gymapp.core.stats.PlanSuggestion
import de.baschek.gymapp.core.stats.recommendPlans
import de.baschek.gymapp.data.Profile
import de.baschek.gymapp.data.SettingKeys
import de.baschek.gymapp.data.Stored
import de.baschek.gymapp.telemetry.Telemetry
import de.baschek.gymapp.ui.SyncUi
import de.baschek.gymapp.ui.SyncUiSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate

data class PackRow(val id: String, val text: String, val checked: Boolean)

/** "Heute": gym choice, recommended plan, mode, packing list and the sync state. */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(private val c: AppContainer) : ViewModel() {
    private val repo = c.repository
    private fun <T> kotlinx.coroutines.flow.Flow<T>.state(initial: T): StateFlow<T> =
        stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), initial)

    val sync: StateFlow<SyncUi> = SyncUiSource(c, viewModelScope).state
    val profile: StateFlow<String> = repo.activeProfile.state(Profile.PERSONAL)
    val plans: StateFlow<List<Stored<Plan>>> = repo.observePlans().state(emptyList())
    val gyms: StateFlow<List<Stored<Gym>>> = repo.observeGyms().state(emptyList())
    val activeSession: StateFlow<Stored<Session>?> = repo.observeActiveSession().state(null)

    /** The gym used last time; shown as a hint but never preselected (the gym is confirmed with a tap). */
    val lastGym: StateFlow<String?> = repo.observeSetting(SettingKeys.CURRENT_GYM).state(null)

    val suggestions: StateFlow<List<PlanSuggestion>> =
        combine(plans, repo.observeCompletedSessions()) { p, s ->
            recommendPlans(p.map { it.id to it.value.name }, s.map { it.value }, Instant.now())
        }.state(emptyList())

    val selectedGym = MutableStateFlow<String?>(null)
    val selectedPlan = MutableStateFlow<String?>(null)
    val mode = MutableStateFlow(WorkoutMode.NORMAL)

    val pack: StateFlow<List<PackRow>> = selectedGym.flatMapLatest { gymId ->
        if (gymId == null) flowOf(emptyList())
        else combine(gyms, repo.observePackChecked(gymId)) { list, raw ->
            val items = list.firstOrNull { it.id == gymId }?.value?.packlist.orEmpty().sortedBy { it.order }
            val checked = PackState.decodeOrNull(raw, LocalDate.now().toString())
                ?: items.filter { it.doneDefault == true }.map { it.id }.toSet()
            items.map { PackRow(it.id, it.text, it.id in checked) }
        }
    }.state(emptyList())

    fun selectGym(id: String) {
        selectedGym.value = id
    }

    fun selectPlan(id: String?) {
        selectedPlan.value = id
    }

    fun setMode(value: String) {
        mode.value = value
    }

    fun togglePack(itemId: String) {
        val gymId = selectedGym.value ?: return
        viewModelScope.launch {
            val defaults = gyms.value.firstOrNull { it.id == gymId }?.value?.packlist.orEmpty()
                .filter { it.doneDefault == true }.map { it.id }.toSet()
            repo.togglePackItem(gymId, itemId, LocalDate.now().toString(), defaults)
        }
    }

    fun syncNow() {
        viewModelScope.launch { c.syncManager.runSync(manual = true) }
    }

    fun resolveConflicts(items: List<String>, keepMine: Boolean) {
        viewModelScope.launch { items.forEach { repo.resolveConflict(it, keepMine) } }
    }

    fun seedDemo() {
        viewModelScope.launch { repo.seedDemoData() }
    }

    /** Starts (or resumes) a session in the explicitly chosen gym and reports its id. */
    fun start(planId: String, gymId: String, onStarted: (String) -> Unit) {
        Telemetry.track("heute", "start_session")
        viewModelScope.launch {
            repo.setSetting(SettingKeys.CURRENT_GYM, gymId)
            repo.startSession(planId, mode.value, gymId)?.let(onStarted)
        }
    }
}
