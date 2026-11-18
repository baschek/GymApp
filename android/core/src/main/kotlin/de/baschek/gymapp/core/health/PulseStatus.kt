package de.baschek.gymapp.core.health

/*
 * What the watch says about heart rate (watch design spec J). Pure, so the texts are tested: instead of the old
 * "Trag die Uhr enger" while nothing was being recorded, the status names the real state and, for errors, the reason.
 */

enum class PulseState {
    /** Recording runs and a recent reading exists. */
    MEASURING,
    /** Recording runs (or is starting) but there is no reading yet. */
    NO_READING,
    /** The heart-rate permission is missing. */
    PERMISSION_MISSING,
    /** Nothing is recorded (not started, or consent not given). */
    OFF,
    /** Recording failed or was interrupted; [PulseStatus.detail] names the reason. */
    ERROR,
}

/**
 * @property label short text ("♥ 124", "Puls aus", "Erlaubnis fehlt", "Kein Messwert", "Puls-Fehler")
 * @property detail one explaining line, null when the label says it all
 * @property canStart a "Puls starten" button makes sense (off, error, or permission missing during a workout)
 * @property askConsent the one-time question "Puls bei jedem Training aufzeichnen?" has not been answered yet
 */
data class PulseStatus(
    val state: PulseState,
    val label: String,
    val detail: String?,
    val canStart: Boolean,
    val askConsent: Boolean,
)

/**
 * @param workoutActive a workout runs on the watch
 * @param permissionGranted the heart-rate permission is granted
 * @param recording a recording for this workout is starting or running
 * @param bpm the latest reading of this workout's recording, if any
 * @param error the recorder's error message for this workout (technical, see [pulseErrorReason]), if any
 * @param autoConsent "Puls bei jedem Training aufzeichnen": true/false once answered, null while never asked
 */
fun pulseStatus(
    workoutActive: Boolean,
    permissionGranted: Boolean,
    recording: Boolean,
    bpm: Int?,
    error: String?,
    autoConsent: Boolean?,
): PulseStatus {
    val ask = workoutActive && autoConsent == null
    return when {
        recording && bpm != null -> PulseStatus(PulseState.MEASURING, "♥ $bpm", null, canStart = false, askConsent = false)
        recording -> PulseStatus(PulseState.NO_READING, "Kein Messwert", "Aufzeichnung läuft, noch kein Messwert vom Sensor.", canStart = false, askConsent = false)
        !permissionGranted -> PulseStatus(PulseState.PERMISSION_MISSING, "Erlaubnis fehlt", "Die Uhr darf den Puls nicht lesen.", canStart = workoutActive, askConsent = ask)
        error != null -> PulseStatus(PulseState.ERROR, "Puls-Fehler", pulseErrorReason(error), canStart = workoutActive, askConsent = ask)
        else -> PulseStatus(PulseState.OFF, "Puls aus", if (workoutActive) "Puls wird nicht aufgezeichnet." else null, canStart = workoutActive, askConsent = ask)
    }
}

/** German reason for a recorder error message (the recorder reports technical English texts). */
fun pulseErrorReason(message: String?): String {
    val m = message.orEmpty()
    return when {
        m.isBlank() -> "Unbekannter Fehler"
        m.contains("permission", ignoreCase = true) -> "Pulserlaubnis fehlt"
        m.contains("not supported", ignoreCase = true) -> "Die Uhr unterstützt Krafttraining mit Puls nicht"
        m.contains("Another exercise", ignoreCase = true) -> "Eine andere App zeichnet gerade ein Training auf"
        m.contains("no longer owned", ignoreCase = true) -> "Eine andere App hat die Aufzeichnung übernommen"
        m.contains("Orphaned", ignoreCase = true) -> "Alte Aufzeichnung wurde aufgeräumt"
        m.contains("ended exercise", ignoreCase = true) -> "Die Uhr hat die Aufzeichnung beendet"
        m.contains("Start failed", ignoreCase = true) -> "Start fehlgeschlagen"
        m.contains("foreground", ignoreCase = true) -> "Hintergrunddienst durfte nicht starten"
        m.contains("could not be saved", ignoreCase = true) || m.contains("cache", ignoreCase = true) -> "Speichern auf der Uhr fehlgeschlagen"
        else -> "Fehler: " + m.take(80)
    }
}
