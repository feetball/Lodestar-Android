package net.kuecker.lodestar.kit.mount

import net.kuecker.lodestar.kit.foundation.AstroMath
import net.kuecker.lodestar.kit.foundation.EquatorialCoordinate
import kotlin.math.abs

/**
 * A mount that moves: the physics shared by the protocol simulators. It slews towards a target at a finite rate
 * (right ascension and declination move at the same speed, right ascension the short way round), reports "slewing"
 * until it arrives, accepts syncs, and moves continuously while a nudge is on. Time comes from the [ManualMountClock],
 * so results are deterministic. Coordinates are in whatever frame the protocol simulator is used with.
 */
class MountSimulatorModel(
    private val clock: ManualMountClock,
    start: EquatorialCoordinate,
    /** Slew speed in degrees per second. */
    val slewRateDegreesPerSecond: Double = 4.0,
    /** Seconds between the slew command and the first movement; the mount reports "slewing" meanwhile. */
    val startDelaySeconds: Double = 0.4,
) {
    private var rightAscension: Double = start.rightAscension
    private var declination: Double = start.declination
    private var lastUpdate: Double = clock.secondsSince1970
    private var goalRightAscension: Double = start.rightAscension
    private var goalDeclination: Double = start.declination
    private var slewing = false
    private var slewBegan: Double = 0.0
    private var rightAscensionRate = 0.0
    private var declinationRate = 0.0

    /** Whether the mount tracks the sky (it never drifts in the simulation; the flag is only reported). */
    var isTracking: Boolean = true

    /** Whether the mount has been star-aligned. */
    var isAligned: Boolean = true

    /** Whether the mount is parked. */
    var isParked: Boolean = false

    /** The number of slews started so far. */
    var slewsStarted: Int = 0
        private set

    /** The number of syncs performed so far. */
    var syncsPerformed: Int = 0
        private set

    /** The number of times the mount was told to stop. */
    var stopsReceived: Int = 0
        private set

    /** Where the mount points now. */
    val position: EquatorialCoordinate
        get() {
            advance()
            return EquatorialCoordinate(rightAscension, declination)
        }

    /** Whether the mount is slewing (a nudge does not count). */
    val isSlewing: Boolean
        get() {
            advance()
            return slewing
        }

    /** Whether a nudge is moving the mount. */
    val isNudging: Boolean
        get() {
            advance()
            return rightAscensionRate != 0.0 || declinationRate != 0.0
        }

    /** Starts a slew to [target]. */
    fun beginSlew(target: EquatorialCoordinate) {
        advance()
        goalRightAscension = target.rightAscension
        goalDeclination = target.declination
        slewing = true
        slewBegan = clock.secondsSince1970
        slewsStarted += 1
    }

    /** Stops a slew and all nudges. */
    fun stop() {
        advance()
        slewing = false
        rightAscensionRate = 0.0
        declinationRate = 0.0
        stopsReceived += 1
    }

    /** Declares the mount to point at [target] now. */
    fun sync(target: EquatorialCoordinate) {
        advance()
        rightAscension = target.rightAscension
        declination = target.declination
        syncsPerformed += 1
    }

    /** Moves continuously: [rightAscensionDegreesPerSecond] and [declinationDegreesPerSecond] (signed; 0 leaves an axis still). */
    fun setNudgeRates(rightAscensionDegreesPerSecond: Double, declinationDegreesPerSecond: Double) {
        advance()
        rightAscensionRate = rightAscensionDegreesPerSecond
        declinationRate = declinationDegreesPerSecond
    }

    /** Moves the position by [rightAscensionDegrees] and [declinationDegrees] at once (a guide pulse). */
    fun displace(rightAscensionDegrees: Double, declinationDegrees: Double) {
        advance()
        rightAscension = AstroMath.normalizeDegrees(rightAscension + rightAscensionDegrees)
        declination = (declination + declinationDegrees).coerceIn(-90.0, 90.0)
    }

    private fun advance() {
        val now = clock.secondsSince1970
        val previous = lastUpdate
        lastUpdate = now
        val elapsed = now - previous
        if (elapsed <= 0) return
        if (slewing) {
            val movingSince = maxOf(previous, slewBegan + startDelaySeconds)
            val movingTime = now - movingSince
            if (movingTime > 0) {
                val step = slewRateDegreesPerSecond * movingTime
                val raDelta = AstroMath.normalizeSignedDegrees(goalRightAscension - rightAscension)
                val decDelta = goalDeclination - declination
                rightAscension = if (abs(raDelta) <= step) goalRightAscension else {
                    AstroMath.normalizeDegrees(rightAscension + (if (raDelta > 0) step else -step))
                }
                declination = if (abs(decDelta) <= step) goalDeclination else declination + (if (decDelta > 0) step else -step)
                if (rightAscension == goalRightAscension && declination == goalDeclination) slewing = false
            }
        }
        if (rightAscensionRate != 0.0 || declinationRate != 0.0) {
            rightAscension = AstroMath.normalizeDegrees(rightAscension + rightAscensionRate * elapsed)
            declination = (declination + declinationRate * elapsed).coerceIn(-90.0, 90.0)
        }
    }
}
