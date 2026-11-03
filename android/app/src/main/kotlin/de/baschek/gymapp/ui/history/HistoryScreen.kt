package de.baschek.gymapp.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.baschek.gymapp.core.model.Session
import de.baschek.gymapp.core.progression.totalVolume
import de.baschek.gymapp.core.stats.leadingBlanks
import de.baschek.gymapp.core.stats.sessionsByDay
import de.baschek.gymapp.data.Stored
import de.baschek.gymapp.telemetry.Telemetry
import de.baschek.gymapp.ui.ConflictSheet
import de.baschek.gymapp.ui.Navigator
import de.baschek.gymapp.ui.Routes
import de.baschek.gymapp.ui.SyncBadgeView
import de.baschek.gymapp.ui.SyncBanner
import de.baschek.gymapp.ui.appViewModelFactory
import de.baschek.gymapp.ui.components.BadgeKind
import de.baschek.gymapp.ui.components.ChevronIcon
import de.baschek.gymapp.ui.components.Group
import de.baschek.gymapp.ui.components.GhostIconButton
import de.baschek.gymapp.ui.components.GymCardSurface
import de.baschek.gymapp.ui.components.GymIcons
import de.baschek.gymapp.ui.components.Hairline
import de.baschek.gymapp.ui.components.ListRow
import de.baschek.gymapp.ui.components.Overline
import de.baschek.gymapp.ui.components.ScreenList
import de.baschek.gymapp.ui.components.StatusBadge
import de.baschek.gymapp.ui.components.Tag
import de.baschek.gymapp.ui.components.TopTitle
import de.baschek.gymapp.ui.fmtDayLong
import de.baschek.gymapp.ui.fmtInt
import de.baschek.gymapp.ui.fmtMinutes
import de.baschek.gymapp.ui.fmtMonth
import de.baschek.gymapp.ui.gymColorOf
import de.baschek.gymapp.ui.gymLabel
import de.baschek.gymapp.ui.parseInstant
import de.baschek.gymapp.ui.theme.Spacing
import de.baschek.gymapp.ui.tracked
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.IsoFields

private const val SCREEN = "verlauf"

/** Verlauf: month calendar (dots in the gym colours), gym filter and the sessions grouped by week. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(nav: Navigator) {
    val vm: HistoryViewModel = viewModel(factory = appViewModelFactory { HistoryViewModel(it) })
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val gyms by vm.gyms.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()
    val sync by vm.sync.collectAsStateWithLifecycle()
    var monthText by rememberSaveable { mutableStateOf(YearMonth.now().toString()) }
    var dayFilter by rememberSaveable { mutableStateOf<Int?>(null) }
    var gymFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var showConflicts by rememberSaveable { mutableStateOf(false) }
    val month = YearMonth.parse(monthText)
    val zone = ZoneId.systemDefault()

    LaunchedEffect(Unit) { Telemetry.track(SCREEN, "view") }

    val byGym = sessions.filter { gymFilter == null || it.value.gymId == gymFilter }
    val byDay = sessionsByDay(byGym.map { it.value }, month, zone)
    val shown = if (dayFilter == null) byGym else byGym.filter { s ->
        parseInstant(s.value.effectiveTime)?.atZone(zone)?.toLocalDate()?.let { YearMonth.from(it) == month && it.dayOfMonth == dayFilter } == true
    }

    ScreenList {
        item { TopTitle("Verlauf", trailing = { SyncBadgeView(sync) }) }
        item { SyncBanner(sync, onRetry = vm::syncNow, onResolve = { showConflicts = true }, onPair = { nav.push(Routes.PAIRING) }) }

        item {
            GymCardSurface {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    GhostIconButton(GymIcons.Back, "Vorheriger Monat", tracked(SCREEN, "month_prev") { monthText = month.minusMonths(1).toString(); dayFilter = null })
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(fmtMonth(month), style = MaterialTheme.typography.titleMedium)
                        Text("${byDay.values.sumOf { it.size }} Trainings", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    GhostIconButton(GymIcons.Chevron, "Nächster Monat", tracked(SCREEN, "month_next") { monthText = month.plusMonths(1).toString(); dayFilter = null })
                }
                CalendarGrid(
                    month = month, selectedDay = dayFilter,
                    dots = byDay.mapValues { (_, list) -> list.map { it.gymId } },
                    gymColor = { id -> gymColorOf(id, gyms) },
                    onDay = tracked1("select_day") { d -> dayFilter = if (dayFilter == d) null else d },
                )
            }
        }

        if (gyms.size > 1) {
            item {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.s2)) {
                    FilterChip(gymFilter == null, tracked(SCREEN, "filter_all") { gymFilter = null }, label = { Text("Alle") })
                    gyms.forEach { g ->
                        FilterChip(gymFilter == g.id, tracked(SCREEN, "filter_gym") { gymFilter = if (gymFilter == g.id) null else g.id }, label = { Text(g.value.name) })
                    }
                }
            }
        }

        if (dayFilter != null) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${dayFilter}. ${fmtMonth(month)}", style = MaterialTheme.typography.titleMedium)
                    de.baschek.gymapp.ui.components.LinkButton("Alle anzeigen", tracked(SCREEN, "clear_day") { dayFilter = null })
                }
            }
        }

        if (shown.isEmpty()) {
            item {
                GymCardSurface {
                    Text(if (sessions.isEmpty()) "Noch keine Trainings" else "Keine Trainings gefunden", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        if (sessions.isEmpty()) "Abgeschlossene Trainings erscheinen hier, auch wenn du offline bist." else "Ändere den Filter oder den Monat.",
                        Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            val weeks = shown.groupBy { weekKey(it.value, zone) }
            weeks.forEach { (week, list) ->
                item(key = "w-$week") {
                    Column {
                        Overline(week, Modifier.padding(start = 4.dp, bottom = 8.dp))
                        Group {
                            list.forEachIndexed { i, stored ->
                                if (i > 0) Hairline()
                                SessionRow(stored, gyms, pending, onClick = tracked(SCREEN, "open_session") { nav.push(Routes.session(stored.id)) })
                            }
                        }
                    }
                }
            }
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

private fun tracked1(element: String, block: (Int) -> Unit): (Int) -> Unit = de.baschek.gymapp.ui.tracked1(SCREEN, element, block)

private fun weekKey(s: Session, zone: ZoneId): String {
    val d = parseInstant(s.effectiveTime)?.atZone(zone)?.toLocalDate() ?: return "Unbekannt"
    val week = d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
    val monday = d.minusDays((d.dayOfWeek.value - 1).toLong())
    return "Woche $week · ab %02d.%02d.".format(monday.dayOfMonth, monday.monthValue)
}

@Composable
private fun SessionRow(stored: Stored<Session>, gyms: List<Stored<de.baschek.gymapp.core.model.Gym>>, pending: Set<String>, onClick: () -> Unit) {
    val s = stored.value
    val instant = parseInstant(s.effectiveTime)
    val volume = s.exercises.sumOf { totalVolume(it) }.toLong()
    ListRow(
        title = s.planName, minHeight = 68.dp,
        subtitle = listOfNotNull(
            gymLabel(s.gymId, gyms),
            s.durationSeconds?.let { fmtMinutes(it / 60) },
            if (volume > 0) "${fmtInt(volume)} kg" else null,
        ).joinToString(" · "),
        onClick = onClick,
        leading = { Box(Modifier.size(10.dp).clip(CircleShape).background(gymColorOf(s.gymId, gyms))) },
        titleSuffix = { instant?.let { Text(fmtDayLong(it).dropLast(5), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
        trailing = {
            Column(horizontalAlignment = Alignment.End) {
                if (!s.reviewFlags.isNullOrEmpty()) Tag("zu prüfen")
                if (stored.id in pending) StatusBadge(BadgeKind.WARN, GymIcons.Sync, "Offen")
            }
            ChevronIcon()
        },
    )
}

@Composable
private fun CalendarGrid(
    month: YearMonth,
    selectedDay: Int?,
    dots: Map<Int, List<String?>>,
    gymColor: @Composable (String?) -> androidx.compose.ui.graphics.Color,
    onDay: (Int) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val blanks = leadingBlanks(month)
    val days = month.lengthOfMonth()
    val today = LocalDate.now()
    Column(Modifier.padding(top = Spacing.s2), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth()) {
            listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So").forEach {
                Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            }
        }
        val cells = blanks + days
        val rows = (cells + 6) / 7
        for (r in 0 until rows) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (col in 0 until 7) {
                    val day = r * 7 + col - blanks + 1
                    if (day < 1 || day > days) {
                        Box(Modifier.weight(1f).height(44.dp))
                    } else {
                        val list = dots[day].orEmpty()
                        val isToday = month.atDay(day) == today
                        Column(
                            Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(12.dp))
                                .background(if (selectedDay == day) cs.primaryContainer else if (isToday) cs.surfaceContainerHigh else cs.surfaceContainer)
                                .then(if (list.isNotEmpty()) Modifier.clickable(role = Role.Button) { onDay(day) } else Modifier)
                                .semantics { contentDescription = "$day. ${fmtMonth(month)}" + if (list.isNotEmpty()) ", ${list.size} Training" else "" },
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                        ) {
                            Text("$day", style = MaterialTheme.typography.bodySmall, color = cs.onSurface)
                            Row(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.height(6.dp)) {
                                list.take(3).forEach { Box(Modifier.size(5.dp).clip(CircleShape).background(gymColor(it))) }
                            }
                        }
                    }
                }
            }
        }
    }
}
