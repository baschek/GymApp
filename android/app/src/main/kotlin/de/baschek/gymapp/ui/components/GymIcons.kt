package de.baschek.gymapp.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Outlined 24 dp line icons (1.8 dp stroke, round caps) drawn from SVG path data, so no icon
 * library is needed. Tint them with `Icon(tint = ...)`.
 */
object GymIcons {
    private fun icon(name: String, vararg paths: String): ImageVector {
        val b = ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        for (d in paths) {
            b.addPath(
                pathData = PathParser().parsePathString(d).toNodes(),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return b.build()
    }

    private const val CIRCLE = "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18z"

    val Ok = icon("ok", CIRCLE, "M8 12.5l2.7 2.7L16 9.5")
    val Offline = icon(
        "offline", "M3 3l18 18", "M5 12.5a10 10 0 0 1 4-2.4M19 12.5a10 10 0 0 0-3-2M8.5 16a5 5 0 0 1 7 0", "M12 19.5v.01",
    )
    val Warn = icon("warn", "M12 4l9 16H3z", "M12 10v4M12 17.2v.01")
    val Sync = icon("sync", "M20 11a8 8 0 0 0-14-4M4 5v4h4", "M4 13a8 8 0 0 0 14 4M20 19v-4h-4")
    val Skip = icon("skip", "M6 5l9 7-9 7zM18 5v14")
    val Close = icon("close", "M6 6l12 12M18 6L6 18")
    val Link = icon(
        "link", "M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1",
    )
    val Today = icon("today", "M7 5h10a3 3 0 0 1 3 3v9a3 3 0 0 1-3 3H7a3 3 0 0 1-3-3V8a3 3 0 0 1 3-3z", "M4 10h16M8 3v4M16 3v4")
    val Plans = icon("plans", "M8 6h12M8 12h12M8 18h12M4 6h.01M4 12h.01M4 18h.01")
    val History = icon("history", CIRCLE, "M12 7v5l3 2")
    val Chart = icon("chart", "M5 20V10M12 20V4M19 20v-7")
    val More = icon("more", "M5 12h.01M12 12h.01M19 12h.01")
    val Check = icon("check", "M5 12.5l4.5 4.5L19 7.5")
    val Play = icon("play", "M8 5v14l11-7z")
    val Up = icon("up", "M6 14l6-6 6 6")
    val Down = icon("down", "M6 10l6 6 6-6")
    val Plus = icon("plus", "M12 5v14M5 12h14")
    val Minus = icon("minus", "M5 12h14")
    val Back = icon("back", "M15 5l-7 7 7 7")
    val Chevron = icon("chevron", "M9 5l7 7-7 7")
    val Share = icon("share", "M12 15V4M8 8l4-4 4 4", "M5 12v7h14v-7")
    val Search = icon("search", "M11 4.5a6.5 6.5 0 1 0 0 13a6.5 6.5 0 1 0 0-13z", "M20 20l-4-4")
    val Camera = icon("camera", "M4 8h3l2-3h6l2 3h3v11H4z", "M12 9.5a3.5 3.5 0 1 0 0 7a3.5 3.5 0 1 0 0-7z")
    val Menu = icon("menu", "M5 7h14M5 12h14M5 17h14")
    val Edit = icon("edit", "M4 20h4L19 9l-4-4L4 16z")
    val Bell = icon("bell", "M6 17V11a6 6 0 0 1 12 0v6l1.5 2h-15zM10 21h4")
    val Target = icon("target", CIRCLE, "M12 7a5 5 0 1 0 0 10a5 5 0 1 0 0-10z", "M12 12v.01")
    val Dumbbell = icon("dumbbell", "M6.5 6.5v11M17.5 6.5v11M3.5 9v6M20.5 9v6M6.5 12h11")
}
