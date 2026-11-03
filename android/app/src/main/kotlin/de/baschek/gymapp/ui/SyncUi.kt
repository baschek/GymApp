package de.baschek.gymapp.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.baschek.gymapp.AppContainer
import de.baschek.gymapp.data.Profile
import de.baschek.gymapp.sync.FailureKind
import de.baschek.gymapp.sync.SyncState
import de.baschek.gymapp.ui.components.BadgeKind
import de.baschek.gymapp.ui.components.Banner
import de.baschek.gymapp.ui.components.GymIcons
import de.baschek.gymapp.ui.components.GymSheet
import de.baschek.gymapp.ui.components.LinkButton
import de.baschek.gymapp.ui.components.PrimaryButton
import de.baschek.gymapp.ui.components.SoftButton
import de.baschek.gymapp.ui.components.StatusBadge
import de.baschek.gymapp.ui.theme.Spacing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.Instant

/** What the user sees about synchronisation. Derived from the sync manager and the stored state. */
enum class SyncKind { OK, SYNCING, OFFLINE, ERROR, CONFLICT, DISCONNECTED, UNAUTHORIZED, NOT_PAIRED, TEST_LOCAL }

data class ConflictItem(val notice: String, val label: String)

data class SyncUi(
    val kind: SyncKind = SyncKind.OK,
    val lastSync: Instant? = null,
    val pending: Int = 0,
    val conflicts: List<ConflictItem> = emptyList(),
    val rejected: Int = 0,
)

/** Combines the sync state flows once per ViewModel. */
class SyncUiSource(c: AppContainer, scope: CoroutineScope) {
    private val repo = c.repository

    private val conditions = combine(c.secureStore.paired, c.syncManager.state, repo.connectionLost, repo.authError, repo.activeProfile) {
            paired, state, lost, authError, profile ->
        Conditions(paired, state, lost, authError, profile)
    }
    private val facts = combine(repo.lastSyncAt, repo.pendingChanges, repo.syncNotices, repo.observePlans()) { last, pending, notices, plans ->
        Facts(last, pending, notices, plans.associate { it.id to it.value.name })
    }

    val state: StateFlow<SyncUi> = combine(conditions, facts) { cnd, f ->
        val conflicts = f.notices.filter { it.startsWith("conflict:") }.map { n ->
            val parts = n.split(':', limit = 3)
            val label = when (parts.getOrNull(1)) {
                "plan" -> "Plan „${f.planNames[parts.getOrNull(2)] ?: "unbekannt"}“"
                "exercise" -> "Übung"
                "machine_profile" -> "Maschinenprofil"
                "session" -> "Training"
                "goal" -> "Ziele"
                "gym" -> "Studio"
                else -> "Eintrag"
            }
            ConflictItem(n, label)
        }
        val failure = (cnd.state as? SyncState.Failed)?.kind
        val kind = when {
            cnd.profile == Profile.TEST -> SyncKind.TEST_LOCAL
            !cnd.paired -> SyncKind.NOT_PAIRED
            cnd.state is SyncState.Running -> SyncKind.SYNCING
            cnd.lost || failure == FailureKind.DISCONNECTED -> SyncKind.DISCONNECTED
            cnd.authError || failure == FailureKind.UNAUTHORIZED -> SyncKind.UNAUTHORIZED
            failure == FailureKind.NETWORK -> SyncKind.OFFLINE
            failure != null -> SyncKind.ERROR
            conflicts.isNotEmpty() -> SyncKind.CONFLICT
            else -> SyncKind.OK
        }
        SyncUi(kind, f.last, f.pending, conflicts, f.notices.count { it.startsWith("rejected:") })
    }.stateIn(scope, SharingStarted.WhileSubscribed(5000), SyncUi())

    private data class Conditions(val paired: Boolean, val state: SyncState, val lost: Boolean, val authError: Boolean, val profile: String)
    private data class Facts(val last: Instant?, val pending: Int, val notices: List<String>, val planNames: Map<String, String>)
}

/** Short status for the top bar: icon + words. */
@Composable
fun SyncBadgeView(ui: SyncUi, modifier: Modifier = Modifier) {
    val (kind, icon, text) = when (ui.kind) {
        SyncKind.OK -> Triple(
            BadgeKind.OK, GymIcons.Ok,
            if (ui.pending > 0) "Gespeichert, ${ui.pending} offen"
            else ui.lastSync?.let { "Synchronisiert ${fmtWhen(it).removePrefix("heute ")}" } ?: "Noch nie synchronisiert",
        )
        SyncKind.SYNCING -> Triple(BadgeKind.NEUTRAL, GymIcons.Sync, "Synchronisiere …")
        SyncKind.OFFLINE -> Triple(BadgeKind.WARN, GymIcons.Offline, "Offline" + if (ui.pending > 0) " · ${ui.pending} offen" else "")
        SyncKind.ERROR -> Triple(BadgeKind.ERROR, GymIcons.Warn, "Sync-Fehler")
        SyncKind.CONFLICT -> Triple(BadgeKind.WARN, GymIcons.Warn, "Konflikt")
        SyncKind.DISCONNECTED -> Triple(BadgeKind.ERROR, GymIcons.Warn, "Getrennt")
        SyncKind.UNAUTHORIZED -> Triple(BadgeKind.ERROR, GymIcons.Warn, "Zugriff abgelehnt")
        SyncKind.NOT_PAIRED -> Triple(BadgeKind.WARN, GymIcons.Offline, "Nicht verbunden")
        SyncKind.TEST_LOCAL -> Triple(BadgeKind.NEUTRAL, GymIcons.Ok, "Nur lokal")
    }
    StatusBadge(kind, icon, text, modifier)
}

/**
 * The banner and progress bar that belong to the sync state. Nothing is shown in the normal state.
 * [onRetry] syncs again, [onResolve] opens the conflict sheet, [onPair] opens the pairing screen.
 */
@Composable
fun SyncBanner(
    ui: SyncUi,
    onRetry: () -> Unit,
    onResolve: () -> Unit,
    onPair: () -> Unit,
    modifier: Modifier = Modifier,
    showNotPaired: Boolean = false,
) {
    Column(modifier.fillMaxWidth()) {
        if (ui.kind == SyncKind.SYNCING) {
            LinearProgressIndicator(
                Modifier.fillMaxWidth().padding(bottom = Spacing.s2),
                color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            )
        }
        when (ui.kind) {
            SyncKind.OFFLINE -> Banner(BadgeKind.WARN, GymIcons.Offline, "Offline", text = "Dein Training wird gespeichert und später synchronisiert.")
            SyncKind.ERROR -> Banner(BadgeKind.ERROR, GymIcons.Warn, "Synchronisierung fehlgeschlagen", text = "Server nicht erreichbar. Deine Daten sind lokal sicher.") {
                LinkButton("Erneut versuchen", tracked("sync", "retry", onRetry))
            }
            SyncKind.CONFLICT -> Banner(
                BadgeKind.WARN, GymIcons.Warn,
                if (ui.conflicts.size == 1) "Konflikt bei ${ui.conflicts.single().label}" else "${ui.conflicts.size} Konflikte",
                text = "Die Änderung wurde auch auf dem Server vorgenommen.",
            ) { LinkButton("Auflösen", tracked("sync", "open_conflict", onResolve)) }
            SyncKind.DISCONNECTED -> Banner(BadgeKind.ERROR, GymIcons.Warn, "Verbindung getrennt – neu koppeln", text = "Der Server kennt dieses Gerät nicht mehr. Deine Daten bleiben auf dem Gerät.") {
                LinkButton("Neu koppeln", tracked("sync", "repair", onPair))
            }
            SyncKind.UNAUTHORIZED -> Banner(BadgeKind.ERROR, GymIcons.Warn, "Zugriff abgelehnt", text = "Bitte koppele das Gerät neu.") {
                LinkButton("Neu koppeln", tracked("sync", "repair", onPair))
            }
            SyncKind.NOT_PAIRED -> if (showNotPaired) {
                Banner(BadgeKind.WARN, GymIcons.Offline, "Nicht mit einem Server verbunden", text = "Die Daten liegen nur auf diesem Gerät.") {
                    LinkButton("Verbinden", tracked("sync", "pair", onPair))
                }
            }
            else -> Unit
        }
    }
}

/** Conflict sheet: the server's version (newest wins by default) or the local edit that lost. */
@Composable
fun ConflictSheet(items: List<ConflictItem>, onKeepNewest: () -> Unit, onKeepMine: () -> Unit, onDismiss: () -> Unit) {
    GymSheet(onDismiss, title = if (items.size == 1) "Konflikt bei ${items.single().label}" else "${items.size} Konflikte") {
        Text(
            "Diese Einträge wurden auf dem Server und auf diesem Gerät geändert. Standardmäßig gilt die neueste Version.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        items.forEach { Text("• ${it.label}", style = MaterialTheme.typography.bodyLarge) }
        PrimaryButton("Neueste behalten", tracked("sync", "keep_newest", onKeepNewest), height = 52.dp)
        SoftButton("Meine behalten", tracked("sync", "keep_mine", onKeepMine), Modifier.fillMaxWidth())
    }
}
