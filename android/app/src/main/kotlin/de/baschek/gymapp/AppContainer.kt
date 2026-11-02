package de.baschek.gymapp

import android.app.Application
import de.baschek.gymapp.core.api.createHttpClient
import de.baschek.gymapp.data.DeviceKeys
import de.baschek.gymapp.data.GymRepository
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

/** Hand-wired dependencies (no DI framework needed at this size). */
class AppContainer(app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val repository = GymRepository(GymDatabase.build(app))
    val secureStore = SecureStore(app)
    val scheduler = SyncScheduler(app)
    private val httpClient = createHttpClient(OkHttp.create())
    val syncManager = SyncManager(repository, secureStore, httpClient, scheduler, DeviceKeys())
    val timer = TimerController(repository, appScope)
    val workout = WorkoutActions(repository, timer, scheduler)
    val updater = AppUpdater(app, httpClient)

    init {
        Telemetry.install(repository, appScope)
        // Bring back a running timer after process death.
        appScope.launch { runCatching { timer.restore() } }
    }
}
