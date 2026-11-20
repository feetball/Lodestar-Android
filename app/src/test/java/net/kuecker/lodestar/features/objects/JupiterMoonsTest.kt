package net.kuecker.lodestar.features.objects

import net.kuecker.lodestar.kit.ephemeris.GalileanMoons
import net.kuecker.lodestar.kit.ephemeris.GreatRedSpot
import net.kuecker.lodestar.kit.events.EventDetails
import net.kuecker.lodestar.kit.events.EventKind
import net.kuecker.lodestar.kit.foundation.GeographicLocation
import net.kuecker.lodestar.kit.foundation.JulianDate
import net.kuecker.lodestar.kit.foundation.Observer
import net.kuecker.lodestar.model.LocationSource
import net.kuecker.lodestar.model.ObserverContext
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JupiterMoonsTest {
    private val context = ObserverContext(
        observer = Observer(checkNotNull(GeographicLocation.validated(32.78, -96.80, 150.0))),
        timeZone = ZoneId.of("America/Chicago"),
        name = "Dallas",
        source = LocationSource.Device,
    )

    @Test
    fun theStripSpansTheFarthestMoonInSteps() {
        val example = GalileanMoons.positions(JulianDate.fromTt(2_448_972.50068))
        // Callisto stands 7.08 radii west and Europa 7.44: twelve radii are enough.
        assertEquals(12.0, JupiterMoons.halfRange(example))
        for (day in 0 until 60) {
            val state = GalileanMoons.positions(JulianDate(2_461_000.5 + day * 0.7))
            val range = JupiterMoons.halfRange(state)
            assertTrue(range in listOf(12.0, 18.0, 24.0, 30.0))
            assertTrue(state.moons.all { Math.abs(it.x) <= range })
        }
    }

    @Test
    fun theSummaryNamesEachMoonAndItsSide() {
        val summary = JupiterMoons.summary(GalileanMoons.positions(JulianDate.fromTt(2_448_972.50068)))
        assertTrue(summary.startsWith("East is on the left. Io 3.4 radii east"), summary)
        assertTrue("Europa 7.4 radii west" in summary && "Callisto 7.1 radii west" in summary, summary)
    }

    @Test
    fun tonightKeepsTheEventsThatCanBeSeenFromHere() {
        val from = Instant.parse("2026-12-01T00:00:00Z")
        val events = JupiterMoons.tonight(context, from, GreatRedSpot.reference)
        assertTrue(events.isNotEmpty())
        assertTrue(events.all { it.kind == EventKind.GREAT_RED_SPOT_TRANSIT || it.kind == EventKind.JUPITER_MOON_EVENT })
        assertTrue(events.all { it.visibility?.isObservable == true && it.date <= from.plusSeconds(24 * 3600) })
        val transit = assertNotNull(JupiterMoons.nextTransit(events, from))
        assertTrue(transit.details is EventDetails.GreatRedSpotTransit)
        val moonEvents = JupiterMoons.moonEvents(events, from)
        assertTrue(moonEvents.size <= JupiterMoons.EVENT_LIMIT && moonEvents.all { it.details is EventDetails.JupiterMoonEvent })
        assertTrue(JupiterMoons.moonEvents(events, from.plusSeconds(48 * 3600)).isEmpty())
    }
}
