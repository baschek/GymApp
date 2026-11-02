package de.baschek.gymapp.core.api

import de.baschek.gymapp.core.model.ChangesResponse
import de.baschek.gymapp.core.model.GymJson
import de.baschek.gymapp.core.model.PairRequest
import de.baschek.gymapp.core.model.PairResponse
import de.baschek.gymapp.core.model.PushOp
import de.baschek.gymapp.core.model.PushRequest
import de.baschek.gymapp.core.model.PushResponse
import de.baschek.gymapp.core.model.SnapshotResponse
import de.baschek.gymapp.core.model.StatusResponse
import de.baschek.gymapp.core.model.TelemetryCount
import de.baschek.gymapp.core.model.TelemetryRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Builds the HTTP client. The engine is injected (OkHttp on Android, MockEngine in tests). */
fun createHttpClient(engine: HttpClientEngine): HttpClient = HttpClient(engine) {
    expectSuccess = false
    followRedirects = false
    install(HttpTimeout) {
        connectTimeoutMillis = 15_000
        requestTimeoutMillis = 60_000
        socketTimeoutMillis = 60_000
    }
    // Deliberately no Logging plugin: headers contain the bearer token.
}

/**
 * Ktor implementation of [SyncApi]. Base URL and token are looked up on every
 * call so a new pairing takes effect without rebuilding the client.
 */
class KtorSyncApi(
    private val client: HttpClient,
    private val baseUrl: () -> String?,
    private val token: () -> String?,
) : SyncApi {

    override suspend fun status(): StatusResponse =
        get("status", StatusResponse.serializer())

    override suspend fun snapshot(): SnapshotResponse =
        get("snapshot", SnapshotResponse.serializer())

    override suspend fun changes(since: Long, limit: Int): ChangesResponse =
        get("changes?since=$since&limit=${limit.coerceIn(1, 500)}", ChangesResponse.serializer())

    override suspend fun push(ops: List<PushOp>): PushResponse {
        val body = GymJson.encodeToString(PushRequest.serializer(), PushRequest(ops))
        return send(HttpMethod.Post, "push", body, true, PushResponse.serializer())!!
    }

    override suspend fun telemetry(batchId: String, counts: List<TelemetryCount>) {
        val body = GymJson.encodeToString(TelemetryRequest.serializer(), TelemetryRequest(batchId, counts))
        send<Unit>(HttpMethod.Post, "telemetry", body, true, null)
    }

    private suspend fun <T> get(path: String, serializer: KSerializer<T>): T =
        send(HttpMethod.Get, path, null, true, serializer)!!

    private suspend fun <T> send(
        method: HttpMethod,
        path: String,
        body: String?,
        authenticated: Boolean,
        serializer: KSerializer<T>?,
    ): T? {
        val base = baseUrl() ?: throw SyncApiException.NotConfigured()
        val bearer = if (authenticated) (token() ?: throw SyncApiException.NotConfigured()) else null
        return execute(client, base, method, path, body, bearer, serializer)
    }

    companion object {
        /** Pairing: exchanges a one-time code for a token. No authentication header. */
        suspend fun pair(client: HttpClient, baseUrl: String, code: String, deviceName: String): PairResponse {
            val body = GymJson.encodeToString(PairRequest.serializer(), PairRequest(code.trim(), deviceName))
            return execute(client, baseUrl, HttpMethod.Post, "pair", body, null, PairResponse.serializer(), pairing = true)!!
        }

        private suspend fun <T> execute(
            client: HttpClient,
            base: String,
            method: HttpMethod,
            path: String,
            body: String?,
            bearer: String?,
            serializer: KSerializer<T>?,
            pairing: Boolean = false,
        ): T? {
            val response: HttpResponse
            val text: String
            try {
                response = client.request {
                    this.method = method
                    url("$base/gym/v1/$path")
                    header(HttpHeaders.Accept, "application/json")
                    if (bearer != null) header(HttpHeaders.Authorization, "Bearer $bearer")
                    if (body != null) setBody(TextContent(body, ContentType.Application.Json))
                }
                text = response.bodyAsText()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw SyncApiException.Network(e)
            }

            val code = response.status.value
            if (code in 200..299) {
                if (serializer == null) return null
                try {
                    return GymJson.decodeFromString(serializer, text)
                } catch (e: Exception) {
                    throw SyncApiException.BadResponse("undecodable ${e::class.simpleName}")
                }
            }
            val error = errorCode(text)
            throw when (code) {
                401 -> if (pairing) SyncApiException.InvalidCode() else SyncApiException.Unauthorized()
                410 -> SyncApiException.ResyncRequired()
                429 -> SyncApiException.RateLimited(response.headers[HttpHeaders.RetryAfter]?.toIntOrNull())
                else -> SyncApiException.Http(code, error)
            }
        }

        private fun errorCode(text: String): String? = try {
            ((GymJson.parseToJsonElement(text) as? JsonObject)?.get("error") as? JsonPrimitive)?.contentOrNull
                ?.take(64)
        } catch (e: Exception) {
            null
        }
    }
}
