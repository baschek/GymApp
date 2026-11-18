package de.baschek.gymapp.recorder.shared

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.Window
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async

@Composable
fun RecorderOverlay(recorder: DeviceRecorder, modifier: Modifier = Modifier) {
    LifecycleResumeEffect(recorder) {
        recorder.checkDeadline()
        onPauseOrDispose { }
    }
    val active by recorder.recording.collectAsState()
    if (!active) return
    val view = LocalView.current
    val window = LocalActivity.current?.window
    val scope = rememberCoroutineScope()
    DisposableEffect(recorder, window, view) {
        val capture = ScreenCapture { valid ->
            scope.async {
                withFrameNanos { }
                if (window != null && valid() && view.isShown && view.hasWindowFocus() && recorder.claimCapture()) {
                    try {
                        captureWindow(window, view)?.takeIf { valid() }
                    } catch (_: IllegalArgumentException) {
                        null // PixelCopy can lose its surface as the activity is stopping.
                    } catch (_: IllegalStateException) {
                        null // The Compose root can detach between the frame and the callback.
                    }
                } else null
            }
        }
        recorder.screenshot = capture
        onDispose { if (recorder.screenshot === capture) recorder.screenshot = null }
    }
    androidx.compose.foundation.Canvas(modifier.size(9.dp).semantics { contentDescription = "Navigationsaufzeichnung aktiv" }) {
        drawCircle(androidx.compose.ui.graphics.Color.White)
        drawCircle(androidx.compose.ui.graphics.Color.Red, radius = size.minDimension / 3)
    }
}

private val captions = setOf(
    "Heute", "Pläne", "Verlauf", "Fortschritt", "Mehr", "Einstellungen", "Training", "Pause",
    "Start", "Weiter", "Zurück", "Fertig", "Speichern", "Abbrechen", "Training beenden",
    "Übungen", "Notiz", "Gewicht ändern", "Satz geschafft", "Rückgängig", "Aufwärmsatz", "✓",
)

internal data class Region(val bounds: RectF, val control: Boolean, val caption: String?)

private fun collectRegions(node: SemanticsNode, regions: MutableList<Region>) {
    val rect = node.boundsInRoot
    if (rect.isEmpty) return
    val config = node.config
    val control = SemanticsActions.OnClick in config
    val texts = config.getOrNull(SemanticsProperties.Text)
    val editable = SemanticsProperties.EditableText in config
    if (control || texts != null || editable) {
        val caption = if (editable) null else texts?.singleOrNull()?.text?.takeIf(captions::contains)
        regions += Region(RectF(rect.left / 2, rect.top / 2, rect.right / 2, rect.bottom / 2), control, caption)
    }
    node.children.forEach { collectRegions(it, regions) }
}

private suspend fun captureWindow(window: Window, view: View): Bitmap? {
    val owner = (view as? RootForTest)?.semanticsOwner ?: return null
    if (view.width < 2 || view.height < 2) return null
    val regions = mutableListOf<Region>()
    collectRegions(owner.unmergedRootSemanticsNode, regions)
    val xy = IntArray(2).also(view::getLocationInWindow)
    val src = Rect(xy[0], xy[1], xy[0] + view.width, xy[1] + view.height)
    val pixels = createBitmap(src.width() / 2, src.height() / 2)
    val result = CompletableDeferred<Int>()
    PixelCopy.request(window, src, pixels, { result.complete(it) }, Handler(Looper.getMainLooper()))
    if (result.await() != PixelCopy.SUCCESS) return null
    redact(pixels, regions)
    return pixels
}

/** Positive mask: retain only a flat sampled surface colour per control. Even custom-drawn graphs,
 * photos, overlays and text missing from accessibility semantics are erased before WebP encoding. */
internal fun redact(pixels: Bitmap, regions: List<Region>) {
    val fills = regions.map { region ->
        val x = region.bounds.centerX().toInt().coerceIn(0, pixels.width - 1)
        val y = (region.bounds.top + 2).toInt().coerceIn(0, pixels.height - 1)
        val sample = pixels[x, y]
        Color.rgb(Color.red(sample) and 0xe0, Color.green(sample) and 0xe0, Color.blue(sample) and 0xe0)
    }
    pixels.eraseColor(Color.BLACK)
    val canvas = Canvas(pixels)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    for ((i, region) in regions.withIndex()) {
        val rect = region.bounds
        if (region.control) {
            paint.color = fills[i]
            canvas.drawRoundRect(rect, 4f, 4f, paint)
            paint.style = Paint.Style.STROKE
            paint.color = Color.GRAY
            canvas.drawRoundRect(rect, 4f, 4f, paint)
            paint.style = Paint.Style.FILL
        }
        if (region.caption in captions) {
            paint.color = Color.WHITE
            paint.textSize = minOf(rect.height() * 0.8f, 18f)
            canvas.drawText(requireNotNull(region.caption), rect.left, rect.centerY() - (paint.ascent() + paint.descent()) / 2, paint)
        } else if (!region.control) {
            paint.color = Color.DKGRAY
            canvas.drawRect(rect, paint)
        }
    }
}
