package de.baschek.gymapp.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.baschek.gymapp.GymApplication
import de.baschek.gymapp.data.Profile
import de.baschek.gymapp.ui.components.GymBottomBar
import de.baschek.gymapp.ui.components.bottomBarSpace
import de.baschek.gymapp.ui.history.HistoryScreen
import de.baschek.gymapp.ui.history.SessionDetailScreen
import de.baschek.gymapp.ui.home.HomeScreen
import de.baschek.gymapp.ui.more.ExerciseDetailScreen
import de.baschek.gymapp.ui.more.ExercisesScreen
import de.baschek.gymapp.ui.more.GoalsScreen
import de.baschek.gymapp.ui.more.MachineScreen
import de.baschek.gymapp.ui.more.MoreScreen
import de.baschek.gymapp.ui.more.SettingsScreen
import de.baschek.gymapp.ui.pairing.PairingScreen
import de.baschek.gymapp.ui.plans.PlanEditorScreen
import de.baschek.gymapp.ui.plans.PlansScreen
import de.baschek.gymapp.ui.progress.ProgressScreen
import de.baschek.gymapp.ui.workout.WorkoutScreen
import kotlinx.coroutines.launch

/** Navigation shell: top-level tabs with the translucent bottom bar, detail screens, the training flow without bar. */
@Composable
fun AppRoot(requestedSession: String? = null, onRequestHandled: () -> Unit = {}) {
    val container = (LocalContext.current.applicationContext as GymApplication).container
    val nav = rememberNavigator(Routes.TODAY)
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val profile by container.repository.activeProfile.collectAsStateWithLifecycle(initialValue = Profile.PERSONAL)

    LaunchedEffect(requestedSession) {
        if (requestedSession != null) {
            nav.push(Routes.workout(requestedSession))
            onRequestHandled()
        }
    }
    BackHandler(enabled = nav.canPop) { nav.pop() }

    val route = nav.current
    val inWorkout = Routes.kind(route) == "workout"
    val test = profile == Profile.TEST
    val show: (String) -> Unit = { msg -> scope.launch { snackbar.showSnackbar(msg) } }

    CompositionLocalProvider(LocalSnack provides show, LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            if (test) TestBar()
            Box(Modifier.weight(1f).fillMaxWidth().then(if (test) Modifier.consumeWindowInsets(WindowInsets.statusBars) else Modifier)) {
                when (Routes.kind(route)) {
                    Routes.TODAY -> HomeScreen(nav, onOpenWorkout = { nav.push(Routes.workout(it)) })
                    Routes.PLANS -> PlansScreen(nav)
                    "plan" -> PlanEditorScreen(Routes.arg(route), nav)
                    Routes.HISTORY -> HistoryScreen(nav)
                    "session" -> SessionDetailScreen(Routes.arg(route), nav)
                    Routes.PROGRESS -> ProgressScreen(nav)
                    Routes.MORE -> MoreScreen(nav)
                    Routes.EXERCISES -> ExercisesScreen(nav)
                    "exercise" -> ExerciseDetailScreen(Routes.arg(route), nav)
                    "machine" -> MachineScreen(Routes.arg(route, 1), Routes.arg(route, 2), nav)
                    Routes.GOALS -> GoalsScreen(nav)
                    Routes.SETTINGS -> SettingsScreen(nav)
                    Routes.PAIRING -> PairingScreen(nav)
                    "workout" -> WorkoutScreen(Routes.arg(route), nav)
                    else -> HomeScreen(nav, onOpenWorkout = { nav.push(Routes.workout(it)) })
                }
                if (!inWorkout) {
                    GymBottomBar(
                        Routes.tabOf(route),
                        onSelect = { tab ->
                            de.baschek.gymapp.telemetry.Telemetry.track("navigation", "tab_${tab.route}")
                            nav.resetTo(tab.route)
                        },
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = if (inWorkout) 96.dp else bottomBarSpace() + 8.dp, start = 16.dp, end = 16.dp))
            }
        }
    }
}

/** Marks the local-only test profile on every screen (the only use of `tertiary`). */
@Composable
private fun TestBar() {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth().background(cs.tertiaryContainer).windowInsetsPadding(WindowInsets.statusBars)) {
        Text(
            "TESTPROFIL · NUR LOKAL", Modifier.fillMaxWidth().padding(vertical = 4.dp), textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp), color = cs.tertiary,
        )
    }
}
