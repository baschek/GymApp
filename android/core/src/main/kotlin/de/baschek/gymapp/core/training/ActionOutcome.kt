package de.baschek.gymapp.core.training

/*
 * Result of a workout action (watch design spec L). An action that changes nothing (tapping "Jetzt" on the current
 * exercise, "Weiter" twice, moving the first exercise up) is [ActionOutcome.Unchanged] and is ignored silently. A real
 * rejection carries a [RejectReason] whose German text the UI shows for [REJECTION_VISIBLE_MILLIS] and then hides.
 * There is no generic "Aktion nicht möglich" any more.
 */

sealed interface ActionOutcome {
    /** The action changed the workout. */
    data object Changed : ActionOutcome

    /** Nothing to do (the action would not change anything); show nothing. */
    data object Unchanged : ActionOutcome

    /** The action was refused; [reason] says why. */
    data class Rejected(val reason: RejectReason) : ActionOutcome

    val changed: Boolean get() = this == Changed

    /** The text to show, or null (changed or silently ignored). */
    val message: String? get() = (this as? Rejected)?.reason?.message
}

/** Real rejections with the text shown to the user. */
enum class RejectReason(val message: String) {
    NO_ACTIVE_WORKOUT("Kein Training aktiv"),
    EXERCISE_GONE("Übung nicht mehr im Training"),
    EXERCISE_DONE("Übung ist schon fertig"),
    SET_ALREADY_DONE("Satz ist schon erfasst"),
    INVALID_VALUE("Wert ungültig"),
    REST_ALREADY_OVER("Pause schon beendet"),
    NOTHING_TO_UNDO("Nichts zum Rückgängigmachen"),
    SET_LIMIT("Höchstens $MAX_WORKING_SETS Arbeitssätze"),
    CANNOT_START("Training kann nicht starten"),
    NOT_SAVED("Änderung konnte nicht gespeichert werden"),
}

/** How long a rejection stays visible. */
const val REJECTION_VISIBLE_MILLIS = 3_000L
