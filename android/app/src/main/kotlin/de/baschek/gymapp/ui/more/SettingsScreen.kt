package de.baschek.gymapp.ui.more

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.baschek.gymapp.BuildConfig
import de.baschek.gymapp.data.Accent
import de.baschek.gymapp.data.Profile
import de.baschek.gymapp.telemetry.Telemetry
import de.baschek.gymapp.ui.ConflictSheet
import de.baschek.gymapp.ui.Navigator
import de.baschek.gymapp.ui.Routes
import de.baschek.gymapp.ui.SyncBadgeView
import de.baschek.gymapp.ui.SyncBanner
import de.baschek.gymapp.ui.SyncKind
import de.baschek.gymapp.ui.appViewModelFactory
import de.baschek.gymapp.ui.components.BackBar
import de.baschek.gymapp.ui.components.BadgeKind
import de.baschek.gymapp.ui.components.Banner
import de.baschek.gymapp.ui.components.ChevronIcon
import de.baschek.gymapp.ui.components.Group
import de.baschek.gymapp.ui.components.GymCardSurface
import de.baschek.gymapp.ui.components.GymIcons
import de.baschek.gymapp.ui.components.GymSwitch
import de.baschek.gymapp.ui.components.Hairline
import de.baschek.gymapp.ui.components.LinkButton
import de.baschek.gymapp.ui.components.ListRow
import de.baschek.gymapp.ui.components.Overline
import de.baschek.gymapp.ui.components.ScreenList
import de.baschek.gymapp.ui.components.SegmentItem
import de.baschek.gymapp.ui.components.Segmented
import de.baschek.gymapp.ui.components.SoftButton
import de.baschek.gymapp.ui.components.TickIcon
import de.baschek.gymapp.ui.fmtWhen
import de.baschek.gymapp.ui.theme.Spacing
import de.baschek.gymapp.ui.theme.colors
import de.baschek.gymapp.ui.tracked
import de.baschek.gymapp.ui.tracked1

private const val SCREEN = "einstellungen"

/** Einstellungen: sync and pairing, profile, accent colour, notifications, app update. */
@Composable
fun SettingsScreen(nav: Navigator) {
    val vm: SettingsViewModel = viewModel(factory = appViewModelFactory { SettingsViewModel(it) })
    val sync by vm.sync.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()
    val accent by vm.accent.collectAsStateWithLifecycle()
    val vibration by vm.restVibration.collectAsStateWithLifecycle()
    val keepOn by vm.keepScreenOn.collectAsStateWithLifecycle()
    val paired by vm.paired.collectAsStateWithLifecycle()
    val update by vm.update.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showConflicts by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { Telemetry.track(SCREEN, "view") }

    ScreenList {
        item { BackBar("Einstellungen", tracked(SCREEN, "back") { nav.pop() }) }
        item { SyncBanner(sync, onRetry = vm::syncNow, onResolve = { showConflicts = true }, onPair = { nav.push(Routes.PAIRING) }, showNotPaired = true) }

        item {
            Column {
                Overline("Synchronisierung", Modifier.padding(start = 4.dp, bottom = 8.dp))
                GymCardSurface {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        SyncBadgeView(sync, Modifier.weight(1f))
                        Text(
                            sync.lastSync?.let { "Zuletzt: ${fmtWhen(it)}" } ?: "Noch nie synchronisiert",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (profile == Profile.TEST) {
                        Text("Im Testprofil wird nichts synchronisiert.", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    SoftButton(
                        "Jetzt synchronisieren", tracked(SCREEN, "sync_now", vm::syncNow), Modifier.padding(top = Spacing.s3).fillMaxWidth(),
                        icon = GymIcons.Sync, enabled = paired && sync.kind != SyncKind.SYNCING && profile == Profile.PERSONAL,
                    )
                    SoftButton(
                        if (paired) "Neu koppeln" else "Mit Server verbinden", tracked(SCREEN, "pair") { nav.push(Routes.PAIRING) },
                        Modifier.padding(top = Spacing.s2).fillMaxWidth(), icon = GymIcons.Link,
                    )
                    if (paired) LinkButton("Verbindung trennen", tracked(SCREEN, "unpair", vm::unpair), color = MaterialTheme.colorScheme.error)
                }
            }
        }

        item {
            Column {
                Overline("Profil", Modifier.padding(start = 4.dp, bottom = 8.dp))
                Segmented(
                    listOf(SegmentItem("Persönlich"), SegmentItem("Test")), if (profile == Profile.TEST) 1 else 0,
                    tracked1<Int>(SCREEN, "profile") { vm.setProfile(if (it == 1) Profile.TEST else Profile.PERSONAL) },
                )
                if (profile == Profile.TEST) {
                    Text(
                        "Testdaten sind vom persönlichen Profil getrennt und werden nie synchronisiert.", Modifier.padding(start = 4.dp, top = 8.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SoftButton("Testdaten anlegen", tracked(SCREEN, "seed_demo", vm::seedDemo), Modifier.padding(top = Spacing.s2))
                }
            }
        }

        item {
            Column {
                Overline("Akzentfarbe", Modifier.padding(start = 4.dp, bottom = 8.dp))
                Group {
                    Accent.entries.forEachIndexed { i, a ->
                        if (i > 0) Hairline()
                        ListRow(
                            a.label, onClick = tracked(SCREEN, "accent_${a.key}") { vm.setAccent(a) },
                            subtitle = if (a == Accent.PAPIER) "Standard" else null,
                            leading = {
                                Box(
                                    Modifier.size(28.dp).clip(CircleShape).background(a.colors().primary)
                                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                                )
                            },
                            trailing = { if (a == accent) TickIcon() },
                        )
                    }
                }
            }
        }

        item {
            Column {
                Overline("Benachrichtigungen", Modifier.padding(start = 4.dp, bottom = 8.dp))
                Group {
                    ListRow(
                        "Vibration am Pausenende", subtitle = "Einmal, wenn der Countdown null erreicht",
                        trailing = { GymSwitch(vibration, tracked1<Boolean>(SCREEN, "vibration") { vm.setRestVibration(it) }, "Vibration am Pausenende") },
                    )
                    Hairline()
                    ListRow(
                        "Bildschirm im Training anlassen",
                        trailing = { GymSwitch(keepOn, tracked1<Boolean>(SCREEN, "keep_screen_on") { vm.setKeepScreenOn(it) }, "Bildschirm im Training anlassen") },
                    )
                    Hairline()
                    ListRow(
                        "Android-Benachrichtigungen", subtitle = "Timer in der Statusleiste verwalten",
                        onClick = tracked(SCREEN, "system_notifications") {
                            context.startActivity(
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        },
                        trailing = { ChevronIcon() },
                    )
                }
            }
        }

        item {
            Column {
                Overline("App", Modifier.padding(start = 4.dp, bottom = 8.dp))
                GymCardSurface {
                    Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyLarge)
                    when (val u = update) {
                        UpdateUi.Idle -> Unit
                        UpdateUi.Checking -> Text("Suche …", style = MaterialTheme.typography.bodyMedium)
                        UpdateUi.UpToDate -> Text("Die App ist aktuell.", style = MaterialTheme.typography.bodyMedium)
                        UpdateUi.Downloading -> Text("Lade herunter …", style = MaterialTheme.typography.bodyMedium)
                        UpdateUi.Error -> Text("Update fehlgeschlagen.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                        is UpdateUi.NeedsPermission -> Text("Bitte erlaube die Installation aus dieser Quelle und tippe danach erneut.", style = MaterialTheme.typography.bodyMedium)
                        is UpdateUi.Available -> Unit
                    }
                    val info = (update as? UpdateUi.Available)?.info ?: (update as? UpdateUi.NeedsPermission)?.info
                    if (info != null) {
                        Banner(
                            BadgeKind.NEUTRAL, GymIcons.Sync, "Neue Version verfügbar", Modifier.padding(top = Spacing.s2), text = "GymApp ${info.versionName} ist bereit.",
                        ) { LinkButton("Aktualisieren", tracked(SCREEN, "install_update") { vm.installUpdate(info) }) }
                    } else {
                        SoftButton(
                            "Nach Update suchen", tracked(SCREEN, "check_update", vm::checkUpdate), Modifier.padding(top = Spacing.s3),
                            enabled = update != UpdateUi.Checking && update != UpdateUi.Downloading,
                        )
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
