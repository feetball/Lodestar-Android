package net.kuecker.lodestar.features.tools

import net.kuecker.lodestar.kit.foundation.AstroMath
import net.kuecker.lodestar.kit.foundation.roundedHalfAwayFromZero
import net.kuecker.lodestar.shared.AppFormat

/** Clock positions on a polar-scope reticle. */
internal object ToolsPolarClock {
    /** The clock time for a direction measured clockwise from 12 o'clock: 30° per hour, so 315° is "10:30". */
    fun text(angle: Double): String {
        if (!angle.isFinite()) return AppFormat.MISSING
        val totalMinutes = (AstroMath.normalizeDegrees(angle) * 2).roundedHalfAwayFromZero().toInt() % 720
        val hour = if (totalMinutes / 60 == 0) 12 else totalMinutes / 60
        val minutes = totalMinutes % 60
        return "$hour:${if (minutes < 10) "0" else ""}$minutes"
    }
}
