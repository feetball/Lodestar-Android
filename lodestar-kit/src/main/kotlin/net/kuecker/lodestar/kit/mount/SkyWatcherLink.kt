package net.kuecker.lodestar.kit.mount

/**
 * One command and its reply over a [MountTransport] that may lose or damage datagrams (the mounts' UDP port 11880 answers every
 * command in one datagram, and UDP gives no delivery guarantee). An attempt discards stale input, sends the command and waits for the
 * reply; a missing, truncated or garbled reply, and the errors that a damaged request can cause, make the exchange send the command
 * again, up to [attempts] times. Replies that parse but carry the wrong number of digits count as garbled. A reply that arrives after
 * its attempt timed out is dropped by the next attempt's flush, so a late answer is not taken for the next command's.
 */
internal class SkyWatcherLink(
    transport: MountTransport,
    private val clock: MountClock,
    private val attempts: Int,
    private val timeoutSeconds: Double,
) {
    private val link = MountLink(transport, clock)

    /** The datagrams sent so far (retries included). */
    var datagramsSent: Int = 0
        private set

    /** How many of them were repetitions of a command that got no usable reply. */
    var retries: Int = 0
        private set

    /**
     * Sends the command and returns the data digits of the reply.
     *
     * @param maximumAttempts how many times the command may be sent; use 1 for commands that must not be repeated blindly.
     * @throws MountError.Timeout if no usable reply arrived, the mapped error for a `!` reply (see [SkyWatcherCodec.error]).
     */
    @Throws(MountError::class)
    fun exchange(
        command: SkyWatcherCommand,
        axis: SkyWatcherAxis,
        data: String,
        operation: String,
        maximumAttempts: Int = attempts,
    ): String {
        val request = SkyWatcherCodec.request(command, axis, data)
        var last: MountError = MountError.Timeout(operation)
        val limit = maxOf(1, minOf(maximumAttempts, attempts))
        for (attempt in 1..limit) {
            if (attempt > 1) retries += 1
            link.beginExchange()
            link.send(request)
            datagramsSent += 1
            try {
                val line = link.readUntil(SkyWatcherCodec.terminator.toByte(), maximumReplyBytes, timeoutSeconds, operation)
                when (val reply = SkyWatcherCodec.parseReply(line)) {
                    is SkyWatcherReply.Success -> {
                        if (command.replyDigits >= 0 && reply.payload.length != command.replyDigits) {
                            last = MountError.MalformedReply("$operation: wrong reply length")
                        } else {
                            return reply.payload
                        }
                    }
                    is SkyWatcherReply.Failure -> {
                        if (!SkyWatcherCodec.isPossiblyCausedByDamagedRequest(reply.code)) throw SkyWatcherCodec.error(reply.code)
                        last = SkyWatcherCodec.error(reply.code)
                    }
                }
            } catch (error: MountError.Timeout) {
                last = error
            } catch (error: MountError.MalformedReply) {
                last = error
            }
        }
        throw last
    }

    /** Sends the command like [exchange] and ignores what the reply carries. */
    @Throws(MountError::class)
    fun command(
        command: SkyWatcherCommand,
        axis: SkyWatcherAxis,
        data: String,
        operation: String,
        maximumAttempts: Int = attempts,
    ) {
        exchange(command, axis, data, operation, maximumAttempts)
    }

    /** Pauses for [seconds]. */
    fun pause(seconds: Double) {
        clock.sleep(seconds)
    }

    private companion object {
        /** A reply is at most `=` and six digits; anything longer than this without a terminator is garbage. */
        const val maximumReplyBytes: Int = 12
    }
}
