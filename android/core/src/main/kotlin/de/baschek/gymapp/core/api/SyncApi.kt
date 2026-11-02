package de.baschek.gymapp.core.api

import de.baschek.gymapp.core.model.ChangesResponse
import de.baschek.gymapp.core.model.PushOp
import de.baschek.gymapp.core.model.PushResponse
import de.baschek.gymapp.core.model.SnapshotResponse
import de.baschek.gymapp.core.model.StatusResponse
import de.baschek.gymapp.core.model.TelemetryCount

/** Authenticated part of the sync protocol (see docs/sync-protocol.md). */
interface SyncApi {
    suspend fun status(): StatusResponse
    suspend fun snapshot(): SnapshotResponse
    suspend fun changes(since: Long, limit: Int = 500): ChangesResponse
    suspend fun push(ops: List<PushOp>): PushResponse
    suspend fun telemetry(batchId: String, counts: List<TelemetryCount>)
}

/** Failures of the sync API. Messages never contain tokens or request bodies. */
sealed class SyncApiException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** 401 on an authenticated call: the token is missing, invalid or revoked. */
    class Unauthorized : SyncApiException("unauthorized")

    /** 401 on pairing: wrong or expired code. */
    class InvalidCode : SyncApiException("invalid_code")

    /** 410: the cursor is too old, a full snapshot is required. */
    class ResyncRequired : SyncApiException("resync_required")

    class RateLimited(val retryAfterSeconds: Int?) : SyncApiException("rate_limited")

    class Http(val code: Int, val error: String?) : SyncApiException("http_$code${error?.let { ":$it" } ?: ""}")

    /** Connectivity problem, timeout or TLS failure: worth retrying later. */
    class Network(cause: Throwable) : SyncApiException("network: ${cause::class.simpleName}", cause)

    class BadResponse(detail: String) : SyncApiException("bad_response: $detail")

    class NotConfigured : SyncApiException("not_configured")
}
