package net.kuecker.lodestar.shared

import android.view.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import net.kuecker.lodestar.kit.foundation.Vector3

/**
 * The screen's right and up directions in device coordinates (x = right edge, y = top edge, z = out of the screen
 * in the natural orientation). Sensor attitudes are in device coordinates; projections onto the screen must use
 * these axes. Phones run in portrait (identity), but Android ignores the orientation lock on large screens.
 */
data class ScreenAxes(val right: Vector3, val up: Vector3) {
    companion object {
        val natural = ScreenAxes(Vector3(1.0, 0.0, 0.0), Vector3(0.0, 1.0, 0.0))

        /** For a [Surface] rotation: the drawing is rotated opposite to the device (ROTATION_90 = device turned left). */
        fun forRotation(rotation: Int): ScreenAxes = when (rotation) {
            Surface.ROTATION_90 -> ScreenAxes(Vector3(0.0, -1.0, 0.0), Vector3(1.0, 0.0, 0.0))
            Surface.ROTATION_180 -> ScreenAxes(Vector3(-1.0, 0.0, 0.0), Vector3(0.0, -1.0, 0.0))
            Surface.ROTATION_270 -> ScreenAxes(Vector3(0.0, 1.0, 0.0), Vector3(-1.0, 0.0, 0.0))
            else -> natural
        }
    }
}

/** The screen axes for the current display rotation (updates on configuration changes). */
@Composable
fun rememberScreenAxes(): ScreenAxes {
    val view = LocalView.current
    val configuration = LocalConfiguration.current
    return remember(configuration) { ScreenAxes.forRotation(view.display?.rotation ?: Surface.ROTATION_0) }
}
