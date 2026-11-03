package de.baschek.gymapp

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import de.baschek.gymapp.timer.WorkoutService
import de.baschek.gymapp.ui.AppRoot
import de.baschek.gymapp.ui.theme.GymAppTheme
import androidx.activity.SystemBarStyle
import androidx.compose.runtime.collectAsState
import de.baschek.gymapp.data.Accent
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    /** Session requested by tapping the ongoing notification. */
    private var requestedSession by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        val prefs = (application as GymApplication).container.prefs
        requestedSession = intent?.getStringExtra(EXTRA_SESSION_ID)
        setContent {
            val accent by prefs.accent.collectAsState(initial = Accent.PAPIER)
            GymAppTheme(accent) {
                AppRoot(requestedSession = requestedSession, onRequestHandled = { requestedSession = null })
            }
        }
        watchActiveSession()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedSession = intent.getStringExtra(EXTRA_SESSION_ID)
    }

    /**
     * While the app is visible and a session is active: keep the screen on (window flag, set again
     * after rotation, process death and returning from the background because the collection
     * restarts with every STARTED) and make sure the foreground service is running.
     */
    private fun watchActiveSession() {
        val repository = (application as GymApplication).container.repository
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(repository.observeActiveSession(), repository.keepScreenOn) { session, keep -> session != null to keep }
                    .collect { (active, keep) ->
                        if (active && keep) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        if (active) WorkoutService.ensureRunning(this@MainActivity)
                    }
            }
        }
    }

    companion object {
        const val EXTRA_SESSION_ID = "de.baschek.gymapp.SESSION_ID"
    }
}
