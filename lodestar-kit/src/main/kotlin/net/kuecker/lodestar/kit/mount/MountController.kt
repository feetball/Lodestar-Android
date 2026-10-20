package net.kuecker.lodestar.kit.mount

import net.kuecker.lodestar.kit.ephemeris.SolarSystemBody
import net.kuecker.lodestar.kit.ephemeris.horizontal
import net.kuecker.lodestar.kit.foundation.EquatorialCoordinate
import net.kuecker.lodestar.kit.foundation.GeographicLocation
import net.kuecker.lodestar.kit.foundation.Observer
import net.kuecker.lodestar.kit.foundation.ProperMotion
import net.kuecker.lodestar.kit.foundation.SkyFrame
import net.kuecker.lodestar.kit.foundation.addingTimeInterval
import net.kuecker.lodestar.kit.foundation.timeIntervalSince
import java.time.Instant
import kotlin.math.ceil

/**
 * The GoTo state machine: connects to a mount through a [MountDriver], keeps its position up to date, slews to J2000
 * targets, syncs, aborts, nudges and switches tracking, and watches every slew until the mount has arrived, stopped
 * short, failed or timed out.
 *
 * ```
 * DISCONNECTED --connect--> IDLE --goto--> SLEWING --poll: arrived / failed / timeout--> IDLE
 *                            |  \--nudge--> NUDGING --duration over (poll) / stopNudge--> IDLE
 *                            \--abort (any phase)--> IDLE            3 failed polls in a row --> DISCONNECTED
 * ```
 *
 * Safety rules enforced here: a target below [MountSettings.altitudeLimit] (now, and
 * [MountSettings.horizonLookaheadSeconds] from now) is never sent, and neither is a target within
 * [MountSettings.sunExclusionDegrees] of the Sun while the Sun is up; a goto while a slew or nudge runs is refused
 * ([MountError.SlewInProgress], [MountError.Busy]) until [abort] has been called, and so is a goto or sync while
 * the mount's own "slewing" flag is set (a move started on the hand controller); a slew that exceeds
 * [MountSettings.maximumSlewSeconds] is aborted; a nudge ends by itself after its duration (the next [poll] sends the
 * stop, so poll at least once a second while nudging); every driver exchange has a timeout and no loop is unbounded.
 *
 * Not thread safe. Call every method from one thread or queue, typically a background worker that calls [poll]
 * about once a second while the Aim screen shows the mount and [abort] on the user's request.
 */
class MountController(
    private val driver: MountDriver,
    /** The observer used for the altitude limit and the epoch conversions; may be replaced when the user moves. */
    var observer: Observer,
    /** The limits and timings in use. */
    val settings: MountSettings = MountSettings.standard,
    private val clock: MountClock = SystemMountClock(),
) {
    /** What the mount can do; null before [connect] succeeded. */
    var capabilities: MountCapabilities? = null
        private set

    private var phase: MountPhase = MountPhase.DISCONNECTED
    private var position: EquatorialCoordinate? = null
    private var mountPosition: EquatorialCoordinate? = null
    private var target: EquatorialCoordinate? = null
    private var sentTarget: EquatorialCoordinate? = null
    private var separation: Double? = null
    private var tracking: Boolean? = null
    private var aligned: Boolean? = null
    private var moving: Boolean? = null
    private var slewStarted: Instant? = null
    private var slewElapsed: Double? = null
    private var sawSlewing = false
    private var outcome: MountSlewOutcome? = null
    private var lastError: MountError? = null
    private var updated: Instant? = null
    private var failures = 0
    private var nudgeDirection: MountNudgeDirection? = null
    private var nudgeEnds: Instant? = null

    /** The state as of the last call; cheap, never talks to the mount. */
    val status: MountStatus
        get() = MountStatus(
            phase, position, target, separation, tracking, aligned, moving, slewElapsed, outcome, lastError, updated,
        )

    /** True while a session is open. */
    val isConnected: Boolean get() = phase != MountPhase.DISCONNECTED

    // MARK: Session

    /**
     * Opens the session: runs the driver's handshake and reads the first position. Does nothing but return the
     * capabilities if the session is already open.
     */
    @Throws(MountError::class)
    fun connect(): MountCapabilities {
        capabilities?.let { if (phase != MountPhase.DISCONNECTED) return it }
        try {
            val found = driver.connect()
            val now = clock.now()
            aligned = found.isAligned
            readPosition(now, found.isAligned)
            tracking = driver.readTracking()
            moving = driver.readSlewing()
            capabilities = found
            phase = MountPhase.IDLE
            failures = 0
            lastError = null
            outcome = null
            target = null
            sentTarget = null
            separation = null
            slewStarted = null
            slewElapsed = null
            nudgeDirection = null
            nudgeEnds = null
            updated = now
            return found
        } catch (error: MountError) {
            lastError = error
            driver.disconnect()
            throw error
        }
    }

    /** Ends the session. A running nudge is stopped (best effort); a running slew is left to finish. */
    fun disconnect() {
        val direction = nudgeDirection
        if (phase != MountPhase.DISCONNECTED && direction != null) {
            try {
                driver.stopNudge(direction)
            } catch (error: MountError) {
                lastError = error
            }
        }
        if (phase != MountPhase.DISCONNECTED) driver.disconnect()
        phase = MountPhase.DISCONNECTED
        nudgeDirection = null
        nudgeEnds = null
        slewStarted = null
        slewElapsed = null
    }

    // MARK: Polling

    /**
     * One refresh cycle: reads the mount's position (and slewing/tracking flags), advances a running slew or nudge and
     * returns the new [status]. Never throws: failures are recorded in [MountStatus.lastError]; after
     * [MountSettings.maximumConsecutiveFailures] failed polls in a row the session ends
     * ([MountError.ConnectionLost]). Costs two to four driver exchanges (about 30 to 200 ms on a Wi-Fi mount).
     */
    fun poll(): MountStatus {
        if (phase == MountPhase.DISCONNECTED) return status
        val now = clock.now()
        try {
            when (phase) {
                MountPhase.SLEWING -> pollSlewing(now)
                MountPhase.NUDGING -> pollNudging(now)
                else -> readState(now)
            }
            failures = 0
            lastError = null
        } catch (error: MountError) {
            recordFailure(error)
        }
        updated = clock.now()
        return status
    }

    /**
     * Polls every [MountSettings.pollIntervalSeconds] until the slew has ended and returns the final status. The loop is
     * bounded by the maximum slew time, so it returns after at most about that long.
     */
    fun awaitSettled(): MountStatus {
        val limit = ceil(settings.maximumSlewSeconds / settings.pollIntervalSeconds).toInt() + 10
        var iterations = 0
        while (phase == MountPhase.SLEWING && iterations < limit) {
            poll()
            if (phase != MountPhase.SLEWING) break
            clock.sleep(settings.pollIntervalSeconds)
            iterations++
        }
        return status
    }

    // MARK: Commands

    /**
     * Starts a slew to the J2000 place [target] (with its [properMotion], if it has one) and returns as soon as the mount has
     * accepted it; watch it with [poll] or [awaitSettled].
     *
     * @throws MountError.NotConnected, [MountError.SlewInProgress] (call [abort] first), [MountError.Busy] (nudging),
     * [MountError.InvalidInput], [MountError.NotSupported], [MountError.BelowHorizonLimit] (checked with the observer's
     * sky now and [MountSettings.horizonLookaheadSeconds] from now), [MountError.NearSun] (the Sun is up and the target is
     * within [MountSettings.sunExclusionDegrees] of it, now or [MountSettings.horizonLookaheadSeconds] from now), and
     * whatever the mount answers ([MountError.NotAligned], [MountError.MountHorizonLimit], [MountError.Parked], …).
     */
    @Throws(MountError::class)
    fun goto(target: EquatorialCoordinate, properMotion: ProperMotion? = null) {
        val found = requireConnected()
        requireFinite(target)
        when (phase) {
            MountPhase.SLEWING -> throw MountError.SlewInProgress
            MountPhase.NUDGING -> throw MountError.Busy
            else -> Unit
        }
        if (moving == true) throw MountError.SlewInProgress
        if (!found.canSlew) throw MountError.NotSupported(MountFeature.SLEW)
        val now = clock.now()
        checkAltitude(target, properMotion, now)
        checkSun(target, properMotion, now)
        val sent = driver.epoch.fromJ2000(target, SkyFrame(now, observer), properMotion)
        try {
            driver.slew(sent)
        } catch (error: MountError) {
            lastError = error
            if (error.isCommunicationFailure) stopQuietly()
            throw error
        }
        phase = MountPhase.SLEWING
        this.target = target
        sentTarget = sent
        separation = mountPosition?.angularSeparation(sent)
        slewStarted = now
        slewElapsed = 0.0
        sawSlewing = false
        outcome = null
        lastError = null
        updated = now
    }

    /**
     * Tells the mount that it points at the J2000 place [target] now (after the user centred that star), and returns by
     * how much the mount's idea of its position changed.
     *
     * @throws MountError.SyncOffsetTooLarge if the change exceeds [MountSettings.maximumSyncOffsetDegrees], plus the
     * errors listed at [goto] that apply (not connected, busy, not supported, the mount's refusals).
     */
    @Throws(MountError::class)
    fun sync(target: EquatorialCoordinate, properMotion: ProperMotion? = null): MountSyncResult {
        val found = requireConnected()
        requireFinite(target)
        when (phase) {
            MountPhase.SLEWING -> throw MountError.SlewInProgress
            MountPhase.NUDGING -> throw MountError.Busy
            else -> Unit
        }
        if (moving == true) throw MountError.SlewInProgress
        if (!found.canSync) throw MountError.NotSupported(MountFeature.SYNC)
        val now = clock.now()
        val sent = driver.epoch.fromJ2000(target, SkyFrame(now, observer), properMotion)
        val before = driver.readPosition()
        val offset = before.angularSeparation(sent)
        if (offset > settings.maximumSyncOffsetDegrees) {
            throw MountError.SyncOffsetTooLarge(offset, settings.maximumSyncOffsetDegrees)
        }
        try {
            driver.sync(sent)
        } catch (error: MountError) {
            lastError = error
            throw error
        }
        storePosition(sent, now)
        lastError = null
        updated = now
        return MountSyncResult(offset)
    }

    /**
     * Stops the mount: halts a slew or nudge (each stop is attempted up to three times on communication failures) and
     * returns to [MountPhase.IDLE]. Allowed in every connected phase.
     *
     * @throws MountError if the mount could not be stopped; the phase is then unchanged and the call can be repeated.
     */
    @Throws(MountError::class)
    fun abort() {
        requireConnected()
        val direction = nudgeDirection
        val failure = repeatUntilDone { driver.abort() }
            ?: direction?.let { toStop -> repeatUntilDone { driver.stopNudge(toStop) } }
        if (failure != null) {
            lastError = failure
            throw failure
        }
        if (phase == MountPhase.SLEWING) finishSlew(MountSlewOutcome.Aborted)
        phase = MountPhase.IDLE
        nudgeDirection = null
        nudgeEnds = null
        moving = false
        lastError = null
    }

    /**
     * Moves the mount in [direction] at [rate] for [durationSeconds] (at most [MountSettings.maximumNudgeSeconds]). The
     * nudge ends at the first [poll] after the duration, or at [stopNudge]. A nudge in another direction replaces a
     * running one.
     */
    @Throws(MountError::class)
    fun nudge(direction: MountNudgeDirection, rate: MountNudgeRate = MountNudgeRate.CENTER, durationSeconds: Double = 0.5) {
        val found = requireConnected()
        if (!(durationSeconds > 0) || durationSeconds > settings.maximumNudgeSeconds) {
            throw MountError.InvalidInput("the nudge must last more than 0 and at most ${settings.maximumNudgeSeconds} seconds")
        }
        if (phase == MountPhase.SLEWING) throw MountError.SlewInProgress
        if (!found.canNudge) throw MountError.NotSupported(MountFeature.NUDGE)
        val previous = nudgeDirection
        if (previous != null && previous != direction) {
            driver.stopNudge(previous)
            nudgeDirection = null
        }
        val now = clock.now()
        try {
            driver.startNudge(direction, rate, durationSeconds)
        } catch (error: MountError) {
            lastError = error
            try {
                driver.stopNudge(direction)
            } catch (_: MountError) {
                // Already failing; the error above is what the caller needs to see.
            }
            throw error
        }
        phase = MountPhase.NUDGING
        nudgeDirection = direction
        nudgeEnds = now.addingTimeInterval(durationSeconds)
        lastError = null
    }

    /** Ends a running nudge now. Does nothing when no nudge is running. */
    @Throws(MountError::class)
    fun stopNudge() {
        requireConnected()
        val direction = nudgeDirection ?: return
        val failure = repeatUntilDone { driver.stopNudge(direction) }
        if (failure != null) {
            lastError = failure
            throw failure
        }
        if (phase == MountPhase.NUDGING) phase = MountPhase.IDLE
        nudgeDirection = null
        nudgeEnds = null
        moving = null
    }

    /** Switches the mount's tracking on or off (refused while a slew runs). */
    @Throws(MountError::class)
    fun setTracking(enabled: Boolean) {
        val found = requireConnected()
        if (phase == MountPhase.SLEWING) throw MountError.SlewInProgress
        if (!found.canSetTracking) throw MountError.NotSupported(MountFeature.TRACKING)
        try {
            driver.setTracking(enabled)
        } catch (error: MountError) {
            lastError = error
            throw error
        }
        tracking = enabled
        lastError = null
    }

    /**
     * Sets the mount's site and clock (UTC, no zone offset) from [location] and [date] (default: the clock's now).
     * Needed by mounts that compute their own sky (OnStep, SynScan, NexStar hand controls).
     */
    @Throws(MountError::class)
    fun setSiteAndTime(location: GeographicLocation, date: Instant? = null) {
        val found = requireConnected()
        if (phase == MountPhase.SLEWING) throw MountError.SlewInProgress
        if (!found.canSetSiteAndTime) throw MountError.NotSupported(MountFeature.SITE_AND_TIME)
        try {
            driver.setSiteAndTime(location, date ?: clock.now())
        } catch (error: MountError) {
            lastError = error
            throw error
        }
        lastError = null
    }

    // MARK: Polling steps

    private fun pollSlewing(now: Instant) {
        val started = slewStarted ?: now
        val elapsed = now.timeIntervalSince(started)
        slewElapsed = elapsed
        if (elapsed > settings.maximumSlewSeconds) {
            driver.abort()
            moving = false
            finishSlew(MountSlewOutcome.Failed(MountError.SlewTimeout(settings.maximumSlewSeconds)))
            return
        }
        val isMoving = driver.readSlewing()
        moving = isMoving
        val reading = driver.readPosition()
        storePosition(reading, now)
        if (isMoving) {
            sawSlewing = true
            return
        }
        val goal = sentTarget ?: return
        val remaining = reading.angularSeparation(goal)
        when {
            remaining <= settings.arrivalToleranceDegrees -> finishSlew(MountSlewOutcome.Arrived(remaining))
            !sawSlewing && elapsed < settings.slewStartGraceSeconds -> Unit
            else -> finishSlew(MountSlewOutcome.Failed(MountError.SlewStopped(remaining)))
        }
    }

    private fun pollNudging(now: Instant) {
        val ends = nudgeEnds
        val direction = nudgeDirection
        if (ends != null && direction != null && now >= ends) {
            driver.stopNudge(direction)
            phase = MountPhase.IDLE
            nudgeDirection = null
            nudgeEnds = null
            moving = null
        }
        readState(now)
    }

    private fun readState(now: Instant) {
        if (aligned != true) aligned = driver.readAligned() ?: aligned
        readPosition(now, aligned)
        moving = driver.readSlewing()
        tracking = driver.readTracking()
    }

    /** Reads and stores the position; before an alignment the mount's coordinates may be meaningless, which is not an error. */
    private fun readPosition(now: Instant, alignment: Boolean?) {
        try {
            storePosition(driver.readPosition(), now)
        } catch (error: MountError.MalformedReply) {
            if (alignment != false) throw error
        }
    }

    private fun recordFailure(error: MountError) {
        failures += 1
        lastError = error
        if (failures < settings.maximumConsecutiveFailures) return
        // A nudge keeps an axis turning until the mount is told to stop, and the poll that would have ended it will not
        // come again. The link may be gone, so this is one best-effort attempt that never throws.
        val nudging = nudgeDirection
        if (phase == MountPhase.NUDGING && nudging != null) stopNudgeQuietly(nudging)
        if (phase == MountPhase.SLEWING) {
            stopQuietly()
            outcome = MountSlewOutcome.Failed(MountError.ConnectionLost)
        }
        phase = MountPhase.DISCONNECTED
        lastError = MountError.ConnectionLost
        nudgeDirection = null
        nudgeEnds = null
        slewStarted = null
        slewElapsed = null
        driver.disconnect()
    }

    // MARK: Helpers

    private fun requireConnected(): MountCapabilities {
        val found = capabilities
        if (phase == MountPhase.DISCONNECTED || found == null) throw MountError.NotConnected
        return found
    }

    private fun requireFinite(target: EquatorialCoordinate) {
        if (!target.rightAscension.isFinite() || !target.declination.isFinite()) {
            throw MountError.InvalidInput("the target coordinates are not numbers")
        }
    }

    private fun checkAltitude(target: EquatorialCoordinate, properMotion: ProperMotion?, now: Instant) {
        val limit = settings.altitudeLimit
        val offsets = if (settings.horizonLookaheadSeconds > 0) {
            doubleArrayOf(0.0, settings.horizonLookaheadSeconds)
        } else {
            doubleArrayOf(0.0)
        }
        for (offset in offsets) {
            val frame = SkyFrame(now.addingTimeInterval(offset), observer)
            val altitude = frame.horizontal(target, properMotion, false).altitude
            if (altitude < limit) throw MountError.BelowHorizonLimit(altitude, limit)
        }
    }

    /**
     * Refuses a target that is closer to the Sun than [MountSettings.sunExclusionDegrees] while the Sun is up. The check
     * looks at the sky now and [MountSettings.horizonLookaheadSeconds] from now (the Sun may rise during the slew), and
     * compares positions, not identities: Venus near inferior conjunction, the Moon at new moon or a comet in the glare
     * are as dangerous as the Sun itself. Both places are geometric, topocentric and in the horizontal frame, so no
     * frame conversion can put them out of step. The Sun counts as up from -1 degrees, which covers its disc and the
     * refraction at the horizon.
     */
    private fun checkSun(target: EquatorialCoordinate, properMotion: ProperMotion?, now: Instant) {
        val limit = settings.sunExclusionDegrees
        val offsets = if (settings.horizonLookaheadSeconds > 0) {
            doubleArrayOf(0.0, settings.horizonLookaheadSeconds)
        } else {
            doubleArrayOf(0.0)
        }
        for (offset in offsets) {
            val frame = SkyFrame(now.addingTimeInterval(offset), observer)
            val sun = frame.horizontal(SolarSystemBody.SUN, refraction = false)
            if (sun.altitude <= sunUpAltitude) continue
            val place = frame.horizontal(target, properMotion, false)
            val distance = place.angularSeparation(sun)
            if (distance < limit) throw MountError.NearSun(distance, limit)
        }
    }

    private fun storePosition(reading: EquatorialCoordinate, now: Instant) {
        mountPosition = reading
        position = driver.epoch.toJ2000(reading, SkyFrame(now, observer))
        val goal = sentTarget
        separation = if (goal != null) reading.angularSeparation(goal) else null
    }

    private fun finishSlew(result: MountSlewOutcome) {
        outcome = result
        phase = MountPhase.IDLE
        slewStarted = null
        slewElapsed = null
    }

    /** Tells the mount to stop after an exchange failed so badly that a slew may or may not have started. */
    private fun stopQuietly() {
        try {
            driver.abort()
        } catch (_: MountError) {
            // Nothing more can be done; the caller reports the original failure.
        }
    }

    /** Tells the mount to end the nudge in [direction] when the session is being given up; a failure changes nothing. */
    private fun stopNudgeQuietly(direction: MountNudgeDirection) {
        try {
            driver.stopNudge(direction)
        } catch (_: MountError) {
            // The link is probably gone; the session ends with `ConnectionLost` either way.
        }
    }

    /** Runs [action] up to three times while it fails with communication errors; returns the last error, or null on success. */
    private fun repeatUntilDone(action: () -> Unit): MountError? {
        var last: MountError? = null
        for (attempt in 1..stopAttempts) {
            try {
                action()
                return null
            } catch (error: MountError) {
                last = error
                if (!error.isCommunicationFailure) return error
            }
        }
        return last
    }

    private companion object {
        const val stopAttempts: Int = 3

        /** The Sun's geometric altitude (degrees) above which it counts as up for the exclusion zone. */
        const val sunUpAltitude: Double = -1.0
    }
}
