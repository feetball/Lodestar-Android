package net.kuecker.lodestar.features.aim

import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.rememberTextMeasurer
import net.kuecker.lodestar.shared.rememberScreenAxes
import net.kuecker.lodestar.theme.LocalPalette

/** Nanoseconds between redraws: about 30 a second whatever the refresh rate of the display (frames are skipped). */
private const val FRAME_INTERVAL_NANOS = 30_000_000L

/**
 * The sky laid over the camera picture: a `Canvas` redrawn about 30 times a second from the newest motion sample.
 * It fills its parent (strokes are clipped at its edges), lets touches through and is invisible to TalkBack.
 *
 * The frame is pulled from the engine inside the draw, so the sensor rate never reaches Compose state; the frame loop
 * pauses while the Aim tab is not showing ([isActive] is `false`). It also publishes the display rotation in
 * [AimViewModel.screenAxes].
 */
@Composable
fun SkyOverlayView(model: AimViewModel, isActive: Boolean, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val axes = rememberScreenAxes()
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val scratch = remember(textMeasurer, density) { SkyOverlayScratch(textMeasurer, density) }
    val context = LocalContext.current
    var tick by remember { mutableLongStateOf(0L) }
    var reduceMotion by remember { mutableStateOf(false) }

    SideEffect { model.screenAxes = axes }
    LaunchedEffect(isActive) {
        if (!isActive) return@LaunchedEffect
        reduceMotion = isMotionReduced(context)
        var lastDrawn = 0L
        while (true) {
            withFrameNanos { now ->
                if (now - lastDrawn >= FRAME_INTERVAL_NANOS) {
                    lastDrawn = now
                    tick = now
                }
            }
        }
    }
    // Clipping also gives the canvas a layer of its own, so redrawing it every frame does not re-record the screen.
    Canvas(modifier.fillMaxSize().clipToBounds().clearAndSetSemantics { }) {
        // Reading the tick is what schedules the next redraw.
        val frameNanos = tick
        val frame = model.engine.frame() ?: return@Canvas
        // The target ring holds still for people who asked for less motion.
        val time = if (reduceMotion) 0.0 else frameNanos / 1e9
        val session = model.mosaic
        SkyOverlayRenderer.create(
            scope = this,
            scratch = scratch,
            frame = frame,
            content = model.skyContent,
            configuration = model.configuration,
            palette = palette,
            mosaic = model.mosaicPointings?.takeIf { it.plan === session.plan },
            mosaicStep = session.step,
            camera = model.cameraGeometry,
            virtualFieldOfView = model.virtualFieldOfView,
            hudRotation = model.hudRotation,
            screenAxes = axes,
            time = time,
        )?.draw()
    }
}

/** Whether the user turned animations off in the system's accessibility settings ("Remove animations"). */
private fun isMotionReduced(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
