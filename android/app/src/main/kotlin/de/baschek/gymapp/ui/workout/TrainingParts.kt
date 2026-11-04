package de.baschek.gymapp.ui.workout

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.baschek.gymapp.core.model.SessionExercise
import de.baschek.gymapp.core.model.SetStatus
import de.baschek.gymapp.core.training.SetRow
import de.baschek.gymapp.core.training.SetRowKind
import de.baschek.gymapp.core.training.isSkipped
import de.baschek.gymapp.core.training.setRows
import de.baschek.gymapp.telemetry.Telemetry
import de.baschek.gymapp.ui.components.Group
import de.baschek.gymapp.ui.components.GymIcons
import de.baschek.gymapp.ui.components.Hairline
import de.baschek.gymapp.ui.theme.Spacing
import de.baschek.gymapp.ui.theme.gymColors

/**
 * Horizontal strip of all exercises: tap a chip to jump to that exercise (also to finished or skipped
 * ones). The shown exercise is highlighted; finished ones carry a check, skipped ones are struck through.
 */
@Composable
internal fun ExerciseStrip(
    exercises: List<SessionExercise>,
    selectedId: String?,
    onJump: (String) -> Unit,
    screen: String,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val listState = rememberLazyListState()
    val selectedIndex = exercises.indexOfFirst { it.id == selectedId }
    LaunchedEffect(selectedIndex) {
        if (selectedIndex >= 0) listState.animateScrollToItem((selectedIndex - 1).coerceAtLeast(0))
    }
    LazyRow(modifier.fillMaxWidth(), state = listState, horizontalArrangement = Arrangement.spacedBy(Spacing.s2)) {
        itemsIndexed(exercises, key = { _, ex -> ex.id }) { i, ex ->
            val selected = ex.id == selectedId
            val skipped = isSkipped(ex)
            val done = !skipped && ex.sets.isNotEmpty() && ex.sets.none { it.status == SetStatus.PENDING }
            Surface(
                onClick = { Telemetry.track(screen, "jump_chip"); onJump(ex.id) },
                shape = RoundedCornerShape(50),
                color = if (selected) cs.primary.copy(alpha = 0.28f) else cs.surfaceContainerHigh,
            ) {
                Row(
                    Modifier.heightIn(min = 44.dp).padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (done) Icon(GymIcons.Check, contentDescription = "erledigt", Modifier.size(16.dp), tint = MaterialTheme.gymColors.done)
                    Text(
                        "${i + 1} ${ex.name}",
                        Modifier.widthIn(max = 150.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium),
                        color = if (skipped || (done && !selected)) cs.onSurfaceVariant else cs.onSurface,
                        textDecoration = if (skipped) TextDecoration.LineThrough else null,
                    )
                }
            }
        }
    }
}

/**
 * All sets of the exercise as compact rows: logged sets with their values, the current set marked,
 * open sets with their target. Logged and skipped sets can be tapped (correct / take back).
 */
@Composable
internal fun SetListCard(ex: SessionExercise, onTapSet: (SetRow) -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val rows = setRows(ex)
    Group(modifier) {
        rows.forEachIndexed { i, row ->
            if (i > 0) Hairline()
            val tappable = row.kind == SetRowKind.DONE || row.kind == SetRowKind.SKIPPED
            Row(
                Modifier.fillMaxWidth().heightIn(min = 40.dp)
                    .then(
                        if (tappable) Modifier.clickable(
                            role = Role.Button,
                            onClickLabel = if (row.kind == SetRowKind.DONE) "Satz korrigieren" else "Satz wieder aufnehmen",
                        ) { Telemetry.track("training", "set_row_tap"); onTapSet(row) } else Modifier,
                    )
                    .padding(horizontal = 14.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                    when (row.kind) {
                        SetRowKind.DONE -> Icon(GymIcons.Check, contentDescription = "erledigt", Modifier.size(18.dp), tint = MaterialTheme.gymColors.done)
                        SetRowKind.CURRENT -> Icon(GymIcons.Play, contentDescription = "aktueller Satz", Modifier.size(16.dp), tint = cs.primary)
                        else -> Unit
                    }
                }
                Text(row.label, Modifier.widthIn(min = 18.dp), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                Text(
                    row.primary, Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = if (row.kind == SetRowKind.CURRENT) FontWeight.Bold else FontWeight.Medium),
                    color = if (row.kind == SetRowKind.OPEN || row.kind == SetRowKind.SKIPPED) cs.onSurfaceVariant else cs.onSurface,
                    maxLines = 1,
                )
                val tail = when (row.kind) {
                    SetRowKind.DONE -> listOfNotNull(row.secondary, "antippen").joinToString(" · ")
                    SetRowKind.SKIPPED -> listOfNotNull(row.secondary, "wieder aufnehmen").joinToString(" · ")
                    else -> row.secondary.orEmpty()
                }
                Text(tail, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}
