package de.baschek.gymapp.sync

import android.os.Build
import de.baschek.gymapp.BuildConfig
import de.baschek.gymapp.core.api.BaseUrl
import de.baschek.gymapp.core.api.InvalidBaseUrlException
import de.baschek.gymapp.core.api.KtorSyncApi
import de.baschek.gymapp.core.api.RequestSigner
import de.baschek.gymapp.data.DeviceKeys
import java.util.UUID
import de.baschek.gymapp.core.api.SyncApiException
import de.baschek.gymapp.core.sync.SyncEngine
import de.baschek.gymapp.core.sync.SyncReport
import de.baschek.gymapp.core.sync.TelemetrySync
import de.baschek.gymapp.data.GymRepository
import de.baschek.gymapp.data.SecureStore
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

enum class FailureKind { UNAUTHORIZED, DISCONNECTED, NETWORK, SERVER, OTHER }

sealed interface SyncState {
    data object Idle : SyncState
    data object Running : SyncState
    data class Failed(val kind: FailureKind) : SyncState
}

enum class SyncOutcome { SUCCESS, NOT_PAIRED, UNAUTHORIZED, DISCONNECTED, BACKING_OFF, RETRY, ALREADY_RUNNING }

sealed interface PairResult {
    data object Success : PairResult
    data object InvalidUrl : PairResult
    data object InvalidCode : PairResult
    data object RateLimited : PairResult
    data object Unavailable : PairResult
    data object Network : PairResult
    data object Other : PairResult
}

/** Runs syncs (one at a time), keeps the UI state and handles pairing. */
class SyncManager(
    private val repository: GymRepository,
    private val secure: SecureStore,
    private val httpClient: HttpClient,
    private val scheduler: SyncScheduler,
    private val deviceKeys: DeviceKeys,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val api = KtorSyncApi(
        httpClient, { secure.baseUrl() }, { secure.token() },
        { secure.keyAlias()?.let(deviceKeys::load)?.let { RequestSigner(it) } },
    )
    private val engine = SyncEngine(api, repository.syncStore)
    private val telemetry = TelemetrySync(api, repository.telemetryStore)
    private val mutex = Mutex()

    /** Called after assistant adjustments changed the running session (the one-step undo of the training screen is then stale). */
    @Volatile var onAdjustmentsResolved: (() -> Unit)? = null

    private val _state = MutableStateFlow<SyncState>(SyncState.Idle)
    val state: StateFlow<SyncState> = _state.asStateFlow()

    /**
     * @param manual true for the user's "sync now"; automatic runs respect the reconnect backoff
     * that applies after the server stopped recognising this device.
     */
    suspend fun runSync(manual: Boolean = false): SyncOutcome {
        if (!secure.paired.value) return SyncOutcome.NOT_PAIRED
        if (!manual) {
            val next = repository.nextReconnectAttemptAt()
            if (next != null && clock() < next) return SyncOutcome.BACKING_OFF
        }
        if (!mutex.tryLock()) return SyncOutcome.ALREADY_RUNNING
        _state.value = SyncState.Running
        try {
            var report = engine.sync()
            // Adjustments from the assistant (contract amendment D) are applied to the running session and
            // answered right away, so the server sees applied/rejected within the same run.
            if (repository.applyPendingAdjustments() > 0) {
                onAdjustmentsResolved?.invoke()
                val answer = engine.sync()
                report = report.copy(notices = report.notices + answer.notices)
            }
            // Telemetry is best effort and must not fail the sync.
            try {
                telemetry.upload()
            } catch (e: CancellationException) {
                throw e
            } catch (e: SyncApiException.Unauthorized) {
                throw e
            } catch (e: Exception) {
                // keep batch for the next run
            }
            repository.recordSyncSuccess(noticesOf(report))
            _state.value = SyncState.Idle
            return SyncOutcome.SUCCESS
        } catch (e: CancellationException) {
            _state.value = SyncState.Idle
            throw e
        } catch (e: SyncApiException) {
            return when (e) {
                is SyncApiException.NotFound -> {
                    // A server that never answered this pairing is just failing; a device that
                    // synced before and is now unknown was revoked, replaced or expired.
                    if (repository.hasSyncedBefore()) {
                        repository.recordConnectionLost(clock())
                        _state.value = SyncState.Failed(FailureKind.DISCONNECTED)
                        SyncOutcome.DISCONNECTED
                    } else fail(FailureKind.OTHER)
                }
                is SyncApiException.Unauthorized -> {
                    repository.recordAuthError()
                    _state.value = SyncState.Failed(FailureKind.UNAUTHORIZED)
                    SyncOutcome.UNAUTHORIZED
                }
                is SyncApiException.Network, is SyncApiException.RateLimited -> fail(FailureKind.NETWORK)
                is SyncApiException.Http -> fail(FailureKind.SERVER)
                else -> fail(FailureKind.OTHER)
            }
        } catch (e: Exception) {
            return fail(FailureKind.OTHER)
        } finally {
            mutex.unlock()
        }
    }

    private fun fail(kind: FailureKind): SyncOutcome {
        _state.value = SyncState.Failed(kind)
        return SyncOutcome.RETRY
    }

    private fun noticesOf(report: SyncReport): List<String> =
        report.notices.map { "${it.kind}:${it.entity}:${it.id}" }

    suspend fun pair(urlInput: String, code: String): PairResult {
        val base = try {
            BaseUrl.normalize(urlInput, allowInsecureLocal = BuildConfig.DEBUG)
        } catch (e: InvalidBaseUrlException) {
            return PairResult.InvalidUrl
        }
        // A fresh key per pairing: the old pairing keeps working until the new one succeeded.
        val alias = "gymapp_device_key_" + UUID.randomUUID()
        return try {
            val key = deviceKeys.generate(alias)
            val response = KtorSyncApi.pair(httpClient, base, code, Build.MODEL ?: "Android", key.publicKeySpkiBase64Url())
            val oldAlias = secure.keyAlias()
            secure.save(base, response.token, alias)
            oldAlias?.let(deviceKeys::delete)
            repository.resetSyncState()
            scheduler.requestSync()
            PairResult.Success
        } catch (e: CancellationException) {
            deviceKeys.delete(alias)
            throw e
        } catch (e: Exception) {
            deviceKeys.delete(alias)
            when (e) {
                is SyncApiException.InvalidCode -> PairResult.InvalidCode
                is SyncApiException.PairingUnavailable -> PairResult.Unavailable
                is SyncApiException.RateLimited -> PairResult.RateLimited
                is SyncApiException.Network -> PairResult.Network
                else -> PairResult.Other
            }
        }
    }

    /** Forgets the server: deletes the token and the device key. Local data stays on the device. */
    suspend fun unpair() {
        val alias = secure.keyAlias()
        secure.clear()
        alias?.let(deviceKeys::delete)
        repository.clearConnectionLost()
        _state.value = SyncState.Idle
    }
}
