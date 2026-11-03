package de.baschek.gymapp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.baschek.gymapp.ui.theme.GymAppTheme
import de.baschek.gymapp.ui.theme.GymTextStyles
import de.baschek.gymapp.ui.theme.Spacing
import de.baschek.gymapp.ui.theme.gymColors

/** "01:30" from seconds; negative values (overtime) get a leading plus. */
fun formatClock(seconds: Int): String {
    val abs = kotlin.math.abs(seconds)
    val text = "%02d:%02d".format(abs / 60, abs % 60)
    return if (seconds < 0) "+$text" else text
}

/**
 * The large rest card: caption, countdown (72 sp, tabular), progress bar, the next set and "+15 s".
 * At zero the countdown turns green (`done`).
 */
@Composable
fun RestTimerCard(
    caption: String,
    remainingSeconds: Int,
    progress: Float,
    nextText: String?,
    onPlus15: () -> Unit,
    modifier: Modifier = Modifier,
    badge: String? = null,
) {
    val cs = MaterialTheme.colorScheme
    GymCardSurface(modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Overline(caption)
            if (badge != null) StatusBadge(BadgeKind.OK, GymIcons.Check, badge, Modifier.padding(top = 6.dp))
            Text(
                formatClock(remainingSeconds),
                Modifier.padding(top = 6.dp, bottom = 12.dp).semantics {
                    liveRegion = LiveRegionMode.Off
                    contentDescription = "Pause, noch ${kotlin.math.max(remainingSeconds, 0)} Sekunden"
                },
                style = GymTextStyles.displayTimer,
                color = if (remainingSeconds <= 0) MaterialTheme.gymColors.done else cs.onSurface,
                textAlign = TextAlign.Center,
            )
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(cs.surfaceContainerHigh)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(cs.primary))
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s3)) {
                Text(
                    nextText.orEmpty(), Modifier.weight(1f), style = GymTextStyles.number.copy(fontSize = MaterialTheme.typography.bodyMedium.fontSize),
                    color = cs.onSurfaceVariant,
                )
                SoftButton("+15 s", onPlus15, height = Spacing.TouchMin)
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0C0C0C)
@Composable
private fun RestPreview() {
    GymAppTheme {
        Column(Modifier.padding(16.dp)) {
            RestTimerCard("Pause · als Nächstes Satz 2 von 3", 72, 0.2f, "Dann: 57,5 kg × 10", {}, badge = "Neues Gewicht: +2,5 kg")
        }
    }
}
