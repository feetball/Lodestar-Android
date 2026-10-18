package net.kuecker.lodestar.features.aim

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import net.kuecker.lodestar.features.mount.MountHudCard
import net.kuecker.lodestar.theme.LocalPalette
import kotlin.math.min

/** The HUD's text grows with the system font size up to this factor, as Dynamic Type stops at xxxLarge on iOS. */
private const val MAXIMUM_FONT_SCALE = 1.3f

/** The readout does not grow wider than this in a wide layout. */
private val WIDE_READOUT_WIDTH = 420.dp

/** Distance of the level gauge from the top of a tall layout. */
private val LEVEL_GAUGE_TOP_INSET = 84.dp

/**
 * The heads-up display over the sky: status chips, the target and how to reach it, the level gauge and the controls.
 *
 * The whole display is laid out for the orientation the phone is clamped in and turned with it, in quarter turns, so
 * its text reads upright for the photographer; a phone clamped sideways gets a wide layout. The sky itself and the big
 * arrow stay in the phone's own screen axes. It keeps clear of display cutouts.
 */
@Composable
internal fun GuidanceHUD(
    model: AimViewModel,
    onSheet: (AimSheet) -> Unit,
    isActive: Boolean,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, min(density.fontScale, MAXIMUM_FONT_SCALE))) {
        BoxWithConstraints(
            modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
        ) {
            // A sideways HUD is laid out in the swapped size and turned back into the screen. A change of rotation
            // fades the old layout out and the new one in.
            AnimatedContent(
                targetState = model.hudRotation,
                modifier = Modifier.fillMaxSize(),
                transitionSpec = { fadeIn(tween(ROTATION_FADE_MS)) togetherWith fadeOut(tween(ROTATION_FADE_MS)) },
                label = "GuidanceHUD rotation",
            ) { rotation ->
                val layoutSize = if (rotation.swapsAxes) DpSize(maxHeight, maxWidth) else DpSize(maxWidth, maxHeight)
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Box(Modifier.requiredSize(layoutSize).rotate(rotation.degrees.toFloat())) {
                        AimHUDLayout(model, onSheet, isActive, isWide = layoutSize.width > layoutSize.height)
                    }
                }
            }
        }
    }
}

private const val ROTATION_FADE_MS = 200

/** The HUD's content for a given shape: controls under the readout in a tall layout, beside it in a wide one. */
@Composable
private fun AimHUDLayout(model: AimViewModel, onSheet: (AimSheet) -> Unit, isActive: Boolean, isWide: Boolean) {
    val palette = LocalPalette.current
    CompositionLocalProvider(LocalContentColor provides palette.primaryText) {
        Box(Modifier.fillMaxSize().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp)) {
            if (isWide) WideLayout(model, onSheet, isActive) else TallLayout(model, onSheet, isActive)
        }
    }
}

@Composable
private fun TallLayout(model: AimViewModel, onSheet: (AimSheet) -> Unit, isActive: Boolean) {
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AimStatusChips(model)
            Spacer(Modifier.weight(1f))
            AimReadout(model, onSheet, isActive)
            MountHudCard(model.guidanceTarget)
            AimControlsBar(model, onSheet)
        }
        // Below the chips and clear of the arrow around the reticle.
        AimLevelGauge(model, isActive, Modifier.align(Alignment.TopEnd).padding(top = LEVEL_GAUGE_TOP_INSET))
    }
}

@Composable
private fun WideLayout(model: AimViewModel, onSheet: (AimSheet) -> Unit, isActive: Boolean) {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column(
            Modifier.weight(1f, fill = false).widthIn(max = WIDE_READOUT_WIDTH).fillMaxHeight().padding(end = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AimStatusChips(model)
            Spacer(Modifier.weight(1f))
            AimReadout(model, onSheet, isActive)
            MountHudCard(model.guidanceTarget)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AimLevelGauge(model, isActive)
            AimControlsBar(model, onSheet, axis = Orientation.Vertical)
        }
    }
}
