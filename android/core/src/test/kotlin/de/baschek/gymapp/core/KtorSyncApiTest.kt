package de.baschek.gymapp.core

import de.baschek.gymapp.core.api.KtorSyncApi
import de.baschek.gymapp.core.api.RequestSigner
import de.baschek.gymapp.core.api.SignedHeaders
import de.baschek.gymapp.core.api.SyncApiException
import de.baschek.gymapp.core.api.createHttpClient
import de.baschek.gymapp.core.model.PairResponse
import de.baschek.gymapp.core.model.PushOp
import de.baschek.gymapp.core.model.TelemetryCount
import de.baschek.gymapp.core.update.UpdateChecker
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KtorSyncApiTest {
    private fun bodyText(r: HttpRequestData) = (r.body as ByteArrayContent).bytes().decodeToString()

    private val key = signingKey()
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val base = "https://sync.example.org"

    private fun api(handler: io.ktor.client.engine.mock.MockRequestHandleScope.(HttpRequestData) -> io.ktor.client.request.HttpResponseData?, token: String? = "tok-123"): Pair<KtorSyncApi, MutableList<HttpRequestData>> {
        val seen = mutableListOf<HttpRequestData>()
        val engine = MockEngine { req -> seen += req; this.handler(req) ?: respondError(HttpStatusCode.InternalServerError) }
        return KtorSyncApi(createHttpClient(engine), { base }, { token }, { RequestSigner(key, { 1_700_000_000L }, { "fixed-nonce" }) }) to seen
    }

    @Test
    fun pairSendsNoAuthHeaderAndReturnsToken() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val engine = MockEngine { req -> seen += req; respond("""{"token":"secret-token","device_id":"d1"}""", HttpStatusCode.OK, json) }
        val r = KtorSyncApi.pair(createHttpClient(engine), base, " 123456 ", "Test device", key.publicKeySpkiBase64Url())
        assertEquals("secret-token", r.token)
        assertNull(seen.single().headers[HttpHeaders.Authorization])
        assertEquals("$base/gym/v1/pair", seen.single().url.toString())
        val body = bodyText(seen.single())
        assertTrue(body.contains("\"code\":\"123456\"") && body.contains("device_name") && body.contains("\"public_key\":\"${key.publicKeySpkiBase64Url()}\""))
        assertFalse(PairResponse("secret-token", "d1").toString().contains("secret-token"))
    }

    @Test
    fun wrongPairingCodeMapsToInvalidCode() = runTest {
        val engine = MockEngine { respond("""{"error":"invalid_code"}""", HttpStatusCode.Unauthorized, json) }
        assertFailsWith<SyncApiException.InvalidCode> { KtorSyncApi.pair(createHttpClient(engine), base, "x", "d", "k") }
        val limited = MockEngine { respond("{}", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "30")) }
        val e = assertFailsWith<SyncApiException.RateLimited> { KtorSyncApi.pair(createHttpClient(limited), base, "x", "d", "k") }
        assertEquals(30, e.retryAfterSeconds)
    }

    @Test
    fun authenticatedCallsCarryBearerAndParseResponses() = runTest {
        val (api, seen) = api({ req ->
            when {
                req.url.encodedPath.endsWith("/changes") -> respond("""{"changes":[{"id":"a","entity":"plan","rev":3,"updated_at":"2026-01-01T00:00:00Z","deleted":false,"origin":"server","data":{"name":"n","x":1}}],"next_since":3,"has_more":false}""", HttpStatusCode.OK, json)
                req.url.encodedPath.endsWith("/status") -> respond("""{"rev":3,"server_time":"t"}""", HttpStatusCode.OK, json)
                else -> null
            }
        })
        val ch = api.changes(2, 9999)
        assertEquals("a", ch.changes.single().id)
        assertEquals("1", ch.changes.single().data["x"].toString())
        assertEquals("since=2&limit=500", seen.single().url.encodedQuery)
        assertEquals("Bearer tok-123", seen.single().headers[HttpHeaders.Authorization])
        assertEquals(3L, api.status().rev)
    }

    @Test
    fun errorStatusesAreMapped() = runTest {
        suspend fun call(status: HttpStatusCode) =
            api({ respond("""{"error":"x"}""", status, json) }).first.status()
        assertFailsWith<SyncApiException.Unauthorized> { call(HttpStatusCode.Unauthorized) }
        assertFailsWith<SyncApiException.ResyncRequired> { call(HttpStatusCode.Gone) }
        val e = assertFailsWith<SyncApiException.Http> { call(HttpStatusCode.InternalServerError) }
        assertEquals(500, e.code)
    }

    @Test
    fun networkFailureAndBadBodyAreDistinguished() = runTest {
        val down = KtorSyncApi(createHttpClient(MockEngine { throw IOException("boom") }), { base }, { "t" }, { RequestSigner(key) })
        assertFailsWith<SyncApiException.Network> { down.status() }
        val garbage = api({ respond("<html>", HttpStatusCode.OK, json) }).first
        assertFailsWith<SyncApiException.BadResponse> { garbage.status() }
    }

    @Test
    fun notConfiguredWithoutBaseUrlOrToken() = runTest {
        val (noToken, seen) = api({ respond("{}", HttpStatusCode.OK, json) }, token = null)
        assertFailsWith<SyncApiException.NotConfigured> { noToken.status() }
        assertTrue(seen.isEmpty())
    }

    @Test
    fun pushAndTelemetryBodies() = runTest {
        val (api, seen) = api({ respond("""{"results":[{"op_id":"o1","status":"applied","document":null}],"rev":4}""", HttpStatusCode.OK, json) })
        val op = PushOp("o1", "plan", "p1", PushOp.UPSERT, null, "2026-01-01T00:00:00Z", buildJsonObject { put("name", "n") })
        assertEquals("applied", api.push(listOf(op)).results.single().status)
        val body = bodyText(seen.single())
        assertTrue(body.contains("\"base_rev\"").not() || body.contains("null"), "null base_rev is omitted or null")
        assertTrue(body.contains("\"client_updated_at\":\"2026-01-01T00:00:00Z\""))

        val (tapi, tseen) = api({ respond("""{"ok":true}""", HttpStatusCode.OK, json) })
        tapi.telemetry("batch-1", listOf(TelemetryCount("2026-01-01", "home", "sync", 3)))
        val tbody = bodyText(tseen.single())
        assertTrue(tbody.contains("\"batch_id\":\"batch-1\"") && tbody.contains("\"count\":3"))
    }

    @Test
    fun updateCheckerReadsReleaseList() = runTest {
        val engine = MockEngine {
            respond(
                """[{"tag_name":"android-v0.2.0","draft":false,"prerelease":false,"assets":[{"name":"gymapp.apk","size":10,"browser_download_url":"https://github.com/baschek/GymApp/releases/download/android-v0.2.0/gymapp.apk"}]}]""",
                HttpStatusCode.OK, json,
            )
        }
        val info = UpdateChecker(createHttpClient(engine)).check("0.1.0")
        assertEquals("0.2.0", info!!.versionName)
        assertNull(UpdateChecker(createHttpClient(engine)).check("0.2.0"))
    }

    @Test
    fun authenticatedRequestsCarryAVerifiableSignature() = runTest {
        val (api, seen) = api({ respond("""{"changes":[],"next_since":2,"has_more":false}""", HttpStatusCode.OK, json) })
        api.changes(2, 500)
        val h = seen.single().headers
        assertEquals("1700000000", h[SignedHeaders.TIMESTAMP])
        assertEquals("fixed-nonce", h[SignedHeaders.NONCE])
        val canonical = "GET\n/gym/v1/changes\nsince=2&limit=500\n1700000000\nfixed-nonce\n" + RequestSigner.sha256Hex(ByteArray(0))
        assertTrue(RequestSigner.verify(key.publicKeySpkiBase64Url(), canonical.toByteArray(), h[SignedHeaders.SIGNATURE]!!))
        assertFalse(RequestSigner.verify(key.publicKeySpkiBase64Url(), canonical.replace("/changes", "/snapshot").toByteArray(), h[SignedHeaders.SIGNATURE]!!))
    }

    @Test
    fun postSignatureCoversTheBodyAndBasePathPrefix() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val engine = MockEngine { r -> seen += r; respond("""{"results":[],"rev":1}""", HttpStatusCode.OK, json) }
        val api = KtorSyncApi(createHttpClient(engine), { "https://sync.example.org/prefix/" }, { "t" }, { RequestSigner(key, { 5L }, { "n" }) })
        api.push(emptyList())
        val body = bodyText(seen.single())
        val canonical = "POST\n/prefix/gym/v1/push\n\n5\nn\n" + RequestSigner.sha256Hex(body.toByteArray())
        assertTrue(RequestSigner.verify(key.publicKeySpkiBase64Url(), canonical.toByteArray(), seen.single().headers[SignedHeaders.SIGNATURE]!!))
    }

    @Test
    fun notFoundIsReportedDistinctlyForPairingAndAuthenticatedCalls() = runTest {
        val e = MockEngine { respond("Not Found", HttpStatusCode.NotFound) }
        assertFailsWith<SyncApiException.PairingUnavailable> { KtorSyncApi.pair(createHttpClient(e), base, "x", "d", "k") }
        val api = KtorSyncApi(createHttpClient(e), { base }, { "t" }, { RequestSigner(key) })
        assertFailsWith<SyncApiException.NotFound> { api.status() }
    }

    @Test
    fun missingSignerMeansNotConfiguredAndNothingIsSent() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val api = KtorSyncApi(createHttpClient(MockEngine { r -> seen += r; respond("{}", HttpStatusCode.OK, json) }), { base }, { "t" }, { null })
        assertFailsWith<SyncApiException.NotConfigured> { api.status() }
        assertTrue(seen.isEmpty())
    }
}
