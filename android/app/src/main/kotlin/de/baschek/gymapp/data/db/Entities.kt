package de.baschek.gymapp.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A contract document. [data] holds the raw JSON of the contract `data` object
 * (so unknown fields are never lost); the remaining columns are derived and
 * only exist for querying. `profile` separates the synced personal data from
 * the local-only test profile.
 */
@Entity(
    tableName = "documents",
    primaryKeys = ["profile", "entity", "id"],
    indices = [
        Index("profile", "entity", "deleted", "sortAt"),
        Index("profile", "entity", "planId"),
        Index("profile", "entity", "gymId"),
        Index("profile", "entity", "status"),
    ],
)
data class DocumentEntity(
    val profile: String,
    val entity: String,
    val id: String,
    /** Server revision; null while the document has never been acknowledged by the server. */
    val rev: Long?,
    @ColumnInfo(name = "updated_at") val updatedAt: String,
    val deleted: Boolean,
    val origin: String,
    val data: String,
    val planId: String?,
    val gymId: String?,
    val exerciseId: String?,
    val status: String?,
    val sortAt: String?,
)

/** Pending local change; at most one per document (a newer edit replaces the older op). */
@Entity(tableName = "outbox", indices = [Index(value = ["entity", "id"], unique = true)])
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val opId: String,
    val entity: String,
    val id: String,
    val op: String,
    val baseRev: Long?,
    val clientUpdatedAt: String,
    val data: String,
)

@Entity(tableName = "settings")
data class SettingEntity(@PrimaryKey val name: String, val value: String)

/** Local UX counters: how often an element on a screen was used per day. */
@Entity(tableName = "telemetry", primaryKeys = ["day", "screen", "element"])
data class TelemetryEntity(val day: String, val screen: String, val element: String, val hits: Int)

data class DocKeyRow(val entity: String, val id: String)
