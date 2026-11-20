package net.kuecker.lodestar.wear

import net.kuecker.lodestar.kit.wrist.AimWristMessage

/**
 * The state of the watch's Aim page: the newest aim message from the phone and when it arrived.
 *
 * The phone sends about twice a second while it aims. The page shows "Waiting for phone" until a message has come and
 * again when none has for [STALE_AFTER_MILLIS], and a message older than the newest one is dropped (messages may arrive
 * out of order). Times are on any monotonic clock in milliseconds, such as `SystemClock.elapsedRealtime()`.
 */
class WearAimTracker {
    /** A message and when it arrived. */
    data class Received(val message: AimWristMessage, val at: Long)

    companion object {
        /** Milliseconds without a message after which what was shown is out of date. */
        const val STALE_AFTER_MILLIS = 3_000L
    }

    /** The newest message and its arrival; null until the first one. */
    var latest: Received? = null
        private set

    /** The newest message if it is recent enough to show at [now]. */
    fun current(now: Long): AimWristMessage? =
        latest?.takeIf { now - it.at <= STALE_AFTER_MILLIS }?.message

    /** Takes over [message], which arrived at [now]. Returns true when it reaches the target (for a tap on the wrist). */
    fun receive(message: AimWristMessage, now: Long): Boolean {
        val previous = latest
        if (previous != null && message.sent <= previous.message.sent) return false
        val wasOnTarget = current(now)?.isOnTarget ?: false
        latest = Received(message, now)
        return message.isOnTarget && !wasOnTarget
    }
}
