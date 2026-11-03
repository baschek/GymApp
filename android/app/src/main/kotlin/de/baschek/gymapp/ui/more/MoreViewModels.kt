package de.baschek.gymapp.ui.more

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.baschek.gymapp.AppContainer
import de.baschek.gymapp.core.model.Exercise
import de.baschek.gymapp.core.model.Goal
import de.baschek.gymapp.core.model.Gym
import de.baschek.gymapp.core.model.MachineProfile
import de.baschek.gymapp.core.update.UpdateInfo
import de.baschek.gymapp.data.Accent
import de.baschek.gymapp.data.GoalState
import de.baschek.gymapp.data.Profile
import de.baschek.gymapp.data.SettingKeys
import de.baschek.gymapp.data.Stored
import de.baschek.gymapp.telemetry.Telemetry
import de.baschek.gymapp.ui.SyncUi
import de.baschek.gymapp.ui.SyncUiSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

private fun <T> kotlinx.coroutines.flow.Flow<T>.stateIn2(vm: ViewModel, initial: T): StateFlow<T> =
    stateIn(vm.viewModelScope, SharingStarted.WhileSubscribed(5000), initial)

class ExercisesViewModel(c: AppContainer) : ViewModel() {
    private val repo = c.repository
    val catalog: StateFlow<List<Stored<Exercise>>> = repo.observeExercises().stateIn2(this, emptyList())
    val profiles: StateFlow<List<Stored<MachineProfile>>> = repo.observeMachineProfiles().stateIn2(this, emptyList())
}

class ExerciseDetailViewModel(private val c: AppContainer, private val exerciseId: String) : ViewModel() {
    private val repo = c.repository
    val exercise: StateFlow<Stored<Exercise>?> = repo.observeExercises().map { l -> l.firstOrNull { it.id == exerciseId } }.stateIn2(this, null)
    val profiles: StateFlow<List<Stored<MachineProfile>>> = repo.observeMachineProfiles().stateIn2(this, emptyList())
    val gyms: StateFlow<List<Stored<Gym>>> = repo.observeGyms().stateIn2(this, emptyList())

    fun saveNotes(text: String) {
        viewModelScope.launch { repo.updateExercise(exerciseId) { it.copy(notes = text.trim().ifEmpty { null }) } }
    }

    /** Photos stay on this device (the contract does not sync photo bytes). */
    fun photoFile(): File = File(c.photoDir(), "$exerciseId.jpg")
}

class MachineViewModel(private val c: AppContainer, private val gymId: String, private val exerciseId: String) : ViewModel() {
    private val repo = c.repository
    val profiles: StateFlow<List<Stored<MachineProfile>>> = repo.observeMachineProfiles().stateIn2(this, emptyList())
    val gyms: StateFlow<List<Stored<Gym>>> = repo.observeAllGyms().stateIn2(this, emptyList())
    val exercise: StateFlow<Stored<Exercise>?> = repo.observeExercises().map { l -> l.firstOrNull { it.id == exerciseId } }.stateIn2(this, null)

    fun save(transform: (MachineProfile) -> MachineProfile, onSaved: () -> Unit) {
        viewModelScope.launch {
            repo.saveMachineProfile(gymId, exerciseId, transform)
            onSaved()
        }
    }
}

class GoalsViewModel(c: AppContainer) : ViewModel() {
    private val repo = c.repository
    val goal: StateFlow<GoalState?> = repo.observeGoal().stateIn2(this, null)

    fun change(transform: (Goal) -> Goal) {
        viewModelScope.launch { repo.saveGoal(transform) }
    }
}

sealed interface UpdateUi {
    data object Idle : UpdateUi
    data object Checking : UpdateUi
    data object UpToDate : UpdateUi
    data class Available(val info: UpdateInfo) : UpdateUi
    data object Downloading : UpdateUi
    data class NeedsPermission(val info: UpdateInfo) : UpdateUi
    data object Error : UpdateUi
}

class SettingsViewModel(private val c: AppContainer) : ViewModel() {
    private val repo = c.repository
    val sync: StateFlow<SyncUi> = SyncUiSource(c, viewModelScope).state
    val profile: StateFlow<String> = repo.activeProfile.stateIn2(this, Profile.PERSONAL)
    val accent: StateFlow<Accent> = c.prefs.accent.stateIn2(this, Accent.PAPIER)
    val restVibration: StateFlow<Boolean> = c.prefs.restVibration.stateIn2(this, true)
    val keepScreenOn: StateFlow<Boolean> = repo.observeSetting(SettingKeys.KEEP_SCREEN_ON).map { it != "0" }.stateIn2(this, true)
    val paired: StateFlow<Boolean> = c.secureStore.paired

    private val _update = MutableStateFlow<UpdateUi>(UpdateUi.Idle)
    val update: StateFlow<UpdateUi> = _update.asStateFlow()
    private var downloaded: File? = null

    fun setProfile(p: String) {
        viewModelScope.launch { repo.setProfile(p) }
    }

    fun setAccent(a: Accent) {
        viewModelScope.launch { c.prefs.setAccent(a) }
    }

    fun setRestVibration(on: Boolean) {
        viewModelScope.launch { c.prefs.setRestVibration(on) }
    }

    fun setKeepScreenOn(on: Boolean) {
        viewModelScope.launch { repo.setSetting(SettingKeys.KEEP_SCREEN_ON, if (on) "1" else "0") }
    }

    fun syncNow() {
        viewModelScope.launch { c.syncManager.runSync(manual = true) }
    }

    fun unpair() {
        viewModelScope.launch { c.syncManager.unpair() }
    }

    fun resolveConflicts(items: List<String>, keepMine: Boolean) {
        viewModelScope.launch { items.forEach { repo.resolveConflict(it, keepMine) } }
    }

    fun seedDemo() {
        viewModelScope.launch { repo.seedDemoData() }
    }

    fun checkUpdate() {
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
        Telemetry.track("einstellungen", "install_update")
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
