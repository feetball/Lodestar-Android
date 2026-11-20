package net.kuecker.lodestar.kit.phototools

import net.kuecker.lodestar.kit.foundation.AstroMath
import net.kuecker.lodestar.kit.foundation.Matrix3
import net.kuecker.lodestar.kit.foundation.Vector3
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// MARK: - Alt-azimuth field rotation, polar-alignment drift and apparent size

class TrackedExposureTests {

    private fun lens(
        sensor: SensorFormat = SensorFormat.fullFrame,
        focalLength: Double = 200.0,
        aperture: Double = 2.8,
        pitch: Double? = 4.0,
    ): LensSetup =
        LensSetup(sensor = sensor, focalLengthMillimeters = focalLength, aperture = aperture, pixelPitchMicrometers = pitch)

    // MARK: Field rotation rate

    @Test
    fun testFieldRotationRateOnTheMeridian() {
        // 15.041 * cos(45) * cos(0) / cos(45) = 15.041, due north and due south alike.
        assertEquals(15.041, TrackedExposure.fieldRotationRate(latitude = 45.0, azimuth = 0.0, altitude = 45.0), 1e-9)
        assertEquals(15.041, TrackedExposure.fieldRotationRate(latitude = 45.0, azimuth = 180.0, altitude = 45.0), 1e-9)
    }

    @Test
    fun testFieldRotationRateIsZeroDueEastAndWest() {
        assertEquals(0.0, TrackedExposure.fieldRotationRate(latitude = 45.0, azimuth = 90.0, altitude = 45.0), 1e-9)
        assertEquals(0.0, TrackedExposure.fieldRotationRate(latitude = 45.0, azimuth = 270.0, altitude = 20.0), 1e-9)
    }

    @Test
    fun testFieldRotationRateIsAMagnitudeSoTheAzimuthConventionDoesNotMatter() {
        val rate = TrackedExposure.fieldRotationRate(latitude = 40.0, azimuth = 30.0, altitude = 25.0)
        assertEquals(15.041 * cos(40 * AstroMath.radiansPerDegree) * cos(30 * AstroMath.radiansPerDegree) / cos(25 * AstroMath.radiansPerDegree), rate, 1e-9)
        for (azimuth in listOf(150.0, 210.0, 330.0, -30.0, 390.0)) {
            assertEquals(rate, TrackedExposure.fieldRotationRate(latitude = 40.0, azimuth = azimuth, altitude = 25.0), 1e-9)
        }
        // The southern hemisphere rotates at the same rate as its mirror image in the north.
        assertEquals(rate, TrackedExposure.fieldRotationRate(latitude = -40.0, azimuth = 30.0, altitude = 25.0), 1e-9)
    }

    @Test
    fun testFieldRotationRateIsTheRateOfChangeOfTheParallacticAngle() {
        // Independent check of the formula: the field of an alt-azimuth mount turns as the parallactic angle q,
        // tan q = sin H / (tan phi cos delta - sin delta cos H) (Meeus, Astronomical Algorithms, chapter 14), and the
        // hour angle H grows by 15.041 arcseconds per second. Differentiate q numerically and compare.
        val cases = listOf(
            Triple(45.0, 20.0, 30.0),
            Triple(52.0, -10.0, -60.0),
            Triple(-33.9, -45.0, 10.0),
            Triple(10.0, 70.0, 100.0),
            Triple(60.0, 5.0, -20.0),
        )
        val step = 1e-5
        for ((latitude, declination, hourAngle) in cases) {
            val before = parallacticAngle(latitude, declination, hourAngle - step * 180 / Math.PI)
            val after = parallacticAngle(latitude, declination, hourAngle + step * 180 / Math.PI)
            val difference = atan2(sin(after - before), cos(after - before))
            val expected = 15.041 * abs(difference / (2 * step))
            val (azimuth, altitude) = horizontal(latitude, declination, hourAngle)
            val rate = TrackedExposure.fieldRotationRate(latitude = latitude, azimuth = azimuth, altitude = altitude)
            assertEquals(expected, rate, expected * 1e-6, "lat $latitude dec $declination H $hourAngle")
        }
    }

    @Test
    fun testFieldRotationRateGrowsTowardsTheZenithAndIsGuarded() {
        val at45 = TrackedExposure.fieldRotationRate(latitude = 45.0, azimuth = 0.0, altitude = 45.0)
        val at80 = TrackedExposure.fieldRotationRate(latitude = 45.0, azimuth = 0.0, altitude = 80.0)
        assertEquals(at45 * cos(45 * AstroMath.radiansPerDegree) / cos(80 * AstroMath.radiansPerDegree), at80, 1e-9)
        val guarded = TrackedExposure.fieldRotationRate(latitude = 45.0, azimuth = 0.0, altitude = 89.9)
        assertEquals(15.041 * cos(45 * AstroMath.radiansPerDegree) / cos(89.9 * AstroMath.radiansPerDegree), guarded, 1e-6)
        for (altitude in listOf(89.95, 90.0, 100.0, 1e9)) {
            assertEquals(guarded, TrackedExposure.fieldRotationRate(latitude = 45.0, azimuth = 0.0, altitude = altitude), 1e-6)
        }
        assertTrue(guarded.isFinite() && guarded < 10_000)
        // Below the horizon the formula is the mirror image.
        assertEquals(at80, TrackedExposure.fieldRotationRate(latitude = 45.0, azimuth = 0.0, altitude = -80.0), 1e-9)
    }

    @Test
    fun testFieldRotationVanishesAtThePoles() {
        assertEquals(0.0, TrackedExposure.fieldRotationRate(latitude = 90.0, azimuth = 0.0, altitude = 45.0), 1e-9)
        assertEquals(0.0, TrackedExposure.fieldRotationRate(latitude = -90.0, azimuth = 0.0, altitude = 45.0), 1e-9)
        assertEquals(0.0, TrackedExposure.fieldRotationRate(latitude = 120.0, azimuth = 0.0, altitude = 45.0), 1e-9)
    }

    @Test
    fun testFieldRotationRateOfValuesThatAreNotNumbersIsZero() {
        assertEquals(0.0, TrackedExposure.fieldRotationRate(latitude = Double.NaN, azimuth = 0.0, altitude = 45.0))
        assertEquals(0.0, TrackedExposure.fieldRotationRate(latitude = 45.0, azimuth = Double.POSITIVE_INFINITY, altitude = 45.0))
        assertEquals(0.0, TrackedExposure.fieldRotationRate(latitude = 45.0, azimuth = 0.0, altitude = Double.NaN))
    }

    // MARK: Field rotation limit

    @Test
    fun testFieldRotationLimitOnAFullFrameCamera() {
        // The corner of a 36 x 24 mm frame is 21.633 mm from the centre: 5408.3 pixels of 4 micrometres.
        // 1.5 px * 206264.806 / (5408.3 px * 15.041 arcsec/s) = 3.803 s.
        val seconds = TrackedExposure.maxExposureForFieldRotation(
            setup = lens(), latitude = 45.0, azimuth = 0.0, altitude = 45.0,
        )
        assertEquals(3.8029, assertNotNull(seconds), 1e-3)
        val halfDiagonalPixels = SensorFormat.fullFrame.diagonalMillimeters * 1000 / 2 / 4.0
        assertEquals(1.5 * AstroMath.arcsecondsPerRadian / (halfDiagonalPixels * 15.041), seconds, 1e-9)
    }

    @Test
    fun testFieldRotationLimitScalesWithToleranceSensorAndPitchButNotFocalLength() {
        val base = assertNotNull(
            TrackedExposure.maxExposureForFieldRotation(setup = lens(), latitude = 45.0, azimuth = 0.0, altitude = 45.0),
        )
        val tolerant = assertNotNull(
            TrackedExposure.maxExposureForFieldRotation(
                setup = lens(), tolerancePixels = 3.0, latitude = 45.0, azimuth = 0.0, altitude = 45.0,
            ),
        )
        assertEquals(2 * base, tolerant, 1e-9)
        val coarsePixels = assertNotNull(
            TrackedExposure.maxExposureForFieldRotation(
                setup = lens(pitch = 8.0), latitude = 45.0, azimuth = 0.0, altitude = 45.0,
            ),
        )
        assertEquals(2 * base, coarsePixels, 1e-9)
        val wideLens = assertNotNull(
            TrackedExposure.maxExposureForFieldRotation(
                setup = lens(focalLength = 24.0), latitude = 45.0, azimuth = 0.0, altitude = 45.0,
            ),
        )
        assertEquals(base, wideLens, 1e-9)
        val smallSensor = assertNotNull(
            TrackedExposure.maxExposureForFieldRotation(
                setup = lens(sensor = SensorFormat.microFourThirds), latitude = 45.0, azimuth = 0.0, altitude = 45.0,
            ),
        )
        assertEquals(
            base * SensorFormat.fullFrame.diagonalMillimeters / SensorFormat.microFourThirds.diagonalMillimeters,
            smallSensor,
            1e-9,
        )
    }

    @Test
    fun testFieldRotationLimitShrinksTowardsTheZenith() {
        val low = assertNotNull(
            TrackedExposure.maxExposureForFieldRotation(setup = lens(), latitude = 45.0, azimuth = 0.0, altitude = 30.0),
        )
        val high = assertNotNull(
            TrackedExposure.maxExposureForFieldRotation(setup = lens(), latitude = 45.0, azimuth = 0.0, altitude = 75.0),
        )
        assertTrue(high < low / 2)
    }

    @Test
    fun testFieldRotationLimitAssumesTheDefaultPixelPitchWhenThereIsNone() {
        val unknown = TrackedExposure.maxExposureForFieldRotation(
            setup = lens(pitch = null), latitude = 45.0, azimuth = 0.0, altitude = 45.0,
        )
        val known = TrackedExposure.maxExposureForFieldRotation(
            setup = lens(pitch = ExposureCalculator.defaultPixelPitchMicrometers),
            latitude = 45.0, azimuth = 0.0, altitude = 45.0,
        )
        assertEquals(assertNotNull(known), assertNotNull(unknown), 1e-12)
    }

    @Test
    fun testFieldRotationLimitIsNilWithoutRotation() {
        assertNull(TrackedExposure.maxExposureForFieldRotation(setup = lens(), latitude = 45.0, azimuth = 90.0, altitude = 45.0))
        assertNull(TrackedExposure.maxExposureForFieldRotation(setup = lens(), latitude = 90.0, azimuth = 0.0, altitude = 45.0))
        assertNull(TrackedExposure.maxExposureForFieldRotation(setup = lens(), latitude = Double.NaN, azimuth = 0.0, altitude = 45.0))
    }

    @Test
    fun testToleranceIsLimitedToItsRange() {
        val base = assertNotNull(
            TrackedExposure.maxExposureForFieldRotation(setup = lens(), latitude = 45.0, azimuth = 0.0, altitude = 45.0),
        )
        val notANumber = assertNotNull(
            TrackedExposure.maxExposureForFieldRotation(
                setup = lens(), tolerancePixels = Double.NaN, latitude = 45.0, azimuth = 0.0, altitude = 45.0,
            ),
        )
        assertEquals(base, notANumber, 1e-12)
        val huge = assertNotNull(
            TrackedExposure.maxExposureForFieldRotation(
                setup = lens(), tolerancePixels = 1000.0, latitude = 45.0, azimuth = 0.0, altitude = 45.0,
            ),
        )
        assertEquals(base * 20.0 / 1.5, huge, 1e-9)
        val negative = assertNotNull(
            TrackedExposure.maxExposureForFieldRotation(
                setup = lens(), tolerancePixels = -2.0, latitude = 45.0, azimuth = 0.0, altitude = 45.0,
            ),
        )
        assertEquals(base * 0.1 / 1.5, negative, 1e-9)
    }

    // MARK: Polar-alignment drift

    @Test
    fun testDriftRateOfOneDegreeOfPolarError() {
        assertEquals(0.2625, TrackedExposure.driftRate(polarErrorDegrees = 1.0), 1e-4)
        assertEquals(15.041 * sin(1.0 * AstroMath.radiansPerDegree), TrackedExposure.driftRate(polarErrorDegrees = 1.0), 1e-12)
    }

    @Test
    fun testDriftRateIsTheLargestDisplacementOfTwoRotationsAboutAxesApart() {
        // Independent check of the derivation: rotate the sky about the true pole by the sidereal angle of one second
        // and take it back about an axis one degree away; the largest displacement of any star is the drift rate.
        val error = 1.0 * AstroMath.radiansPerDegree
        val angle = 15.041 / AstroMath.arcsecondsPerRadian
        val pole = Vector3(0.0, 0.0, 1.0)
        val axis = Vector3(sin(error), 0.0, cos(error))
        val net = Matrix3.rotation(axis, -angle) * Matrix3.rotation(pole, angle)
        var largest = 0.0
        for (latitude in -88..90 step 2) {
            for (longitude in 0 until 360 step 2) {
                val star = Vector3(
                    cos(latitude * AstroMath.radiansPerDegree) * cos(longitude * AstroMath.radiansPerDegree),
                    cos(latitude * AstroMath.radiansPerDegree) * sin(longitude * AstroMath.radiansPerDegree),
                    sin(latitude * AstroMath.radiansPerDegree),
                )
                largest = max(largest, (net * star - star).length)
            }
        }
        val drift = TrackedExposure.driftRate(polarErrorDegrees = 1.0)
        assertTrue(largest * AstroMath.arcsecondsPerRadian <= drift * 1.0001)
        assertEquals(drift, largest * AstroMath.arcsecondsPerRadian, drift * 1e-3)
    }

    @Test
    fun testDriftRateLimitsItsInput() {
        assertEquals(TrackedExposure.driftRate(polarErrorDegrees = 1.0), TrackedExposure.driftRate(polarErrorDegrees = Double.NaN), 1e-12)
        assertEquals(TrackedExposure.driftRate(polarErrorDegrees = 0.01), TrackedExposure.driftRate(polarErrorDegrees = 0.0), 1e-12)
        assertEquals(TrackedExposure.driftRate(polarErrorDegrees = 2.0), TrackedExposure.driftRate(polarErrorDegrees = -2.0), 1e-12)
        assertEquals(15.041, TrackedExposure.driftRate(polarErrorDegrees = 1000.0), 1e-9)
    }

    @Test
    fun testPolarErrorLimitForTwoHundredMillimetres() {
        // Image scale 206.265 * 4 / 200 = 4.1253 arcsec/px; 1.5 px = 6.188 arcsec; at 0.2625 arcsec/s that is 23.57 s.
        val seconds = TrackedExposure.maxExposureForPolarError(setup = lens(), polarErrorDegrees = 1.0)
        assertEquals(23.57, seconds, 0.01)
        val scale = assertNotNull(lens().imageScaleArcsecondsPerPixel)
        assertEquals(1.5 * scale / (15.041 * sin(1.0 * AstroMath.radiansPerDegree)), seconds, 1e-9)
    }

    @Test
    fun testPolarErrorLimitScalesWithTheLensTheToleranceAndTheError() {
        val base = TrackedExposure.maxExposureForPolarError(setup = lens(), polarErrorDegrees = 1.0)
        assertEquals(base / 2, TrackedExposure.maxExposureForPolarError(setup = lens(focalLength = 400.0), polarErrorDegrees = 1.0), 1e-9)
        assertEquals(
            2 * base,
            TrackedExposure.maxExposureForPolarError(setup = lens(), tolerancePixels = 3.0, polarErrorDegrees = 1.0),
            1e-9,
        )
        assertEquals(
            base * sin(1.0 * AstroMath.radiansPerDegree) / sin(0.25 * AstroMath.radiansPerDegree),
            TrackedExposure.maxExposureForPolarError(setup = lens(), polarErrorDegrees = 0.25),
            1e-9,
        )
        val unknownPitch = TrackedExposure.maxExposureForPolarError(setup = lens(pitch = null), polarErrorDegrees = 1.0)
        assertEquals(base, unknownPitch, 1e-9)
    }

    // MARK: Apparent size in pixels

    @Test
    fun testPixelSizeOfTheMoonAt200Millimetres() {
        // 0.52 degrees = 1872 arcsec; the image scale is 4.1253 arcsec/px: the Moon spans 453.8 px.
        val pixels = assertNotNull(TrackedExposure.pixelSize(angularDiameterDegrees = 0.52, setup = lens()))
        assertEquals(453.8, pixels, 0.05)
        val scale = assertNotNull(lens().imageScaleArcsecondsPerPixel)
        assertEquals(0.52 * 3600 / scale, pixels, 1e-9)
    }

    @Test
    fun testPixelSizeOfAPlanet() {
        // Jupiter near opposition, 46 arcsec across, about 11 px at 200 mm on 4 micrometre pixels.
        val pixels = assertNotNull(TrackedExposure.pixelSize(angularDiameterDegrees = 46.0 / 3600, setup = lens()))
        assertEquals(11.15, pixels, 0.01)
        val longer = assertNotNull(TrackedExposure.pixelSize(angularDiameterDegrees = 46.0 / 3600, setup = lens(focalLength = 400.0)))
        assertEquals(2 * pixels, longer, 1e-9)
    }

    @Test
    fun testPixelSizeWithoutAPixelPitch() {
        val withDefault = TrackedExposure.pixelSize(angularDiameterDegrees = 0.52, setup = lens(pitch = null))
        assertEquals(assertNotNull(TrackedExposure.pixelSize(angularDiameterDegrees = 0.52, setup = lens())), assertNotNull(withDefault), 1e-9)
        assertNull(TrackedExposure.pixelSize(angularDiameterDegrees = 0.52, setup = lens(pitch = null), assumingDefaultPixelPitch = false))
        assertNotNull(TrackedExposure.pixelSize(angularDiameterDegrees = 0.52, setup = lens(), assumingDefaultPixelPitch = false))
    }

    @Test
    fun testPixelSizeOfAnInvalidDiameterIsNil() {
        for (diameter in listOf(0.0, -0.5, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertNull(TrackedExposure.pixelSize(angularDiameterDegrees = diameter, setup = lens()), "$diameter")
        }
    }

    // MARK: Helpers

    /** The parallactic angle in radians (Meeus, Astronomical Algorithms, equation 14.1); angles in degrees. */
    private fun parallacticAngle(latitude: Double, declination: Double, hourAngle: Double): Double {
        val phi = latitude * AstroMath.radiansPerDegree
        val delta = declination * AstroMath.radiansPerDegree
        val hour = hourAngle * AstroMath.radiansPerDegree
        return atan2(sin(hour) * cos(phi), sin(phi) * cos(delta) - cos(phi) * sin(delta) * cos(hour))
    }

    /** The azimuth from north through east and the altitude, in degrees, of a star at the hour angle (west positive). */
    private fun horizontal(latitude: Double, declination: Double, hourAngle: Double): Pair<Double, Double> {
        val phi = latitude * AstroMath.radiansPerDegree
        val delta = declination * AstroMath.radiansPerDegree
        val hour = hourAngle * AstroMath.radiansPerDegree
        val east = -cos(delta) * sin(hour)
        val north = sin(delta) * cos(phi) - cos(delta) * cos(hour) * sin(phi)
        val up = sin(delta) * sin(phi) + cos(delta) * cos(hour) * cos(phi)
        return Pair(atan2(east, north) / AstroMath.radiansPerDegree, asin(up) / AstroMath.radiansPerDegree)
    }
}

// MARK: - Moon exposure

class MoonExposureTests {

    private fun suggestion(
        fraction: Double = 1.0,
        altitude: Double = 90.0,
        aperture: Double = 11.0,
        iso: Double = 100.0,
    ): MoonExposureSuggestion =
        MoonExposure.suggest(illuminatedFraction = fraction, altitude = altitude, aperture = aperture, iso = iso)

    @Test
    fun testLooney11FullMoon() {
        // f/11 at 1/ISO: 1/100 s at ISO 100.
        val full = suggestion()
        assertEquals(0.01, full.seconds, 1e-9)
        assertEquals(1.0, full.airmass, 1e-12)
        assertEquals("Full Moon high in the sky: the Looney 11 rule as it stands, f/11 at 1/ISO.", full.note)
        assertEquals(1.0 / 400, suggestion(iso = 400.0).seconds, 1e-9)
    }

    @Test
    fun testHalfMoonAtF8() {
        // One stop more light than the full Moon: f/8 at 1/ISO.
        assertEquals(0.01, suggestion(fraction = 0.5, aperture = 8.0).seconds, 1e-9)
        assertEquals(0.01, suggestion(fraction = 0.2, aperture = 5.6).seconds, 1e-9)
    }

    @Test
    fun testExposureValuesAtISO100() {
        assertEquals(13.6, suggestion(fraction = 1.0).exposureValue, 0.05)
        assertEquals(12.6, suggestion(fraction = 0.5).exposureValue, 0.05)
        assertEquals(11.6, suggestion(fraction = 0.2).exposureValue, 0.05)
        assertEquals(log2(11.0 * 11.0 * 100), suggestion(fraction = 1.0).exposureValue, 1e-12)
    }

    @Test
    fun testShutterScalesWithISOAndAperture() {
        val base = suggestion(fraction = 0.5, aperture = 8.0)
        assertEquals(base.seconds / 2, suggestion(fraction = 0.5, aperture = 8.0, iso = 200.0).seconds, 1e-12)
        assertEquals(base.seconds * 4, suggestion(fraction = 0.5, aperture = 16.0).seconds, 1e-12)
        assertEquals(base.seconds / 8, suggestion(fraction = 0.5, aperture = 8.0, iso = 800.0).seconds, 1e-12)
    }

    @Test
    fun testThePhaseAdjustmentIsALineBetweenTheAnchorsAndHeldOutside() {
        val crescent = suggestion(fraction = 0.2).exposureValue
        val half = suggestion(fraction = 0.5).exposureValue
        val full = suggestion(fraction = 1.0).exposureValue
        assertEquals((crescent + half) / 2, suggestion(fraction = 0.35).exposureValue, 1e-12)
        assertEquals((half + full) / 2, suggestion(fraction = 0.75).exposureValue, 1e-12)
        assertEquals(crescent, suggestion(fraction = 0.0).exposureValue, 1e-12)
        assertEquals(crescent, suggestion(fraction = 0.05).exposureValue, 1e-12)
        assertEquals(full, suggestion(fraction = 1.7).exposureValue, 1e-12)
        assertTrue(suggestion(fraction = 0.3).seconds > suggestion(fraction = 0.6).seconds)
    }

    @Test
    fun testALowMoonNeedsMoreLight() {
        // At 30 degrees the airmass is 2: 0.3 stops lost.
        val low = suggestion(altitude = 30.0)
        assertEquals(2.0, low.airmass, 1e-9)
        assertEquals(0.01 * 2.0.pow(0.3), low.seconds, 1e-9)
        assertEquals(
            "Low Moon (2.0 airmasses): 0.3 stops more light for the extinction.",
            low.note,
        )
        assertTrue(suggestion(altitude = 15.0).seconds > low.seconds)
    }

    @Test
    fun testTheAirmassIsCapped() {
        for (altitude in listOf(11.0, 5.0, 0.5)) {
            assertEquals(5.0, suggestion(altitude = altitude).airmass, 1e-12, "$altitude")
        }
        val atTheHorizon = suggestion(altitude = 0.0)
        assertEquals(5.0, atTheHorizon.airmass, 1e-12)
        assertEquals(5.0, suggestion(altitude = -20.0).airmass, 1e-12)
        assertEquals(0.01 * 2.0.pow(1.2), atTheHorizon.seconds, 1e-9)
        assertEquals("The Moon is at or below the horizon: the extinction is the largest the rule counts.", atTheHorizon.note)
        assertEquals(1.0, suggestion(altitude = 90.0).airmass, 1e-12)
        assertEquals(1.0, suggestion(altitude = 400.0).airmass, 1e-12)
    }

    @Test
    fun testNotesNameThePhaseAndTheExtinction() {
        assertEquals("A 50% lit Moon needs about 0.9 stops more light than a full Moon.", suggestion(fraction = 0.5).note)
        assertEquals(
            "A 20% lit Moon needs about 1.9 stops more light than a full Moon. " +
                "Low Moon (2.0 airmasses): 0.3 stops more light for the extinction.",
            suggestion(fraction = 0.2, altitude = 30.0).note,
        )
    }

    @Test
    fun testValuesThatAreNotNumbersTakeTheirFallbacks() {
        val reference = suggestion()
        assertEquals(reference.seconds, suggestion(fraction = Double.NaN).seconds, 1e-12)
        assertEquals(reference.seconds, suggestion(altitude = Double.NaN).seconds, 1e-12)
        assertEquals(1.0, suggestion(altitude = Double.NaN).airmass, 1e-12)
        assertEquals(reference.seconds, suggestion(iso = Double.NaN).seconds, 1e-12)
        assertEquals(
            suggestion(aperture = LensSetup.defaultAperture).seconds,
            suggestion(aperture = Double.NaN).seconds,
            1e-12,
        )
    }

    @Test
    fun testApertureAndISOAreLimitedToTheirRanges() {
        assertEquals(suggestion(aperture = 32.0).seconds, suggestion(aperture = 500.0).seconds, 1e-12)
        assertEquals(suggestion(aperture = 0.95).seconds, suggestion(aperture = 0.0).seconds, 1e-12)
        assertEquals(suggestion(iso = 102_400.0).seconds, suggestion(iso = 1e9).seconds, 1e-12)
        assertEquals(suggestion(iso = 50.0).seconds, suggestion(iso = -100.0).seconds, 1e-12)
    }
}
