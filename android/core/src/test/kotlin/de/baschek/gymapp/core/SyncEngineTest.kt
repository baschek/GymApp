package de.baschek.gymapp.core

import de.baschek.gymapp.core.api.SyncApi
import de.baschek.gymapp.core.api.SyncApiException
import de.baschek.gymapp.core.model.ChangesResponse
import de.baschek.gymapp.core.model.DocKey
import de.baschek.gymapp.core.model.Document
import de.baschek.gymapp.core.model.PushOp
import de.baschek.gymapp.core.model.PushOpResult
import de.baschek.gymapp.core.model.PushResponse
import de.baschek.gymapp.core.model.SnapshotResponse
import de.baschek.gymapp.core.model.StatusResponse
import de.baschek.gymapp.core.model.TelemetryCount
import de.baschek.gymapp.core.sync.OutboxOp
import de.baschek.gymapp.core.sync.SyncEngine
import de.baschek.gymapp.core.sync.SyncNotice
import de.baschek.gymapp.core.sync.SyncStore
import de.baschek.gymapp.core.sync.TelemetryBatch
import de.baschek.gymapp.core.sync.TelemetryStore
import de.baschek.gymapp.core.sync.TelemetrySync
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun doc(id: String, rev: Long, name: String = "n", deleted: Boolean = false, entity: String = "plan") =
    Document(id, entity, rev, "2026-01-01T00:00:00Z", deleted, "server", buildJsonObject { put("name", name) })

private class FakeStore : SyncStore {
    val docs = linkedMapOf<DocKey, Document>()
    val outbox = mutableListOf<OutboxOp>()
    val rejected = mutableListOf<String?>()
    var cursor: Long? = null

    fun enqueue(id: String, baseRev: Long? = null, op: String = PushOp.UPSERT, name: String = "local") {
        outbox += OutboxOp((outbox.size + 1).toLong(), "op-$id", "plan", id, op, baseRev, "2026-02-01T00:00:00Z", buildJsonObject { put("name", name) })
    }

    override suspend fun pendingOps(limit: Int) = outbox.sortedBy { it.seq }.take(limit)
    override suspend fun pendingKeys() = outbox.map { it.key }.toSet()
    override suspend fun acknowledge(op: OutboxOp, serverDoc: Document?) {
        outbox.remove(op)
        if (serverDoc != null) docs[serverDoc.key] = serverDoc
    }
    override suspend fun resolveConflict(op: OutboxOp, serverDoc: Document?) {
        outbox.remove(op)
        if (serverDoc != null) docs[serverDoc.key] = serverDoc
    }
    override suspend fun reject(op: OutboxOp, reason: String?) {
        outbox.remove(op)
        rejected += reason
    }
    override suspend fun cursor() = cursor
    override suspend fun setCursor(rev: Long) { cursor = rev }
    override suspend fun applyRemote(docs: List<Document>) {
        for (d in docs) {
            val cur = this.docs[d.key]
            if (cur == null || d.rev > cur.rev) this.docs[d.key] = d
        }
    }
    override suspend fun applySnapshot(docs: List<Document>, rev: Long, keep: Set<DocKey>) {
        val keys = docs.map { it.key }.toSet()
        this.docs.keys.filter { it !in keys && it !in keep }.forEach { this.docs.remove(it) }
        docs.filter { it.key !in keep }.forEach { this.docs[it.key] = it }
        cursor = rev
    }
}

private class FakeApi : SyncApi {
    var serverRev = 10L
    val pushCalls = mutableListOf<List<PushOp>>()
    var pushHandler: (PushOp) -> PushOpResult = { PushOpResult(it.opId, PushOpResult.APPLIED, doc(it.id, ++serverRev)) }
    var snapshotDocs = listOf<Document>()
    var pages = ArrayDeque<ChangesResponse>()
    var changesCalls = mutableListOf<Long>()
    var changesError: SyncApiException? = null
    var statusCalls = 0
    var failPush: SyncApiException? = null

    override suspend fun status(): StatusResponse { statusCalls++; return StatusResponse(serverRev) }
    override suspend fun snapshot() = SnapshotResponse(serverRev, snapshotDocs)
    override suspend fun changes(since: Long, limit: Int): ChangesResponse {
        changesCalls += since
        changesError?.let { changesError = null; throw it }
        return pages.removeFirst()
    }
    override suspend fun push(ops: List<PushOp>): PushResponse {
        failPush?.let { throw it }
        pushCalls += ops
        return PushResponse(ops.map(pushHandler), serverRev)
    }
    override suspend fun telemetry(batchId: String, counts: List<TelemetryCount>) = Unit
}

class SyncEngineTest {
    @Test
    fun firstRunPushesThenTakesSnapshotAndKeepsPendingDocs() = runTest {
        val store = FakeStore().apply { enqueue("mine"); docs[DocKey("plan", "stale")] = doc("stale", 3) }
        store.enqueue("late", name = "x")
        val api = FakeApi().apply {
            snapshotDocs = listOf(doc("a", 5), doc("mine", 6, "server-copy"))
            pushHandler = { PushOpResult(it.opId, PushOpResult.APPLIED, null) }
        }
        val report = SyncEngine(api, store).sync()
        assertTrue(report.usedSnapshot)
        assertEquals(2, report.applied)
        assertNull(store.docs[DocKey("plan", "stale")])
        assertEquals(10L, store.cursor)
        assertEquals(listOf("op-mine", "op-late"), api.pushCalls.single().map { it.opId })
    }

    @Test
    fun conflictReplacesLocalAndRejectedIsDroppedWithNotices() = runTest {
        val store = FakeStore().apply { enqueue("c", baseRev = 1); enqueue("r"); cursor = 10 }
        val api = FakeApi().apply {
            pushHandler = {
                if (it.id == "c") PushOpResult(it.opId, PushOpResult.CONFLICT, doc("c", 11, "winner"))
                else PushOpResult(it.opId, PushOpResult.REJECTED, null, "invalid_field")
            }
            pages.add(ChangesResponse(emptyList(), 11, false))
        }
        val report = SyncEngine(api, store).sync()
        assertEquals(1, report.conflicts)
        assertEquals(1, report.rejected)
        assertEquals(listOf<String?>("invalid_field"), store.rejected)
        assertEquals(setOf(SyncNotice.CONFLICT, SyncNotice.REJECTED), report.notices.map { it.kind }.toSet())
        assertEquals("winner", store.docs[DocKey("plan", "c")]!!.data["name"].toString().trim('"'))
        assertTrue(store.outbox.isEmpty())
    }

    @Test
    fun pullsAllPagesAndStoresCursorPerPage() = runTest {
        val store = FakeStore().apply { cursor = 10 }
        val api = FakeApi().apply {
            serverRev = 14
            pages.add(ChangesResponse(listOf(doc("a", 11), doc("b", 12)), 12, true))
            pages.add(ChangesResponse(listOf(doc("b", 13, deleted = true), doc("c", 14)), 14, false))
        }
        val report = SyncEngine(api, store).sync()
        assertEquals(listOf(10L, 12L), api.changesCalls)
        assertEquals(14L, store.cursor)
        assertEquals(4, report.pulled)
        assertTrue(store.docs[DocKey("plan", "b")]!!.deleted)
    }

    @Test
    fun resyncRequiredFallsBackToSnapshot() = runTest {
        val store = FakeStore().apply { cursor = 2; docs[DocKey("plan", "gone")] = doc("gone", 1) }
        val api = FakeApi().apply {
            serverRev = 50
            changesError = SyncApiException.ResyncRequired()
            snapshotDocs = listOf(doc("fresh", 40))
        }
        val report = SyncEngine(api, store).sync()
        assertTrue(report.usedSnapshot)
        assertEquals(50L, store.cursor)
        assertEquals(setOf(DocKey("plan", "fresh")), store.docs.keys)
    }

    @Test
    fun upToDateCursorSkipsPullAfterCheapStatusCheck() = runTest {
        val store = FakeStore().apply { cursor = 10 }
        val api = FakeApi()
        val report = SyncEngine(api, store).sync()
        assertTrue(report.skippedPull)
        assertEquals(1, api.statusCalls)
        assertTrue(api.changesCalls.isEmpty())
    }

    @Test
    fun remoteChangeDoesNotOverwriteDocumentWithPendingLocalOp() = runTest {
        val store = FakeStore().apply { cursor = 10; enqueue("p", baseRev = 4, name = "local-edit"); docs[DocKey("plan", "p")] = doc("p", 4, "local-edit") }
        val api = FakeApi().apply {
            serverRev = 20
            // push will fail transiently for nothing; instead simulate op added mid-sync by returning applied w/o doc then a new local write
            pushHandler = { PushOpResult(it.opId, PushOpResult.APPLIED, null) }
            pages.add(ChangesResponse(listOf(doc("p", 15, "remote-edit")), 15, false))
        }
        // Local write arriving after the push step must win over the pulled copy.
        val racing = object : SyncStore by store {
            override suspend fun cursor(): Long? {
                if (store.outbox.isEmpty()) store.enqueue("p", baseRev = 4, name = "newer-local")
                return store.cursor
            }
        }
        SyncEngine(api, racing).sync()
        assertEquals("local-edit", store.docs[DocKey("plan", "p")]!!.data["name"].toString().trim('"'))
    }

    @Test
    fun largeOutboxIsSplitIntoBatches() = runTest {
        val store = FakeStore().apply { cursor = 10; repeat(5) { enqueue("d$it") } }
        val api = FakeApi()
        api.pages.add(ChangesResponse(emptyList(), 15, false))
        SyncEngine(api, store, maxOpsPerPush = 2).sync()
        assertEquals(5, api.pushCalls.sumOf { it.size })
    }

    @Test
    fun missingResultAbortsInsteadOfLoopingForever() = runTest {
        val store = FakeStore().apply { cursor = 10; enqueue("x") }
        val api = FakeApi()
        val broken = object : SyncApi by api {
            override suspend fun push(ops: List<PushOp>) = PushResponse(emptyList(), 1)
        }
        assertFailsWith<SyncApiException.BadResponse> { SyncEngine(broken, store).sync() }
        assertEquals(1, store.outbox.size)
    }

    @Test
    fun unauthorizedKeepsOutboxAndCursor() = runTest {
        val store = FakeStore().apply { cursor = 10; enqueue("x") }
        val api = FakeApi().apply { failPush = SyncApiException.Unauthorized() }
        assertFailsWith<SyncApiException.Unauthorized> { SyncEngine(api, store).sync() }
        assertEquals(1, store.outbox.size)
        assertEquals(10L, store.cursor)
    }

    @Test
    fun cursorThatDoesNotAdvanceWithMoreDataIsAnError() = runTest {
        val store = FakeStore().apply { cursor = 10 }
        val api = FakeApi().apply { serverRev = 99; pages.add(ChangesResponse(emptyList(), 10, true)) }
        assertFailsWith<SyncApiException.BadResponse> { SyncEngine(api, store).sync() }
    }

    @Test
    fun telemetryBatchIsCompletedOnlyAfterSuccessfulUpload() = runTest {
        var completed = 0
        val store = object : TelemetryStore {
            override suspend fun nextBatch() = TelemetryBatch("b1", listOf(TelemetryCount("2026-01-01", "home", "sync", 2)))
            override suspend fun completeBatch(batch: TelemetryBatch) { completed++ }
        }
        val failing = object : SyncApi by FakeApi() {
            override suspend fun telemetry(batchId: String, counts: List<TelemetryCount>) = throw SyncApiException.Http(500, null)
        }
        assertFailsWith<SyncApiException.Http> { TelemetrySync(failing, store).upload() }
        assertEquals(0, completed)
        assertTrue(TelemetrySync(FakeApi(), store).upload())
        assertEquals(1, completed)
    }
}
