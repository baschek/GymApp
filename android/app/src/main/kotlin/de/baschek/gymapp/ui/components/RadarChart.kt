package de.baschek.gymapp.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.sp
import de.baschek.gymapp.core.model.RadarArea
import de.baschek.gymapp.core.stats.RadarAxis
import de.baschek.gymapp.core.stats.formatSets
import de.baschek.gymapp.core.stats.radarRadius
import de.baschek.gymapp.ui.theme.GymAppTheme
import kotlin.math.cos
import kotlin.math.sin

/**
 * Balance radar: six areas, the dashed polygon is the goal, the filled one what was trained.
 * The numbers are also in the content description (the filled area alone would not say them).
 */
@Composable
fun RadarChart(axes: List<RadarAxis>, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 12.sp, color = cs.onSurfaceVariant, fontFamily = MaterialTheme.typography.bodySmall.fontFamily)
    val description = "Netzdiagramm gegen die Ziele: " +
        axes.joinToString("; ") { "${it.area.label} ${formatSets(it.value)} von ${formatSets(it.goal)} Sätzen" }

    Canvas(modifier.fillMaxWidth().aspectRatio(300f / 250f).semantics { contentDescription = description }) {
        val s = size.width / 300f
        val cx = 150f * s
        val cy = 125f * s
        val radius = 84f * s
        val n = axes.size.coerceAtLeast(1)
        fun point(i: Int, r: Double): Offset {
            val a = -Math.PI / 2 + i * 2 * Math.PI / n
            return Offset((cx + cos(a) * radius * r).toFloat(), (cy + sin(a) * radius * r).toFloat())
        }
        fun polygon(radii: List<Double>): Path = Path().apply {
            radii.forEachIndexed { i, r -> val p = point(i, r); if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
            close()
        }
        val grid = Stroke(width = 1f * s)
        for (ring in listOf(0.33, 0.66, 1.0)) drawPath(polygon(List(n) { ring }), cs.outlineVariant, style = grid)
        for (i in 0 until n) drawLine(cs.outlineVariant, Offset(cx, cy), point(i, 1.0), strokeWidth = 1f * s)

        val goalRadii = axes.map { radarRadius(it.goal, axes) }
        val valueRadii = axes.map { radarRadius(it.value, axes) }
        if (axes.isNotEmpty()) {
            drawPath(
                polygon(goalRadii), cs.onSurfaceVariant,
                style = Stroke(width = 1.5f * s, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f * s, 4f * s))),
            )
            drawPath(polygon(valueRadii), cs.primary, alpha = 0.28f, style = Fill)
            drawPath(polygon(valueRadii), cs.primary, style = Stroke(width = 2.5f * s, join = StrokeJoin.Round))
        }
        axes.forEachIndexed { i, axis ->
            val p = point(i, 1.22)
            val layout = measurer.measure(axis.area.label, labelStyle)
            drawText(layout, topLeft = Offset(p.x - layout.size.width / 2f, p.y - layout.size.height / 2f))
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF171717)
@Composable
private fun RadarPreview() {
    GymAppTheme {
        RadarChart(
            listOf(
                RadarAxis(RadarArea.BRUST, 12.0, 12.0), RadarAxis(RadarArea.SCHULTERN, 6.0, 8.0), RadarAxis(RadarArea.ARME, 10.0, 12.0),
                RadarAxis(RadarArea.BEINE, 11.0, 20.0), RadarAxis(RadarArea.BAUCH, 5.0, 8.0), RadarAxis(RadarArea.RUECKEN, 10.0, 12.0),
            ),
        )
    }
}
