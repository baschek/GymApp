package de.baschek.gymapp.ui.workout

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.baschek.gymapp.core.training.WeightIncrease
import de.baschek.gymapp.telemetry.Telemetry
import de.baschek.gymapp.ui.components.GymIcons
import de.baschek.gymapp.ui.components.LinkButton
import de.baschek.gymapp.ui.components.Overline
import de.baschek.gymapp.ui.components.bottomInset
import de.baschek.gymapp.ui.fmtNum
import de.baschek.gymapp.ui.theme.GymTextStyles
import de.baschek.gymapp.ui.theme.Spacing
import de.baschek.gymapp.ui.theme.gymColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val SCREEN = "steigerung"

/** How long one exercise stays on screen before the next one follows (milliseconds). */
private const val SHOW_MS = 2600L
private const val SHOW_REDUCED_MS = 2400L

/**
 * Between the last set and the summary: one short pop per exercise whose working weight went up, one after another
 * ("Beinpresse", 60 → 65 kg). Same idea as the milestone screen of the first (web) version of the app: a dimmed screen, a
 * pulsing ring, the old weight turning into the new one in green. A tap goes to the next one, "Überspringen" straight to the
 * summary. With animations switched off in Android (system "Animationen entfernen") nothing moves: each exercise is shown
 * as a still picture.
 */
@Composable
internal fun WeightIncreaseCelebration(items: List<WeightIncrease>, onDone: () -> Unit) {
    val reduced = rememberReducedMotion()
    var index by rememberSaveable { mutableIntStateOf(0) }
    val finish by rememberUpdatedState(onDone)
    val last = items.lastIndex
    val current = index.coerceIn(0, last)
    val advance = {
        if (current >= last) {
            finish()
        } else {
            index = current + 1
        }
    }

    LaunchedEffect(Unit) { Telemetry.track(SCREEN, "view") }
    LaunchedEffect(current) {
        delay(if (reduced) SHOW_REDUCED_MS else SHOW_MS)
        if (current >= last) finish() else index = current + 1
    }

    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.fillMaxSize().background(cs.background)
            .clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null,
                role = Role.Button, onClickLabel = if (current >= last) "Zur Zusammenfassung" else "Weiter",
            ) { Telemetry.track(SCREEN, "tap_next"); advance() },
    ) {
        key(current) { CelebrationItem(items[current], reduced, Modifier.align(Alignment.Center)) }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = bottomInset() + Spacing.s4, start = Spacing.Gutter, end = Spacing.Gutter),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "${current + 1} von ${items.size} · antippen für weiter", style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
            )
            LinkButton("Überspringen", { Telemetry.track(SCREEN, "skip"); finish() }, height = Spacing.TouchMin)
        }
    }
}

/** The picture of one exercise: ring, arrow, caption, name and the weight change. */
@Composable
private fun CelebrationItem(item: WeightIncrease, reduced: Boolean, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val done = MaterialTheme.gymColors.done
    val pop = remember { Animatable(if (reduced) 1f else 0.7f) }
    val fade = remember { Animatable(if (reduced) 1f else 0f) }
    val ring = remember { Animatable(0f) }
    val newAlpha = remember { Animatable(if (reduced) 1f else 0f) }
    val flash = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        if (reduced) return@LaunchedEffect
        launch { fade.animateTo(1f, tween(300)) }
        launch { pop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMedium)) }
        delay(700)
        // the old weight turns into the new one: it appears with a short flash and a ring runs out from the arrow
        launch { newAlpha.animateTo(1f, tween(200)) }
        launch { flash.snapTo(1f); flash.animateTo(0f, tween(800)) }
        ring.animateTo(1f, tween(1000, easing = FastOutSlowInEasing))
    }

    Column(
        modifier.fillMaxWidth().padding(horizontal = Spacing.Gutter)
            .semantics(mergeDescendants = true) { contentDescription = "${item.caption()}: ${item.label()}" },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(240.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(150.dp)
                    .graphicsLayer {
                        val s = 1f + ring.value * 0.8f
                        scaleX = s; scaleY = s; alpha = if (reduced) 0f else (1f - ring.value) * 0.7f * (if (ring.value > 0f) 1f else 0f)
                    }
                    .border(BorderStroke(2.dp, done), CircleShape),
            )
            Box(
                Modifier.size(96.dp)
                    .graphicsLayer { scaleX = pop.value; scaleY = pop.value; alpha = fade.value }
                    .clip(CircleShape).background(cs.primary.copy(alpha = 0.16f))
                    .border(BorderStroke(1.dp, cs.primary), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (item.lowerIsBetter) GymIcons.Down else GymIcons.Up, contentDescription = null, Modifier.size(44.dp), tint = cs.primary)
            }
        }
        Overline(item.caption(), Modifier.graphicsLayer { alpha = fade.value }, color = cs.primary)
        Text(
            item.name, Modifier.padding(top = Spacing.s2).graphicsLayer { alpha = fade.value },
            style = MaterialTheme.typography.headlineMedium, color = cs.onSurface, textAlign = TextAlign.Center, maxLines = 2,
        )
        Row(
            Modifier.padding(top = Spacing.s4).graphicsLayer { scaleX = pop.value; scaleY = pop.value; alpha = fade.value },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s3),
        ) {
            Text(fmtNum(item.fromKg), style = GymTextStyles.number.copy(fontSize = 26.sp), color = cs.onSurfaceVariant)
            Text("→", style = GymTextStyles.number.copy(fontSize = 26.sp), color = cs.onSurfaceVariant)
            Text(
                "${fmtNum(item.toKg)} kg",
                Modifier.graphicsLayer {
                    val s = 1f + flash.value * 0.35f
                    scaleX = s; scaleY = s; alpha = newAlpha.value
                },
                style = GymTextStyles.number.copy(fontSize = 44.sp), color = done,
            )
        }
    }
}

/** The Android equivalent of "prefers-reduced-motion": the system setting "Animationen entfernen" sets the animator scale to 0. */
@Composable
private fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
}
