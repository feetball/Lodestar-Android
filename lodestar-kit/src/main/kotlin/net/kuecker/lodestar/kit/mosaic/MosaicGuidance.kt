package net.kuecker.lodestar.kit.mosaic

import net.kuecker.lodestar.kit.foundation.AstroMath
import net.kuecker.lodestar.kit.foundation.HorizontalCoordinate
import net.kuecker.lodestar.kit.foundation.SkyFrame
import net.kuecker.lodestar.kit.foundation.Vector3
import kotlin.math.min

/**
 * Where to aim the camera for one panel at one moment, and how to turn it.
 *
 * Created by [MosaicGuidance] only.
 *
 * @property panelIndex The index of the panel in [MosaicPlan.panels].
 * @property direction The azimuth and altitude of the panel's centre. Pass it as the target of the aiming
 * guidance. Refracted when the pointing was asked for with refraction.
 * @property corners The azimuth and altitude of the four corners of the frame: top left, top right, bottom
 * right, bottom left. Project them into the camera view to draw the panel; the edges between them are
 * straight lines in a rectilinear view.
 * @property roll The turn of the camera about its optical axis, in degrees (−180, 180], that keeps the panel's
 * edges aligned with the plan: how far the top of the picture must lean clockwise, as seen looking through
 * the camera, from straight up so that it points to the plan's pole. For an equatorial plan with position
 * angle 0 this is the parallactic angle, positive in the west of the meridian: the top of the picture then
 * points to the celestial north pole and the rows follow the declination circle. For a horizontal plan it is 0:
 * the camera stays level. It describes the picture, so it is the same for landscape and portrait.
 * @property isAboveHorizon `true` when the centre of the panel is above the horizon.
 * @property lowestAltitude The lowest altitude among the centre and the corners in degrees; below 0 the panel
 * reaches below the horizon.
 */
@ConsistentCopyVisibility
data class MosaicPanelPointing internal constructor(
    val panelIndex: Int,
    val direction: HorizontalCoordinate,
    val corners: List<HorizontalCoordinate>,
    val roll: Double,
    val isAboveHorizon: Boolean,
    val lowestAltitude: Double,
)

/**
 * Turns the panels of a [MosaicPlan] into directions the aiming guidance can steer to.
 *
 * Panels of a horizontal plan stay where they are. Panels of an equatorial plan move with the sky, so the
 * directions are worked out for the instant of a [SkyFrame] and have to be refreshed as time passes. One call
 * for a whole plan costs a few microseconds per panel; call it about once a second while shooting, not for
 * every camera frame.
 */
object MosaicGuidance {
    /** The distance, in degrees, from a panel's centre towards the pole at which the picture's "up" is measured. */
    internal const val rollProbeDegrees: Double = 0.1

    /**
     * The pointing for one panel.
     *
     * @param panel A panel of [plan].
     * @param plan The plan the panel belongs to.
     * @param frame The instant and observer.
     * @param refraction Whether to raise the altitudes by atmospheric refraction, as for the other objects on screen.
     */
    fun pointing(
        panel: MosaicPanel,
        plan: MosaicPlan,
        frame: SkyFrame,
        refraction: Boolean = true,
    ): MosaicPanelPointing {
        val direction = horizontal(panel.center, frame, refraction)
        val corners = panel.corners.map { horizontal(it, frame, refraction) }
        var lowest = direction.altitude
        for (corner in corners) lowest = min(lowest, corner.altitude)
        return MosaicPanelPointing(
            panelIndex = panel.index,
            direction = direction,
            corners = corners,
            roll = panelRoll(panel, plan, frame),
            isAboveHorizon = direction.altitude > 0.0,
            lowestAltitude = lowest,
        )
    }

    /**
     * The pointings for all panels of [plan], in the order of [MosaicPlan.panels].
     *
     * @param plan The plan.
     * @param frame The instant and observer.
     * @param refraction Whether to raise the altitudes by atmospheric refraction.
     */
    fun pointings(plan: MosaicPlan, frame: SkyFrame, refraction: Boolean = true): List<MosaicPanelPointing> =
        plan.panels.map { pointing(it, plan, frame, refraction) }

    /**
     * The parallactic angle in degrees, (−180, 180]: the angle at a star between the direction to the
     * celestial pole and the direction to the zenith, positive in the west of the meridian (hour angle
     * between 0° and 180°). A picture taken level has the pole's direction tilted by this angle.
     *
     * @param hourAngle The star's local hour angle in degrees.
     * @param declination The star's declination in degrees.
     * @param latitude The observer's latitude in degrees.
     */
    fun parallacticAngle(hourAngle: Double, declination: Double, latitude: Double): Double =
        AstroMath.atan2Deg(
            AstroMath.sinDeg(hourAngle) * AstroMath.cosDeg(latitude),
            AstroMath.sinDeg(latitude) * AstroMath.cosDeg(declination) -
                AstroMath.cosDeg(latitude) * AstroMath.sinDeg(declination) * AstroMath.cosDeg(hourAngle),
        )

    /**
     * The roll, in degrees (−180, 180], of a camera that looks along [forward] with the top of its picture towards
     * [up], measured as for [MosaicPanelPointing.roll]: how far the top of the picture leans clockwise, as seen
     * through the camera, from straight up. Use it on the phone's attitude to show how far the camera is from the roll
     * a panel asks for: the camera has to turn clockwise by `normalizeSignedDegrees(panelRoll − cameraRoll)`.
     *
     * @param forward The direction the camera looks, an ENU vector of any non-zero length.
     * @param up The direction of the top of the picture, an ENU vector; its part along [forward] is ignored.
     * @return The roll; 0 for a camera that looks within a few thousandths of a degree of the zenith or nadir,
     * or when [up] has no part across [forward].
     */
    fun roll(forward: Vector3, up: Vector3): Double {
        val c = forward.normalized
        val across = up - c * up.dot(c)
        val level = Vector3(0.0, 0.0, 1.0) - c * c.z
        if (!(level.length > 1e-9 && across.length > 1e-12)) return 0.0
        val levelUnit = level.normalized
        val upUnit = across.normalized
        return AstroMath.atan2Deg(upUnit.dot(c.cross(levelUnit)), upUnit.dot(levelUnit))
    }

    private fun horizontal(point: MosaicPoint, frame: SkyFrame, refraction: Boolean): HorizontalCoordinate = when (point) {
        is MosaicPoint.Equatorial -> frame.horizontal(point.coordinate, null, refraction)
        is MosaicPoint.Horizontal -> point.coordinate
    }

    /**
     * The roll of the panel at the instant of [frame]. The direction of the plan's pole is carried to the
     * horizon frame together with the panel's centre; the roll is the angle from the vertical to that
     * direction as seen from the centre.
     */
    private fun panelRoll(panel: MosaicPanel, plan: MosaicPlan, frame: SkyFrame): Double {
        if (plan.kind == MosaicFrameKind.HORIZONTAL) return 0.0
        val center = panel.center.unitVector
        val pole = plan.pole.unitVector
        val towardsPole = pole - center * pole.dot(center)
        if (!(towardsPole.length > 1e-9)) return 0.0
        val probe = (center + towardsPole.normalized * AstroMath.tanDeg(rollProbeDegrees)).normalized
        return roll(frame.horizontalVector(center), frame.horizontalVector(probe))
    }
}
