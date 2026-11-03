package de.baschek.gymapp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.baschek.gymapp.ui.theme.Spacing

/** Top padding that keeps content clear of the status bar and the camera cut-out. */
@Composable
fun topInset(): Dp = WindowInsets.statusBars.union(WindowInsets.displayCutout).asPaddingValues().calculateTopPadding()

@Composable
fun bottomInset(): Dp = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

/**
 * Standard scrolling screen: edge-to-edge, 16 dp gutters, content scrolls under the translucent bottom bar
 * ([bottomBar]) and, when [pinnedHeight] is set, leaves room for a pinned action area.
 */
@Composable
fun ScreenList(
    modifier: Modifier = Modifier,
    bottomBar: Boolean = true,
    pinnedHeight: Dp = 0.dp,
    state: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
    val bottom = (if (bottomBar) bottomBarSpace() else bottomInset()) + pinnedHeight + Spacing.s6
    LazyColumn(
        modifier.fillMaxSize(),
        state = state,
        contentPadding = PaddingValues(start = Spacing.Gutter, end = Spacing.Gutter, top = topInset() + Spacing.s2, bottom = bottom),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = content,
    )
}

/** Large screen title with an optional trailing slot (sync badge, actions). */
@Composable
fun TopTitle(title: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onSurface)
        if (trailing != null) Box(Modifier.padding(start = Spacing.s2)) { trailing() }
    }
}

/** Back arrow with a small title for detail screens. */
@Composable
fun BackBar(title: String, onBack: () -> Unit, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().heightIn(min = Spacing.Touch), verticalAlignment = Alignment.CenterVertically) {
        GhostIconButton(GymIcons.Back, "Zurück", onBack, tint = MaterialTheme.colorScheme.onSurface)
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge.copy(fontSize = 17.sp), maxLines = 1)
        if (trailing != null) trailing()
    }
}

/**
 * Pinned action area at the bottom of a screen: fades the content out behind it and keeps clear of the
 * gesture bar. With [aboveBottomBar] it sits above the navigation bar.
 */
@Composable
fun PinnedBottom(modifier: Modifier = Modifier, aboveBottomBar: Boolean = false, content: @Composable () -> Unit) {
    val bg = MaterialTheme.colorScheme.background
    Column(
        modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, bg), startY = 0f, endY = 70f))
            .padding(top = 26.dp, start = Spacing.Gutter, end = Spacing.Gutter, bottom = if (aboveBottomBar) bottomBarSpace() + Spacing.s2 else bottomInset() + Spacing.s2),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) { content() }
}

/** Box helper: content plus a pinned bottom area. */
@Composable
fun PinnedScreen(pinned: @Composable () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxSize()) {
        content()
        Box(Modifier.align(Alignment.BottomCenter)) { pinned() }
    }
}
