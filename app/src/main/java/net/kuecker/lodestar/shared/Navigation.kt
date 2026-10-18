package net.kuecker.lodestar.shared

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import net.kuecker.lodestar.app.AppModel

/** The app model, provided at the root. Read it with `LocalAppModel.current`. */
val LocalAppModel = staticCompositionLocalOf<AppModel> { error("LocalAppModel is provided by RootScreen") }

/**
 * UI state that must survive tab switches and configuration changes (navigation stacks, list options): a plain
 * map owned by the process-wide [AppModel]. Keys are namespaced by feature, e.g. "objects.nav".
 */
class RetainedState {
    private val values = HashMap<String, Any>()

    @Suppress("UNCHECKED_CAST") // each key is only ever used with one type
    fun <T : Any> getOrPut(key: String, create: () -> T): T = values.getOrPut(key, create) as T
}

/** The retained value for [key], created once with [create]. */
@Composable
fun <T : Any> rememberRetained(key: String, create: () -> T): T {
    val app = LocalAppModel.current
    return remember(key) { app.retained.getOrPut(key, create) }
}

/** A navigation back stack for one tab (the root route is never popped). */
class NavStack<T : Any>(root: T) {
    private val entries = mutableStateListOf(root)

    val top: T get() = entries.last()
    val depth: Int get() = entries.size
    val canPop: Boolean get() = entries.size > 1

    fun push(route: T) {
        entries.add(route)
    }

    fun pop(): Boolean {
        if (entries.size <= 1) return false
        entries.removeAt(entries.lastIndex)
        return true
    }

    fun popToRoot() {
        while (entries.size > 1) entries.removeAt(entries.lastIndex)
    }
}

/** Shows the top of [stack], slides between levels and pops on system back. */
@Composable
fun <T : Any> NavStackHost(stack: NavStack<T>, modifier: Modifier = Modifier, content: @Composable (T) -> Unit) {
    BackHandler(enabled = stack.canPop) { stack.pop() }
    AnimatedContent(
        targetState = stack.top to stack.depth,
        modifier = modifier,
        transitionSpec = {
            val forward = targetState.second >= initialState.second
            (slideInHorizontally { width -> if (forward) width / 3 else -width / 3 } + fadeIn()) togetherWith
                (slideOutHorizontally { width -> if (forward) -width / 3 else width / 3 } + fadeOut())
        },
        label = "NavStackHost",
    ) { (route, _) -> content(route) }
}
