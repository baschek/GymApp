package de.baschek.gymapp.watch.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import de.baschek.gymapp.watch.R
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * Tokens and building blocks of the watch UI: pure black, paper accent (selectable like the phone), status always
 * with text or symbol (spec A3). Every interactive element reports its tap through [LocalInteraction] (telemetry and
 * the navigation hook) and is at least 48 dp in both directions (spec A7).
 */

internal val Paper = Color(0xfff5f5f5)
internal val Muted = Color(0xffa3a3a3)
internal val Surface = Color(0xff242424)
internal val Track = Color(0xff1d1d1d)
internal val Outline = Color(0xff8a8a8a)
internal val Warning = Color(0xffe0b15a)
internal val ErrorColor = Color(0xfff07373)
internal val Hanken = FontFamily(Font(R.font.hanken_grotesk))

internal data class WatchAccent(val color: Color, val onColor: Color)
internal val LocalAccent = staticCompositionLocalOf { WatchAccent(Paper, Color(0xff0c0c0c)) }
internal fun watchAccent(key: String?): WatchAccent = when (key) {
    "salbei" -> WatchAccent(Color(0xffa8cf8e), Color(0xff14210d))
    "dunst" -> WatchAccent(Color(0xff9db2cc), Color(0xff0d1624))
    "sand" -> WatchAccent(Color(0xffd9c9a6), Color(0xff241d0e))
    else -> WatchAccent(Paper, Color(0xff0c0c0c))
}

/** Reports a tap with a telemetry-safe element name; provided by [WatchUi] (one place, see [WatchNavObserver]). */
internal val LocalInteraction = staticCompositionLocalOf<(String) -> Unit> { {} }

private val noPadding = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))

/** Percentage paddings for lists (spec A7): about 5 % at the sides, more at top and bottom of the round screen. */
@Composable
internal fun listPadding(): PaddingValues {
    val configuration = LocalConfiguration.current
    val width = configuration.screenWidthDp.dp
    val height = configuration.screenHeightDp.dp
    return PaddingValues(start = width * 0.052f, end = width * 0.052f, top = height * 0.14f, bottom = height * 0.22f)
}

@Composable
internal fun Small(text: String, modifier: Modifier = Modifier, color: Color = Muted, size: Int = 12, maxLines: Int = 2) {
    if (text.isNotBlank()) Text(text, modifier = modifier, color = color, fontFamily = Hanken, fontSize = size.sp, lineHeight = (size + 3).sp,
        textAlign = TextAlign.Center, maxLines = maxLines, overflow = TextOverflow.Ellipsis, style = noPadding)
}

@Composable
internal fun Title(text: String, modifier: Modifier = Modifier, size: Int = 18, color: Color = Paper, maxLines: Int = 2) {
    Text(text, modifier = modifier, color = color, fontFamily = Hanken, fontWeight = FontWeight.Bold, fontSize = size.sp, lineHeight = (size + 2).sp,
        maxLines = maxLines, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, style = noPadding)
}

/** Grey outline text for the always-on display (spec M). */
@Composable
internal fun AmbientText(text: String, size: Int = 20) {
    if (text.isNotBlank()) Text(text, color = Outline, fontFamily = Hanken, fontWeight = FontWeight.Bold, fontSize = size.sp,
        textAlign = TextAlign.Center, maxLines = 2, style = TextStyle(drawStyle = Stroke(width = 1f)))
}

/** A round button (check, "Weiter", undo); never the wide edge button (spec A8). */
@Composable
internal fun RoundButton(symbol: String, description: String, modifier: Modifier = Modifier, primary: Boolean = true, size: Dp = 56.dp, enabled: Boolean = true, onClick: () -> Unit) {
    val track = LocalInteraction.current
    val accent = LocalAccent.current
    FilledIconButton(
        onClick = { track(elementName(description)); onClick() },
        modifier = modifier.size(size).semantics { contentDescription = description },
        enabled = enabled,
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = if (primary) accent.color else Surface,
            contentColor = if (primary) accent.onColor else Paper,
        ),
    ) {
        Text(symbol, fontFamily = Hanken, fontWeight = FontWeight.Bold, fontSize = if (symbol.length > 2) 13.sp else 22.sp,
            maxLines = 1, textAlign = TextAlign.Center)
    }
}

/** A full-width list button; disabled when the action would change nothing (spec H, L). */
@Composable
internal fun ListButton(
    text: String, detail: String = "", selected: Boolean = false, enabled: Boolean = true, color: Long? = null,
    description: String = text, onClick: () -> Unit,
) {
    val track = LocalInteraction.current
    val accent = LocalAccent.current
    val colors = if (selected) ButtonDefaults.buttonColors(containerColor = accent.color, contentColor = accent.onColor, secondaryContentColor = accent.onColor)
    else ButtonDefaults.buttonColors(containerColor = Surface, contentColor = Paper, secondaryContentColor = Muted)
    val secondary: (@Composable RowScope.() -> Unit)? = if (detail.isNotBlank()) {
        { Text(detail, fontFamily = Hanken, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
    } else null
    val icon: (@Composable BoxScope.() -> Unit)? = if (color != null) {
        { Box(Modifier.size(10.dp).background(Color(color), CircleShape)) }
    } else null
    Button(
        onClick = { track(elementName(description)); onClick() },
        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
        secondaryLabel = secondary,
        icon = icon,
        enabled = enabled,
        colors = colors,
    ) {
        Text(text, fontFamily = Hanken, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** On/off setting as a switch row (spec J). */
@Composable
internal fun SwitchRow(text: String, checked: Boolean, detail: String = "", onChange: (Boolean) -> Unit) {
    val track = LocalInteraction.current
    val secondary: (@Composable RowScope.() -> Unit)? = if (detail.isNotBlank()) {
        { Text(detail, fontFamily = Hanken, fontSize = 12.sp, maxLines = 2) }
    } else null
    SwitchButton(
        checked = checked,
        onCheckedChange = { track(elementName(text)); onChange(it) },
        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
        secondaryLabel = secondary,
    ) {
        Text(text, fontFamily = Hanken, fontSize = 14.sp, maxLines = 3)
    }
}

/** A read-only fact (label above, value below). */
@Composable
internal fun InfoCard(label: String, value: String, valueColor: Color = Paper) {
    Column(Modifier.fillMaxWidth().background(Surface, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 8.dp)) {
        Text(label, color = Muted, fontFamily = Hanken, fontSize = 11.sp)
        Text(value, color = valueColor, fontFamily = Hanken, fontSize = 14.sp, lineHeight = 18.sp)
    }
}

@Composable
internal fun CenterText(text: String, color: Color = Muted, size: Int = 12) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Small(text, color = color, size = size, maxLines = 4) }
}

/** A scrolling list with header (TransformingLazyColumn + ListHeader inside ScreenScaffold, spec A8). */
@Composable
internal fun ListScreen(title: String, empty: Boolean = false, emptyText: String = "", tag: String = "watch-list", content: TransformingLazyColumnScope.() -> Unit) {
    val list = rememberTransformingLazyColumnState()
    ScreenScaffold(scrollState = list, contentPadding = listPadding()) { padding ->
        TransformingLazyColumn(state = list, contentPadding = padding, modifier = Modifier.fillMaxSize().testTag(tag),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item { ListHeader { Text(title, color = Muted, fontFamily = Hanken, fontWeight = FontWeight.Bold, fontSize = 13.sp, textAlign = TextAlign.Center, maxLines = 2) } }
            if (empty) item { CenterText(emptyText) }
            content()
        }
    }
}

/** A small section header inside a list (left page: "Übungen", "Dieser Satz", "Training"). */
@Composable
internal fun SectionHeader(text: String) {
    ListHeader { Text(text.uppercase(java.util.Locale.GERMANY), color = Muted, fontFamily = Hanken, fontWeight = FontWeight.Bold, fontSize = 11.sp) }
}

/** A tappable value (weight or reps) opening the editor; at least 48 dp (spec D). */
@Composable
internal fun ValueButton(value: String, unit: String, description: String, onClick: () -> Unit) {
    val track = LocalInteraction.current
    Column(Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp).clickable { track(elementName(description)); onClick() }
        .semantics(mergeDescendants = true) { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(value, color = Paper, fontSize = 24.sp, fontWeight = FontWeight.Bold, fontFamily = Hanken, lineHeight = 25.sp, maxLines = 1, style = noPadding)
        Text(unit, color = Muted, fontSize = 10.sp, fontFamily = Hanken, maxLines = 1, style = noPadding)
    }
}

/** A selectable compact choice (effort picker, add-on chips); 48 dp high. */
@Composable
internal fun Chip(text: String, selected: Boolean, width: Dp, description: String = text, onClick: () -> Unit) {
    val track = LocalInteraction.current
    val accent = LocalAccent.current
    Box(Modifier.width(width).height(48.dp).background(if (selected) accent.color else Surface, RoundedCornerShape(24.dp))
        .clickable { track(elementName(description)); onClick() }
        .semantics { contentDescription = description + if (selected) ", ausgewählt" else "" }, contentAlignment = Alignment.Center) {
        Text(text, color = if (selected) accent.onColor else Paper, fontFamily = Hanken, fontWeight = FontWeight.Bold, fontSize = 12.sp,
            maxLines = 2, textAlign = TextAlign.Center, lineHeight = 13.sp, style = noPadding)
    }
}

/** Ring at the edge (rest countdown, setup timer, workout duration). */
@Composable
internal fun EdgeRing(fraction: Float, color: Color = LocalAccent.current.color, width: Dp = 5.dp) {
    Canvas(Modifier.fillMaxSize().padding(3.dp)) {
        val stroke = width.toPx()
        drawArc(Track, -90f, 360f, false, style = Stroke(stroke, cap = StrokeCap.Round))
        if (fraction > 0f) drawArc(color, -90f, 359.9f * fraction.coerceIn(0f, 1f), false, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

/** A null sample starts a new segment; no values are filled into missing intervals. */
@Composable
internal fun PulseCurve(samples: List<Pair<Long, Int?>>) {
    val valid = samples.mapNotNull { it.second }
    if (valid.size < 2) return
    val first = samples.first().first
    val span = (samples.last().first - first).coerceAtLeast(1L)
    val low = (valid.minOrNull() ?: 0).toFloat()
    val range = ((valid.maxOrNull() ?: 0) - low).coerceAtLeast(1f)
    val accent = LocalAccent.current
    Canvas(Modifier.width(120.dp).height(30.dp).semantics { contentDescription = "Pulsverlauf, Messlücken bleiben leer" }) {
        var previous: Offset? = null
        samples.forEach { (time, bpm) ->
            if (bpm == null) previous = null else {
                val point = Offset(((time - first).toFloat() / span).coerceIn(0f, 1f) * size.width,
                    size.height - ((bpm - low) / range).coerceIn(0f, 1f) * size.height)
                previous?.let { drawLine(accent.color, it, point, strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round) }
                previous = point
            }
        }
    }
}

/** Monochrome heart silhouette; it does not depend on the device's emoji font. */
@Composable
internal fun HeartIcon(color: Color = Muted, size: Dp = 14.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        val heart = Path().apply {
            moveTo(w * .5f, h * .88f)
            cubicTo(w * .42f, h * .8f, w * .08f, h * .56f, w * .08f, h * .32f)
            cubicTo(w * .08f, h * .09f, w * .34f, h * .03f, w * .5f, h * .24f)
            cubicTo(w * .66f, h * .03f, w * .92f, h * .09f, w * .92f, h * .32f)
            cubicTo(w * .92f, h * .56f, w * .58f, h * .8f, w * .5f, h * .88f)
            close()
        }
        drawPath(heart, color)
    }
}

internal fun formatWeight(value: Double): String = if (value == value.roundToInt().toDouble()) value.roundToInt().toString() else "%.2f".format(java.util.Locale.GERMANY, value).trimEnd('0').trimEnd(',')
internal fun setValue(weight: Double?, amount: Double, unit: String, weightLabel: String): String = (weight?.let { "${formatWeight(it)} $weightLabel × " } ?: "") + "${formatWeight(amount)} $unit"
/** "1:12"; after the end of a rest "+0:07" (spec F). */
internal fun clock(seconds: Int): String { val n = abs(seconds); return (if (seconds < 0) "+" else "") + "%d:%02d".format(n / 60, n % 60) }
/** Minutes instead of seconds for the always-on display (spec M): "ca. 2 min", "unter 1 min", "+3 min". */
internal fun ambientMinutes(seconds: Int): String = when {
    seconds < 0 -> "+${(-seconds) / 60} min"
    seconds < 60 -> "unter 1 min"
    else -> "ca. ${(seconds + 59) / 60} min"
}
