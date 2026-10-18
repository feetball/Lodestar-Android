package net.kuecker.lodestar.features.aim

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.East
import androidx.compose.material.icons.filled.West
import androidx.compose.ui.graphics.vector.ImageVector
import net.kuecker.lodestar.kit.pointing.Guidance
import net.kuecker.lodestar.kit.pointing.GuidanceState
import kotlin.math.abs

/** What the guidance asks of the photographer, in words and numbers for the readouts and TalkBack. */
internal class AimGuidanceSummary(guidance: Guidance) {
    /** One movement of the tripod head. */
    class Move(
        /** "Pan right", "Tilt down". */
        val verb: String,
        val icon: ImageVector,
        /** The size of the movement in degrees (always positive). */
        val degrees: Double,
    )

    val pan = Move(
        verb = if (guidance.panDelta >= 0) "Pan right" else "Pan left",
        icon = if (guidance.panDelta >= 0) Icons.Filled.East else Icons.Filled.West,
        degrees = abs(guidance.panDelta),
    )
    val tilt = Move(
        verb = if (guidance.tiltDelta >= 0) "Tilt up" else "Tilt down",
        icon = if (guidance.tiltDelta >= 0) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
        degrees = abs(guidance.tiltDelta),
    )

    /** Angle between where the camera points and the target, degrees. */
    val separation = guidance.separation
    val isOnTarget = guidance.state == GuidanceState.ON_TARGET

    /** Azimuth is a poor guide within a few degrees of the zenith: only the arrow and the separation are reliable. */
    val isNearZenith = guidance.isNearZenith
    val isBelowHorizon = guidance.targetBelowHorizon

    /** The summary for TalkBack, e.g. "Pan right 23 degrees, tilt up 12 degrees, 26 degrees away". */
    val spokenText: String
        get() {
            if (isOnTarget) return "On target"
            val away = "${AimFormat.spoken(separation)} away"
            if (isNearZenith) return "Near the zenith, follow the arrow, $away"
            return "${pan.verb} ${AimFormat.spoken(pan.degrees)}, ${tilt.verb.lowercase()} ${AimFormat.spoken(tilt.degrees)}, $away"
        }
}
