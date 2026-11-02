package de.baschek.gymapp.core.sync

import de.baschek.gymapp.core.api.SyncApi
import de.baschek.gymapp.core.api.SyncApiException
import de.baschek.gymapp.core.model.PushOp
import de.baschek.gymapp.core.model.PushOpResult

data class SyncNotice(val kind: String, val entity: String, val id: String, val detail: String? = null) {
    companion object {
        const val CONFLICT = "conflict"
        const val REJECTED = "rejected"
    }
}

data class SyncReport(
    val pushed: Int = 0,
    val applied: Int = 0,
    val conflicts: Int = 0,
    val rejected: Int = 0,
    val pulled: Int = 0,
    val usedSnapshot: Boolean = false,
    val skippedPull: Boolean = false,
    val notices: List<SyncNotice> = emptyList(),
)

/**
 * Document sync (push outbox, then pull changes) against [SyncApi]/[SyncStore].
 * Errors surface as [SyncApiException]; progress made before an error is kept.
 */
class SyncEngine(
    private val api: SyncApi,
    private val store: SyncStore,
    private val maxOpsPerPush: Int = 200,
    private val maxPushBytes: Int = 1_800_000,
    private val pageSize: Int = 500,
) {
    suspend fun sync(): SyncReport {
        val notices = mutableListOf<SyncNotice>()
        var pushed = 0
        var applied = 0
        var conflicts = 0
        var rejected = 0

        // 1. Push outbox until empty.
        var guard = 0
        while (true) {
            val ops = selectBatch(store.pendingOps(maxOpsPerPush))
            if (ops.isEmpty()) break
            if (++guard > 10_000) throw SyncApiException.BadResponse("push loop")
            val response = api.push(ops.map { it.toWire() })
            val byId = response.results.associateBy { it.opId }
            for (op in ops) {
                val result = byId[op.opId] ?: throw SyncApiException.BadResponse("missing result")
                pushed++
                when (result.status) {
                    PushOpResult.APPLIED -> {
                        store.acknowledge(op, result.document)
                        applied++
                    }
                    PushOpResult.CONFLICT -> {
                        store.resolveConflict(op, result.document)
                        conflicts++
                        notices += SyncNotice(SyncNotice.CONFLICT, op.entity, op.id)
                    }
                    PushOpResult.REJECTED -> {
                        store.reject(op, result.reason)
                        rejected++
                        notices += SyncNotice(SyncNotice.REJECTED, op.entity, op.id, result.reason)
                    }
                    else -> throw SyncApiException.BadResponse("unknown status")
                }
            }
        }

        // 2. Pull.
        val cursor = store.cursor()
        var pulled = 0
        var usedSnapshot = false
        var skipped = false
        if (cursor == null) {
            pulled += pullSnapshot()
            usedSnapshot = true
        } else if (pushed == 0 && api.status().rev <= cursor) {
            skipped = true
        } else {
            var since: Long = cursor
            while (true) {
                val page = try {
                    api.changes(since, pageSize)
                } catch (e: SyncApiException.ResyncRequired) {
                    pulled += pullSnapshot()
                    usedSnapshot = true
                    break
                }
                val keep = store.pendingKeys()
                val docs = page.changes.filter { it.key !in keep }
                if (docs.isNotEmpty()) store.applyRemote(docs)
                pulled += page.changes.size
                if (page.nextSince > since) {
                    store.setCursor(page.nextSince)
                } else if (page.hasMore) {
                    throw SyncApiException.BadResponse("cursor did not advance")
                }
                if (!page.hasMore) break
                since = page.nextSince
            }
        }
        return SyncReport(pushed, applied, conflicts, rejected, pulled, usedSnapshot, skipped, notices)
    }

    private suspend fun pullSnapshot(): Int {
        val snapshot = api.snapshot()
        store.applySnapshot(snapshot.documents, snapshot.rev, store.pendingKeys())
        return snapshot.documents.size
    }

    /** Keeps the request below the size limit; always at least one op so progress is possible. */
    private fun selectBatch(ops: List<OutboxOp>): List<OutboxOp> {
        val out = mutableListOf<OutboxOp>()
        var bytes = 0
        for (op in ops) {
            val size = op.data.toString().toByteArray(Charsets.UTF_8).size + 300
            if (out.isNotEmpty() && bytes + size > maxPushBytes) break
            out += op
            bytes += size
        }
        return out
    }

    private fun OutboxOp.toWire() = PushOp(
        opId = opId, entity = entity, id = id, op = op,
        baseRev = baseRev, clientUpdatedAt = clientUpdatedAt, data = data,
    )
}

/** Uploads local UX counters. Failures leave the batch in place for the next attempt. */
class TelemetrySync(private val api: SyncApi, private val store: TelemetryStore, private val maxRows: Int = 1000) {
    suspend fun upload(): Boolean {
        val batch = store.nextBatch() ?: return false
        require(batch.counts.size <= maxRows) { "batch too large" }
        api.telemetry(batch.batchId, batch.counts)
        store.completeBatch(batch)
        return true
    }
}
