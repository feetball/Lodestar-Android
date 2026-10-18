package net.kuecker.lodestar.features.satellites

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.kuecker.lodestar.kit.foundation.Observer
import net.kuecker.lodestar.services.OnlineFeed
import net.kuecker.lodestar.services.SatelliteLibrary
import net.kuecker.lodestar.services.SatellitePassItem
import net.kuecker.lodestar.services.SatellitePasses
import net.kuecker.lodestar.shared.LocalAppModel
import net.kuecker.lodestar.shared.rememberRetained
import java.time.Duration
import java.time.Instant

/**
 * The visible passes of the next three days, found once off the main thread and shared by the Tonight card and the Passes
 * screen. They are found again when the satellite data or the observing place changes, and every half hour as the window
 * moves on.
 */
class SatellitePassBoard {
    /** The passes, by the time they become visible; `null` until they have been worked out. */
    var items: List<SatellitePassItem>? by mutableStateOf(null)
        private set

    private val lock = Mutex()
    private var generation = -1L
    private var observer: Observer? = null
    private var computedAt: Instant = Instant.EPOCH

    /** Works the passes out for [library] and [observer] unless they are already current at [now]. */
    suspend fun ensure(library: SatelliteLibrary, observer: Observer, now: Instant) {
        lock.withLock {
            val isCurrent = generation == library.generation && this.observer == observer &&
                Duration.between(computedAt, now) < REFRESH_AFTER
            if (isCurrent) return
            val found = withContext(Dispatchers.Default) { SatellitePasses.compute(library, observer, now) }
            generation = library.generation
            this.observer = observer
            computedAt = now
            items = found
        }
    }

    /** Forgets the passes (the satellite data went away). */
    fun clear() {
        generation = -1
        observer = null
        items = null
    }

    companion object {
        /** The window is moved on after this long. */
        val REFRESH_AFTER: Duration = Duration.ofMinutes(30)
    }
}

/**
 * The shared [SatellitePassBoard], kept up to date for as long as this is in the composition: it works the passes out
 * for the app's satellite data and observing place and again every few minutes.
 */
@Composable
internal fun rememberSatellitePassBoard(observer: Observer?): SatellitePassBoard {
    val app = LocalAppModel.current
    val board = rememberRetained("satellites.board") { SatellitePassBoard() }
    val library = app.online.satellites
    val isOn = app.online.isEnabled(OnlineFeed.SATELLITE_PASSES)
    LaunchedEffect(library, observer, isOn) {
        if (!isOn || observer == null || !library.hasPassData) {
            board.clear()
            return@LaunchedEffect
        }
        while (true) {
            board.ensure(library, observer, Snapshot.withoutReadObservation { app.clock.now })
            delay(REFRESH_CHECK_MILLIS)
        }
    }
    return board
}

private const val REFRESH_CHECK_MILLIS = 5 * 60_000L
