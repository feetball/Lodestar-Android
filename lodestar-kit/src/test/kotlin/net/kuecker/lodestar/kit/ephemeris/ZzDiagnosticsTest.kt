package net.kuecker.lodestar.kit.ephemeris

import net.kuecker.lodestar.kit.events.*
import net.kuecker.lodestar.kit.foundation.*
import java.time.Instant
import kotlin.test.Test
import kotlin.test.fail

class ZzDiagnosticsTest {
    @Test
    fun diagnostics() {
        val sb = StringBuilder("\n")
        val ex = JulianDate.fromTt(2_448_972.50068)
        val s = JupiterPhysical.state(ex)
        sb.append("PHYS w1=${s.systemILongitude} w2=${s.systemIILongitude} DE=${s.earthDeclination} DS=${s.sunDeclination} P=${s.positionAngle} D=${s.distanceAU}\n")
        val m = GalileanMoons.positions(ex)
        for (p in m.moons) sb.append("MOON ${p.moon} x=${p.x} y=${p.y} z=${p.z}\n")
        sb.append("DE43b ${m.earthDeclination} DS ${m.sunDeclination}\n")
        for (t in listOf("2026-04-06T03:18:00Z", "2026-04-08T04:57:00Z", "2026-04-11T02:28:00Z", "2026-05-31T04:03:00Z")) {
            sb.append("CM2 $t ${JupiterPhysical.state(JulianDate(Instant.parse(t))).systemIILongitude}\n")
        }
        fun st(planet: SolarSystemBody, a: String, b: String) {
            for (e in PlanetStations.find(planet, Instant.parse(a), Instant.parse(b))) {
                val d = (e.details as EventDetails.PlanetStation).station
                sb.append("STATION $planet ${d.kind} ${e.date} lon=${d.longitude}\n")
            }
        }
        st(SolarSystemBody.MARS, "2024-10-01T00:00:00Z", "2025-04-01T00:00:00Z")
        st(SolarSystemBody.MERCURY, "2026-01-01T00:00:00Z", "2026-04-30T00:00:00Z")
        st(SolarSystemBody.JUPITER, "2026-11-01T00:00:00Z", "2027-01-31T00:00:00Z")
        st(SolarSystemBody.SATURN, "2026-06-01T00:00:00Z", "2026-12-31T00:00:00Z")
        st(SolarSystemBody.VENUS, "2026-09-01T00:00:00Z", "2026-12-31T00:00:00Z")
        st(SolarSystemBody.NEPTUNE, "2026-06-01T00:00:00Z", "2026-12-31T00:00:00Z")
        val transits = GreatRedSpot.reference.transits(Instant.parse("2026-11-19T00:00:00Z"), Instant.parse("2026-11-22T00:00:00Z"))
        for (t in transits) sb.append("GRS ${t.date} ${t.longitude}\n")
        val t0 = System.nanoTime()
        val ev = GalileanMoons.events(Instant.parse("2026-11-19T00:00:00Z"), Instant.parse("2026-12-19T00:00:00Z"))
        sb.append("MOONEVENTS 30d ${ev.size} in ${(System.nanoTime() - t0) / 1e6} ms\n")
        val t1 = System.nanoTime()
        val grs = GreatRedSpot.reference.transits(Instant.parse("2026-11-19T00:00:00Z"), Instant.parse("2027-11-19T00:00:00Z"))
        sb.append("GRS year ${grs.size} in ${(System.nanoTime() - t1) / 1e6} ms\n")
        val t2 = System.nanoTime()
        val stations = PlanetStations.events(Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-12-31T00:00:00Z"))
        sb.append("STATIONS ${stations.size} in ${(System.nanoTime() - t2) / 1e6} ms\n")
        // Compare moon event times with the Horizons check (2026-11-19..) for a few
        for (e in ev.take(12)) sb.append("EV ${e.moon} ${e.phenomenon} ${e.edge} ${e.date}\n")
        fail(sb.toString())
    }
}
