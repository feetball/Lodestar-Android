package net.kuecker.lodestar.features.aim

import net.kuecker.lodestar.kit.pointing.Guidance
import net.kuecker.lodestar.kit.pointing.GuidanceTracker
import net.kuecker.lodestar.kit.pointing.PointingSolution
import net.kuecker.lodestar.services.MotionSample
import net.kuecker.lodestar.services.MotionService
import net.kuecker.lodestar.shared.SkySnapshot

/** One moment of aiming: where the camera points and how to reach the target from there. */
class AimFrame(
    val sample: MotionSample,
    /** The DSLR's pointing: the phone's attitude with the mount and the alignment applied. */
    val pointing: PointingSolution,
    /** How to reach the target; `null` without a target or before its position is known. */
    val guidance: Guidance?,
    /** The target's apparent position as of the last sky rebuild. */
    val target: SkySnapshot.Target?,
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

    /** The target's position from a freshly built snapshot; ignored if it belongs to another object. */
    fun update(target: SkySnapshot.Target?) {
        if (target == null || target.obj.id != targetID) return
        this.target = target
    }

    /** The frame for the newest motion sample, `null` while the sensors deliver nothing. */
    fun frame(): AimFrame? {
        val sample = motion.latestSample() ?: return null
        val pointing = PointingSolution.solve(sample.attitude, configuration.mount, configuration.alignment)
        val target = target
        val guidance = if (target != null) tracker.update(pointing, target.horizontal) else null
        return AimFrame(sample, pointing, guidance, target)
    }
}
