package net.kuecker.lodestar.kit.events

import net.kuecker.lodestar.kit.ephemeris.*
import net.kuecker.lodestar.kit.foundation.*
import net.kuecker.lodestar.kit.phototools.FrameSize
import net.kuecker.lodestar.kit.phototools.LensSetup
import net.kuecker.lodestar.kit.planning.NightSummary
import java.time.Instant
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.pow

/**
 * Where to point a camera at a meteor shower (Swift `MeteorAimAdvice`).
 *
 * @property azimuth Azimuth of the view in degrees from north through east, [0, 360).
 * @property altitude Geometric altitude of the view in degrees, above [MeteorCameraAdvice.minimumAltitude].
 * @property separationFromRadiant Great-circle distance from the radiant in degrees (the ring radius, 40°).
 * @property aimPoint The place of the view as a J2000 position at the instant the advice is for, so the app can make
 * a custom target of it; it stays fixed among the stars while the horizontal place moves with the sky.
 * @property moonNote A sentence about the Moon in that direction; `null` when the Moon was not considered.
 * @property reason One sentence on why this direction.
 */
data class MeteorAimAdvice(
    val azimuth: Double,
    val altitude: Double,
    val separationFromRadiant: Double,
    val aimPoint: EquatorialCoordinate,
    val moonNote: String?,
    val reason: String,
)

/** The Moon as the direction chooser sees it: where it stands in the horizon frame and how much of it is lit. */
internal data class MeteorCameraMoon(val direction: HorizontalCoordinate, val illuminatedFraction: Double)

/**
 * The direction the chooser picked on the ring around the radiant.
 *
 * @property direction The picked view, in the horizon frame.
 * @property positionAngle Where it lies on the ring, in degrees from the vertical circle through the radiant towards
 * the zenith, turning right-handed about the radiant.
 * @property separationFromRadiant Distance from the radiant in degrees.
 * @property moonSeparation Distance from the Moon in degrees; `null` when there is no Moon above the horizon.
 * @property isSteeredByMoon `true` when the Moon penalty made the chooser take another direction than the altitude
 * preference alone would have.
 */
internal data class MeteorCameraChoice(
    val direction: HorizontalCoordinate,
    val positionAngle: Double,
    val separationFromRadiant: Double,
    val moonSeparation: Double?,
    val isSteeredByMoon: Boolean,
)

/**
 * Advice for photographing a meteor shower with a fixed camera: which part of the sky to frame, how many meteors to
 * expect in the frame, and when the radiant is highest in the dark.
 *
 * **Direction.** Meteors seem to fly away from the radiant; they are shortest near it, where they come almost straight
 * at the observer, and longest well away from it, so a camera is best pointed 30° to 60° from the radiant rather than
 * at it. [bestAim] samples the ring 40° from the radiant every 10° of position angle and scores each direction above
 * [minimumAltitude] by its height (best at [preferredAltitude], falling linearly to nothing at 25° and 85°: clear of
 * the horizon haze, but not so high that the camera is awkward to frame) minus a Moon penalty. The Moon penalty is the
 * Moon's illuminated fraction times how far the direction lies inside [moonInfluenceRadius] of the Moon
 * (`1 − separation / 40°`, so 0 at 40° and a full Moon in the frame costs a whole point of score); it applies only when
 * the Moon is above the horizon. Directions whose scores differ by less than 1e-9 count as equal and the one with the
 * smaller position angle wins, which keeps the choice the same in both languages when the sky is symmetric.
 *
 * **Count.** The rate an eye sees under a perfect sky is [observedHourlyRate]: the zenithal hourly rate scaled by
 * sin(radiant altitude), the IMO definition `ZHR = HR · r^(6.5 − lm) / sin h` read backwards (IMO, Rendtel and Arlt
 * (eds.), *Handbook for Meteor Observers*; the correction for a partly obstructed sky is 1 here). [expectedInFrame]
 * takes the part of that rate which falls in the camera frame, the frame's solid angle over the 2π sr of sky above
 * the horizon, and divides by the population index raised to `6.5 − cameraLimitingMagnitude`, because every
 * magnitude the sensor cannot reach hides `r` times as many meteors. It is a planning estimate: it takes a fast, wide
 * lens to record meteors to about +2 for a bright Perseid-class shower, assumes the whole frame lies above the horizon
 * and ignores exposure, sky glow and trailing loss.
 *
 * Every call is cheap: [bestAim] is one sky frame, the Moon and 36 directions (about 0.1 ms), [bestTimeTonight] one sky
 * frame per ten minutes of darkness (about 45 µs each, 2 ms for a seven-hour dark). Nothing here uses the network or
 * shares state, so any thread may call it.
 */
object MeteorCameraAdvice {
    /** The distance of the sampled directions from the radiant, in degrees. */
    const val ringRadius: Double = 40.0

    /** The step between the sampled directions along the ring, in degrees of position angle. */
    const val ringStep: Double = 10.0

    /** Directions at or below this altitude, in degrees, are not offered. */
    const val minimumAltitude: Double = 25.0

    /** The altitude, in degrees, the preference for height peaks at. */
    const val preferredAltitude: Double = 55.0

    /** The altitude, in degrees, above which a direction scores nothing for height. */
    const val maximumAltitude: Double = 85.0

    /** [bestAim] returns `null` when the radiant is lower than this altitude, in degrees. */
    const val lowestRadiantAltitude: Double = -10.0

    /** The Moon penalty applies to directions closer than this to the Moon, in degrees. */
    const val moonInfluenceRadius: Double = 40.0

    /** The limiting magnitude of the naked eye under a perfect sky, the one the ZHR is defined for. */
    const val eyeLimitingMagnitude: Double = 6.5

    /** The limiting magnitude assumed for the camera: a fast wide lens records meteors to about +2 for a bright shower. */
    const val cameraLimitingMagnitude: Double = 2.0

    /** The time between the instants [bestTimeTonight] looks at, in seconds (10 minutes). */
    const val timeStep: Double = 600.0

    /** [bestTimeTonight] looks at no more than this many steps of one dark interval. */
    const val maximumTimeSteps: Int = 1_000

    private const val moonNoteThreshold: Double = 0.25
    private const val scoreTolerance: Double = 1e-9
    private const val hemisphereSolidAngle: Double = 2 * PI

    /**
     * The best direction to point a camera at [shower] at [date] (Swift `bestAim(shower:observer:at:moonConsidered:)`),
     * or `null` when the radiant is below [lowestRadiantAltitude] or no direction on the ring is above
     * [minimumAltitude].
     *
     * @param moonConsidered Subtract the Moon penalty and write a [MeteorAimAdvice.moonNote]; `false` ignores the Moon.
     */
    fun bestAim(
        shower: MeteorShower,
        observer: Observer,
        date: Instant,
        moonConsidered: Boolean = true,
    ): MeteorAimAdvice? {
        val frame = SkyFrame(date, observer)
        val radiant = frame.horizontal(shower.radiant(date), null, false)
        val moon = if (moonConsidered) {
            MeteorCameraMoon(
                frame.horizontal(SolarSystemBody.MOON, false),
                frame.state(SolarSystemBody.MOON).illuminatedFraction ?: 0.0,
            )
        } else {
            null
        }
        val choice = choose(radiant, moon) ?: return null
        val steered = choice.isSteeredByMoon
        val reason = "Meteors trail longest about ${ringRadius.toInt()}° from the radiant. " +
            if (steered) {
                "This view is high in the sky and turned away from the Moon."
            } else {
                "This view is high enough to clear the horizon haze."
            }
        return MeteorAimAdvice(
            azimuth = choice.direction.azimuth,
            altitude = choice.direction.altitude,
            separationFromRadiant = choice.separationFromRadiant,
            aimPoint = frame.j2000(choice.direction, false),
            moonNote = moon?.let { moonNote(it, choice) },
            reason = reason,
        )
    }

    /**
     * The direction on the ring around [radiant] (a geometric horizontal place) with the best score, or `null` when the
     * radiant is lower than [lowestRadiantAltitude] (or not a number) or no direction is above [minimumAltitude].
     * [moon] is `null` when the Moon is not considered; a Moon at or below the horizon is ignored.
     */
    internal fun choose(radiant: HorizontalCoordinate, moon: MeteorCameraMoon?): MeteorCameraChoice? {
        if (!(radiant.altitude >= lowestRadiantAltitude)) return null
        val center = radiant.unitVector
        val zenith = Vector3(0.0, 0.0, 1.0)
        var towardZenith = zenith - center * zenith.dot(center)
        if (towardZenith.length < 1e-9) {
            // The radiant is at the zenith or the nadir: every direction up is the same, so use north.
            val north = Vector3(0.0, 1.0, 0.0)
            towardZenith = north - center * north.dot(center)
        }
        val up = towardZenith.normalized
        val right = center.cross(up)
        val visibleMoon = moon?.takeIf { it.direction.altitude > 0 && it.illuminatedFraction.isFinite() }
        val moonVector = visibleMoon?.direction?.unitVector
        val lit = visibleMoon?.let { AstroMath.clamp(it.illuminatedFraction, 0.0, 1.0) } ?: 0.0
        val cosRadius = AstroMath.cosDeg(ringRadius)
        val sinRadius = AstroMath.sinDeg(ringRadius)

        var best: Candidate? = null
        var bestByAltitude: Candidate? = null
        for (step in 0 until (360.0 / ringStep).toInt()) {
            val angle = step * ringStep
            val offset = up * AstroMath.cosDeg(angle) + right * AstroMath.sinDeg(angle)
            val vector = center * cosRadius + offset * sinRadius
            val direction = HorizontalCoordinate(vector)
            if (!(direction.altitude > minimumAltitude)) continue
            val preference = altitudePreference(direction.altitude)
            val moonSeparation = moonVector?.let { vector.angle(it) }
            val penalty = moonSeparation?.let {
                lit * AstroMath.clamp((moonInfluenceRadius - it) / moonInfluenceRadius, 0.0, 1.0)
            } ?: 0.0
            val candidate = Candidate(direction, angle, preference, preference - penalty, moonSeparation)
            val currentBest = best
            if (currentBest == null || candidate.score > currentBest.score + scoreTolerance) best = candidate
            val currentByAltitude = bestByAltitude
            if (currentByAltitude == null || candidate.preference > currentByAltitude.preference + scoreTolerance) {
                bestByAltitude = candidate
            }
        }
        val picked = best ?: return null
        return MeteorCameraChoice(
            direction = picked.direction,
            positionAngle = picked.positionAngle,
            separationFromRadiant = picked.direction.unitVector.angle(center),
            moonSeparation = picked.moonSeparation,
            isSteeredByMoon = bestByAltitude?.positionAngle != picked.positionAngle,
        )
    }

    /** The preference for a direction's height, [0, 1]: 1 at [preferredAltitude], 0 at 25° and 85° and beyond. */
    internal fun altitudePreference(altitude: Double): Double {
        val spread = if (altitude < preferredAltitude) preferredAltitude - minimumAltitude else maximumAltitude - preferredAltitude
        return AstroMath.clamp(1 - abs(altitude - preferredAltitude) / spread, 0.0, 1.0)
    }

    internal fun moonNote(moon: MeteorCameraMoon, choice: MeteorCameraChoice): String {
        if (!(moon.direction.altitude > 0)) return "The Moon is below the horizon."
        val separation = choice.moonSeparation ?: return "The Moon is below the horizon."
        val lit = if (moon.illuminatedFraction.isFinite()) AstroMath.clamp(moon.illuminatedFraction, 0.0, 1.0) else 0.0
        val altitudeText = moon.direction.altitude.roundedHalfAwayFromZero().toInt()
        val separationText = separation.roundedHalfAwayFromZero().toInt()
        val percent = (lit * 100).roundedHalfAwayFromZero().toInt()
        val note = "The Moon is $altitudeText° up, $separationText° from this view, $percent% lit."
        return if (separation < moonInfluenceRadius && lit >= moonNoteThreshold) {
            "$note It is close enough to wash out the fainter meteors."
        } else {
            note
        }
    }

    /**
     * The hourly rate an observer under a perfect sky (limiting magnitude [limitingMagnitude], 6.5 by default) sees
     * (Swift `observedHourlyRate(shower:radiantAltitude:limitingMagnitude:)`): `ZHR · sin(altitude) · r^(lm − 6.5)`.
     * 0 when the radiant is not above the horizon or a value is not a usable number.
     *
     * @param radiantAltitude The radiant's geometric altitude in degrees.
     */
    fun observedHourlyRate(
        shower: MeteorShower,
        radiantAltitude: Double,
        limitingMagnitude: Double = eyeLimitingMagnitude,
    ): Double {
        val index = shower.populationIndex
        if (!(radiantAltitude > 0) || !radiantAltitude.isFinite() || !limitingMagnitude.isFinite()) return 0.0
        if (!(index.isFinite() && index > 0) || shower.zhr < 0) return 0.0
        val height = AstroMath.sinDeg(AstroMath.clamp(radiantAltitude, 0.0, 90.0))
        return shower.zhr * height * index.pow(limitingMagnitude - eyeLimitingMagnitude)
    }

    /**
     * The solid angle of the camera frame of [setup] in steradians (Swift `frameSolidAngle(of:)`): the rectangular
     * pyramid of the frame's width and height angles, `4 · asin(sin(w/2) · sin(h/2))`.
     */
    fun frameSolidAngle(setup: LensSetup): Double = frameSolidAngle(setup.fieldOfView)

    internal fun frameSolidAngle(frame: FrameSize): Double {
        val width = frame.horizontal
        val height = frame.vertical
        if (!(width.isFinite() && height.isFinite() && width > 0 && height > 0 && width < 180 && height < 180)) return 0.0
        return 4 * asin(AstroMath.sinDeg(width / 2) * AstroMath.sinDeg(height / 2))
    }

    /**
     * The meteors an hour expected in the frame of [setup] (Swift `expectedInFrame(observedHourlyRate:shower:setup:)`):
     * [observedHourlyRate] (what the eye sees, e.g. from [observedHourlyRate]) times the frame's share of the sky above
     * the horizon, `solid angle / 2π`, times the camera's handicap `r^−(6.5 − cameraLimitingMagnitude)`.
     *
     * For the Perseids (ZHR 100, r 2.2) with the radiant 60° up and a 24 mm full-frame lens (frame 73.7° × 53.1°,
     * 1.0866 sr) that is `100 · sin 60° · 0.1729 · 2.2^−4.5 = 0.431` meteors an hour. 0 when the rate is not a usable
     * number or the radiant's population index is not positive.
     */
    fun expectedInFrame(observedHourlyRate: Double, shower: MeteorShower, setup: LensSetup): Double {
        val index = shower.populationIndex
        if (!(observedHourlyRate.isFinite() && observedHourlyRate > 0)) return 0.0
        if (!(index.isFinite() && index > 0)) return 0.0
        val share = AstroMath.clamp(frameSolidAngle(setup) / hemisphereSolidAngle, 0.0, 1.0)
        return observedHourlyRate * share * index.pow(cameraLimitingMagnitude - eyeLimitingMagnitude)
    }

    /**
     * The mean number of minutes between two meteors in the frame (Swift
     * `meanMinutesPerMeteor(observedHourlyRate:shower:setup:)`), 60 divided by [expectedInFrame]; `null` when none is
     * expected.
     */
    fun meanMinutesPerMeteor(observedHourlyRate: Double, shower: MeteorShower, setup: LensSetup): Double? {
        val expected = expectedInFrame(observedHourlyRate, shower, setup)
        return if (expected > 0) 60 / expected else null
    }

    /**
     * The instant of astronomical darkness in [summary] with the radiant of [shower] highest, looking at the dark
     * intervals every [timeStep] and at their ends (Swift `bestTimeTonight(shower:summary:)`); the earliest when several
     * are equally high. `null` when the night has no astronomical darkness. The Moon is not considered: the Moon
     * penalty is part of [bestAim].
     *
     * Cost: one sky frame per ten minutes of darkness, about 45 µs each.
     */
    fun bestTimeTonight(shower: MeteorShower, summary: NightSummary): Instant? {
        var best: Instant? = null
        var bestAltitude = Double.NEGATIVE_INFINITY
        for (interval in summary.astronomicalDarknessIntervals) {
            val duration = interval.duration
            if (!duration.isFinite()) continue
            val steps = ceil(duration / timeStep).coerceAtMost(maximumTimeSteps.toDouble()).toInt()
            for (step in 0..steps) {
                val offset = if (step * timeStep < duration) step * timeStep else duration
                val time = interval.start.addingTimeInterval(offset)
                val altitude = SkyFrame(time, summary.observer).horizontal(shower.radiant(time), null, false).altitude
                if (altitude > bestAltitude) {
                    bestAltitude = altitude
                    best = time
                }
            }
        }
        return best
    }

    private class Candidate(
        val direction: HorizontalCoordinate,
        val positionAngle: Double,
        val preference: Double,
        val score: Double,
        val moonSeparation: Double?,
    )
}
