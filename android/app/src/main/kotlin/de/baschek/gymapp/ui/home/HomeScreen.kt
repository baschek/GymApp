package de.baschek.gymapp.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.baschek.gymapp.core.model.Plan
import de.baschek.gymapp.core.model.WorkoutMode
import de.baschek.gymapp.core.stats.WORKOUT_MODES
import de.baschek.gymapp.core.stats.estimateMinutes
import de.baschek.gymapp.data.Profile
import de.baschek.gymapp.telemetry.Telemetry
import de.baschek.gymapp.ui.ConflictSheet
import de.baschek.gymapp.ui.Navigator
import de.baschek.gymapp.ui.Routes
import de.baschek.gymapp.ui.SyncBadgeView
import de.baschek.gymapp.ui.SyncBanner
import de.baschek.gymapp.ui.SyncKind
import de.baschek.gymapp.ui.appViewModelFactory
import de.baschek.gymapp.ui.components.ChevronIcon
import de.baschek.gymapp.ui.components.CheckRow
import de.baschek.gymapp.ui.components.GymCard
import de.baschek.gymapp.ui.components.GymCardSurface
import de.baschek.gymapp.ui.components.Group
import de.baschek.gymapp.ui.components.Hairline
import de.baschek.gymapp.ui.components.LinkButton
import de.baschek.gymapp.ui.components.ListRow
import de.baschek.gymapp.ui.components.Overline
import de.baschek.gymapp.ui.components.PinnedBottom
import de.baschek.gymapp.ui.components.PrimaryButton
import de.baschek.gymapp.ui.components.ScreenList
import de.baschek.gymapp.ui.components.SegmentItem
import de.baschek.gymapp.ui.components.Segmented
import de.baschek.gymapp.ui.components.SoftButton
import de.baschek.gymapp.ui.components.TopTitle
import de.baschek.gymapp.ui.components.GymIcons
import de.baschek.gymapp.ui.fmtDaysAgo
import de.baschek.gymapp.ui.fmtMinutes
import de.baschek.gymapp.ui.gymChoices
import de.baschek.gymapp.ui.gymById
import de.baschek.gymapp.ui.gymColorOf
import de.baschek.gymapp.ui.gymLabel
import de.baschek.gymapp.ui.theme.Spacing
import de.baschek.gymapp.ui.tracked
import de.baschek.gymapp.ui.tracked1

private const val SCREEN = "heute"

private fun modeLabel(mode: String) = when (mode) {
    WorkoutMode.FAST -> "Schnell"
    WorkoutMode.ULTRA -> "Ultra"
    else -> "Normal"
}

/** Heute: gym, recommended workout, mode, packing list, sync state and the pinned start button. */
@Composable
fun HomeScreen(nav: Navigator, onOpenWorkout: (String) -> Unit) {
    val vm: HomeViewModel = viewModel(factory = appViewModelFactory { HomeViewModel(it) })
    val sync by vm.sync.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()
    val plans by vm.plans.collectAsStateWithLifecycle()
    val gyms by vm.gyms.collectAsStateWithLifecycle()
    val active by vm.activeSession.collectAsStateWithLifecycle()
    val lastGym by vm.lastGym.collectAsStateWithLifecycle()
    val suggestions by vm.suggestions.collectAsStateWithLifecycle()
    val selectedGym by vm.selectedGym.collectAsStateWithLifecycle()
    val selectedPlan by vm.selectedPlan.collectAsStateWithLifecycle()
    val mode by vm.mode.collectAsStateWithLifecycle()
    val pack by vm.pack.collectAsStateWithLifecycle()
    var showConflicts by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) { Telemetry.track(SCREEN, "view") }

    val planById = plans.associateBy { it.id }
    val chosen = suggestions.firstOrNull { it.planId == selectedPlan } ?: suggestions.firstOrNull()
    val chosenPlan: Plan? = chosen?.let { planById[it.planId]?.value }
    val alternatives = suggestions.filter { it.planId != chosen?.planId }.take(if (selectedPlan == null) 2 else 3)
    val choices = gymChoices(gyms)

    Box(Modifier.fillMaxSize()) {
        ScreenList(pinnedHeight = 100.dp) {
            item {
                TopTitle("Heute", trailing = { SyncBadgeView(sync) })
            }
            item {
                SyncBanner(
                    sync, onRetry = vm::syncNow, onResolve = { showConflicts = true },
                    onPair = { nav.push(Routes.PAIRING) }, showNotPaired = profile == Profile.PERSONAL,
                )
            }

            active?.let { session ->
                item {
                    GymCardSurface {
                        Overline("Training läuft")
                        Text(session.value.planName, Modifier.padding(top = 2.dp), style = MaterialTheme.typography.headlineSmall)
                        SoftButton(
                            "Fortsetzen", tracked(SCREEN, "resume") { onOpenWorkout(session.id) },
                            Modifier.padding(top = Spacing.s3), icon = GymIcons.Play,
                        )
                    }
                }
            }

            item {
                Column {
                    Overline("Wo trainierst du?", Modifier.padding(start = 4.dp, bottom = 8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        choices.forEach { id ->
                            val gym = gymById(id, gyms)
                            GymCard(
                                name = gymLabel(id, gyms),
                                subtitle = gym?.subtitle,
                                stripe = gymColorOf(id, gyms),
                                selected = selectedGym == id,
                                onClick = tracked(SCREEN, "select_gym") { vm.selectGym(id) },
                                hint = if (id == lastGym && selectedGym != id) "Zuletzt hier" else null,
                            )
                        }
                    }
                }
            }

            if (chosen == null || chosenPlan == null) {
                item {
                    GymCardSurface {
                        Text("Noch kein Plan", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "Lege einen Plan an oder lass dir vom Assistenten einen vorschlagen. Er erscheint nach der Synchronisierung.",
                            Modifier.padding(top = 4.dp, bottom = Spacing.s3), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        SoftButton("Plan anlegen", tracked(SCREEN, "create_plan") { nav.resetTo(Routes.PLANS) }, icon = GymIcons.Plus)
                        if (profile == Profile.TEST) {
                            SoftButton("Testdaten anlegen", tracked(SCREEN, "seed_demo", vm::seedDemo), Modifier.padding(top = 8.dp))
                        }
                    }
                }
            } else {
                item {
                    GymCardSurface {
                        Overline(if (chosen.planId == suggestions.firstOrNull()?.planId) "Empfohlen" else "Gewählt")
                        Text(chosen.name, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "${chosenPlan.exercises.size} Übungen · ${fmtDaysAgo(chosen.daysAgo)}",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Segmented(
                            WORKOUT_MODES.map { SegmentItem(modeLabel(it), fmtMinutes(estimateMinutes(chosenPlan.exercises, it))) },
                            selected = WORKOUT_MODES.indexOf(mode).coerceAtLeast(0),
                            onSelect = tracked1<Int>(SCREEN, "select_mode") { vm.setMode(WORKOUT_MODES[it]) },
                            modifier = Modifier.padding(top = Spacing.s3), tall = true,
                        )
                    }
                }
                if (alternatives.isNotEmpty()) {
                    item {
                        Group {
                            alternatives.forEachIndexed { i, alt ->
                                if (i > 0) Hairline()
                                val minutes = planById[alt.planId]?.value?.let { estimateMinutes(it.exercises, mode) }
                                ListRow(
                                    alt.name, minHeight = 64.dp,
                                    subtitle = listOfNotNull("Alternative", minutes?.let(::fmtMinutes), fmtDaysAgo(alt.daysAgo)).joinToString(" · "),
                                    onClick = tracked(SCREEN, "select_alternative") { vm.selectPlan(alt.planId) },
                                    trailing = { ChevronIcon() },
                                )
                            }
                        }
                    }
                }
            }

            if (selectedGym != null && pack.isNotEmpty()) {
                item {
                    Column {
                        Overline("Packliste", Modifier.padding(start = 4.dp, bottom = 8.dp))
                        Group {
                            pack.forEachIndexed { i, row ->
                                if (i > 0) Hairline()
                                CheckRow(row.text, row.checked, tracked(SCREEN, "toggle_pack") { vm.togglePack(row.id) })
                            }
                        }
                    }
                }
            }

            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    LinkButton(
                        "Jetzt synchronisieren", tracked(SCREEN, "sync_now", vm::syncNow),
                        enabled = sync.kind != SyncKind.SYNCING && sync.kind != SyncKind.NOT_PAIRED && sync.kind != SyncKind.TEST_LOCAL,
                    )
                }
            }
        }

        Box(Modifier.align(Alignment.BottomCenter)) {
            PinnedBottom(aboveBottomBar = true) {
                val gymId = selectedGym
                val planId = chosen?.planId
                when {
                    active != null -> PrimaryButton("Training fortsetzen", tracked(SCREEN, "resume_pinned") { onOpenWorkout(active!!.id) }, icon = GymIcons.Play)
                    else -> {
                        PrimaryButton(
                            if (gymId == null) "Studio wählen" else "Training starten",
                            onClick = { if (gymId != null && planId != null) vm.start(planId, gymId, onOpenWorkout) },
                            icon = GymIcons.Play, enabled = gymId != null && planId != null,
                        )
                        if (gymId != null) {
                            Text(
                                "in ${gymLabel(gymId, gyms)}", Modifier.fillMaxWidth().padding(top = 2.dp), textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    if (showConflicts) {
        ConflictSheet(
            sync.conflicts,
            onKeepNewest = { vm.resolveConflicts(sync.conflicts.map { it.notice }, keepMine = false); showConflicts = false },
            onKeepMine = { vm.resolveConflicts(sync.conflicts.map { it.notice }, keepMine = true); showConflicts = false },
            onDismiss = { showConflicts = false },
        )
    }
}
