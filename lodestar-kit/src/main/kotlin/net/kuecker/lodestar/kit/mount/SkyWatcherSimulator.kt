package net.kuecker.lodestar.kit.mount

import net.kuecker.lodestar.kit.foundation.AstroMath
import net.kuecker.lodestar.kit.foundation.EquatorialCoordinate
import net.kuecker.lodestar.kit.foundation.GeographicLocation
import net.kuecker.lodestar.kit.foundation.Matrix3
import net.kuecker.lodestar.kit.foundation.Observer
import net.kuecker.lodestar.kit.foundation.SkyFrame
import net.kuecker.lodestar.kit.foundation.Vector3
import net.kuecker.lodestar.kit.foundation.instantFromTimeIntervalSince1970
import net.kuecker.lodestar.kit.foundation.roundedHalfAwayFromZero
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A deterministic xorshift64* generator, identical in Swift and Kotlin, for the random faults of the simulators.
 */
class SkyWatcherSimulatedRandom(seed: Long) {
    private var state: Long = if (seed == 0L) 0x2545F4914F6CDD1DL else seed

    /** The next value in [0, 1). */
    fun next(): Double {
        state = state xor (state ushr 12)
        state = state xor (state shl 25)
        state = state xor (state ushr 27)
        val mixed = state * 0x2545F4914F6CDD1DL
        return (mixed ushr 11).toDouble() / 9007199254740992.0
    }
}

/**
 * A Sky-Watcher motor controller behind a [MountTransport], for tests, UI previews and the demo mode: two axes with encoders that
 * turn at finite speeds, the controller's commands and error replies, and a mount whose real geometry has the [errors] that an alignment
 * has to find. It answers every command of [SkyWatcherCodec] like the firmware (`=` data, `!` code), requires `F` before motion
 * commands, accepts speed mode (step period, optionally high speed), GOTO by increment or by absolute target with acceleration and braking,
 * stops with `K` (deceleration) and `L` (instant), and returns an axis to speed mode when it stops.
 *
 * Faults: the base class's [inject] drops or damages the reply to a command (the label is the letter and channel, e.g. `j1`);
 * [requestLossProbability], [replyLossProbability] and [garbleProbability] add random datagram loss and damage from a seeded
 * generator. Time is the shared [ManualMountClock]; every reply costs the latency, a missing reply costs the caller's timeout.
 *
 * The true pointing of the simulated mount is [truePointing] (a direction in the local frame of [SkyWatcherPointingModel]) and
 * [truePlace] (an apparent place of date): what the camera really sees, as opposed to what the driver believes.
 */
class SkyWatcherSimulator(
    clock: ManualMountClock,
    /** Where the simulated mount stands. */
    val location: GeographicLocation,
    /** How the axes are arranged. */
    val mode: SkyWatcherMountMode = SkyWatcherMountMode.ALT_AZIMUTH,
    /** The real errors of the mount (home position error, base tilt, orthogonality, cone). */
    val errors: SkyWatcherPointingErrors = SkyWatcherPointingErrors(),
    /** The motor board version it reports. */
    val version: SkyWatcherVersion = SkyWatcherVersion(3, 0x22, 0xA5),
    /** Counts per revolution of both axes. */
    val countsPerRevolution: Int = 9_216_000,
    /** The step timer frequency. */
    val timerFrequency: Int = 1_500_000,
    /** The high-speed ratio. */
    val highSpeedRatio: Int = 32,
    /** The fastest the axes turn. */
    val maximumSlewDegreesPerSecond: Double = 3.0,
    /** How fast the axes accelerate and brake. */
    val accelerationDegreesPerSecondSquared: Double = 6.0,
    /** The constant speed of low-speed GOTOs. */
    val lowSpeedGotoDegreesPerSecond: Double = 0.5,
    /** True if the position count of axis 1 decreases when the axis turns in the positive direction. */
    val axis1Inverted: Boolean = false,
    /** True if the position count of axis 2 decreases when the axis turns in the positive direction. */
    val axis2Inverted: Boolean = false,
    latencySeconds: Double = 0.05,
    chunkSize: Int = 0,
    seed: Long = 1,
) : MountSimulatedStreamTransport(clock, latencySeconds, chunkSize) {
    private enum class Phase { STOPPED, SPEED, GOTO, BRAKING }

    private class SimulatedAxis {
        var position: Double = 0.0
        var velocity: Double = 0.0
        var phase: Phase = Phase.STOPPED
        var modeCode: Char = '1'
        var directionCode: Char = '0'
        var period: Int = 0
        var increment: Int = 0
        var brake: Int = 0
        var target: Double = 0.0
        var absoluteTarget: Int? = null
        var gotoFast: Boolean = false
        var initialized: Boolean = false
        var lastFast: Boolean = false
    }

    private val axes = arrayOf(SimulatedAxis(), SimulatedAxis())

    // The counts that the encoders lack compared to the physical position, after the position was set with `E`.
    private val encoderShift = doubleArrayOf(0.0, 0.0)
    private var lastTime: Double = clock.secondsSince1970
    private val random = SkyWatcherSimulatedRandom(seed)
    private var sleeping = false

    /** The chance that a command is lost before the controller sees it (0 to 1). */
    var requestLossProbability: Double = 0.0

    /** The chance that the reply to a command is lost (0 to 1). */
    var replyLossProbability: Double = 0.0

    /** The chance that a reply is damaged: a bad character, cut short, a stray byte in front, or sent twice (0 to 1). */
    var garbleProbability: Double = 0.0

    /** The number of GOTOs started. */
    var gotosStarted: Int = 0
        private set

    /** The number of speed-mode starts. */
    var speedStarts: Int = 0
        private set

    /** The highest speed any axis reached, in degrees per second. */
    var peakSpeedDegreesPerSecond: Double = 0.0
        private set

    /** True once the axes were told that initialization is done (`F`). */
    val isInitialized: Boolean get() = axes[0].initialized && axes[1].initialized

    /** True while at least one axis moves. */
    val isMoving: Boolean
        get() {
            advance()
            return axes[0].phase != Phase.STOPPED || axes[1].phase != Phase.STOPPED
        }

    // MARK: Test access

    /** The encoder angle of axis 1 in degrees. */
    val axis1Degrees: Double get() = axisDegrees(0)

    /** The encoder angle of axis 2 in degrees. */
    val axis2Degrees: Double get() = axisDegrees(1)

    private fun axisDegrees(index: Int): Double {
        advance()
        val inverted = if (index == 0) axis1Inverted else axis2Inverted
        return (if (inverted) -1.0 else 1.0) * axes[index].position * 360.0 / countsPerRevolution.toDouble()
    }

    private fun shiftDegrees(index: Int): Double {
        val inverted = if (index == 0) axis1Inverted else axis2Inverted
        return (if (inverted) -1.0 else 1.0) * encoderShift[index] * 360.0 / countsPerRevolution.toDouble()
    }

    /** The physical angle of the axis: the encoder angle plus the shift that `E` (set position) caused. */
    private fun physicalDegrees(index: Int): Double = axisDegrees(index) + shiftDegrees(index)

    /**
     * The errors as they are now: [errors] with the home position error changed by what `E` (set position) did to the encoder
     * zero. A pointing model with these errors turns encoder angles into the real pointing.
     */
    val effectiveErrors: SkyWatcherPointingErrors
        get() = errors.copy(axis1Index = errors.axis1Index + shiftDegrees(0), axis2Index = errors.axis2Index + shiftDegrees(1))

    /** Moves the axes to the given encoder angles at once and stops them (as if the user turned the mount by hand before connecting). */
    fun placeAxes(axis1Degrees: Double, axis2Degrees: Double) {
        advance()
        for (index in 0 until 2) {
            val inverted = if (index == 0) axis1Inverted else axis2Inverted
            val degrees = if (index == 0) axis1Degrees else axis2Degrees
            val axis = axes[index]
            axis.position = (if (inverted) -1.0 else 1.0) * degrees * countsPerRevolution.toDouble() / 360.0
            axis.velocity = 0.0
            axis.phase = Phase.STOPPED
            axis.modeCode = '1'
        }
    }

    /** The direction the telescope really points at, in the local frame of [SkyWatcherPointingModel] (true geometry with [errors]). */
    fun truePointing(): Vector3 {
        val theta = AstroMath.radians(physicalDegrees(0) + errors.axis1Index)
        val secondary = AstroMath.radians(physicalDegrees(1) + errors.axis2Index)
        val phi = if (mode == SkyWatcherMountMode.EQUATORIAL) secondary + PI / 2 else secondary
        val cone = AstroMath.radians(errors.cone)
        var vector = Vector3(sin(cone), cos(cone) * cos(phi), cos(cone) * sin(phi))
        vector = Matrix3.rotationY(AstroMath.radians(errors.orthogonality)) * vector
        vector = Matrix3.rotationZ(-theta) * vector
        vector = Matrix3.rotationX(AstroMath.radians(errors.baseTiltX)) * vector
        vector = Matrix3.rotationY(AstroMath.radians(errors.baseTiltY)) * vector
        if (mode == SkyWatcherMountMode.EQUATORIAL && location.latitude < 0) vector = Vector3(vector.x, -vector.y, -vector.z)
        return vector
    }

    /** The apparent place of date the telescope really points at now. */
    fun truePlace(): EquatorialCoordinate {
        val frame = SkyFrame(clock.now(), Observer(location))
        return SkyWatcherSky.apparentPlace(truePointing(), frame.localApparentSiderealTime, location.latitude, mode)
    }

    // MARK: Protocol

    override fun consume(pending: ByteArray): Int {
        var end = -1
        for (index in pending.indices) {
            if ((pending[index].toInt() and 0xFF) == SkyWatcherCodec.terminator) {
                end = index
                break
            }
        }
        if (end < 0) return 0
        var start = -1
        for (index in end - 1 downTo 0) {
            if ((pending[index].toInt() and 0xFF) == SkyWatcherCodec.leadingByte) {
                start = index
                break
            }
        }
        if (start >= 0) handle(pending.copyOfRange(start, end))
        return end + 1
    }

    private fun handle(line: ByteArray) {
        val text = MountText.latin1(line)
        val label = if (text.length >= 3) text.substring(1, 3) else text
        if (random.next() < requestLossProbability) {
            noReply(label)
            return
        }
        advance()
        val answer = execute(text)
        val bytes = MountText.latin1Bytes(answer)
        if (random.next() < replyLossProbability) {
            noReply(label)
            return
        }
        reply(label, if (random.next() < garbleProbability) garbled(bytes) else bytes)
    }

    private fun garbled(bytes: ByteArray): ByteArray {
        val kind = (random.next() * 4).toInt()
        return when (kind) {
            0 -> {
                val copy = bytes.copyOf()
                val index = 1 + (random.next() * (copy.size - 2).coerceAtLeast(1)).toInt()
                copy[min(index, copy.size - 1)] = 'G'.code.toByte()
                copy
            }
            1 -> bytes.copyOfRange(0, max(0, bytes.size - 2))
            2 -> byteArrayOf('\n'.code.toByte()) + bytes
            else -> bytes + bytes
        }
    }

    private fun failure(code: Int): String = "!${SkyWatcherCodec.encode8(code).substring(1)}\r"

    private fun success(payload: String): String = "=$payload\r"

    private fun expectedDataLength(command: SkyWatcherCommand): Int = when (command) {
        SkyWatcherCommand.SET_MOTION_MODE -> 2
        SkyWatcherCommand.SET_POSITION, SkyWatcherCommand.SET_GOTO_TARGET_INCREMENT, SkyWatcherCommand.SET_BRAKE_POINT_INCREMENT,
        SkyWatcherCommand.SET_GOTO_TARGET, SkyWatcherCommand.SET_STEP_PERIOD, SkyWatcherCommand.SET_LONG_GOTO_STEP_PERIOD,
        SkyWatcherCommand.SET_BRAKE_STEPS, SkyWatcherCommand.EXTENDED_SETTING, SkyWatcherCommand.EXTENDED_INQUIRE,
        -> 6
        SkyWatcherCommand.SET_SLEEP, SkyWatcherCommand.SET_AUX_SWITCH, SkyWatcherCommand.SET_AUTOGUIDE_SPEED,
        SkyWatcherCommand.INQUIRE_INCREMENT,
        -> 1
        else -> 0
    }

    private fun execute(text: String): String {
        if (text.length < 3) return failure(1)
        val command = SkyWatcherCommand.fromLetter(text[1]) ?: return failure(0)
        val data = text.substring(3)
        if (data.length != expectedDataLength(command)) return failure(1)
        for (character in data) {
            if (character !in '0'..'9' && character !in 'A'..'F') return failure(3)
        }
        val channel = text[2]
        val targets = when (channel) {
            '1' -> listOf(0)
            '2' -> listOf(1)
            '3' -> if (command == SkyWatcherCommand.INITIALIZATION_DONE || command == SkyWatcherCommand.START_MOTION ||
                command == SkyWatcherCommand.STOP_MOTION || command == SkyWatcherCommand.INSTANT_STOP
            ) {
                listOf(0, 1)
            } else {
                return failure(3)
            }
            else -> return failure(3)
        }
        var result = success("")
        for (index in targets) {
            result = run(command, index, data)
            if (result.startsWith("!")) return result
        }
        return result
    }

    private fun requireReady(axis: SimulatedAxis): String? {
        if (sleeping) return failure(5)
        if (!axis.initialized) return failure(4)
        return null
    }

    private fun run(command: SkyWatcherCommand, index: Int, data: String): String {
        val axis = axes[index]
        when (command) {
            SkyWatcherCommand.INQUIRE_VERSION -> return success(SkyWatcherCodec.encodeVersion(version))
            SkyWatcherCommand.INQUIRE_COUNTS_PER_REVOLUTION -> return success(SkyWatcherCodec.encode24(countsPerRevolution))
            SkyWatcherCommand.INQUIRE_TIMER_FREQUENCY -> return success(SkyWatcherCodec.encode24(timerFrequency))
            SkyWatcherCommand.INQUIRE_HIGH_SPEED_RATIO -> return success(SkyWatcherCodec.encode8(highSpeedRatio))
            SkyWatcherCommand.INQUIRE_STATUS -> return success(
                SkyWatcherCodec.encodeStatus(
                    SkyWatcherAxisStatus(
                        isSpeedMode = axis.modeCode == '1' || axis.modeCode == '3',
                        isDecreasing = axis.directionCode == '1',
                        isFast = axis.modeCode == '0' || axis.modeCode == '3',
                        isRunning = axis.phase != Phase.STOPPED,
                        isBlocked = false,
                        isInitialized = axis.initialized,
                        isLevelSwitchOn = false,
                    ),
                ),
            )
            SkyWatcherCommand.INQUIRE_POSITION, SkyWatcherCommand.INQUIRE_AXIS_POSITION -> {
                val counts = axis.position.roundedHalfAwayFromZero().toInt().coerceIn(-0x7FFFFF, 0x7FFFFF)
                return success(SkyWatcherCodec.encodePosition(counts))
            }
            SkyWatcherCommand.INQUIRE_GOTO_TARGET -> return success(SkyWatcherCodec.encodePosition(axis.target.roundedHalfAwayFromZero().toInt().coerceIn(-0x7FFFFF, 0x7FFFFF)))
            SkyWatcherCommand.INQUIRE_STEP_PERIOD -> return success(SkyWatcherCodec.encode24(axis.period))
            SkyWatcherCommand.INQUIRE_INCREMENT -> return success(SkyWatcherCodec.encode24(axis.increment))
            SkyWatcherCommand.INQUIRE_BRAKE_POINT, SkyWatcherCommand.INQUIRE_BRAKE_STEPS -> return success(SkyWatcherCodec.encode24(axis.brake))
            SkyWatcherCommand.INQUIRE_TRACKING_PERIOD -> {
                val speed = SkyWatcherCodec.speed(SkyWatcherSky.siderealDegreesPerSecond, countsPerRevolution, timerFrequency, highSpeedRatio)
                return success(SkyWatcherCodec.encode24(speed?.period ?: 0))
            }
            SkyWatcherCommand.INQUIRE_PEC_PERIOD -> return success(SkyWatcherCodec.encode24(countsPerRevolution / 40))
            SkyWatcherCommand.EXTENDED_INQUIRE -> return success(SkyWatcherCodec.encode24(0x8000))
            SkyWatcherCommand.EXTENDED_SETTING, SkyWatcherCommand.SET_AUX_SWITCH, SkyWatcherCommand.SET_AUTOGUIDE_SPEED -> return success("")
            SkyWatcherCommand.SET_SLEEP -> {
                sleeping = data == "1"
                return success("")
            }
            SkyWatcherCommand.INITIALIZATION_DONE -> {
                axis.initialized = true
                return success("")
            }
            SkyWatcherCommand.SET_POSITION -> {
                if (axis.phase != Phase.STOPPED) return failure(2)
                val newPosition = SkyWatcherCodec.decodePosition(data).toDouble()
                encoderShift[index] += axis.position - newPosition
                axis.position = newPosition
                return success("")
            }
            SkyWatcherCommand.STOP_MOTION -> {
                if (axis.phase == Phase.SPEED || axis.phase == Phase.GOTO) axis.phase = Phase.BRAKING
                return success("")
            }
            SkyWatcherCommand.INSTANT_STOP -> {
                halt(axis)
                return success("")
            }
            else -> Unit
        }
        requireReady(axis)?.let { return it }
        when (command) {
            SkyWatcherCommand.SET_MOTION_MODE -> {
                if (axis.phase != Phase.STOPPED) return failure(2)
                val mode = data[0]
                val direction = data[1]
                if (mode !in '0'..'3' || (direction != '0' && direction != '1')) return failure(3)
                axis.modeCode = mode
                axis.directionCode = direction
            }
            SkyWatcherCommand.SET_GOTO_TARGET_INCREMENT -> {
                axis.increment = SkyWatcherCodec.decode24(data)
                axis.absoluteTarget = null
            }
            SkyWatcherCommand.SET_BRAKE_POINT_INCREMENT, SkyWatcherCommand.SET_BRAKE_STEPS -> axis.brake = SkyWatcherCodec.decode24(data)
            SkyWatcherCommand.SET_LONG_GOTO_STEP_PERIOD -> Unit
            SkyWatcherCommand.SET_GOTO_TARGET -> axis.absoluteTarget = SkyWatcherCodec.decodePosition(data)
            SkyWatcherCommand.SET_STEP_PERIOD -> {
                if (axis.phase == Phase.SPEED && axis.lastFast) return failure(2)
                val period = SkyWatcherCodec.decode24(data)
                if (period < 1) return failure(3)
                axis.period = period
                if (axis.phase == Phase.SPEED) axis.velocity = speedVelocity(axis)
            }
            SkyWatcherCommand.START_MOTION -> return start(axis)
        }
        return success("")
    }

    private fun halt(axis: SimulatedAxis) {
        axis.phase = Phase.STOPPED
        axis.velocity = 0.0
        axis.modeCode = '1'
    }

    private fun countsPerSecond(degreesPerSecond: Double): Double = degreesPerSecond * countsPerRevolution.toDouble() / 360.0

    private fun speedVelocity(axis: SimulatedAxis): Double {
        val factor = if (axis.modeCode == '3') highSpeedRatio.toDouble() else 1.0
        val magnitude = min(factor * timerFrequency.toDouble() / max(axis.period, 1).toDouble(), countsPerSecond(maximumSlewDegreesPerSecond))
        return if (axis.directionCode == '1') -magnitude else magnitude
    }

    private fun start(axis: SimulatedAxis): String {
        if (axis.phase != Phase.STOPPED) return failure(2)
        when (axis.modeCode) {
            '1', '3' -> {
                if (axis.period < 1) return failure(3)
                axis.lastFast = axis.modeCode == '3'
                axis.velocity = speedVelocity(axis)
                axis.phase = Phase.SPEED
                speedStarts += 1
            }
            else -> {
                val absolute = axis.absoluteTarget
                val signed = if (axis.directionCode == '1') -axis.increment else axis.increment
                axis.target = if (absolute != null) absolute.toDouble() else axis.position + signed.toDouble()
                axis.gotoFast = axis.modeCode == '0'
                axis.lastFast = axis.gotoFast
                axis.phase = Phase.GOTO
                axis.velocity = 0.0
                gotosStarted += 1
            }
        }
        return success("")
    }

    // MARK: Physics

    private fun advance() {
        val now = clock.secondsSince1970
        var remaining = now - lastTime
        lastTime = now
        if (!(remaining > 0)) return
        val step = max(0.02, remaining / 20000.0)
        while (remaining > 1e-12) {
            val dt = min(remaining, step)
            for (axis in axes) move(axis, dt)
            remaining -= dt
        }
        for (axis in axes) {
            val speed = abs(axis.velocity) * 360.0 / countsPerRevolution.toDouble()
            if (speed > peakSpeedDegreesPerSecond) peakSpeedDegreesPerSecond = speed
        }
    }

    private fun move(axis: SimulatedAxis, dt: Double) {
        val acceleration = countsPerSecond(accelerationDegreesPerSecondSquared)
        when (axis.phase) {
            Phase.STOPPED -> Unit
            Phase.SPEED -> axis.position += axis.velocity * dt
            Phase.BRAKING -> {
                val speed = abs(axis.velocity)
                val reduced = speed - acceleration * dt
                val sign = if (axis.velocity >= 0) 1.0 else -1.0
                if (reduced <= 0) {
                    axis.position += sign * speed * dt / 2
                    halt(axis)
                } else {
                    axis.position += sign * (speed + reduced) / 2 * dt
                    axis.velocity = sign * reduced
                }
            }
            Phase.GOTO -> {
                val distance = axis.target - axis.position
                val remainingDistance = abs(distance)
                if (remainingDistance <= 0.5) {
                    axis.position = axis.target
                    halt(axis)
                    return
                }
                val sign = if (distance >= 0) 1.0 else -1.0
                val limit = countsPerSecond(if (axis.gotoFast) maximumSlewDegreesPerSecond else lowSpeedGotoDegreesPerSecond)
                val desired = if (axis.gotoFast) min(limit, sqrt(2 * acceleration * remainingDistance)) else limit
                var speed = abs(axis.velocity)
                speed = if (desired > speed) min(desired, speed + acceleration * dt) else max(desired, speed - acceleration * dt)
                speed = max(speed, countsPerSecond(0.01))
                val travel = speed * dt
                if (travel >= remainingDistance) {
                    axis.position = axis.target
                    halt(axis)
                } else {
                    axis.position += sign * travel
                    axis.velocity = sign * speed
                }
            }
        }
    }

    companion object {
        /** The simulator's default start time, for demos: 2026-10-18 04:00 UTC. */
        val defaultStart = instantFromTimeIntervalSince1970(1_792_296_000.0)
    }
}
