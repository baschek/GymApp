package de.baschek.gymapp.ui.progress

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.baschek.gymapp.core.model.MuscleGroup
import de.baschek.gymapp.core.stats.StatsPeriod
import de.baschek.gymapp.core.stats.TrendStatus
import de.baschek.gymapp.core.stats.formatSets
import de.baschek.gymapp.core.stats.lowAreas
import de.baschek.gymapp.core.stats.radarAxes
import de.baschek.gymapp.core.model.Goal
import de.baschek.gymapp.telemetry.Telemetry
import de.baschek.gymapp.ui.ConflictSheet
import de.baschek.gymapp.ui.MuscleDetailSheet
import de.baschek.gymapp.ui.MuscleShareSheet
import de.baschek.gymapp.ui.Navigator
import de.baschek.gymapp.ui.Routes
import de.baschek.gymapp.ui.SyncBadgeView
import de.baschek.gymapp.ui.SyncBanner
import de.baschek.gymapp.ui.appViewModelFactory
import de.baschek.gymapp.ui.components.BadgeKind
import de.baschek.gymapp.ui.components.BodySide
import de.baschek.gymapp.ui.components.ExercisePickerSheet
import de.baschek.gymapp.ui.components.Group
import de.baschek.gymapp.ui.components.GymCardSurface
import de.baschek.gymapp.ui.components.GymIcons
import de.baschek.gymapp.ui.components.Hairline
import de.baschek.gymapp.ui.components.ListRow
import de.baschek.gymapp.ui.components.MostLoadedCard
import de.baschek.gymapp.ui.components.MuscleMapCard
import de.baschek.gymapp.ui.components.Overline
import de.baschek.gymapp.ui.components.ProgressChart
import de.baschek.gymapp.ui.components.RadarChart
import de.baschek.gymapp.ui.components.ScreenList
import de.baschek.gymapp.ui.components.SegmentItem
import de.baschek.gymapp.ui.components.Segmented
import de.baschek.gymapp.ui.components.SoftButton
import de.baschek.gymapp.ui.components.StatusBadge
import de.baschek.gymapp.ui.components.ChevronIcon
import de.baschek.gymapp.ui.components.TopTitle
import de.baschek.gymapp.ui.fmtNum
import de.baschek.gymapp.ui.gymLabel
import de.baschek.gymapp.ui.theme.GymTextStyles
import de.baschek.gymapp.ui.tracked
import de.baschek.gymapp.ui.tracked1

private const val SCREEN = "fortschritt"
private val PERIODS = listOf(StatsPeriod.TODAY, StatsPeriod.WEEK, StatsPeriod.MONTH)
private val PERIOD_NAMES = listOf("Heute", "Woche", "4 Wochen")
private val PERIOD_TEXT = listOf("heute", "in der Woche", "in 4 Wochen")

/** Fortschritt with the segments Verlauf, Muskeln and Gleichgewicht. */
@Composable
fun ProgressScreen(nav: Navigator) {
    val vm: ProgressViewModel = viewModel(factory = appViewModelFactory { ProgressViewModel(it) })
    val sync by vm.sync.collectAsStateWithLifecycle()
    val gyms by vm.gyms.collectAsStateWithLifecycle()
    val period by vm.period.collectAsStateWithLifecycle()
    val muscles by vm.muscles.collectAsStateWithLifecycle()
    val week by vm.week.collectAsStateWithLifecycle()
    val weekSessions by vm.weekSessions.collectAsStateWithLifecycle()
    val trained by vm.trained.collectAsStateWithLifecycle()
    val selected by vm.selectedExercise.collectAsStateWithLifecycle()
    val exerciseGyms by vm.exerciseGyms.collectAsStateWithLifecycle()
    val activeGym by vm.activeGym.collectAsStateWithLifecycle()
    val trend by vm.trend.collectAsStateWithLifecycle()
    val goalState by vm.goal.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(0) }
    var side by rememberSaveable { mutableStateOf(BodySide.FRONT) }
    var detail by rememberSaveable { mutableStateOf<String?>(null) }
    var share by rememberSaveable { mutableStateOf(false) }
    var picking by rememberSaveable { mutableStateOf(false) }
    var showConflicts by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) { Telemetry.track(SCREEN, "view") }
    val periodIndex = PERIODS.indexOf(period)
    val goal = goalState?.goal ?: Goal()

    ScreenList {
        item { TopTitle("Fortschritt", trailing = { SyncBadgeView(sync) }) }
        item { SyncBanner(sync, onRetry = vm::syncNow, onResolve = { showConflicts = true }, onPair = { nav.push(Routes.PAIRING) }) }
        item {
            Segmented(
                listOf(SegmentItem("Verlauf"), SegmentItem("Muskeln"), SegmentItem("Gleichgewicht")), tab,
                tracked1<Int>(SCREEN, "segment") { tab = it },
            )
        }
        when (tab) {
            0 -> {
                val t = trend
                val ex = selected
                if (ex == null || t == null) {
                    item {
                        GymCardSurface {
                            Text("Noch keine Daten", style = MaterialTheme.typography.headlineSmall)
                            Text(
                                "Nach drei Trainings einer Übung erscheint hier der Verlauf. Alles funktioniert auch offline.",
                                Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    item { SoftButton(ex.value.name, tracked(SCREEN, "pick_exercise") { picking = true }, Modifier.fillMaxWidth(), icon = GymIcons.Search) }
                    if (exerciseGyms.size > 1) {
                        item {
                            Segmented(
                                exerciseGyms.map { SegmentItem(gymLabel(it, gyms)) }, exerciseGyms.indexOf(activeGym).coerceAtLeast(0),
                                tracked1<Int>(SCREEN, "pick_gym") { vm.pickGym(exerciseGyms[it]) },
                            )
                        }
                    }
                    item {
                        GymCardSurface {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                Column {
                                    Overline("Gewicht und e1RM")
                                    Text(t.best?.let { "${fmtNum(it)} kg" } ?: "–", style = MaterialTheme.typography.headlineSmall)
                                }
                                when (t.status) {
                                    TrendStatus.STAGNATING -> StatusBadge(BadgeKind.WARN, GymIcons.Warn, "Stagniert")
                                    TrendStatus.RISING -> StatusBadge(BadgeKind.OK, GymIcons.Up, "Im Aufwärtstrend")
                                    else -> Unit
                                }
                            }
                            if (t.status == TrendStatus.NOT_ENOUGH_DATA) {
                                Text(
                                    "Für den Verlauf braucht es mindestens drei Trainings (bisher ${t.weights.size}).", Modifier.padding(top = 8.dp),
                                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                val description = "Gewicht von ${fmtNum(t.weights.first())} auf ${fmtNum(t.weights.last())} Kilogramm in ${t.weights.size} Trainings" +
                                    if (t.status == TrendStatus.STAGNATING) ", stagniert" else ""
                                ProgressChart(t.weights, t.oneRepMax, description, Modifier.padding(top = 8.dp))
                                Text(
                                    if (t.status == TrendStatus.STAGNATING) "Seit drei Trainings kein neues Gewicht. Vorschlag: erst Wiederholungen steigern."
                                    else "Durchgezogen: Gewicht · gepunktet: geschätztes 1RM",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    item {
                        Group {
                            ListRow("Bestgewicht", trailing = { Num(t.best?.let { "${fmtNum(it)} kg" } ?: "–") })
                            Hairline()
                            ListRow("Geschätztes 1RM", trailing = { Num(t.bestOneRepMax?.let { "${fmtNum(Math.round(it * 2) / 2.0)} kg" } ?: "–") })
                            Hairline()
                            ListRow("Trainings diese Woche", trailing = { Num("$weekSessions") })
                        }
                    }
                }
                item {
                    GymCardSurface {
                        Overline("Sätze je Muskelgruppe, diese Woche", Modifier.padding(bottom = 4.dp))
                        val top = week.top(5)
                        if (top.isEmpty()) Text("Noch keine Sätze in dieser Woche.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        top.forEach { (g, v) ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(MuscleGroup.label(g), style = MaterialTheme.typography.bodyLarge)
                                Num(formatSets(v))
                            }
                        }
                    }
                }
            }
            1 -> {
                item { Segmented(PERIOD_NAMES.map { SegmentItem(it) }, periodIndex, tracked1<Int>(SCREEN, "period") { vm.setPeriod(PERIODS[it]) }) }
                item { MuscleMapCard("Trainierte Muskeln", muscles, side, { side = it }, tracked1<String>(SCREEN, "muscle") { detail = it }) }
                item { MostLoadedCard(muscles, tracked1<String>(SCREEN, "muscle_row") { detail = it }) }
                item { SoftButton("Karte teilen", tracked(SCREEN, "share_open") { share = true }, icon = GymIcons.Share) }
            }
            else -> {
                val axes = radarAxes(muscles.totals, goal.targetSets, period, goal.trainingDaysPerWeek)
                val low = lowAreas(axes)
                item { Segmented(PERIOD_NAMES.map { SegmentItem(it) }, periodIndex, tracked1<Int>(SCREEN, "period") { vm.setPeriod(PERIODS[it]) }) }
                item {
                    GymCardSurface {
                        Overline("Gleichgewicht")
                        RadarChart(axes)
                        Text("Gestrichelt: dein Ziel · Voll: erreicht", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                item {
                    GymCardSurface {
                        Overline("Zu kurz gekommen")
                        Text(
                            if (low.isEmpty()) "nichts" else low.joinToString(", ") { it.area.label },
                            Modifier.padding(top = 4.dp), style = MaterialTheme.typography.headlineSmall,
                        )
                    }
                }
                item {
                    Group {
                        ListRow(
                            "Ziele anpassen", subtitle = "Trainingstage und Sätze pro Woche",
                            onClick = tracked(SCREEN, "open_goals") { nav.push(Routes.GOALS) }, trailing = { ChevronIcon() },
                        )
                    }
                }
            }
        }
    }

    if (picking) {
        ExercisePickerSheet(trained, onPick = { vm.pickExercise(it.id); picking = false }, onDismiss = { picking = false }, title = "Übung wählen")
    }
    detail?.let { MuscleDetailSheet(it, muscles, PERIOD_TEXT[periodIndex.coerceAtLeast(0)], onDismiss = { detail = null }) }
    if (share) {
        MuscleShareSheet("Trainierte Muskeln", PERIOD_NAMES[periodIndex.coerceAtLeast(0)], muscles, onDismiss = { share = false })
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

@Composable
private fun Num(text: String) {
    Text(text, style = GymTextStyles.number.copy(fontSize = MaterialTheme.typography.bodyLarge.fontSize), color = MaterialTheme.colorScheme.onSurface)
}
