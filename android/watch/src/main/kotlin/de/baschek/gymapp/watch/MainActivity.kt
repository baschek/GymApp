package de.baschek.gymapp.watch

import de.baschek.gymapp.watch.recorder.NavRecorder
import android.os.Bundle
import android.os.Build
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.wear.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.wear.ambient.AmbientLifecycleObserver
import de.baschek.gymapp.core.wear.events.WorkoutProfile
import de.baschek.gymapp.watch.runtime.AndroidWatchRuntime
import de.baschek.gymapp.watch.ui.WatchRoot
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var runtime: AndroidWatchRuntime
    private val requestNotificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        (application as WatchApplication).repository.value?.state?.value?.let { state ->
            de.baschek.gymapp.watch.runtime.WorkoutNotification.update(this, state)
        }
    }
    private val requestHeartPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> runtime.onPermissionResult(granted) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as WatchApplication
        runtime = AndroidWatchRuntime(app) { requestHeartPermission.launch(it) }
        lifecycle.addObserver(AmbientLifecycleObserver(this, object : AmbientLifecycleObserver.AmbientLifecycleCallback {
            override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) = runtime.setAmbient(true)
            override fun onExitAmbient() = runtime.setAmbient(false)
        }))
        runtime.refreshPermission()
        runtime.retrySync()
        setContent {
            val repository by app.repository.collectAsState()
            val scope = rememberCoroutineScope()
            val current = repository
            if (current == null) {
                Column(Modifier.fillMaxSize().background(Color.Black).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text("Profil wählen", color = Color.White)
                    Text("Persönlich", color = Color.White, modifier = Modifier.clickable { scope.launch { app.selectProfile(WorkoutProfile.PERSONAL) } }.heightIn(min = 48.dp).wrapContentHeight())
                    Text("Testprofil · nur lokal", color = Color.LightGray, modifier = Modifier.clickable { scope.launch { app.selectProfile(WorkoutProfile.TEST) } }.heightIn(min = 48.dp).wrapContentHeight())
                }
            } else {
                val workout by current.state.collectAsState()
                LaunchedEffect(workout.session?.status) {
                    if (Build.VERSION.SDK_INT >= 33 && workout.session?.status == de.baschek.gymapp.core.model.SessionStatus.ACTIVE &&
                        checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED &&
                        !getPreferences(MODE_PRIVATE).getBoolean("notification_permission_asked", false)) {
                        getPreferences(MODE_PRIVATE).edit().putBoolean("notification_permission_asked", true).apply()
                        requestNotificationPermission.launch("android.permission.POST_NOTIFICATIONS")
                    }
                }
                Box(Modifier.fillMaxSize()) {
                    WatchRoot(current, runtime)
                    de.baschek.gymapp.recorder.shared.RecorderOverlay(NavRecorder, Modifier.align(Alignment.TopCenter).padding(top = 8.dp))
                }
            }
        }
    }
    override fun onResume() {
        super.onResume()
        if (::runtime.isInitialized) { runtime.refreshPermission(); runtime.retrySync() }
    }
}
