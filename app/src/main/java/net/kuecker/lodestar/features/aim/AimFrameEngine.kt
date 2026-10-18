package net.kuecker.lodestar.features.aim

import net.kuecker.lodestar.features.satellites.SatelliteAimFix
import net.kuecker.lodestar.features.satellites.SatelliteAimSource
import net.kuecker.lodestar.kit.pointing.Guidance
import net.kuecker.lodestar.kit.pointing.GuidanceTracker
import net.kuecker.lodestar.kit.pointing.PointingSolution
import net.kuecker.lodestar.services.MotionSample
import net.kuecker.lodestar.services.MotionService
import net.kuecker.lodestar.shared.SkySnapshot
import java.time.Instant

/** One moment of aiming: where the camera points and how to reach the target from there. */
class AimFrame(
    val sample: MotionSample,
    /** The DSLR's pointing: the phone's attitude with the mount and the alignment applied. */
    val pointing: PointingSolution,
    /** How to reach the target; `null` without a target or before its position is known. */
    val guidance: Guidance?,
    /** The target's apparent position as of the last sky rebuild; `null` while a mosaic panel is aimed at instead. */
    val target: SkySnapshot.Target?,
    /** The mosaic panel guidance leads to instead of the target, while a mosaic runs. */
    val panel: AimPanelAim? = null,
    /**
     * How far the camera has to turn about its axis for [panel], degrees, positive clockwise as the photographer sees
     * it; `null` without a panel.
     */
    val rollDifference: Double? = null,
    /** Where a chosen satellite (or the peak of its pass) is; guidance leads there instead of to the target. */
    val satellite: SatelliteAimFix? = null,
)

/**
 * Turns the newest motion sample into an [AimFrame].
 *
 * It is cheap, so every consumer asks for the frame it needs when it draws (30 Hz overlay, arrow, HUD text, audio
 * ticks) instead of receiving 60 Hz updates through observable state. It owns the guidance tracker whose hysteresis
 * keeps the on-target state steady; asking again for the same sample gives the same answer. Main thread only.
 */
class AimFrameEngine(private val motion: MotionService, configuration: AimConfiguration) {
    private var configuration = configuration
    private var tracker = GuidanceTracker(configuration.onTargetThreshold)
    private var targetID: String? = null
    private var target: SkySnapshot.Target? = null
    private var panel: AimPanelAim? = null
    private var satellite: SatelliteAimSource? = null
    private var satelliteNow: () -> Instant = Instant::now
    private var satelliteSample: MotionSample? = null
    private var satelliteFix: SatelliteAimFix? = null

    fun configure(configuration: AimConfiguration) {
        if (configuration.onTargetThreshold != tracker.onTargetThreshold) {
            tracker = GuidanceTracker(configuration.onTargetThreshold)
        }
        this.configuration = configuration
    }

    /**
     * Chooses the object guidance leads to. Its position follows with [update]; until then, and after the target
     * changes, there is no guidance.
     */
    fun setTarget(id: String?) {
        if (id == targetID) return
        targetID = id
        target = null
        tracker.reset()
    }

    /**
     * Leads guidance to the panel [aim] of a mosaic instead of the target, or back to the target for `null`. Calling it
     * again for the same panel (same key) only moves its direction, as the sky turns; another panel, or none, starts
     * the on-target state afresh.
     */
    fun setPanel(aim: AimPanelAim?) {
        if (aim?.key != panel?.key) tracker.reset()
        panel = aim
    }

    /**
     * Leads guidance to [source] (a moving satellite, or the fixed peak of its pass) instead of the target, or back to the
     * target for `null`. A mosaic panel still comes first. [now] is the instant to look the satellite up at; the look
     * is made once per motion sample. Another source starts the on-target state afresh.
     */
    fun setSatellite(source: SatelliteAimSource?, now: () -> Instant = Instant::now) {
        if (source?.key != satellite?.key) tracker.reset()
        satellite = source
        satelliteNow = now
        satelliteSample = null
        satelliteFix = null
    }

    /** The target's position from a freshly built snapshot; ignored if it belongs to another object. */
    fun update(target: SkySnapshot.Target?) {
        if (target == null || target.obj.id != targetID) return
        this.target = target
    }

    /** The frame for the newest motion sample, `null` while the sensors deliver nothing. */
    fun frame(): AimFrame? {
        val sample = motion.latestSample() ?: return null
        val pointing = PointingSolution.solve(sample.attitude, configuration.mount, configuration.alignment)
        val aim = panel
        if (aim != null) {
            return AimFrame(
                sample, pointing, tracker.update(pointing, aim.horizontal), null, aim, aim.rollDifference(pointing),
            )
        }
        val source = satellite
        if (source != null) {
            val fix = satelliteFix(source, sample)
            return AimFrame(sample, pointing, fix?.let { tracker.update(pointing, it.horizontal) }, null, satellite = fix)
        }
        val target = target
        val guidance = if (target != null) tracker.update(pointing, target.horizontal) else null
        return AimFrame(sample, pointing, guidance, target)
    }

    /** The satellite's place for [sample]: one look per attitude update, however often the frame is asked for. */
    private fun satelliteFix(source: SatelliteAimSource, sample: MotionSample): SatelliteAimFix? {
        if (sample !== satelliteSample) {
            satelliteSample = sample
            satelliteFix = source.fix(satelliteNow())
        }
        return satelliteFix
    }
}
