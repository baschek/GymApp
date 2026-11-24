package de.baschek.gymapp.core.health

import de.baschek.gymapp.core.api.SyncApi
import de.baschek.gymapp.core.api.SyncApiException
import de.baschek.gymapp.core.model.Session
import de.baschek.gymapp.core.model.SessionStatus
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Wire format of `POST /gym/v1/heart` (server contract "Private Pulskurve"): lossless, compact and deterministic.
 * The server compares a repeated upload of the same version byte-for-byte after parsing, so the key order
 * (session_id, version, start_ms, end_ms, samples, gaps) and the numbers must never vary between attempts.
 */
object TessHeartPayload {
    /** The server rejects larger bodies (and answers with its uniform 404, which would look like a lost device). */
    const val MAX_BODY_BYTES = 2 * 1024 * 1024
    private val ID = Regex("[A-Za-z0-9][A-Za-z0-9._:\\-]{0,127}")

    /** True when the server would accept this recording; false recordings are never sent. */
    fun sendable(recording: HeartRecording): Boolean =
        recording.profile == RecordingProfile.PERSONAL &&
            recording.samples.isNotEmpty() &&
            ID.matches(recording.sessionId) &&
            recording.version < Long.MAX_VALUE

    /** The JSON body, or null when [sendable] is false or the body would exceed [MAX_BODY_BYTES]. */
    fun encode(recording: HeartRecording): String? {
        if (!sendable(recording)) return null
        var previous = recording.startMillis
        val samples = recording.samples.map { sample ->
            val delta = sample.timeMillis - previous
            previous = sample.timeMillis
            JsonArray(listOf(
                JsonPrimitive(delta), JsonPrimitive(sample.bpm),
                JsonPrimitive(if (sample.quality == SampleQuality.RELIABLE) 0 else 1),
            ))
        }
        val gaps = recording.gaps.map {
            JsonArray(listOf(JsonPrimitive(it.startMillis - recording.startMillis), JsonPrimitive(it.endMillis - recording.startMillis)))
        }
        val body = JsonObject(linkedMapOf(
            "session_id" to JsonPrimitive(recording.sessionId),
            "version" to JsonPrimitive(recording.version),
            "start_ms" to JsonPrimitive(recording.startMillis),
            "end_ms" to JsonPrimitive(recording.endMillis),
            "samples" to JsonArray(samples),
            "gaps" to JsonArray(gaps),
        )).toString()
        return if (body.toByteArray(Charsets.UTF_8).size <= MAX_BODY_BYTES) body else null
    }
}

/** Which sessions may leave the phone: only completed ones that were started after the consent. */
object TessHeartPolicy {
    fun eligible(session: Session, enabled: Boolean, enabledSinceMillis: Long): Boolean {
        if (!enabled || session.status != SessionStatus.COMPLETED) return false
        val started = runCatching { Instant.parse(session.startedAt).toEpochMilli() }.getOrNull() ?: return false
        return started >= enabledSinceMillis
    }
}

/** What was done with a version of a recording; kept per session so a version is never sent twice. */
data class HeartReceipt(val version: Long, val accepted: Boolean)

/** Everything the upload needs from the app; the app side owns consent, profile, files and receipts. */
interface HeartSeriesSource {
    /** Switch on and personal profile active. Checked before every single upload. */
    suspend fun allowed(): Boolean
    /** Completed sessions of the active profile that were started after the consent. */
    suspend fun candidateSessionIds(): List<String>
    suspend fun recording(sessionId: String): HeartRecording?
    suspend fun receipt(sessionId: String): HeartReceipt?
    suspend fun saveReceipt(sessionId: String, receipt: HeartReceipt)
}

data class HeartUploadReport(
    val sent: Int = 0,
    /** Network trouble, rate limit or server error: trying again soon makes sense. */
    val retrySoon: Boolean = false,
    /** The server does not know the route or the device (404) or refused the credentials: wait for the next regular sync. */
    val blocked: Boolean = false,
)

/**
 * Uploads the exact heart-rate curve of each finished session once per version. Nothing is queued: the list of open
 * work is derived on every run (recording version versus receipt), so a retry is always safe and idempotent, also on
 * the server side (same version and content = "unchanged"). Pulse values never reach logs or exception messages.
 */
class HeartSeriesSync(private val api: SyncApi, private val source: HeartSeriesSource, private val maxPerRun: Int = 20) {
    suspend fun upload(): HeartUploadReport {
        if (!source.allowed()) return HeartUploadReport()
        var sent = 0
        for (id in source.candidateSessionIds()) {
            if (sent >= maxPerRun) return HeartUploadReport(sent, retrySoon = true)
            val recording = source.recording(id) ?: continue
            // Checked after the (slow) file read and right before sending: a switch-off or profile change wins.
            if (!source.allowed()) break
            if (!TessHeartPayload.sendable(recording) || recording.sessionId != id) continue
            val receipt = source.receipt(id)
            if (receipt != null && receipt.version >= recording.version) continue
            val body = TessHeartPayload.encode(recording) ?: continue
            try {
                api.heartSeries(body)
                source.saveReceipt(id, HeartReceipt(recording.version, accepted = true))
                sent++
            } catch (e: CancellationException) {
                throw e
            } catch (e: SyncApiException.Http) {
                // 400: this exact version is invalid for the server; a changed (higher) version is tried again.
                if (e.code != 400) return HeartUploadReport(sent, retrySoon = true)
                source.saveReceipt(id, HeartReceipt(recording.version, accepted = false))
            } catch (e: SyncApiException.NotFound) {
                return HeartUploadReport(sent, blocked = true)
            } catch (e: SyncApiException.Unauthorized) {
                return HeartUploadReport(sent, blocked = true)
            } catch (e: SyncApiException) {
                return HeartUploadReport(sent, retrySoon = true)
            }
        }
        return HeartUploadReport(sent)
    }
}
