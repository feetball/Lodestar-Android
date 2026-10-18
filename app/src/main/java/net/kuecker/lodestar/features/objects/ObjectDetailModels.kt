package net.kuecker.lodestar.features.objects

import net.kuecker.lodestar.features.smallbodies.SmallBodyDetail
import net.kuecker.lodestar.kit.catalog.AngularSize
import net.kuecker.lodestar.kit.catalog.CelestialObject
import net.kuecker.lodestar.kit.ephemeris.MoonPhaseInfo
import net.kuecker.lodestar.kit.ephemeris.NightTwilight
import net.kuecker.lodestar.kit.ephemeris.RiseTransitSet
import net.kuecker.lodestar.kit.foundation.DateInterval
import net.kuecker.lodestar.kit.foundation.EquatorialCoordinate
import net.kuecker.lodestar.kit.foundation.HorizontalCoordinate
import net.kuecker.lodestar.kit.phototools.ExposureEstimate
import net.kuecker.lodestar.kit.phototools.FrameSize
import net.kuecker.lodestar.model.ObserverContext
import java.text.NumberFormat
import java.time.Instant
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** How far away an object is, in the unit that suits it. */
internal sealed interface ObjectDetailDistance {
    data class Kilometers(val value: Double) : ObjectDetailDistance

    data class AstronomicalUnits(val value: Double) : ObjectDetailDistance

    data class LightYears(val value: Double) : ObjectDetailDistance

    /** The distance for display, such as "384,400 km", "1.52 AU" or "2.5 million light-years". */
    val text: String
        get() = when (this) {
            is Kilometers -> "${number(value, 0, 0)} km"
            is AstronomicalUnits -> "${number(value, 2, 2)} AU"
            is LightYears -> when {
                value >= 1_000_000 -> "${number(value / 1_000_000, 1, 1)} million light-years"
                value < 100 -> "${number(value, 0, 1)} light-years"
                else -> "${number(value, 0, 0)} light-years"
            }
        }

    private companion object {
        fun number(value: Double, minimumFractionDigits: Int, maximumFractionDigits: Int): String =
            NumberFormat.getNumberInstance().apply {
                this.minimumFractionDigits = minimumFractionDigits
                this.maximumFractionDigits = maximumFractionDigits
            }.format(value)
    }
}

/** What changes from minute to minute about an object: where it is, how bright, how large and how lit. */
internal data class ObjectDetailCurrent(
    val date: Instant,
    /** Apparent position in the observer's sky, refraction included, as the Aim screen shows it. */
    val horizontal: HorizontalCoordinate,
    /** Apparent place on the true equator and equinox of date. */
    val equatorial: EquatorialCoordinate,
    /** The visual magnitude now (it changes for the Sun, the Moon and the planets). */
    val magnitude: Double?,
    /** The size now (it changes for the Sun, the Moon and the planets). */
    val angularSize: AngularSize?,
    val distance: ObjectDetailDistance?,
    /** The sunlit fraction of the disc; `null` for the Sun and for objects without phases. */
    val illuminatedFraction: Double?,
    /** The Moon's phase; set only for the Moon. */
    val moonPhase: MoonPhaseInfo?,
    /** `true` when the object is up: above the horizon and, with a recorded skyline, above the skyline. */
    val isUp: Boolean = horizontal.altitude > 0,
    /** The apparent altitude of the recorded skyline at the object's azimuth; `null` without a skyline. */
    val skylineAltitude: Double? = null,
    /** `true` for a comet or an asteroid, whose distances and warnings are in [smallBody]. */
    val isSmallBody: Boolean = false,
    /** A comet's or asteroid's distances, elongation, phase, magnitude estimate, warnings and tail; `null` when its orbit cannot be evaluated. */
    val smallBody: SmallBodyDetail? = null,
) {
    /** `true` when the object is above the horizon but behind the recorded skyline. */
    val isBehindSkyline: Boolean get() = skylineAltitude != null && horizontal.altitude > 0 && !isUp
}

/** One point of the altitude curve. */
internal data class ObjectDetailAltitudeSample(
    val date: Instant,
    /** Apparent altitude in degrees. */
    val altitude: Double,
    /** The apparent altitude of the recorded skyline at the object's azimuth then; `null` without a skyline. */
    val skylineAltitude: Double? = null,
    /** `true` when the object is up: above the horizon and, with a skyline, above it. */
    val isUp: Boolean = altitude > 0,
)

/** A stretch of the night in which the sky is lit to a certain degree by the Sun. */
internal data class ObjectDetailTwilightBand(
    val interval: DateInterval,
    /** 1 = astronomical twilight, 2 = nautical twilight, 3 = civil twilight, 4 = daylight. */
    val brightness: Int,
)

/** The highest point of an object during the dark hours. */
internal data class ObjectDetailPeak(
    val date: Instant,
    /** Apparent altitude in degrees. */
    val altitude: Double,
    /** `true` when the object is up then: above the horizon and, with a recorded skyline, above it. */
    val isUp: Boolean = altitude > 0,
)

/** The object rising, culminating or setting. */
internal data class ObjectDetailEvent(val kind: Kind, val date: Instant) {
    enum class Kind { RISE, TRANSIT, SET }
}

/**
 * Rising, culmination and setting of an object for one night, with the data behind the altitude chart.
 *
 * A night runs from local noon to local noon (see `TwilightCalculator`).
 */
internal data class ObjectDetailNight(
    /** Local noon to local noon. */
    val interval: DateInterval,
    /** Sunset to sunrise: the stretch the chart shows. */
    val chartRange: DateInterval,
    val twilight: NightTwilight,
    /** The hours of astronomical darkness, if the Sun gets that low. */
    val darkness: DateInterval?,
    /** The object's rise, transit and set within [interval], with the horizon of `RiseSetCalculator`. */
    val events: RiseTransitSet,
    /** Apparent altitude at transit. */
    val transitAltitude: Double?,
    /** The apparent altitude every ten minutes across [chartRange]. */
    val samples: List<ObjectDetailAltitudeSample>,
    val twilightBands: List<ObjectDetailTwilightBand>,
    /** The instants of the time axis labels, on local hours. */
    val hourTicks: List<Instant>,
    /** Where the object is highest while it is dark; `null` when there is no darkness. */
    val bestInDarkness: ObjectDetailPeak?,
    /** `true` when the events, the curve and the best point respect the site's recorded skyline. */
    val usesSkyline: Boolean = false,
) {
    /** When this night is over and "tonight" means the next one. */
    val expiry: Instant get() = twilight.sunrise ?: interval.end

    /** The rising, culmination and setting that fall in the night, in the order they happen. */
    val timeline: List<ObjectDetailEvent>
        get() = listOfNotNull(
            events.rise?.let { ObjectDetailEvent(ObjectDetailEvent.Kind.RISE, it) },
            events.transit?.let { ObjectDetailEvent(ObjectDetailEvent.Kind.TRANSIT, it) },
            events.set?.let { ObjectDetailEvent(ObjectDetailEvent.Kind.SET, it) },
        ).sortedBy { it.date }
}

/** How much the Moon is likely to disturb a shot of the object. */
internal data class ObjectDetailMoonConditions(
    /** The instant the conditions were evaluated for. */
    val date: Instant,
    /** Whether [date] is the present moment (otherwise it is the object's best time tonight). */
    val isNow: Boolean,
    /** Angular distance between the object and the Moon, in degrees. */
    val separation: Double,
    val illuminatedFraction: Double,
    val impact: Impact,
) {
    enum class Impact { MOON_DOWN, MINOR, MODERATE, SEVERE }
}

/** Everything the detail screen computes for an object and an observer. */
internal data class ObjectDetailSnapshot(
    val current: ObjectDetailCurrent,
    val night: ObjectDetailNight,
    /** `null` for the Sun and the Moon. */
    val moon: ObjectDetailMoonConditions?,
)

/** The inputs of [ObjectDetailCalculator.snapshot]. */
internal data class ObjectDetailInput(
    val obj: CelestialObject,
    val context: ObserverContext,
    val date: Instant,
    /** The night computed for an earlier snapshot, reused while it is still the current night. */
    val previousNight: ObjectDetailNight?,
)

/** How much of the camera frame an extended object covers. */
internal data class ObjectDetailFraming(
    /** The object's major axis divided by the frame's long side. */
    val majorFraction: Double,
    /** The object's minor axis divided by the frame's short side. */
    val minorFraction: Double,
) {
    /** The larger of the two fractions: how full the frame is along the tightest dimension. */
    val fillFraction: Double get() = max(majorFraction, minorFraction)

    val fitsInFrame: Boolean get() = fillFraction <= 1

    /** Panels needed along the frame's long side to cover the object with overlapping panels. */
    val longSidePanels: Int get() = panels(majorFraction)

    /** Panels needed along the frame's short side to cover the object with overlapping panels. */
    val shortSidePanels: Int get() = panels(minorFraction)

    private companion object {
        /** How much neighbouring panels of a mosaic overlap, as a fraction of a panel. */
        const val MOSAIC_OVERLAP = 0.2

        fun panels(fraction: Double): Int {
            if (!(fraction > 1)) return 1
            val extra = ceil((fraction - 1) / (1 - MOSAIC_OVERLAP))
            return min(1 + extra, 99.0).toInt()
        }
    }
}

/** How an object fits the user's lens and how long an exposure can be before stars trail. */
internal data class ObjectDetailPhotography(
    /** The frame of the user's camera and lens, in landscape orientation. */
    val frame: FrameSize,
    /** `null` for objects without a known angular size. */
    val framing: ObjectDetailFraming?,
    /** `null` for the Sun and the Moon, for which star trails do not matter. */
    val npf: ExposureEstimate?,
    val rule500: ExposureEstimate?,
)
