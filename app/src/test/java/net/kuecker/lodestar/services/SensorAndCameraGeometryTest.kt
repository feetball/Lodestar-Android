package net.kuecker.lodestar.services

import android.view.Surface
import net.kuecker.lodestar.kit.foundation.Vector3
import net.kuecker.lodestar.shared.ScreenAxes
import java.time.Instant
import kotlin.math.atan
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SensorAndCameraGeometryTest {
    private fun degrees(radians: Double) = Math.toDegrees(radians)

    @Test
    fun fullSensorFrameKeepsTheFullFieldAndPortraitSwapsAxes() {
        // 6.4 × 4.8 mm active area, 4.8 mm lens, 4:3 stream rotated 90° for a portrait display.
        val geometry = assertNotNull(CameraService.geometry(4.8, 6.4, 4.8, 1440, 1080, 90))
        assertEquals(degrees(2 * atan(2.4 / 4.8)), geometry.horizontalFOV, 1e-9)
        assertEquals(degrees(2 * atan(3.2 / 4.8)), geometry.verticalFOV, 1e-9)
        assertEquals(1080, geometry.imageWidth)
        assertEquals(1440, geometry.imageHeight)
    }

    @Test
    fun wideStreamCropsTheSensorHeight() {
        val geometry = assertNotNull(CameraService.geometry(4.8, 6.4, 4.8, 1920, 1080, 0))
        assertEquals(degrees(2 * atan(3.2 / 4.8)), geometry.horizontalFOV, 1e-9)
        val halfHeight = 6.4 / (1920.0 / 1080.0) / 2
        assertEquals(degrees(2 * atan(halfHeight / 4.8)), geometry.verticalFOV, 1e-9)
        // The two fields of view describe one pinhole: their tangents keep the frame's aspect ratio.
        val ratio = tan(Math.toRadians(geometry.horizontalFOV / 2)) / tan(Math.toRadians(geometry.verticalFOV / 2))
        assertEquals(1920.0 / 1080.0, ratio, 1e-9)
    }

    @Test
    fun unusableInputGivesNoGeometry() {
        assertNull(CameraService.geometry(0.0, 6.4, 4.8, 1920, 1080, 0))
        assertNull(CameraService.geometry(4.8, 6.4, 4.8, 0, 1080, 0))
    }

    @Test
    fun screenAxesAreRightHandedForEveryRotation() {
        for (rotation in listOf(Surface.ROTATION_0, Surface.ROTATION_90, Surface.ROTATION_180, Surface.ROTATION_270)) {
            val axes = ScreenAxes.forRotation(rotation)
            val outOfScreen = axes.right.cross(axes.up)
            assertTrue((outOfScreen - Vector3(0.0, 0.0, 1.0)).length < 1e-12, "rotation $rotation")
        }
        // Turning the phone to the left (ROTATION_90) puts its right edge at the top of the picture.
        assertEquals(Vector3(1.0, 0.0, 0.0), ScreenAxes.forRotation(Surface.ROTATION_90).up)
    }

    @Test
    fun stableLocationIgnoresJitterButFollowsRealMoves() {
        val filter = StableLocationFilter()
        val t = Instant.parse("2026-07-17T05:30:00Z")
        fun fix(latitude: Double, accuracy: Double) = LocationFix(latitude, -112.0, 2000.0, accuracy, 10.0, t)
        val first = assertNotNull(filter.accept(fix(36.0, 1500.0)))
        assertEquals(2000.0, first.elevation)
        assertNull(filter.accept(fix(36.0005, 1400.0)), "55 m of jitter")
        assertNotNull(filter.accept(fix(36.0, 20.0)), "accuracy improved from 1.5 km to 20 m")
        assertNotNull(filter.accept(fix(36.01, 20.0)), "moved 1.1 km")
    }
}
