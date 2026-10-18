package net.kuecker.lodestar.kit.mount

/** A defect the simulators can be told to produce in the next reply to a command (see [MountSimulatedStreamTransport.inject]). */
sealed interface MountSimulatedFault {
    /** The mount does not answer at all. */
    data object Silence : MountSimulatedFault

    /** The reply is replaced by [text] (one character per byte), for garbled or wrong answers. */
    data class Replace(val text: String) : MountSimulatedFault

    /** Only the first [keep] bytes of the reply arrive. */
    data class Truncate(val keep: Int) : MountSimulatedFault

    /** The connection drops: the next send or receive fails with [MountError.Transport]. */
    data object Disconnect : MountSimulatedFault
}

/**
 * The common part of the byte-stream simulators: collects what the client sends, lets the subclass answer complete
 * commands, delivers replies (optionally in small chunks, as a TCP stream may) and charges simulated time for every
 * reply ([latencySeconds]) and for every timeout (the full timeout), so deadlines behave as on a real link.
 */
abstract class MountSimulatedStreamTransport(
    /** The clock shared with the controller. */
    protected val clock: ManualMountClock,
    /** Simulated round-trip time added to the clock for every reply. */
    val latencySeconds: Double = 0.05,
    /** Deliver replies in pieces of this many bytes per receive call; 0 delivers each reply at once. */
    val chunkSize: Int = 0,
) : MountTransport {
    private var inbox = ByteArray(0)
    private var outbox = ByteArray(0)
    private var broken = false
    private val faults = ArrayList<Pair<String, MountSimulatedFault>>()
    private val log = ArrayList<String>()

    /** The labels of the commands received so far, oldest first. */
    val commandLog: List<String> get() = log

    /** Arranges for the next [times] replies to commands whose label starts with [commandPrefix] to suffer [fault]. */
    fun inject(commandPrefix: String, fault: MountSimulatedFault, times: Int = 1) {
        for (count in 0 until times) faults.add(commandPrefix to fault)
    }

    /** Forgets the recorded command labels. */
    fun clearLog() {
        log.clear()
    }

    /**
     * Consumes complete commands from the front of [pending] and answers them with [reply]; returns the number of bytes
     * used (0 if the first command is not complete yet).
     */
    protected abstract fun consume(pending: ByteArray): Int

    /** Sends the reply [bytes] to the command called [label], after applying any injected fault. */
    protected fun reply(label: String, bytes: ByteArray) {
        log.add(label)
        val index = faults.indexOfFirst { label.startsWith(it.first) }
        var delivered = bytes
        if (index >= 0) {
            val fault = faults.removeAt(index).second
            when (fault) {
                MountSimulatedFault.Silence -> return
                is MountSimulatedFault.Replace -> delivered = MountText.latin1Bytes(fault.text)
                is MountSimulatedFault.Truncate -> delivered = bytes.copyOfRange(0, fault.keep.coerceIn(0, bytes.size))
                MountSimulatedFault.Disconnect -> {
                    broken = true
                    return
                }
            }
        }
        outbox += delivered
        clock.advance(latencySeconds)
    }

    /** Records that a command with [label] arrived and needs no reply. */
    protected fun noReply(label: String) {
        log.add(label)
        val index = faults.indexOfFirst { label.startsWith(it.first) }
        if (index >= 0 && faults[index].second == MountSimulatedFault.Disconnect) {
            faults.removeAt(index)
            broken = true
        }
    }

    override fun send(bytes: ByteArray) {
        if (broken) throw MountError.Transport("connection reset by the simulated mount")
        inbox += bytes
        while (inbox.isNotEmpty()) {
            val used = consume(inbox)
            if (used <= 0) break
            inbox = inbox.copyOfRange(used, inbox.size)
        }
    }

    override fun receive(maxCount: Int, timeoutSeconds: Double): ByteArray {
        if (broken) throw MountError.Transport("connection reset by the simulated mount")
        if (outbox.isEmpty()) {
            clock.advance(timeoutSeconds)
            throw MountError.Timeout("simulated")
        }
        val available = if (chunkSize > 0) minOf(chunkSize, outbox.size) else outbox.size
        val count = minOf(maxCount, available)
        val result = outbox.copyOfRange(0, count)
        outbox = outbox.copyOfRange(count, outbox.size)
        return result
    }

    override fun discardPendingInput() {
        outbox = ByteArray(0)
    }
}
