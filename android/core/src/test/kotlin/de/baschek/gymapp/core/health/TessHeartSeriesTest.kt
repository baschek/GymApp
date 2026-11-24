package de.baschek.gymapp.core.health

import de.baschek.gymapp.core.api.KtorSyncApi
import de.baschek.gymapp.core.api.RequestSigner
import de.baschek.gymapp.core.api.SignedHeaders
import de.baschek.gymapp.core.api.SyncApi
import de.baschek.gymapp.core.api.SyncApiException
import de.baschek.gymapp.core.api.createHttpClient
import de.baschek.gymapp.core.model.ChangesResponse
import de.baschek.gymapp.core.model.PushOp
import de.baschek.gymapp.core.model.PushResponse
import de.baschek.gymapp.core.model.Session
import de.baschek.gymapp.core.model.SessionStatus
import de.baschek.gymapp.core.model.SnapshotResponse
import de.baschek.gymapp.core.model.StatusResponse
import de.baschek.gymapp.core.model.TelemetryCount
import de.baschek.gymapp.core.signingKey
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.headersOf
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** Same example as the server test `payload()` of the TESS heart-series change (synthetic data only). */
private fun sample() = HeartRecording(
    RecordingProfile.PERSONAL, "session-1", 1000, 9000, 4,
    listOf(HeartSample(1000, 70), HeartSample(2000, 71, SampleQuality.UNRELIABLE), HeartSample(6000, 80)),
    listOf(RecordingGap(3500, 5500)),
)

/** Port of `validate()` in TESS `gym_heart.py`: true when the server would store this body. */
private fun serverAccepts(text: String): Boolean {
    val data = Json.parseToJsonElement(text) as? JsonObject ?: return false
    if (data.keys != setOf("session_id", "version", "start_ms", "end_ms", "samples", "gaps")) return false
    val sid = data.getValue("session_id").jsonPrimitive.content
    if (!Regex("[A-Za-z0-9][A-Za-z0-9._:\\-]{0,127}").matches(sid)) return false
    val version = data.getValue("version").jsonPrimitive.long
    val start = data.getValue("start_ms").jsonPrimitive.long
    val end = data.getValue("end_ms").jsonPrimitive.long
    if (version < 0 || start < 0 || start >= end || end - start > 86_400_000) return false
    val samples = data.getValue("samples").jsonArray
    val gaps = data.getValue("gaps").jsonArray
    if (samples.size > 100_000 || gaps.size > 10_000) return false
    var stamp = start
    val decoded = mutableListOf<Long>()
    samples.forEachIndexed { i, s ->
        val parts = s.jsonArray
        if (parts.size != 3 || parts.any { (it as? JsonPrimitive)?.isString != false }) return false
        val (delta, bpm, quality) = parts.map { it.jsonPrimitive.long }
        if (delta < (if (i == 0) 0 else 1) || bpm !in 1..300 || quality !in 0..1) return false
        stamp += delta
        if (stamp > end) return false
        decoded += stamp
    }
    var lastEnd = 0L
    val pairs = gaps.map { g ->
        val (a, b) = g.jsonArray.map { it.jsonPrimitive.long }
        if (a < lastEnd || !(0 <= a && a < b && b <= end - start)) return false
        lastEnd = b
        a to b
    }
    var gi = 0
    for (t in decoded) {
        while (gi < pairs.size && start + pairs[gi].second <= t) gi++
        if (gi < pairs.size && t > start + pairs[gi].first) return false
    }
    return true
}

private class HeartFakeApi : SyncApi {
    val bodies = mutableListOf<String>()
    var failure: (() -> Throwable)? = null
    override suspend fun status() = StatusResponse(1)
    override suspend fun snapshot() = SnapshotResponse(1, emptyList())
    override suspend fun changes(since: Long, limit: Int) = ChangesResponse(emptyList(), 1, false)
    override suspend fun push(ops: List<PushOp>) = PushResponse(emptyList(), 1)
    override suspend fun telemetry(batchId: String, counts: List<TelemetryCount>) = Unit
    override suspend fun heartSeries(body: String) {
        failure?.let { throw it() }
        bodies += body
    }
}

private class FakeSource(
    var on: Boolean = true,
    val recordings: MutableMap<String, HeartRecording> = mutableMapOf(),
    var candidates: List<String> = recordings.keys.toList(),
) : HeartSeriesSource {
    val receipts = mutableMapOf<String, HeartReceipt>()
    var afterRecordingRead: () -> Unit = {}
    override suspend fun allowed() = on
    override suspend fun candidateSessionIds() = candidates
    override suspend fun recording(sessionId: String) = recordings[sessionId].also { afterRecordingRead() }
    override suspend fun receipt(sessionId: String) = receipts[sessionId]
    override suspend fun saveReceipt(sessionId: String, receipt: HeartReceipt) { receipts[sessionId] = receipt }
}

class TessHeartSeriesTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    @Test fun `body is exactly the TESS format with deltas, quality markers and gap offsets`() {
        assertEquals(
            """{"session_id":"session-1","version":4,"start_ms":1000,"end_ms":9000,""" +
                """"samples":[[0,70,0],[1000,71,1],[4000,80,0]],"gaps":[[2500,4500]]}""",
            TessHeartPayload.encode(sample()),
        )
    }

    @Test fun `first delta is relative to start and later ones to the previous point`() {
        val body = Json.parseToJsonElement(TessHeartPayload.encode(sample().copy(
            startMillis = 500, samples = listOf(HeartSample(700, 90), HeartSample(701, 91)), gaps = emptyList()))!!).jsonObject
        assertEquals(listOf(listOf(200, 90, 0), listOf(1, 91, 0)),
            body.getValue("samples").jsonArray.map { s -> s.jsonArray.map { it.jsonPrimitive.int } })
        assertEquals(JsonArray(emptyList()), body.getValue("gaps"))
    }

    @Test fun `encoding is deterministic and accepted by a port of the server validation`() {
        val first = TessHeartPayload.encode(sample())!!
        assertEquals(first, TessHeartPayload.encode(sample().copy()))
        assertTrue(serverAccepts(first))
    }

    @Test fun `gaps stay gaps and no point is invented inside or around them`() {
        val body = Json.parseToJsonElement(TessHeartPayload.encode(sample())!!).jsonObject
        assertEquals(3, body.getValue("samples").jsonArray.size)
        assertEquals(1, body.getValue("gaps").jsonArray.size)
        // A gap that contains a point is already rejected by the recording model, and by the server port.
        assertFalse(serverAccepts(TessHeartPayload.encode(sample())!!.replace("[[2500,4500]]", "[[500,1500]]")))
    }

    @Test fun `largest recording fits the signed 2 MiB route limit`() {
        val samples = (0 until 100_000).map { HeartSample(1_000_000L + it * 800L, 300, SampleQuality.UNRELIABLE) }
        val gaps = (0 until 10_000).map { RecordingGap(1_000_000L + 80_100_000L + it * 2L, 1_000_000L + 80_100_001L + it * 2L) }
        val body = TessHeartPayload.encode(HeartRecording(RecordingProfile.PERSONAL, "s", 1_000_000, 87_400_000, 1, samples, gaps))
        assertNotNull(body)
        assertTrue(body.toByteArray().size < TessHeartPayload.MAX_BODY_BYTES)
        assertTrue(serverAccepts(body))
    }

    @Test fun `test recordings, empty recordings and foreign ids are never encoded`() {
        assertNull(TessHeartPayload.encode(sample().copy(profile = RecordingProfile.TEST)))
        assertNull(TessHeartPayload.encode(sample().copy(samples = emptyList(), gaps = emptyList())))
        assertNull(TessHeartPayload.encode(sample().copy(sessionId = "../x")))
        assertNull(TessHeartPayload.encode(sample().copy(sessionId = "a".repeat(129))))
    }

    @Test fun `only completed sessions started after the consent are eligible`() {
        val done = Session(status = SessionStatus.COMPLETED, startedAt = "2026-11-24T10:00:00Z")
        val since = java.time.Instant.parse("2026-11-24T10:00:00Z").toEpochMilli()
        assertTrue(TessHeartPolicy.eligible(done, true, since))
        assertFalse(TessHeartPolicy.eligible(done, true, since + 1))                       // started before the switch
        assertFalse(TessHeartPolicy.eligible(done, false, 0))                              // switch off
        assertFalse(TessHeartPolicy.eligible(done.copy(status = SessionStatus.ACTIVE), true, 0))
        assertFalse(TessHeartPolicy.eligible(done.copy(startedAt = "kaputt"), true, 0))
        assertFalse(TessHeartPolicy.eligible(done, true, Long.MAX_VALUE))                  // never activated
    }

    @Test fun `switch off sends nothing`() = runTest {
        val api = HeartFakeApi()
        val source = FakeSource(on = false, recordings = mutableMapOf("session-1" to sample()))
        assertEquals(HeartUploadReport(), HeartSeriesSync(api, source).upload())
        assertTrue(api.bodies.isEmpty() && source.receipts.isEmpty())
    }

    @Test fun `test profile recordings are not sent even if offered`() = runTest {
        val api = HeartFakeApi()
        val source = FakeSource(recordings = mutableMapOf("session-1" to sample().copy(profile = RecordingProfile.TEST)))
        HeartSeriesSync(api, source).upload()
        assertTrue(api.bodies.isEmpty())
    }

    @Test fun `sent once per version, a higher version is sent again`() = runTest {
        val api = HeartFakeApi()
        val source = FakeSource(recordings = mutableMapOf("session-1" to sample()))
        val sync = HeartSeriesSync(api, source)
        assertEquals(1, sync.upload().sent)
        assertEquals(0, sync.upload().sent)
        assertEquals(1, api.bodies.size)
        source.recordings["session-1"] = sample().copy(version = 5, samples = sample().samples + HeartSample(7000, 82))
        assertEquals(1, sync.upload().sent)
        assertEquals(2, api.bodies.size)
        assertEquals(HeartReceipt(5, true), source.receipts["session-1"])
    }

    @Test fun `a failed attempt is retried with the byte-identical body`() = runTest {
        val api = HeartFakeApi()
        val source = FakeSource(recordings = mutableMapOf("session-1" to sample()))
        val sync = HeartSeriesSync(api, source)
        api.failure = { SyncApiException.Network(IOException("offline")) }
        assertTrue(sync.upload().retrySoon)
        assertNull(source.receipts["session-1"])
        api.failure = { SyncApiException.Http(500, "server_error") }
        assertTrue(sync.upload().retrySoon)
        api.failure = { SyncApiException.RateLimited(30) }
        assertTrue(sync.upload().retrySoon)
        api.failure = null
        assertEquals(1, sync.upload().sent)
        // A lost response is repeated: the server answers "unchanged" only for the same bytes.
        source.receipts.clear()
        sync.upload()
        assertEquals(api.bodies[0], api.bodies[1])
    }

    @Test fun `rejected version is not retried until the version changes`() = runTest {
        val api = HeartFakeApi()
        val source = FakeSource(recordings = mutableMapOf("session-1" to sample()))
        val sync = HeartSeriesSync(api, source)
        api.failure = { SyncApiException.Http(400, "invalid_request") }
        assertFalse(sync.upload().retrySoon)
        assertEquals(HeartReceipt(4, false), source.receipts["session-1"])
        api.failure = null
        assertEquals(0, sync.upload().sent)
        source.recordings["session-1"] = sample().copy(version = 5)
        assertEquals(1, sync.upload().sent)
    }

    @Test fun `unknown route or device waits for the regular sync without a receipt`() = runTest {
        val api = HeartFakeApi()
        val source = FakeSource(recordings = mutableMapOf("session-1" to sample()))
        api.failure = { SyncApiException.NotFound() }
        val report = HeartSeriesSync(api, source).upload()
        assertTrue(report.blocked && !report.retrySoon)
        assertTrue(source.receipts.isEmpty())
    }

    @Test fun `switching off in the middle of a run stops further uploads`() = runTest {
        val api = HeartFakeApi()
        val source = FakeSource(recordings = mutableMapOf("a" to sample().copy(sessionId = "a"), "b" to sample().copy(sessionId = "b")))
        source.afterRecordingRead = { if (api.bodies.isNotEmpty()) source.on = false }
        HeartSeriesSync(api, source).upload()
        assertEquals(1, api.bodies.size)
    }

    @Test fun `sessions without a recording or with an empty one are skipped`() = runTest {
        val api = HeartFakeApi()
        val source = FakeSource(
            recordings = mutableMapOf("empty" to sample().copy(sessionId = "empty", samples = emptyList(), gaps = emptyList(), version = 0)),
            candidates = listOf("missing", "empty"),
        )
        assertEquals(0, HeartSeriesSync(api, source).upload().sent)
        assertTrue(api.bodies.isEmpty())
    }

    @Test fun `request goes to the heart route with bearer and a signature over the exact body`() = runTest {
        val key = signingKey()
        val seen = mutableListOf<HttpRequestData>()
        val engine = MockEngine { r -> seen += r; respond("""{"status":"stored"}""", HttpStatusCode.OK, json) }
        val api = KtorSyncApi(createHttpClient(engine), { "https://sync.example.org" }, { "tok" }, { RequestSigner(key, { 7L }, { "nonce-1" }) })
        val body = TessHeartPayload.encode(sample())!!
        api.heartSeries(body)
        val request = seen.single()
        assertEquals("POST", request.method.value)
        assertEquals("/gym/v1/heart", request.url.encodedPath)
        assertEquals("Bearer tok", request.headers[HttpHeaders.Authorization])
        val sent = (request.body as ByteArrayContent).bytes().decodeToString()
        assertEquals(body, sent)
        val canonical = "POST\n/gym/v1/heart\n\n7\nnonce-1\n" + RequestSigner.sha256Hex(sent.toByteArray())
        assertTrue(RequestSigner.verify(key.publicKeySpkiBase64Url(), canonical.toByteArray(), request.headers[SignedHeaders.SIGNATURE]!!))
    }

    @Test fun `http 400 and 404 map to distinct exceptions`() = runTest {
        val key = signingKey()
        fun api(status: HttpStatusCode) = KtorSyncApi(createHttpClient(MockEngine { respond("""{"error":"invalid_request"}""", status, json) }),
            { "https://sync.example.org" }, { "t" }, { RequestSigner(key) })
        assertEquals(400, assertFailsWith<SyncApiException.Http> { api(HttpStatusCode.BadRequest).heartSeries("{}") }.code)
        assertFailsWith<SyncApiException.NotFound> { api(HttpStatusCode.NotFound).heartSeries("{}") }
    }
}
