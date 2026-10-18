package net.kuecker.lodestar.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Whether the app currently hides the status and navigation bars (Aim tab, night mode). Provided by RootScreen;
 * sheets and dialogs are separate windows and use it to hide their own bars, which would otherwise show in white.
 */
val LocalSystemBarsHidden = compositionLocalOf { false }

/** Hides the system bars of the dialog/sheet window this composable is shown in (a swipe shows them briefly). */
@Composable
fun HideSystemBarsInWindow() {
    val view = LocalView.current
    val window = (view.parent as? DialogWindowProvider)?.window ?: return
    LaunchedEffect(window) {
        // The window has an insets controller only once it is on screen.
        while (!view.isAttachedToWindow) withFrameMillis { }
        val controller = WindowCompat.getInsetsController(window, view)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }
}
