package de.baschek.gymapp.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.baschek.gymapp.AppContainer
import de.baschek.gymapp.core.model.Gym
import de.baschek.gymapp.core.model.Plan
import de.baschek.gymapp.core.model.Session
import de.baschek.gymapp.core.update.UpdateInfo
import de.baschek.gymapp.data.Profile
import de.baschek.gymapp.data.SettingKeys
import de.baschek.gymapp.data.Stored
import de.baschek.gymapp.sync.SyncState
import de.baschek.gymapp.telemetry.Telemetry
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant

sealed interface UpdateUi {
    data object Idle : UpdateUi
    data object Checking : UpdateUi
    data object UpToDate : UpdateUi
    data class Available(val info: UpdateInfo) : UpdateUi
    data object Downloading : UpdateUi
    data class NeedsPermission(val info: UpdateInfo) : UpdateUi
    data object Error : UpdateUi
}

class HomeViewModel(private val c: AppContainer) : ViewModel() {
    private val repo = c.repository

    val profile: StateFlow<String> = repo.activeProfile.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Profile.PERSONAL)
    val paired: StateFlow<Boolean> = c.secureStore.paired
    val syncState: StateFlow<SyncState> = c.syncManager.state
    val lastSyncAt: StateFlow<Instant?> = repo.lastSyncAt.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val connectionLost: StateFlow<Boolean> = repo.connectionLost.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val authError: StateFlow<Boolean> = repo.authError.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val notices: StateFlow<List<String>> = repo.syncNotices.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val plans: StateFlow<List<Stored<Plan>>> = repo.observePlans().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val gyms: StateFlow<List<Stored<Gym>>> = repo.observeGyms().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val activeSession: StateFlow<Stored<Session>?> = repo.observeActiveSession().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val currentGym: StateFlow<String?> = repo.observeSetting(SettingKeys.CURRENT_GYM).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _update = MutableStateFlow<UpdateUi>(UpdateUi.Idle)
    val update: StateFlow<UpdateUi> = _update.asStateFlow()
    private var downloaded: File? = null

    fun syncNow() {
        Telemetry.track("home", "sync_now")
        viewModelScope.launch { c.syncManager.runSync(manual = true) }
    }

    fun unpair() {
        Telemetry.track("home", "unpair")
        viewModelScope.launch { c.syncManager.unpair() }
    }

    fun setProfile(p: String) {
        Telemetry.track("home", "profile_$p")
        viewModelScope.launch { repo.setProfile(p) }
    }

    fun selectGym(id: String) {
        viewModelScope.launch { repo.setSetting(SettingKeys.CURRENT_GYM, id) }
    }

    fun seedDemo() {
        Telemetry.track("home", "seed_demo")
        viewModelScope.launch { repo.seedDemoData() }
    }

    /** Starts (or resumes) a session in the explicitly confirmed gym and reports its id. */
    fun startSession(planId: String, gymId: String, onStarted: (String) -> Unit) {
        Telemetry.track("home", "start_session")
        viewModelScope.launch {
            repo.setSetting(SettingKeys.CURRENT_GYM, gymId)
            repo.startSession(planId, gymId = gymId)?.let(onStarted)
        }
    }

    fun checkUpdate() {
        Telemetry.track("home", "check_update")
        _update.value = UpdateUi.Checking
        viewModelScope.launch {
            _update.value = try {
                c.updater.checkForUpdate()?.let { UpdateUi.Available(it) } ?: UpdateUi.UpToDate
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                UpdateUi.Error
            }
        }
    }

    fun installUpdate(info: UpdateInfo) {
        Telemetry.track("home", "install_update")
        viewModelScope.launch {
            try {
                val file = downloaded ?: run {
                    _update.value = UpdateUi.Downloading
                    c.updater.download(info).also { downloaded = it }
                }
                _update.value = if (c.updater.install(file)) UpdateUi.Available(info) else UpdateUi.NeedsPermission(info)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                downloaded = null
                _update.value = UpdateUi.Error
            }
        }
    }
}
