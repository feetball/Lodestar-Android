package net.kuecker.lodestar.features.aim

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
import net.kuecker.lodestar.kit.foundation.HorizontalCoordinate
import net.kuecker.lodestar.kit.pointing.AlignmentHygiene
import net.kuecker.lodestar.kit.pointing.AlignmentPoint
import net.kuecker.lodestar.kit.pointing.AlignmentPointSeparation
import net.kuecker.lodestar.shared.AppFormat
import java.time.Instant
import kotlin.math.floor

/** How the age of an alignment reads: "Aligned 12 min ago" on the card, "Alignment 3 h old" on the chip. */
internal object AimAlignmentAge {
    /** Shown on the card when the alignment is stale. */
    const val STALE_ADVICE = "This alignment is getting old. Align on a star again for the best accuracy."

    /** Whole minutes and hours of [seconds]: "12 min", "3 h" or "1 h 30 min"; "just now" below a minute. */
    fun text(seconds: Double): String {
        val minutes = if (seconds.isFinite() && seconds > 0) floor(seconds / 60).toInt() else 0
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "$minutes min"
            minutes % 60 == 0 -> "${minutes / 60} h"
            else -> "${minutes / 60} h ${minutes % 60} min"
        }
    }

    /** The line on the alignment card, `seconds` after the alignment was made. */
    fun cardText(seconds: Double): String {
        val age = text(seconds)
        return if (age == "just now") "Aligned just now" else "Aligned $age ago"
    }

    /** The text of the stale chip. */
    fun chipText(seconds: Double): String = "Alignment ${text(seconds)} old"
}

/** How far a reference object is from the alignment points already taken, as the alignment sheet words it. */
internal object AimAlignmentSpacing {
    /** Whole degrees, rounded down so that "19°" never sits beside a limit of 20°; arc minutes below a degree. */
    fun degreesText(degrees: Double): String =
        if (degrees >= 1) "${floor(degrees).toInt()}°" else AppFormat.separation(degrees)

    /** The nearest point's name, or "point 2" when it has none. */
    fun pointLabel(nearest: AlignmentPointSeparation): String = nearest.pointName ?: "point ${nearest.pointIndex + 1}"

    /** "47° from Vega", for the candidate list. */
    fun nearestText(nearest: AlignmentPointSeparation): String =
        "${degreesText(nearest.degrees)} from ${pointLabel(nearest)}"

    /** Whether [nearest] is closer than the spacing that gives a well-conditioned fit. */
    fun isTooClose(nearest: AlignmentPointSeparation): Boolean =
        nearest.degrees < AlignmentHygiene.minimumPointSeparationDegrees

    /**
     * The warning for syncing [candidate] next to the [existing] points, or null when it is far enough from all of
     * them (or there are none): "Only 8° from Vega: pick a star at least 20° away for a better fit".
     */
    fun warningText(candidate: HorizontalCoordinate, existing: List<AlignmentPoint>): String? {
        if (AlignmentHygiene.separationWarning(candidate, existing) == null) return null
        val nearest = AlignmentHygiene.bestSeparation(candidate, existing) ?: return null
        val minimum = AlignmentHygiene.minimumPointSeparationDegrees.toInt()
        return "Only ${degreesText(nearest.degrees)} from ${pointLabel(nearest)}: pick a star at least $minimum° away for a better fit"
    }
}

/** The wall clock, refreshed every [intervalMillis]: for texts that count the time since something. */
@Composable
internal fun rememberWallClock(intervalMillis: Long = 30_000L): State<Instant> =
    produceState(Instant.now()) {
        while (true) {
            delay(intervalMillis)
            value = Instant.now()
        }
    }
