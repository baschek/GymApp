package de.baschek.gymapp

import android.app.Application

/** Plain application for Robolectric tests: no WorkManager scheduling or container setup at startup. */
class TestApplication : Application()
