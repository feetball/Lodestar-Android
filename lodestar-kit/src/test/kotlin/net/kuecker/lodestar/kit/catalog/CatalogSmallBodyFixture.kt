package net.kuecker.lodestar.kit.catalog

import net.kuecker.lodestar.kit.comets.CometElementsParser
import net.kuecker.lodestar.kit.comets.CometMagnitudes
import net.kuecker.lodestar.kit.comets.CometsTestData
import net.kuecker.lodestar.kit.comets.MinorPlanetElementsParser
import net.kuecker.lodestar.kit.comets.SmallBody
import net.kuecker.lodestar.kit.comets.SmallBodyElements
import net.kuecker.lodestar.kit.comets.SmallBodyKind
import net.kuecker.lodestar.kit.comets.SmallBodyPhotometry
import kotlin.test.assertNotNull

/**
 * The comets and asteroids of the small-body catalog tests: real MPC lines of 2026-10-18 (`CometsTestData`) and a helper
 * for synthetic comets. The bundled catalog is the one of `CatalogFixture`.
 */
internal object CatalogSmallBodyFixture {
    val tsuchinshanAtlas: SmallBody = CometElementsParser.parseLine(CometsTestData.cometTsuchinshanAtlas)
    val ponsBrooks: SmallBody = CometElementsParser.parseLine(CometsTestData.cometPonsBrooks)
    val halley: SmallBody = CometElementsParser.parseLine(CometsTestData.cometHalley)
    val haleBopp: SmallBody = CometElementsParser.parseLine(CometsTestData.cometHaleBopp)
    val encke: SmallBody = CometElementsParser.parseLine(CometsTestData.cometEncke)
    val oumuamua: SmallBody = CometElementsParser.parseLine(CometsTestData.cometOumuamua)
    val ceres: SmallBody = MinorPlanetElementsParser.parseLine(CometsTestData.asteroidCeres)
    val vesta: SmallBody = MinorPlanetElementsParser.parseLine(CometsTestData.asteroidVesta)
    val eros: SmallBody = MinorPlanetElementsParser.parseLine(CometsTestData.asteroidEros)
    val yr4: SmallBody = MinorPlanetElementsParser.parseLine(CometsTestData.asteroidYr42024)
    val numberedUnnamed: SmallBody = MinorPlanetElementsParser.parseLine(CometsTestData.asteroidNumberedUnnamed)
    val withoutMagnitude: SmallBody = MinorPlanetElementsParser.parseLine(CometsTestData.asteroidUnknownH)

    val comets: List<SmallBody> = listOf(tsuchinshanAtlas, ponsBrooks, halley, haleBopp, encke)
    val asteroids: List<SmallBody> = listOf(ceres, vesta, eros, yr4)

    /** The bundled catalog with all of the bodies above that have a magnitude, added. */
    fun catalog(): Catalog = CatalogFixture.catalog().withSmallBodies(comets + asteroids)

    /**
     * A comet with the given [name] whose peak magnitude estimate is [peakMagnitude] (perihelion at 1 AU, where the
     * estimate is `M1 + 5·log10(√2)`, so M1 is the estimate minus 0.7526).
     */
    fun comet(designation: String, name: String, peakMagnitude: Double): SmallBody {
        val orbit = assertNotNull(SmallBodyElements.create(1.0, 0.5, 20.0, 30.0, 40.0, 2_461_000.0, 2_461_200.5))
        val magnitudes = assertNotNull(CometMagnitudes.create(peakMagnitude - 0.752574989159953, 10.0))
        return assertNotNull(
            SmallBody.create(designation, name, SmallBodyKind.COMET, null, orbit, SmallBodyPhotometry.Comet(magnitudes)),
        )
    }
}
