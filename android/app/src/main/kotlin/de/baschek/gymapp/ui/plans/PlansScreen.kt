package de.baschek.gymapp.ui.plans

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.baschek.gymapp.core.stats.estimateMinutes
import de.baschek.gymapp.telemetry.Telemetry
import de.baschek.gymapp.ui.ConflictSheet
import de.baschek.gymapp.ui.Navigator
import de.baschek.gymapp.ui.Routes
import de.baschek.gymapp.ui.SyncBadgeView
import de.baschek.gymapp.ui.SyncBanner
import de.baschek.gymapp.ui.appViewModelFactory
import de.baschek.gymapp.ui.components.ChevronIcon
import de.baschek.gymapp.ui.components.Group
import de.baschek.gymapp.ui.components.GymCardSurface
import de.baschek.gymapp.ui.components.GymField
import de.baschek.gymapp.ui.components.GymIcons
import de.baschek.gymapp.ui.components.GymSheet
import de.baschek.gymapp.ui.components.Hairline
import de.baschek.gymapp.ui.components.ListRow
import de.baschek.gymapp.ui.components.PrimaryButton
import de.baschek.gymapp.ui.components.ScreenList
import de.baschek.gymapp.ui.components.SoftButton
import de.baschek.gymapp.ui.components.Tag
import de.baschek.gymapp.ui.components.TopTitle
import de.baschek.gymapp.ui.theme.Spacing
import de.baschek.gymapp.ui.tracked

private const val SCREEN = "plaene"

/** Pläne: the list of plans with the assistant tag, the new-plan sheet and the empty state. */
@Composable
fun PlansScreen(nav: Navigator) {
    val vm: PlansViewModel = viewModel(factory = appViewModelFactory { PlansViewModel(it) })
    val plans by vm.plans.collectAsStateWithLifecycle()
    val seen by vm.seen.collectAsStateWithLifecycle()
    val sync by vm.sync.collectAsStateWithLifecycle()
    var creating by rememberSaveable { mutableStateOf(false) }
    var showConflicts by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) { Telemetry.track(SCREEN, "view") }

    ScreenList {
        item { TopTitle("Pläne", trailing = { SyncBadgeView(sync) }) }
        item { SyncBanner(sync, onRetry = vm::syncNow, onResolve = { showConflicts = true }, onPair = { nav.push(Routes.PAIRING) }) }
        if (plans.isEmpty()) {
            item {
                GymCardSurface {
                    Text("Keine Pläne", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Lege einen eigenen Plan an. Pläne vom Assistenten erscheinen nach der Synchronisierung hier.",
                        Modifier.padding(top = 4.dp, bottom = Spacing.s3), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SoftButton("Plan anlegen", tracked(SCREEN, "create") { creating = true }, icon = GymIcons.Plus)
                }
            }
        } else {
            item {
                Group {
                    plans.sortedBy { it.value.name.lowercase() }.forEachIndexed { i, stored ->
                        if (i > 0) Hairline()
                        val p = stored.value
                        val changed = p.changedByAssistantAt
                        val unseen = changed != null && seen[stored.id] != changed
                        val modes = listOf("normal", "fast", "ultra").joinToString(" / ") { estimateMinutes(p.exercises, it).toString() }
                        ListRow(
                            p.name, minHeight = 72.dp,
                            subtitle = "${p.exercises.size} Übungen · $modes min",
                            onClick = tracked(SCREEN, "open_plan") { nav.push(Routes.plan(stored.id)) },
                            titleSuffix = if (unseen) ({ Tag("Assistent") }) else null,
                            trailing = { ChevronIcon() },
                        )
                    }
                }
            }
            item { SoftButton("Neuer Plan", tracked(SCREEN, "create") { creating = true }, icon = GymIcons.Plus) }
        }
    }

    if (creating) {
        var name by rememberSaveable { mutableStateOf("") }
        GymSheet({ creating = false }, title = "Neuer Plan") {
            GymField(name, { name = it }, "Name", placeholder = "Zum Beispiel Push A")
            PrimaryButton(
                "Anlegen", tracked(SCREEN, "create_confirm") {
                    creating = false
                    vm.createPlan(name) { nav.push(Routes.plan(it)) }
                },
                height = 52.dp,
            )
        }
    }
    if (showConflicts) {
        ConflictSheet(
            sync.conflicts,
            onKeepNewest = { vm.resolveConflicts(sync.conflicts.map { it.notice }, false); showConflicts = false },
            onKeepMine = { vm.resolveConflicts(sync.conflicts.map { it.notice }, true); showConflicts = false },
            onDismiss = { showConflicts = false },
        )
    }
}
