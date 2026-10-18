package net.kuecker.lodestar.features.aim

import net.kuecker.lodestar.kit.catalog.Catalog
import net.kuecker.lodestar.kit.catalog.CelestialObject
import net.kuecker.lodestar.kit.catalog.horizontal
import net.kuecker.lodestar.kit.ephemeris.SolarSystemBody
import net.kuecker.lodestar.kit.ephemeris.state
import net.kuecker.lodestar.kit.formatting.AngleFormatter
import net.kuecker.lodestar.kit.foundation.HorizontalCoordinate
import net.kuecker.lodestar.kit.foundation.Observer
import net.kuecker.lodestar.kit.foundation.SkyFrame
import net.kuecker.lodestar.kit.pointing.AlignmentError
import net.kuecker.lodestar.kit.pointing.AlignmentModel
import net.kuecker.lodestar.shared.SkySnapshotBuilder
import java.time.Instant

/** A bright object that can serve as a reference for star alignment. */
internal data class AimAlignmentCandidate(
    val obj: CelestialObject,
    /** Apparent (refracted) position when the list was made. */
    val horizontal: HorizontalCoordinate,
    val magnitude: Double?,
) {
    val id: String get() = obj.id
}

/** A candidate with its distance from where the camera points, for sorting. */
internal data class AimAlignmentRow(
    val candidate: AimAlignmentCandidate,
    /** Degrees from the camera's boresight; `null` while the sensors deliver nothing. */
    val separation: Double?,
) {
    val id: String get() = candidate.id
}

internal object AimAlignmentCandidates {
    /** Lower limit of the altitude: objects this high are easy to find and clear of the haze, degrees. */
    const val MINIMUM_ALTITUDE = 15.0

    /** Upper limit: the alignment solver rejects objects close to the zenith, where azimuth means little. */
    const val MAXIMUM_ALTITUDE = AlignmentModel.maximumAltitude - 2

    /** Stars at least this bright are offered. */
    const val STAR_MAGNITUDE_LIMIT = 2.0

    /** Planets at least this bright are offered. */
    const val PLANET_MAGNITUDE_LIMIT = 3.0

    /**
     * The Moon, the bright planets and the bright stars that are between the altitude limits at [date]. The Sun is
     * never offered: it must not be pointed at without a solar filter. Searches the catalog, so run it off the main
     * thread.
     */
    fun make(catalog: Catalog, observer: Observer, date: Instant): List<AimAlignmentCandidate> {
        val frame = SkyFrame(date, observer)
        val candidates = mutableListOf<AimAlignmentCandidate>()
        fun add(obj: CelestialObject, magnitude: Double?) {
            val horizontal = frame.horizontal(obj, refraction = true)
            if (horizontal.altitude in MINIMUM_ALTITUDE..MAXIMUM_ALTITUDE) {
                candidates.add(AimAlignmentCandidate(obj, horizontal, magnitude))
            }
        }
        for (body in SolarSystemBody.entries) {
            if (body == SolarSystemBody.SUN) continue
            val obj = catalog.objectWithID(SkySnapshotBuilder.objectID(body)) ?: continue
            val magnitude = frame.state(body).apparentMagnitude
            if (body != SolarSystemBody.MOON && (magnitude ?: Double.POSITIVE_INFINITY) > PLANET_MAGNITUDE_LIMIT) continue
            add(obj, magnitude)
        }
        for (star in catalog.renderStars) {
            if (star.magnitude > STAR_MAGNITUDE_LIMIT) break
            catalog.objectWithID(star.id)?.let { add(it, star.magnitude) }
        }
        return candidates
    }
}

/** The outcome of the last alignment step, shown in the sheet. */
internal sealed interface AimAlignmentNotice {
    val text: String

    data class Success(override val text: String) : AimAlignmentNotice

    data class Failure(override val text: String) : AimAlignmentNotice

    val isSuccess: Boolean get() = this is Success
}

/** An explanation a photographer can act on; [name] is the object the alignment was attempted on. */
internal fun AlignmentError.aimMessage(name: String): String {
    val limit = AlignmentModel.maximumAltitude.toInt()
    return when (this) {
        AlignmentError.NonFiniteInput ->
            "The sensors gave an unusable reading. Hold the phone still for a moment and sync again."
        is AlignmentError.BoresightTooHigh ->
            "The camera points almost straight up (${AngleFormatter.formatDegrees(altitude, 0)}). " +
                "Alignment needs an object below $limit°: choose a lower one."
        is AlignmentError.TargetTooHigh ->
            "$name is too high in the sky (${AngleFormatter.formatDegrees(altitude, 0)}) for a reliable alignment. " +
                "Choose an object below $limit°."
        is AlignmentError.ImplausibleCorrection ->
            "This would need a correction of ${AngleFormatter.formatDegrees(degrees, 0)}, more than a bracket can " +
                "cause. Check that $name is really in the middle of the frame and that the right mount is chosen in Settings."
    }
}
