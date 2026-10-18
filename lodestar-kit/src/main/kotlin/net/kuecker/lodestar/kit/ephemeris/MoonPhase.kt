package net.kuecker.lodestar.kit.ephemeris

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.kuecker.lodestar.kit.foundation.*
import java.time.Instant
import kotlin.math.abs

/**
 * The eight named phases of the Moon.
 *
 * Serialized as its [rawValue] string, like the Swift `Codable` raw-value enum.
 *
 * @property rawValue The camel-case name used as the serialized form, e.g. `"waxingCrescent"`.
 */
@Serializable
enum class MoonPhaseName(val rawValue: String) {
    /** New moon: the Moon is within [principalHalfWidth] of the Sun. */
    @SerialName("newMoon")
    NEW_MOON("newMoon"),

    /** Waxing crescent. */
    @SerialName("waxingCrescent")
    WAXING_CRESCENT("waxingCrescent"),

    /** First quarter. */
    @SerialName("firstQuarter")
    FIRST_QUARTER("firstQuarter"),

    /** Waxing gibbous. */
    @SerialName("waxingGibbous")
    WAXING_GIBBOUS("waxingGibbous"),

    /** Full moon. */
    @SerialName("fullMoon")
    FULL_MOON("fullMoon"),

    /** Waning gibbous. */
    @SerialName("waningGibbous")
    WANING_GIBBOUS("waningGibbous"),

    /** Last quarter. */
    @SerialName("lastQuarter")
    LAST_QUARTER("lastQuarter"),

    /** Waning crescent. */
    @SerialName("waningCrescent")
    WANING_CRESCENT("waningCrescent"),
    ;

    /** The English name, e.g. "Waxing Crescent". */
    val displayName: String
        get() = when (this) {
            NEW_MOON -> "New Moon"
            WAXING_CRESCENT -> "Waxing Crescent"
            FIRST_QUARTER -> "First Quarter"
            WAXING_GIBBOUS -> "Waxing Gibbous"
            FULL_MOON -> "Full Moon"
            WANING_GIBBOUS -> "Waning Gibbous"
            LAST_QUARTER -> "Last Quarter"
            WANING_CRESCENT -> "Waning Crescent"
        }

    /** The name of the matching SF Symbol on Apple platforms (drawn as seen from the northern hemisphere). */
    val symbolName: String
        get() = when (this) {
            NEW_MOON -> "moonphase.new.moon"
            WAXING_CRESCENT -> "moonphase.waxing.crescent"
            FIRST_QUARTER -> "moonphase.first.quarter"
            WAXING_GIBBOUS -> "moonphase.waxing.gibbous"
            FULL_MOON -> "moonphase.full.moon"
            WANING_GIBBOUS -> "moonphase.waning.gibbous"
            LAST_QUARTER -> "moonphase.last.quarter"
            WANING_CRESCENT -> "moonphase.waning.crescent"
        }

    companion object {
        /**
         * Half-width in degrees of the elongation range, around 0°, 90°, 180° and 270°, that counts
         * as new moon, first quarter, full moon and last quarter (about 22 hours on either side).
         */
        const val principalHalfWidth: Double = 11.25

        /**
         * The phase name for a lunar elongation (Moon minus Sun ecliptic longitude) in degrees
         * (Swift `init(elongation:)`).
         */
        fun fromElongation(elongation: Double): MoonPhaseName {
            val normalized = AstroMath.normalizeDegrees(if (elongation.isFinite()) elongation else 0.0)
            val width = principalHalfWidth
            return when {
                normalized < width -> NEW_MOON
                normalized < 90 - width -> WAXING_CRESCENT
                normalized < 90 + width -> FIRST_QUARTER
                normalized < 180 - width -> WAXING_GIBBOUS
                normalized < 180 + width -> FULL_MOON
                normalized < 270 - width -> WANING_GIBBOUS
                normalized < 270 + width -> LAST_QUARTER
                normalized < 360 - width -> WANING_CRESCENT
                else -> NEW_MOON
            }
        }

        /** The phase whose [rawValue] is [raw], or `null` (Swift `init?(rawValue:)`). */
        fun fromRawValue(raw: String): MoonPhaseName? = entries.firstOrNull { it.rawValue == raw }
    }
}

/**
 * The four instants of a lunation that astronomers tabulate.
 *
 * Serialized as its [rawValue] string, like the Swift `Codable` raw-value enum.
 *
 * @property rawValue The camel-case name used as the serialized form, e.g. `"firstQuarter"`.
 */
@Serializable
enum class PrincipalMoonPhase(val rawValue: String) {
    /** New moon. */
    @SerialName("newMoon")
    NEW_MOON("newMoon"),

    /** First quarter. */
    @SerialName("firstQuarter")
    FIRST_QUARTER("firstQuarter"),

    /** Full moon. */
    @SerialName("fullMoon")
    FULL_MOON("fullMoon"),

    /** Last quarter. */
    @SerialName("lastQuarter")
    LAST_QUARTER("lastQuarter"),
    ;

    /** The lunar elongation in degrees at which the phase occurs: 0, 90, 180 or 270. */
    val elongation: Double
        get() = when (this) {
            NEW_MOON -> 0.0
            FIRST_QUARTER -> 90.0
            FULL_MOON -> 180.0
            LAST_QUARTER -> 270.0
        }

    /** The English name, e.g. "Full Moon". */
    val displayName: String get() = phaseName.displayName

    /** The name of the matching SF Symbol on Apple platforms. */
    val symbolName: String get() = phaseName.symbolName

    /**
     * The corresponding [MoonPhaseName]. (Swift: `name`, which would clash with `Enum.name` in Kotlin.)
     */
    val phaseName: MoonPhaseName
        get() = when (this) {
            NEW_MOON -> MoonPhaseName.NEW_MOON
            FIRST_QUARTER -> MoonPhaseName.FIRST_QUARTER
            FULL_MOON -> MoonPhaseName.FULL_MOON
            LAST_QUARTER -> MoonPhaseName.LAST_QUARTER
        }

    companion object {
        /** The phase whose [rawValue] is [raw], or `null` (Swift `init?(rawValue:)`). */
        fun fromRawValue(raw: String): PrincipalMoonPhase? = entries.firstOrNull { it.rawValue == raw }
    }
}

/**
 * The Moon's phase at one instant.
 *
 * Instances are produced by [MoonPhaseCalculator.info]; like Swift's memberwise initializer, the constructor is
 * not public.
 *
 * @property date The instant the values describe.
 * @property illuminatedFraction Fraction of the visible disc that is sunlit, [0, 1].
 * @property phaseAngle The Sun–Moon–Earth angle in degrees, [0, 180]; 0° at full moon, 180° at new moon.
 * @property elongation The Moon's apparent ecliptic longitude minus the Sun's, in degrees, [0, 360).
 * @property ageDays Days since the previous new moon.
 * @property name The named phase for this elongation.
 */
@ConsistentCopyVisibility
data class MoonPhaseInfo internal constructor(
    val date: Instant,
    val illuminatedFraction: Double,
    val phaseAngle: Double,
    val elongation: Double,
    val ageDays: Double,
    val name: MoonPhaseName,
) {
    /** `true` between new moon and full moon. */
    val isWaxing: Boolean get() = elongation < 180
}

/** Phase of the Moon and the dates of new moon, quarters and full moon. */
object MoonPhaseCalculator {
    /** Mean length of the synodic month in days. */
    const val meanSynodicMonthDays: Double = 29.530_588_861

    /** The largest number of phases [nextPhases] returns. */
    const val maximumPhaseCount: Int = 1_000

    /**
     * One principal phase and the instant it occurs (Swift: the tuple `(kind:date:)` of `nextPhases`).
     *
     * @property kind Which of the four principal phases.
     * @property date The instant of the phase.
     */
    data class PhaseEvent(val kind: PrincipalMoonPhase, val date: Instant)

    /** Mean rate of change of the elongation in degrees per day. */
    private const val meanElongationRate: Double = 360.0 / meanSynodicMonthDays

    /** The phase of the Moon at [date]. */
    fun info(date: Instant): MoonPhaseInfo {
        val julianDate = JulianDate(date)
        val state = Ephemeris.state(SolarSystemBody.MOON, julianDate)
        val elongation = Ephemeris.lunarElongation(julianDate)
        val previousNewMoon = instant(0.0, julianDate.addingDays(-elongation / meanElongationRate))
        return MoonPhaseInfo(
            date = date,
            illuminatedFraction = state.illuminatedFraction ?: 0.0,
            phaseAngle = state.phaseAngle ?: 0.0,
            elongation = elongation,
            ageDays = swiftMax(0.0, julianDate.ut - previousNewMoon.ut),
            name = MoonPhaseName.fromElongation(elongation),
        )
    }

    /**
     * The next [count] new moons, first quarters, full moons and last quarters after [date], in
     * time order. [count] is limited to [maximumPhaseCount]. A phase less than a second after
     * [date] counts as [date] itself, so passing the date of one result returns the following phases.
     *
     * The instants are found by root finding on the difference of the apparent geocentric
     * ecliptic longitudes of the Moon and the Sun, as in the Astronomical Almanac.
     */
    fun nextPhases(date: Instant, count: Int): List<PhaseEvent> {
        val wanted = minOf(maxOf(count, 0), maximumPhaseCount)
        val phases = ArrayList<PhaseEvent>()
        // Swift: `date.timeIntervalSince1970.isFinite`. An `Instant` cannot hold a non-finite time (the Foundation
        // helpers saturate ±infinity at `Instant.MIN` / `Instant.MAX`), so those two stand for it.
        val isFiniteDate = date != Instant.MIN && date != Instant.MAX
        if (!(wanted > 0 && isFiniteDate)) return phases
        val start = JulianDate(date)
        val startElongation = Ephemeris.lunarElongation(start)
        if (!startElongation.isFinite()) return phases
        var quarterIndex = (startElongation / 90).toInt() + 1
        var estimate = start.addingDays((90 * quarterIndex.toDouble() - startElongation) / meanElongationRate)
        // At most the first two candidates can fall at or before `date`; every later one is appended.
        for (candidate in 0 until wanted + 2) {
            if (phases.size >= wanted) break
            val kind = PrincipalMoonPhase.entries[quarterIndex % 4]
            val moment = instant(kind.elongation, estimate)
            if (moment.date.timeIntervalSince(date) > 1) {
                phases.add(PhaseEvent(kind, moment.date))
            }
            quarterIndex += 1
            estimate = moment.addingDays(meanSynodicMonthDays / 4)
        }
        return phases
    }

    /** The instant near [estimate] at which the lunar elongation equals [target] degrees (secant method). */
    private fun instant(target: Double, estimate: JulianDate): JulianDate {
        fun elongationError(julianDate: JulianDate): Double =
            AstroMath.normalizeSignedDegrees(Ephemeris.lunarElongation(julianDate) - target)

        var previous = estimate
        var previousError = elongationError(previous)
        var current = previous.addingDays(-previousError / meanElongationRate)
        var currentError = elongationError(current)
        for (iteration in 0 until 10) {
            if (!(abs(current.ut - previous.ut) > 1e-7 && currentError != previousError)) break
            val next = current.addingDays(-currentError * (current.ut - previous.ut) / (currentError - previousError))
            previous = current
            previousError = currentError
            current = next
            currentError = elongationError(current)
        }
        return current
    }
}
