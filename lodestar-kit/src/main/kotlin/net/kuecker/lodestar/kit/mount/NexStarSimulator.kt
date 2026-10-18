package net.kuecker.lodestar.kit.mount

/**
 * An in-memory Celestron NexStar or Sky-Watcher SynScan hand control behind a [MountTransport], for tests and UI previews.
 * It answers the commands of [NexStarCodec] with the documented replies (binary version for Celestron, six hex digits for
 * SynScan, one extra byte for pass-through commands to an absent device), ignores GOTO and sync while it is not aligned
 * (as the real hand control does) and drives a [MountSimulatorModel].
 */
class NexStarSimulator(
    clock: ManualMountClock,
    /** The moving mount behind the protocol. */
    val mount: MountSimulatorModel,
    /** Which hand control family to imitate. */
    val flavor: NexStarFlavor = NexStarFlavor.SYNSCAN,
    /** Reported major version. */
    val major: Int = 4,
    /** Reported minor version. */
    val minor: Int = 38,
    /** Reported patch level (SynScan only). */
    val patch: Int = 6,
    /** The answer to `m`. */
    val model: Int = 128,
    /** The tracking mode the mount runs in while it tracks (1 alt-az, 2 equatorial). */
    var trackingMode: Int = 1,
    latencySeconds: Double = 0.05,
    chunkSize: Int = 0,
) : MountSimulatedStreamTransport(clock, latencySeconds, chunkSize) {
    /** The eight bytes of the last `W` (set location) command, as unsigned values; empty before one arrived. */
    var receivedLocation: List<Int> = emptyList()
        private set

    /** The eight bytes of the last `H` (set time) command, as unsigned values; empty before one arrived. */
    var receivedTime: List<Int> = emptyList()
        private set

    private var rightAscensionRate = 0.0
    private var declinationRate = 0.0

    override fun consume(pending: ByteArray): Int {
        val opcode = (pending[0].toInt() and 0xFF).toChar()
        val needed = when (opcode) {
            'T', 'K' -> 2
            'r', 's' -> 18
            'R', 'S' -> 10
            'P' -> 8
            'W', 'H' -> 9
            else -> 1
        }
        if (pending.size < needed) return 0
        handle(opcode, pending.copyOfRange(0, needed))
        return needed
    }

    private fun answer(label: String, vararg bytes: Int) =
        reply(label, ByteArray(bytes.size + 1) { index -> if (index < bytes.size) bytes[index].toByte() else '#'.code.toByte() })

    private fun handle(opcode: Char, command: ByteArray) {
        val label = opcode.toString()
        when (opcode) {
            'V' -> if (flavor == NexStarFlavor.CELESTRON) {
                answer(label, major, minor)
            } else {
                val digits = hex2(major) + hex2(minor) + hex2(patch)
                reply(label, MountText.ascii("$digits#"))
            }
            'm' -> answer(label, model)
            'J' -> answer(label, if (mount.isAligned) 1 else 0)
            'L' -> answer(label, if (mount.isSlewing) '1'.code else '0'.code)
            'M' -> {
                mount.stop()
                rightAscensionRate = 0.0
                declinationRate = 0.0
                answer(label)
            }
            't' -> answer(label, if (mount.isTracking) trackingMode else 0)
            'T' -> {
                val mode = command[1].toInt() and 0xFF
                mount.isTracking = mode != 0
                if (mode != 0) trackingMode = mode
                answer(label)
            }
            'e', 'E' -> {
                val precise = opcode == 'e'
                val position = mount.position
                val text = NexStarCodec.encodeAngle(position.rightAscension, precise) + "," +
                    NexStarCodec.encodeAngle(position.declination, precise) + "#"
                reply(label, MountText.ascii(text))
            }
            'r', 'R', 's', 'S' -> positionCommand(opcode, command)
            'P' -> passthrough(command)
            'K' -> answer(label, command[1].toInt() and 0xFF)
            'W' -> {
                receivedLocation = command.drop(1).map { it.toInt() and 0xFF }
                answer(label)
            }
            'H' -> {
                receivedTime = command.drop(1).map { it.toInt() and 0xFF }
                answer(label)
            }
            else -> noReply(label)
        }
    }

    private fun positionCommand(opcode: Char, command: ByteArray) {
        val label = opcode.toString()
        val target = try {
            NexStarCodec.decodePosition(MountText.latin1(command.copyOfRange(1, command.size)))
        } catch (error: MountError) {
            noReply(label)
            return
        }
        if (mount.isAligned) {
            if (opcode == 'r' || opcode == 'R') {
                mount.beginSlew(target)
                mount.isTracking = true
            } else {
                mount.sync(target)
            }
        }
        answer(label)
    }

    private fun passthrough(command: ByteArray) {
        val device = command[2].toInt() and 0xFF
        val id = command[3].toInt() and 0xFF
        val data1 = command[4].toInt() and 0xFF
        val data2 = command[5].toInt() and 0xFF
        val responseLength = command[7].toInt() and 0xFF
        if (device != 16 && device != 17) {
            reply("P", byteArrayOf(0, '#'.code.toByte()))
            return
        }
        when (id) {
            6, 7 -> setAxisRate(device, id == 6, (data1 * 256 + data2) / 4.0 / 3600.0)
            36, 37 -> setAxisRate(device, id == 36, fixedRatesDegreesPerSecond[data1.coerceIn(0, 9)])
            254 -> {
                reply("P", byteArrayOf(major.toByte(), minor.toByte(), '#'.code.toByte()))
                return
            }
            else -> Unit
        }
        reply("P", ByteArray(responseLength + 1) { index -> if (index == responseLength) '#'.code.toByte() else 0 })
    }

    /** Positive on the right ascension / azimuth axis moves west, positive on the declination / altitude axis moves north. */
    private fun setAxisRate(device: Int, positive: Boolean, degreesPerSecond: Double) {
        val signed = if (positive) degreesPerSecond else -degreesPerSecond
        if (device == 16) rightAscensionRate = -signed else declinationRate = signed
        mount.setNudgeRates(rightAscensionRate, declinationRate)
    }

    private fun hex2(value: Int): String = value.toString(16).uppercase().padStart(2, '0')

    private companion object {
        val fixedRatesDegreesPerSecond: DoubleArray =
            doubleArrayOf(0.0, 0.0042, 0.0084, 0.0167, 0.0333, 0.0667, 0.1333, 0.5, 1.0, 3.0)
    }
}
