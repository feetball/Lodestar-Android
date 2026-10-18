package net.kuecker.lodestar.kit.catalog

import net.kuecker.lodestar.kit.ephemeris.*
import net.kuecker.lodestar.kit.foundation.*
import java.time.Instant
import kotlin.math.abs
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** The SkyFrame and rise/set helpers for catalog objects. */
class CatalogSkyTests {
    /** 2026-10-17 03:00 UT. */
    private val date: Instant = JulianDate(JulianDate.julianDay(year = 2026, month = 10, day = 17, hour = 3)).date

    private fun observer(latitude: Double, longitude: Double = -105.0): Observer =
        Observer(GeographicLocation(latitude = latitude, longitude = longitude, elevation = 0.0))

    private fun frame(latitude: Double = 40.0, at: Instant? = null): SkyFrame =
        SkyFrame(at ?: date, observer(latitude))

    private fun obj(id: String): CelestialObject = assertNotNull(CatalogFixture.catalog().objectWithID(id), id)

    // MARK: Horizontal coordinates

    @Test
    fun testPolarisStandsAtTheAltitudeOfTheLatitude() {
        val polaris = obj("star.hip11767")
        for (latitude in listOf(10.0, 25.0, 40.0, 52.0, 60.0)) {
            val horizontal = frame(latitude = latitude).horizontal(polaris, refraction = false)
            // Polaris circles the pole at a distance of about 0.65°.
            assertEquals(latitude, horizontal.altitude, 0.8, "latitude $latitude")
        }
        val atForty = frame().horizontal(polaris, refraction = false)
        assertTrue(min(atForty.azimuth, 360 - atForty.azimuth) < 2.5, "Polaris is due north")
        val refracted = frame().horizontal(polaris, refraction = true)
        assertTrue(refracted.altitude > atForty.altitude)
        assertTrue(refracted.altitude - atForty.altitude < 0.05)
        assertEquals(refracted, frame().horizontal(polaris), "refraction is on by default")
    }

    @Test
    fun testPolesStandAtTheLatitudeAndItsNegative() {
        val north = obj("special.ncp")
        val south = obj("special.scp")
        for (latitude in listOf(-60.0, -33.9, 0.0, 25.0, 40.0, 89.0)) {
            val skyFrame = frame(latitude = latitude)
            val ncp = skyFrame.horizontal(north, refraction = false)
            val scp = skyFrame.horizontal(south, refraction = false)
            assertEquals(latitude, ncp.altitude, 1e-9)
            assertEquals(-latitude, scp.altitude, 1e-9)
            if (abs(latitude) < 89) {
                assertEquals(0.0, AstroMath.normalizeSignedDegrees(ncp.azimuth), 1e-7, "north pole is due north")
                assertEquals(180.0, scp.azimuth, 1e-7, "south pole is due south")
            }
        }
        val refracted = frame().horizontal(north, refraction = true)
        assertEquals(0.0194, refracted.altitude - 40, 0.004, "about 1.2′ of refraction at 40°")
        assertEquals(
            -34.0, frame(latitude = -34.0).horizontal(north, refraction = true).altitude, 1e-9,
            "no refraction far below the horizon",
        )
    }

    @Test
    fun testSolarSystemObjectsMatchTheEphemeris() {
        val skyFrame = frame()
        for (body in SolarSystemBody.entries) {
            val id = if (body == SolarSystemBody.SUN) "sun" else if (body == SolarSystemBody.MOON) "moon" else "planet.${body.rawValue}"
            val catalogObject = obj(id)
            for (refraction in listOf(true, false)) {
                assertEquals(skyFrame.horizontal(body, refraction), skyFrame.horizontal(catalogObject, refraction), id)
            }
            assertEquals(skyFrame.topocentricEquatorial(body), skyFrame.apparentEquatorial(catalogObject), id)
        }
        // The Sun at 03:00 UT over Colorado is below the horizon; the Moon is topocentric.
        assertTrue(skyFrame.horizontal(obj("sun"), refraction = false).altitude < 0)
        val moon = obj("moon")
        val topocentric = skyFrame.apparentEquatorial(moon)
        val geocentric = skyFrame.geocentricApparentEquatorial(SolarSystemBody.MOON)
        assertTrue(topocentric.angularSeparation(geocentric) > 0.05, "lunar parallax is up to a degree")
    }

    @Test
    fun testFixedObjectsIncludeProperMotionAndTheApparentPlace() {
        val skyFrame = frame()
        for (id in listOf("star.hip32349", "star.hip87937", "dso.M31", "dso.NGC7000", "special.galacticCenter", "star.hip11767")) {
            val catalogObject = obj(id)
            val position = catalogObject.position as? ObjectPosition.Fixed ?: fail(id)
            assertEquals(
                skyFrame.horizontal(position.coordinate, position.properMotion, refraction = false),
                skyFrame.horizontal(catalogObject, refraction = false),
                id,
            )
            assertEquals(
                skyFrame.apparentPlace(position.coordinate, position.properMotion),
                skyFrame.apparentEquatorial(catalogObject),
                id,
            )
        }
        // Barnard's Star moves 10.3″ a year; since 2000 that is more than 4′.
        val barnard = obj("star.hip87937")
        val position = barnard.position as? ObjectPosition.Fixed ?: fail("fixed")
        val moved = skyFrame.apparentEquatorial(barnard)
        val still = skyFrame.apparentPlace(position.coordinate, null)
        assertEquals(0.077, moved.angularSeparation(still), 0.01)
    }

    @Test
    fun testPoleApparentPlaceIsTheTruePole() {
        val skyFrame = frame()
        assertEquals(EquatorialCoordinate(rightAscension = 0.0, declination = 90.0), skyFrame.apparentEquatorial(obj("special.ncp")))
        assertEquals(EquatorialCoordinate(rightAscension = 0.0, declination = -90.0), skyFrame.apparentEquatorial(obj("special.scp")))
    }

    @Test
    fun testCustomTargetsBehaveLikeFixedObjects() {
        val position = EquatorialCoordinate(rightAscension = 150.0, declination = 10.0)
        val target = CelestialObject.custom("Leo field", position)
        val skyFrame = frame()
        assertEquals(skyFrame.horizontal(position, null, refraction = true), skyFrame.horizontal(target))
        assertEquals(skyFrame.apparentPlace(position), skyFrame.apparentEquatorial(target))
        assertNull(skyFrame.currentAngularSize(target))
        assertNull(skyFrame.currentMagnitude(target))
        val transit = RiseSetCalculator.riseTransitSet(target, observer(latitude = 40.0), DateInterval(date, 86_400.0))
        assertEquals(
            RiseSetCalculator.riseTransitSet(position, observer = observer(latitude = 40.0), interval = DateInterval(date, 86_400.0)),
            transit,
        )
    }

    @Test
    fun testEveryCatalogObjectHasAFinitePosition() {
        val catalog = CatalogFixture.catalog()
        val skyFrame = frame(latitude = -30.0, at = date)
        for (obj in catalog.objects) {
            val horizontal = skyFrame.horizontal(obj)
            assertTrue(horizontal.azimuth.isFinite() && horizontal.azimuth >= 0.0 && horizontal.azimuth < 360.0, obj.id)
            assertTrue(horizontal.altitude.isFinite() && horizontal.altitude >= -90.0 && horizontal.altitude <= 90.0, obj.id)
            val apparent = skyFrame.apparentEquatorial(obj)
            assertTrue(apparent.rightAscension.isFinite() && apparent.declination.isFinite(), obj.id)
        }
    }

    // MARK: Size and brightness

    @Test
    fun testCurrentAngularSizeOfSolarSystemBodies() {
        val moon = obj("moon")
        val sizes = ArrayList<Double>()
        for (day in 0 until 28) {
            val skyFrame = frame(at = date.addingTimeInterval(day.toDouble() * 86_400))
            val size = assertNotNull(skyFrame.currentAngularSize(moon))
            assertEquals(skyFrame.state(SolarSystemBody.MOON).angularDiameterArcseconds / 60, size.majorArcminutes, 1e-12)
            assertEquals(size.majorArcminutes, size.minorArcminutes)
            assertNull(size.positionAngle)
            assertTrue(size.majorArcminutes >= 28.5 && size.majorArcminutes <= 34.5, "${size.majorArcminutes}")
            sizes.add(size.majorArcminutes)
        }
        assertTrue(assertNotNull(sizes.maxOrNull()) - assertNotNull(sizes.minOrNull()) > 2, "the Moon's size changes through the month")
        val sun = assertNotNull(frame().currentAngularSize(obj("sun")))
        assertEquals(32.0, sun.majorArcminutes, 0.6)
        val jupiter = assertNotNull(frame().currentAngularSize(obj("planet.jupiter")))
        assertTrue(jupiter.majorArcminutes >= 0.45 && jupiter.majorArcminutes <= 0.9, "${jupiter.majorArcminutes}")
        assertTrue(jupiter.description.endsWith("″"), jupiter.description)
        assertTrue(assertNotNull(frame().currentAngularSize(obj("moon"))).description.endsWith("′"))
    }

    @Test
    fun testCurrentAngularSizeOfOtherObjectsIsTheCatalogSize() {
        val skyFrame = frame()
        val m31 = obj("dso.M31")
        assertEquals(m31.angularSize, skyFrame.currentAngularSize(m31))
        assertEquals("178′ × 70′", skyFrame.currentAngularSize(m31)?.description)
        assertNull(skyFrame.currentAngularSize(obj("star.hip32349")))
        assertNull(skyFrame.currentAngularSize(obj("special.ncp")))
    }

    @Test
    fun testCurrentMagnitude() {
        val skyFrame = frame()
        assertEquals(-26.7, assertNotNull(skyFrame.currentMagnitude(obj("sun"))), 0.3)
        val moon = assertNotNull(skyFrame.currentMagnitude(obj("moon")))
        assertTrue(moon >= -13.5 && moon <= -2, "$moon")
        val jupiter = assertNotNull(skyFrame.currentMagnitude(obj("planet.jupiter")))
        assertTrue(jupiter >= -3.0 && jupiter <= -1.4, "$jupiter")
        assertEquals(-1.44, skyFrame.currentMagnitude(obj("star.hip32349")))
        assertEquals(3.44, skyFrame.currentMagnitude(obj("dso.M31")))
        assertNull(skyFrame.currentMagnitude(obj("special.scp")))
        assertNull(skyFrame.currentMagnitude(obj("dso.B33")))
    }

    // MARK: Rise, transit and set

    @Test
    fun testRiseTransitSetOfAFixedObject() {
        val interval = DateInterval(date, 86_400.0)
        val place = observer(latitude = 40.0)
        val m31 = obj("dso.M31")
        val result = RiseSetCalculator.riseTransitSet(m31, place, interval)
        assertEquals(RiseTransitSet.Circumstance.NORMAL, result.circumstance)
        val rise = assertNotNull(result.rise)
        val set = assertNotNull(result.set)
        val transit = assertNotNull(result.transit)
        assertTrue(rise in interval && set in interval && transit in interval)
        // 90° minus the distance in declination (of date) from the latitude, geometric.
        val apparentDeclination = frame().apparentEquatorial(m31).declination
        assertEquals(90 - abs(apparentDeclination - 40), assertNotNull(result.transitAltitude), 0.02)
        val position = m31.position as? ObjectPosition.Fixed ?: fail("fixed")
        assertEquals(
            RiseSetCalculator.riseTransitSet(position.coordinate, position.properMotion, place, interval),
            result,
        )
        // The altitude at the rise and set times is that of the standard horizon.
        for (time in listOf(rise, set)) {
            val altitude = SkyFrame(time, place).horizontal(m31, refraction = false).altitude
            assertEquals(RiseSetCalculator.standardHorizonAltitude, altitude, 0.05)
        }
        val sirius = RiseSetCalculator.riseTransitSet(obj("star.hip32349"), place, interval)
        val siriusDeclination = frame().apparentEquatorial(obj("star.hip32349")).declination
        assertEquals(90 - abs(siriusDeclination - 40), assertNotNull(sirius.transitAltitude), 0.02)
    }

    @Test
    fun testRiseTransitSetOfSolarSystemBodiesUsesTheirOwnThresholds() {
        val interval = DateInterval(date, 86_400.0)
        val place = observer(latitude = 40.0)
        val bodies = listOf(
            "sun" to SolarSystemBody.SUN, "moon" to SolarSystemBody.MOON, "planet.jupiter" to SolarSystemBody.JUPITER,
            "planet.venus" to SolarSystemBody.VENUS,
        )
        for ((id, body) in bodies) {
            assertEquals(
                RiseSetCalculator.riseTransitSet(body, place, interval),
                RiseSetCalculator.riseTransitSet(obj(id), place, interval),
                id,
            )
        }
        val sun = RiseSetCalculator.riseTransitSet(obj("sun"), place, interval)
        assertEquals(RiseTransitSet.Circumstance.NORMAL, sun.circumstance)
        assertNotNull(sun.rise)
        assertNotNull(sun.set)
    }

    @Test
    fun testCircumpolarAndNeverRisingFixedObjects() {
        val interval = DateInterval(date, 86_400.0)
        val polaris = obj("star.hip11767")
        assertEquals(
            RiseTransitSet.Circumstance.ALWAYS_UP,
            RiseSetCalculator.riseTransitSet(polaris, observer(latitude = 40.0), interval).circumstance,
        )
        assertEquals(
            RiseTransitSet.Circumstance.ALWAYS_DOWN,
            RiseSetCalculator.riseTransitSet(polaris, observer(latitude = -40.0), interval).circumstance,
        )
        val sirius = obj("star.hip32349")
        assertEquals(
            RiseTransitSet.Circumstance.ALWAYS_DOWN,
            RiseSetCalculator.riseTransitSet(sirius, observer(latitude = 80.0), interval).circumstance,
        )
        assertEquals(
            RiseTransitSet.Circumstance.ALWAYS_UP,
            RiseSetCalculator.riseTransitSet(sirius, observer(latitude = -80.0), interval).circumstance,
        )
    }

    @Test
    fun testCelestialPolesNeverRiseOrSet() {
        val interval = DateInterval(date, 86_400.0)
        val north = obj("special.ncp")
        val south = obj("special.scp")

        class Case(val latitude: Double, val north: RiseTransitSet.Circumstance, val south: RiseTransitSet.Circumstance)

        val up = RiseTransitSet.Circumstance.ALWAYS_UP
        val down = RiseTransitSet.Circumstance.ALWAYS_DOWN
        val expectations = listOf(
            Case(40.0, up, down), Case(-40.0, down, up), Case(89.0, up, down), Case(-89.0, down, up),
            Case(0.0, up, up), Case(-0.5, up, up), Case(-0.6, down, up),
            Case(0.5, up, up), Case(0.6, up, down),
        )
        for (case in expectations) {
            val place = observer(latitude = case.latitude)
            val ncp = RiseSetCalculator.riseTransitSet(north, place, interval)
            val scp = RiseSetCalculator.riseTransitSet(south, place, interval)
            assertEquals(case.north, ncp.circumstance, "NCP at ${case.latitude}")
            assertEquals(case.south, scp.circumstance, "SCP at ${case.latitude}")
            for (result in listOf(ncp, scp)) {
                assertNull(result.rise)
                assertNull(result.set)
                assertNull(result.transit)
            }
            assertEquals(case.latitude, assertNotNull(ncp.transitAltitude), 1e-12)
            assertEquals(-case.latitude, assertNotNull(scp.transitAltitude), 1e-12)
        }
    }

    @Test
    fun testPolesAgreeWithTheHorizontalCoordinates() {
        val interval = DateInterval(date, 3_600.0)
        for (latitude in listOf(-70.0, -20.0, 15.0, 48.0, 85.0)) {
            val place = observer(latitude = latitude)
            val skyFrame = SkyFrame(date, place)
            for (id in listOf("special.ncp", "special.scp")) {
                val pole = obj(id)
                val result = RiseSetCalculator.riseTransitSet(pole, place, interval)
                val altitude = skyFrame.horizontal(pole, refraction = false).altitude
                assertEquals(altitude, assertNotNull(result.transitAltitude), 1e-9)
                assertEquals(
                    if (altitude >= RiseSetCalculator.standardHorizonAltitude) {
                        RiseTransitSet.Circumstance.ALWAYS_UP
                    } else {
                        RiseTransitSet.Circumstance.ALWAYS_DOWN
                    },
                    result.circumstance,
                )
            }
        }
    }
}
