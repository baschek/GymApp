package de.baschek.gymapp.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
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
 * Selectable gym card: a 6 dp stripe in the gym's colour (decoration only, the name carries the
 * meaning), name and one line of context. Selected: brighter fill and an accent border.
 */
@Composable
fun GymCard(
    name: String,
    subtitle: String?,
    stripe: Color,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        shape = RoundedCornerShape(Spacing.RadiusCard),
        color = if (selected) cs.primaryContainer else cs.surfaceContainer,
        border = BorderStroke(if (selected) 1.5.dp else 0.5.dp, if (selected) cs.primary else cs.outlineVariant),
    ) {
        Row(Modifier.height(IntrinsicSize.Min).heightIn(min = 64.dp)) {
            Box(Modifier.width(6.dp).fillMaxHeight().background(stripe))
            Row(
                Modifier.weight(1f).padding(horizontal = Spacing.s4, vertical = Spacing.s3),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s3),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), color = cs.onSurface)
                    val line = listOfNotNull(subtitle, hint).joinToString(" · ")
                    if (line.isNotEmpty()) Text(line, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
                if (selected) TickIcon()
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
private fun GymCardPreview() {
    GymAppTheme {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GymCard("Beispielstudio", "Standard-Geräte", Color(0xFF4C8DFF), selected = true, onClick = {})
            GymCard("Zweites Studio", "Kabelzüge · Sauna", Color(0xFF8E99A8), selected = false, onClick = {}, hint = "Zuletzt hier")
        }
    }
}
