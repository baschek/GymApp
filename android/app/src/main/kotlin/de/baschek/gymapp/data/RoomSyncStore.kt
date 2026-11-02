package de.baschek.gymapp.data

import androidx.room.withTransaction
import de.baschek.gymapp.core.model.DocKey
import de.baschek.gymapp.core.model.Document
import de.baschek.gymapp.core.model.GymJson
import de.baschek.gymapp.core.model.IndexColumns
import de.baschek.gymapp.core.model.TelemetryCount
import de.baschek.gymapp.core.sync.OutboxOp
import de.baschek.gymapp.core.sync.SyncStore
import de.baschek.gymapp.core.sync.TelemetryBatch
import de.baschek.gymapp.core.sync.TelemetryStore
import de.baschek.gymapp.data.db.DocumentEntity
import de.baschek.gymapp.data.db.GymDatabase
import de.baschek.gymapp.data.db.OutboxEntity
import de.baschek.gymapp.data.db.SettingEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

internal fun parseObject(text: String): JsonObject =
    runCatching { GymJson.parseToJsonElement(text) as JsonObject }.getOrDefault(JsonObject(emptyMap()))

internal fun Document.toEntity(profile: String): DocumentEntity {
    val idx = IndexColumns.extract(entity, data)
    return DocumentEntity(
        profile = profile, entity = entity, id = id, rev = rev, updatedAt = updatedAt,
        deleted = deleted, origin = origin, data = if (deleted) "{}" else data.toString(),
        planId = idx.planId, gymId = idx.gymId, exerciseId = idx.exerciseId, status = idx.status, sortAt = idx.sortAt,
    )
}

internal fun OutboxEntity.toOp() = OutboxOp(
    seq = seq, opId = opId, entity = entity, id = id, op = op, baseRev = baseRev,
    clientUpdatedAt = clientUpdatedAt, data = parseObject(data),
)

/**
 * [SyncStore] on top of Room. Part of the repository layer: only
 * [GymRepository] hands it out. It only ever reads and writes the personal profile.
 */
internal class RoomSyncStore(private val db: GymDatabase) : SyncStore {
    private val dao = db.dao()
    private val profile = Profile.PERSONAL

    override suspend fun pendingOps(limit: Int): List<OutboxOp> = dao.outbox(limit).map { it.toOp() }

    override suspend fun pendingKeys(): Set<DocKey> =
        dao.outboxKeys().map { DocKey(it.entity, it.id) }.toSet()

    override suspend fun acknowledge(op: OutboxOp, serverDoc: Document?) = db.withTransaction {
        dao.deleteOutboxIfSame(op.seq, op.opId)
        adopt(op, serverDoc)
    }

    override suspend fun resolveConflict(op: OutboxOp, serverDoc: Document?) = db.withTransaction {
        dao.deleteOutboxIfSame(op.seq, op.opId)
        adopt(op, serverDoc)
    }

    /** Takes over the server copy unless a newer local edit is waiting; then only the base revision moves. */
    private suspend fun adopt(op: OutboxOp, serverDoc: Document?) {
        if (serverDoc == null) return
        if (dao.outboxFor(op.entity, op.id) != null) {
            dao.rebaseOutbox(op.entity, op.id, serverDoc.rev)
            dao.updateRev(profile, op.entity, op.id, serverDoc.rev)
        } else {
            dao.upsertDoc(serverDoc.toEntity(profile))
        }
    }

    override suspend fun reject(op: OutboxOp, reason: String?) {
        dao.deleteOutboxIfSame(op.seq, op.opId)
    }

    override suspend fun cursor(): Long? = dao.getSetting(SettingKeys.SYNC_CURSOR)?.toLongOrNull()

    override suspend fun setCursor(rev: Long) {
        dao.putSetting(SettingEntity(SettingKeys.SYNC_CURSOR, rev.toString()))
    }

    override suspend fun applyRemote(docs: List<Document>) = db.withTransaction {
        for (d in docs) {
            val local = dao.getDoc(profile, d.entity, d.id)
            if (local == null && d.deleted) continue
            if (local == null || (local.rev ?: Long.MIN_VALUE) < d.rev) dao.upsertDoc(d.toEntity(profile))
        }
    }

    override suspend fun applySnapshot(docs: List<Document>, rev: Long, keep: Set<DocKey>) = db.withTransaction {
        val incoming = docs.map { it.key }.toSet()
        for (row in dao.syncedKeys(profile)) {
            val key = DocKey(row.entity, row.id)
            if (key !in incoming && key !in keep) dao.deleteDoc(profile, row.entity, row.id)
        }
        dao.upsertDocs(docs.filter { it.key !in keep }.map { it.toEntity(profile) })
        dao.putSetting(SettingEntity(SettingKeys.SYNC_CURSOR, rev.toString()))
    }
}

@Serializable
private data class StoredBatch(val batchId: String, val counts: List<TelemetryCount>)

internal class RoomTelemetryStore(
    private val db: GymDatabase,
    private val newId: () -> String,
) : TelemetryStore {
    private val dao = db.dao()

    override suspend fun nextBatch(): TelemetryBatch? = db.withTransaction {
        dao.getSetting(SettingKeys.TELEMETRY_BATCH)?.let { text ->
            runCatching { GymJson.decodeFromString(StoredBatch.serializer(), text) }.getOrNull()
                ?.let { return@withTransaction TelemetryBatch(it.batchId, it.counts) }
        }
        val rows = dao.telemetryRows(1000)
        if (rows.isEmpty()) return@withTransaction null
        val counts = rows.map { TelemetryCount(it.day, it.screen, it.element, it.hits) }
        val batch = TelemetryBatch(newId(), counts)
        dao.putSetting(
            SettingEntity(SettingKeys.TELEMETRY_BATCH, GymJson.encodeToString(StoredBatch.serializer(), StoredBatch(batch.batchId, counts))),
        )
        batch
    }

    override suspend fun completeBatch(batch: TelemetryBatch) = db.withTransaction {
        batch.counts.forEach { dao.subtractTelemetry(it.day, it.screen, it.element, it.count) }
        dao.pruneTelemetry()
        dao.deleteSetting(SettingKeys.TELEMETRY_BATCH)
    }
}

