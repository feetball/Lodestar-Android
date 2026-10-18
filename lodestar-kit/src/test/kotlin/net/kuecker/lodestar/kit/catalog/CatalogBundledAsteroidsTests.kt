package net.kuecker.lodestar.kit.catalog

import net.kuecker.lodestar.kit.comets.AsteroidMagnitudes
import net.kuecker.lodestar.kit.comets.CometsHorizonsData
import net.kuecker.lodestar.kit.comets.SmallBodyBrightness
import net.kuecker.lodestar.kit.comets.SmallBodyEphemeris
import net.kuecker.lodestar.kit.comets.SmallBodyKind
import net.kuecker.lodestar.kit.comets.SmallBodyPhotometry
import net.kuecker.lodestar.kit.foundation.EquatorialCoordinate
import net.kuecker.lodestar.kit.foundation.JulianDate
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The bright asteroids that ship with the library (`asteroids.txt`, an extract of the Minor Planet Center's `MPCORB.DAT`
 * of 2026-10-18), against JPL Horizons and the Small-Body Database.
 */
class CatalogBundledAsteroidsTests {
    private val loaded = BundledAsteroids.load()
    private val byDesignation = loaded.bodies.associateBy { it.designation }

    // MARK: The data

    @Test
    fun testTheBundledFileDecodesWithoutRejections() {
        assertEquals(216, loaded.bodies.size, "the asteroids of the pinned MPCORB file that pass the cut")
        assertEquals(0, loaded.rejectedLineCount)
        assertEquals(loaded.bodies.size, loaded.bodies.map { it.id }.toSet().size)
        assertTrue(loaded.bodies.all { it.kind == SmallBodyKind.ASTEROID })
        assertTrue(loaded.bodies.all { it.number != null && it.designation == it.number.toString() })
        assertEquals(loaded.bodies.map { it.number }.sortedBy { it }, loaded.bodies.map { it.number }, "in the order of the MPC file")
        assertTrue(loaded.bodies.all { it.name.isNotEmpty() || it.number!! > 10_000 }, "the bright ones have names")
    }

    @Test
    fun testTheSelectionRuleHolds() {
        for (body in loaded.bodies) {
            val photometry = assertNotNull(body.photometry as? SmallBodyPhotometry.Asteroid, body.id)
            assertTrue(photometry.magnitudes.absoluteMagnitude <= BundledAsteroids.maximumAbsoluteMagnitude, body.id)
            assertTrue(assertNotNull(SmallBodyBrightness.peakMagnitudeEstimate(body)) <= BundledAsteroids.peakMagnitudeLimit + 1e-6, body.id)
        }
        for (number in listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 15, 433, 1036)) {
            assertNotNull(byDesignation["$number"], "($number) is bright")
        }
        // Objects that are bright in absolute magnitude but never in the sky are not in it.
        for (number in listOf(134_340, 136_199, 136_472, 90_377, 50_000, 28_978)) {
            assertEquals(null, byDesignation["$number"], "($number) is far away")
        }
        assertEquals("Vesta", byDesignation["4"]?.name)
        assertEquals("Ceres", byDesignation["1"]?.name)
        assertEquals("Eros", byDesignation["433"]?.name)
        assertEquals(3.25, (byDesignation["4"]?.photometry as SmallBodyPhotometry.Asteroid).magnitudes.absoluteMagnitude)
        assertEquals(0.15, (byDesignation["4"]?.photometry as SmallBodyPhotometry.Asteroid).magnitudes.slopeParameter)
        assertEquals(AsteroidMagnitudes.defaultSlopeParameter, 0.15)
    }

    @Test
    fun testTheFileIsSmallAndKeepsTheMinorPlanetCenterHeader() {
        val data = BundledAsteroids.bundledData()
        assertTrue(data.size in 30_000..60_000, "${data.size} bytes")
        val text = String(data, Charsets.UTF_8)
        // The licence of the MPC: the header stays with the data, the Minor Planet Center is credited.
        assertTrue(text.contains("MINOR PLANET CENTER ORBIT DATABASE (MPCORB)"))
        assertTrue(text.contains("Software programs may include this datafile amongst their datasets"))
        assertTrue(text.contains("proper attribution"))
        assertTrue(text.contains("https://www.minorplanetcenter.org/iau/MPCORB/MPCORB.DAT"))
        assertTrue(text.contains("Source: https://www.minorplanetcenter.net/iau/MPCORB/MPCORB.DAT.gz"))
        assertTrue(text.all { it.code < 128 }, "plain ASCII")
        assertEquals(BundledAsteroids.resourceFileName, "asteroids.txt")
    }

    // MARK: Freshness

    @Test
    fun testTheEpochOfTheElementsIsReported() {
        // MPC epoch K2669 = 2026 June 9 (Julian Day 2461200.5 in TT).
        val epoch = JulianDate.fromTt(2_461_200.5).date
        assertEquals(epoch, loaded.oldestEpoch)
        assertEquals(epoch, loaded.newestEpoch)
        assertTrue(loaded.bodies.all { it.orbit.epochJulianDayTT == 2_461_200.5 })
        assertEquals(0.0, loaded.ageDays(loaded.oldestEpoch))
        assertEquals(131.0, loaded.ageDays(Instant.parse("2026-10-18T00:00:00Z")), 0.01)
        assertEquals(365.0, loaded.ageDays(loaded.oldestEpoch.plusSeconds(365L * 86_400)), 1e-6)
        assertTrue(loaded.ageDays(Instant.parse("2026-01-01T00:00:00Z")) < 0)
    }

    // MARK: Against JPL

    @Test
    fun testElementsAgreeWithJPLHorizonsOsculatingElements() {
        // Horizons' own osculating elements for the same epoch (Rec #4 and Rec #1, solutions of 2021-Apr-13).
        for (case in listOf(CometsHorizonsData.vesta, CometsHorizonsData.ceres)) {
            val number = case.title.substringAfter("(").substringBefore(")")
            val orbit = assertNotNull(byDesignation[number], case.title).orbit
            assertEquals(case.epochTDB, orbit.epochJulianDayTT, case.title)
            assertEquals(case.perihelionDistance, orbit.perihelionDistanceAU, 2e-6, case.title)
            assertEquals(case.eccentricity, orbit.eccentricity, 2e-6, case.title)
            assertEquals(case.inclination, orbit.inclination, 2e-4, case.title)
            assertEquals(case.ascendingNode, orbit.longitudeOfAscendingNode, 2e-4, case.title)
            assertEquals(case.argumentOfPerihelion, orbit.argumentOfPerihelion, 2e-4, case.title)
            assertEquals(case.perihelionTimeTDB, orbit.perihelionJulianDayTT, 0.005, case.title)
            val magnitudes = (byDesignation[number]?.photometry as SmallBodyPhotometry.Asteroid).magnitudes
            assertEquals(case.absoluteMagnitude, magnitudes.absoluteMagnitude, case.title)
        }
    }

    @Test
    fun testPositionsAgreeWithJPLHorizons() {
        // Measured (arcseconds): 0.1 at the epoch, 0.3 after 30 days, 5.3 after 120 and 131, 37 after 365, 790 after 731.
        val allowed = mapOf(0.0 to 1.0, 30.0 to 2.0, 120.0 to 10.0, 131.0 to 12.0, 365.0 to 80.0, 731.0 to 1_700.0)
        val offsets = CatalogAsteroidReferenceData.offsetsDays
        assertEquals(allowed.keys.toList(), offsets)
        var checked = 0
        for (reference in CatalogAsteroidReferenceData.references) {
            val body = assertNotNull(byDesignation[reference.designation], reference.title)
            assertEquals(reference.title, body.title)
            for ((index, offset) in offsets.withIndex()) {
                val date = JulianDate(CatalogAsteroidReferenceData.epochJulianDay + offset)
                val state = assertNotNull(SmallBodyEphemeris.state(body, date))
                val expected = EquatorialCoordinate(reference.positions[index].first, reference.positions[index].second)
                val error = state.astrometricJ2000.angle(expected.unitVector) * 3600
                val limit = assertNotNull(allowed[offset])
                assertTrue(error <= limit, "${reference.title} at $offset days: $error arcsec, allowed $limit")
                checked += 1
            }
        }
        assertEquals(7 * 6, checked)
    }

    @Test
    fun testMagnitudeOfVestaAgreesWithHorizons() {
        // Horizons' APmag for Vesta 120 days after the epoch is 6.423 (H = 3.25, G = 0.32); the MPC's G = 0.15 gives 0.2 less.
        val vesta = assertNotNull(byDesignation["4"])
        val row = CometsHorizonsData.vesta.rows[2]
        val state = assertNotNull(SmallBodyEphemeris.state(vesta, JulianDate(CometsHorizonsData.vesta.epochTDB + row.offsetDays)))
        assertEquals(row.magnitude, assertNotNull(state.apparentMagnitude), 0.3)
    }

    // MARK: In the catalog

    @Test
    fun testTheBundledAsteroidsWorkInACatalog() {
        val bundled = CatalogFixture.catalog()
        val catalog = bundled.withSmallBodies(loaded.bodies)
        assertEquals(216, catalog.smallBodies.size)
        assertEquals(bundled.objects.size + 216, catalog.objects.size)
        assertTrue(catalog.smallBodies.all { it.category == ObjectCategory.ASTEROID })
        for ((query, id) in listOf(
            "Vesta" to "asteroid.4", "4 Vesta" to "asteroid.4", "Ceres" to "asteroid.1", "Pallas" to "asteroid.2",
            "Juno" to "asteroid.3", "Eros" to "asteroid.433", "Ganymed" to "asteroid.1036", "Hygiea" to "asteroid.10",
        )) {
            assertEquals(id, catalog.search(query, limit = 1).single().id, query)
        }
        assertEquals("asteroid.4", catalog.smallBodies.first().id, "Vesta has the brightest best case")
        // Listed asteroids are the NOTABLE or better ones: peak magnitude 10 or brighter.
        val listed = catalog.smallBodies.filter { it.isListed }
        assertTrue(listed.size in 80..140, "${listed.size}")
        assertTrue(listed.all { assertNotNull(it.estimatedPeakMagnitude) <= Prominence.notableSmallBodyMagnitude })
        assertEquals(9 + listed.size, catalog.objects(ObjectGroup.SOLAR_SYSTEM).size)
        assertEquals(setOf(Prominence.SHOWPIECE, Prominence.PROMINENT, Prominence.NOTABLE, Prominence.OBSCURE), catalog.smallBodies.map { it.prominence }.toSet())
        // Downloaded elements replace the bundled ones: the last body with an identifier wins.
        val fresher = loaded.bodies.first { it.id == "asteroid.4" }
        val replaced = bundled.withSmallBodies(loaded.bodies + fresher)
        assertEquals(216, replaced.smallBodies.size)
    }

    // MARK: Damaged files

    @Test
    fun testDamagedFilesAreRejectedWithATypedError() {
        val data = BundledAsteroids.bundledData()
        for (bad in listOf(ByteArray(0), "\n\n".toByteArray(), "no header and no orbits\n".toByteArray(), "-".repeat(160).toByteArray())) {
            assertFailsWith<CatalogError.BrightAsteroidsUnavailable> { BundledAsteroids.parse(bad) }
        }
        assertFailsWith<CatalogError.BrightAsteroidsUnavailable> { BundledAsteroids.parse(ByteArray(BundledAsteroids.maximumFileBytes + 1)) }
        val error = assertFailsWith<CatalogError.BrightAsteroidsUnavailable> { BundledAsteroids.parse(ByteArray(0)) }
        assertTrue(error.description.contains("bright-asteroid"), error.description)
        assertEquals(error.description, error.message)
        assertEquals(CatalogError.BrightAsteroidsUnavailable("x"), CatalogError.BrightAsteroidsUnavailable("x"))

        // A damaged line costs that line only.
        val text = String(data, Charsets.UTF_8)
        val damaged = BundledAsteroids.parse((text + "garbage line that is not an orbit\n" + "00001 not a number\n").toByteArray())
        assertEquals(216, damaged.bodies.size)
        assertEquals(2, damaged.rejectedLineCount)
        // Windows line endings, no final line break and a duplicated record are accepted.
        val windows = BundledAsteroids.parse(text.replace("\n", "\r\n").trimEnd().toByteArray())
        assertEquals(216, windows.bodies.size)
        assertEquals(0, windows.rejectedLineCount)
        val lastLine = text.trimEnd().lines().last()
        val duplicated = BundledAsteroids.parse((text + lastLine + "\n").toByteArray())
        assertEquals(216, duplicated.bodies.size)
        assertEquals(1, duplicated.rejectedLineCount)
        assertFalse(duplicated.bodies.isEmpty())
    }
}
