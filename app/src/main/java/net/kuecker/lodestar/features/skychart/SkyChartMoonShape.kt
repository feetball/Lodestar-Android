package net.kuecker.lodestar.features.skychart

import androidx.compose.ui.graphics.Path
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** The shape of the Moon's lit part, for the Moon marker on the chart. */
internal object SkyChartMoonShape {
    private const val STEPS = 24

    /**
     * Adds the outline of the lit part of a disc to [path] as one closed subpath, in screen pixels.
     *
     * The limb on the Sun's side is a half circle; the terminator is a half ellipse that bulges towards the Sun for a
     * crescent and away from it for a gibbous Moon.
     *
     * @param centerX The disc's centre.
     * @param radius The disc's radius.
     * @param fraction The lit fraction of the disc, 0 (new) to 1 (full).
     * @param towardsSun The direction of the Sun from the Moon on the screen, in radians (x right, y down).
     */
    fun addLitOutline(path: Path, centerX: Float, centerY: Float, radius: Float, fraction: Double, towardsSun: Double) {
        val cosine = cos(towardsSun).toFloat()
        val sine = sin(towardsSun).toFloat()
        val reach = radius * (1 - 2 * fraction.coerceIn(0.0, 1.0)).toFloat()
        var isFirst = true

        fun place(x: Float, y: Float) {
            val px = centerX + x * cosine - y * sine
            val py = centerY + x * sine + y * cosine
            if (isFirst) path.moveTo(px, py) else path.lineTo(px, py)
            isFirst = false
        }

        for (step in 0..STEPS) {
            val t = -PI / 2 + PI * step / STEPS
            place(radius * cos(t).toFloat(), radius * sin(t).toFloat())
        }
        for (step in 0..STEPS) {
            val t = PI * step / STEPS
            place(reach * sin(t).toFloat(), radius * cos(t).toFloat())
        }
        path.close()
    }
}
