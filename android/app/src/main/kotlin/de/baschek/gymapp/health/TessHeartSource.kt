package de.baschek.gymapp.health

import android.content.Context
import de.baschek.gymapp.core.health.HeartReceipt
import de.baschek.gymapp.core.health.HeartRecording
import de.baschek.gymapp.core.health.HeartSeriesSource
import de.baschek.gymapp.core.health.RecordingProfile
import de.baschek.gymapp.core.health.TessHeartPolicy
import de.baschek.gymapp.data.GymRepository
import de.baschek.gymapp.data.Profile
import java.security.MessageDigest
import kotlinx.coroutines.flow.first

/**
 * Own switch "Pulskurve an TESS" (device only, off by default, not synced, excluded from backups). The time of
 * activation limits the upload to trainings that were started afterwards; old data is never sent.
 */
class TessHeartConsent(context: Context) {
    private val prefs = context.getSharedPreferences("tess_heart_consent", Context.MODE_PRIVATE)
    fun enabled(): Boolean = prefs.getBoolean("enabled", false)
    fun enabledSince(): Long = prefs.getLong("since", Long.MAX_VALUE)
    fun setEnabled(enabled: Boolean, nowMillis: Long = System.currentTimeMillis()) {
        val edit = prefs.edit().putBoolean("enabled", enabled)
        // Each new activation starts a new window; trainings of an off period are never sent later.
        if (enabled && !enabled()) edit.putLong("since", nowMillis)
        edit.apply()
    }
}

/** App side of [de.baschek.gymapp.core.health.HeartSeriesSync]: consent, profile, private recordings, receipts. */
class TessHeartSource(
    context: Context,
    private val repository: GymRepository,
) : HeartSeriesSource {
    private val appContext = context.applicationContext
    private val consent = TessHeartConsent(appContext)
    private val store = PrivateHeartStore(appContext)
    // Receipts hold only a version number per hashed session id, never measurements.
    private val prefs = appContext.getSharedPreferences("tess_heart_receipts", Context.MODE_PRIVATE)

    override suspend fun allowed(): Boolean = consent.enabled() && repository.currentProfile() == Profile.PERSONAL

    override suspend fun candidateSessionIds(): List<String> {
        if (!allowed()) return emptyList()
        val since = consent.enabledSince()
        return repository.observeCompletedSessions().first()
            .filter { TessHeartPolicy.eligible(it.value, true, since) }
            .map { it.id }
    }

    override suspend fun recording(sessionId: String): HeartRecording? =
        runCatching { store.read(RecordingProfile.PERSONAL, sessionId) }.getOrNull()

    override suspend fun receipt(sessionId: String): HeartReceipt? =
        prefs.getString(key(sessionId), null)?.split(':')?.let { parts ->
            val version = parts.getOrNull(0)?.toLongOrNull() ?: return null
            HeartReceipt(version, parts.getOrNull(1) == "ok")
        }

    override suspend fun saveReceipt(sessionId: String, receipt: HeartReceipt) {
        prefs.edit().putString(key(sessionId), "${receipt.version}:${if (receipt.accepted) "ok" else "rejected"}").apply()
    }

    private fun key(id: String) = MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
