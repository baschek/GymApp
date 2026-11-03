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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.sp
import de.baschek.gymapp.ui.theme.GymAppTheme
import de.baschek.gymapp.ui.theme.gymColors
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Exercise trend: weight as a solid accent line with dots, estimated 1RM as a dotted line (never colour
 * alone), three dashed grid lines with four labelled values, the newest value ringed.
 * [description] states the trend in words for TalkBack.
 */
@Composable
fun ProgressChart(
    weights: List<Double>,
    oneRepMax: List<Double>,
    description: String,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val skipped = MaterialTheme.gymColors.skipped
    val measurer = rememberTextMeasurer()
    val label = TextStyle(fontSize = 11.sp, color = cs.onSurfaceVariant)

    Canvas(modifier.fillMaxWidth().aspectRatio(320f / 160f).semantics { contentDescription = description }) {
        if (weights.isEmpty()) return@Canvas
        val s = size.width / 320f
        val all = weights + oneRepMax
        val lo = floor(all.min() / 10.0) * 10.0
        var hi = ceil(all.max() / 10.0) * 10.0
        if (hi <= lo) hi = lo + 10.0
        val w = 300f * s
        val h = 140f * s
        fun x(i: Int) = (34f + (if (weights.size < 2) 0f else i * (w / s - 44f) / (weights.size - 1))) * s
        fun y(v: Double) = (8f * s + (h - 8f * s) * (1.0 - (v - lo) / (hi - lo))).toFloat()

        for (k in 0..3) {
            val tick = lo + (hi - lo) * k / 3.0
            val yy = y(tick)
            drawLine(cs.outlineVariant, Offset(30f * s, yy), Offset(316f * s, yy), strokeWidth = 1f * s, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f * s, 4f * s)))
            val text = measurer.measure(tick.roundToInt().toString(), label)
            drawText(text, topLeft = Offset(0f, yy - text.size.height / 2f))
        }
        fun line(values: List<Double>): Path = Path().apply {
            values.forEachIndexed { i, v -> if (i == 0) moveTo(x(i), y(v)) else lineTo(x(i), y(v)) }
        }
        if (oneRepMax.size >= 2) {
            drawPath(
                line(oneRepMax), cs.onSurfaceVariant,
                style = Stroke(width = 2f * s, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(1f * s, 6f * s))),
            )
        }
        drawPath(line(weights), cs.primary, style = Stroke(width = 3f * s, cap = StrokeCap.Round, join = StrokeJoin.Round))
        weights.forEachIndexed { i, v -> drawCircle(cs.primary, radius = 3.5f * s, center = Offset(x(i), y(v))) }
        val last = weights.lastIndex
        drawCircle(skipped, radius = 8f * s, center = Offset(x(last), y(weights[last])), style = Stroke(width = 2f * s))
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF171717)
@Composable
private fun ChartPreview() {
    GymAppTheme {
        val w = listOf(40.0, 42.5, 45.0, 47.5, 50.0, 52.5, 55.0)
        ProgressChart(w, w.map { it * 1.27 }, "Gewicht steigt von 40 auf 55 Kilogramm")
    }
}
