package de.baschek.gymapp.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.baschek.gymapp.core.model.MuscleGroup
import de.baschek.gymapp.core.stats.MuscleSummary
import de.baschek.gymapp.core.stats.formatSets
import de.baschek.gymapp.ui.theme.GymAppTheme
import de.baschek.gymapp.ui.theme.GymTextStyles
import de.baschek.gymapp.ui.theme.Spacing

private const val REGION_SCALE = 10f

/** Heat opacity of a muscle: brighter = more weighted sets in the period. */
fun heatAlpha(value: Double, max: Double): Float = if (max <= 0.0) 0f else (0.24 + 0.76 * (value / max)).toFloat().coerceIn(0f, 1f)

/**
 * Body drawing with the trained muscles in the accent colour (opacity by weighted sets). Tapping a muscle
 * reports its group via [onMuscleTap]. The figure is a placeholder, see [BodyArt].
 */
@Composable
fun MuscleBody(
    side: BodySide,
    summary: MuscleSummary,
    modifier: Modifier = Modifier,
    onMuscleTap: ((String) -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val accent = cs.primary
    val base = cs.surfaceContainerHigh
    val shapes = BodyArt.shapes(side)
    val paths: List<Path> = remember(side) { shapes.map { PathParser().parsePathString(it.d).toPath() } }
    val max = summary.max
    val description = "Körperkarte ${if (side == BodySide.FRONT) "Vorderseite" else "Rückseite"}" +
        summary.top(3).joinToString(prefix = ", am meisten belastet: ", separator = ", ") { (g, v) -> "${MuscleGroup.label(g)} ${formatSets(v)} Sätze" }
            .takeIf { summary.totals.isNotEmpty() }.orEmpty()

    Canvas(
        modifier.aspectRatio(BodyArt.WIDTH / BodyArt.HEIGHT)
            .semantics { contentDescription = description }
            .then(
                if (onMuscleTap == null) Modifier
                else Modifier.pointerInput(side, summary) {
                    detectTapGestures { offset ->
                        val s = size.width / BodyArt.WIDTH
                        var x = offset.x / s
                        val y = offset.y / s
                        if (x > BodyArt.WIDTH / 2) x = BodyArt.WIDTH - x
                        val hit = shapes.indices.firstOrNull { i -> pathContains(paths[i], x, y) }
                        if (hit != null) onMuscleTap(shapes[hit].group)
                    }
                },
            ),
    ) {
        val s = size.width / BodyArt.WIDTH
        withTransform({ scale(s, s, pivot = Offset.Zero) }) {
            drawCircle(base, BodyArt.HEAD_R, Offset(BodyArt.HEAD_CX, BodyArt.HEAD_CY))
            drawRoundRect(base, Offset(102f, 54f), Size(16f, 10f), CornerRadius(5f, 5f))
            for (mirrored in listOf(false, true)) {
                withTransform({
                    if (mirrored) {
                        translate(left = BodyArt.WIDTH, top = 0f)
                        scale(-1f, 1f, pivot = Offset.Zero)
                    }
                }) {
                    shapes.forEachIndexed { i, shape ->
                        drawPath(paths[i], base)
                        val v = summary.totals[shape.group] ?: 0.0
                        if (v > 0.0) drawPath(paths[i], accent, alpha = heatAlpha(v, max))
                    }
                }
            }
        }
    }
}

/** Point-in-path test through an Android Region (the Compose Path has no contains). */
internal fun pathContains(path: Path, x: Float, y: Float): Boolean {
    val scaled = Path().apply {
        addPath(path)
        transform(Matrix().apply { scale(REGION_SCALE, REGION_SCALE) })
    }
    val region = android.graphics.Region()
    val clip = android.graphics.Region(0, 0, (BodyArt.WIDTH * REGION_SCALE).toInt(), (BodyArt.HEIGHT * REGION_SCALE).toInt())
    region.setPath(scaled.asAndroidPath(), clip)
    return region.contains((x * REGION_SCALE).toInt(), (y * REGION_SCALE).toInt())
}

@Composable
private fun HeatLegend(maxText: String) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("0", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
        Box(
            Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp))
                .background(Brush.horizontalGradient(listOf(cs.primary.copy(alpha = 0.18f).compositeOver(cs.surfaceContainerHigh), cs.primary))),
        )
        Text(maxText, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
    }
}

/** The muscle map card: title, front/back switch, body, legend. */
@Composable
fun MuscleMapCard(
    title: String,
    summary: MuscleSummary,
    side: BodySide,
    onSide: (BodySide) -> Unit,
    onMuscleTap: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    GymCardSurface(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Overline(title, Modifier.weight(1f))
            Segmented(
                listOf(SegmentItem("Vorne"), SegmentItem("Hinten")), if (side == BodySide.FRONT) 0 else 1,
                { onSide(if (it == 0) BodySide.FRONT else BodySide.BACK) }, Modifier.width(160.dp),
            )
        }
        Box(Modifier.fillMaxWidth().padding(top = 12.dp), contentAlignment = Alignment.Center) {
            MuscleBody(side, summary, Modifier.widthIn(max = 190.dp).fillMaxWidth(), onMuscleTap)
        }
        HeatLegend(if (summary.max > 0) "${formatSets(summary.max)} Sätze" else "keine Sätze")
        Text(
            "Tippe auf einen Muskel, um die Übungen zu sehen.", Modifier.padding(top = 6.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** "Meist belastet": the top groups as a list with bars; tapping a row opens the same detail as the body. */
@Composable
fun MostLoadedCard(summary: MuscleSummary, onMuscleTap: (String) -> Unit, modifier: Modifier = Modifier, count: Int = 4) {
    val cs = MaterialTheme.colorScheme
    GymCardSurface(modifier.fillMaxWidth(), padding = androidx.compose.foundation.layout.PaddingValues(horizontal = Spacing.s4, vertical = Spacing.s3)) {
        Overline("Meist belastet", Modifier.padding(bottom = 4.dp))
        val top = summary.top(count)
        if (top.isEmpty()) {
            Text("Noch keine Sätze in diesem Zeitraum.", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
        }
        top.forEachIndexed { i, (group, value) ->
            if (i > 0) Hairline()
            Row(
                Modifier.fillMaxWidth().heightIn(min = Spacing.Touch).clickable { onMuscleTap(group) },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s3),
            ) {
                Text(MuscleGroup.label(group), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Box(Modifier.width(70.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(cs.surfaceContainerHigh)) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth((value / summary.max).toFloat().coerceIn(0f, 1f)).background(cs.primary))
                }
                Text(
                    formatSets(value), Modifier.widthIn(min = 30.dp), style = GymTextStyles.number.copy(fontSize = MaterialTheme.typography.bodyLarge.fontSize),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                )
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0C0C0C)
@Composable
private fun MuscleMapPreview() {
    GymAppTheme {
        val summary = MuscleSummary(
            mapOf(MuscleGroup.BRUST to 9.0, MuscleGroup.SCHULTERN to 6.0, MuscleGroup.TRIZEPS to 5.0, MuscleGroup.BAUCH to 2.0),
            emptyMap(),
        )
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            MuscleMapCard("Trainierte Muskeln heute", summary, BodySide.FRONT, {}, {})
            MostLoadedCard(summary, {})
        }
    }
}
