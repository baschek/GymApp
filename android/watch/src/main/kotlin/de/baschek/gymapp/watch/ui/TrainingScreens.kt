package de.baschek.gymapp.watch.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.pager.HorizontalPager
import androidx.wear.compose.foundation.pager.VerticalPager
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.material3.AnimatedPage
import androidx.wear.compose.material3.HorizontalPageIndicator
import androidx.wear.compose.material3.HorizontalPagerScaffold
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import de.baschek.gymapp.core.training.effortChoices
import de.baschek.gymapp.watch.R
import kotlinx.coroutines.delay
import kotlin.math.abs

/*
 * Training (spec B, D-H, J): three pages, the set in the middle. The middle page shows the phase of the workout
 * (set, effort picker, rest, exercise finished, device setup); it changes only with real events.
 */

/** Top edge of the exercise title on the set page, as a fraction of the screen height (below the curved time). */
private const val SET_TITLE_TOP = 0.14f

private val QUICK_NOTES = listOf("Sitz +1", "Sitz −1", "Schmerz", "Gerät besetzt", "Griff gewechselt")

@Composable
internal fun TrainingPager(ui: UiContext) {
    HorizontalPagerScaffold(pagerState = ui.pager, pageIndicator = { HorizontalPageIndicator(ui.pager) }) {
        HorizontalPager(state = ui.pager, modifier = Modifier.fillMaxSize().testTag("training-pager")) { page ->
            AnimatedPage(pageIndex = page, pagerState = ui.pager) {
                when (page) {
                    TrainingPage.LEFT -> LeftPage(ui)
                    TrainingPage.RIGHT -> RightPage(ui)
                    else -> MiddlePage(ui)
                }
            }
        }
    }
}

@Composable
private fun MiddlePage(ui: UiContext) {
    when (ui.phase) {
        SetPhase.SET -> SetPage(ui)
        SetPhase.EFFORT -> EffortPicker(ui)
        SetPhase.REST -> RestPage(ui)
        SetPhase.DONE -> ExerciseDonePage(ui)
        SetPhase.SETUP -> SetupPage(ui)
        SetPhase.EMPTY -> ListScreen("Training") { item { CenterText("Kein offener Satz") } }
    }
}

/** Primary round button low in the middle; with [left] (or [reserveLeft]) a 48-dp slot to its left that never moves it. */
@Composable
private fun BoxWithConstraintsScope.PrimaryRow(center: Dp, left: (@Composable () -> Unit)?, reserveLeft: Boolean = left != null, primary: @Composable () -> Unit) {
    if (!reserveLeft) Box(Modifier.align(Alignment.TopCenter).offset(y = center - 28.dp)) { primary() }
    else Row(Modifier.align(Alignment.TopCenter).offset(y = center - 28.dp).height(56.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { left?.invoke() }
        primary()
    }
}

// ---- D: set ------------------------------------------------------------------------------------------------------

@Composable
private fun SetPage(ui: UiContext) {
    val state = ui.state
    val current = state.current ?: return
    val pulseStart = state.pulse?.canStart == true
    val elapsed = state.timedElapsedSeconds
    var titleLines by remember(current.exerciseName) { mutableIntStateOf(1) }
    ScreenScaffold { _ ->
        BoxWithConstraints(Modifier.fillMaxSize()) {
            if (state.durationRingEnabled && state.durationRing != null) EdgeRing(state.durationRing, color = Muted, width = 3.dp)
            val rowCenter = maxHeight * (if (pulseStart) 0.74f else 0.80f)
            // Round-safe title: it starts below the curved time and is no wider than the chord of the circle at its top edge.
            val top = maxHeight * SET_TITLE_TOP
            val width = (minOf(maxWidth, maxHeight) * chordFraction(SET_TITLE_TOP) - 8.dp).coerceAtLeast(0.dp)
            val progress = if (current.isDuration && elapsed != null) "Zeit ${clock(elapsed)}" else "Satz ${current.number} von ${current.total}"
            Column(Modifier.align(Alignment.TopCenter).padding(top = top).width(width).height(rowCenter - 30.dp - top),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                FitTitle(current.exerciseName, Modifier.fillMaxWidth().testTag("set-title"), maxLines = 2) { titleLines = it }
                // A two-line name leaves no room for a separate target line: it joins the progress line.
                if (titleLines > 1) Small(listOf(progress, current.target).filter { it.isNotBlank() }.joinToString(" · "), size = 11, maxLines = 1)
                else Small(progress)
                Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    if (current.hasWeight) {
                        ValueButton(ui.draft.total(current)?.let(::formatWeight) ?: "–", current.weightLabel, "Gewicht ändern") { ui.openEditor(EditorKind.WEIGHT) }
                        Text("×", color = Muted, fontSize = 16.sp, modifier = Modifier.padding(horizontal = 2.dp))
                    }
                    ValueButton(formatWeight(if (current.isDuration && elapsed != null) elapsed.toDouble() else ui.draft.amount), current.unit, "${current.unit} ändern") { ui.openEditor(EditorKind.AMOUNT) }
                }
                if (titleLines <= 1) Small(current.target, size = 11, maxLines = 1)
            }
            val heart: (@Composable () -> Unit)? = if (pulseStart) {
                { RoundButton("", "Puls starten", primary = false, size = 48.dp, icon = R.drawable.ic_favorite_outline) { ui.dispatch(WatchAction.StartRecording) } }
            } else null
            PrimaryRow(rowCenter, heart) {
                when {
                    current.isDuration && elapsed == null -> RoundButton("", "Zeit starten", icon = R.drawable.ic_play) { ui.dispatch(WatchAction.StartSetTimer) }
                    current.isDuration -> RoundButton("", "Stopp und speichern", icon = R.drawable.ic_stop) { ui.check(elapsed!!.coerceAtLeast(1).toDouble()) }
                    else -> RoundButton("", "Satz geschafft", icon = R.drawable.ic_check) { ui.check(ui.draft.amount) }
                }
            }
        }
    }
}

// ---- E: effort picker --------------------------------------------------------------------------------------------

@Composable
private fun EffortPicker(ui: UiContext) {
    val effort = ui.effort
    val choices = effortChoices(effort.target)
    val requester = remember { FocusRequester() }
    var accumulated by remember { mutableFloatStateOf(0f) }
    var remaining by remember { mutableIntStateOf(5) }
    LaunchedEffect(effort.interaction.intValue) {
        remaining = 5
        while (remaining > 0) { delay(1_000); remaining-- }
    }
    LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }
    ScreenScaffold { _ ->
        BoxWithConstraints(Modifier.fillMaxSize().focusRequester(requester).onRotaryScrollEvent { event ->
            // The bezel moves the selection; it counts as the person's choice (spec E) and restarts the 5 s.
            accumulated += event.verticalScrollPixels
            if (abs(accumulated) >= 24f) {
                val index = choices.indexOfFirst { it.band == effort.selected }.coerceAtLeast(0)
                val next = (index + if (accumulated > 0) 1 else -1).coerceIn(0, choices.lastIndex)
                effort.selected = choices[next].band
                effort.userMoved = true
                effort.interaction.intValue++
                accumulated = 0f
            }
            true
        }.focusable()) {
            Small("Wie anstrengend?", Modifier.align(Alignment.TopCenter).padding(top = maxHeight * 0.13f), color = androidx.compose.ui.graphics.Color.White, size = 13)
            Column(Modifier.align(Alignment.Center), verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                choices.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        row.forEach { choice -> Chip(choice.label, choice.band == effort.selected, 72.dp) { ui.commitEffort(choice.band) } }
                    }
                }
            }
            Small(if (effort.userMoved) "Gewählt in $remaining s" else "Vorauswahl in $remaining s",
                Modifier.align(Alignment.TopCenter).padding(top = maxHeight * 0.80f), size = 11)
        }
    }
}

// ---- F: rest -----------------------------------------------------------------------------------------------------

@Composable
private fun RestPage(ui: UiContext) {
    // Swipe up: second level (+15 s, -15 s, undo, one more set); swipe down closes it (spec F).
    key(ui.state.navigationKey) {
        val vertical = rememberPagerState(pageCount = { 2 })
        VerticalPager(state = vertical, modifier = Modifier.fillMaxSize().testTag("rest-pager")) { page ->
            if (page == 0) RestMain(ui) else RestMore(ui)
        }
    }
}

@Composable
private fun RestMain(ui: UiContext) {
    val state = ui.state
    val track = LocalInteraction.current
    val accent = LocalAccent.current
    val seconds = state.restRemainingSeconds ?: 0
    val over = seconds <= 0
    val current = state.current
    ScreenScaffold { _ ->
        BoxWithConstraints(Modifier.fillMaxSize()) {
            EdgeRing(if (state.restTotalSeconds > 0) (seconds.toFloat() / state.restTotalSeconds).coerceIn(0f, 1f) else 0f)
            val rowCenter = maxHeight * 0.75f
            val top = maxHeight * 0.10f
            val next = state.nextSet
            val nextText = when {
                state.exerciseFinished -> "nächste Übung auf Befehl"
                next == null -> ""
                else -> next.valueText
            }
            // Two 48-dp targets (countdown, suggestion) may need more than the slot: the content is allowed to overflow it.
            Box(Modifier.align(Alignment.TopCenter).padding(top = top).fillMaxWidth().padding(horizontal = maxWidth * 0.12f).height(rowCenter - 30.dp - top), contentAlignment = Alignment.Center) {
              Column(Modifier.wrapContentHeight(align = Alignment.CenterVertically, unbounded = true),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                // Countdown: tap opens the rest editor (+-15 s with bezel or buttons).
                Column(Modifier.defaultMinSize(minWidth = 96.dp, minHeight = 48.dp).clickable { track("pause_aendern"); ui.openEditor(EditorKind.REST) }
                    .semantics(mergeDescendants = true) { contentDescription = "Pause ändern, " + clock(seconds) + if (state.exerciseFinished) ", Übung fertig" else ", als Nächstes $nextText" },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Small(if (state.exerciseFinished) "Übung fertig" else if (over) "Los" else "Pause", size = 11, color = if (over) accent.color else Muted)
                    Title(clock(seconds), size = 30, maxLines = 1, color = if (over) accent.color else Paper)
                }
                if (state.exerciseFinished) Small("Danach: nächste Übung auf Befehl", size = 11)
                else if (next != null) {
                    val suggestion = next.suggestionText?.takeIf { state.suggestedWeight != null && current != null && next.setId == current.setId }
                    val accepted = suggestion != null && current != null && ui.draft.edited &&
                        abs((ui.draft.total(current) ?: -1.0) - (next.hint?.weightKg ?: -2.0)) < 1e-6
                    val head = if (next.newExercise) next.exerciseName else "Als Nächstes · " + if (next.warmup) "Aufwärmsatz" else "Satz ${next.setNumber} von ${next.setTotal}"
                    val clickable = if (suggestion != null && !accepted) Modifier.defaultMinSize(minWidth = 96.dp, minHeight = 48.dp)
                        .clickable { track("vorschlag_uebernehmen"); ui.acceptSuggestion() }
                        .semantics(mergeDescendants = true) { contentDescription = "Vorschlag übernehmen: $suggestion" } else Modifier
                    Column(clickable, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Small(head, size = 11, maxLines = 1, marquee = true)
                        if (next.newExercise && next.setupText.isNotBlank()) Small(next.setupText, color = Paper, size = 12, maxLines = 1, marquee = true)
                        when {
                            accepted -> Small("${formatWeight(ui.draft.total(current!!) ?: 0.0)} kg übernommen", color = accent.color, size = 13, maxLines = 1)
                            suggestion != null -> Small(suggestion, color = accent.color, size = 13, maxLines = 1)
                            else -> Small(next.valueText, color = Paper, size = 13, maxLines = 1)
                        }
                    }
                }
              }
            }
            val undo: (@Composable () -> Unit)? = if (ui.undoVisible && state.undoAvailable) {
                { RoundButton("", "Rückgängig", primary = false, size = 48.dp, icon = R.drawable.ic_undo) { ui.dispatch(WatchAction.Undo) } }
            } else null
            // "Weiter" never moves: the undo slot is always reserved. The watch never goes on by itself (spec A5).
            PrimaryRow(rowCenter, undo, reserveLeft = true) {
                RoundButton("", "Weiter", icon = R.drawable.ic_play) { ui.dispatch(WatchAction.SkipRest) }
            }
        }
    }
}

@Composable
private fun RestMore(ui: UiContext) {
    val current = ui.state.current
    ListScreen("Pause", tag = "rest-more") {
        item { ListButton("+15 s", description = "Plus 15 s") { ui.dispatch(WatchAction.ChangeRest(15)) } }
        item { ListButton("−15 s", description = "Minus 15 s") { ui.dispatch(WatchAction.ChangeRest(-15)) } }
        item { ListButton("Rückgängig", "Letzten Satz", enabled = ui.state.undoAvailable) { ui.dispatch(WatchAction.Undo) } }
        item { ListButton("Noch ein Satz", current?.exerciseName.orEmpty(), enabled = current != null) { current?.let { ui.dispatch(WatchAction.AddSet(it.exerciseId)) } } }
    }
}

// ---- G: exercise finished and device setup ----------------------------------------------------------------------

@Composable
private fun ExerciseDonePage(ui: UiContext) {
    val state = ui.state
    val current = state.current
    val next = state.nextExerciseName
    val skipped = state.exercises.count { it.skipped }
    ListScreen("Übung fertig") {
        item { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Title(current?.exerciseName.orEmpty(), size = 16) } }
        item { CenterText("${state.sets.size} Sätze gespeichert") }
        if (next != null) item { ListButton("Nächste Übung: $next", selected = true, description = "Nächste Übung") { ui.dispatch(WatchAction.NextExercise) } }
        else item { ListButton("Training beenden", selected = true) { ui.push(Route.END_CONFIRM) } }
        item { ListButton("Noch ein Satz", enabled = current != null) { current?.let { ui.dispatch(WatchAction.AddSet(it.exerciseId)) } } }
        if (next == null && skipped > 0) item { CenterText("$skipped übersprungen") }
    }
}

@Composable
private fun SetupPage(ui: UiContext) {
    val state = ui.state
    val remaining = state.setupRemainingSeconds ?: 0
    ScreenScaffold { _ ->
        BoxWithConstraints(Modifier.fillMaxSize()) {
            EdgeRing(remaining.coerceAtLeast(0) / 45f)
            val rowCenter = maxHeight * 0.80f
            val top = maxHeight * 0.12f
            Column(Modifier.align(Alignment.TopCenter).padding(top = top).fillMaxWidth().padding(horizontal = maxWidth * 0.1f).height(rowCenter - 30.dp - top),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Small("Gerät einstellen", size = 11)
                Title(state.current?.exerciseName.orEmpty(), size = 14, marquee = true)
                Title(state.current?.setup.orEmpty(), size = 19, color = LocalAccent.current.color, maxLines = 2)
                Small(clock(remaining), size = 12)
            }
            // The timer never closes the screen by itself, also not at 0 (spec G).
            PrimaryRow(rowCenter, null) { RoundButton("", "Fertig", icon = R.drawable.ic_check) { ui.dispatch(WatchAction.FinishSetup) } }
        }
    }
}

// ---- H: left page -----------------------------------------------------------------------------------------------

private fun statusSymbol(row: WatchExerciseRow): String = when {
    row.skipped -> "⊘"
    row.current -> "●"
    row.completed -> "✓"
    else -> "○"
}

@Composable
private fun LeftPage(ui: UiContext) {
    val state = ui.state
    val current = state.current
    val open = current != null && current.setId.isNotEmpty()
    ListScreen("Übungen", state.exercises.isEmpty(), "Keine Übungen", tag = "training-left") {
        items(state.exercises, key = { it.id }) { ex ->
            ListButton("${statusSymbol(ex)}  ${ex.name}", "${ex.status} · ${ex.detail}", selected = ex.current, description = ex.name) {
                ui.selection.menuExercise = ex.id; ui.push(Route.EXERCISE_MENU)
            }
        }
        item { SectionHeader("Dieser Satz") }
        item { ListButton("Aufwärmsatz", "zählt nicht als Arbeitssatz", enabled = current != null) { current?.let { ui.dispatch(WatchAction.Warmup(it.exerciseId)) } } }
        item { ListButton("Notiz", enabled = current != null) { ui.push(Route.NOTE) } }
        item { ListButton("Satz überspringen", enabled = open) { current?.let { ui.dispatch(WatchAction.SkipSet(it.exerciseId, it.setId)) } } }
        item { ListButton("Satz hinzufügen", enabled = current != null) { current?.let { ui.dispatch(WatchAction.AddSet(it.exerciseId)) } } }
        item { ListButton("Bisherige Sätze", "${state.sets.size}", enabled = current != null) { ui.push(Route.SETS) } }
        if (current?.isDuration == true && open) item { ListButton("Zeitwert speichern", setValue(null, ui.draft.amount, current.unit, "")) { ui.check(ui.draft.amount) } }
        item { SectionHeader("Training") }
        item { ListButton("Übung hinzufügen") { ui.push(Route.ADD_EXERCISE) } }
        item { ListButton("Rückgängig", "Letzte Aktion", enabled = state.undoAvailable) { ui.dispatch(WatchAction.Undo) } }
    }
}

@Composable
internal fun ExerciseMenu(ui: UiContext) {
    val rows = ui.state.exercises
    val index = rows.indexOfFirst { it.id == ui.selection.menuExercise }
    val ex = rows.getOrNull(index)
    if (ex == null) { ListScreen("Übung") { item { CenterText("Übung nicht mehr im Training") } }; return }
    // Actions that would change nothing are disabled instead of failing (spec H, L).
    ListScreen(ex.name) {
        item { CenterText("${ex.status} · ${ex.detail}") }
        item { ListButton("Jetzt", "vorziehen", enabled = !ex.current && !ex.completed) { ui.dispatch(WatchAction.Focus(ex.id)) } }
        item { ListButton(if (ex.skipped) "Wieder aufnehmen" else "Überspringen", enabled = ex.skipped || !ex.completed) { ui.dispatch(WatchAction.ToggleExercise(ex.id, ex.skipped)); ui.back() } }
        item { ListButton("Nach oben", enabled = index > 0) { ui.dispatch(WatchAction.Move(ex.id, -1)) } }
        item { ListButton("Nach unten", enabled = index < rows.lastIndex) { ui.dispatch(WatchAction.Move(ex.id, 1)) } }
    }
}

@Composable
internal fun SetsScreen(ui: UiContext) {
    val state = ui.state
    ListScreen(state.current?.exerciseName ?: "Sätze", state.sets.isEmpty(), "Noch kein Satz.") {
        items(state.sets, key = { it.id }) { row ->
            ListButton("${row.label}  ${row.value}", row.effort) {
                ui.selection.setEdit = row.id; ui.selection.editWeight = row.weight; ui.selection.editAmount = row.amount
                ui.push(Route.SET_EDIT)
            }
        }
    }
}

@Composable
internal fun SetEditScreen(ui: UiContext) {
    val row = ui.state.sets.firstOrNull { it.id == ui.selection.setEdit }
    val current = ui.state.current
    if (row == null || current == null) { ListScreen("Satz") { item { CenterText("Satz nicht mehr vorhanden") } }; return }
    ScreenScaffold { _ ->
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val rowCenter = maxHeight * 0.74f
            val top = maxHeight * 0.12f
            Column(Modifier.align(Alignment.TopCenter).padding(top = top).fillMaxWidth().height(rowCenter - 30.dp - top),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Small("Satz bearbeiten · ${row.label}")
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    if (row.weight != null) {
                        ValueButton(formatWeight(ui.selection.editWeight ?: 0.0), "kg", "Gewicht ändern") { ui.openEditor(EditorKind.SET_WEIGHT) }
                        Text("×", color = Muted, fontSize = 16.sp)
                    }
                    ValueButton(formatWeight(ui.selection.editAmount), row.unit, "Wert ändern") { ui.openEditor(EditorKind.SET_AMOUNT) }
                }
            }
            PrimaryRow(rowCenter, { RoundButton("", "Löschen", primary = false, size = 48.dp, icon = R.drawable.ic_close) { ui.dispatch(WatchAction.DeleteSet(current.exerciseId, row.id)); ui.back() } }) {
                RoundButton("", "Änderung speichern", icon = R.drawable.ic_check) { ui.dispatch(WatchAction.CorrectSet(current.exerciseId, row.id, ui.selection.editWeight, ui.selection.editAmount, row.effort)); ui.back() }
            }
        }
    }
}

@Composable
internal fun AddExerciseScreen(ui: UiContext) {
    ListScreen("Übung hinzufügen", ui.state.library.isEmpty(), "Keine Übungen auf der Uhr.") {
        items(ui.state.library, key = { it.id }) { item -> ListButton(item.name, item.detail) { ui.dispatch(WatchAction.AddExercise(item.id)); ui.back() } }
    }
}

// ---- I: note ----------------------------------------------------------------------------------------------------

/** Appends a quick text to an existing note; never overwrites (spec I). */
internal fun appendNote(existing: String, addition: String): String =
    listOf(existing.trim(), addition.trim()).filter { it.isNotEmpty() }.joinToString(" · ")

@Composable
internal fun NoteScreen(ui: UiContext) {
    val current = ui.state.current
    if (current == null) { ListScreen("Notiz") { item { CenterText("Kein Training") } }; return }
    val append: (String) -> Unit = { text -> ui.dispatch(WatchAction.SetNote(current.exerciseId, appendNote(current.exerciseNote, text))) }
    val speak = rememberNoteInput(append)
    ListScreen("Notiz · ${current.exerciseName}") {
        if (current.setup.isNotBlank()) item { InfoCard("Einstellung", current.setup) }
        item { InfoCard("Notiz", current.note.ifBlank { "Keine Notiz" }) }
        item { ListButton("Sprechen oder tippen") { speak() } }
        item { SectionHeader("Anhängen") }
        items(QUICK_NOTES, key = { it }) { text -> ListButton(text, description = "Anhängen $text") { append(text) } }
    }
}

// ---- J: right page and end of training -----------------------------------------------------------------------------

@Composable
private fun RightPage(ui: UiContext) {
    val state = ui.state
    ListScreen("Training", tag = "training-right") {
        // Restrained, no warning colour; asks before ending (spec J).
        item { ListButton("Training beenden") { ui.push(Route.END_CONFIRM) } }
        item { ListButton("Auf Handy öffnen") { ui.dispatch(WatchAction.OpenPhone) } }
        item { ListButton("Gesundheit", state.pulse?.label ?: state.recording) { ui.push(Route.HEALTH) } }
        item { ListButton("Verbindung", state.pendingChangesLabel ?: state.connection) { ui.push(Route.CONNECTION) } }
        item { ListButton("Einstellungen") { ui.push(Route.SETTINGS) } }
    }
}

@Composable
internal fun EndConfirmScreen(ui: UiContext) {
    ScreenScaffold { _ ->
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val rowCenter = maxHeight * 0.74f
            val top = maxHeight * 0.14f
            Column(Modifier.align(Alignment.TopCenter).padding(top = top).fillMaxWidth().padding(horizontal = maxWidth * 0.1f).height(rowCenter - 30.dp - top),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Title("Training beenden?", size = 16)
                Small("Offene Sätze werden übersprungen.")
            }
            PrimaryRow(rowCenter, { RoundButton("", "Abbrechen", primary = false, size = 48.dp, icon = R.drawable.ic_close) { ui.back() } }) {
                RoundButton("", "Beenden", icon = R.drawable.ic_check) { ui.back(); ui.dispatch(WatchAction.Finish) }
            }
        }
    }
}

// ---- D second level: value editor -----------------------------------------------------------------------------------

@Composable
internal fun ValueEditor(ui: UiContext) {
    val kind = EditorKind.entries.firstOrNull { it.name == ui.selection.editor } ?: EditorKind.WEIGHT
    val state = ui.state
    val current = state.current
    val draft = ui.draft
    val sel = ui.selection
    val accent = LocalAccent.current
    val requester = remember { FocusRequester() }
    var accumulated by remember { mutableFloatStateOf(0f) }
    val steps = current?.weightSteps.orEmpty()
    fun stepWeight(w: Double, direction: Int): Double =
        if (steps.isEmpty()) (w + direction * 2.5).coerceAtLeast(0.0)
        else steps[((steps.indices.minByOrNull { abs(steps[it] - w) } ?: 0) + direction).coerceIn(0, steps.lastIndex)]
    fun stepAmount(a: Double, direction: Int): Double =
        (a + direction * if (current?.isDuration == true) 5 else if (current?.unit == "m") 10 else 1).coerceIn(1.0, 999.0)
    fun step(direction: Int) {
        when (kind) {
            EditorKind.WEIGHT -> { draft.weight = stepWeight(draft.weight ?: 0.0, direction); draft.weightEdited = true }
            EditorKind.AMOUNT -> { draft.amount = stepAmount(draft.amount, direction); draft.amountEdited = true }
            EditorKind.REST -> ui.dispatch(WatchAction.ChangeRest(direction * 15))
            EditorKind.SET_WEIGHT -> sel.editWeight = stepWeight(sel.editWeight ?: 0.0, direction)
            EditorKind.SET_AMOUNT -> sel.editAmount = stepAmount(sel.editAmount, direction)
        }
    }
    fun valueOf(kind: EditorKind, delta: Int): String? = when (kind) {
        EditorKind.WEIGHT, EditorKind.SET_WEIGHT -> {
            val w = (if (kind == EditorKind.WEIGHT) draft.weight else sel.editWeight) ?: 0.0
            if (delta == 0) formatWeight(w) else stepWeight(w, delta).takeIf { abs(it - w) > 1e-9 }?.let(::formatWeight)
        }
        EditorKind.AMOUNT, EditorKind.SET_AMOUNT -> {
            val a = if (kind == EditorKind.AMOUNT) draft.amount else sel.editAmount
            if (delta == 0) formatWeight(a) else stepAmount(a, delta).takeIf { abs(it - a) > 1e-9 }?.let(::formatWeight)
        }
        EditorKind.REST -> if (delta == 0) clock(state.restRemainingSeconds ?: 0) else null
    }
    // Only inside the editor the bezel changes values, in real machine steps (spec B, D).
    LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }
    ScreenScaffold { _ ->
        BoxWithConstraints(Modifier.fillMaxSize().focusRequester(requester).onRotaryScrollEvent { event ->
            accumulated += event.verticalScrollPixels
            if (abs(accumulated) >= 20f) { step(if (accumulated > 0) 1 else -1); accumulated = 0f }
            true
        }.focusable()) {
            val h = maxHeight
            val addOns = if (kind == EditorKind.WEIGHT) current?.addOns.orEmpty() else emptyList()
            // Top: add-on chips (up to two) or the label.
            if (addOns.isNotEmpty()) Row(Modifier.align(Alignment.TopCenter).offset(y = h * 0.115f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                addOns.take(2).forEachIndexed { index, value ->
                    val on = index in draft.addOns
                    Chip((if (on) "✓ " else "+ ") + formatWeight(value), on, 56.dp, description = "Zusatzgewicht ${formatWeight(value)}") {
                        draft.addOns = if (on) draft.addOns - index else draft.addOns + index; draft.weightEdited = true
                    }
                }
            } else Small(when (kind) {
                EditorKind.WEIGHT, EditorKind.SET_WEIGHT -> if (steps.isEmpty()) "Gewicht · kein Maschinenprofil" else "Gewicht"
                EditorKind.AMOUNT, EditorKind.SET_AMOUNT -> current?.unit ?: "Wert"
                EditorKind.REST -> "Pause in 15-s-Schritten"
            }, Modifier.align(Alignment.TopCenter).padding(top = h * 0.16f))
            // Middle: - [neighbour / value / neighbour] +
            Row(Modifier.align(Alignment.TopCenter).offset(y = h * 0.505f - 28.dp).fillMaxWidth().padding(horizontal = maxWidth * 0.04f),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                RoundButton("−", "Wert verringern", primary = false, size = 48.dp) { step(-1) }
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                    Small(valueOf(kind, -1) ?: " ", size = 11, maxLines = 1)
                    Text(valueOf(kind, 0).orEmpty(), color = accent.color, fontFamily = Hanken, fontSize = 28.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 1)
                    Small(valueOf(kind, 1) ?: " ", size = 11, maxLines = 1)
                }
                RoundButton("+", "Wert erhöhen", primary = false, size = 48.dp) { step(1) }
            }
            val below = when {
                kind == EditorKind.WEIGHT && current != null && addOns.isNotEmpty() -> "Gesamt ${formatWeight(draft.total(current) ?: 0.0)} kg"
                kind == EditorKind.AMOUNT -> current?.target.orEmpty()
                else -> ""
            }
            Small(below, Modifier.align(Alignment.TopCenter).padding(top = h * 0.655f), size = 11, maxLines = 1)
            Box(Modifier.align(Alignment.TopCenter).offset(y = h * 0.72f)) { RoundButton("", "Fertig", size = 48.dp, icon = R.drawable.ic_check) { ui.back() } }
        }
    }
}
