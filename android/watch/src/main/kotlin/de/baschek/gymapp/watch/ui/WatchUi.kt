package de.baschek.gymapp.watch.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.BasicSwipeToDismissBox
import androidx.wear.compose.foundation.pager.PagerState
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.foundation.rememberSwipeToDismissBoxState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import androidx.wear.compose.material3.curvedText
import androidx.wear.compose.material3.timeTextCurvedText
import androidx.wear.compose.material3.timeTextSeparator
import de.baschek.gymapp.core.model.Target
import de.baschek.gymapp.core.model.WorkoutMode
import de.baschek.gymapp.core.training.EFFORT_PICK_TIMEOUT_MILLIS
import de.baschek.gymapp.core.training.effortChoices
import de.baschek.gymapp.core.training.resolveEffort
import de.baschek.gymapp.watch.recorder.NavRecorder
import kotlinx.coroutines.delay
import kotlin.math.abs

/** Draft values of the open set; survive remote prefill updates once edited (spec A4) and activity re-creation. */
@Stable
internal class Draft(weight: Double?, amount: Double, addOns: Set<Int>, edited: Boolean) {
    var weight by mutableStateOf(weight)
    var amount by mutableDoubleStateOf(amount)
    var addOns by mutableStateOf(addOns)
    var edited by mutableStateOf(edited)

    fun total(current: WatchCurrentSet): Double? = weight?.plus(addOns.sumOf { current.addOns.getOrElse(it) { 0.0 } })
    fun addOnValues(current: WatchCurrentSet): List<Double> = addOns.sorted().mapNotNull { current.addOns.getOrNull(it) }

    companion object {
        val Saver = listSaver<Draft, Any>(
            save = { listOf(it.weight ?: Double.NaN, it.amount, it.addOns.joinToString(","), it.edited) },
            restore = { Draft((it[0] as Double).takeUnless(Double::isNaN), it[1] as Double, (it[2] as String).split(',').mapNotNull(String::toIntOrNull).toSet(), it[3] as Boolean) },
        )
    }
}

/** Saveable selections of the sub-views (spec B: activity re-creation or a notification tap keep the place). */
@Stable
internal class Selection {
    var editor by mutableStateOf(EditorKind.WEIGHT.name)
    var menuExercise by mutableStateOf("")
    var setEdit by mutableStateOf("")
    var history by mutableStateOf("")
    var startKind by mutableStateOf("")
    var startId by mutableStateOf("")
    var gym by mutableStateOf("")
    var mode by mutableStateOf(WorkoutMode.NORMAL)
    var editWeight by mutableStateOf<Double?>(null)
    var editAmount by mutableDoubleStateOf(0.0)

    companion object {
        val Saver = listSaver<Selection, Any>(
            save = { listOf(it.editor, it.menuExercise, it.setEdit, it.history, it.startKind, it.startId, it.gym, it.mode, it.editWeight ?: Double.NaN, it.editAmount) },
            restore = { v ->
                Selection().apply {
                    editor = v[0] as String; menuExercise = v[1] as String; setEdit = v[2] as String; history = v[3] as String
                    startKind = v[4] as String; startId = v[5] as String; gym = v[6] as String; mode = v[7] as String
                    editWeight = (v[8] as Double).takeUnless(Double::isNaN); editAmount = v[9] as Double
                }
            },
        )
    }
}

/** The effort picker after the check (spec E): the set is saved when a band is tapped or after 5 s. */
@Stable
internal class EffortState {
    var pending by mutableStateOf<WatchAction.Log?>(null)
    var target by mutableStateOf(Target())
    var selected by mutableStateOf("")
    var userMoved by mutableStateOf(false)
    /** Restarts the 5-s timeout (bezel moves). */
    val interaction: MutableIntState = mutableIntStateOf(0)

    fun open(log: WatchAction.Log, target: Target) {
        this.target = target
        selected = effortChoices(target).firstOrNull { it.preselected }?.band.orEmpty()
        userMoved = false
        pending = log
        interaction.intValue++
    }
}

enum class EditorKind { WEIGHT, AMOUNT, REST, SET_WEIGHT, SET_AMOUNT }

/** Everything a screen needs; built once per composition by [WatchUi]. */
internal class UiContext(
    val state: WatchPresentation,
    val dispatch: (WatchAction) -> Unit,
    val nav: NavState,
    val phase: SetPhase,
    val pager: PagerState,
    val draft: Draft,
    val selection: Selection,
    val effort: EffortState,
    val undoVisible: Boolean,
    val celebration: MutableIntState,
    private val go: (NavState, String) -> Unit,
) {
    fun push(route: Route) = go(WatchNavRules.push(nav, route), "user:tap")
    fun back() { WatchNavRules.back(nav)?.let { go(it, "user:tap") } }
    fun reset(route: Route) = go(NavState(listOf(route)), "user:tap")
    fun toPage(page: Int) = go(NavState(listOf(Route.TRAINING), page), "user:tap")

    fun openEditor(kind: EditorKind) { selection.editor = kind.name; push(Route.EDITOR) }

    /** The check: open the effort picker; the set is logged by [commitEffort] (spec E). */
    fun check(amount: Double) {
        val current = state.current ?: return
        if (current.setId.isEmpty() || effort.pending != null) return
        effort.open(WatchAction.Log(current.exerciseId, current.setId, draft.total(current), amount, draft.addOnValues(current)), current.effortTarget)
        if (nav.top != Route.TRAINING || nav.page != TrainingPage.MIDDLE) toPage(TrainingPage.MIDDLE)
    }

    /** [chosen] null saves the preselection as "auto", a band as "user". */
    fun commitEffort(chosen: String?) {
        val log = effort.pending ?: return
        val entry = resolveEffort(chosen, effort.target)
        effort.pending = null
        dispatch(log.copy(effort = entry.effort, effortSource = entry.source))
    }

    /** One tap takes over the suggested weight for the next set (spec F/K). */
    fun acceptSuggestion() {
        val weight = state.suggestedWeight ?: return
        draft.weight = weight
        draft.addOns = state.suggestedAddOns
        draft.edited = true
    }

    /** Start flow: training item first, then gym (preselected), mode and packing list (spec C). */
    fun beginStart(kind: String, id: String) {
        selection.startKind = kind
        selection.startId = id
        selection.gym = (if (kind == "day") state.dayGyms[id] else null)?.takeIf { g -> state.gyms.any { it.id == g } }
            ?: state.lastGymId?.takeIf { g -> state.gyms.any { it.id == g } } ?: state.gyms.firstOrNull()?.id.orEmpty()
        selection.mode = WorkoutMode.NORMAL
        push(Route.START_GYM)
    }

    fun startWorkout() {
        val s = selection
        when (s.startKind) {
            "day" -> dispatch(WatchAction.StartDay(s.startId, s.gym, s.mode))
            "spontaneous" -> dispatch(WatchAction.StartSpontaneous(s.startId, s.gym, s.mode))
            "plan" -> dispatch(WatchAction.StartPlan(s.startId, s.gym, s.mode))
        }
    }
}

/**
 * The watch UI (spec A-N). [observer] receives every screen change and tap (single hook for telemetry and the
 * navigation recorder); [onExit] closes the app from the start screen (the system swipe-to-dismiss is off, so the
 * training never closes by a swipe).
 */
@Composable
fun WatchUi(state: WatchPresentation, observer: WatchNavObserver = WatchNavObserver.None, onExit: () -> Unit = {}, dispatch: (WatchAction) -> Unit) {
    val finished = state.summary != null && !state.sessionActive
    var navCode by rememberSaveable { mutableStateOf("") }
    var seenEvent by rememberSaveable { mutableStateOf<String?>(null) }
    val nav = NavState.decode(navCode) ?: WatchNavRules.afterEvent(null, state.sessionActive, finished, state.celebrations.isNotEmpty())
    val effort = remember { EffortState() }
    val phase = WatchNavRules.phase(state, effort.pending != null)
    val latestPhase by rememberUpdatedState(phase)
    val latestObserver by rememberUpdatedState(observer)
    val recording by NavRecorder.recording.collectAsState()
    val latestState by rememberUpdatedState(state)
    fun go(target: NavState, cause: String) {
        val now = NavState.decode(navCode)
        if (target == now) return
        // Recorder hook: the cause of an automatic change is recorded right before it happens.
        if (recording && cause.startsWith("auto:") && cause != "auto:start") {
            val s = latestState
            NavRecorder.auto(when {
                s.summary != null && !s.sessionActive -> "auto:summary"
                !s.sessionActive -> "auto:session_end"
                s.setupRemainingSeconds != null -> "auto:setup"
                else -> "auto:remote_event"
            })
        }
        navCode = target.encode()
        latestObserver.onScreen(target.screen(latestPhase), cause)
    }

    // Automatic screen changes only on real events, never on the per-second countdowns; restored state (re-creation,
    // notification tap) is kept because the event was already seen (spec A4, B).
    val eventKey = "${state.navigationKey}#${state.sessionActive}#$finished"
    LaunchedEffect(eventKey) {
        if (seenEvent == eventKey && navCode.isNotEmpty()) return@LaunchedEffect
        val first = seenEvent == null
        seenEvent = eventKey
        go(WatchNavRules.afterEvent(NavState.decode(navCode), state.sessionActive, finished, state.celebrations.isNotEmpty()), if (first) "auto:start" else "auto:event")
    }

    val pager = rememberPagerState(initialPage = nav.page, pageCount = { TrainingPage.COUNT })
    LaunchedEffect(nav.page, nav.top) {
        if (nav.top == Route.TRAINING && pager.currentPage != nav.page) {
            if (abs(pager.currentPage - nav.page) == 1) pager.animateScrollToPage(nav.page) else pager.scrollToPage(nav.page)
        }
    }
    LaunchedEffect(pager) {
        snapshotFlow { pager.currentPage }.collect { page ->
            val now = NavState.decode(navCode) ?: return@collect
            if (now.top == Route.TRAINING && now.stack.size == 1 && now.page != page) go(now.copy(page = page), "user:swipe")
        }
    }
    LaunchedEffect(phase) { val now = NavState.decode(navCode); if (now?.top == Route.TRAINING && now.page == TrainingPage.MIDDLE) latestObserver.onScreen(now.screen(phase), "auto:phase") }

    val current = state.current
    val draft = rememberSaveable(current?.exerciseId, current?.setId, saver = Draft.Saver) {
        Draft(current?.weight, current?.amount ?: 0.0, current?.selectedAddOns ?: emptySet(), false)
    }
    // A remote update of the prefill (phone, assistant) only replaces values the person has not edited (spec A4).
    LaunchedEffect(current?.weight, current?.amount, current?.selectedAddOns) {
        if (!draft.edited) { draft.weight = current?.weight; draft.amount = current?.amount ?: 0.0; draft.addOns = current?.selectedAddOns ?: emptySet() }
    }
    val selection = rememberSaveable(saver = Selection.Saver) { Selection() }
    // Recorder hook: the visible screen (sub-views first, then the training page or phase), only while recording.
    val visibleId = recorderScreen(nav, phase, selection.editor)
    LaunchedEffect(visibleId, recording) { if (recording) NavRecorder.screen(visibleId) }
    val celebration = rememberSaveable { mutableIntStateOf(0) }

    // The picker belongs to one open set; if the set was logged elsewhere meanwhile, nothing is saved twice.
    LaunchedEffect(current?.exerciseId, current?.setId) { if (effort.pending?.let { it.exercise != current?.exerciseId || it.set != current?.setId } == true) effort.pending = null }

    // Undo appears for 6.5 s after a set, left of "Weiter" (spec F).
    var undoVisible by remember { mutableStateOf(false) }
    LaunchedEffect(state.navigationKey, state.restRemainingSeconds != null) {
        if (state.restRemainingSeconds != null && state.undoAvailable) { undoVisible = true; delay(6_500); undoVisible = false } else undoVisible = false
    }

    val ui = UiContext(state, dispatch, nav, phase, pager, draft, selection, effort, undoVisible, celebration, ::go)
    LaunchedEffect(effort.pending, effort.interaction.intValue) {
        if (effort.pending != null) { delay(EFFORT_PICK_TIMEOUT_MILLIS); ui.commitEffort(if (effort.userMoved) effort.selected else null) }
    }

    fun back(cause: String) {
        val now = NavState.decode(navCode) ?: nav
        val target = WatchNavRules.back(now)
        if (target == null) onExit() else go(target, cause)
    }
    BackHandler(enabled = nav.stack.size > 1 || nav.top != Route.HOME) { back("user:back") }

    val accent = watchAccent(state.accent)
    val tap: (String) -> Unit = { element -> latestObserver.onTap((NavState.decode(navCode) ?: nav).screen(latestPhase), element) }
    CompositionLocalProvider(LocalAccent provides accent, LocalInteraction provides tap) {
        MaterialTheme(colorScheme = ColorScheme(primary = accent.color, onPrimary = accent.onColor)) {
            AppScaffold(timeText = { WatchTimeText(state) }, containerColor = Color.Black) {
                if (state.ambient && state.sessionActive) AmbientScreen(state, phase)
                else SwipeNavHost(nav, onBack = { back("user:swipe") }, onExit = onExit) { route -> RouteContent(route, ui) }
                ErrorBanner(state.error)
            }
        }
    }
}

/** Equivalent of SwipeDismissableNavHost for the saveable back stack: the edge swipe closes the top sub-view. */
@Composable
private fun SwipeNavHost(nav: NavState, onBack: () -> Unit, onExit: () -> Unit, content: @Composable (Route) -> Unit) {
    val below = nav.stack.getOrNull(nav.stack.size - 2)
    val exitable = nav.stack.size == 1 && nav.top == Route.HOME
    val swipe = rememberSwipeToDismissBoxState()
    BasicSwipeToDismissBox(
        onDismissed = { if (below != null) onBack() else onExit() },
        state = swipe,
        backgroundKey = below?.name ?: "exit",
        contentKey = "${nav.stack.size}:${nav.top.name}",
        // In the training the pages take the horizontal swipes; on its left page nothing happens (spec B).
        userSwipeEnabled = below != null || exitable,
    ) { isBackground ->
        if (isBackground) { if (below != null) content(below) else Box(Modifier.fillMaxSize().background(Color.Black)) }
        else content(nav.top)
    }
}

@Composable
private fun RouteContent(route: Route, ui: UiContext) {
    when (route) {
        Route.HOME -> HomeScreen(ui)
        Route.START_PLANS -> StartPlansScreen(ui)
        Route.START_SPONTANEOUS -> SpontaneousScreen(ui)
        Route.START_GYM -> StartGymScreen(ui)
        Route.START_MODE -> StartModeScreen(ui)
        Route.START_PACK -> StartPackScreen(ui)
        Route.PROGRESS -> ProgressScreen(ui)
        Route.FREQUENCY -> RowsScreen("Trainingshäufigkeit", ui.state.frequency, "Noch keine abgeschlossenen Trainings.")
        Route.WEIGHTS -> RowsScreen("Arbeitsgewichte", ui.state.weights, "Noch keine Arbeitsgewichte vorhanden.")
        Route.BESTS -> RowsScreen("Bestleistungen", ui.state.bests, "Noch keine Bestleistungen vorhanden.")
        Route.HISTORY -> HistoryScreen(ui)
        Route.HISTORY_DETAIL -> HistoryDetailScreen(ui)
        Route.SETTINGS -> SettingsScreen(ui)
        Route.HEALTH -> HealthScreen(ui)
        Route.CONNECTION -> ConnectionScreen(ui)
        Route.TRAINING -> TrainingPager(ui)
        Route.EDITOR -> ValueEditor(ui)
        Route.EXERCISE_MENU -> ExerciseMenu(ui)
        Route.SETS -> SetsScreen(ui)
        Route.SET_EDIT -> SetEditScreen(ui)
        Route.NOTE -> NoteScreen(ui)
        Route.ADD_EXERCISE -> AddExerciseScreen(ui)
        Route.END_CONFIRM -> EndConfirmScreen(ui)
        Route.CELEBRATION -> CelebrationScreen(ui)
        Route.SUMMARY -> SummaryScreen(ui)
    }
}

/** Time on every screen (spec A7); during a workout the pulse, in the test profile a permanent "Testprofil" (spec L). */
@Composable
private fun WatchTimeText(state: WatchPresentation) {
    val pulse = when {
        !state.sessionActive -> null
        state.ambient -> "♥ –"
        else -> state.pulse?.label ?: state.bpm?.let { "♥ $it" } ?: "Puls aus"
    }
    TimeText { time ->
        timeTextCurvedText(time)
        if (pulse != null) { timeTextSeparator(); curvedText(pulse, color = Muted) }
        if (state.testProfile) { timeTextSeparator(); curvedText("Testprofil", color = Warning) }
    }
}

/** Real rejections name their reason and disappear after 3 s (WatchRoot); no-ops stay silent (spec L). */
@Composable
private fun BoxScope.ErrorBanner(error: String?) {
    if (error == null) return
    val height = LocalConfiguration.current.screenHeightDp.dp
    Text(error, color = ErrorColor, fontFamily = Hanken, fontSize = 12.sp, textAlign = TextAlign.Center, maxLines = 2,
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = height * 0.05f).background(Color.Black).padding(horizontal = 8.dp, vertical = 4.dp))
}
