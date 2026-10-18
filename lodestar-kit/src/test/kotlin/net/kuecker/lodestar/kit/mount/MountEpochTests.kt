package net.kuecker.lodestar.kit.mount

import net.kuecker.lodestar.kit.foundation.AstroMath
import net.kuecker.lodestar.kit.foundation.EquatorialCoordinate
import net.kuecker.lodestar.kit.foundation.GeographicLocation
import net.kuecker.lodestar.kit.foundation.JulianDate
import net.kuecker.lodestar.kit.foundation.Observer
import net.kuecker.lodestar.kit.foundation.ProperMotion
import net.kuecker.lodestar.kit.foundation.SkyFrame
import net.kuecker.lodestar.kit.foundation.instantFromTimeIntervalSince1970
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** J2000 to mount-frame conversions: published values (Meeus) and round trips. */
class MountEpochTests {
    private val observer = Observer(GeographicLocation(latitude = 0.0, longitude = 0.0))

    private fun frame(jd: JulianDate) = SkyFrame(jd, observer)

    private fun frame(unixSeconds: Double) = SkyFrame(instantFromTimeIntervalSince1970(unixSeconds), observer)

    private fun hms(hours: Int, minutes: Int, seconds: Double) = (hours + minutes / 60.0 + seconds / 3600.0) * 15.0

    private fun dms(degrees: Int, minutes: Int, seconds: Double) = degrees + minutes / 60.0 + seconds / 3600.0

    private val thetaPersei = EquatorialCoordinate(hms(2, 44, 11.986), dms(49, 13, 42.48))

    private fun thetaPerseiMotion() = ProperMotion(0.03425 * 15 * 1000 * AstroMath.cosDeg(thetaPersei.declination), -89.5)

    @Test
    fun testMeanOfDateMatchesMeeusExample21b() {
        // Meeus, Astronomical Algorithms 21.b: θ Persei, J2000.0 → 2028 November 13.19 TD, with proper motion:
        // α = 2h 46m 11.331s = 41.547214°, δ = +49° 20′ 54.54″ = 49.348483°.
        val sky = frame(JulianDate.fromTt(2_462_088.69))
        val place = MountEpoch.MEAN_OF_DATE.fromJ2000(thetaPersei, sky, thetaPerseiMotion())
        assertEquals(41.547214, place.rightAscension, 0.00001)
        assertEquals(49.348483, place.declination, 0.00001)
    }

    @Test
    fun testApparentOfDateMatchesMeeusExample23a() {
        // Meeus 23.a: the apparent place for the same instant: α = 2h 46m 14.390s, δ = +49° 21′ 07.45″.
        val sky = frame(JulianDate.fromTt(2_462_088.69))
        val place = MountEpoch.APPARENT_OF_DATE.fromJ2000(thetaPersei, sky, thetaPerseiMotion())
        assertEquals(hms(2, 46, 14.390), place.rightAscension, 0.05 / 3600)
        assertEquals(dms(49, 21, 7.45), place.declination, 0.05 / 3600)
    }

    @Test
    fun testJ2000FrameOnlyAppliesProperMotion() {
        val sky = frame(JulianDate.fromTt(2_462_088.69))
        assertEquals(thetaPersei, MountEpoch.J2000.fromJ2000(thetaPersei, sky))
        val moved = MountEpoch.J2000.fromJ2000(thetaPersei, sky, thetaPerseiMotion())
        assertEquals(thetaPerseiMotion().apply(thetaPersei, sky.yearsSinceJ2000), moved)
        assertTrue(moved.angularSeparation(thetaPersei) in 0.0025..0.0030, "moved ${moved.angularSeparation(thetaPersei)}")
        // Barnard's Star moves 10.3 arcseconds per year in declination.
        val barnard = EquatorialCoordinate.fromRightAscensionHours(17.9631, 4.6934)
        val sky2026 = frame(1_792_296_000.0)
        val shifted = MountEpoch.J2000.fromJ2000(barnard, sky2026, ProperMotion(-798.58, 10_328.12))
        assertEquals(10.32812 * sky2026.yearsSinceJ2000 / 3600, shifted.declination - barnard.declination, 0.0002)
    }

    @Test
    fun testMeanOfDateIsPurePrecession() {
        // 26.8 years of general precession move a place on the equator at α = 0 by about 46.1″ × 26.8 = 0.34°.
        val sky = frame(1_792_296_000.0)
        val origin = EquatorialCoordinate(0.0, 0.0)
        val place = MountEpoch.MEAN_OF_DATE.fromJ2000(origin, sky)
        assertEquals(0.343, place.rightAscension, 0.005)
        assertEquals(0.149, place.declination, 0.005)
        // Apparent and mean places differ by nutation and aberration only: below 0.01° (36″).
        val apparent = MountEpoch.APPARENT_OF_DATE.fromJ2000(origin, sky)
        val difference = place.angularSeparation(apparent)
        assertTrue(difference > 0.0005 && difference < 0.01, "difference $difference")
    }

    @Test
    fun testPlacesSurviveTheRoundTrip() {
        val dates = listOf(946_728_000.0, 1_000_000_000.0, 1_792_296_000.0, 2_550_000_000.0, 4_000_000_000.0)
        val places = ArrayList<EquatorialCoordinate>()
        for (ra in listOf(0.0, 0.0001, 45.0, 123.456, 180.0, 270.5, 359.9999)) {
            for (dec in listOf(-89.9, -60.0, -10.0, 0.0, 33.3, 75.0, 89.9)) places.add(EquatorialCoordinate(ra, dec))
        }
        places.add(EquatorialCoordinate(10.0, 90.0))
        places.add(EquatorialCoordinate(250.0, -90.0))
        for (epoch in MountEpoch.entries) {
            for (date in dates) {
                val sky = frame(date)
                for (place in places) {
                    val mount = epoch.fromJ2000(place, sky)
                    val back = epoch.toJ2000(mount, sky)
                    // First-order aberration inversion is good to a few milliarcseconds; the rest is exact.
                    assertEquals(0.0, place.angularSeparation(back), 2e-6, "$epoch $date $place")
                }
            }
        }
    }

    @Test
    fun testRightAscensionStaysInRangeAcrossTheWrap() {
        val sky = frame(1_792_296_000.0)
        val nearZero = EquatorialCoordinate(359.9999, 10.0)
        for (epoch in MountEpoch.entries) {
            val mount = epoch.fromJ2000(nearZero, sky)
            assertTrue(mount.rightAscension >= 0.0 && mount.rightAscension < 360.0, "$epoch ${mount.rightAscension}")
            val separation = mount.angularSeparation(nearZero)
            assertTrue(separation < 0.5, "$epoch $separation")
        }
    }

    @Test
    fun testRawValues() {
        assertEquals(MountEpoch.MEAN_OF_DATE, MountEpoch.fromRawValue("meanOfDate"))
        assertEquals(MountEpoch.APPARENT_OF_DATE, MountEpoch.fromRawValue("apparentOfDate"))
        assertEquals(MountEpoch.J2000, MountEpoch.fromRawValue("j2000"))
        assertNull(MountEpoch.fromRawValue("B1950"))
        for (protocol in MountProtocol.entries) assertEquals(protocol, MountProtocol.fromRawValue(protocol.rawValue))
        for (feature in MountFeature.entries) assertEquals(feature, MountFeature.fromRawValue(feature.rawValue))
        for (phase in MountPhase.entries) assertEquals(phase, MountPhase.fromRawValue(phase.rawValue))
        for (direction in MountNudgeDirection.entries) assertEquals(direction, MountNudgeDirection.fromRawValue(direction.rawValue))
        for (rate in MountNudgeRate.entries) assertEquals(rate, MountNudgeRate.fromRawValue(rate.rawValue))
        for (flavor in NexStarFlavor.entries) assertEquals(flavor, NexStarFlavor.fromRawValue(flavor.rawValue))
        for (precision in LX200Precision.entries) assertEquals(precision, LX200Precision.fromRawValue(precision.rawValue))
        for (dialect in LX200Dialect.entries) assertEquals(dialect, LX200Dialect.fromRawValue(dialect.rawValue))
        assertNull(MountProtocol.fromRawValue("x"))
    }

    @Test
    fun testNudgeRates() {
        assertEquals(15.041068 / 3600, MountNudgeRate.siderealDegreesPerSecond, 1e-8)
        assertEquals(MountNudgeRate.siderealDegreesPerSecond, MountNudgeRate.GUIDE.degreesPerSecond, 0.0)
        assertEquals(8 * MountNudgeRate.siderealDegreesPerSecond, MountNudgeRate.CENTER.degreesPerSecond, 1e-12)
        assertTrue(MountNudgeRate.entries.zipWithNext().all { (a, b) -> a.degreesPerSecond < b.degreesPerSecond })
        assertTrue(abs(MountNudgeRate.SLEW.degreesPerSecond * 3600 * 4) < 65_535, "fits the NexStar rate field")
    }
}
