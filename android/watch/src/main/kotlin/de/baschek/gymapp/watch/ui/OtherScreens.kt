package de.baschek.gymapp.watch.ui

import android.app.RemoteInput
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import de.baschek.gymapp.watch.recorder.NavRecorder
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.input.RemoteInputIntentHelper
import de.baschek.gymapp.core.health.PulseState

/*
 * Start (spec C), progress and history, settings/health/connection (spec J), summary and weight-increase scenes
 * (spec N), note input (spec I).
 */

private val DEFAULT_MODES = listOf(WatchOption("normal", "Normal"), WatchOption("fast", "Schnell"), WatchOption("ultra", "Ultra"))

// ---- C: start ------------------------------------------------------------------------------------------------------

@Composable
internal fun HomeScreen(ui: UiContext) {
    val state = ui.state
    val current = state.current
    val plan = state.homePlan
    ListScreen(if (state.sessionActive) "Training läuft" else "GymApp") {
        // Connection line, always visible and in words (spec C1, L).
        item { ListButton(state.connection, state.pendingChangesLabel.orEmpty(), description = "Verbindung") { ui.push(Route.CONNECTION) } }
        if (state.sessionActive) {
            item { ListButton("Fortsetzen", current?.let { "${it.exerciseName} · Satz ${it.number}/${it.total} · ${setValue(it.weight, it.amount, it.unit, it.weightLabel)}" }.orEmpty(), selected = true) { ui.reset(Route.TRAINING) } }
        } else {
            if (plan != null) item {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Title(plan.name, size = 17)
                    Small(plan.detail)
                }
            }
            item {
                ListButton("Start ▶", plan?.name ?: "Training wählen", selected = true, description = "Start") {
                    if (plan != null) ui.beginStart("plan", plan.id) else ui.push(Route.START_PLANS)
                }
            }
            items(state.dayWorkouts, key = { "day-" + it.id }) { day -> ListButton("Workout laden", "Heute: ${day.name}") { ui.beginStart("day", day.id) } }
            item { ListButton("Andere Pläne") { ui.push(Route.START_PLANS) } }
            item { ListButton("Spontan") { ui.push(Route.START_SPONTANEOUS) } }
        }
        item { ListButton("Fortschritt & Verlauf") { ui.push(Route.PROGRESS) } }
        item { ListButton("Einstellungen") { ui.push(Route.SETTINGS) } }
    }
}

@Composable
internal fun StartPlansScreen(ui: UiContext) {
    ListScreen("Pläne", ui.state.plans.isEmpty() && ui.state.dayWorkouts.isEmpty(), "Keine Pläne auf der Uhr. Mit dem Handy verbinden.") {
        items(ui.state.dayWorkouts, key = { "day-" + it.id }) { day -> ListButton("Workout laden", "Heute: ${day.name}") { ui.beginStart("day", day.id) } }
        items(ui.state.plans, key = { it.id }) { plan -> ListButton(plan.name, plan.detail) { ui.beginStart("plan", plan.id) } }
        item { ListButton("Spontan") { ui.push(Route.START_SPONTANEOUS) } }
    }
}

@Composable
internal fun SpontaneousScreen(ui: UiContext) {
    ListScreen("Übung wählen", ui.state.library.isEmpty(), "Keine Übungen auf der Uhr.") {
        items(ui.state.library, key = { it.id }) { item -> ListButton(item.name, item.detail) { ui.beginStart("spontaneous", item.id) } }
    }
}

/** The gym is mandatory and confirmed explicitly; the last one is preselected and listed first (spec C). */
@Composable
internal fun StartGymScreen(ui: UiContext) {
    val preselected = ui.selection.gym
    val gyms = ui.state.gyms.sortedByDescending { it.id == preselected }
    ListScreen("Studio", gyms.isEmpty(), "Keine Studios auf der Uhr. Mit dem Handy verbinden.") {
        items(gyms, key = { it.id }) { gym ->
            ListButton(gym.name, if (gym.id == preselected) "Voreinstellung" else gym.detail, selected = gym.id == preselected, color = gym.color) {
                ui.selection.gym = gym.id; ui.push(Route.START_MODE)
            }
        }
    }
}

@Composable
internal fun StartModeScreen(ui: UiContext) {
    val key = "${ui.selection.startKind}:${ui.selection.startId}"
    val modes = ui.state.modeOptions[key]?.takeIf { it.isNotEmpty() } ?: ui.state.modes.ifEmpty { DEFAULT_MODES }
    ListScreen("Modus") {
        items(modes, key = { it.id }) { mode ->
            ListButton(mode.name, mode.detail, selected = ui.selection.mode == mode.id) { ui.selection.mode = mode.id; ui.push(Route.START_PACK) }
        }
    }
}

/** Packing list with checkmarks, "Los" fixed at the bottom (spec C). */
@Composable
internal fun StartPackScreen(ui: UiContext) {
    val state = ui.state
    val gym = ui.selection.gym
    val day = if (ui.selection.startKind == "day") ui.selection.startId else ""
    val packItems = (state.packsByGym[gym] ?: state.packItems) + state.packsByDay[day].orEmpty()
    val checked = state.packChecked[gym].orEmpty() + state.packChecked["day:$day"].orEmpty()
    val list = rememberTransformingLazyColumnState()
    val padding = listPadding()
    ScreenScaffold(scrollState = list, contentPadding = padding) { _ ->
        BoxWithConstraints(Modifier.fillMaxSize()) {
            TransformingLazyColumn(state = list, contentPadding = PaddingValues(start = maxWidth * 0.052f, end = maxWidth * 0.052f, top = maxHeight * 0.14f, bottom = maxHeight * 0.42f),
                modifier = Modifier.fillMaxSize().testTag("watch-list"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item { ListHeader { Text("Packliste ${checked.count { id -> packItems.any { it.id == id } }}/${packItems.size}", color = Muted, fontFamily = Hanken) } }
                if (packItems.isEmpty()) item { CenterText("Nichts einzupacken") }
                items(packItems, key = { it.id }) { item ->
                    ListButton((if (item.id in checked) "☑  " else "☐  ") + item.name, description = item.name) { ui.dispatch(WatchAction.TogglePack(gym, day, item.id)) }
                }
            }
            Box(Modifier.align(Alignment.TopCenter).offset(y = maxHeight * 0.80f - 28.dp)) { RoundButton("Los", "Los") { ui.startWorkout() } }
        }
    }
}

// ---- progress and history -------------------------------------------------------------------------------------------

@Composable
internal fun ProgressScreen(ui: UiContext) {
    ListScreen("Fortschritt & Verlauf") {
        item { ListButton("Trainingshäufigkeit") { ui.push(Route.FREQUENCY) } }
        item { ListButton("Arbeitsgewichte") { ui.push(Route.WEIGHTS) } }
        item { ListButton("Bestleistungen") { ui.push(Route.BESTS) } }
        item { ListButton("Verlauf") { ui.push(Route.HISTORY) } }
    }
}

@Composable
internal fun RowsScreen(title: String, rows: List<WatchProgressRow>, emptyText: String) {
    ListScreen(title, rows.isEmpty(), emptyText) { items(rows, key = { it.id }) { InfoCard(it.title, it.detail) } }
}

@Composable
internal fun HistoryScreen(ui: UiContext) {
    ListScreen("Verlauf", ui.state.history.isEmpty(), "Noch keine abgeschlossenen Trainings.") {
        items(ui.state.history, key = { it.id }) { entry ->
            ListButton(entry.title, entry.detail.substringBefore(" · ")) { ui.selection.history = entry.id; ui.push(Route.HISTORY_DETAIL) }
        }
    }
}

@Composable
internal fun HistoryDetailScreen(ui: UiContext) {
    val entry = ui.state.history.firstOrNull { it.id == ui.selection.history }
    ListScreen(entry?.title ?: "Training", entry == null, "Training nicht gefunden.") {
        if (entry != null) {
            item { InfoCard(entry.title, entry.detail) }
            items(entry.sections) { section -> InfoCard(section.first, section.second) }
        }
    }
}

// ---- J: settings, health, connection ----------------------------------------------------------------------------------

@Composable
internal fun SettingsScreen(ui: UiContext) {
    val state = ui.state
    ListScreen("Einstellungen") {
        item { SwitchRow("Dauer am Rand", state.durationRingEnabled, "Ring im Training, eine Runde = 60 min") { ui.dispatch(WatchAction.SetDurationRing(it)) } }
        item { SwitchRow("Puls bei jedem Training aufzeichnen", state.heartAutoConsent == true) { ui.dispatch(WatchAction.SetHeartAuto(it)) } }
        // The navigation recorder always runs; only its send button lives here (see docs/navrec.md).
        item { RecorderSettings() }
        item { ListButton("Gesundheit") { ui.push(Route.HEALTH) } }
        item { ListButton("Verbindung", state.connection) { ui.push(Route.CONNECTION) } }
        item { InfoCard("Uhr-App-Update", state.updateStatus) }
        item { ListButton("Nach Update suchen") { ui.dispatch(WatchAction.CheckUpdate) } }
        if (state.updateCanInstall && state.sessionActive) item { InfoCard("Update bereit", "Nach dem Training installieren") }
        else if (state.updateCanInstall) item { ListButton("Update installieren", selected = true) { ui.dispatch(WatchAction.InstallUpdate) } }
    }
}

@Composable
internal fun HealthScreen(ui: UiContext) {
    val state = ui.state
    val pulse = state.pulse
    ListScreen("Gesundheit") {
        item { InfoCard("Puls", pulse?.let { p -> listOfNotNull(p.label, p.detail).joinToString(" · ") } ?: if (!state.pulsePermission) "Erlaubnis fehlt" else if (state.bpm == null) "Kein Messwert" else "♥ ${state.bpm}") }
        if (pulse?.state == PulseState.MEASURING || (pulse == null && state.bpm != null)) item { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { PulseCurve(state.pulseSamples) } }
        if (state.sessionActive && (pulse?.canStart ?: true)) item {
            ListButton(if (pulse?.state == PulseState.PERMISSION_MISSING || !state.pulsePermission) "Erlauben und starten" else "Puls starten", selected = true) { ui.dispatch(WatchAction.StartRecording) }
        }
        item { SwitchRow("Bei jedem Training aufzeichnen", state.heartAutoConsent == true, if (state.heartAutoConsent == null) "Einmal zustimmen" else "") { ui.dispatch(WatchAction.SetHeartAuto(it)) } }
        item { InfoCard("Aufzeichnung", state.recording) }
        if (state.testProfile) item { InfoCard("Gesundheitsdaten", "Testprofil: nur lokal, kein Export") }
        else {
            item { InfoCard("Export in Health Connect", state.export) }
            item { InfoCard("Handy-Gesundheitsdaten", when (state.phoneHealthEnabled) { true -> if (state.phoneHealthBackground == true) "Einwilligung erteilt · Hintergrundzugriff erlaubt" else "Einwilligung erteilt · Hintergrundzugriff nicht bestätigt"; false -> "Keine Einwilligung"; null -> "Status unbekannt" }) }
            item { ListButton("Auf Handy einrichten") { ui.dispatch(WatchAction.OpenPhoneHealthSettings) } }
        }
    }
}

@Composable
internal fun ConnectionScreen(ui: UiContext) {
    val state = ui.state
    ListScreen("Verbindung") {
        item { InfoCard(state.connection, state.pendingChangesLabel ?: "Keine Änderungen offen") }
        if (state.testProfile) item { InfoCard("Testprofil", "Daten werden nicht synchronisiert und nicht exportiert.", Warning) }
        state.conflict?.let { message ->
            // Conflicts never block the training (spec L).
            item { InfoCard("Konflikt", message, Warning) }
            item { ListButton("Neueste", "Neuesten Stand übernehmen") { ui.dispatch(WatchAction.Resolve(true)) } }
            item { ListButton("Meine", "Stand dieser Uhr behalten") { ui.dispatch(WatchAction.Resolve(false)) } }
        }
        item { ListButton("Erneut") { ui.dispatch(WatchAction.Retry) } }
    }
}

// ---- N: weight increase and summary ------------------------------------------------------------------------------------

@Composable
internal fun CelebrationScreen(ui: UiContext) {
    val list = ui.state.celebrations
    val index = ui.celebration.intValue
    val c = list.getOrNull(index)
    if (c == null) { SummaryScreen(ui); return }
    val track = LocalInteraction.current
    val accent = LocalAccent.current
    fun next() { if (index + 1 >= list.size) ui.reset(Route.SUMMARY) else ui.celebration.intValue = index + 1 }
    ScreenScaffold { _ ->
        BoxWithConstraints(Modifier.fillMaxSize().clickable { track("weiter"); next() }.semantics { contentDescription = "Weiter" }) {
            EdgeRing(1f)
            Column(Modifier.align(Alignment.Center).padding(horizontal = maxWidth * 0.12f), horizontalAlignment = Alignment.CenterHorizontally) {
                Small("Neues Gewicht", color = accent.color)
                Title(c.exercise, size = 16)
                Small(c.from + " →", size = 13)
                Title(c.to, size = 30, color = accent.color, maxLines = 1)
                Small("${index + 1} von ${list.size}", size = 11)
            }
            Box(Modifier.align(Alignment.TopCenter).offset(y = maxHeight * 0.76f)) { RoundButton("»", "Überspringen", primary = false, size = 48.dp) { ui.reset(Route.SUMMARY) } }
        }
    }
}

@Composable
internal fun SummaryScreen(ui: UiContext) {
    val state = ui.state
    ListScreen("Geschafft") {
        state.summary?.let { s ->
            item { InfoCard("Volumen", s.volume) }
            item { InfoCard("Sätze", s.sets) }
            item { InfoCard("Dauer", s.duration) }
            item { InfoCard("Puls Ø / max", s.pulse) }
        }
        item { InfoCard(state.connection, state.pendingChangesLabel ?: "Alles übertragen") }
        item { InfoCard("Gesundheit", if (state.testProfile) "Testprofil: kein Export" else state.export) }
        if (state.undoAvailable) item { ListButton("Rückgängig", "Training wieder öffnen") { ui.dispatch(WatchAction.Undo) } }
        item { ListButton("Fertig", selected = true) { ui.celebration.intValue = 0; ui.reset(Route.HOME) } }
    }
}

// ---- I: system text input --------------------------------------------------------------------------------------------

private const val NOTE_KEY = "note"

/** Opens the system's dictation/keyboard (RemoteInput); the result is appended (spec I). */
@Composable
internal fun rememberNoteInput(onText: (String) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = result.data?.let { RemoteInput.getResultsFromIntent(it)?.getCharSequence(NOTE_KEY)?.toString() }
        if (!text.isNullOrBlank()) onText(text)
    }
    return {
        val input = RemoteInput.Builder(NOTE_KEY).setLabel("Notiz").build()
        val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
        RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(input))
        runCatching { launcher.launch(intent) }
    }
}

// ---- M: always-on display ---------------------------------------------------------------------------------------------

/**
 * Mostly black, grey outlines at the same place as in the active view; no own pixel shift (the system does it).
 * Seconds and pulse change faster than the about once-a-minute ambient updates, so they become minutes or "–".
 */
@Composable
internal fun AmbientScreen(state: WatchPresentation, phase: SetPhase) {
    val current = state.current
    ScreenScaffold { _ ->
        BoxWithConstraints(Modifier.fillMaxSize()) {
            Column(Modifier.align(Alignment.Center).padding(horizontal = maxWidth * 0.12f), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (phase == SetPhase.REST) {
                    Small(if (state.exerciseFinished) "Übung fertig" else "Pause", color = Outline)
                    AmbientText(ambientMinutes(state.restRemainingSeconds ?: 0), size = 22)
                    Small("Als Nächstes", color = Outline, size = 11)
                    Small(if (state.exerciseFinished) "nächste Übung auf Befehl" else state.nextSet?.valueText.orEmpty(), color = Outline)
                } else {
                    Small(current?.exerciseName.orEmpty(), color = Outline)
                    AmbientText(current?.let { "Satz ${it.number}/${it.total}" }.orEmpty())
                    Small(current?.let { setValue(it.weight, it.amount, it.unit, it.weightLabel) }.orEmpty(), color = Outline)
                }
            }
        }
    }
}

/** Send button of the always-on navigation recorder; no values or note texts are ever recorded. */
@Composable
internal fun RecorderSettings() {
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf("") }
    ListButton("Aufzeichnung ans Handy senden", message, description = "Aufzeichnung senden") {
        message = "Sende …"
        scope.launch {
            message = try {
                if (NavRecorder.export() > 0) "Gesendet" else "Nichts Neues zu senden"
            } catch (e: Exception) { "Senden fehlgeschlagen, bitte erneut tippen" }
        }
    }
}
