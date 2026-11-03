package de.baschek.gymapp.ui.components

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.PathParser
import de.baschek.gymapp.R
import de.baschek.gymapp.core.model.MuscleGroup
import de.baschek.gymapp.core.stats.MuscleSummary
import de.baschek.gymapp.core.stats.formatSets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Renders the muscle map card off-screen into a bitmap (plain Android canvas, no window needed) and hands it
 * to the Android share sheet through the app's FileProvider (cache directory).
 */
object MuscleShare {
    private const val WIDTH = 1080
    private const val HEIGHT = 1500

    fun render(context: Context, title: String, subtitle: String, summary: MuscleSummary, accent: Int): Bitmap {
        val bmp = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val regular = ResourcesCompat.getFont(context, R.font.hanken_grotesk) ?: Typeface.DEFAULT
        val bold = Typeface.create(regular, Typeface.BOLD)
        c.drawColor(0xFF0C0C0C.toInt())

        val card = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF171717.toInt() }
        c.drawRoundRect(RectF(48f, 48f, WIDTH - 48f, HEIGHT - 48f), 56f, 56f, card)

        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = bold; textSize = 64f; color = 0xFFF5F5F5.toInt() }
        c.drawText(title, 100f, 150f, text)
        text.apply { typeface = regular; textSize = 40f; color = 0xFFA3A3A3.toInt() }
        c.drawText(subtitle, 100f, 210f, text)

        val base = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF242424.toInt() }
        val max = summary.max
        val scale = 2.2f
        for ((index, side) in listOf(BodySide.FRONT, BodySide.BACK).withIndex()) {
            val left = 100f + index * 450f
            val top = 260f
            c.save()
            c.translate(left, top)
            c.scale(scale, scale)
            c.drawCircle(BodyArt.HEAD_CX, BodyArt.HEAD_CY, BodyArt.HEAD_R, base)
            c.drawRoundRect(RectF(102f, 54f, 118f, 64f), 5f, 5f, base)
            for (mirrored in listOf(false, true)) {
                c.save()
                if (mirrored) {
                    c.translate(BodyArt.WIDTH, 0f)
                    c.scale(-1f, 1f)
                }
                for (shape in BodyArt.shapes(side)) {
                    val path: Path = PathParser.createPathFromPathData(shape.d)
                    c.drawPath(path, base)
                    val v = summary.totals[shape.group] ?: 0.0
                    if (v > 0.0) {
                        val heat = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = accent
                            alpha = (heatAlpha(v, max) * 255).toInt()
                        }
                        c.drawPath(path, heat)
                    }
                }
                c.restore()
            }
            c.restore()
        }

        text.apply { typeface = bold; textSize = 44f; color = 0xFFF5F5F5.toInt() }
        c.drawText("Meist belastet", 100f, 1130f, text)
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = regular; textSize = 40f; color = 0xFFF5F5F5.toInt() }
        val value = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = bold; textSize = 40f; color = 0xFFF5F5F5.toInt(); textAlign = Paint.Align.RIGHT }
        val bar = Paint(Paint.ANTI_ALIAS_FLAG)
        summary.top(4).forEachIndexed { i, (group, v) ->
            val y = 1200f + i * 64f
            c.drawText(MuscleGroup.label(group), 100f, y, label)
            bar.color = 0xFF242424.toInt()
            c.drawRoundRect(RectF(560f, y - 24f, 860f, y - 8f), 8f, 8f, bar)
            bar.color = accent
            c.drawRoundRect(RectF(560f, y - 24f, 560f + 300f * (v / max).toFloat(), y - 8f), 8f, 8f, bar)
            c.drawText(formatSets(v), 980f, y, value)
        }
        return bmp
    }

    /** Renders, stores the PNG in the cache and opens the Android share sheet. */
    suspend fun share(context: Context, title: String, subtitle: String, summary: MuscleSummary, accent: Int) {
        val file = withContext(Dispatchers.Default) {
            val bitmap = render(context, title, subtitle, summary, accent)
            val dir = File(context.cacheDir, "shared").apply { mkdirs() }
            val target = File(dir, "muskelkarte.png")
            target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            target
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "Karte teilen")
        if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }
}
