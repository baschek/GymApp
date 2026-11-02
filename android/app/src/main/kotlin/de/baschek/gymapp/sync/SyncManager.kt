package de.baschek.gymapp.sync

import android.os.Build
import de.baschek.gymapp.BuildConfig
import de.baschek.gymapp.core.api.BaseUrl
import de.baschek.gymapp.core.api.InvalidBaseUrlException
import de.baschek.gymapp.core.api.KtorSyncApi
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

enum class FailureKind { UNAUTHORIZED, NETWORK, SERVER, OTHER }

sealed interface SyncState {
    data object Idle : SyncState
    data object Running : SyncState
    data class Failed(val kind: FailureKind) : SyncState
}

enum class SyncOutcome { SUCCESS, NOT_PAIRED, UNAUTHORIZED, RETRY, ALREADY_RUNNING }

sealed interface PairResult {
    data object Success : PairResult
    data object InvalidUrl : PairResult
    data object InvalidCode : PairResult
    data object RateLimited : PairResult
    data object Network : PairResult
    data object Other : PairResult
}

/** Runs syncs (one at a time), keeps the UI state and handles pairing. */
class SyncManager(
    private val repository: GymRepository,
    private val secure: SecureStore,
    private val httpClient: HttpClient,
    private val scheduler: SyncScheduler,
) {
    private val api = KtorSyncApi(httpClient, { secure.baseUrl() }, { secure.token() })
    private val engine = SyncEngine(api, repository.syncStore)
    private val telemetry = TelemetrySync(api, repository.telemetryStore)
    private val mutex = Mutex()

    private val _state = MutableStateFlow<SyncState>(SyncState.Idle)
    val state: StateFlow<SyncState> = _state.asStateFlow()

    suspend fun runSync(): SyncOutcome {
        if (!secure.paired.value) return SyncOutcome.NOT_PAIRED
        if (!mutex.tryLock()) return SyncOutcome.ALREADY_RUNNING
        _state.value = SyncState.Running
        try {
            val report = engine.sync()
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
        report.notices.map { "${it.kind}:${it.entity}" }

    suspend fun pair(urlInput: String, code: String): PairResult {
        val base = try {
            BaseUrl.normalize(urlInput, allowInsecureLocal = BuildConfig.DEBUG)
        } catch (e: InvalidBaseUrlException) {
            return PairResult.InvalidUrl
        }
        return try {
            val response = KtorSyncApi.pair(httpClient, base, code, Build.MODEL ?: "Android")
            secure.save(base, response.token)
            repository.resetSyncState()
            scheduler.requestSync()
            PairResult.Success
        } catch (e: CancellationException) {
            throw e
        } catch (e: SyncApiException.InvalidCode) {
            PairResult.InvalidCode
        } catch (e: SyncApiException.RateLimited) {
            PairResult.RateLimited
        } catch (e: SyncApiException.Network) {
            PairResult.Network
        } catch (e: Exception) {
            PairResult.Other
        }
    }

    /** Forgets the server. Local data stays on the device. */
    fun unpair() {
        secure.clear()
        _state.value = SyncState.Idle
    }
}
