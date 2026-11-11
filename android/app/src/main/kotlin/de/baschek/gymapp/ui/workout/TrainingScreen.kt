package de.baschek.gymapp.ui.workout

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.baschek.gymapp.core.model.Session
import de.baschek.gymapp.core.model.SessionExercise
import de.baschek.gymapp.core.model.SetStatus
import de.baschek.gymapp.core.model.SetType
import de.baschek.gymapp.core.progression.WEIGHTED_TYPES
import de.baschek.gymapp.core.progression.freeWeights
import de.baschek.gymapp.core.training.NotificationReps
import de.baschek.gymapp.core.training.SetCorrection
import de.baschek.gymapp.core.training.SkipReason
import de.baschek.gymapp.core.training.canAddSet
import de.baschek.gymapp.core.training.hasSkippedSets
import de.baschek.gymapp.core.training.setRows
import de.baschek.gymapp.core.training.viewExerciseIndex
import de.baschek.gymapp.core.training.warmupProposal
import de.baschek.gymapp.core.training.weightHint
import de.baschek.gymapp.telemetry.Telemetry
import de.baschek.gymapp.ui.Navigator
import de.baschek.gymapp.ui.Routes
import de.baschek.gymapp.ui.components.GymField
import de.baschek.gymapp.ui.components.GymIcons
import de.baschek.gymapp.ui.components.GymSheet
import de.baschek.gymapp.ui.components.LinkButton
import de.baschek.gymapp.ui.components.PinnedBottom
import de.baschek.gymapp.ui.components.PrimaryButton
import de.baschek.gymapp.ui.components.SegmentItem
import de.baschek.gymapp.ui.components.Segmented
import de.baschek.gymapp.ui.components.SoftButton
import de.baschek.gymapp.ui.components.Tag
import de.baschek.gymapp.ui.components.ExercisePickerSheet
import de.baschek.gymapp.ui.components.bottomInset
import de.baschek.gymapp.ui.components.topInset
import de.baschek.gymapp.ui.fmtNum
import de.baschek.gymapp.ui.gymColorOf
import de.baschek.gymapp.ui.gymLabel
import de.baschek.gymapp.ui.parseInstant
import de.baschek.gymapp.ui.theme.Spacing
import de.baschek.gymapp.ui.tracked
import de.baschek.gymapp.workout.SetInput
import kotlin.math.abs

private const val SCREEN = "training"

private fun reasonText(ex: SessionExercise, total: Double): String? {
    val suggested = ex.suggestedWeightKg ?: return null
    if (Math.abs(total - suggested) > 1e-6) return "Eigene Wahl. Vorschlag war ${fmtNum(suggested)} kg."
    return when (ex.suggestionReason) {
        "gym_working_weight" -> "Dein Arbeitsgewicht in diesem Studio."
        "gym_history" -> "Wie beim letzten Training hier."
        "cross_gym_estimate" -> "Schätzung aus einem anderen Studio. Vorsichtig testen."
        "guided_estimate" -> "Vorsichtiger Startwert aus einer verwandten Übung."
        "plan_weight" -> "Startgewicht aus dem Plan."
        else -> null
    }
}

/**
 * The set screen, built to fit one display: header, exercise strip (tap = jump), exercise name with the
 * machine setup (tap = edit), the set list (tap a logged set = correct it), the input block with the
 * weight suggestion and the pinned "Satz geschafft" row.
 */
@Composable
internal fun TrainingScreen(sessionId: String, session: Session, vm: WorkoutViewModel, nav: Navigator) {
    val machines by vm.machines.collectAsStateWithLifecycle()
    val gyms by vm.gyms.collectAsStateWithLifecycle()
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val focus by vm.focus.collectAsStateWithLifecycle()
    val undoLabel by vm.undoLabel.collectAsStateWithLifecycle()
    val accepted by vm.acceptedWeight.collectAsStateWithLifecycle()
    val repsOverride by vm.repsOverride.collectAsStateWithLifecycle()
    val bodyWeight by vm.bodyWeight.collectAsStateWithLifecycle()
    val now = rememberNow(1000)

    var sheet by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmFinish by rememberSaveable { mutableStateOf(false) }
    var pickExercise by rememberSaveable { mutableStateOf(false) }

    val exIndex = viewExerciseIndex(session.exercises, focus)
    val ex = session.exercises.getOrNull(exIndex)
    val set = ex?.sets?.firstOrNull { it.status == SetStatus.PENDING }
    val warm = set?.setType == SetType.WARMUP

    val info = ex?.let { machines[it.exerciseId] }
    val levels = info?.levels.orEmpty()
    val addonList = info?.addons.orEmpty()
    val hasProfile = levels.isNotEmpty()
    val wheel = if (hasProfile) levels else remember { freeWeights() }
    val weighted = ex != null && ex.measurementType in WEIGHTED_TYPES
    val acceptedForEx = accepted?.takeIf { ex != null && it.first == ex.id }?.second
    val overrideReps = if (ex != null && set != null) NotificationReps.valueFor(repsOverride, sessionId, ex.id, set.id) else null
    val draft = remember(set?.id, hasProfile, acceptedForEx) { buildDraft(ex, set, levels, addonList, wheel, acceptedForEx, overrideReps) }
    // "−1 / +1" in the notification while this screen is open: the entry follows the shared value (weight edits stay).
    LaunchedEffect(set?.id, overrideReps) { overrideReps?.let { draft.value = it } }

    val hint = remember(ex, info, bodyWeight, warm) {
        if (ex == null || warm || !weighted) null else weightHint(ex, info?.candidates.orEmpty(), info?.workingWeightKg, bodyWeight)
    }

    val workingTotal = ex?.sets?.count { it.setType == SetType.WORKING } ?: 0
    val workingDone = ex?.sets?.count { it.setType == SetType.WORKING && it.status != SetStatus.PENDING } ?: 0

    val logSet = tracked(SCREEN, "log_set") {
        if (ex != null && set != null) {
            vm.logSet(
                ex.id, set.id,
                SetInput(
                    weightKg = if (weighted) draft.total else null,
                    value = draft.value,
                    effort = draft.effortBand(warm),
                    addonWeightsKg = draft.addons.sorted().takeIf { it.isNotEmpty() && weighted },
                ),
            )
        }
    }

    // Height of the pinned bar (buttons + navigation bar inset), measured, so the scroll content always ends above it.
    val density = LocalDensity.current
    var pinnedPx by remember { mutableIntStateOf(0) }
    val pinnedDp = if (pinnedPx > 0) with(density) { pinnedPx.toDp() } else 190.dp + bottomInset()

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(top = topInset() + Spacing.s2, start = Spacing.Gutter, end = Spacing.Gutter, bottom = pinnedDp + Spacing.s6),
            verticalArrangement = Arrangement.spacedBy(Spacing.s5),
        ) {
            val started = parseInstant(session.startedAt)?.toEpochMilli() ?: now
            val position = when {
                ex == null -> "Alle Sätze erledigt"
                set == null -> "Übung ${exIndex + 1}/${session.exercises.size} · erledigt"
                warm -> "Übung ${exIndex + 1}/${session.exercises.size} · Aufwärmsatz"
                else -> "Übung ${exIndex + 1}/${session.exercises.size} · Satz ${minOf(workingDone + 1, workingTotal)}/$workingTotal"
            }
            TrainingHeader(
                planName = session.planName, position = position, gymName = gymLabel(session.gymId, gyms),
                gymColor = gymColorOf(session.gymId, gyms), elapsed = formatElapsed((now - started) / 1000),
                onList = tracked(SCREEN, "open_list") { sheet = "list" },
            )
            ExerciseStrip(session.exercises, ex?.id, onJump = vm::focusExercise, screen = SCREEN)

            if (ex == null) {
                Text(
                    "In diesem Training gibt es keine offenen Sätze.", style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column {
                    Text(ex.name, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 2)
                    SetupLine(info?.setup, tracked(SCREEN, "setup_open") { sheet = "setup" })
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s2), verticalAlignment = Alignment.CenterVertically) {
                        ex.supersetGroup?.takeIf { it.isNotBlank() }?.let { Tag("Superset $it") }
                        if (warm) Tag("Aufwärmsatz")
                    }
                    ex.notes?.takeIf { it.isNotBlank() }?.let {
                        Text(it, Modifier.padding(top = 2.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                SetListCard(
                    ex, onTapSet = { row -> sheet = (if (row.kind == de.baschek.gymapp.core.training.SetRowKind.DONE) "edit:" else "resume:") + row.setId },
                    onAddSet = if (canAddSet(session, ex.id)) ({ vm.addSet(ex.id) }) else null,
                )

                if (set != null) {
                    SetEntryCard(
                        ex = ex, draft = draft, weighted = weighted, warm = warm, levels = levels, addonList = addonList, wheel = wheel,
                        screen = SCREEN,
                        onValueEdited = vm::clearRepsOverride,
                        onCreateProfile = tracked(SCREEN, "create_profile") { session.gymId?.let { nav.push(Routes.machine(it, ex.exerciseId)) } },
                        afterWeight = {
                            if (hint != null) {
                                WeightHintBlock(
                                    hint, applied = abs(draft.total - hint.weightKg) < 1e-6,
                                    onApply = { draft.applyTotal(hint.weightKg, levels, addonList) },
                                    screen = SCREEN, modifier = Modifier.padding(top = Spacing.s4),
                                )
                            } else if (!warm && workingDone == 0) {
                                reasonText(ex, draft.total)?.let {
                                    Text(it, Modifier.padding(top = Spacing.s3), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        },
                    )
                } else {
                    FinishedExerciseCard(
                        ex,
                        onResume = { vm.resumeSkipped(ex.id) },
                        onNext = { vm.focusExercise(null) },
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = Spacing.s2), horizontalArrangement = Arrangement.Center) {
                LinkButton("Training beenden", tracked(SCREEN, "finish_open") { confirmFinish = true }, height = Spacing.TouchMin)
            }
        }

        PinnedBottom(Modifier.align(Alignment.BottomCenter).onSizeChanged { pinnedPx = it.height }) {
            PrimaryButton("Satz geschafft", onClick = logSet, icon = GymIcons.Check, height = 64.dp, enabled = ex != null && set != null)
            val pad = PaddingValues(horizontal = 0.dp)
            // Four equal columns; labels never wrap. Disabled actions stay readable (60 % instead of 40 %).
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                BarAction("Überspringen", tracked(SCREEN, "skip_open") { sheet = "skip" }, set != null, pad, Modifier.weight(1f))
                BarAction("Aufwärmsatz", tracked(SCREEN, "warmup_open") { sheet = "warmup" }, set != null && !warm, pad, Modifier.weight(1f))
                BarAction("Notiz", tracked(SCREEN, "note_open") { sheet = "note" }, ex != null, pad, Modifier.weight(1f))
                BarAction("Rückgängig", tracked(SCREEN, "undo") { vm.undo() }, undoLabel != null, pad, Modifier.weight(1f))
            }
        }
    }

    val current = sheet
    when {
        current == "skip" -> if (ex != null && set != null) SkipSheet(
            onPick = { reason, rest ->
                Telemetry.track(SCREEN, "skip_${reason.name.lowercase()}")
                if (rest) vm.skipRemaining(ex.id, reason) else vm.skipSet(ex.id, set.id, reason)
                sheet = if (reason == SkipReason.PAIN) "pain-note" else null
            },
            onDismiss = { sheet = null },
        )
        current == "pain-note" || current == "note" -> if (ex != null) NoteSheet(
            title = if (current == "pain-note") "Was ist passiert? (Schmerz)" else "Notiz zu ${ex.name}",
            initial = ex.notes.orEmpty(),
            onSave = { vm.setNote(ex.id, it); sheet = null },
            onDismiss = { sheet = null },
        )
        current == "warmup" -> if (ex != null && set != null) {
            val proposal = warmupProposal(lastWorking(ex)?.weightKg ?: ex.suggestedWeightKg ?: levels.minOrNull() ?: 20.0, levels)
            GymSheet({ sheet = null }, title = "Aufwärmsatz") {
                Text(
                    "Leichter Satz vor dem ersten Arbeitssatz, zum Beispiel ${fmtNum(proposal.weightKg)} kg × ${proposal.reps}. Er zählt nicht für den Fortschritt.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PrimaryButton("Aufwärmsatz einfügen", tracked(SCREEN, "warmup_insert") { vm.addWarmup(ex.id); sheet = null }, height = 52.dp)
            }
        }
        current == "list" -> GymSheet({ sheet = null }, title = "Aktuelles Workout") {
            ExerciseListCard(
                session, editable = true, onMove = vm::moveExercise, onToggleSkip = vm::toggleSkipExercise,
                onAdd = { pickExercise = true }, screen = SCREEN,
                onJump = { id -> vm.focusExercise(id); sheet = null }, focusedId = ex?.id, onRemove = vm::removeExercise,
            )
            PrimaryButton("Training beenden", tracked(SCREEN, "finish_open") { sheet = null; confirmFinish = true }, height = 52.dp)
        }
        current == "setup" -> if (ex != null) SetupSheet(
            exerciseName = ex.name, initial = info?.setup, screen = SCREEN,
            onSave = { params ->
                session.gymId?.let { vm.saveSetup(it, ex.exerciseId, params) }
                sheet = null
            },
            onDismiss = { sheet = null },
        )
        current != null && current.startsWith("edit:") -> {
            val setId = current.removePrefix("edit:")
            val target = ex?.sets?.firstOrNull { it.id == setId }
            if (ex != null && target != null && target.status == SetStatus.COMPLETED) {
                CorrectSetSheet(
                    ex = ex, set = target, levels = levels, addonList = addonList, wheel = wheel, weighted = weighted,
                    onSave = { vm.correctSet(ex.id, target.id, it); sheet = null },
                    onReopen = { vm.reopenSet(ex.id, target.id); sheet = null },
                    onDismiss = { sheet = null },
                )
            }
        }
        current != null && current.startsWith("resume:") -> {
            val setId = current.removePrefix("resume:")
            if (ex != null && ex.sets.any { it.id == setId }) {
                GymSheet({ sheet = null }, title = "Satz wieder aufnehmen") {
                    Text(
                        "Der Satz wird wieder offen und kommt in der Reihenfolge der Sätze dran.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    PrimaryButton("Satz wieder aufnehmen", tracked(SCREEN, "resume_set") { vm.reopenSet(ex.id, setId); sheet = null }, height = 52.dp)
                    if (ex.sets.count { it.status != SetStatus.PENDING && it.status != SetStatus.COMPLETED } > 1) {
                        SoftButton("Alle übersprungenen Sätze aufnehmen", tracked(SCREEN, "resume_all") { vm.resumeSkipped(ex.id); sheet = null }, Modifier.fillMaxWidth(), height = 52.dp)
                    }
                }
            }
        }
    }
    if (pickExercise) {
        ExercisePickerSheet(catalog, onPick = { vm.addExercise(it.id); pickExercise = false }, onDismiss = { pickExercise = false })
    }
    if (confirmFinish) {
        AlertDialog(
            onDismissRequest = { confirmFinish = false },
            title = { Text("Training beenden") },
            text = { Text("Offene Sätze werden als übersprungen markiert.") },
            confirmButton = { TextButton(onClick = tracked(SCREEN, "finish_confirm") { confirmFinish = false; vm.finishNow() }) { Text("Beenden") } },
            dismissButton = { TextButton(onClick = tracked(SCREEN, "finish_cancel") { confirmFinish = false }) { Text("Abbrechen") } },
        )
    }
}

/** One of the four text actions in the pinned bar: single line, no wrapping, equal width. */
@Composable
private fun BarAction(text: String, onClick: () -> Unit, enabled: Boolean, pad: PaddingValues, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    androidx.compose.material3.TextButton(
        onClick = onClick, modifier = modifier.heightIn(min = 48.dp), enabled = enabled, contentPadding = pad,
        colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
            contentColor = cs.onSurface, disabledContentColor = cs.onSurfaceVariant.copy(alpha = 0.6f),
        ),
    ) {
        Text(
            text, maxLines = 1, softWrap = false, overflow = TextOverflow.Visible, textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium, fontSize = 13.sp),
        )
    }
}

/** Shown instead of the input block when the exercise has no open set (all done or skipped). */
@Composable
private fun FinishedExerciseCard(ex: SessionExercise, onResume: () -> Unit, onNext: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.s2)) {
        Text(
            if (hasSkippedSets(ex)) "Diese Übung hat übersprungene Sätze." else "Alle Sätze dieser Übung sind erledigt. Tippe einen Satz an, um ihn zu korrigieren.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (hasSkippedSets(ex)) {
            PrimaryButton("Übersprungene Sätze wieder aufnehmen", tracked(SCREEN, "resume_exercise", onResume), height = 52.dp)
        }
        SoftButton("Zur nächsten offenen Übung", tracked(SCREEN, "next_open", onNext), Modifier.fillMaxWidth(), height = 52.dp)
    }
}

/** Compact top bar: gym stripe + names on the left, elapsed time and the workout list on the right. */
@Composable
private fun TrainingHeader(planName: String, position: String, gymName: String, gymColor: Color, elapsed: String, onList: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s3)) {
        Box(Modifier.width(4.dp).height(40.dp).background(gymColor, androidx.compose.foundation.shape.RoundedCornerShape(2.dp)))
        Column(Modifier.weight(1f)) {
            Text(planName, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), color = cs.onSurface, maxLines = 1)
            Text("$position · $gymName", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1)
        }
        SoftButton(elapsed, onList, height = 48.dp, icon = GymIcons.Menu)
    }
}

@Composable
private fun SkipSheet(onPick: (SkipReason, Boolean) -> Unit, onDismiss: () -> Unit) {
    var scope by remember { mutableIntStateOf(0) }
    GymSheet(onDismiss, title = "Überspringen") {
        Segmented(listOf(SegmentItem("Nur dieser Satz"), SegmentItem("Rest der Übung")), scope, { scope = it })
        listOf(
            SkipReason.TIME to "Zeit",
            SkipReason.EQUIPMENT to "Gerät besetzt",
            SkipReason.PAIN to "Schmerz",
            SkipReason.OTHER to "Sonstiges",
        ).forEach { (reason, label) ->
            SoftButton(label, { onPick(reason, scope == 1) }, Modifier.fillMaxWidth(), height = Spacing.TouchMin)
        }
    }
}

@Composable
private fun NoteSheet(title: String, initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    GymSheet(onDismiss, title = title) {
        GymField(text, { text = it }, "Notiz", singleLine = false, placeholder = "Zum Beispiel: Sitz eine Stufe höher")
        PrimaryButton("Speichern", tracked(SCREEN, "note_save") { onSave(text) }, height = 52.dp)
    }
}

/** Corrects weight, reps and effort of a logged set while the workout is running. */
@Composable
private fun CorrectSetSheet(
    ex: SessionExercise,
    set: de.baschek.gymapp.core.model.SetLog,
    levels: List<Double>,
    addonList: List<Double>,
    wheel: List<Double>,
    weighted: Boolean,
    onSave: (SetCorrection) -> Unit,
    onReopen: () -> Unit,
    onDismiss: () -> Unit,
) {
    val warm = set.setType == SetType.WARMUP
    val draft = remember(set.id) { buildDraftFromSet(ex, set, levels, addonList, wheel) }
    val label = setRows(ex).firstOrNull { it.setId == set.id }?.label
    GymSheet(onDismiss, title = if (warm) "Aufwärmsatz korrigieren" else "Satz ${label ?: ""} korrigieren".replace("  ", " ")) {
        SetEntryCard(ex = ex, draft = draft, weighted = weighted, warm = warm, levels = levels, addonList = addonList, wheel = wheel, screen = "training_edit")
        PrimaryButton(
            "Speichern",
            tracked("training_edit", "save") {
                onSave(
                    SetCorrection(
                        weightKg = if (weighted) draft.total else null,
                        value = draft.value,
                        effort = draft.effortBand(warm),
                        addonWeightsKg = draft.addons.sorted().takeIf { it.isNotEmpty() && weighted },
                    ),
                )
            },
            height = 52.dp,
        )
        LinkButton("Satz wieder offen setzen", tracked("training_edit", "reopen", onReopen), Modifier.fillMaxWidth(), height = 48.dp)
    }
}
