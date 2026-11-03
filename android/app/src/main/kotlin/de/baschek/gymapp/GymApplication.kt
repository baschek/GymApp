package de.baschek.gymapp

import android.app.Application

class GymApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.scheduler.schedulePeriodic()
        // Sync on app start; the worker does nothing while the app is not paired.
        container.scheduler.requestSync()
    }
}
