package de.baschek.gymapp

import de.baschek.gymapp.recorder.NavRecorder
import android.app.Application
import de.baschek.gymapp.core.api.createHttpClient
import de.baschek.gymapp.health.AutomaticHealthExport
import de.baschek.gymapp.data.AppPrefs
import de.baschek.gymapp.data.DeviceKeys
import de.baschek.gymapp.data.GymRepository
import de.baschek.gymapp.data.Profile
import de.baschek.gymapp.data.SecureStore
import de.baschek.gymapp.data.db.GymDatabase
import de.baschek.gymapp.sync.SyncManager
import de.baschek.gymapp.sync.SyncScheduler
import de.baschek.gymapp.telemetry.Telemetry
import de.baschek.gymapp.timer.TimerController
import de.baschek.gymapp.update.AppUpdater
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import de.baschek.gymapp.workout.WorkoutActions
import de.baschek.gymapp.wear.WatchCatalogPublisher
import de.baschek.gymapp.wear.PhoneEventPublisher
import de.baschek.gymapp.wear.transport.WorkoutDataItems
import de.baschek.gymapp.wear.transport.WorkoutInbox

/** Hand-wired dependencies (no DI framework needed at this size). */
class AppContainer(app: Application) {
    private val appContext = app.applicationContext
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val repository = GymRepository(GymDatabase.build(app))
    val prefs = AppPrefs(app)
    val health = AutomaticHealthExport(appContext, repository)
    val secureStore = SecureStore(app)
    val scheduler = SyncScheduler(app)
    private val httpClient = createHttpClient(OkHttp.create())
    val syncManager = SyncManager(repository, secureStore, httpClient, scheduler, DeviceKeys())
    val timer = TimerController(repository, appScope)
    val workout = WorkoutActions(repository, timer, scheduler, appScope)
    val updater = AppUpdater(app, httpClient)
    private val restoreWorkout = appScope.launch {
        runCatching { timer.restore() }
        runCatching { workout.restoreRepsOverride() }
    }
    internal val phoneEventPublisher = PhoneEventPublisher(appContext, repository)
    internal val workoutInbox = WorkoutInbox(
        appContext,
        canReceive = { repository.currentProfile() == Profile.PERSONAL },
        pollWhenVisible = { repository.getSetting("watch_companion_enabled") == "1" },
    ) { events ->
        // A startup restore must not overwrite the state received from the watch.
        restoreWorkout.join()
        val result = repository.importWatchEvents(events)
        try {
            if (result.appliedCount > 0) scheduler.requestSync()
            val state = result.lastLeader
            if (state != null && state.lastOperation?.eventId == repository.getSetting("watch_leader_event")) {
                timer.applyRemote(state.timer)
                workout.setFocus(state.focusExerciseId, fromRemote = true)
            }
        } finally {
            // A UI or alarm failure must not withhold receipts for committed operations.
            WorkoutDataItems.acknowledge(appContext, result.acknowledgements)
        }
    }

    /** Local-only exercise photos (the contract does not sync photo bytes). */
    fun photoDir(): java.io.File = java.io.File(appContext.filesDir, "photos").apply { mkdirs() }

    init {
        appScope.launch { repository.cleanOrphanWatchConflicts() }
        NavRecorder.install(app)
        appScope.launch { repository.observeActiveSession().collect { NavRecorder.workoutChanged(it?.id) } }
        Telemetry.install(repository, appScope)
        health.start(appScope)
        WatchCatalogPublisher(appContext, repository, prefs).start(appScope)
        phoneEventPublisher.start(appScope)
        // An adjustment changes sets of several exercises: the one-step undo of the last action would undo the wrong thing.
        syncManager.onAdjustmentsResolved = { workout.clearUndo() }
    }
}
