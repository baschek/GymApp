package de.baschek.gymapp.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import de.baschek.gymapp.AppContainer
import de.baschek.gymapp.GymApplication

/** Creates ViewModels with access to the [AppContainer]. */
inline fun <reified VM : ViewModel> appViewModelFactory(crossinline create: (AppContainer) -> VM): ViewModelProvider.Factory =
    viewModelFactory {
        initializer { create((this[APPLICATION_KEY] as GymApplication).container) }
    }
