package net.kuecker.lodestar.features.objects

import net.kuecker.lodestar.kit.catalog.CelestialObject
import net.kuecker.lodestar.kit.catalog.ObjectPosition
import net.kuecker.lodestar.kit.catalog.transit
import net.kuecker.lodestar.kit.foundation.EquatorialCoordinate
import net.kuecker.lodestar.kit.foundation.Observer
import net.kuecker.lodestar.kit.foundation.roundedHalfAwayFromZero
import net.kuecker.lodestar.kit.foundation.timeIntervalSince
import net.kuecker.lodestar.kit.planning.MeridianClock
import net.kuecker.lodestar.model.CoordinateStyle
import java.time.Instant
import kotlin.math.abs

/**
 * Where an object stands against the local meridian at one instant: its hour angle and the transits around it. The
 * Now card shows it as "Hour angle −1h 23m · transits in 1 h 23 min".
 */
internal data class ObjectDetailMeridian(
    /** The hour angle in hours, west positive, in (−12, 12]. */
    val hourAngle: Double,
    /** The first transit after [date] within a day, if any. */
    val next: Instant?,
    /** The last transit at or before [date] within a day, if any. */
    val previous: Instant?,
    /** The instant the hour angle and the transits were computed for. */
    val date: Instant,
) {
    /** The hour angle for display: "−1h 23m" (sexagesimal) or "−1.389 h" (decimal); "+" in the west. */
    fun hourAngleText(style: CoordinateStyle): String = hourAngleText(hourAngle, style)

    /**
     * "transits in 1 h 23 min" while the object is east of the meridian, "transited 25 min ago" once it is west of it;
     * `null` when that transit is more than a day away.
     */
    val transitText: String?
        get() = if (hourAngle < 0) {
            next?.let { transitIn(it.timeIntervalSince(date)) }
        } else {
            previous?.let { transitedAgo(date.timeIntervalSince(it)) }
        }

    /** The value of the card's row: "−1h 23m · transits in 1 h 23 min". */
    fun rowText(style: CoordinateStyle): String =
        listOfNotNull(hourAngleText(style), transitText).joinToString(" · ")

    companion object {
        /**
         * The hour angle and transits of [obj] at [date], or `null` for an object that has none: a celestial pole, or a
         * comet or asteroid whose orbit cannot be evaluated ([isEvaluable] false).
         *
         * @param apparent The object's apparent place at [date] (true equator and equinox of date).
         */
        fun of(
            obj: CelestialObject,
            observer: Observer,
            date: Instant,
            apparent: EquatorialCoordinate,
            isEvaluable: Boolean = true,
        ): ObjectDetailMeridian? {
            if (!isEvaluable || obj.position is ObjectPosition.CelestialPole) return null
            val hourAngle = MeridianClock.hourAngle(apparent, date, observer)
            if (!hourAngle.isFinite()) return null
            val transits = MeridianClock.transit(obj, date, observer)
            return ObjectDetailMeridian(hourAngle, transits.next, transits.previous, date)
        }

        /** The hour angle in hours as "−1h 23m", "+0h 25m" or "0h 00m"; or "−1.389 h", "+0.417 h", "0.000 h". */
        fun hourAngleText(hours: Double, style: CoordinateStyle): String {
            val magnitude = abs(hours)
            if (!hours.isFinite() || magnitude > MAXIMUM_HOURS) return MISSING
            return when (style) {
                CoordinateStyle.SEXAGESIMAL -> {
                    val minutes = (magnitude * 60).roundedHalfAwayFromZero().toLong()
                    sign(hours, minutes == 0L) + "${minutes / 60}h ${(minutes % 60).toString().padStart(2, '0')}m"
                }
                CoordinateStyle.DECIMAL -> {
                    val thousandths = (magnitude * 1000).roundedHalfAwayFromZero().toLong()
                    sign(hours, thousandths == 0L) + "${thousandths / 1000}.${(thousandths % 1000).toString().padStart(3, '0')} h"
                }
            }
        }

        /** "in 1 h 23 min" for the transit [seconds] ahead; "now" within half a minute. */
        fun transitIn(seconds: Double): String? {
            val minutes = wholeMinutes(seconds) ?: return null
            return if (minutes == 0L) "transits now" else "transits in ${durationText(minutes)}"
        }

        /** "transited 25 min ago" for the transit [seconds] behind; "just now" within half a minute. */
        fun transitedAgo(seconds: Double): String? {
            val minutes = wholeMinutes(seconds) ?: return null
            return if (minutes == 0L) "transited just now" else "transited ${durationText(minutes)} ago"
        }

        /** A span of [minutes] as "25 min", "1 h" or "1 h 23 min". */
        fun durationText(minutes: Long): String {
            val hours = minutes / 60
            val rest = minutes % 60
            return when {
                hours == 0L -> "$rest min"
                rest == 0L -> "$hours h"
                else -> "$hours h $rest min"
            }
        }

        /** [seconds] rounded to whole minutes (never negative, at most a year); `null` when it is not finite. */
        private fun wholeMinutes(seconds: Double): Long? =
            if (seconds.isFinite()) (seconds.coerceIn(0.0, MAXIMUM_SECONDS) / 60).roundedHalfAwayFromZero().toLong() else null

        private fun sign(value: Double, isZero: Boolean): String = if (isZero) "" else if (value < 0) "−" else "+"

        private const val MISSING = "—"
        private const val MAXIMUM_HOURS = 24.0
        private const val MAXIMUM_SECONDS = 365 * 86_400.0
    }
}
