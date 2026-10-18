package net.kuecker.lodestar.features.aim

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import net.kuecker.lodestar.kit.formatting.AngleFormatter
import net.kuecker.lodestar.kit.foundation.AstroMath
import net.kuecker.lodestar.kit.foundation.Matrix3
import net.kuecker.lodestar.kit.foundation.Vector3
import net.kuecker.lodestar.kit.foundation.roundedHalfAwayFromZero
import net.kuecker.lodestar.kit.pointing.DeviceAttitude
import net.kuecker.lodestar.kit.pointing.MountProfile
import net.kuecker.lodestar.kit.pointing.PinholeProjection
import net.kuecker.lodestar.kit.pointing.PointingSolution
import net.kuecker.lodestar.services.CameraGeometry
import net.kuecker.lodestar.shared.AppFormat
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/** Builds the pinhole projection that lays the sky over the screen. */
object AimProjectionFactory {
    /** The range of the virtual lens, as the field of view along the screen's long side in degrees. */
    val virtualFieldOfViewRange: ClosedFloatingPointRange<Double> = 20.0..100.0
    const val defaultVirtualFieldOfView = 60.0

    /**
     * The projection for a viewport (pixels). With the optics of a running camera the focal length makes the overlay
     * match the aspect-filled picture exactly; without one a virtual lens of [virtualFieldOfView] is used. `null`
     * when the viewport is empty.
     */
    fun projection(viewport: Size, camera: CameraGeometry?, virtualFieldOfView: Double): PinholeProjection? {
        val width = viewport.width.toDouble()
        val height = viewport.height.toDouble()
        if (camera != null) {
            val focalLength = focalLength(viewport, camera)
            if (focalLength != null) return PinholeProjection.create(width, height, focalLength)
        }
        val fieldOfView = AstroMath.clamp(
            virtualFieldOfView, virtualFieldOfViewRange.start, virtualFieldOfViewRange.endInclusive,
        )
        return PinholeProjection.fromLongSideFieldOfView(width, height, fieldOfView)
    }

    /**
     * The focal length, in pixels, of a picture that is scaled to cover [viewport] (aspect fill).
     *
     * The picture grows until both of its sides reach the viewport; the side with the larger ratio then fits
     * exactly and the other one is cropped. The focal length follows from the field of view across the side
     * that fits: `f = scale · pixels / 2 / tan(fov / 2)`.
     */
    fun focalLength(viewport: Size, camera: CameraGeometry): Double? {
        if (camera.imageWidth <= 0 || camera.imageHeight <= 0 || !(viewport.width > 0) || !(viewport.height > 0)) {
            return null
        }
        val imageWidth = camera.imageWidth.toDouble()
        val imageHeight = camera.imageHeight.toDouble()
        val widthScale = viewport.width / imageWidth
        val heightScale = viewport.height / imageHeight
        val fitsWidth = widthScale >= heightScale
        val scale = if (fitsWidth) widthScale else heightScale
        val pixels = if (fitsWidth) imageWidth else imageHeight
        val fieldOfView = if (fitsWidth) camera.horizontalFOV else camera.verticalFOV
        if (!(fieldOfView.isFinite() && fieldOfView > 0 && fieldOfView < 180)) return null
        return scale * pixels / 2 / AstroMath.tanDeg(fieldOfView / 2)
    }

    /**
     * The attitude of a virtual device whose back camera looks along the lens, with the screen axes the guidance uses.
     *
     * The phone's own back camera sees what the lens sees only when the phone is clamped with its camera towards the
     * scene. A phone lying on top of the camera looks at the ground instead, so the sky is drawn as seen from the
     * lens: [PointingSolution] gives the lens direction and the world directions that the phone's screen draws as
     * right and up, which are the axes the arrow and the off-screen indicator use as well. `null` if they do not
     * form a rotation.
     */
    fun lensViewAttitude(pointing: PointingSolution): DeviceAttitude? {
        val worldToScreen = Matrix3(pointing.screenRightVector, pointing.screenUpVector, pointing.boresightVector * -1.0)
        // `worldToDevice` is the matrix times the reference transform, so undo that to get the matrix.
        val matrix = worldToScreen * DeviceAttitude.referenceTransform(0.0).transposed
        return DeviceAttitude.create(matrix, 0.0, 0.0)
    }
}

/**
 * How far the HUD is turned, clockwise in quarter turns, so that it reads upright when the phone is clamped
 * sideways (or upside down) on the camera.
 */
enum class AimHUDRotation(val rawValue: Int) {
    UPRIGHT(0),
    CLOCKWISE(90),
    UPSIDE_DOWN(180),
    COUNTERCLOCKWISE(270);

    val degrees: Double get() = rawValue.toDouble()

    /** `true` when the HUD's width and height trade places. */
    val swapsAxes: Boolean get() = this == CLOCKWISE || this == COUNTERCLOCKWISE

    /** [offset], given as seen by the reader, turned to where it lies on the screen (any unit). */
    fun rotated(offset: Offset): Offset = when (this) {
        UPRIGHT -> offset
        CLOCKWISE -> Offset(-offset.y, offset.x)
        UPSIDE_DOWN -> Offset(-offset.x, -offset.y)
        COUNTERCLOCKWISE -> Offset(offset.y, -offset.x)
    }

    /** [offset] in dp, as [rotated] for an [Offset]. */
    fun rotated(offset: DpOffset): DpOffset {
        val turned = rotated(Offset(offset.x.value, offset.y.value))
        return DpOffset(turned.x.dp, turned.y.dp)
    }

    companion object {
        /**
         * Below this length of gravity's component in the screen plane the phone counts as lying flat and its
         * orientation is undefined.
         */
        private const val FLAT_THRESHOLD = 0.3

        /**
         * The HUD follows the phone only once the phone is turned more than this many degrees from the HUD's current
         * rotation. A quarter-turn boundary lies at 45°, so the margin beyond it keeps the HUD from flipping back and
         * forth.
         */
        private const val HYSTERESIS = 55.0

        /**
         * The rotation for a gravity vector in screen coordinates (x right, y up, unit g).
         *
         * World-up seen on the screen is `(−g.x, −g.y)`; the HUD turns clockwise by that direction's angle from
         * the screen's top. The current rotation is kept while the phone lies (nearly) flat and while it stays
         * within the hysteresis band, so the HUD does not flip back and forth near a boundary. Without a current
         * rotation a flat phone gets the rotation that suits the mount.
         */
        fun updated(current: AimHUDRotation?, gravity: Vector3, mount: MountProfile): AimHUDRotation {
            val inPlane = sqrt(gravity.x * gravity.x + gravity.y * gravity.y)
            if (!(inPlane >= FLAT_THRESHOLD)) return current ?: restingRotation(mount)
            val angle = AstroMath.atan2Deg(-gravity.x, -gravity.y)
            if (current != null && abs(AstroMath.normalizeSignedDegrees(angle - current.degrees)) <= HYSTERESIS) {
                return current
            }
            val quarterTurns = ((((angle / 90).roundedHalfAwayFromZero().toInt()) % 4) + 4) % 4
            return entries.firstOrNull { it.rawValue == quarterTurns * 90 } ?: UPRIGHT
        }

        /**
         * A phone lying on the camera is read from behind it: with the top edge towards the photographer the
         * text is upside down.
         */
        private fun restingRotation(mount: MountProfile): AimHUDRotation =
            if (mount.cameraAxis.y < -0.7) UPSIDE_DOWN else UPRIGHT
    }
}

/** Text for the angles on the Aim screen. */
object AimFormat {
    /** An angle for the readouts: one decimal from 1°, arcminutes or arcseconds below. */
    fun angle(degrees: Double): String {
        if (!degrees.isFinite()) return AppFormat.MISSING
        val magnitude = abs(degrees)
        if (magnitude >= 1) return AngleFormatter.formatDegrees(magnitude, decimals = 1)
        return AppFormat.separation(magnitude)
    }

    /** An angle as spoken by TalkBack: "23 degrees", "1 degree", "under one degree". */
    fun spoken(degrees: Double): String {
        if (!degrees.isFinite()) return "unknown"
        val whole = min(abs(degrees), 360.0).roundedHalfAwayFromZero().toInt()
        return when (whole) {
            0 -> "under one degree"
            1 -> "1 degree"
            else -> "$whole degrees"
        }
    }
}
