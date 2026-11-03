package de.baschek.gymapp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.baschek.gymapp.core.model.Gym
import de.baschek.gymapp.ui.theme.GymAppTheme
import de.baschek.gymapp.ui.theme.Spacing

/** Identity colour of a gym from its synced document ("#rrggbb"); the neutral fallback otherwise. */
fun gymStripeColor(gym: Gym?, fallback: Color): Color {
    val hex = gym?.color ?: return fallback
    return runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(fallback)
}

/**
 * One row (56 dp) of the gym list on "Heute": a thin rounded stripe in the gym's colour (decoration only,
 * the name carries the meaning), name and one line of context, and at the end a check mark when selected
 * or the subtle [hint] ("Zuletzt hier"). Row height and text positions never change with the selection.
 * Put the rows in a [Group] separated by [Hairline]s.
 */
@Composable
fun GymChoiceRow(
    name: String,
    subtitle: String?,
    stripe: Color,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().heightIn(min = Spacing.TouchMin)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = Spacing.s4, vertical = Spacing.s2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s3),
    ) {
        Box(Modifier.width(4.dp).height(28.dp).clip(RoundedCornerShape(2.dp)).background(stripe))
        Column(Modifier.weight(1f)) {
            Text(
                name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal),
                color = cs.onSurface,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
        }
        // Fixed-size end slot: tick, hint or nothing, so the text never shifts when the selection changes.
        Box(Modifier.widthIn(min = 22.dp), contentAlignment = Alignment.CenterEnd) {
            when {
                selected -> TickIcon()
                hint != null -> Text(hint, maxLines = 1, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant.copy(alpha = 0.7f))
            }
        }
    }
}

/** Small colour dot for lists. */
@Composable
fun GymDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(10.dp).clip(CircleShape).background(color))
}

@Preview(showBackground = true, backgroundColor = 0xFF0C0C0C)
@Composable
private fun GymChoiceRowPreview() {
    GymAppTheme {
        Group(Modifier.padding(16.dp)) {
            GymChoiceRow("Beispielstudio", "Standard-Geräte", Color(0xFF4C8DFF), selected = true, onClick = {})
            Hairline()
            GymChoiceRow("Zweites Studio", "Kabelzüge · Sauna", Color(0xFF8E99A8), selected = false, onClick = {}, hint = "Zuletzt hier")
        }
    }
}
