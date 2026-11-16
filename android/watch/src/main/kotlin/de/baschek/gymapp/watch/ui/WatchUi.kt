package de.baschek.gymapp.watch.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.baschek.gymapp.watch.R
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs
import kotlin.math.roundToInt

internal val LocalInteraction = staticCompositionLocalOf<() -> Unit> { {} }

private val Paper = Color(0xfff5f5f5)
private data class WatchAccent(val color: Color, val onColor: Color)
private val LocalAccent = staticCompositionLocalOf { WatchAccent(Paper, Color(0xff0c0c0c)) }
private fun watchAccent(key: String?): WatchAccent = when (key) {
    "salbei" -> WatchAccent(Color(0xffa8cf8e), Color(0xff14210d))
    "dunst" -> WatchAccent(Color(0xff9db2cc), Color(0xff0d1624))
    "sand" -> WatchAccent(Color(0xffd9c9a6), Color(0xff241d0e))
    else -> WatchAccent(Paper, Color(0xff0c0c0c))
}
private val Muted = Color(0xffa3a3a3)
private val Surface = Color(0xff242424)
private val Track = Color(0xff1d1d1d)
private val Warning = Color(0xffe0b15a)
private val Error = Color(0xfff07373)
private val Hanken = FontFamily(Font(R.font.hanken_grotesk))

/** Presentation data contains only values supplied by the local workout store and runtime. */
data class WatchOption(val id: String, val name: String, val detail: String = "", val color: Long? = null)
data class WatchSetRow(val id: String, val label: String, val value: String, val effort: String = "", val weight: Double? = null, val amount: Double = 0.0, val unit: String = "Wdh.")
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
)
data class WatchSummary(val volume: String, val sets: String, val duration: String, val pulse: String)
data class WatchCelebration(val exercise: String, val from: String, val to: String)
data class WatchPresentation(
    val accent: String? = null, val gyms: List<WatchOption> = emptyList(), val plans: List<WatchOption> = emptyList(),
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
    val testProfile: Boolean = false, val ambientOffsetX: Int = 0, val ambientOffsetY: Int = 0, val durationRing: Float? = null,
    val setupRemainingSeconds: Int? = null, val timedElapsedSeconds: Int? = null, val durationRingEnabled: Boolean = false, val packChecked: Map<String, Set<String>> = emptyMap(), val summary: WatchSummary? = null, val celebrations: List<WatchCelebration> = emptyList(),
    val error: String? = null,
)

/** Events are handled by the local-first repository; UI never fabricates training data. */
sealed interface WatchAction {
    data class StartPlan(val plan: String, val gym: String, val mode: String) : WatchAction
    data class StartDay(val day: String, val gym: String, val mode: String) : WatchAction
    data class StartSpontaneous(val exercise: String, val gym: String, val mode: String) : WatchAction
    data class Log(val exercise: String, val set: String, val weight: Double?, val amount: Double, val addons: List<Double> = emptyList(), val effort: String? = null) : WatchAction
    data class Focus(val exercise: String) : WatchAction
    data class Move(val exercise: String, val delta: Int) : WatchAction
    data class ToggleExercise(val exercise: String, val skipped: Boolean) : WatchAction
    data class CorrectSet(val exercise: String, val set: String, val weight: Double?, val amount: Double, val effort: String) : WatchAction
    data class DeleteSet(val exercise: String, val set: String) : WatchAction
    data object FinishSetup : WatchAction
    data object StartSetTimer : WatchAction
    data object StartRecording : WatchAction
    data class SetDurationRing(val enabled: Boolean) : WatchAction
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
    data object OpenPhone : WatchAction
}

private enum class Screen { HOME, GYM, PICK, SPONTANEOUS, MODE, PACK, TRAIN, REST, EXERCISE_DONE, SETUP, CELEBRATION, SUMMARY, HEALTH, CONNECTION, SETTINGS }
private enum class Editor { WEIGHT, AMOUNT, REST }

@Composable
fun WatchUi(state: WatchPresentation, dispatch: (WatchAction) -> Unit) {
    var screen by remember { mutableStateOf(if (state.sessionActive) Screen.TRAIN else Screen.HOME) }
    var previous by remember { mutableStateOf(Screen.HOME) }
    var gym by remember { mutableStateOf("") }
    var plan by remember { mutableStateOf("") }
    var day by remember { mutableStateOf("") }
    var spontaneous by remember { mutableStateOf("") }
    var addingExercise by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf("normal") }

    var editor by remember { mutableStateOf<Editor?>(null) }
    var exerciseMenu by remember { mutableStateOf<WatchExerciseRow?>(null) }
    var editingSet by remember { mutableStateOf<WatchSetRow?>(null) }
    var editWeight by remember { mutableStateOf<Double?>(null) }
    var editAmount by remember { mutableDoubleStateOf(0.0) }
    var endConfirm by remember { mutableStateOf(false) }
    val durationRing = state.durationRingEnabled
    var celebrationIndex by remember { mutableIntStateOf(0) }
    var draftWeight by remember(state.current?.exerciseId, state.current?.setId, state.current?.weight) { mutableStateOf(state.current?.weight) }
    var draftAmount by remember(state.current?.exerciseId, state.current?.setId, state.current?.amount) { mutableDoubleStateOf(state.current?.amount ?: 0.0) }
    var addOns by remember(state.current?.exerciseId, state.current?.setId) { mutableStateOf(state.current?.selectedAddOns ?: emptySet()) }
    val current = state.current
    val packItems = (state.packsByGym[gym] ?: state.packItems) + state.packsByDay[day].orEmpty()
    val checked = state.packChecked[gym].orEmpty() + state.packChecked["day:$day"].orEmpty()
    val pager = rememberPagerState(initialPage = 1, pageCount = { 6 })
    val scope = rememberCoroutineScope()
    val setRequester = remember { FocusRequester() }
    LaunchedEffect(pager.currentPage, editor, screen) { if (pager.currentPage == 1 && editor == null && screen == Screen.TRAIN) runCatching { setRequester.requestFocus() } }
    LaunchedEffect(state.sessionActive, state.restRemainingSeconds, state.setupRemainingSeconds, state.exerciseFinished, state.summary) {
        if (state.summary != null && !state.sessionActive) {
            screen = if (state.celebrations.isNotEmpty()) Screen.CELEBRATION else Screen.SUMMARY
        } else if (state.sessionActive) {
            if (state.setupRemainingSeconds != null) screen = Screen.SETUP
            else if (state.restRemainingSeconds != null) screen = Screen.REST
            else if (state.exerciseFinished) screen = Screen.EXERCISE_DONE
            else if (screen in listOf(Screen.HOME, Screen.PACK, Screen.REST, Screen.EXERCISE_DONE, Screen.SUMMARY, Screen.CELEBRATION)) screen = Screen.TRAIN
        }
    }
    fun back() {
        when {
            editor != null -> editor = null
            exerciseMenu != null -> exerciseMenu = null
            editingSet != null -> editingSet = null
            endConfirm -> endConfirm = false
            screen in listOf(Screen.HEALTH, Screen.CONNECTION, Screen.SETTINGS) -> screen = previous
            screen == Screen.TRAIN -> if (pager.currentPage > 0) scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } else screen = Screen.HOME
            screen == Screen.PACK -> screen = Screen.MODE
            screen == Screen.MODE -> screen = Screen.PICK
            screen == Screen.SPONTANEOUS && addingExercise -> { addingExercise = false; screen = Screen.TRAIN }
            screen in listOf(Screen.PICK, Screen.SPONTANEOUS) -> screen = Screen.GYM
            else -> screen = if (state.sessionActive) Screen.TRAIN else Screen.HOME
        }
    }
    BackHandler(enabled = screen != Screen.HOME || editor != null || exerciseMenu != null || editingSet != null || endConfirm) { back() }
    CompositionLocalProvider(LocalAccent provides watchAccent(state.accent)) { MaterialTheme {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            if (state.ambient && state.sessionActive) {
                CenterColumn(Modifier.offset(x = state.ambientOffsetX.dp, y = state.ambientOffsetY.dp)) {
                    Small(if (screen == Screen.REST) "Pause" else current?.exerciseName.orEmpty())
                    AmbientTitle(if (screen == Screen.REST) state.restRemainingSeconds?.let(::clock).orEmpty() else current?.let { "Satz ${it.number}/${it.total}" }.orEmpty())
                    Small(current?.let { setValue(it.weight, it.amount, it.unit, it.weightLabel) }.orEmpty())
                }
            } else {
                when (screen) {
                    Screen.HOME -> CenterColumn {
                        Small(if (state.sessionActive) "Training läuft" else "Heute · ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date())}")
                        Title(if (state.sessionActive) current?.exerciseName ?: "Training" else state.suggestion)
                        Small(if (state.sessionActive) current?.let { "Satz ${it.number} von ${it.total} · ${setValue(it.weight, it.amount, it.unit, it.weightLabel)}" }.orEmpty() else state.suggestionDetail)
                        Pill(if (state.sessionActive) "Fortsetzen  ▶" else "Start  ▶", true) { screen = if (state.sessionActive) Screen.TRAIN else Screen.GYM }
                        Pill(state.connection + if (state.pendingCount > 0) " · ${state.pendingCount} offen" else "") { previous = Screen.HOME; screen = Screen.CONNECTION }
                    }
                    Screen.GYM -> ListScreen("Studio", state.gyms.isEmpty(), "Keine Studios auf der Uhr. Mit dem Handy verbinden.") {
                        items(state.gyms, key = { it.id }) { item -> RowPill(item.name, item.detail, color = item.color) { gym = item.id; screen = Screen.PICK } }
                    }
                    Screen.PICK -> ListScreen("Training") {
                        items(state.dayWorkouts, key = { it.id }) { item -> RowPill("Workout laden", "heute") { day = item.id; plan = ""; spontaneous = ""; screen = Screen.MODE } }
                        items(state.plans, key = { it.id }) { item -> RowPill(item.name, item.detail) { plan = item.id; day = ""; spontaneous = ""; screen = Screen.MODE } }
                        item { RowPill("Spontan") { screen = Screen.SPONTANEOUS } }
                    }
                    Screen.SPONTANEOUS -> ListScreen("Übung wählen", state.library.isEmpty(), "Keine Übungen auf der Uhr.") {
                        items(state.library, key = { it.id }) { item -> RowPill(item.name, item.detail) { if (addingExercise) { dispatch(WatchAction.AddExercise(item.id)); addingExercise = false; screen = Screen.TRAIN } else { spontaneous = item.id; day = ""; plan = ""; screen = Screen.MODE } } }
                    }
                    Screen.MODE -> ListScreen("Modus") {
                        items(state.modes.ifEmpty { listOf(WatchOption("normal", "Normal"), WatchOption("fast", "Schnell"), WatchOption("ultra", "Ultra")) }, key = { it.id }) { item ->
                            RowPill(item.name, item.detail, selected = mode == item.id) { mode = item.id; screen = Screen.PACK }
                        }
                    }
                    Screen.PACK -> Box(Modifier.fillMaxSize()) {
                        ListScreen("Packliste ${checked.size}/${packItems.size}", bottom = 76) {
                            items(packItems, key = { it.id }) { item -> RowPill((if (item.id in checked) "☑  " else "☐  ") + item.name) { dispatch(WatchAction.TogglePack(gym, day, item.id)) } }
                        }
                        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp)) {
                            Pill("Los", true) {
                                when { day.isNotEmpty() -> dispatch(WatchAction.StartDay(day, gym, mode)); spontaneous.isNotEmpty() -> dispatch(WatchAction.StartSpontaneous(spontaneous, gym, mode)); plan.isNotEmpty() -> dispatch(WatchAction.StartPlan(plan, gym, mode)) }
                            }
                        }
                    }
                    Screen.TRAIN -> {
                        if (durationRing && state.durationRing != null) ProgressRing(state.durationRing)
                        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                            when (page) {
                                0 -> ListScreen("Übungen", bottom = 32) { items(state.exercises, key = { it.id }) { ex -> RowPill("${ex.status}  ${ex.name}", ex.detail, selected = ex.current) { exerciseMenu = ex } } }
                                1 -> if (current != null) Box(Modifier.fillMaxSize().focusRequester(setRequester).onRotaryScrollEvent {
                                    if (current.hasWeight) editor = Editor.WEIGHT else editor = Editor.AMOUNT
                                    true
                                }.focusable()) {
                                    Column(Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, bottom = 68.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                        Small("♥ ${state.bpm?.toString() ?: "– –"}" + if (state.phoneLeads) "  ▣ Handy" else "")
                                        Title(current.exerciseName, size = 18)
                                        Small(if (current.isDuration && state.timedElapsedSeconds != null) "Zeit: ${clock(state.timedElapsedSeconds)}" else "Satz ${current.number} von ${current.total}")
                                        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                            if (current.hasWeight) { ValueButton(draftWeight?.let { formatWeight(it + addOns.sumOf { index -> current.addOns.getOrElse(index) { 0.0 } }) } ?: "–", current.weightLabel, "Gewicht ändern") { editor = Editor.WEIGHT }; Text("×", color = Muted, fontSize = 18.sp) }
                                            ValueButton(formatWeight(draftAmount), current.unit, "${current.unit} ändern") { editor = Editor.AMOUNT }
                                        }
                                    }
                                    Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp)) {
                                        if (current.isDuration && state.timedElapsedSeconds == null) Pill("Zeit starten", true) { dispatch(WatchAction.StartSetTimer) }
                                        else if (current.isDuration) Pill("Stopp und speichern", true) { dispatch(WatchAction.Log(current.exerciseId, current.setId, draftWeight?.plus(addOns.sumOf { current.addOns.getOrElse(it) { 0.0 } }), state.timedElapsedSeconds!!.coerceAtLeast(1).toDouble(), addOns.sorted().mapNotNull { current.addOns.getOrNull(it) }, current.effort)) }
                                        else Pill("✓", true, "Satz geschafft") { dispatch(WatchAction.Log(current.exerciseId, current.setId, draftWeight?.plus(addOns.sumOf { current.addOns.getOrElse(it) { 0.0 } }), draftAmount, addOns.sorted().mapNotNull { current.addOns.getOrNull(it) }, current.effort)) }
                                    }
                                } else CenterColumn { Small("Kein offener Satz") }
                                2 -> ListScreen(current?.exerciseName ?: "Sätze", state.sets.isEmpty(), "Noch kein Satz.", bottom = 32) { items(state.sets, key = { it.id }) { row -> RowPill("${row.label}  ${row.value}", row.effort) { editingSet = row; editWeight = row.weight; editAmount = row.amount } } }
                                3 -> ListScreen("Notiz", bottom = 32) {
                                    current?.setup?.takeIf { it.isNotBlank() }?.let { item { InfoCard("Einstellung", it) } }
                                    item { InfoCard("Notiz", current?.note?.takeIf { it.isNotBlank() } ?: "Keine Notiz") }
                                }
                                4 -> CenterColumn {
                                    if (!state.pulsePermission) { Title("Pulserlaubnis fehlt"); Small("Puls während des Trainings anzeigen und aufzeichnen."); Pill("Auf Handy öffnen") { dispatch(WatchAction.OpenPhone) } }
                                    else if (state.bpm == null) { Title("– –", size = 40); Small("Kein Messwert. Trag die Uhr enger und halte den Arm ruhig.") }
                                    else { Title(state.bpm.toString(), size = 44); Small("Schläge pro Minute"); PulseCurve(state.pulseSamples); Small(state.recording) }
                                }
                                5 -> ListScreen("Mehr", bottom = 32) {
                                    if (state.undoAvailable) item { RowPill("Rückgängig") { dispatch(WatchAction.Undo) } }
                                    item { RowPill("Übung hinzufügen") { addingExercise = true; screen = Screen.SPONTANEOUS } }
                                    if (current?.isDuration == true) item { RowPill("Zeitwert speichern") { dispatch(WatchAction.Log(current.exerciseId, current.setId, draftWeight?.plus(addOns.sumOf { current.addOns.getOrElse(it) { 0.0 } }), draftAmount, addOns.sorted().mapNotNull { current.addOns.getOrNull(it) }, current.effort)) } }
                                    item { RowPill("Aufwärmsatz") { current?.let { dispatch(WatchAction.Warmup(it.exerciseId)) } } }
                                    item { RowPill("Satz hinzufügen") { current?.let { dispatch(WatchAction.AddSet(it.exerciseId)) } } }
                                    item { RowPill("Satz überspringen") { current?.let { dispatch(WatchAction.SkipSet(it.exerciseId, it.setId)) } } }
                                    item { InfoCard("Pause ändern", "Während der Pause verfügbar") }
                                    item { RowPill("Gesundheit") { previous = Screen.TRAIN; screen = Screen.HEALTH } }
                                    item { RowPill("Verbindung") { previous = Screen.TRAIN; screen = Screen.CONNECTION } }
                                    item { RowPill("Einstellungen") { previous = Screen.TRAIN; screen = Screen.SETTINGS } }
                                    item { RowPill("Auf Handy öffnen") { dispatch(WatchAction.OpenPhone) } }
                                    item { RowPill("Training beenden") { endConfirm = true } }
                                }
                            }
                        }
                        PageDots(pager.currentPage, Modifier.align(Alignment.BottomCenter).padding(bottom = 9.dp))
                    }
                    Screen.REST -> {
                        val seconds = state.restRemainingSeconds ?: 0
                        ProgressRing(if (state.restTotalSeconds > 0) (seconds.toFloat() / state.restTotalSeconds).coerceIn(0f, 1f) else 0f)
                        CenterColumn {
                            if (state.undoAvailable) Pill("Rückgängig") { dispatch(WatchAction.Undo) }
                            Small(if (state.exerciseFinished) "Übung fertig" else if (seconds <= 0) "Los" else "Pause")
                            Box(Modifier.heightIn(min = 48.dp).clickable { editor = Editor.REST }, contentAlignment = Alignment.Center) { Title(clock(seconds), size = 42) }
                            Small(if (state.exerciseFinished) "Danach: Nächste Übung auf Befehl" else "Als Nächstes: ${current?.let { setValue(it.weight, it.amount, it.unit, it.weightLabel) }.orEmpty()}")
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { Pill("+15 s") { dispatch(WatchAction.ChangeRest(15)) }; Pill("Weiter", true) { dispatch(WatchAction.SkipRest) } }
                        }
                    }
                    Screen.EXERCISE_DONE -> CenterColumn {
                        Small("Fertig"); Title(current?.exerciseName.orEmpty()); Small("${state.sets.size} Sätze gespeichert")
                        Pill(state.nextExerciseName ?: "Training beenden", true) { if (state.nextExerciseName == null) endConfirm = true else dispatch(WatchAction.NextExercise) }
                        Pill("Noch ein Satz") { current?.let { dispatch(WatchAction.AddSet(it.exerciseId)) } }
                    }
                    Screen.SETUP -> { ProgressRing((state.setupRemainingSeconds ?: 0).coerceAtLeast(0) / 45f); CenterColumn { Small("Gerät einstellen"); Title(current?.exerciseName.orEmpty()); Title(current?.setup.orEmpty()); Small(clock(state.setupRemainingSeconds ?: 0)); Pill("Fertig", true) { dispatch(WatchAction.FinishSetup); screen = Screen.TRAIN } } }
                    Screen.CELEBRATION -> state.celebrations.getOrNull(celebrationIndex)?.let { c -> CenterColumn {
                        Small("Neues Gewicht"); Title(c.exercise); Small(c.from); Title(c.to, size = 38); Pill("Weiter", true) { celebrationIndex++; if (celebrationIndex >= state.celebrations.size) screen = Screen.SUMMARY }; Pill("Überspringen") { screen = Screen.SUMMARY }
                    } }
                    Screen.SUMMARY -> ListScreen("Geschafft") {
                        state.summary?.let { s -> item { InfoCard("Volumen", s.volume) }; item { RowPill("Sätze", s.sets) {} }; item { RowPill("Dauer", s.duration) {} }; item { RowPill("Puls Ø / max", s.pulse) {} } }
                        if (state.undoAvailable) item { RowPill("Rückgängig") { dispatch(WatchAction.Undo) } }
                        item { InfoCard("Sync", state.connection + if (state.pendingCount > 0) " · ${state.pendingCount} offen" else "") }
                        item { InfoCard("Gesundheit", state.export) }
                        item { RowPill("Fertig", selected = true) { screen = Screen.HOME } }
                    }
                    Screen.HEALTH -> ListScreen("Gesundheit") {
                        item { InfoCard("Aufzeichnung", state.recording) }; item { InfoCard("Puls", "Puls während des Trainings anzeigen und aufzeichnen.") }; item { RowPill("Puls aufzeichnen") { dispatch(WatchAction.StartRecording) } }; item { InfoCard("Puls", if (!state.pulsePermission) "Erlaubnis fehlt" else if (state.bpm == null) "Kein Messwert" else "Wird gemessen") }; item { InfoCard("Export in Health Connect", state.export) }
                        item { RowPill("Auf Handy öffnen") { dispatch(WatchAction.OpenPhone) } }; item { RowPill("Zurück") { back() } }
                    }
                    Screen.CONNECTION -> ListScreen("Verbindung") {
                        item { InfoCard(state.connection, if (state.pendingCount > 0) "${state.pendingCount} Änderungen offen" else "Keine Änderungen offen") }
                        state.conflict?.let { message -> item { InfoCard("Konflikt", message) }; item { RowPill("Neueste") { dispatch(WatchAction.Resolve(true)) } }; item { RowPill("Meine") { dispatch(WatchAction.Resolve(false)) } } }
                        item { RowPill("Erneut") { dispatch(WatchAction.Retry) } }; item { RowPill("Zurück") { back() } }
                    }
                    Screen.SETTINGS -> ListScreen("Rand im Training") {
                        item { RowPill("Dauer anzeigen", selected = durationRing) { dispatch(WatchAction.SetDurationRing(true)) } }; item { RowPill("Dauer ausblenden", selected = !durationRing) { dispatch(WatchAction.SetDurationRing(false)) } }; item { RowPill("Zurück") { back() } }
                    }
                }
                if (state.testProfile) Text("Testprofil", color = Warning, fontSize = 10.sp, modifier = Modifier.align(Alignment.TopCenter).background(Color.Black))
                state.error?.let { Text(it, color = Error, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.align(Alignment.BottomCenter).background(Color.Black).padding(4.dp)) }
                editingSet?.let { row ->
                    CenterColumn(Modifier.background(Color.Black)) {
                        Small("Satz bearbeiten · ${row.label}")
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                            if (row.weight != null) ValueButton(formatWeight(editWeight ?: 0.0), "kg", "Gewicht ändern") { editor = Editor.WEIGHT }
                            ValueButton(formatWeight(editAmount), row.unit, "Wert ändern") { editor = Editor.AMOUNT }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Pill("Löschen") { current?.let { dispatch(WatchAction.DeleteSet(it.exerciseId, row.id)) }; editingSet = null }
                            Pill("✓", true, "Änderung speichern") { current?.let { dispatch(WatchAction.CorrectSet(it.exerciseId, row.id, editWeight, editAmount, row.effort)) }; editingSet = null }
                        }
                    }
                }
                exerciseMenu?.let { ex -> ListScreen(ex.name) {
                    if (!ex.completed) item { RowPill("Jetzt") { dispatch(WatchAction.Focus(ex.id)); exerciseMenu = null; scope.launch { pager.scrollToPage(1) } } }
                    item { RowPill(if (ex.skipped) "Wieder aufnehmen" else "Überspringen") { dispatch(WatchAction.ToggleExercise(ex.id, ex.skipped)); exerciseMenu = null } }
                    item { RowPill("Nach oben") { dispatch(WatchAction.Move(ex.id, -1)); exerciseMenu = null } }; item { RowPill("Nach unten") { dispatch(WatchAction.Move(ex.id, 1)); exerciseMenu = null } }; item { RowPill("Schließen") { exerciseMenu = null } }
                } }
                if (editor != null) ValueEditor(editor!!, current, if (editingSet != null) editWeight else draftWeight, if (editingSet != null) editAmount else draftAmount, onWeight = { if (editingSet != null) editWeight = it else draftWeight = it }, onAmount = { if (editingSet != null) editAmount = it else draftAmount = it }, onRest = { dispatch(WatchAction.ChangeRest(it)) }, addOns = if (editingSet != null) emptySet() else addOns, onAddOns = { addOns = it }, onClose = { editor = null })
                if (endConfirm) CenterColumn(Modifier.background(Color.Black)) { Title("Training beenden?"); Small("Offene Sätze werden übersprungen."); Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { Pill("Weiter") { endConfirm = false }; Pill("Beenden", true) { endConfirm = false; dispatch(WatchAction.Finish) } } }
            }
        }
    } }
}

@Composable
private fun CenterColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 0.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center, content = content)
}

@Composable
private fun Title(text: String, size: Int = 20, color: Color = Paper) {
    Text(text, color = color, fontFamily = Hanken, fontWeight = FontWeight.Bold, fontSize = size.sp, lineHeight = (size + 2).sp, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)))
}

@Composable
private fun AmbientTitle(text: String) {
    Text(text, color = Color(0xff8a8a8a), fontFamily = Hanken, fontWeight = FontWeight.Bold, fontSize = 20.sp,
        textAlign = TextAlign.Center, maxLines = 2, style = TextStyle(drawStyle = Stroke(width = 1f)))
}

/** A null sample starts a new segment; no values are filled into missing intervals. */
@Composable
private fun PulseCurve(samples: List<Pair<Long, Int?>>) {
    val valid = samples.mapNotNull { it.second }
    if (valid.size < 2) return
    val first = samples.first().first
    val span = (samples.last().first - first).coerceAtLeast(1L)
    val low = (valid.minOrNull() ?: 0).toFloat()
    val range = ((valid.maxOrNull() ?: 0) - low).coerceAtLeast(1f)
    val accent = LocalAccent.current
    Canvas(Modifier.width(120.dp).height(30.dp).semantics { contentDescription = "Pulsverlauf, Messlücken bleiben leer" }) {
        var previous: Offset? = null
        samples.forEach { (time, bpm) ->
            if (bpm == null) previous = null else {
                val point = Offset(((time - first).toFloat() / span).coerceIn(0f, 1f) * size.width,
                    size.height - ((bpm - low) / range).coerceIn(0f, 1f) * size.height)
                previous?.let { drawLine(accent.color, it, point, strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round) }
                previous = point
            }
        }
    }
}

@Composable
private fun Small(text: String) {
    if (text.isNotBlank()) Text(text, color = Muted, fontFamily = Hanken, fontSize = 12.sp, lineHeight = 15.sp, textAlign = TextAlign.Center, style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)))
}

@Composable
private fun Pill(text: String, primary: Boolean = false, description: String = text, onClick: () -> Unit) {
    val track = LocalInteraction.current
    val accent = LocalAccent.current
    Box(Modifier.defaultMinSize(minWidth = 70.dp, minHeight = 48.dp).background(if (primary) accent.color else Surface, CircleShape).clickable { track(); onClick() }.semantics { contentDescription = description }.padding(horizontal = 14.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        Text(text, color = if (primary) accent.onColor else Paper, fontFamily = Hanken, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

@Composable
private fun RowPill(text: String, detail: String = "", selected: Boolean = false, color: Long? = null, onClick: () -> Unit) {
    val track = LocalInteraction.current
    val accent = LocalAccent.current
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).background(if (selected) accent.color else Surface, CircleShape).clickable { track(); onClick() }.padding(horizontal = 13.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (color != null) { Box(Modifier.size(8.dp).background(Color(color), CircleShape)); Spacer(Modifier.width(6.dp)) }
        Text(text, modifier = Modifier.weight(1f), color = if (selected) accent.onColor else Paper, fontFamily = Hanken, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (detail.isNotBlank()) Text(detail, color = if (selected) Color.DarkGray else Muted, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
private fun InfoCard(label: String, value: String) {
    Column(Modifier.fillMaxWidth().background(Surface, androidx.compose.foundation.shape.RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 8.dp)) {
        Text(label, color = Muted, fontFamily = Hanken, fontSize = 11.sp)
        Text(value, color = Paper, fontFamily = Hanken, fontSize = 14.sp, lineHeight = 18.sp)
    }
}

@Composable
private fun ListScreen(title: String, empty: Boolean = false, emptyText: String = "", bottom: Int = 24, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    val list = rememberLazyListState()
    val requester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { requester.requestFocus() }
    LazyColumn(Modifier.fillMaxSize().background(Color.Black).focusRequester(requester).onRotaryScrollEvent { event ->
        scope.launch { list.scrollBy(event.verticalScrollPixels) }; true
    }.focusable(), state = list, contentPadding = PaddingValues(start = 25.dp, end = 25.dp, top = 24.dp, bottom = bottom.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item { Text(title, color = Muted, fontFamily = Hanken, fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) }
        if (empty) item { Small(emptyText) }
        content()
    }
}

@Composable
private fun ValueButton(value: String, unit: String, description: String, onClick: () -> Unit) {
    val track = LocalInteraction.current
    Column(Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp).clickable { track(); onClick() }.semantics { contentDescription = description }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(value, color = Paper, fontSize = 26.sp, fontWeight = FontWeight.Bold, fontFamily = Hanken, lineHeight = 27.sp)
        Text(unit, color = Muted, fontSize = 11.sp, fontFamily = Hanken)
    }
}

@Composable
private fun StepButton(text: String, description: String, onClick: () -> Unit) {
    val track = LocalInteraction.current
    Box(Modifier.size(48.dp).background(Surface, CircleShape).clickable { track(); onClick() }.semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Text(text, color = Paper, fontFamily = Hanken, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun PageDots(page: Int, modifier: Modifier = Modifier) {
    val accent = LocalAccent.current
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) { repeat(6) { i -> Box(Modifier.size(6.dp).background(if (i == page) accent.color else Color(0xff3a3a3a), CircleShape)) } }
}

@Composable
private fun ProgressRing(fraction: Float) {
    val accent = LocalAccent.current
    Canvas(Modifier.fillMaxSize().padding(5.dp)) {
        val width = 6.dp.toPx()
        drawArc(Track, -90f, 360f, false, style = Stroke(width, cap = StrokeCap.Round))
        if (fraction > 0f) drawArc(accent.color, -90f, 359.9f * fraction.coerceIn(0f, 1f), false, style = Stroke(width, cap = StrokeCap.Round))
    }
}

@Composable
private fun ValueEditor(editor: Editor, current: WatchCurrentSet?, weight: Double?, amount: Double, onWeight: (Double) -> Unit, onAmount: (Double) -> Unit, onRest: (Int) -> Unit, addOns: Set<Int>, onAddOns: (Set<Int>) -> Unit, onClose: () -> Unit) {
    val requester = remember { FocusRequester() }
    val accent = LocalAccent.current
    var accumulated by remember { mutableFloatStateOf(0f) }
    fun step(direction: Int) {
        when (editor) {
            Editor.WEIGHT -> {
                val steps = current?.weightSteps.orEmpty()
                val w = weight ?: 0.0
                if (steps.isEmpty()) onWeight((w + direction * 2.5).coerceAtLeast(0.0))
                else {
                    val index = steps.indices.minByOrNull { abs(steps[it] - w) } ?: 0
                    onWeight(steps[(index + direction).coerceIn(0, steps.lastIndex)])
                }
            }
            Editor.AMOUNT -> onAmount((amount + direction * if (current?.isDuration == true) 5 else if (current?.unit == "m") 10 else 1).coerceIn(1.0, 999.0))
            Editor.REST -> onRest(direction * 15)
        }
    }
    LaunchedEffect(Unit) { requester.requestFocus() }
    Column(Modifier.fillMaxSize().background(Color.Black).focusRequester(requester).onRotaryScrollEvent { event ->
        accumulated += event.verticalScrollPixels
        if (abs(accumulated) >= 20f) { step(if (accumulated > 0) 1 else -1); accumulated = 0f }
        true
    }.focusable().padding(horizontal = 22.dp, vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Small(when (editor) { Editor.WEIGHT -> "Gewicht"; Editor.AMOUNT -> current?.unit ?: "Wert"; Editor.REST -> "Pause in 15-s-Schritten" })
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            StepButton("−", "Wert verringern") { step(-1) }
            Text(when (editor) { Editor.WEIGHT -> formatWeight(weight ?: 0.0); Editor.AMOUNT -> formatWeight(amount); Editor.REST -> "15 s" }, color = accent.color, fontFamily = Hanken, fontSize = 30.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            StepButton("+", "Wert erhöhen") { step(1) }
        }
        if (editor == Editor.WEIGHT && current != null) {
            if (current.weightSteps.isEmpty()) Small("Kein Maschinenprofil")
            if (current.addOns.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { current.addOns.forEachIndexed { index, value -> Pill((if (index in addOns) "✓ " else "+ ") + formatWeight(value)) { onAddOns(if (index in addOns) addOns - index else addOns + index) } } }
            if (current.addOns.isNotEmpty()) Small("Gesamt ${formatWeight((weight ?: 0.0) + addOns.sumOf { current.addOns.getOrElse(it) { 0.0 } })} kg")
        }
        if (editor == Editor.AMOUNT) Small(current?.target.orEmpty())
        Pill("✓", true, "Fertig", onClose)
    }
}

private fun formatWeight(value: Double): String = if (value == value.roundToInt().toDouble()) value.roundToInt().toString() else "%.2f".format(java.util.Locale.GERMANY, value).trimEnd('0').trimEnd(',')
private fun setValue(weight: Double?, amount: Double, unit: String, weightLabel: String): String = (weight?.let { "${formatWeight(it)} $weightLabel × " } ?: "") + "${formatWeight(amount)} $unit"
private fun clock(seconds: Int): String { val n = abs(seconds); return (if (seconds < 0) "+" else "") + "%d:%02d".format(n / 60, n % 60) }
