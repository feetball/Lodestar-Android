package net.kuecker.lodestar.features.smallbodies

import net.kuecker.lodestar.kit.comets.SmallBody
import net.kuecker.lodestar.kit.comets.SmallBodyEphemeris
import net.kuecker.lodestar.kit.comets.SmallBodyKind
import net.kuecker.lodestar.kit.comets.SmallBodyState
import net.kuecker.lodestar.kit.comets.state
import net.kuecker.lodestar.kit.foundation.JulianDate
import net.kuecker.lodestar.kit.foundation.SkyFrame
import java.time.Instant
import kotlin.math.abs
import kotlin.math.roundToInt

/** Something worth warning about when a comet or an asteroid is listed or aimed at. */
internal enum class SmallBodyWarning {
    /** Closer than 25° to the Sun: twilight and glare hide it. */
    TWILIGHT_GLOW,

    /** The orbital elements are old, so the position is only good to a fraction of a degree or worse. */
    POSITION_APPROXIMATE,

    /** The orbit file has no magnitude parameters for it. */
    NO_BRIGHTNESS_DATA,
}

/**
 * What the app shows about a comet or an asteroid at one instant: distances, elongation, phase, the magnitude
 * estimate, and the warnings. Computed from [SmallBodyState]; pure, so it can be made on any thread.
 */
internal data class SmallBodyInfo(
    val body: SmallBody,
    /** Distance from the Earth in AU. */
    val earthDistanceAU: Double,
    /** Distance from the Sun in AU. */
    val sunDistanceAU: Double,
    /** Angle from the Sun in degrees. */
    val elongation: Double,
    /** Sun–body–Earth angle in degrees. */
    val phaseAngle: Double,
    /** The total magnitude of a comet or V of an asteroid, an estimate (comet totals are good to a magnitude or two). */
    val magnitude: Double?,
    /** Bright enough (magnitude 10) and far enough from the Sun (25°) for a camera. */
    val isPhotographable: Boolean,
    /** Days between the date of the orbital elements and the instant of this info (always positive). */
    val elementsAgeDays: Double,
) {
    val isInTwilightGlow: Boolean get() = elongation < TWILIGHT_ELONGATION

    val hasApproximatePosition: Boolean get() = elementsAgeDays > maximumElementsAgeDays(body.kind)

    val hasNoBrightnessData: Boolean get() = magnitude == null

    /** The warnings that apply, most important first. */
    val warnings: List<SmallBodyWarning>
        get() = buildList {
            if (isInTwilightGlow) add(SmallBodyWarning.TWILIGHT_GLOW)
            if (hasApproximatePosition) add(SmallBodyWarning.POSITION_APPROXIMATE)
            if (hasNoBrightnessData) add(SmallBodyWarning.NO_BRIGHTNESS_DATA)
        }

    companion object {
        /** Below this angle from the Sun a body is "in the twilight glow". */
        const val TWILIGHT_ELONGATION = 25.0

        /** Comet elements older than this many days make the position approximate. */
        const val COMET_ELEMENTS_AGE_DAYS = 60.0

        /** Asteroid elements (the bundled ones) older than this many days make the position approximate. */
        const val ASTEROID_ELEMENTS_AGE_DAYS = 365.0

        fun maximumElementsAgeDays(kind: SmallBodyKind): Double =
            if (kind == SmallBodyKind.COMET) COMET_ELEMENTS_AGE_DAYS else ASTEROID_ELEMENTS_AGE_DAYS

        /** The info of [body] at [frame]'s instant; `null` when its orbit cannot be evaluated then. */
        fun compute(body: SmallBody, frame: SkyFrame): SmallBodyInfo? = of(frame.state(body) ?: return null)

        /** The info of [body] at [date]; the observer does not matter, the numbers are geocentric. */
        fun compute(body: SmallBody, date: Instant): SmallBodyInfo? =
            SmallBodyEphemeris.state(body, JulianDate(date))?.let(::of)

        /** The info for a state already computed. */
        fun of(state: SmallBodyState): SmallBodyInfo = SmallBodyInfo(
            body = state.body,
            earthDistanceAU = state.distanceAU,
            sunDistanceAU = state.heliocentricDistanceAU,
            elongation = state.elongation,
            phaseAngle = state.phaseAngle,
            magnitude = state.apparentMagnitude,
            isPhotographable = state.isPhotographable(),
            elementsAgeDays = abs(state.julianDate.tt - state.body.orbit.epochJulianDayTT),
        )
    }
}

/**
 * Where a comet's ion tail points, seen from the observer, and how to turn the camera so that the tail lies along a
 * side of the frame.
 */
internal data class SmallBodyTail(
    /** Degrees in (−180, 180]: 0 = the tail points straight up, positive = towards the observer's right. */
    val angleFromVertical: Double,
    /** Sun–body–Earth angle: a tail seen almost end-on is short and ill-defined. */
    val phaseAngle: Double,
) {
    /**
     * The smallest camera roll in degrees, in [−45, 45], that puts the tail along a side of the frame (up, down,
     * left or right all count): positive turns the camera clockwise as seen from behind it.
     */
    val cameraTilt: Double
        get() = angleFromVertical - 90.0 * (angleFromVertical / 90.0).roundToInt()

    /** `true` when the comet is seen so nearly end-on that its tail is foreshortened to a stub. */
    val isForeshortened: Boolean get() = phaseAngle < FORESHORTENED_PHASE || phaseAngle > 180.0 - FORESHORTENED_PHASE

    companion object {
        /** Phase angles closer than this to 0° or 180° foreshorten the tail by more than a factor of four. */
        const val FORESHORTENED_PHASE = 15.0

        fun compute(state: SmallBodyState, frame: SkyFrame): SmallBodyTail? {
            if (state.body.kind != SmallBodyKind.COMET) return null
            val angle = SmallBodyEphemeris.tailAngleFromVertical(state, frame) ?: return null
            return SmallBodyTail(angle, state.phaseAngle)
        }
    }
}

/** Everything the detail screen shows about a comet or an asteroid: the [info], and for a comet the [tail]. */
internal data class SmallBodyDetail(val info: SmallBodyInfo, val tail: SmallBodyTail?) {
    companion object {
        /** The detail of [body] at [frame]'s instant; `null` when its orbit cannot be evaluated then. */
        fun compute(body: SmallBody, frame: SkyFrame): SmallBodyDetail? {
            val state = frame.state(body) ?: return null
            return SmallBodyDetail(SmallBodyInfo.of(state), SmallBodyTail.compute(state, frame))
        }
    }
}
