package de.baschek.gymapp.ui.workout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import de.baschek.gymapp.core.model.Session
import de.baschek.gymapp.core.model.SessionExercise
import de.baschek.gymapp.core.model.SetStatus
import de.baschek.gymapp.core.model.SetType
import de.baschek.gymapp.core.training.canMoveExercise
import de.baschek.gymapp.core.training.currentExerciseIndex
import de.baschek.gymapp.core.training.isSkipped
import de.baschek.gymapp.ui.components.GhostIconButton
import de.baschek.gymapp.ui.components.Group
import de.baschek.gymapp.ui.components.GymIcons
import de.baschek.gymapp.ui.components.Hairline
import de.baschek.gymapp.ui.components.LinkButton
import de.baschek.gymapp.ui.components.TickIcon
import de.baschek.gymapp.ui.fmtKg
import de.baschek.gymapp.ui.theme.Spacing
import de.baschek.gymapp.ui.tracked

/**
 * "Noch im Workout": every exercise with its state. Upcoming exercises can be moved up and down or skipped
 * (and taken back); [onAdd] appends another one. With [editable] = false it is a plain overview.
 */
@Composable
fun ExerciseListCard(
    session: Session,
    editable: Boolean,
    onMove: (exerciseId: String, delta: Int) -> Unit,
    onToggleSkip: (exerciseId: String) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
    screen: String = "pause",
) {
    val cs = MaterialTheme.colorScheme
    val current = currentExerciseIndex(session.exercises)
    Group(modifier) {
        session.exercises.forEachIndexed { i, ex ->
            if (i > 0) Hairline()
            val skipped = isSkipped(ex)
            val running = i == current
            val untouched = ex.sets.isNotEmpty() && ex.sets.all { it.status == SetStatus.PENDING }
            val done = !skipped && !running && ex.sets.none { it.status == SetStatus.PENDING }
            val canEdit = editable && (skipped || (untouched && i > current && current >= 0))
            Row(
                Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(start = 14.dp, end = 4.dp, top = Spacing.s2, bottom = Spacing.s2),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        ex.name,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = if (running) FontWeight.Bold else FontWeight.Medium),
                        color = if (skipped || done) cs.onSurfaceVariant else cs.onSurface,
                        textDecoration = if (skipped) TextDecoration.LineThrough else null,
                    )
                    Text(statusLine(ex, running, skipped, done), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
                if (canEdit) {
                    GhostIconButton(GymIcons.Up, "${ex.name} nach oben", tracked(screen, "move_up") { onMove(ex.id, -1) }, enabled = canMoveExercise(session, ex.id, -1))
                    GhostIconButton(GymIcons.Down, "${ex.name} nach unten", tracked(screen, "move_down") { onMove(ex.id, 1) }, enabled = canMoveExercise(session, ex.id, 1))
                    GhostIconButton(
                        if (skipped) GymIcons.Check else GymIcons.Close,
                        if (skipped) "${ex.name} wieder aufnehmen" else "${ex.name} überspringen",
                        tracked(screen, if (skipped) "unskip_exercise" else "skip_exercise") { onToggleSkip(ex.id) },
                    )
                } else if (done) {
                    TickIcon()
                    Spacer(Modifier.width(Spacing.s3))
                }
            }
        }
        if (editable) {
            Hairline()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                LinkButton("Übung hinzufügen", tracked(screen, "add_exercise", onAdd), height = 52.dp)
            }
        }
    }
}

private fun statusLine(ex: SessionExercise, running: Boolean, skipped: Boolean, done: Boolean): String {
    val working = ex.sets.filter { it.setType == SetType.WORKING }
    return when {
        skipped -> "übersprungen"
        done -> "erledigt"
        running -> "läuft · Satz ${working.count { it.status != SetStatus.PENDING } + 1}/${working.size}"
        else -> listOfNotNull("${working.size} Sätze", ex.suggestedWeightKg?.let { fmtKg(it) }).joinToString(" · ")
    }
}
