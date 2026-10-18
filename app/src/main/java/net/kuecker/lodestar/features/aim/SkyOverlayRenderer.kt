package net.kuecker.lodestar.features.aim

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Density
import net.kuecker.lodestar.kit.foundation.AstroMath
import net.kuecker.lodestar.kit.foundation.Matrix3
import net.kuecker.lodestar.kit.foundation.Vector3
import net.kuecker.lodestar.kit.pointing.DeviceAttitude
import net.kuecker.lodestar.services.CameraGeometry
import net.kuecker.lodestar.shared.ScreenAxes
import net.kuecker.lodestar.theme.Palette
import kotlin.math.atan
import kotlin.math.hypot

/** This colour with its opacity scaled by [factor]. */
internal fun Color.faded(factor: Float): Color = copy(alpha = alpha * factor)

/**
 * What the renderer keeps from frame to frame so that drawing allocates next to nothing: the reusable path and point
 * buffers, the label queue with its measured texts, and the paint of the stars. One per overlay, main thread only;
 * make a new one when the density changes.
 */
internal class SkyOverlayScratch(textMeasurer: TextMeasurer, density: Density) {
    val labels = AimLabelQueue(AimTextCache(textMeasurer, density))
    val path = Path()
    val world = AimWorldPath()
    val polygonClip = AimPolygonClip()

    /** Draws the stars as round dots: one `drawPoints` call per group of equal look. */
    val starPaint = android.graphics.Paint().apply {
        isAntiAlias = true
        style = android.graphics.Paint.Style.STROKE
        strokeCap = android.graphics.Paint.Cap.ROUND
    }

    private var points = FloatArray(2 * INITIAL_POINTS)

    /** A buffer for at least [minimumSize] projected coordinates (x, y pairs); its contents are not preserved. */
    fun points(minimumSize: Int): FloatArray {
        if (points.size < minimumSize) points = FloatArray(maxOf(minimumSize, 2 * points.size))
        return points
    }

    private companion object {
        const val INITIAL_POINTS = 2048
    }
}

/**
 * Paints one frame of the sky overlay: Milky Way, grid, constellations, horizon, deep-sky objects, stars, planets,
 * the DSLR frame, the target and the reticle, followed by their labels.
 *
 * Everything is projected with the phone's own back camera, the heading offset applied and the DSLR's device
 * correction not; only the DSLR frame itself comes from the corrected pointing. (A phone that lies on top of the
 * camera cannot see the sky through its back camera: then the sky is drawn as seen from the lens.) The renderer is
 * made for each frame from the draw scope it paints into (it is a [DrawScope] itself, so the drawing code reads like
 * any other), and keeps nothing between frames but the [scratch] buffers.
 *
 * Geometry is worked out in pixels, but sizes, offsets and margins are written in dp (the iOS points) and scaled by
 * the density where they are used; `pointsPerDegree` counts dp.
 */
internal class SkyOverlayRenderer private constructor(
    scope: DrawScope,
    val scratch: SkyOverlayScratch,
    val frame: AimFrame,
    val content: AimSkyContent?,
    val configuration: AimConfiguration,
    val palette: Palette,
    /** Seconds on a continuous clock; drives the pulse of the target ring. */
    val time: Double,
    val hudRotation: AimHUDRotation,
    val projector: AimProjector,
    val frustum: AimFrustum,
    /** The optical axis of the eye the sky is seen from, in world coordinates. */
    val boresight: Vector3,
    /** Rotation from world directions to the screen frame: x right, y up, z backwards (the eye looks along −z). */
    val worldToScreen: Matrix3,
    /** Screen dp per degree at the centre of the view. */
    val pointsPerDegree: Double,
    /** Field of view along the screen's long side, degrees. */
    val fieldOfView: Double,
    /** Angle in degrees from the centre of the view to just beyond a corner. */
    val viewRadius: Double,
) : DrawScope by scope {

    private val labels get() = scratch.labels

    /** Paints the layers back to front, then the labels they asked for. */
    fun draw() {
        labels.reset()
        drawMilkyWay()
        drawGrid()
        drawConstellations()
        drawHorizon()
        drawDeepSky()
        drawStars()
        drawBodies()
        drawDSLRFrame()
        drawTarget()
        drawReticle()
        labels.draw(this, size, hudRotation, density)
    }

    // Helpers

    /** `true` when the viewport position `(x, y)` is on the screen, grown by [margin] dp on every side. */
    fun isVisible(x: Float, y: Float, margin: Float = 4f): Boolean {
        val grow = margin * density
        return x >= -grow && x < size.width + grow && y >= -grow && y < size.height + grow
    }

    /**
     * Queues [text] next to the viewport position `(x, y)`; the offset (dp) is given as the reader sees it and
     * follows the HUD's rotation.
     */
    fun addLabel(
        text: String, x: Float, y: Float, style: AimLabelStyle, priority: Int, offsetX: Float = 0f, offsetY: Float = 0f,
    ) {
        val shift = hudRotation.rotated(Offset(offsetX, offsetY))
        labels.add(text, x + shift.x * density, y + shift.y * density, style, priority)
    }

    /** Strokes everything in [world] with a line [width] dp wide. */
    fun strokeWorld(
        world: AimWorldPath, color: Color, width: Float, cap: StrokeCap = StrokeCap.Butt, join: StrokeJoin = StrokeJoin.Miter,
    ) {
        if (world.isEmpty) return
        world.toPath(scratch.path, projector)
        drawPath(scratch.path, color, style = Stroke(width * density, cap = cap, join = join))
    }

    companion object {
        /** The altitude/azimuth grid appears when the long side of the screen shows no more than this many degrees. */
        const val GRID_MAXIMUM_FIELD_OF_VIEW = 50.0

        /** Margin in dp by which strokes may run past the screen edge before they are cut. */
        const val CLIP_MARGIN = 12.0

        /**
         * The renderer for one frame painted into [scope], or `null` when the screen is empty.
         *
         * @param camera The optics of the running camera picture, or `null` to use the virtual lens.
         * @param screenAxes How the screen sits in the device frame (the display rotation).
         * @param time Seconds on a continuous clock.
         */
        fun create(
            scope: DrawScope,
            scratch: SkyOverlayScratch,
            frame: AimFrame,
            content: AimSkyContent?,
            configuration: AimConfiguration,
            palette: Palette,
            camera: CameraGeometry?,
            virtualFieldOfView: Double,
            hudRotation: AimHUDRotation,
            screenAxes: ScreenAxes,
            time: Double,
        ): SkyOverlayRenderer? {
            val projection = AimProjectionFactory.projection(scope.size, camera, virtualFieldOfView) ?: return null
            // The phone's camera is the eye while the picture runs (the overlay has to match it) and whenever it looks
            // along the lens; otherwise the lens is.
            val eye: DeviceAttitude
            val headingOffset: Double
            if (camera != null || configuration.phoneCameraFollowsLens) {
                eye = frame.sample.attitude
                headingOffset = configuration.headingOffset
            } else {
                eye = AimProjectionFactory.lensViewAttitude(frame.pointing) ?: return null
                headingOffset = 0.0
            }
            val attitude = eye.withHeadingOffset(headingOffset)
            val deviceToScreen = Matrix3(screenAxes.right, screenAxes.up, screenAxes.right.cross(screenAxes.up))
            val worldToScreen = deviceToScreen * attitude.worldToDevice
            val density = scope.density
            return SkyOverlayRenderer(
                scope = scope,
                scratch = scratch,
                frame = frame,
                content = content,
                configuration = configuration,
                palette = palette,
                time = time,
                hudRotation = hudRotation,
                projector = AimProjector(projection, worldToScreen),
                frustum = AimFrustum(projection, worldToScreen.transposed, CLIP_MARGIN * density),
                boresight = attitude.deviceToWorld * DeviceAttitude.backCameraAxis,
                worldToScreen = worldToScreen,
                pointsPerDegree = projection.focalLength / density * AstroMath.radiansPerDegree,
                fieldOfView = projection.longSideFieldOfView,
                viewRadius = AstroMath.degrees(
                    atan(hypot(projection.viewportWidth, projection.viewportHeight) / 2 / projection.focalLength),
                ) + 3,
            )
        }
    }
}
