package net.kuecker.lodestar.kit.mount

import net.kuecker.lodestar.kit.foundation.AstroMath
import net.kuecker.lodestar.kit.foundation.EquatorialCoordinate
import net.kuecker.lodestar.kit.foundation.GeographicLocation
import net.kuecker.lodestar.kit.foundation.Observer
import net.kuecker.lodestar.kit.foundation.SkyFrame
import net.kuecker.lodestar.kit.foundation.addingTimeInterval
import net.kuecker.lodestar.kit.foundation.formatFixed
import net.kuecker.lodestar.kit.foundation.roundedHalfAwayFromZero
import net.kuecker.lodestar.kit.foundation.timeIntervalSince
import java.time.Instant
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** The encoder angles of both axes in degrees, as read from the mount. */
data class SkyWatcherAxisAngles(
    /** The encoder angle of axis 1. */
    val axis1Degrees: Double,
    /** The encoder angle of axis 2. */
    val axis2Degrees: Double,
)

/**
 * Drives a Sky-Watcher Wi-Fi mount (AZ-GTi, AZ-GTe, Star Adventurer GTi, EQ mounts with the Wi-Fi dongle) through the motor
 * controller protocol on UDP port 11880 (see [SkyWatcherCodec]) with the [MountController]. The motor controller only knows
 * encoder counts and step periods, so the driver supplies what the hand controls normally do:
 *
 * - **Pointing model.** The documented home position (equatorial: counterweight down, telescope at the pole; alt-azimuth:
 *   level, pointing north) is the mount's zero. The user powers the mount up at home, connects, and syncs on 1 to 3 stars: one
 *   star finds the home position error, two add the polar axis (equatorial) or level (alt-azimuth) error, three add orthogonality
 *   and cone errors (see [SkyWatcherAlignmentSolver]; the residuals are in [alignmentFit]). [sync] adds an alignment point; goto is
 *   refused until the first one ([MountError.NotAligned]) unless [SkyWatcherConfiguration.allowsGotoWithoutAlignment].
 * - **GoTo.** The target (apparent place of date, [epoch]) is turned into axis angles through the model, for the position the sky
 *   will have when the slew ends; limits and the pier side are chosen first: equatorial mounts take the side that keeps the
 *   counterweight down (the side changes at the meridian, within [SkyWatcherLimits.meridianOverlapDegrees] the current side
 *   stays), alt-azimuth mounts unwrap the azimuth within the cable-wrap limits. Slews whose straight path would dip below the
 *   horizon are split into two legs. When the mount stops, the remaining error is corrected with short slews.
 * - **Tracking.** Equatorial mounts track at the sidereal rate on axis 1 alone. Alt-azimuth mounts have no tracking of their own:
 *   about every [SkyWatcherConfiguration.trackingUpdateIntervalSeconds] the driver computes new rates of both axes from the target's motion and
 *   its distance from the target. **This happens inside [readSlewing]: the app must keep polling** (the controller does that once
 *   per call of `poll`; poll at least once a second). When a limit is reached, tracking stops and [trackingStopReason] says why.
 *
 * Coordinates are in [epoch] = [MountEpoch.APPARENT_OF_DATE]. Refraction is not modelled. Not thread safe.
 */
class SkyWatcherDriver(
    transport: MountTransport,
    private val clock: MountClock,
    /** The settings in use (the [location] may be replaced by [setSiteAndTime]). */
    val configuration: SkyWatcherConfiguration,
) : MountDriver {
    private val link = SkyWatcherLink(transport, clock, configuration.attempts, configuration.exchangeTimeoutSeconds)

    /** The site of the mount. */
    var location: GeographicLocation = configuration.location
        private set

    /** The motor board version found by [connect]; null before. */
    var version: SkyWatcherVersion? = null
        private set

    /** The arrangement in use (from the configuration or the mount model); null before [connect]. */
    var mountMode: SkyWatcherMountMode? = null
        private set

    /** The tracking strategy in use; null before [connect]. */
    var trackingStrategy: SkyWatcherTrackingStrategy? = null
        private set

    /** The encoder angles at [connect]; far from 0 when the mount was not at its home position (or was moved before). */
    var startupAxisAngles: SkyWatcherAxisAngles? = null
        private set

    /** Why tracking stopped by itself (a limit was reached); cleared when tracking is switched on. */
    var trackingStopReason: MountError? = null
        private set

    /** The alignment points in use, oldest first. */
    var alignmentPoints: List<SkyWatcherAlignmentPoint> = emptyList()
        private set

    /** The fit of the model to [alignmentPoints]; null before the first sync. */
    var alignmentFit: SkyWatcherAlignmentFit? = null
        private set

    /** The datagrams sent so far, retries included (link statistics). */
    val datagramsSent: Int get() = link.datagramsSent

    /** How many datagrams were repetitions because no usable reply arrived (link statistics). */
    val retries: Int get() = link.retries

    override val kind: MountProtocol get() = MountProtocol.SKYWATCHER

    override val epoch: MountEpoch get() = MountEpoch.APPARENT_OF_DATE

    // MARK: Private state

    private enum class Motion { STOPPED, TRACKING, SLEWING, NUDGING }

    private class Leg(val axis1: Double?, val axis2: Double?)

    private class Slew(val goal: EquatorialCoordinate, var legs: MutableList<Leg>, var refinements: Int, var lastCommand: Instant)

    private class Nudge(
        val origin: EquatorialCoordinate,
        val start: Instant,
        val rightAscensionRate: Double,
        val declinationRate: Double,
        val deadline: Instant,
    )

    private class Reading(val axis1: Double, val axis2: Double, val counts1: Int, val counts2: Int, val time: Instant)

    private class Choice(val axis1: Double, val axis2: Double, val side: SkyWatcherPierSide)

    private var resolvedMode: SkyWatcherMountMode = configuration.mode ?: SkyWatcherMountMode.ALT_AZIMUTH
    private var resolvedStrategy: SkyWatcherTrackingStrategy = SkyWatcherTrackingStrategy.SERVO
    private var model: SkyWatcherPointingModel = SkyWatcherPointingModel.nominal(resolvedMode, configuration.location.latitude < 0)
    private val countsPerRevolution = IntArray(2)
    private val highSpeedRatio = IntArray(2)
    private var timerFrequency = 0
    private val statuses = arrayOfNulls<SkyWatcherAxisStatus>(2)
    private var motion = Motion.STOPPED
    private var trackingEnabled = false
    private var anchor: EquatorialCoordinate? = null
    private var slew: Slew? = null
    private var nudge: Nudge? = null
    private var lastReading: Reading? = null
    private var lastServo: Instant? = null
    private var pendingStop = false
    private var connected = false

    private val isSouthern: Boolean get() = location.latitude < 0

    /** The side of the mount at the last position reading (equatorial: counterweight side; alt-azimuth: over the top or not); null before the first reading. */
    val pierSide: SkyWatcherPierSide?
        get() = lastReading?.let { model.pierSide(it.axis2) }

    /** The pointing model in use (the nominal model before the first sync); null before [connect]. */
    val pointingModel: SkyWatcherPointingModel? get() = if (connected) model else null

    // MARK: Session

    @Throws(MountError::class)
    override fun connect(): MountCapabilities {
        connected = false
        val found = SkyWatcherCodec.decodeVersion(
            link.exchange(SkyWatcherCommand.INQUIRE_VERSION, SkyWatcherAxis.AXIS1, "", "read version"),
        )
        for (axis in SkyWatcherAxis.entries) {
            val cpr = SkyWatcherCodec.decode24(link.exchange(SkyWatcherCommand.INQUIRE_COUNTS_PER_REVOLUTION, axis, "", "read counts per revolution"))
            if (cpr < 1000) throw MountError.MalformedReply("counts per revolution")
            countsPerRevolution[axis.index] = cpr
        }
        val frequency = SkyWatcherCodec.decode24(link.exchange(SkyWatcherCommand.INQUIRE_TIMER_FREQUENCY, SkyWatcherAxis.AXIS1, "", "read timer frequency"))
        if (frequency <= 0) throw MountError.MalformedReply("timer frequency")
        timerFrequency = frequency
        for (axis in SkyWatcherAxis.entries) {
            highSpeedRatio[axis.index] =
                SkyWatcherCodec.decodeHighSpeedRatio(link.exchange(SkyWatcherCommand.INQUIRE_HIGH_SPEED_RATIO, axis, "", "read high-speed ratio"))
        }
        refreshStatuses()
        if (statuses.any { it?.isInitialized != true }) {
            for (axis in SkyWatcherAxis.entries) link.command(SkyWatcherCommand.INITIALIZATION_DONE, axis, "", "initialize")
            refreshStatuses()
            if (statuses.any { it?.isInitialized != true }) throw MountError.Rejected("the motor controller did not initialize")
        }
        if (statuses.any { it?.isRunning == true }) {
            stopAxes()
            waitUntilStopped()
        }

        version = found
        resolvedMode = configuration.mode ?: SkyWatcherMountCatalog.defaultMode(found.mountCode)
        mountMode = resolvedMode
        resolvedStrategy = if (resolvedMode == SkyWatcherMountMode.ALT_AZIMUTH) {
            SkyWatcherTrackingStrategy.SERVO
        } else {
            configuration.trackingStrategy ?: SkyWatcherTrackingStrategy.SIDEREAL
        }
        trackingStrategy = resolvedStrategy
        alignmentPoints = emptyList()
        alignmentFit = null
        model = SkyWatcherPointingModel.nominal(resolvedMode, isSouthern)
        motion = Motion.STOPPED
        trackingEnabled = false
        trackingStopReason = null
        anchor = null
        slew = null
        nudge = null
        lastServo = null
        pendingStop = false
        connected = true
        val start = readAxes()
        startupAxisAngles = SkyWatcherAxisAngles(start.axis1, start.axis2)
        return MountCapabilities(
            kind = MountProtocol.SKYWATCHER,
            name = "Sky-Watcher ${found.mountName} ${found.description}",
            firmware = found.description,
            canSlew = true,
            canSync = true,
            canAbort = true,
            canSetTracking = true,
            canNudge = true,
            canSetSiteAndTime = true,
            gotoNeedsAlignment = !configuration.allowsGotoWithoutAlignment,
            isAligned = false,
        )
    }

    /**
     * Ends the session. Servo tracking (alt-azimuth mounts) is stopped, because the mount would otherwise keep turning at the last
     * rate; a sidereal tracking equatorial mount keeps tracking, and a running slew is left to finish.
     */
    override fun disconnect() {
        if (!connected) return
        connected = false
        if (motion == Motion.TRACKING && resolvedStrategy == SkyWatcherTrackingStrategy.SERVO) {
            try {
                stopAxes()
            } catch (error: MountError) {
                // Best effort: the link is probably gone.
            }
        }
        motion = Motion.STOPPED
    }

    // MARK: Reading

    @Throws(MountError::class)
    override fun readPosition(): EquatorialCoordinate {
        requireConnected()
        val reading = readAxes()
        checkSiderealLimits(reading)
        return pointing(reading)
    }

    @Throws(MountError::class)
    override fun readSlewing(): Boolean {
        requireConnected()
        refreshStatuses()
        serviceMotion()
        if (motion == Motion.SLEWING) return true
        return statuses.any { it?.isGoto == true }
    }

    @Throws(MountError::class)
    override fun readTracking(): Boolean? {
        requireConnected()
        return trackingEnabled
    }

    @Throws(MountError::class)
    override fun readAligned(): Boolean = alignmentPoints.isNotEmpty()

    // MARK: Slew, sync, abort

    @Throws(MountError::class)
    override fun slew(target: EquatorialCoordinate) {
        requireConnected()
        requireFinite(target)
        if (!configuration.allowsGotoWithoutAlignment && alignmentPoints.isEmpty()) throw MountError.NotAligned
        if (motion == Motion.NUDGING) endNudge()
        val start = readAxes()
        val legs = planSlew(target, start)
        trackingEnabled = true
        trackingStopReason = null
        stopAxes()
        waitUntilStopped()
        motion = Motion.SLEWING
        anchor = target
        val state = Slew(target, legs.toMutableList(), 0, clock.now())
        slew = state
        try {
            val first = state.legs.removeAt(0)
            startLeg(first)
            state.lastCommand = clock.now()
        } catch (error: MountError) {
            slew = null
            motion = Motion.STOPPED
            throw error
        }
    }

    @Throws(MountError::class)
    override fun sync(target: EquatorialCoordinate) {
        requireConnected()
        requireFinite(target)
        val reading = readAxes()
        val direction = SkyWatcherSky.localDirection(target, lst(reading.time), location.latitude, resolvedMode)
        val point = SkyWatcherAlignmentPoint(reading.axis1, reading.axis2, direction, reading.time)
        val updated = ArrayList<SkyWatcherAlignmentPoint>()
        for (existing in alignmentPoints) {
            // Syncing the same star again replaces its earlier point (the local direction of a star changes with the time of the sync).
            val earlier = SkyWatcherSky.apparentPlace(existing.direction, lst(existing.timestamp), location.latitude, resolvedMode)
            if (earlier.angularSeparation(target) >= duplicateSeparationDegrees) updated.add(existing)
        }
        updated.add(point)
        while (updated.size > SkyWatcherAlignmentSolver.maximumPoints) updated.removeAt(0)
        val fit = SkyWatcherAlignmentSolver.solve(updated, resolvedMode, isSouthern)
        if (fit.maximumResidualDegrees > configuration.maximumAlignmentResidualDegrees) {
            throw MountError.Rejected(
                "the alignment would leave ${formatDegrees(fit.maximumResidualDegrees)} of error on a star; check that you centred the right star",
            )
        }
        if (!fit.isPlausible) {
            throw MountError.Rejected(
                "the alignment would need larger corrections than a mount set up by hand can have; check that you centred the right star and that the mount started at its home position",
            )
        }
        alignmentPoints = updated
        alignmentFit = fit
        model = fit.model
        if (motion == Motion.TRACKING) anchor = target
    }

    @Throws(MountError::class)
    override fun abort() {
        requireConnected()
        stopAxes()
        slew = null
        nudge = null
        motion = Motion.STOPPED
        pendingStop = false
    }

    // MARK: Tracking and nudges

    @Throws(MountError::class)
    override fun setTracking(enabled: Boolean) {
        requireConnected()
        if (enabled) {
            trackingEnabled = true
            trackingStopReason = null
            if (motion == Motion.STOPPED) {
                refreshStatuses()
                val refused = startTracking()
                if (refused != null) throw refused
            }
        } else {
            stopAxes()
            trackingEnabled = false
            if (motion == Motion.TRACKING) motion = Motion.STOPPED
        }
    }

    @Throws(MountError::class)
    override fun startNudge(direction: MountNudgeDirection, rate: MountNudgeRate, durationSeconds: Double) {
        requireConnected()
        if (motion == Motion.SLEWING) throw MountError.SlewInProgress
        if (!(durationSeconds > 0) || !durationSeconds.isFinite()) throw MountError.InvalidInput("the nudge duration")
        refreshStatuses()
        val reading = readAxes()
        val origin = pointing(reading)
        val speed = rate.degreesPerSecond
        var rightAscensionRate = 0.0
        var declinationRate = 0.0
        when (direction) {
            MountNudgeDirection.NORTH -> declinationRate = speed
            MountNudgeDirection.SOUTH -> declinationRate = -speed
            MountNudgeDirection.EAST -> rightAscensionRate = speed / max(AstroMath.cosDeg(origin.declination), minimumCosine)
            MountNudgeDirection.WEST -> rightAscensionRate = -speed / max(AstroMath.cosDeg(origin.declination), minimumCosine)
        }
        nudge = Nudge(origin, reading.time, rightAscensionRate, declinationRate, reading.time.addingTimeInterval(durationSeconds + nudgeGraceSeconds))
        motion = Motion.NUDGING
        servoStep(reading, nudgeLeadSeconds) { at -> nudgeAnchor(at) }
        lastServo = clock.now()
    }

    @Throws(MountError::class)
    override fun stopNudge(direction: MountNudgeDirection) {
        requireConnected()
        if (motion == Motion.NUDGING) endNudge()
    }

    @Throws(MountError::class)
    override fun setSiteAndTime(location: GeographicLocation, date: Instant) {
        requireConnected()
        val previous = this.location
        this.location = location
        val moved = abs(location.latitude - previous.latitude) > siteChangeDegrees ||
            abs(AstroMath.normalizeSignedDegrees(location.longitude - previous.longitude)) > siteChangeDegrees
        if ((previous.latitude < 0) != (location.latitude < 0) || moved) {
            clearAlignment()
        } else {
            model = SkyWatcherPointingModel(resolvedMode, isSouthern, model.errors)
        }
    }

    /**
     * Declares that the mount stands at its home position now (counterweight down and pointing at the pole, or level and pointing
     * north): stops the axes, sets both position counts to zero (`E`), forgets the alignment and the tracking. For a mount that was not
     * at home when the session began ([startupAxisAngles] far from zero) and was then brought home with the nudge pad, without
     * switching the power off.
     */
    @Throws(MountError::class)
    fun declareHomePosition() {
        requireConnected()
        stopAxes()
        waitUntilStopped()
        for (axis in SkyWatcherAxis.entries) {
            link.command(SkyWatcherCommand.SET_POSITION, axis, SkyWatcherCodec.encodePosition(0), "set position")
        }
        slew = null
        nudge = null
        anchor = null
        motion = Motion.STOPPED
        trackingEnabled = false
        trackingStopReason = null
        pendingStop = false
        lastServo = null
        clearAlignment()
        val reading = readAxes()
        startupAxisAngles = SkyWatcherAxisAngles(reading.axis1, reading.axis2)
    }

    // MARK: Alignment

    /** Forgets all alignment points; the model returns to the ideal geometry (home position assumed). */
    fun clearAlignment() {
        alignmentPoints = emptyList()
        alignmentFit = null
        model = SkyWatcherPointingModel.nominal(resolvedMode, isSouthern)
    }

    /** Removes the alignment point at [index] and fits the model to the rest. */
    @Throws(MountError::class)
    fun removeAlignmentPoint(index: Int) {
        if (index < 0 || index >= alignmentPoints.size) throw MountError.InvalidInput("there is no alignment point $index")
        val rest = ArrayList(alignmentPoints)
        rest.removeAt(index)
        if (rest.isEmpty()) {
            clearAlignment()
            return
        }
        val fit = SkyWatcherAlignmentSolver.solve(rest, resolvedMode, isSouthern)
        alignmentPoints = rest
        alignmentFit = fit
        model = fit.model
    }

    /**
     * Replaces the alignment with [points] (for an app that kept them from an earlier session with the mount powered on all the
     * time: the points refer to the encoder counts since power-up).
     */
    @Throws(MountError::class)
    fun restoreAlignment(points: List<SkyWatcherAlignmentPoint>) {
        val fit = SkyWatcherAlignmentSolver.solve(points, resolvedMode, isSouthern)
        alignmentPoints = ArrayList(points)
        alignmentFit = fit
        model = fit.model
    }

    // MARK: Axis helpers

    private fun requireConnected() {
        if (!connected) throw MountError.NotConnected
    }

    private fun requireFinite(target: EquatorialCoordinate) {
        if (!target.rightAscension.isFinite() || !target.declination.isFinite()) {
            throw MountError.InvalidInput("the target coordinates are not numbers")
        }
    }

    private fun sense(axis: SkyWatcherAxis): Double {
        val inverted = if (axis == SkyWatcherAxis.AXIS1) configuration.invertsAxis1 else configuration.invertsAxis2
        return if (inverted) -1.0 else 1.0
    }

    private fun degrees(axis: SkyWatcherAxis, counts: Int): Double =
        sense(axis) * counts.toDouble() * 360.0 / countsPerRevolution[axis.index].toDouble()

    private fun counts(axis: SkyWatcherAxis, degrees: Double): Int =
        (sense(axis) * degrees * countsPerRevolution[axis.index].toDouble() / 360.0).roundedHalfAwayFromZero().toInt()

    @Throws(MountError::class)
    private fun readCounts(axis: SkyWatcherAxis): Int =
        SkyWatcherCodec.decodePosition(link.exchange(SkyWatcherCommand.INQUIRE_POSITION, axis, "", "read position"))

    @Throws(MountError::class)
    private fun readAxes(): Reading {
        val before = clock.now()
        val counts1 = readCounts(SkyWatcherAxis.AXIS1)
        val counts2 = readCounts(SkyWatcherAxis.AXIS2)
        val after = clock.now()
        val time = before.addingTimeInterval(after.timeIntervalSince(before) / 2)
        val reading = Reading(
            degrees(SkyWatcherAxis.AXIS1, counts1), degrees(SkyWatcherAxis.AXIS2, counts2), counts1, counts2, time,
        )
        lastReading = reading
        return reading
    }

    @Throws(MountError::class)
    private fun readStatus(axis: SkyWatcherAxis): SkyWatcherAxisStatus {
        val status = SkyWatcherCodec.decodeStatus(link.exchange(SkyWatcherCommand.INQUIRE_STATUS, axis, "", "read status"))
        statuses[axis.index] = status
        return status
    }

    @Throws(MountError::class)
    private fun refreshStatuses() {
        readStatus(SkyWatcherAxis.AXIS1)
        readStatus(SkyWatcherAxis.AXIS2)
    }

    @Throws(MountError::class)
    private fun stopAxes() {
        for (axis in SkyWatcherAxis.entries) link.command(SkyWatcherCommand.STOP_MOTION, axis, "", "stop")
    }

    @Throws(MountError::class)
    private fun waitUntilStopped() {
        for (iteration in 0 until stopPolls) {
            refreshStatuses()
            if (statuses.none { it?.isRunning == true }) return
            link.pause(stopPollSeconds)
        }
        throw MountError.Busy
    }

    @Throws(MountError::class)
    private fun stopAxis(axis: SkyWatcherAxis) {
        link.command(SkyWatcherCommand.STOP_MOTION, axis, "", "stop")
        for (iteration in 0 until stopPolls) {
            if (!readStatus(axis).isRunning) return
            link.pause(stopPollSeconds)
        }
        throw MountError.Busy
    }

    /**
     * Starts the motion set up for [axis]. The command is not repeated blindly: if its reply is lost, the axis' status says whether
     * the start happened. For a GOTO to [gotoTarget] counts the position is checked as well, because a short GOTO may already be
     * over (the axis is then back in speed mode, and starting it again would send it off at the speed of its last step period).
     */
    @Throws(MountError::class)
    private fun startAxis(axis: SkyWatcherAxis, gotoTarget: Int? = null) {
        var last: MountError? = null
        for (attempt in 0 until 3) {
            try {
                link.command(SkyWatcherCommand.START_MOTION, axis, "", "start motion", maximumAttempts = 1)
                return
            } catch (error: MountError.Timeout) {
                last = error
            } catch (error: MountError.MalformedReply) {
                last = error
            }
            // The start may have been executed although its reply was lost: look at the axis before sending it again.
            if (readStatus(axis).isRunning) return
            if (gotoTarget != null && abs(readCounts(axis) - gotoTarget) <= completedCounts) return
        }
        throw last ?: MountError.Timeout("start motion")
    }

    // MARK: Sky helpers

    private fun lst(at: Instant): Double = SkyFrame(at, Observer(location)).localApparentSiderealTime

    private fun pointing(reading: Reading): EquatorialCoordinate =
        SkyWatcherSky.apparentPlace(model.direction(reading.axis1, reading.axis2), lst(reading.time), location.latitude, resolvedMode)

    private fun formatDegrees(value: Double): String = "${formatFixed(value, 2)}°"

    // MARK: Choosing axis angles

    /** The axis angles for [place] at [at], within the limits; throws the mount's limit errors. */
    @Throws(MountError::class)
    private fun choose(place: EquatorialCoordinate, at: Instant, near: Reading, keepSide: Boolean): Choice {
        val limits = configuration.limits
        val sidereal = lst(at)
        val altitude = SkyWatcherSky.altitude(place, sidereal, location.latitude)
        if (altitude < limits.minimumAltitudeDegrees) throw MountError.MountHorizonLimit
        if (resolvedMode == SkyWatcherMountMode.ALT_AZIMUTH && altitude > limits.maximumAltitudeDegrees) throw MountError.MountOverheadLimit
        val direction = SkyWatcherSky.localDirection(place, sidereal, location.latitude, resolvedMode)
        val solutions = model.solutions(direction, near.axis1)
        if (resolvedMode == SkyWatcherMountMode.ALT_AZIMUTH) {
            val normal = solutions.firstOrNull { it.pierSide == SkyWatcherPierSide.EAST } ?: throw MountError.OutsideMountLimits
            var best: Double? = null
            for (turns in -2..2) {
                val candidate = normal.axis1 + 360.0 * turns
                if (candidate < limits.azimuthMinimumDegrees || candidate > limits.azimuthMaximumDegrees) continue
                val current = best
                if (current == null || abs(candidate - near.axis1) < abs(current - near.axis1)) best = candidate
            }
            val chosen = best ?: throw MountError.OutsideMountLimits
            return Choice(chosen, normal.axis2, SkyWatcherPierSide.EAST)
        }
        val currentSide = model.pierSide(near.axis2)
        val limit = 90.0 + limits.meridianOverlapDegrees
        val valid = ArrayList<SkyWatcherAxisSolution>()
        for (solution in solutions) {
            if (abs(model.modelAxis1(solution.axis1)) > limit) continue
            if (keepSide && solution.pierSide != currentSide) continue
            valid.add(solution)
        }
        if (valid.isEmpty()) throw MountError.OutsideMountLimits
        var pick = valid[0]
        if (valid.size > 1) {
            val same = valid.firstOrNull { it.pierSide == currentSide }
            if (same != null) {
                pick = same
            } else if (abs(model.modelAxis1(valid[1].axis1)) < abs(model.modelAxis1(valid[0].axis1))) {
                pick = valid[1]
            }
        }
        return Choice(pick.axis1, pick.axis2, pick.pierSide)
    }

    private fun estimatedSeconds(distanceDegrees: Double): Double =
        distanceDegrees / configuration.assumedSlewDegreesPerSecond + slewOverheadSeconds

    /** True if pointing along the straight path of both axes at equal speed stays above the tripod clearance (equatorial mounts). */
    private fun pathClears(from1: Double, from2: Double, to1: Double, to2: Double): Boolean {
        if (resolvedMode != SkyWatcherMountMode.EQUATORIAL) return true
        val delta1 = to1 - from1
        val delta2 = to2 - from2
        val total = max(abs(delta1), abs(delta2))
        if (total < 1.0) return true
        val floor = configuration.limits.minimumAltitudeDegrees - configuration.limits.pathMarginDegrees
        val sinLatitude = AstroMath.sinDeg(location.latitude)
        val cosLatitude = AstroMath.cosDeg(location.latitude)
        for (index in 0..pathSamples) {
            val travelled = total * index.toDouble() / pathSamples.toDouble()
            val position1 = from1 + (if (delta1 >= 0) 1.0 else -1.0) * min(travelled, abs(delta1))
            val position2 = from2 + (if (delta2 >= 0) 1.0 else -1.0) * min(travelled, abs(delta2))
            val direction = model.direction(position1, position2)
            val altitude = AstroMath.asinDeg(direction.x * cosLatitude + direction.z * sinLatitude)
            if (altitude < floor) return false
        }
        return true
    }

    @Throws(MountError::class)
    private fun planSlew(goal: EquatorialCoordinate, start: Reading): List<Leg> {
        var arrival = start.time
        var choice: Choice? = null
        for (pass in 0 until predictionPasses) {
            val chosen = choose(goal, arrival, start, false)
            choice = chosen
            val distance = max(abs(chosen.axis1 - start.axis1), abs(chosen.axis2 - start.axis2))
            arrival = start.time.addingTimeInterval(estimatedSeconds(distance))
        }
        val final = choice ?: throw MountError.OutsideMountLimits
        if (pathClears(start.axis1, start.axis2, final.axis1, final.axis2)) return listOf(Leg(final.axis1, final.axis2))
        if (pathClears(start.axis1, start.axis2, start.axis1, final.axis2) &&
            pathClears(start.axis1, final.axis2, final.axis1, final.axis2)
        ) {
            return listOf(Leg(null, final.axis2), Leg(final.axis1, null))
        }
        if (pathClears(start.axis1, start.axis2, final.axis1, start.axis2) &&
            pathClears(final.axis1, start.axis2, final.axis1, final.axis2)
        ) {
            return listOf(Leg(final.axis1, null), Leg(null, final.axis2))
        }
        throw MountError.OutsideMountLimits
    }

    // MARK: Motion

    private fun lowSpeedGotoMargin(axis: SkyWatcherAxis): Int =
        (640.0 * countsPerRevolution[axis.index].toDouble() / siderealSecondsPerRevolution).toInt()

    /** Starts the goto of the axes named in [leg]; both axes must be stopped. */
    @Throws(MountError::class)
    private fun startLeg(leg: Leg): Int {
        var started = 0
        val targets = listOf(leg.axis1, leg.axis2)
        for (axis in SkyWatcherAxis.entries) {
            val target = targets[axis.index] ?: continue
            if (startAxisGoto(axis, target)) started += 1
        }
        return started
    }

    @Throws(MountError::class)
    private fun startAxisGoto(axis: SkyWatcherAxis, targetDegrees: Double): Boolean {
        val current = readCounts(axis)
        val targetCounts = counts(axis, targetDegrees)
        if (targetCounts < -maximumCount || targetCounts > maximumCount) throw MountError.OutsideMountLimits
        val delta = targetCounts - current
        if (abs(delta) <= completedCounts) return false
        val magnitude = abs(delta)
        val direction = if (delta > 0) SkyWatcherDirection.INCREASING else SkyWatcherDirection.DECREASING
        val fast = magnitude > lowSpeedGotoMargin(axis)
        val mode = if (fast) SkyWatcherMotionMode.GOTO_FAST else SkyWatcherMotionMode.GOTO_SLOW
        link.command(SkyWatcherCommand.SET_MOTION_MODE, axis, "${mode.code}${direction.code}", "set motion mode")
        if (configuration.usesAbsoluteGotoTarget) {
            link.command(SkyWatcherCommand.SET_GOTO_TARGET, axis, SkyWatcherCodec.encodePosition(targetCounts), "set target")
        } else {
            link.command(SkyWatcherCommand.SET_GOTO_TARGET_INCREMENT, axis, SkyWatcherCodec.encode24(magnitude), "set target")
            val brake = min(magnitude, if (fast) highSpeedBrakeCounts else lowSpeedBrakeCounts)
            link.command(SkyWatcherCommand.SET_BRAKE_POINT_INCREMENT, axis, SkyWatcherCodec.encode24(brake), "set brake point")
        }
        startAxis(axis, targetCounts)
        return true
    }

    /** Runs [axis] in speed mode at [degreesPerSecond] (signed, in the direction of increasing encoder angle); stops it if that is too slow to represent. */
    @Throws(MountError::class)
    private fun setAxisRate(axis: SkyWatcherAxis, degreesPerSecond: Double) {
        val index = axis.index
        val countRate = degreesPerSecond * sense(axis)
        val speed = SkyWatcherCodec.speed(countRate, countsPerRevolution[index], timerFrequency, highSpeedRatio[index])
        if (speed == null) {
            if (statuses[index]?.isRunning != false) stopAxis(axis)
            return
        }
        val direction = if (countRate > 0) SkyWatcherDirection.INCREASING else SkyWatcherDirection.DECREASING
        val status = statuses[index]
        val reusable = status != null && status.isRunning && status.isSpeedMode && !status.isFast && !speed.isHighSpeed &&
            status.isDecreasing == (direction == SkyWatcherDirection.DECREASING)
        if (reusable) {
            link.command(SkyWatcherCommand.SET_STEP_PERIOD, axis, SkyWatcherCodec.encode24(speed.period), "set speed")
            return
        }
        if (status == null || status.isRunning) stopAxis(axis)
        val mode = if (speed.isHighSpeed) SkyWatcherMotionMode.TRACK_FAST else SkyWatcherMotionMode.TRACK_SLOW
        link.command(SkyWatcherCommand.SET_MOTION_MODE, axis, "${mode.code}${direction.code}", "set motion mode")
        link.command(SkyWatcherCommand.SET_STEP_PERIOD, axis, SkyWatcherCodec.encode24(speed.period), "set speed")
        startAxis(axis)
        statuses[index] = SkyWatcherAxisStatus(
            isSpeedMode = true,
            isDecreasing = direction == SkyWatcherDirection.DECREASING,
            isFast = speed.isHighSpeed,
            isRunning = true,
            isBlocked = false,
            isInitialized = true,
            isLevelSwitchOn = false,
        )
    }

    private fun siderealAxis1Rate(): Double =
        (if (isSouthern) -1.0 else 1.0) * SkyWatcherSky.siderealDegreesPerSecond

    /** Starts tracking from the position the mount has now (both axes stopped or running in speed mode). */
    @Throws(MountError::class)
    private fun startTracking(): MountError? {
        val reading = readAxes()
        anchor = pointing(reading)
        motion = Motion.TRACKING
        lastServo = null
        return applyTracking(reading)
    }

    /**
     * Sets the axes to track. Returns the limit error that makes tracking impossible here (tracking is then switched off and
     * [trackingStopReason] says why); communication errors are thrown.
     */
    @Throws(MountError::class)
    private fun applyTracking(reading: Reading): MountError? {
        try {
            if (resolvedStrategy == SkyWatcherTrackingStrategy.SIDEREAL) {
                setAxisRate(SkyWatcherAxis.AXIS1, siderealAxis1Rate())
                if (statuses[1]?.isRunning != false) stopAxis(SkyWatcherAxis.AXIS2)
            } else {
                val place = anchor ?: pointing(reading)
                servoStep(reading, trackingLead()) { place }
                lastServo = clock.now()
            }
            return null
        } catch (error: MountError) {
            if (error.isCommunicationFailure) throw error
            stopTracking(error)
            return error
        }
    }

    private fun trackingLead(): Double = configuration.trackingUpdateIntervalSeconds + trackingLeadExtraSeconds

    private fun nudgeAnchor(at: Instant): EquatorialCoordinate {
        val state = nudge ?: return anchor ?: EquatorialCoordinate(0.0, 0.0)
        val elapsed = at.timeIntervalSince(state.start)
        return EquatorialCoordinate(
            state.origin.rightAscension + state.rightAscensionRate * elapsed,
            AstroMath.clamp(state.origin.declination + state.declinationRate * elapsed, -maximumDeclination, maximumDeclination),
        )
    }

    /**
     * Sets the speeds of both axes so that the mount reaches the place [anchorAt] gives for the time [lead] seconds after [reading].
     * Closed loop: the rate includes the correction of the distance to where the anchor is now.
     */
    @Throws(MountError::class)
    private fun servoStep(reading: Reading, lead: Double, anchorAt: (Instant) -> EquatorialCoordinate) {
        val at = reading.time.addingTimeInterval(lead)
        val wanted = choose(anchorAt(at), at, reading, true)
        val limit = configuration.limits.maximumAxisRateDegreesPerSecond
        var rate1 = (wanted.axis1 - reading.axis1) / lead
        var rate2 = (wanted.axis2 - reading.axis2) / lead
        if (abs(rate1) > limit * trackingRateSlack || abs(rate2) > limit * trackingRateSlack) {
            if (motion == Motion.TRACKING) throw MountError.OutsideMountLimits
        }
        rate1 = AstroMath.clamp(rate1, -limit, limit)
        rate2 = AstroMath.clamp(rate2, -limit, limit)
        setAxisRate(SkyWatcherAxis.AXIS1, rate1)
        setAxisRate(SkyWatcherAxis.AXIS2, rate2)
    }

    @Throws(MountError::class)
    private fun endNudge() {
        nudge = null
        refreshStatuses()
        val reading = readAxes()
        anchor = pointing(reading)
        if (trackingEnabled) {
            motion = Motion.TRACKING
            lastServo = null
            applyTracking(reading)
        } else {
            motion = Motion.STOPPED
            stopAxes()
            waitUntilStopped()
        }
    }

    private fun stopTracking(reason: MountError) {
        trackingEnabled = false
        trackingStopReason = reason
        motion = Motion.STOPPED
        pendingStop = true
    }

    /** Equatorial sidereal tracking: stops it when the counterweight would rise above the limit. */
    private fun checkSiderealLimits(reading: Reading) {
        if (motion != Motion.TRACKING || resolvedMode != SkyWatcherMountMode.EQUATORIAL) return
        if (abs(model.modelAxis1(reading.axis1)) > 90.0 + configuration.limits.meridianOverlapDegrees + limitSlackDegrees) {
            stopTracking(MountError.OutsideMountLimits)
        }
    }

    // MARK: Service (called by readSlewing, once per poll)

    @Throws(MountError::class)
    private fun serviceMotion() {
        if (pendingStop) {
            stopAxes()
            pendingStop = false
        }
        val now = clock.now()
        when (motion) {
            Motion.STOPPED -> if (trackingEnabled) startTracking()
            Motion.SLEWING -> serviceSlew(now)
            Motion.TRACKING -> serviceTracking(now)
            Motion.NUDGING -> serviceNudge(now)
        }
    }

    @Throws(MountError::class)
    private fun serviceSlew(now: Instant) {
        val state = slew ?: run {
            motion = Motion.STOPPED
            return
        }
        if (statuses.any { it?.isGoto == true }) return
        if (statuses.any { it?.isRunning == true }) {
            // An axis runs in speed mode in the middle of a slew (a start that was repeated after a short GOTO had ended): stop it.
            stopAxes()
            return
        }
        if (now.timeIntervalSince(state.lastCommand) < settleSeconds) return
        if (state.legs.isNotEmpty()) {
            val next = state.legs.removeAt(0)
            val started = startLeg(next)
            state.lastCommand = if (started > 0) clock.now() else now.addingTimeInterval(-settleSeconds)
            return
        }
        val reading = readAxes()
        val error = pointing(reading).angularSeparation(state.goal)
        if (error > configuration.refinementToleranceDegrees && state.refinements < maximumRefinements) {
            state.refinements += 1
            try {
                val at = reading.time.addingTimeInterval(refinementLeadSeconds)
                val wanted = choose(state.goal, at, reading, true)
                val started = startLeg(Leg(wanted.axis1, wanted.axis2))
                state.lastCommand = if (started > 0) clock.now() else now.addingTimeInterval(-settleSeconds)
                return
            } catch (limit: MountError) {
                if (limit.isCommunicationFailure) throw limit
            }
        }
        slew = null
        anchor = state.goal
        if (trackingEnabled) {
            motion = Motion.TRACKING
            lastServo = null
            applyTracking(reading)
        } else {
            motion = Motion.STOPPED
        }
    }

    @Throws(MountError::class)
    private fun serviceTracking(now: Instant) {
        if (resolvedStrategy == SkyWatcherTrackingStrategy.SIDEREAL) {
            val status1 = statuses[0]
            val expectedDecreasing = (siderealAxis1Rate() * sense(SkyWatcherAxis.AXIS1)) < 0
            val healthy = status1 != null && status1.isRunning && status1.isSpeedMode && status1.isDecreasing == expectedDecreasing
            if (!healthy || statuses[1]?.isRunning == true) applyTracking(readAxes())
            return
        }
        val last = lastServo
        if (last != null && now.timeIntervalSince(last) < configuration.trackingUpdateIntervalSeconds) return
        val reading = readAxes()
        val place = anchor ?: pointing(reading)
        try {
            servoStep(reading, trackingLead()) { place }
            lastServo = clock.now()
        } catch (limit: MountError) {
            if (limit.isCommunicationFailure) throw limit
            stopTracking(limit)
            stopAxes()
            pendingStop = false
        }
    }

    @Throws(MountError::class)
    private fun serviceNudge(now: Instant) {
        val state = nudge ?: run {
            motion = Motion.STOPPED
            return
        }
        if (now >= state.deadline) {
            endNudge()
            return
        }
        val last = lastServo
        if (last != null && now.timeIntervalSince(last) < nudgeUpdateSeconds) return
        val reading = readAxes()
        try {
            servoStep(reading, nudgeLeadSeconds) { at -> nudgeAnchor(at) }
            lastServo = clock.now()
        } catch (limit: MountError) {
            if (limit.isCommunicationFailure) throw limit
            // The nudge has reached a limit of the mount: end it here and go on tracking from where the mount is.
            endNudge()
        }
    }

    companion object {
        /** The name of this connection type for menus. */
        const val menuTitle: String = "Sky-Watcher Wi-Fi (AZ-GTi, Star Adventurer GTi)"

        private const val duplicateSeparationDegrees: Double = 0.5
        private const val siteChangeDegrees: Double = 0.25
        private const val minimumCosine: Double = 0.05
        private const val maximumDeclination: Double = 89.5
        private const val nudgeGraceSeconds: Double = 2.0
        private const val nudgeLeadSeconds: Double = 0.5
        private const val nudgeUpdateSeconds: Double = 0.5
        private const val trackingLeadExtraSeconds: Double = 0.5
        private const val trackingRateSlack: Double = 1.5
        private const val limitSlackDegrees: Double = 0.25
        private const val slewOverheadSeconds: Double = 1.5
        private const val refinementLeadSeconds: Double = 1.5
        private const val settleSeconds: Double = 1.0
        private const val maximumRefinements: Int = 3
        private const val predictionPasses: Int = 3
        private const val pathSamples: Int = 24
        private const val completedCounts: Int = 3
        private const val stopPolls: Int = 40
        private const val stopPollSeconds: Double = 0.1
        private const val maximumCount: Int = 0x7FFFF0
        private const val highSpeedBrakeCounts: Int = 3200
        private const val lowSpeedBrakeCounts: Int = 200
        private const val siderealSecondsPerRevolution: Double = 86164.09065
    }
}
