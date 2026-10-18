package net.kuecker.lodestar.features.mosaic

import net.kuecker.lodestar.kit.catalog.CelestialObject
import net.kuecker.lodestar.kit.foundation.HorizontalCoordinate
import net.kuecker.lodestar.kit.foundation.Observer
import net.kuecker.lodestar.kit.foundation.SkyFrame
import net.kuecker.lodestar.kit.mosaic.MilkyWayPanorama
import net.kuecker.lodestar.kit.mosaic.MosaicError
import net.kuecker.lodestar.kit.mosaic.MosaicOrientation
import net.kuecker.lodestar.kit.mosaic.MosaicPlan
import net.kuecker.lodestar.kit.mosaic.MosaicPlanner
import net.kuecker.lodestar.kit.mosaic.MosaicRegion
import net.kuecker.lodestar.kit.mosaic.MosaicWarning
import net.kuecker.lodestar.kit.phototools.LensSetup
import java.time.Instant
import kotlin.math.roundToInt

/** The result of planning: a plan to show and start, or the reason there is none. */
internal sealed interface MosaicPlanOutcome {
    /**
     * @property recommended The orientation with fewer panels, which the plan uses unless the person chose one.
     * @property title What the plan covers, for the HUD.
     */
    data class Ready(val plan: MosaicPlan, val recommended: MosaicOrientation, val title: String) : MosaicPlanOutcome

    data class Problem(val message: String) : MosaicPlanOutcome
}

/** Everything the planning needs from the app, gathered on the main thread. */
internal data class MosaicPlanningInputs(
    val setup: MosaicSetup,
    val target: CelestialObject?,
    val lens: LensSetup,
    val observer: Observer,
    /** Where the camera points, when the sensors say. */
    val camera: HorizontalCoordinate?,
    val date: Instant,
)

/** Makes the plan for the mosaic sheet. Pure (but not instant): call it from a background dispatcher. */
internal object MosaicPlanning {
    /** Why [target] cannot be covered with a margin around its size, or `null` when it can. */
    fun targetProblem(target: CelestialObject?): String? {
        if (target == null) return "Choose a target first."
        return try {
            MosaicRegion.fitting(target)
            null
        } catch (error: MosaicError.ObjectHasNoSize) {
            "${target.name} has no known size. Plan a custom size instead."
        } catch (error: MosaicError.ObjectNotFixed) {
            "${target.name} moves across the sky, so a fixed mosaic cannot follow it. Plan a custom size instead."
        } catch (error: MosaicError) {
            error.message ?: "${target.name} cannot be covered."
        }
    }

    /** The visible Milky Way arch as a panorama box, or `null` when none of it is more than 5° up. */
    fun archRegion(observer: Observer, date: Instant): MosaicRegion? =
        MilkyWayPanorama.arch(SkyFrame(date, observer)).panoramaRegion()

    /** Why the Milky Way arch cannot be planned now, or `null` when it can. */
    fun archProblem(observer: Observer, date: Instant): String? =
        if (archRegion(observer, date) == null) "The Milky Way is not above the horizon now." else null

    /** Plans [inputs]; never throws. */
    fun plan(inputs: MosaicPlanningInputs): MosaicPlanOutcome {
        val setup = inputs.setup
        val title: String
        val region: MosaicRegion
        try {
            when (setup.source) {
                MosaicSource.TARGET -> {
                    val target = inputs.target ?: return MosaicPlanOutcome.Problem("Choose a target first.")
                    targetProblem(target)?.let { return MosaicPlanOutcome.Problem(it) }
                    region = MosaicRegion.fitting(target, setup.margin)
                    title = target.name
                }
                MosaicSource.MILKY_WAY -> {
                    region = archRegion(inputs.observer, inputs.date)
                        ?: return MosaicPlanOutcome.Problem("The Milky Way is not above the horizon now.")
                    title = "Milky Way arch"
                }
                MosaicSource.CUSTOM -> {
                    val size = "${formatDegrees(setup.customWidth)}° × ${formatDegrees(setup.customHeight)}°"
                    val target = inputs.target
                    val center = target?.j2000
                    val camera = inputs.camera
                    if (target != null && center != null) {
                        region = MosaicRegion.Equatorial(center, setup.customWidth, setup.customHeight)
                        title = "$size around ${target.name}"
                    } else if (camera != null) {
                        region = MosaicRegion.centered(camera.azimuth, camera.altitude, setup.customWidth, setup.customHeight)
                        title = "$size where the camera points"
                    } else {
                        return MosaicPlanOutcome.Problem("Choose a target, or point the camera where the mosaic should be.")
                    }
                }
            }
            val frame = inputs.lens.fieldOfView
            val recommended = MosaicPlanner.recommendedOrientation(region, frame, setup.overlap)
            val plan = MosaicPlanner.plan(
                region, frame, setup.orientation ?: recommended, setup.overlap, inputs.observer.location.latitude,
            )
            return MosaicPlanOutcome.Ready(plan, recommended, title)
        } catch (error: MosaicError) {
            return MosaicPlanOutcome.Problem(describe(error))
        }
    }

    /** A sentence for a planning error. */
    fun describe(error: MosaicError): String = when (error) {
        is MosaicError.TooManyPanels ->
            "This region is too large for the lens: it would take ${error.count} panels. Use a smaller region or a wider lens."
        is MosaicError.InvalidWidth, is MosaicError.InvalidHeight, is MosaicError.InvalidAzimuthSpan,
        is MosaicError.InvalidAltitudeRange,
        -> "That size is not possible. The width can be up to 360° and the height up to 180°."
        is MosaicError.InvalidFrame -> "Check the lens in Settings: its field of view is not valid."
        is MosaicError.ObjectHasNoSize -> "This object has no known size. Plan a custom size instead."
        is MosaicError.ObjectNotFixed -> "This object moves across the sky. Plan a custom size instead."
        else -> error.message ?: "The mosaic cannot be planned."
    }

    /** A sentence for a warning of the plan. */
    fun describe(warning: MosaicWarning): String = when (warning) {
        is MosaicWarning.TooManyPanels ->
            "${warning.count} panels will take a long time, and the sky moves meanwhile."
        is MosaicWarning.OverlapNotMet ->
            "The region could not be fully covered (${(warning.coveredFraction * 100).roundToInt()} %). Increase the overlap."
    }

    private fun formatDegrees(value: Double): String =
        if (value >= 10) value.roundToInt().toString() else "%.1f".format(value)
}
