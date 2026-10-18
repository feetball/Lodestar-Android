package net.kuecker.lodestar.kit.mount

import net.kuecker.lodestar.kit.foundation.timeIntervalSince

/**
 * Deadline-based framing on top of a [MountTransport]: one exchange is "discard stale input, send, read the reply".
 * Replies may arrive in pieces; bytes that arrive beyond what a read asked for are kept for the next read of the
 * same exchange. Every read has a deadline and a bounded number of receive calls, so nothing here can loop forever.
 */
internal class MountLink(private val transport: MountTransport, private val clock: MountClock) {
    private var buffer = ByteArray(0)

    /** Starts an exchange: forgets buffered bytes and drops late input from earlier exchanges. */
    fun beginExchange() {
        buffer = ByteArray(0)
        try {
            transport.discardPendingInput()
        } catch (error: MountError) {
            throw error
        } catch (error: Exception) {
            throw MountError.Transport(MountText.sanitized(error.message ?: "I/O error", 120))
        }
    }

    fun send(bytes: ByteArray) {
        try {
            transport.send(bytes)
        } catch (error: MountError) {
            throw error
        } catch (error: Exception) {
            throw MountError.Transport(MountText.sanitized(error.message ?: "I/O error", 120))
        }
    }

    /** Reads exactly [count] bytes within [timeoutSeconds]. */
    fun readExactly(count: Int, timeoutSeconds: Double, operation: String): ByteArray {
        val deadlineStart = clock.now()
        var receives = 0
        while (buffer.size < count) {
            receives++
            if (receives > maximumReceives) throw MountError.Timeout(operation)
            fill(count - buffer.size, deadlineStart, timeoutSeconds, operation)
        }
        val result = buffer.copyOfRange(0, count)
        buffer = buffer.copyOfRange(count, buffer.size)
        return result
    }

    /**
     * Reads up to the next [terminator] within [timeoutSeconds] and returns the bytes before it. Fails with
     * [MountError.MalformedReply] when more than [maximumLength] bytes arrive without a terminator.
     */
    fun readUntil(terminator: Byte, maximumLength: Int, timeoutSeconds: Double, operation: String): ByteArray {
        val deadlineStart = clock.now()
        var receives = 0
        while (true) {
            val position = buffer.indexOf(terminator)
            if (position >= 0) {
                if (position > maximumLength) throw MountError.MalformedReply("reply too long")
                val result = buffer.copyOfRange(0, position)
                buffer = buffer.copyOfRange(position + 1, buffer.size)
                return result
            }
            if (buffer.size > maximumLength) throw MountError.MalformedReply("reply too long")
            receives++
            if (receives > maximumReceives) throw MountError.Timeout(operation)
            fill(maximumLength + 1 - buffer.size, deadlineStart, timeoutSeconds, operation)
        }
    }

    private fun fill(wanted: Int, start: java.time.Instant, timeoutSeconds: Double, operation: String) {
        val remaining = timeoutSeconds - clock.now().timeIntervalSince(start)
        if (!(remaining > 0)) throw MountError.Timeout(operation)
        val chunk = try {
            transport.receive(maxOf(wanted, 1), remaining)
        } catch (error: MountError) {
            throw error
        } catch (error: Exception) {
            throw MountError.Transport(MountText.sanitized(error.message ?: "I/O error", 120))
        }
        if (chunk.isEmpty()) throw MountError.Transport("the connection returned no data")
        if (chunk.size > maxOf(wanted, 1)) throw MountError.Transport("the connection returned more data than requested")
        buffer += chunk
    }

    companion object {
        /** The most receive calls one read may use (a transport that dribbles single bytes still finishes). */
        const val maximumReceives: Int = 512
    }
}
