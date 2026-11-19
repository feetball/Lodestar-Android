package net.kuecker.lodestar.kit.ephemeris

import net.kuecker.lodestar.kit.foundation.*
import java.time.Instant
import kotlin.math.abs
import kotlin.math.ceil

/**
 * One crossing of Jupiter's central meridian by the Great Red Spot.
 *
 * @property date The instant the spot is on the central meridian (System II longitude of the meridian equal to the
 * spot's).
 * @property longitude The spot's System II longitude then, degrees, [0, 360).
 * @property wellPlaced The stretch around [date] in which the spot is within 50 minutes (about 30° of longitude) of the
 * meridian, so that it is clearly on the visible disc.
 */
data class GreatRedSpotTransit(
    val date: Instant,
    val longitude: Double,
    val wellPlaced: DateInterval,
)

/**
 * The Great Red Spot, placed by its System II longitude.
 *
 * The spot is not fixed: its longitude drifts by a few degrees a month and wanders. So the position is a longitude at
 * an [epoch] with a [driftPer30Days], and [reference] is the one the kit ships; the apps let the user replace it.
 *
 * The reference is Sky & Telescope's: its online tool "Transit Times of Jupiter's Great Red Spot"
 * (skyandtelescope.org/observing/interactive-sky-watching-tools/transit-times-of-jupiters-great-red-spot/) takes the
 * spot to be at System II longitude 91° on 2026 June 1 with a drift of 1.75° per month, "based on trends noted by
 * JUPOS". Other 2025 and 2026 sources agree within a few degrees: the BAA Jupiter Section's report no. 2 of 2025/26
 * has L2 = 77° on 2025 September 1 (S. Mizumoto, ALPO-Japan); the IMCCE JGRS service
 * (ssp.imcce.fr/webservices/vo-tools/jgrs, from JUPOS measurements) gives 103.5° at 2026 November 19 21:27 UT, 2.7°
 * (4.5 minutes of transit time) ahead of this extrapolation.
 *
 * @property longitude The System II longitude at [epoch], degrees, [0, 360).
 * @property epoch The instant the longitude is for.
 * @property driftPer30Days The spot's drift in System II longitude, degrees per 30 days (positive towards larger
 * longitudes).
 */
@ConsistentCopyVisibility
data class GreatRedSpot private constructor(
    val longitude: Double,
    val epoch: Instant,
    val driftPer30Days: Double,
) {
    /**
     * The spot's System II longitude at [date], degrees, [0, 360): the longitude at the epoch moved by the drift
     * (a straight line; the real drift wanders by a degree or two around it).
     */
    fun systemIILongitude(date: Instant): Double =
        AstroMath.normalizeDegrees(longitudeAt(JulianDate(date).ut))

    private fun longitudeAt(ut: Double): Double =
        longitude + driftPer30Days / 30 * (ut - JulianDate(epoch).ut)

    /**
     * The crossings of the central meridian from [from] to [to] (both inclusive), in time order: about 2.4 a day.
     * Intervals longer than [maximumSpanDays] are cut short; a reversed interval or one outside the years 1 to 4001
     * gives nothing. Each transit is found by Newton steps on [JupiterPhysical.state], accurate to the second for the
     * given longitude. Cost: about 0.1 ms for a day.
     */
    fun transits(from: Instant, to: Instant): List<GreatRedSpotTransit> {
        if (!(from >= distantPast && to <= distantFuture && to >= from)) return emptyList()
        val fromUT = JulianDate(from).ut
        val toUT = minOf(JulianDate(to).ut, fromUT + maximumSpanDays)
        val rate = JupiterPhysical.systemIIRate - driftPer30Days / 30
        val period = 360 / rate
        fun offset(ut: Double): Double =
            AstroMath.normalizeSignedDegrees(
                JupiterPhysical.state(JulianDate(ut)).systemIILongitude - longitudeAt(ut),
            )
        fun refine(guess: Double): Double {
            var ut = guess
            for (iteration in 0 until 6) {
                val step = offset(ut) / rate
                ut -= step
                if (abs(step) < 1e-8) break
            }
            return ut
        }
        val found = ArrayList<GreatRedSpotTransit>()
        var ut = refine(fromUT)
        while (ut < fromUT) ut = refine(ut + period)
        val limit = ceil(maximumSpanDays / period).toInt() + 1
        while (ut <= toUT && found.size < limit) {
            val date = JulianDate(ut).date
            found.add(
                GreatRedSpotTransit(
                    date = date,
                    longitude = AstroMath.normalizeDegrees(longitudeAt(ut)),
                    wellPlaced = DateInterval(
                        date.addingTimeInterval(-wellPlacedMinutes * 60),
                        date.addingTimeInterval(wellPlacedMinutes * 60),
                    ),
                ),
            )
            ut = refine(ut + period)
        }
        return found
    }

    companion object {
        /** The longitude of the reference spot in System II at [referenceEpoch], degrees. */
        const val referenceLongitude: Double = 91.0

        /** The day of [referenceLongitude]: 2026 June 1, 0h UT. */
        val referenceEpoch: Instant = Instant.parse("2026-06-01T00:00:00Z")

        /** The reference drift, degrees per 30 days (1.75° a month). */
        const val referenceDriftPer30Days: Double = 1.75

        /** The spot Sky & Telescope's tool assumes for 2026; see the class comment. */
        val reference: GreatRedSpot = GreatRedSpot(referenceLongitude, referenceEpoch, referenceDriftPer30Days)

        /** How long before and after a transit the spot counts as well placed, in minutes. */
        const val wellPlacedMinutes: Double = 50.0

        /** The longest interval, in days, that [transits] searches. */
        const val maximumSpanDays: Double = 366.0

        /** The largest drift [create] accepts, degrees per 30 days either way. */
        const val maximumDriftPer30Days: Double = 30.0

        /**
         * The spot at [longitude] (any finite value; it is taken modulo 360) on [epoch], drifting by [driftPer30Days]
         * (finite, at most [maximumDriftPer30Days] either way); `null` otherwise (Swift `init?(longitude:epoch:driftPer30Days:)`).
         */
        fun create(
            longitude: Double,
            epoch: Instant,
            driftPer30Days: Double = referenceDriftPer30Days,
        ): GreatRedSpot? {
            if (!longitude.isFinite() || !driftPer30Days.isFinite() || abs(driftPer30Days) > maximumDriftPer30Days) return null
            if (epoch < distantPast || epoch > distantFuture) return null
            return GreatRedSpot(AstroMath.normalizeDegrees(longitude), epoch, driftPer30Days)
        }
    }
}
