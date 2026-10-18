package net.kuecker.lodestar.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * The app's notion of time.
 *
 * [now] is the live wall-clock time (ticks once per second while running) and is what the Aim screen always uses.
 * [displayDate] adds an optional "time travel" offset for planning views such as the sky chart.
 */
class SkyClock(private val scope: CoroutineScope) {
    /** Live time, refreshed every second while the clock runs. */
    var now: Instant by mutableStateOf(Instant.now())
        private set

    /** Time-travel offset applied to [displayDate], in seconds. */
    var offset by mutableDoubleStateOf(0.0)
        private set

    private var tickJob: Job? = null

    /** Set by screenshot mode: the clock stands still at this instant. */
    private var frozenDate: Instant? = null

    /** The instant planning views display. */
    val displayDate: Instant get() = now.plusMillis((offset * 1000).toLong())

    /** `true` while no time-travel offset is applied. */
    val isLive: Boolean get() = offset == 0.0

    fun start() {
        if (tickJob != null) return
        frozenDate?.let {
            now = it
            return
        }
        now = Instant.now()
        tickJob = scope.launch {
            while (isActive) {
                delay(1000)
                now = Instant.now()
            }
        }
    }

    fun stop() {
        tickJob?.cancel()
        tickJob = null
    }

    /** Shows the sky at [date] in planning views (limited to ±1 year from now). */
    fun travel(date: Instant) {
        val limit = 366 * 86_400.0
        val seconds = (date.toEpochMilli() - wallClock().toEpochMilli()) / 1000.0
        offset = seconds.coerceIn(-limit, limit)
        now = wallClock()
    }

    /** Shifts the time-travel offset by [seconds]. */
    fun travel(seconds: Double) {
        travel(displayDate.plusMillis((seconds * 1000).toLong()))
    }

    fun returnToNow() {
        offset = 0.0
        now = wallClock()
    }

    /** Stops the clock at [date] (debug screenshot mode). */
    fun freeze(date: Instant) {
        stop()
        frozenDate = date
        now = date
    }

    private fun wallClock(): Instant = frozenDate ?: Instant.now()
}
