package net.kuecker.lodestar.app

import android.app.Activity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import net.kuecker.lodestar.features.aim.AimScreen
import net.kuecker.lodestar.features.objects.ObjectsScreen
import net.kuecker.lodestar.features.onboarding.OnboardingScreen
import net.kuecker.lodestar.features.settings.SettingsScreen
import net.kuecker.lodestar.features.skychart.SkyChartScreen
import net.kuecker.lodestar.features.tonight.TonightScreen
import net.kuecker.lodestar.shared.LocalAppModel
import net.kuecker.lodestar.shared.LocalSystemBarsHidden
import net.kuecker.lodestar.theme.LodestarTheme
import net.kuecker.lodestar.theme.screenBackground

/**
 * Top-level UI: onboarding on first launch, then the tab interface. Applies the active palette (normal or red
 * night mode) to everything; system bars are hidden on the Aim tab and, at night, everywhere (they are white).
 */
@Composable
fun RootScreen(app: AppModel) {
    val palette = app.palette
    CompositionLocalProvider(LocalAppModel provides app) {
        val hideBars = app.selectedTab == AppTab.AIM || palette.isNight
        LodestarTheme(palette, redFilter = app.settings.redFilterOverlay) {
            SystemBarsEffect(hidden = hideBars)
            CompositionLocalProvider(LocalSystemBarsHidden provides hideBars) {
                if (app.settings.hasCompletedOnboarding) {
                    Tabs(app)
                } else {
                    OnboardingScreen()
                }
            }
        }
    }
    LaunchedEffect(app.settings) { app.syncServices() }
}

@Composable
private fun Tabs(app: AppModel) {
    val palette = app.palette
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = palette.background,
        bottomBar = {
            NavigationBar(containerColor = palette.surface) {
                for (tab in AppTab.entries) {
                    NavigationBarItem(
                        selected = app.selectedTab == tab,
                        onClick = { app.selectedTab = tab },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(tab.title) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = palette.accent,
                            selectedTextColor = palette.accent,
                            unselectedIconColor = palette.tertiaryText,
                            unselectedTextColor = palette.tertiaryText,
                            indicatorColor = palette.surfaceRaised,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        // Aim is full-bleed behind the (hidden) status bar; every other tab respects the insets.
        // Consuming the insets keeps tabs that pad for the keyboard (imePadding) from counting the tab bar twice.
        val modifier = if (app.selectedTab == AppTab.AIM) {
            val bottom = PaddingValues(bottom = padding.calculateBottomPadding())
            Modifier.padding(bottom).consumeWindowInsets(bottom)
        } else {
            Modifier.padding(padding).consumeWindowInsets(padding)
        }
        Box(modifier.fillMaxSize().screenBackground(palette)) {
            when (app.selectedTab) {
                AppTab.AIM -> AimScreen()
                AppTab.OBJECTS -> ObjectsScreen()
                AppTab.SKY -> SkyChartScreen()
                AppTab.TONIGHT -> TonightScreen()
                AppTab.SETTINGS -> SettingsScreen()
            }
        }
    }
}

/** Hides or shows the status and navigation bars (a swipe from the edge shows them briefly while hidden). */
@Composable
private fun SystemBarsEffect(hidden: Boolean) {
    val view = LocalView.current
    LaunchedEffect(hidden) {
        val window = (view.context as? Activity)?.window ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, view)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (hidden) controller.hide(WindowInsetsCompat.Type.systemBars()) else controller.show(WindowInsetsCompat.Type.systemBars())
    }
}
