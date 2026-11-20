package net.kuecker.lodestar.features.aim

import net.kuecker.lodestar.kit.pointing.SteadinessDetector
import net.kuecker.lodestar.kit.pointing.SteadinessSample

/** Where the wait between tapping Sync and syncing stands. */
internal sealed interface AimHoldStillStatus {
    /** Still waiting; [progress] in [0, 1] is how much of the steady window the camera has been still for. */
    data class Waiting(val progress: Double) : AimHoldStillStatus

    /** The camera was still for the whole window: sync now. */
    data object Steady : AimHoldStillStatus

    /** [AimHoldStill.TIMEOUT_SECONDS] passed without a steady window. */
    data object TimedOut : AimHoldStillStatus
}

/**
 * The wait between tapping Sync and syncing: the alignment is only recorded once the camera has been held still for
 * a whole window ([SteadinessDetector]), and the wait ends after [TIMEOUT_SECONDS] without one.
 *
 * Feed [update] with the newest boresight sample at a steady pace (every [POLL_INTERVAL_MS]) and the caller's clock.
 * The clock is any monotonic seconds value; it is not the sensor clock, so a stalled sensor still times out. Create
 * one for each tap on Sync.
 *
 * @param startedAt The caller's clock, in seconds, when Sync was tapped.
 * @param timeoutSeconds How long to wait for a steady window.
 */
internal class AimHoldStill(private val startedAt: Double, private val timeoutSeconds: Double = TIMEOUT_SECONDS) {
    private var detector = SteadinessDetector()

    /**
     * Takes the newest [sample] (null when the sensors delivered nothing) at [now] and says how the wait stands.
     * A steady window wins over a timeout that falls at the same moment.
     */
    fun update(now: Double, sample: SteadinessSample?): AimHoldStillStatus {
        if (sample != null) detector = detector.adding(sample)
        return when {
            detector.isSteady -> AimHoldStillStatus.Steady
            now - startedAt >= timeoutSeconds -> AimHoldStillStatus.TimedOut
            else -> AimHoldStillStatus.Waiting(detector.progress)
        }
    }

    companion object {
        /** Seconds after which the wait gives up. */
        const val TIMEOUT_SECONDS = 6.0

        /** Milliseconds between two looks at the sensors: about the sensors' own rate, so a short shake is seen. */
        const val POLL_INTERVAL_MS = 20L

        /** Shown when the wait timed out. */
        const val TIMEOUT_NOTICE = "The phone is still moving. Steady the tripod and try again."
    }
}
