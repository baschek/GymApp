package de.baschek.gymapp.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface GymDao {
    // documents
    @Query("SELECT * FROM documents WHERE profile = :profile AND entity = :entity AND deleted = 0 ORDER BY sortAt DESC")
    fun observeDocs(profile: String, entity: String): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE profile = :profile AND entity = :entity AND id = :id AND deleted = 0")
    fun observeDoc(profile: String, entity: String, id: String): Flow<DocumentEntity?>

    @Query("SELECT * FROM documents WHERE profile = :profile AND entity = :entity AND gymId = :gymId AND deleted = 0")
    fun observeDocsByGym(profile: String, entity: String, gymId: String): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE profile = :profile AND entity = 'session' AND status = 'active' AND deleted = 0 ORDER BY sortAt DESC LIMIT 1")
    fun observeActiveSession(profile: String): Flow<DocumentEntity?>

    @Query("SELECT * FROM documents WHERE profile = :profile AND entity = :entity AND id = :id")
    suspend fun getDoc(profile: String, entity: String, id: String): DocumentEntity?

    @Query("SELECT * FROM documents WHERE profile = :profile AND entity = :entity AND deleted = 0")
    suspend fun getDocs(profile: String, entity: String): List<DocumentEntity>

    @Query("SELECT * FROM documents WHERE profile = :profile AND entity = :entity AND gymId = :gymId AND deleted = 0")
    suspend fun getDocsByGym(profile: String, entity: String, gymId: String): List<DocumentEntity>

    @Query("SELECT * FROM documents WHERE profile = :profile AND entity = 'session' AND status = 'active' AND deleted = 0 LIMIT 1")
    suspend fun getActiveSession(profile: String): DocumentEntity?

    @Upsert
    suspend fun upsertDoc(doc: DocumentEntity)

    @Upsert
    suspend fun upsertDocs(docs: List<DocumentEntity>)

    @Query("DELETE FROM documents WHERE profile = :profile AND entity = :entity AND id = :id")
    suspend fun deleteDoc(profile: String, entity: String, id: String)

    @Query("UPDATE documents SET rev = :rev WHERE profile = :profile AND entity = :entity AND id = :id")
    suspend fun updateRev(profile: String, entity: String, id: String, rev: Long)

    @Query("SELECT entity, id FROM documents WHERE profile = :profile AND rev IS NOT NULL")
    suspend fun syncedKeys(profile: String): List<DocKeyRow>

    @Query("SELECT COUNT(*) FROM documents WHERE profile = :profile")
    suspend fun countDocs(profile: String): Int

    // outbox
    @Query("SELECT * FROM outbox ORDER BY seq ASC LIMIT :limit")
    suspend fun outbox(limit: Int): List<OutboxEntity>

    @Query("SELECT entity, id FROM outbox")
    suspend fun outboxKeys(): List<DocKeyRow>

    @Query("SELECT * FROM outbox WHERE entity = :entity AND id = :id")
    suspend fun outboxFor(entity: String, id: String): OutboxEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putOutbox(op: OutboxEntity)

    @Query("DELETE FROM outbox WHERE entity = :entity AND id = :id")
    suspend fun deleteOutboxFor(entity: String, id: String)

    @Query("DELETE FROM outbox WHERE seq = :seq AND opId = :opId")
    suspend fun deleteOutboxIfSame(seq: Long, opId: String)

    @Query("UPDATE outbox SET baseRev = :rev WHERE entity = :entity AND id = :id")
    suspend fun rebaseOutbox(entity: String, id: String, rev: Long)

    // settings
    @Query("SELECT value FROM settings WHERE name = :name")
    fun observeSetting(name: String): Flow<String?>

    @Query("SELECT value FROM settings WHERE name = :name")
    suspend fun getSetting(name: String): String?

    @Upsert
    suspend fun putSetting(setting: SettingEntity)

    @Query("DELETE FROM settings WHERE name = :name")
    suspend fun deleteSetting(name: String)

    // telemetry
    @Query("UPDATE telemetry SET hits = hits + 1 WHERE day = :day AND screen = :screen AND element = :element")
    suspend fun bumpTelemetry(day: String, screen: String, element: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTelemetry(row: TelemetryEntity)

    @Query("SELECT * FROM telemetry ORDER BY day, screen, element LIMIT :limit")
    suspend fun telemetryRows(limit: Int): List<TelemetryEntity>

    @Query("UPDATE telemetry SET hits = hits - :sent WHERE day = :day AND screen = :screen AND element = :element")
    suspend fun subtractTelemetry(day: String, screen: String, element: String, sent: Int)

    @Query("DELETE FROM telemetry WHERE hits <= 0")
    suspend fun pruneTelemetry()
}
