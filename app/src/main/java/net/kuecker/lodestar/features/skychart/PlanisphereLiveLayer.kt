package net.kuecker.lodestar.features.skychart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GpsFixed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import net.kuecker.lodestar.app.AppModel
import net.kuecker.lodestar.kit.foundation.AstroMath
import net.kuecker.lodestar.kit.foundation.HorizontalCoordinate
import net.kuecker.lodestar.kit.foundation.Vector3
import net.kuecker.lodestar.kit.foundation.roundedHalfAwayFromZero
import net.kuecker.lodestar.kit.formatting.AngleFormatter
import net.kuecker.lodestar.kit.phototools.FrameSize
import net.kuecker.lodestar.kit.pointing.AlignmentModel
import net.kuecker.lodestar.kit.pointing.MountProfile
import net.kuecker.lodestar.kit.pointing.PinholeProjection
import net.kuecker.lodestar.kit.pointing.PointingSolution
import net.kuecker.lodestar.services.MotionService
import net.kuecker.lodestar.shared.StatusChip
import net.kuecker.lodestar.theme.Palette

/** Where the camera points, from the phone's attitude, the mount and the star alignment. */
internal class SkyChartCameraPose(
    /** Azimuth and altitude of the camera's optical axis. */
    val horizontal: HorizontalCoordinate,
    val boresight: Vector3,
    /** The border of the DSLR picture above the horizon, as chart polylines (x, y pairs). */
    val footprint: List<FloatArray>,
) {
    companion object {
        /** The pose for the newest motion sample, or `null` while the sensors deliver none. */
        fun current(
            motion: MotionService,
            mount: MountProfile,
            alignment: AlignmentModel,
            frame: FrameSize,
        ): SkyChartCameraPose? {
            val sample = motion.latestSample() ?: return null
            val solution = PointingSolution.solve(sample.attitude, mount, alignment)
            val outline = PinholeProjection.dslrFrameOutline(solution, frame.horizontal, frame.vertical, 6)
            val first = outline.firstOrNull()
            val footprint = if (first == null) emptyList() else PlanisphereProjection.visiblePolylines(outline + first)
            return SkyChartCameraPose(solution.boresight, solution.boresightVector, footprint)
        }
    }
}

/**
 * Remembers the last well-defined camera heading. Close to the zenith the azimuth of the optical axis is meaningless and
 * jumps about, so a chart that follows the heading keeps its rotation there.
 */
internal class SkyChartHeadingHold {
    private var rotation = 0.0

    /** The chart rotation in radians that puts the camera's azimuth at the top of the chart. */
    fun rotation(pose: SkyChartCameraPose?): Double {
        if (pose != null && pose.horizontal.altitude < FOLLOW_LIMIT) rotation = AstroMath.radians(pose.horizontal.azimuth)
        return rotation
    }

    private companion object {
        /** Above this altitude (degrees) the azimuth is too unreliable to follow. */
        const val FOLLOW_LIMIT = 85.0
    }
}

/** The text of the camera's azimuth and altitude chip, and what accessibility services say instead. */
internal data class CameraReadoutText(val text: String, val description: String) {
    companion object {
        fun of(horizontal: HorizontalCoordinate): CameraReadoutText {
            val azimuth = horizontal.azimuth
            val compass = AngleFormatter.compassPoint(azimuth)
            val altitude = horizontal.altitude
            val degrees = altitude.roundedHalfAwayFromZero().toInt()
            return CameraReadoutText(
                text = "${AngleFormatter.formatAzimuth(azimuth, 0)} ${compass.abbreviation} · " +
                    AngleFormatter.formatSignedDegrees(altitude, 0),
                description = "Camera pointing ${compass.fullName}, altitude $degrees degrees",
            )
        }
    }
}

/**
 * What the sensors currently say about the camera, refreshed by the surface's frame loop. The pose and the heading are
 * Compose state that only the chart's draw blocks read, so a new sample redraws the camera layer without recomposing
 * anything; the readout changes only when the displayed whole degrees do.
 */
@Stable
internal class SkyChartLiveState {
    /** The camera's pose, or `null` while the sensors deliver no sample. */
    var pose by mutableStateOf<SkyChartCameraPose?>(null)
        private set

    /** The chart rotation that puts the camera heading at the top (the last well-defined one near the zenith). */
    var rotation by mutableDoubleStateOf(0.0)
        private set

    /** The camera chip's text, or `null` without a pose. */
    var readout by mutableStateOf<CameraReadoutText?>(null)
        private set

    private val hold = SkyChartHeadingHold()
    private var readoutKey = Int.MIN_VALUE

    /** Polls the newest sample of the motion sensors. */
    fun refresh(app: AppModel) {
        val pose = SkyChartCameraPose.current(app.motion, app.mountProfile, app.alignment, app.lensSetup.fieldOfView)
        this.pose = pose
        rotation = hold.rotation(pose)
        if (pose == null) {
            clearReadout()
            return
        }
        val azimuth = pose.horizontal.azimuth.roundedHalfAwayFromZero().toInt()
        val altitude = pose.horizontal.altitude.roundedHalfAwayFromZero().toInt()
        val key = azimuth * 1000 + altitude + 90
        if (key != readoutKey) {
            readoutKey = key
            readout = CameraReadoutText.of(pose.horizontal)
        }
    }

    /** Forgets the pose: the sensors are not running. */
    fun clear() {
        pose = null
        clearReadout()
    }

    private fun clearReadout() {
        readout = null
        readoutKey = Int.MIN_VALUE
    }
}

/**
 * The camera's crosshair and the footprint of the DSLR picture, drawn over the chart.
 *
 * A camera that points below the horizon is shown as an arrow on the horizon circle, towards its azimuth. Redraws
 * whenever [live] has a new pose or the model's zoom and pan change.
 */
@Composable
internal fun PlanisphereLiveCanvas(
    model: SkyChartModel,
    live: SkyChartLiveState,
    palette: Palette,
    followsHeading: Boolean,
    modifier: Modifier = Modifier,
) {
    val scratch = remember { LiveScratch() }
    Canvas(modifier.fillMaxSize()) {
        val pose = live.pose ?: return@Canvas
        val dp = density
        val layout = PlanisphereLayout(size.width, size.height, dp)
        val viewport = model.viewport(layout, if (followsHeading) live.rotation else 0.0)
        val color = palette.dslrFrame

        scratch.disc.rewind()
        scratch.disc.addOval(Rect(Offset(viewport.centerX, viewport.centerY), viewport.radius))
        clipPath(scratch.disc) {
            scratch.footprint.rewind()
            for (polyline in pose.footprint) {
                for (index in polyline.indices step 2) {
                    val x = viewport.screenX(polyline[index], polyline[index + 1])
                    val y = viewport.screenY(polyline[index], polyline[index + 1])
                    if (index == 0) scratch.footprint.moveTo(x, y) else scratch.footprint.lineTo(x, y)
                }
            }
            drawPath(scratch.footprint, color, style = Stroke(1.5f * dp, join = StrokeJoin.Round))

            if (pose.boresight.z >= 0) {
                val point = viewport.screen(PlanisphereProjection.chartPoint(pose.boresight))
                val radius = 9f * dp
                scratch.crosshair.rewind()
                PlanisphereShapes.circle(scratch.crosshair, point.x, point.y, radius)
                PlanisphereShapes.axisTicks(scratch.crosshair, point.x, point.y, radius - 3f * dp, radius + 7f * dp)
                drawPath(scratch.crosshair, color, style = Stroke(1.5f * dp))
                drawCircle(color, 1.5f * dp, point)
            }
        }
        if (pose.boresight.z < 0) {
            scratch.arrow.rewind()
            viewport.addRimArrow(scratch.arrow, pose.horizontal.azimuth)
            drawPath(scratch.arrow, color)
        }
    }
}

/** The paths [PlanisphereLiveCanvas] reuses for every frame. */
private class LiveScratch {
    val disc = Path()
    val footprint = Path()
    val crosshair = Path()
    val arrow = Path()
}

/** The camera's azimuth and altitude as a status chip. */
@Composable
internal fun SkyChartCameraReadout(readout: CameraReadoutText, modifier: Modifier = Modifier) {
    StatusChip(
        icon = Icons.Outlined.GpsFixed,
        text = readout.text,
        modifier = modifier.clearAndSetSemantics { contentDescription = readout.description },
    )
}
