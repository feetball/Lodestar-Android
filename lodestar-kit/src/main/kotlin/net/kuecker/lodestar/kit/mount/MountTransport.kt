package net.kuecker.lodestar.kit.mount

import net.kuecker.lodestar.kit.foundation.instantFromTimeIntervalSince1970
import net.kuecker.lodestar.kit.foundation.timeIntervalSince1970
import java.time.Instant

/**
 * The engine's source of time. Timeouts, slew watching and sky calculations all use it, so tests can drive a
 * [ManualMountClock] instead of waiting.
 */
interface MountClock {
    /** The current time. */
    fun now(): Instant

    /** Blocks the calling thread for [seconds] (a manual clock advances instead). */
    fun sleep(seconds: Double)
}

/** The real clock: `Instant.now()` and `Thread.sleep`. */
class SystemMountClock : MountClock {
    override fun now(): Instant = Instant.now()

    override fun sleep(seconds: Double) {
        if (!(seconds > 0)) return
        try {
            Thread.sleep((seconds * 1000).toLong().coerceAtMost(3_600_000L))
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}

/** A clock that only moves when told to: [sleep] and [advance] add time. Used by the simulators and tests. */
class ManualMountClock(start: Instant) : MountClock {
    private var seconds: Double = start.timeIntervalSince1970

    override fun now(): Instant = instantFromTimeIntervalSince1970(seconds)

    override fun sleep(seconds: Double) = advance(seconds)

    /** Moves the clock forward by [seconds]; negative, NaN and infinite values are ignored. */
    fun advance(seconds: Double) {
        if (seconds.isFinite() && seconds > 0) this.seconds += seconds
    }

    /** The clock's reading as seconds since 1970 (without the rounding of [now]). */
    val secondsSince1970: Double get() = seconds
}

/**
 * A byte-stream link to a mount: a TCP socket, a UDP socket "connected" to the mount, or a serial port. The apps
 * implement it; the engine only calls these three methods, one exchange at a time from one thread.
 *
 * Implementations throw [MountError.Timeout] when nothing arrives in time and [MountError.Transport] for I/O errors.
 */
interface MountTransport {
    /** Sends all of [bytes] (one datagram for UDP). */
    @Throws(MountError::class)
    fun send(bytes: ByteArray)

    /**
     * Waits up to [timeoutSeconds] for input and returns what has arrived, at least one and at most [maxCount] bytes.
     *
     * @throws MountError.Timeout if nothing arrived.
     */
    @Throws(MountError::class)
    fun receive(maxCount: Int, timeoutSeconds: Double): ByteArray

    /** Drops any input that has arrived but was not read (late replies of earlier commands). */
    @Throws(MountError::class)
    fun discardPendingInput()
}

/**
 * An HTTP client for one Alpaca server, as the apps implement it: it sends [AlpacaHTTPRequest.method] to
 * [AlpacaEndpoint.baseURL] + [AlpacaHTTPRequest.target] with [AlpacaHTTPRequest.body] and
 * [AlpacaHTTPRequest.contentType], and returns the status code and the body decoded as UTF-8.
 *
 * Implementations throw [MountError.Timeout] when the server does not answer within the timeout and
 * [MountError.Transport] for connection failures. A non-200 status is a normal return value, not an exception.
 */
interface AlpacaTransport {
    /** Performs one request/response exchange, waiting at most [timeoutSeconds]. */
    @Throws(MountError::class)
    fun send(request: AlpacaHTTPRequest, timeoutSeconds: Double): AlpacaHTTPResponse
}
