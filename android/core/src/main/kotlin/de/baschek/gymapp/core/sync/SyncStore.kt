package de.baschek.gymapp.core.sync

import de.baschek.gymapp.core.model.DocKey
import de.baschek.gymapp.core.model.Document
import de.baschek.gymapp.core.model.TelemetryCount
import kotlinx.serialization.json.JsonObject

/** A queued local change. One row per document; later edits replace earlier ones. */
data class OutboxOp(
    val seq: Long,
    val opId: String,
    val entity: String,
    val id: String,
    val op: String,
    val baseRev: Long?,
    val clientUpdatedAt: String,
    val data: JsonObject,
) {
    val key: DocKey get() = DocKey(entity, id)
}

/**
 * Persistence used by [SyncEngine]. Implementations must only ever expose
 * documents of the personal profile: the local-only test profile never shows
 * up in [pendingOps] and is never touched by remote data.
 */
interface SyncStore {
    /** Oldest-first (by seq) pending operations. */
    suspend fun pendingOps(limit: Int): List<OutboxOp>

    suspend fun pendingKeys(): Set<DocKey>

    /** Op was applied. [serverDoc] (if given) becomes the local copy; later ops of the same document are rebased to its rev. */
    suspend fun acknowledge(op: OutboxOp, serverDoc: Document?)

    /** Op lost: replace the local copy by [serverDoc] and drop the op. */
    suspend fun resolveConflict(op: OutboxOp, serverDoc: Document?)

    /** Op was rejected: drop it (keep a trace for diagnostics). */
    suspend fun reject(op: OutboxOp, reason: String?)

    suspend fun cursor(): Long?
    suspend fun setCursor(rev: Long)

    /** Upsert/delete remote documents; documents with a rev not newer than the local one are ignored. */
    suspend fun applyRemote(docs: List<Document>)

    /**
     * Replace the synced state by a snapshot: upsert all [docs], remove local
     * documents that have a server rev but are absent. Documents in [keep]
     * (pending local changes) are left alone. Sets the cursor to [rev].
     */
    suspend fun applySnapshot(docs: List<Document>, rev: Long, keep: Set<DocKey>)
}

data class TelemetryBatch(val batchId: String, val counts: List<TelemetryCount>)

interface TelemetryStore {
    /** Returns the unfinished batch if one exists (same id is reused so resending is idempotent), else a new one, or null if empty. */
    suspend fun nextBatch(): TelemetryBatch?
    suspend fun completeBatch(batch: TelemetryBatch)
}
