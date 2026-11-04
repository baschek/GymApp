package de.baschek.gymapp.ui.workout

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.baschek.gymapp.core.training.WeightHint
import de.baschek.gymapp.telemetry.Telemetry
import de.baschek.gymapp.ui.components.GymIcons
import de.baschek.gymapp.ui.theme.Spacing
import androidx.compose.material3.Icon

/**
 * Suggestion for the next set with its origin: a tappable chip ("142,5 kg übernehmen") with the reason
 * in one line, and "Woher kommt das?" which expands the calculation and the progress towards a
 * permanent working weight. [applied] is true when the suggested weight is already in use.
 */
@Composable
internal fun WeightHintBlock(
    hint: WeightHint,
    applied: Boolean,
    onApply: (() -> Unit)?,
    screen: String,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val showChip = onApply != null && !applied
        Surface(
            onClick = { if (showChip) { Telemetry.track(screen, "hint_apply"); onApply?.invoke() } },
            enabled = showChip,
            shape = RoundedCornerShape(Spacing.RadiusMd),
            color = cs.primary.copy(alpha = if (showChip) 0.18f else 0.08f),
        ) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(when { hint.trend > 0 -> GymIcons.Up; hint.trend < 0 -> GymIcons.Down; else -> GymIcons.Check }, contentDescription = null, tint = cs.primary)
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            showChip -> hint.chipText
                            applied -> "${hint.chipText.removeSuffix(" übernehmen")} ${if (hint.trend == 0) "beibehalten" else "übernommen"}"
                            else -> "Vorschlag ${hint.chipText.removeSuffix(" übernehmen")} ${hint.arrow}".trim()
                        },
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), color = cs.onSurface,
                    )
                    Text(hint.reason, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
            }
        }
        Text(
            if (open) "Weniger" else "Woher kommt das?",
            Modifier.clickable(role = Role.Button) { Telemetry.track(screen, if (open) "hint_close" else "hint_open"); open = !open }
                .heightIn(min = 32.dp).padding(horizontal = 4.dp),
            style = MaterialTheme.typography.labelLarge, color = cs.primary,
        )
        if (open) {
            Text(hint.detail, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
            Text(
                "Dauerhaft: ${hint.evidence}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}
