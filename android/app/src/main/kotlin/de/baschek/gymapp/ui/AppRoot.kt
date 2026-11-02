package de.baschek.gymapp.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import de.baschek.gymapp.ui.history.HistoryScreen
import de.baschek.gymapp.ui.home.HomeScreen
import de.baschek.gymapp.ui.pairing.PairingScreen
import de.baschek.gymapp.ui.workout.WorkoutScreen

private const val HOME = "home"
private const val PAIRING = "pairing"
private const val HISTORY = "history"
private const val WORKOUT_PREFIX = "workout:"

/** Minimal state-based navigation; screens only receive callbacks, so they can be reskinned freely. */
@Composable
fun AppRoot(requestedSession: String? = null, onRequestHandled: () -> Unit = {}) {
    var route by rememberSaveable { mutableStateOf(HOME) }
    LaunchedEffect(requestedSession) {
        if (requestedSession != null) {
            route = WORKOUT_PREFIX + requestedSession
            onRequestHandled()
        }
    }
    BackHandler(enabled = route != HOME) { route = HOME }
    Scaffold { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                route == HISTORY -> HistoryScreen(onClose = { route = HOME })
                route == PAIRING -> PairingScreen(onDone = { route = HOME })
                route.startsWith(WORKOUT_PREFIX) ->
                    WorkoutScreen(sessionId = route.removePrefix(WORKOUT_PREFIX), onClose = { route = HOME })
                else -> HomeScreen(
                    onOpenPairing = { route = PAIRING },
                    onOpenHistory = { route = HISTORY },
                    onOpenWorkout = { route = WORKOUT_PREFIX + it },
                )
            }
        }
    }
}
