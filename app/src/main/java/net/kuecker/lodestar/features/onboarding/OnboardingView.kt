package net.kuecker.lodestar.features.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.kuecker.lodestar.shared.LocalAppModel
import net.kuecker.lodestar.shared.LodestarButton
import net.kuecker.lodestar.shared.LodestarButtonKind
import net.kuecker.lodestar.theme.LocalPalette
import net.kuecker.lodestar.theme.screenBackground

/** The steps of the first-run flow, in order. */
internal enum class OnboardingStep { WELCOME, MOUNTING, PERMISSIONS, NIGHT_VISION }

/**
 * First-run flow: what Lodestar does, how the phone is mounted, where the observer is and how to protect night
 * vision. Shown instead of the tab interface until it ends with `AppModel.completeOnboarding()`. The pages swipe;
 * Back (and the system back gesture) returns to the previous page.
 */
@Composable
fun OnboardingScreen() {
    val app = LocalAppModel.current
    val palette = LocalPalette.current
    val steps = OnboardingStep.entries
    val pager = rememberPagerState { steps.size }
    val scope = rememberCoroutineScope()
    val step = steps[pager.currentPage]

    fun move(offset: Int) {
        val next = pager.currentPage + offset
        if (next in steps.indices) scope.launch { pager.animateScrollToPage(next) }
    }

    fun advance() {
        if (step == OnboardingStep.NIGHT_VISION) app.completeOnboarding() else move(1)
    }

    BackHandler(enabled = pager.currentPage > 0) { move(-1) }
    Column(Modifier.fillMaxSize().screenBackground(palette).safeDrawingPadding()) {
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth()) { page ->
            when (steps[page]) {
                OnboardingStep.WELCOME -> OnboardingWelcomePage()
                OnboardingStep.MOUNTING -> OnboardingMountPage()
                OnboardingStep.PERMISSIONS -> OnboardingPermissionsPage()
                OnboardingStep.NIGHT_VISION -> OnboardingNightPage()
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .background(palette.background)
                .padding(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OnboardingPageDots(current = step.ordinal, count = steps.size, modifier = Modifier.align(Alignment.CenterHorizontally))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (step != OnboardingStep.WELCOME) {
                    LodestarButton("Back", onClick = { move(-1) }, kind = LodestarButtonKind.SECONDARY)
                }
                LodestarButton(
                    if (step == OnboardingStep.NIGHT_VISION) "Start Aiming" else "Continue",
                    onClick = ::advance,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** Dots showing the current step, drawn in the palette. */
@Composable
private fun OnboardingPageDots(current: Int, count: Int, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    Row(
        modifier.clearAndSetSemantics { contentDescription = "Step ${current + 1} of $count" },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        repeat(count) { index ->
            Box(Modifier.size(8.dp).background(if (index == current) palette.accent else palette.stroke, CircleShape))
        }
    }
}
