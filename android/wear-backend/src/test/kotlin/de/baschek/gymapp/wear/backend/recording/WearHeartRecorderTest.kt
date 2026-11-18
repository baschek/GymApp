package de.baschek.gymapp.wear.backend.recording

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import de.baschek.gymapp.core.health.RecordingProfile
import androidx.health.services.client.ExerciseClient
import androidx.health.services.client.data.*
import com.google.common.util.concurrent.Futures
import java.lang.reflect.Proxy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WearHeartRecorderTest {
    @Test fun startReconnectAndStopPersistLifecycle() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(context).grantPermissions("android.permission.BODY_SENSORS")
        val store = RecordingStore(context)
        store.clear()
        var status = ExerciseTrackedStatus.NO_EXERCISE_IN_PROGRESS
        var started = 0
        var ended = 0
        var detached = 0
        var callbackExecutor: java.util.concurrent.Executor? = null
        val client = Proxy.newProxyInstance(ExerciseClient::class.java.classLoader, arrayOf(ExerciseClient::class.java)) { _, method, args ->
            when (method.name) {
                "getCapabilitiesAsync" -> Futures.immediateFuture(ExerciseCapabilities(mapOf(
                    ExerciseType.STRENGTH_TRAINING to ExerciseTypeCapabilities(setOf(DataType.HEART_RATE_BPM), emptyMap(), emptyMap(), false))))
                "getCurrentExerciseInfoAsync" -> Futures.immediateFuture(ExerciseInfo(status, if (status == ExerciseTrackedStatus.NO_EXERCISE_IN_PROGRESS) ExerciseType.UNKNOWN else ExerciseType.STRENGTH_TRAINING))
                "startExerciseAsync" -> { started++; status = ExerciseTrackedStatus.OWNED_EXERCISE_IN_PROGRESS; Futures.immediateFuture(null) }
                "endExerciseAsync" -> { ended++; Futures.immediateFuture(null) }
                "clearUpdateCallbackAsync" -> { detached++; Futures.immediateFuture(null) }
                "setUpdateCallback" -> { callbackExecutor = args[0] as java.util.concurrent.Executor; null }
                else -> error("Unexpected call: ${method.name}")
            }
        } as ExerciseClient
        val first = WearHeartRecorder(context, client, store)
        first.start("synthetic-session", RecordingProfile.PERSONAL)
        assertEquals(RecordingStatus.ACTIVE, store.read()?.status)
        val callbackThread = java.util.concurrent.atomic.AtomicReference<String>()
        val callbackDone = java.util.concurrent.CountDownLatch(1)
        callbackExecutor!!.execute { callbackThread.set(Thread.currentThread().name); callbackDone.countDown() }
        assertTrue(callbackDone.await(2, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals("wear-health-callback", callbackThread.get())
        first.detach()
        assertEquals(1, detached)
        assertEquals(RecordingStatus.ACTIVE, store.read()?.status)
        val restarted = WearHeartRecorder(context, client, RecordingStore(context))
        restarted.reconnect()
        assertEquals(1, started) // reconnect never starts sensors
        restarted.stop()
        assertEquals(1, ended)
        assertEquals(RecordingStatus.STOPPING, RecordingStore(context).read()?.status)
        restarted.detach()
        assertEquals(2, detached)
        store.clear()
    }

    @Test fun orphanedRecordingIsReconciledBeforeANewStart() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(context).grantPermissions("android.permission.BODY_SENSORS")
        val store = RecordingStore(context)
        store.clear()
        var status = ExerciseTrackedStatus.NO_EXERCISE_IN_PROGRESS
        var started = 0
        var ended = 0
        val client = Proxy.newProxyInstance(ExerciseClient::class.java.classLoader, arrayOf(ExerciseClient::class.java)) { _, method, _ ->
            when (method.name) {
                "getCapabilitiesAsync" -> Futures.immediateFuture(ExerciseCapabilities(mapOf(
                    ExerciseType.STRENGTH_TRAINING to ExerciseTypeCapabilities(setOf(DataType.HEART_RATE_BPM), emptyMap(), emptyMap(), false))))
                "getCurrentExerciseInfoAsync" -> Futures.immediateFuture(ExerciseInfo(status, if (status == ExerciseTrackedStatus.NO_EXERCISE_IN_PROGRESS) ExerciseType.UNKNOWN else ExerciseType.STRENGTH_TRAINING))
                "startExerciseAsync" -> { started++; status = ExerciseTrackedStatus.OWNED_EXERCISE_IN_PROGRESS; Futures.immediateFuture(null) }
                "endExerciseAsync" -> { ended++; status = ExerciseTrackedStatus.NO_EXERCISE_IN_PROGRESS; Futures.immediateFuture(null) }
                "clearUpdateCallbackAsync" -> Futures.immediateFuture(null)
                "setUpdateCallback" -> null
                else -> error("Unexpected call: ${method.name}")
            }
        } as ExerciseClient
        val old = WearHeartRecorder(context, client, store)
        old.start("synthetic-a", RecordingProfile.PERSONAL)
        old.detach()
        // A new process finds the old, still active recording.
        val fresh = WearHeartRecorder(context, client, RecordingStore(context))
        assertTrue(fresh.reconcileBeforeStart("synthetic-a", RecordingProfile.PERSONAL))
        assertEquals(RecordingStatus.ACTIVE, store.read()?.status)
        assertFalse(fresh.reconcileBeforeStart("synthetic-b", RecordingProfile.PERSONAL))
        assertEquals(1, ended)
        assertEquals(RecordingStatus.INTERRUPTED, store.read()?.status)
        fresh.clearCompleted()
        fresh.start("synthetic-b", RecordingProfile.PERSONAL)
        assertEquals(2, started)
        assertEquals("synthetic-b", store.read()?.sessionId)
        fresh.detach()
        store.clear()
    }

    @Test fun competingExerciseIsNotSuperseded() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(context).grantPermissions("android.permission.BODY_SENSORS")
        val store = RecordingStore(context)
        store.clear()
        val client = Proxy.newProxyInstance(ExerciseClient::class.java.classLoader, arrayOf(ExerciseClient::class.java)) { _, method, _ ->
            when (method.name) {
                "getCapabilitiesAsync" -> Futures.immediateFuture(ExerciseCapabilities(mapOf(
                    ExerciseType.STRENGTH_TRAINING to ExerciseTypeCapabilities(setOf(DataType.HEART_RATE_BPM), emptyMap(), emptyMap(), false))))
                "getCurrentExerciseInfoAsync" -> Futures.immediateFuture(ExerciseInfo(ExerciseTrackedStatus.OTHER_APP_IN_PROGRESS, ExerciseType.STRENGTH_TRAINING))
                else -> error("Must not call ${method.name}")
            }
        } as ExerciseClient
        try { WearHeartRecorder(context, client, store).start("synthetic", RecordingProfile.PERSONAL); fail("Expected conflict") }
        catch (_: IllegalStateException) { }
        assertNull(store.read())
    }
}
