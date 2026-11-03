package de.baschek.gymapp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.baschek.gymapp.ui.theme.GymAppTheme
import de.baschek.gymapp.ui.theme.Spacing

data class SegmentItem(val label: String, val caption: String? = null)

/**
 * Full-width single-choice control. The selected segment is brighter and bold; no check icon so the
 * label keeps its room. [tall] gives 56 dp targets (training), otherwise 48 dp.
 */
@Composable
fun Segmented(
    items: List<SegmentItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    tall: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(Spacing.RadiusMd)).background(cs.surfaceContainerHigh).padding(3.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items.forEachIndexed { index, item ->
            val on = index == selected
            Column(
                Modifier.weight(1f).heightIn(min = if (tall) Spacing.TouchMin else Spacing.Touch)
                    .clip(RoundedCornerShape(11.dp))
                    .background(if (on) cs.onSurface.copy(alpha = 0.16f).compositeOver(cs.surfaceContainerHigh) else cs.surfaceContainerHigh.copy(alpha = 0f))
                    .selectable(selected = on, role = Role.RadioButton, onClick = { onSelect(index) })
                    .padding(horizontal = 2.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    item.label, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (on) FontWeight.Bold else FontWeight.Medium),
                    color = cs.onSurface, textAlign = TextAlign.Center, maxLines = 1,
                )
                if (item.caption != null) {
                    Text(
                        item.caption, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Normal),
                        color = if (on) cs.onSurface else cs.onSurfaceVariant, textAlign = TextAlign.Center, maxLines = 1,
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0C0C0C)
@Composable
private fun SegmentedPreview() {
    GymAppTheme {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Segmented(listOf(SegmentItem("Normal", "52 min"), SegmentItem("Schnell", "38 min"), SegmentItem("Ultra", "25 min")), 0, {}, tall = true)
            Segmented(listOf(SegmentItem("Am Limit"), SegmentItem("1–2", "übrig"), SegmentItem("3–4", "übrig"), SegmentItem("5+", "locker")), 1, {}, tall = true)
        }
    }
}
