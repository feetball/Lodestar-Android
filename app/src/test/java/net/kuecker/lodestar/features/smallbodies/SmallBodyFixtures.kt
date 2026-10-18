package net.kuecker.lodestar.features.smallbodies

import net.kuecker.lodestar.kit.catalog.Catalog
import net.kuecker.lodestar.kit.catalog.CelestialObject
import net.kuecker.lodestar.kit.comets.CometElementsParser
import net.kuecker.lodestar.kit.comets.CometMagnitudes
import net.kuecker.lodestar.kit.comets.MinorPlanetElementsParser
import net.kuecker.lodestar.kit.comets.SmallBody
import net.kuecker.lodestar.kit.comets.SmallBodyElements
import net.kuecker.lodestar.kit.comets.SmallBodyKind
import net.kuecker.lodestar.kit.comets.SmallBodyPhotometry
import net.kuecker.lodestar.kit.foundation.GeographicLocation
import net.kuecker.lodestar.kit.foundation.Observer
import java.time.Instant
import java.util.Locale
import kotlin.test.assertNotNull

/**
 * Comets and asteroids for the tests: real MPC lines of 2026-10-18 (the engine's own test data) and hand-built bodies.
 * Everything here is deterministic.
 */
internal object SmallBodyFixtures {
    /** The date of the MPC lines: their elements are osculating at 2026-10-17. */
    val date: Instant = Instant.parse("2026-10-18T03:00:00Z")

    val observer = Observer(checkNotNull(GeographicLocation.validated(40.0, -105.3, 1650.0)))

    val tsuchinshanAtlas: SmallBody = CometElementsParser.parseLine(
        """    CK23A030  2024 09 27.8337  0.391312  1.000180  308.5842   21.6759  139.0991  20261017   6.5  3.2  C/2023 A3 (Tsuchinshan-ATLAS)                            MPEC 2026-O98""",
    )
    val ponsBrooks: SmallBody = CometElementsParser.parseLine(
        """0012P         2024 04 21.2066  0.781920  0.954748  199.0355  255.8678   74.1469  20261017   5.0  6.0  12P/Pons-Brooks                                          MPEC 2026-TI5""",
    )
    val halley: SmallBody = CometElementsParser.parseLine(
        """0001P         2061 08  1.5218  0.570945  0.968030  112.1616   59.2610  162.1917  20261017   5.5  3.2  1P/Halley                                                MPC191592""",
    )
    val encke: SmallBody = CometElementsParser.parseLine(
        """0002P         2027 02 10.2276  0.338603  0.847319  187.2873  334.0197   11.3480  20261017  14.4  4.0  2P/Encke                                                 MPC xxxxx""",
    )
    val leonard: SmallBody = CometElementsParser.parseLine(
        """    PK26R020  2026 10 25.2362  0.789531  0.898740  205.5263  179.2306   78.0993  20261017  16.0  4.0  P/2026 R2 (Leonard)                                      MPC xxxxx""",
    )

    /** A comet on a hyperbolic orbit whose perihelion was 168 years ago: its position cannot be evaluated in 2026. */
    val longGone: SmallBody = run {
        val orbit = assertNotNull(SmallBodyElements.create(1.0, 1.5, 30.0, 40.0, 50.0, 2_400_000.0, 2_400_000.0))
        val magnitudes = assertNotNull(CometMagnitudes.create(10.0, 10.0))
        assertNotNull(SmallBody.create("C/1858 L1", "Gone", SmallBodyKind.COMET, null, orbit, SmallBodyPhotometry.Comet(magnitudes)))
    }

    val ceres: SmallBody = MinorPlanetElementsParser.parseLine(
        """00001    3.34  0.15 K2669 274.41935   73.29420   80.24863   10.58803  0.0796923  0.21430445   2.7655526  0 E2026-SD4  7376 127 1801-2026 0.82 M-v 30k MPCORBFIT  4000      (1) Ceres              20260922""",
    )
    val vesta: SmallBody = MinorPlanetElementsParser.parseLine(
        """00004    3.25  0.15 K2669  81.19019  151.46856  103.70135    7.14393  0.0902037  0.27161836   2.3613660  0 E2026-T23  7610 112 1821-2025 0.84 M-p 18k Pan        4000      (4) Vesta              20250624""",
    )
    val yr4: SmallBody = MinorPlanetElementsParser.parseLine(
        """K24Y04R 23.93  0.15 K2669 139.21182  134.36645  271.36696    3.40806  0.6613330  0.24696810   2.5159894  1 E2026-TG8   509   2 2024-2026 0.46 M-v 3Ek Pan        0803          2024 YR4           20260226""",
    )

    /** An asteroid whose magnitude parameters are unknown (H = 99.99). */
    val withoutMagnitude: SmallBody = MinorPlanetElementsParser.parseLine(
        """J91T00T 99.99  0.15 K2669 176.22907  218.04073  192.31551   14.79902  0.1614954  0.75483638   1.1946321  7 E2026-TC5     6   1    3 days 0.86 M-v 3Ek Pan        2804          1991 TT            19911009""",
    )

    /**
     * A comet at perihelion (1.6 AU from the Sun) on [date]'s opposition side: at 2026-10-18 the Earth stands at
     * heliocentric longitude 25°, so a comet at longitude 25° is 0.6 AU away at opposition, in the middle of the night,
     * and with M1 4.8 and K1 10 about magnitude 5.7.
     */
    fun oppositionComet(designation: String = "C/2026 X1", name: String = "Test", totalMagnitude: Double = 4.8): SmallBody {
        val orbit = assertNotNull(SmallBodyElements.create(1.6, 0.05, 0.0, 25.0, 0.0, 2_461_331.5, 2_461_331.5))
        val magnitudes = assertNotNull(CometMagnitudes.create(totalMagnitude, 10.0))
        return assertNotNull(
            SmallBody.create(designation, name, SmallBodyKind.COMET, null, orbit, SmallBodyPhotometry.Comet(magnitudes)),
        )
    }

    /** A comet with the given peak magnitude estimate (perihelion at 1 AU, as the engine's tests do). */
    fun comet(designation: String, name: String, peakMagnitude: Double): SmallBody {
        val orbit = assertNotNull(SmallBodyElements.create(1.0, 0.5, 20.0, 30.0, 40.0, 2_461_000.0, 2_461_200.5))
        val magnitudes = assertNotNull(CometMagnitudes.create(peakMagnitude - 0.752574989159953, 10.0))
        return assertNotNull(
            SmallBody.create(designation, name, SmallBodyKind.COMET, null, orbit, SmallBodyPhotometry.Comet(magnitudes)),
        )
    }

    val comets: List<SmallBody> = listOf(tsuchinshanAtlas, ponsBrooks, halley, encke, leonard)
    val asteroids: List<SmallBody> = listOf(ceres, vesta, yr4, withoutMagnitude)

    fun obj(body: SmallBody): CelestialObject = CelestialObject.fromSmallBody(body)

    /** The bundled catalog with the fixture bodies (and [extra]) added. */
    fun catalog(extra: List<SmallBody> = emptyList()): Catalog = bundled.withSmallBodies(comets + asteroids + extra)

    val bundled: Catalog by lazy { Catalog.loadBundled() }

    /** Numbers and dates in the tests are read with the US conventions, whatever the machine's locale. */
    fun useUSLocale() = Locale.setDefault(Locale.US)
}
