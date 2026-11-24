package de.baschek.gymapp.watch.ui

/*
 * Navigation of the watch (spec B). Pure rules, so they are unit-tested without Compose:
 * - a back stack of routes; sub-views are pushed on top and closed by an edge swipe or the back button,
 * - the training is one route with three pages (left: exercises, middle: set, right: training),
 * - the middle page is the default at the start and after every real event (navigationKey), never per second,
 * - back/edge swipe: close the sub-view, else one page back (right -> middle -> left); on the left page nothing.
 *
 * Every screen change and every tap of the UI goes through [WatchNavObserver] (one place), so a navigation recorder
 * can hook in without touching the screens.
 */

enum class Route {
    HOME, START_PLANS, START_SPONTANEOUS, START_GYM, START_MODE, START_PACK,
    PROGRESS, FREQUENCY, WEIGHTS, BESTS, HISTORY, HISTORY_DETAIL,
    SETTINGS, HEALTH, CONNECTION,
    TRAINING, EDITOR, EXERCISE_MENU, SETS, SET_EDIT, NOTE, ADD_EXERCISE, END_CONFIRM,
    CELEBRATION, SUMMARY,
    ;

    /** Telemetry/recorder name ([a-z_]). */
    val screen: String get() = name.lowercase(java.util.Locale.ROOT)
}

/** The three training pages. */
object TrainingPage {
    const val LEFT = 0
    const val MIDDLE = 1
    const val RIGHT = 2
    const val COUNT = 3
}

/** What the middle page shows; derived from the workout state, never from a timer of the UI. */
enum class SetPhase { SET, EFFORT, REST, DONE, SETUP, EMPTY }

data class NavState(val stack: List<Route>, val page: Int = TrainingPage.MIDDLE) {
    val top: Route get() = stack.last()

    /** Name of the visible screen for telemetry and the recorder ("training_left", "training_set", "editor", ...). */
    fun screen(phase: SetPhase = SetPhase.SET): String = if (top != Route.TRAINING) top.screen else when (page) {
        TrainingPage.LEFT -> "training_left"
        TrainingPage.RIGHT -> "training_right"
        else -> "training_" + phase.name.lowercase(java.util.Locale.ROOT)
    }

    fun encode(): String = stack.joinToString(",") { it.name } + "|" + page

    companion object {
        fun decode(raw: String?): NavState? {
            if (raw.isNullOrBlank()) return null
            val parts = raw.split('|')
            val stack = parts[0].split(',').mapNotNull { name -> Route.entries.firstOrNull { it.name == name } }
            if (stack.isEmpty()) return null
            val page = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, TrainingPage.COUNT - 1) ?: TrainingPage.MIDDLE
            return NavState(stack, page)
        }
    }
}

object WatchNavRules {
    /**
     * Where a real event leads (spec A4, B): an active workout shows the middle page with all sub-views closed, a
     * finished one the weight-increase scenes or the summary; without a workout the person stays where they are,
     * unless they were in the training.
     */
    fun afterEvent(current: NavState?, sessionActive: Boolean, finished: Boolean, celebrations: Boolean): NavState = when {
        sessionActive -> NavState(listOf(Route.TRAINING), TrainingPage.MIDDLE)
        finished -> NavState(listOf(if (celebrations) Route.CELEBRATION else Route.SUMMARY))
        current == null -> NavState(listOf(Route.HOME))
        current.stack.any { it in TRAINING_ROUTES } -> NavState(listOf(Route.HOME))
        else -> current
    }

    /**
     * Back button and edge swipe (spec B). Null means "not handled here": the system may close the app (only on the
     * start screen without a workout).
     */
    fun back(state: NavState): NavState? = when {
        state.stack.size > 1 -> state.copy(stack = state.stack.dropLast(1))
        state.top == Route.TRAINING -> if (state.page > TrainingPage.LEFT) state.copy(page = state.page - 1) else state
        state.top == Route.CELEBRATION -> NavState(listOf(Route.SUMMARY))
        state.top == Route.SUMMARY -> NavState(listOf(Route.HOME))
        else -> null
    }

    fun push(state: NavState, route: Route): NavState = if (state.top == route) state else state.copy(stack = state.stack + route)

    /** Routes that only make sense during a workout. */
    val TRAINING_ROUTES = setOf(Route.TRAINING, Route.EDITOR, Route.EXERCISE_MENU, Route.SETS, Route.SET_EDIT, Route.NOTE, Route.ADD_EXERCISE, Route.END_CONFIRM)

    /** The middle page's content (spec D-G); [effortPending] is the local effort picker after the check. */
    fun phase(state: WatchPresentation, effortPending: Boolean): SetPhase = when {
        effortPending -> SetPhase.EFFORT
        state.setupRemainingSeconds != null -> SetPhase.SETUP
        state.restRemainingSeconds != null -> SetPhase.REST
        state.exerciseFinished -> SetPhase.DONE
        state.current != null && state.current.setId.isNotEmpty() -> SetPhase.SET
        else -> SetPhase.EMPTY
    }
}

/**
 * The single hook for screen changes and taps. [cause] is "user:tap", "user:back", "user:swipe", "auto:event" (a real
 * workout event, also from the phone) or "auto:start". Element names are telemetry-safe ([a-z_]).
 */
interface WatchNavObserver {
    fun onScreen(screen: String, cause: String) {}
    fun onTap(screen: String, element: String) {}

    companion object {
        val None: WatchNavObserver = object : WatchNavObserver {}
    }
}

/** "Satz geschafft" -> "satz_geschafft"; umlauts folded, at most 48 characters, never empty. */
fun elementName(label: String): String {
    val folded = label.lowercase(java.util.Locale.ROOT)
        .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
        .replace(Regex("[^a-z]+"), "_").trim('_')
    return folded.take(48).trimEnd('_').ifEmpty { "tap" }
}

/*
 * Mapping to the fixed identifiers of the always-on navigation recorder (core/recorder/RecorderIds; maintenance notes in
 * docs/navrec.md). Only names, never values.
 */

/** Visible screen for the recorder: sub-views (overlays) first, then the training page, else the route name. */
fun recorderScreen(state: NavState, phase: SetPhase, editorKind: String = ""): String = when {
    state.top == Route.EDITOR -> when (editorKind) {
        EditorKind.AMOUNT.name, EditorKind.SET_AMOUNT.name, EditorKind.AMOUNT_RIGHT.name, EditorKind.SET_AMOUNT_RIGHT.name -> "editor_amount"
        EditorKind.REST.name -> "editor_rest"
        else -> "editor_weight"
    }
    state.top == Route.SET_EDIT -> "edit_set"
    state.top == Route.NOTE -> "train_note"
    state.top != Route.TRAINING -> state.top.screen
    state.page == TrainingPage.LEFT -> "train_left"
    state.page == TrainingPage.RIGHT -> "train_right"
    else -> when (phase) {
        SetPhase.EFFORT -> "pick"
        SetPhase.REST -> "pause"
        SetPhase.DONE -> "exercise_done"
        SetPhase.SETUP -> "setup"
        SetPhase.SET, SetPhase.EMPTY -> "train_center"
    }
}

private val RECORDER_ELEMENTS = mapOf(
    "satz_geschafft" to "log_set", "stopp_und_speichern" to "log_set", "zeit_starten" to "timer_start",
    "weiter" to "skip_rest", "rueckgaengig" to "undo", "fertig" to "done", "loeschen" to "delete_set",
    "wert_verringern" to "step", "wert_erhoehen" to "step", "gewicht_aendern" to "edit_weight",
    "wdh_aendern" to "edit_reps", "sek_aendern" to "edit_reps", "m_aendern" to "edit_reps", "pause_aendern" to "edit_rest",
    "vorschlag_uebernehmen" to "hint_apply", "naechste_uebung" to "next", "noch_ein_satz" to "add_set",
    "satz_hinzufuegen" to "add_set", "aufwaermsatz" to "warmup_insert", "notiz" to "note_open",
    "satz_ueberspringen" to "skip", "uebung_hinzufuegen" to "add_exercise", "training_beenden" to "finish_open",
    "beenden" to "finish_confirm", "abbrechen" to "finish_cancel", "auf_handy_oeffnen" to "open_phone",
    "gesundheit" to "open_health", "verbindung" to "open_connection", "einstellungen" to "open_settings",
    "neueste" to "conflict_newest", "meine" to "conflict_mine", "erneut" to "retry", "start" to "start_session",
    "fortsetzen" to "resume", "los" to "start", "workout_laden" to "start_day_workout", "plus_s" to "rest_plus",
    "minus_s" to "minus15", "puls_starten" to "enable", "jetzt" to "resume_exercise", "ueberspringen" to "skip_exercise",
    "wieder_aufnehmen" to "unskip_exercise", "nach_oben" to "move_up", "nach_unten" to "move_down",
    "nach_update_suchen" to "check_update", "update_installieren" to "install_update",
    "am_limit" to "effort", "aufzeichnung_senden" to "navrec_export", "dauer_am_rand" to "toggle_setting",
)

/** Stable recorder identifier of a tapped element ([elementName] of its description); unknown ones become "tap". */
fun recorderElement(element: String): String = RECORDER_ELEMENTS[element] ?: when {
    element.startsWith("zusatzgewicht") -> "toggle_addon"
    element.startsWith("anhaengen") -> "note"
    element.contains("uebrig") || element.contains("locker") -> "effort"
    element.startsWith("puls") -> "toggle_setting"
    else -> "tap"
}
