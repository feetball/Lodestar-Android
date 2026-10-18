package net.kuecker.lodestar.kit.events

import net.kuecker.lodestar.kit.ephemeris.*
import net.kuecker.lodestar.kit.foundation.*
import java.time.Instant
import kotlin.math.ceil

/**
 * The new and full Moons of one search interval, computed once per run and shared by the finders that start from a
 * syzygy (special full Moons, lunar and solar eclipses).
 *
 * The table covers [from] and [to] with a margin of two days on both sides so that an eclipse whose greatest phase
 * falls inside the interval is never missed.
 */
internal class PhaseTable(from: Instant, to: Instant) {
    /** The instants of the new Moons in the table, in time order. */
    val newMoons: List<Instant>

    /** The instants of the full Moons in the table, in time order. */
    val fullMoons: List<Instant>

    init {
        val start = from.addingTimeInterval(-2 * JulianDate.secondsPerDay)
        val days = to.timeIntervalSince(start) / JulianDate.secondsPerDay + 2
        val count = minOf(ceil(days / 7.0).toInt() + 2, MoonPhaseCalculator.maximumPhaseCount)
        val news = ArrayList<Instant>()
        val fulls = ArrayList<Instant>()
        for (phase in MoonPhaseCalculator.nextPhases(start, count)) {
            when (phase.kind) {
                PrincipalMoonPhase.NEW_MOON -> news.add(phase.date)
                PrincipalMoonPhase.FULL_MOON -> fulls.add(phase.date)
                PrincipalMoonPhase.FIRST_QUARTER, PrincipalMoonPhase.LAST_QUARTER -> Unit
            }
        }
        newMoons = news
        fullMoons = fulls
    }
}
