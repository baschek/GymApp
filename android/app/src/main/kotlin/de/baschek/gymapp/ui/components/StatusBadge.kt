package de.baschek.gymapp.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.baschek.gymapp.ui.theme.GymAppTheme
import de.baschek.gymapp.ui.theme.Spacing
import de.baschek.gymapp.ui.theme.gymColors

/** Colour meaning of a status. Color is never the only signal: every badge has an icon and words. */
enum class BadgeKind { OK, WARN, ERROR, NEUTRAL }

@Composable
fun badgeColor(kind: BadgeKind): Color = when (kind) {
    BadgeKind.OK -> MaterialTheme.gymColors.done
    BadgeKind.WARN -> MaterialTheme.gymColors.skipped
    BadgeKind.ERROR -> MaterialTheme.gymColors.pain
    BadgeKind.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** Icon + text, subdued; only done, skipped and pain get a colour. */
@Composable
fun StatusBadge(kind: BadgeKind, icon: ImageVector, text: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, contentDescription = null, tint = badgeColor(kind), modifier = Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/**
 * Full-width notice for states that need words: offline, sync error, conflict, assistant changes,
 * missing machine profile. [actions] are 48 dp text links.
 */
@Composable
fun Banner(
    kind: BadgeKind,
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    text: String? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    val c = badgeColor(kind)
    Surface(
        modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(Spacing.RadiusBanner),
        color = c.copy(alpha = 0.13f).compositeOver(MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(Spacing.s3)) {
            Icon(icon, contentDescription = null, tint = c, modifier = Modifier.padding(top = 1.dp).size(20.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                if (text != null) Text(text, style = MaterialTheme.typography.bodyMedium)
                if (actions != null) Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s1), content = actions)
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0C0C0C)
@Composable
private fun StatusPreview() {
    GymAppTheme {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            StatusBadge(BadgeKind.OK, GymIcons.Ok, "Synchronisiert 17:42")
            StatusBadge(BadgeKind.WARN, GymIcons.Offline, "Offline · 3 offen")
            StatusBadge(BadgeKind.ERROR, GymIcons.Warn, "Sync-Fehler")
            Banner(BadgeKind.WARN, GymIcons.Offline, "Offline", text = "Dein Training wird gespeichert und später synchronisiert.")
            Banner(BadgeKind.ERROR, GymIcons.Warn, "Synchronisierung fehlgeschlagen", text = "Deine Daten sind lokal sicher.") {
                LinkButton("Erneut versuchen", onClick = {})
            }
        }
    }
}
