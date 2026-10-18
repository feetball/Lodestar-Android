package net.kuecker.lodestar.features.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withSave
import kotlin.math.abs

/**
 * The geometry of the Moon's phase, as the Tonight card draws it: the lit part is bounded by the limb of the lit side
 * and the terminator, an ellipse as wide as the phase says. Phases are drawn as seen from the northern hemisphere (the
 * lit side of a waxing Moon on the right) and mirrored for the southern one.
 */
internal data class WidgetMoonShape(
    /** The lit fraction of the disc, 0…1. */
    val fraction: Double,
    /** Whether the lit side is on the left. */
    val isMirrored: Boolean,
) {
    /** The terminator's half width as a part of the radius: 1 at new and full Moon, 0 at the quarters. */
    val terminatorHalfWidth: Double get() = abs(1 - 2 * fraction)

    /** Whether the terminator bulges towards the lit side (a crescent) rather than away from it (a gibbous Moon). */
    val isCrescent: Boolean get() = fraction < 0.5

    companion object {
        operator fun invoke(illuminatedFraction: Double, isWaxing: Boolean, isSouthernHemisphere: Boolean): WidgetMoonShape {
            val fraction = if (illuminatedFraction.isFinite()) illuminatedFraction.coerceIn(0.0, 1.0) else 0.0
            // The lit side is on the right for a waxing Moon in the north and for a waning one in the south.
            return WidgetMoonShape(fraction, isMirrored = isWaxing == isSouthernHemisphere)
        }
    }
}

/** Draws the Moon's phase into a bitmap for the widgets (they cannot draw on a canvas themselves). */
internal object WidgetMoonImage {
    /** The side of the square bitmap in pixels: crisp at 56 dp on a 3× screen, small enough for a widget's budget. */
    const val SIZE_PIXELS = 168

    /** The outline's width as a part of the bitmap. */
    private const val OUTLINE = 0.045f

    /** A bitmap of the phase: the lit part filled with [litColor] and the whole disc outlined with [outlineColor]. */
    fun render(shape: WidgetMoonShape, litColor: Int, outlineColor: Int): Bitmap {
        val size = SIZE_PIXELS
        val bitmap = createBitmap(size, size)
        val canvas = Canvas(bitmap)
        val stroke = size * OUTLINE
        val radius = size / 2f - stroke / 2f
        val center = size / 2f
        val limb = RectF(center - radius, center - radius, center + radius, center + radius)
        val halfWidth = radius * shape.terminatorHalfWidth.toFloat()
        val terminator = RectF(center - halfWidth, limb.top, center + halfWidth, limb.bottom)
        val lit = Path().apply {
            arcTo(limb, -90f, 180f, true)
            // Back from the bottom to the top: bulging towards the lit side for a crescent, away from it for a gibbous Moon.
            arcTo(terminator, 90f, if (shape.isCrescent) -180f else 180f, false)
            close()
        }
        canvas.withSave {
            if (shape.isMirrored) scale(-1f, 1f, center, center)
            drawPath(lit, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = litColor; style = Paint.Style.FILL })
            drawCircle(
                center, center, radius,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = outlineColor; style = Paint.Style.STROKE; strokeWidth = stroke },
            )
        }
        return bitmap
    }
}
