package de.baschek.gymapp.health

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import de.baschek.gymapp.core.health.HeartRecording
import de.baschek.gymapp.core.health.RecordingProfile
import de.baschek.gymapp.core.health.SampleQuality
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface HealthAvailability {
    data object Available : HealthAvailability
    data object Unavailable : HealthAvailability
    data object ProviderUpdateRequired : HealthAvailability
}

sealed interface HealthExportResult {
    data object ConsentRequired : HealthExportResult
    data object LocalOnly : HealthExportResult
    data object UnsupportedHistory : HealthExportResult
    data class NotAvailable(val availability: HealthAvailability) : HealthExportResult
    data class PermissionRequired(val missing: Set<String>) : HealthExportResult
    data object RecordingChanged : HealthExportResult
    data object Exported : HealthExportResult
    /** A retry with the exact same recording/version is safe. */
    data object Failed : HealthExportResult
}

/** Phone-only export; callers must obtain explicit consent for each initiated export. */
class HealthConnectExporter(private val context: Context) {
    val requiredPermissions: Set<String> = setOf(
        HealthPermission.getWritePermission(ExerciseSessionRecord::class),
        HealthPermission.getWritePermission(HeartRateRecord::class),
        // Read is restricted below to this app’s own records for export reconciliation.
        HealthPermission.getReadPermission(HeartRateRecord::class),
    )

    fun availability(): HealthAvailability = when (HealthConnectClient.getSdkStatus(context)) {
        HealthConnectClient.SDK_AVAILABLE -> HealthAvailability.Available
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HealthAvailability.ProviderUpdateRequired
        else -> HealthAvailability.Unavailable
    }

    fun supportsBackgroundRead(): Boolean = availability() == HealthAvailability.Available &&
        HealthConnectClient.getOrCreate(context).features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND) ==
        HealthConnectFeatures.FEATURE_STATUS_AVAILABLE

    suspend fun backgroundReadGranted(): Boolean = supportsBackgroundRead() &&
        HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND in HealthConnectClient.getOrCreate(context).permissionController.getGrantedPermissions()

    suspend fun missingPermissions(): Set<String>? {
        if (availability() != HealthAvailability.Available) return null
        return requiredPermissions - HealthConnectClient.getOrCreate(context).permissionController.getGrantedPermissions()
    }

    /** Loads the current local version; expectedVersion protects an out-of-date consent screen. */
    suspend fun export(sessionId: String, profile: RecordingProfile, expectedVersion: Long, userConsented: Boolean, automatic: Boolean = false): HealthExportResult {
        if (profile == RecordingProfile.TEST) return HealthExportResult.LocalOnly
        if (!userConsented) return HealthExportResult.ConsentRequired
        return withContext(Dispatchers.IO) { healthStoreMutex.withLock {
            try {
                if (automatic && !HealthExportConsent(context).enabled()) return@withLock HealthExportResult.ConsentRequired
                val recording = PrivateHeartStore(context).readUnlocked(profile, sessionId)
                    ?: return@withLock HealthExportResult.RecordingChanged
                if (recording.version != expectedVersion) return@withLock HealthExportResult.RecordingChanged
                val available = availability()
                if (available != HealthAvailability.Available) return@withLock HealthExportResult.NotAvailable(available)
                val client = HealthConnectClient.getOrCreate(context)
                val missing = requiredPermissions - client.permissionController.getGrantedPermissions()
                if (missing.isNotEmpty()) return@withLock HealthExportResult.PermissionRequired(missing)
                exportCurrent(context, recording, object : ExportWriter {
                    override suspend fun write(records: List<Record>) { client.insertRecords(records) }
                    override suspend fun ownHeartRecords(start: Long, end: Long): List<OwnedHeart> {
                        val found = mutableListOf<OwnedHeart>()
                        var token: String? = null
                        do {
                            val response = client.readRecords(ReadRecordsRequest(
                                recordType = HeartRateRecord::class,
                                timeRangeFilter = TimeRangeFilter.between(Instant.ofEpochMilli(start), Instant.ofEpochMilli(end).plusMillis(2)),
                                dataOriginFilter = setOf(DataOrigin(context.packageName)), pageToken = token,
                            ))
                            found += response.records.filter { it.metadata.dataOrigin.packageName == context.packageName }
                                .mapNotNull { record -> record.metadata.clientRecordId?.let { OwnedHeart(record.metadata.id, it) } }
                            token = response.pageToken
                        } while (token != null)
                        return found
                    }
                    override suspend fun deleteHeart(ids: List<String>) {
                        client.deleteRecords(HeartRateRecord::class, ids, emptyList())
                    }
                })
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                HealthExportResult.Failed
            }
        } }
    }

}

/** Stable identifiers plus monotonic versions make a repeated insert idempotent. */
internal fun recordsFor(recording: HeartRecording): List<Record> {
    val id = stableId(recording.sessionId)
    val start = Instant.ofEpochMilli(recording.startMillis)
    val end = Instant.ofEpochMilli(recording.endMillis)
    val result = mutableListOf<Record>(ExerciseSessionRecord(
        startTime = start, startZoneOffset = null, endTime = end, endZoneOffset = null,
        metadata = Metadata.unknownRecordingMethod("gymapp-exercise-$id", recording.version),
        exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING,
    ))
    val samples = recording.samples.filter { it.quality == SampleQuality.RELIABLE }
    samples.chunked(1000).forEachIndexed { index, chunk ->
        result += HeartRateRecord(
            startTime = Instant.ofEpochMilli(chunk.first().timeMillis), startZoneOffset = null,
            endTime = Instant.ofEpochMilli(chunk.last().timeMillis).plusMillis(1), endZoneOffset = null,
            samples = chunk.map { HeartRateRecord.Sample(Instant.ofEpochMilli(it.timeMillis), it.bpm.toLong()) },
            metadata = Metadata.activelyRecorded(Device(Device.TYPE_WATCH), "gymapp-heart-$id-$index", recording.version),
        )
    }
    return result
}

private fun stableId(sessionId: String) = UUID.nameUUIDFromBytes(sessionId.toByteArray(Charsets.UTF_8)).toString()

internal data class OwnedHeart(val id: String, val clientId: String)

internal interface ExportWriter {
    suspend fun write(records: List<Record>)
    suspend fun ownHeartRecords(start: Long, end: Long): List<OwnedHeart>
    suspend fun deleteHeart(ids: List<String>)
}

/** Call under healthStoreMutex. The durable attempt fence precedes any external side effect. */
internal suspend fun exportCurrent(
    context: Context, recording: HeartRecording, writer: ExportWriter,
    restrictedHistory: Boolean = android.os.Build.VERSION.SDK_INT <= 33,
    nowMillis: Long = System.currentTimeMillis(),
): HealthExportResult {
    require(recording.profile == RecordingProfile.PERSONAL)
    val directory = File(context.noBackupFilesDir, "heart-export-checkpoints")
    check(directory.exists() || directory.mkdirs())
    val checkpoint = AtomicFile(File(directory, stableId(recording.sessionId)))
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(Json.encodeToString(recording).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    val input = try { checkpoint.openRead() } catch (e: java.io.FileNotFoundException) {
        val base = checkpoint.baseFile
        if (java.nio.file.Files.notExists(base.toPath()) &&
            java.nio.file.Files.notExists(File(base.path + ".bak").toPath())) null else throw e
    }
    val previous = input?.use { stream ->
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(256)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            check(output.size() + count <= 1024)
            output.write(buffer, 0, count)
        }
        output.toString(Charsets.UTF_8.name())
    }
    val previousParts = previous?.split(':')
    if (previousParts != null) {
        check(previousParts.size == 4)
        val version = previousParts[0].toLong()
        val start = previousParts[2].toLong()
        val end = previousParts[3].toLong()
        check(version >= 0 && previousParts[1].matches(Regex("[0-9a-f]{64}")) && start >= 0 && end > start)
        if (recording.version < version || (recording.version == version && digest != previousParts[1]))
            return HealthExportResult.RecordingChanged
    }
    val windowStart = minOf(recording.startMillis, previousParts?.get(2)?.toLong() ?: recording.startMillis)
    val windowEnd = maxOf(recording.endMillis, previousParts?.get(3)?.toLong() ?: recording.endMillis)
    // Android 13 and earlier limit even this app's own records to 30 days before
    // the first permission grant. That grant time is unknown, so use now as a safe bound.
    if (restrictedHistory && windowStart < nowMillis - 30L * 24 * 60 * 60 * 1000)
        return HealthExportResult.UnsupportedHistory
    // Reserve this version and digest before touching Health Connect. A crash cannot
    // allow a stale or different payload to reuse a partially exported version.
    val stream = checkpoint.startWrite()
    try {
        stream.write("${recording.version}:$digest:$windowStart:$windowEnd".toByteArray(Charsets.UTF_8))
        checkpoint.finishWrite(stream)
    } catch (e: Exception) {
        checkpoint.failWrite(stream)
        throw e
    }
    val records = recordsFor(recording)
    records.chunked(10).forEach { writer.write(it) }
    val count = records.size - 1
    val id = stableId(recording.sessionId)
    val obsolete = ((count until 100).map { "gymapp-heart-$id-$it" } + "gymapp-heart-$id").toSet()
    // Query only our own origin, then delete only exact obsolete identifiers that exist.
    val ids = writer.ownHeartRecords(windowStart, windowEnd)
        .filter { it.clientId in obsolete }.map { it.id }.distinct()
    ids.chunked(100).forEach { writer.deleteHeart(it) }
    return HealthExportResult.Exported
}
