package de.baschek.gymapp.ui.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    TODAY("today", "Heute", GymIcons.Today),
    PLANS("plans", "Pläne", GymIcons.Plans),
    HISTORY("history", "Verlauf", GymIcons.History),
    PROGRESS("progress", "Fortschritt", GymIcons.Chart),
    MORE("more", "Mehr", GymIcons.More),
}

/** Height of the bar without the gesture inset; screens leave this much room at the bottom. */
val BottomBarHeight: Dp = 80.dp

@Composable
fun bottomBarSpace(): Dp = BottomBarHeight + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

/** Translucent bottom bar (Heute, Pläne, Verlauf, Fortschritt, Mehr). Hidden during training. */
@Composable
fun GymBottomBar(selected: Tab, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    NavigationBar(modifier, containerColor = cs.background.copy(alpha = 0.88f), tonalElevation = 0.dp) {
        Tab.entries.forEach { tab ->
            NavigationBarItem(
                selected = tab == selected,
                onClick = { onSelect(tab) },
                icon = { Icon(tab.icon, contentDescription = null) },
                label = { Text(tab.label, maxLines = 1) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = cs.onSurface, selectedTextColor = cs.onSurface, indicatorColor = cs.primaryContainer,
                    unselectedIconColor = cs.onSurfaceVariant, unselectedTextColor = cs.onSurfaceVariant,
                ),
            )
        }
    }
}
