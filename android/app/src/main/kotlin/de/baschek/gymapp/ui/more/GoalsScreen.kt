package de.baschek.gymapp.ui.more

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.baschek.gymapp.core.model.Goal
import de.baschek.gymapp.core.model.GoalAuthor
import de.baschek.gymapp.core.model.RadarArea
import de.baschek.gymapp.core.model.forArea
import de.baschek.gymapp.core.model.with
import de.baschek.gymapp.telemetry.Telemetry
import de.baschek.gymapp.ui.Navigator
import de.baschek.gymapp.ui.appViewModelFactory
import de.baschek.gymapp.ui.components.BackBar
import de.baschek.gymapp.ui.components.GymCardSurface
import de.baschek.gymapp.ui.components.Overline
import de.baschek.gymapp.ui.components.ScreenList
import de.baschek.gymapp.ui.components.StepRow
import de.baschek.gymapp.ui.fmtWhen
import de.baschek.gymapp.ui.parseInstant
import de.baschek.gymapp.ui.tracked

private const val SCREEN = "ziele"

/** Mehr > Ziele: training days per week and target sets per muscle area per week. */
@Composable
fun GoalsScreen(nav: Navigator) {
    val vm: GoalsViewModel = viewModel(factory = appViewModelFactory { GoalsViewModel(it) })
    val state by vm.goal.collectAsStateWithLifecycle()
    val goal = state?.goal ?: Goal()
    LaunchedEffect(Unit) { Telemetry.track(SCREEN, "view") }

    ScreenList {
        item { BackBar("Ziele", tracked(SCREEN, "back") { nav.pop() }) }
        item {
            GymCardSurface {
                Overline("Training")
                StepRow(
                    "Trainingstage pro Woche", "${goal.trainingDaysPerWeek}",
                    onMinus = tracked(SCREEN, "days_minus") { vm.change { it.copy(trainingDaysPerWeek = (it.trainingDaysPerWeek - 1).coerceAtLeast(1)) } },
                    onPlus = tracked(SCREEN, "days_plus") { vm.change { it.copy(trainingDaysPerWeek = (it.trainingDaysPerWeek + 1).coerceAtMost(7)) } },
                    minusEnabled = goal.trainingDaysPerWeek > 1, plusEnabled = goal.trainingDaysPerWeek < 7,
                )
            }
        }
        item {
            GymCardSurface {
                Overline("Ziel-Sätze pro Woche")
                RadarArea.entries.forEach { area ->
                    val v = goal.targetSets.forArea(area)
                    StepRow(
                        area.label, "$v",
                        onMinus = tracked(SCREEN, "sets_minus") { vm.change { it.copy(targetSets = it.targetSets.with(area, (v - 1).coerceAtLeast(0))) } },
                        onPlus = tracked(SCREEN, "sets_plus") { vm.change { it.copy(targetSets = it.targetSets.with(area, (v + 1).coerceAtMost(60))) } },
                        minusEnabled = v > 0, plusEnabled = v < 60,
                    )
                }
            }
        }
        item {
            val by = when (goal.updatedBy) {
                GoalAuthor.SERVER -> "vom Assistenten"
                GoalAuthor.APP -> "von dir"
                else -> null
            }
            val at = parseInstant(state?.updatedAt)?.let { fmtWhen(it) }
            Text(
                if (at != null && by != null) "Zuletzt geändert $at ($by)" else "Noch nicht geändert",
                Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
