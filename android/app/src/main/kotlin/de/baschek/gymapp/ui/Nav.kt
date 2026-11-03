package de.baschek.gymapp.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.toMutableStateList
import de.baschek.gymapp.ui.components.Tab

/** Route strings. Detail routes carry ids after a '|' (ids never contain it). */
object Routes {
    const val TODAY = "today"
    const val PLANS = "plans"
    const val HISTORY = "history"
    const val PROGRESS = "progress"
    const val MORE = "more"
    const val EXERCISES = "exercises"
    const val GOALS = "goals"
    const val SETTINGS = "settings"
    const val PAIRING = "pairing"

    fun plan(id: String) = "plan|$id"
    fun session(id: String) = "session|$id"
    fun exercise(id: String) = "exercise|$id"
    fun machine(gymId: String, exerciseId: String) = "machine|$gymId|$exerciseId"
    fun workout(id: String) = "workout|$id"

    fun kind(route: String) = route.substringBefore('|')
    fun arg(route: String, index: Int = 1): String = route.split('|').getOrElse(index) { "" }

    /** Which tab is highlighted for a route. */
    fun tabOf(route: String): Tab = when (kind(route)) {
        PLANS, "plan" -> Tab.PLANS
        HISTORY, "session" -> Tab.HISTORY
        PROGRESS -> Tab.PROGRESS
        MORE, EXERCISES, "exercise", "machine", GOALS, SETTINGS, PAIRING -> Tab.MORE
        else -> Tab.TODAY
    }
}

/** Minimal back stack: tabs replace the stack, detail screens are pushed. */
class Navigator(private val stack: SnapshotStateList<String>) {
    val current: String get() = stack.last()
    val canPop: Boolean get() = stack.size > 1

    fun push(route: String) {
        if (stack.lastOrNull() != route) stack.add(route)
    }

    fun pop(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    fun resetTo(route: String) {
        stack.clear()
        stack.add(route)
    }
}

@Composable
fun rememberNavigator(start: String): Navigator {
    val stack = rememberSaveable(
        saver = listSaver<SnapshotStateList<String>, String>(save = { it.toList() }, restore = { it.toMutableStateList() }),
    ) { mutableStateListOf(start) }
    return Navigator(stack)
}

/** Shows a short message (snackbar) from anywhere. */
val LocalSnack = staticCompositionLocalOf<(String) -> Unit> { {} }
