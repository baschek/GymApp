package de.baschek.gymapp.watch.ui

import de.baschek.gymapp.core.health.PulseStatus
import de.baschek.gymapp.core.model.Target
import de.baschek.gymapp.core.training.SetPreview

/** Presentation data contains only values supplied by the local workout store and runtime. */
data class WatchOption(val id: String, val name: String, val detail: String = "", val color: Long? = null)
data class WatchSetRow(val id: String, val label: String, val value: String, val effort: String = "", val weight: Double? = null, val amount: Double = 0.0, val unit: String = "Wdh.",
    /** Right-side reps of a left/right set ([amount] is then the left side); null for ordinary sets. */
    val amountRight: Double? = null)
data class WatchExerciseRow(
    val id: String, val name: String, val detail: String, val status: String,
    val current: Boolean = false, val completed: Boolean = false, val skipped: Boolean = false,
)
data class WatchCurrentSet(
    val exerciseId: String, val setId: String, val exerciseName: String, val number: Int, val total: Int,
    val weight: Double?, val amount: Double, val unit: String, val weightLabel: String = "kg",
    val target: String = "", val setup: String = "", val note: String = "",
    val weightSteps: List<Double> = emptyList(), val addOns: List<Double> = emptyList(),
    val selectedAddOns: Set<Int> = emptySet(), val effort: String? = null, val hasWeight: Boolean = weight != null, val isDuration: Boolean = false,
    /** Target of the exercise; the effort picker preselects its reserve (spec E). */
    val effortTarget: Target = Target(rirMin = 1.0, rirMax = 2.0),
    /** The note of this exercise in the running workout only (the note screen appends to it, spec I). */
    val exerciseNote: String = "",
    /** Right-side reps of a left/right exercise ([amount] is then the left side, both mandatory); null for ordinary sets. */
    val amountRight: Double? = null,
)
data class WatchSummary(val volume: String, val sets: String, val duration: String, val pulse: String)
data class WatchCelebration(val exercise: String, val from: String, val to: String)
data class WatchProgressRow(val id: String, val title: String, val detail: String)
data class WatchHistoryEntry(val id: String, val title: String, val detail: String, val sections: List<Pair<String, String>>)
data class WatchPresentation(
    val phoneHealthEnabled: Boolean? = null, val phoneHealthBackground: Boolean? = null, val updateStatus: String = "Wird geprüft", val updateCanInstall: Boolean = false, val frequency: List<WatchProgressRow> = emptyList(), val weights: List<WatchProgressRow> = emptyList(), val bests: List<WatchProgressRow> = emptyList(), val history: List<WatchHistoryEntry> = emptyList(), val accent: String? = null, val gyms: List<WatchOption> = emptyList(), val plans: List<WatchOption> = emptyList(),
    val dayWorkouts: List<WatchOption> = emptyList(), val library: List<WatchOption> = emptyList(),
    val packItems: List<WatchOption> = emptyList(), val packsByDay: Map<String, List<WatchOption>> = emptyMap(), val packsByGym: Map<String, List<WatchOption>> = emptyMap(), val modes: List<WatchOption> = emptyList(),
    val suggestion: String = "Kein Training vorgeschlagen", val suggestionDetail: String = "",
    val exercises: List<WatchExerciseRow> = emptyList(), val sets: List<WatchSetRow> = emptyList(),
    val current: WatchCurrentSet? = null, val sessionActive: Boolean = false,
    val restRemainingSeconds: Int? = null, val restTotalSeconds: Int = 0, val exerciseFinished: Boolean = false,
    val nextExerciseName: String? = null, val undoAvailable: Boolean = false,
    val connection: String = "Nur Uhr", val pendingCount: Int = 0, val conflict: String? = null,
    val bpm: Int? = null, val pulseSamples: List<Pair<Long, Int?>> = emptyList(), val pulsePermission: Boolean = false, val recording: String = "Nicht verfügbar",
    val export: String = "Nicht verfügbar", val phoneLeads: Boolean = false, val ambient: Boolean = false,
    val testProfile: Boolean = false, val durationRing: Float? = null,
    val setupRemainingSeconds: Int? = null, val timedElapsedSeconds: Int? = null, val durationRingEnabled: Boolean = false, val packChecked: Map<String, Set<String>> = emptyMap(), val summary: WatchSummary? = null, val celebrations: List<WatchCelebration> = emptyList(),
    val error: String? = null,
    /**
     * Changes only on real events (set logged, rest started or ended, exercise changed, workout started or ended, also
     * from the phone), never per second; automatic screen changes are keyed on it (spec A4/A5).
     */
    val navigationKey: String = "",
    /** The next set for the rest/set screens (spec D/F): values, target line, suggestion, setup of a new exercise. */
    val nextSet: SetPreview? = null,
    /** Heart-rate status with reason (spec J); null outside the app's runtime (previews, tests). */
    val pulse: PulseStatus? = null,
    /** "Puls bei jedem Training aufzeichnen": null until answered once. */
    val heartAutoConsent: Boolean? = null,
    /**
     * Weight suggestion of [nextSet] split into machine level and add-on indices of [current], set only when the next
     * set is the current draft's set; one tap on the suggestion row takes it over (spec F/K).
     */
    val suggestedWeight: Double? = null,
    val suggestedAddOns: Set<Int> = emptySet(),
    /** Start screen (spec C): the suggested plan with gym and mode, and the gym preselected in the start flow. */
    val homePlan: WatchOption? = null,
    val lastGymId: String? = null,
    /** Gym of each offered day workout (preselected in the start flow). */
    val dayGyms: Map<String, String> = emptyMap(),
    /** Modes with their estimated duration per "plan:<id>" / "day:<id>"; missing keys show the modes without duration. */
    val modeOptions: Map<String, List<WatchOption>> = emptyMap(),
    /** Identity of the workout owning the set drafts. */
    val sessionId: String? = null,
) {
    internal val pendingChangesLabel: String?
        get() = when (pendingCount) {
            0 -> null
            1 -> "1 Änderung nicht übertragen"
            else -> "$pendingCount Änderungen nicht übertragen"
        }
}

/** Events are handled by the local-first repository; UI never fabricates training data. */
sealed interface WatchAction {
    data class StartPlan(val plan: String, val gym: String, val mode: String) : WatchAction
    data class StartDay(val day: String, val gym: String, val mode: String) : WatchAction
    data class StartSpontaneous(val exercise: String, val gym: String, val mode: String) : WatchAction
    /** [effortSource]: "user" when the band was chosen in the effort picker, "auto" when the preselection applied (spec E). */
    data class Log(val exercise: String, val set: String, val weight: Double?, val amount: Double, val addons: List<Double> = emptyList(), val effort: String? = null, val effortSource: String? = null, val amountRight: Double? = null) : WatchAction
    data class Focus(val exercise: String) : WatchAction
    data class Move(val exercise: String, val delta: Int) : WatchAction
    data class ToggleExercise(val exercise: String, val skipped: Boolean) : WatchAction
    data class CorrectSet(val exercise: String, val set: String, val weight: Double?, val amount: Double, val effort: String, val amountRight: Double? = null) : WatchAction
    data class DeleteSet(val exercise: String, val set: String) : WatchAction
    /** Replaces the exercise's note in the running workout (the note screen appends before sending, spec I). */
    data class SetNote(val exercise: String, val note: String) : WatchAction
    data object FinishSetup : WatchAction
    data object StartSetTimer : WatchAction
    data object StartRecording : WatchAction
    data class SetDurationRing(val enabled: Boolean) : WatchAction
    /** Answer to "Puls bei jedem Training aufzeichnen" (spec J). */
    data class SetHeartAuto(val enabled: Boolean) : WatchAction
    data class TogglePack(val gym: String, val day: String, val item: String) : WatchAction
    data class AddExercise(val exercise: String) : WatchAction
    data class AddSet(val exercise: String) : WatchAction
    data class Warmup(val exercise: String) : WatchAction
    data class SkipSet(val exercise: String, val set: String) : WatchAction
    data object Undo : WatchAction
    data object NextExercise : WatchAction
    data object Finish : WatchAction
    data class ChangeRest(val seconds: Int) : WatchAction
    data object SkipRest : WatchAction
    data object Retry : WatchAction
    data class Resolve(val latest: Boolean) : WatchAction
    data object OpenPhoneHealthSettings : WatchAction
    data object CheckUpdate : WatchAction
    data object InstallUpdate : WatchAction
    data object OpenPhone : WatchAction
}
