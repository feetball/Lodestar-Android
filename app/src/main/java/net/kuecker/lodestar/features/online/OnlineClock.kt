package net.kuecker.lodestar.features.online

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.delay
import net.kuecker.lodestar.shared.LocalAppModel
import java.time.Instant

/** The real present moment, refreshed every [periodMillis], for texts such as "5 min ago" about real downloads. */
@Composable
internal fun rememberWallNow(periodMillis: Long = 30_000): Instant {
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(periodMillis) {
        while (true) {
            delay(periodMillis)
            now = Instant.now()
        }
    }
    return now
}

/**
 * The app's clock (which a screenshot can stop), sampled every [periodMillis]: the clock ticks every second, and the
 * screens that use this must not recompose with it.
 */
@Composable
fun rememberSkyNow(periodMillis: Long = 60_000): Instant {
    val clock = LocalAppModel.current.clock
    var now by remember(clock) { mutableStateOf(Snapshot.withoutReadObservation { clock.now }) }
    LaunchedEffect(clock, periodMillis) {
        while (true) {
            delay(periodMillis)
            now = clock.now
        }
    }
    return now
}
