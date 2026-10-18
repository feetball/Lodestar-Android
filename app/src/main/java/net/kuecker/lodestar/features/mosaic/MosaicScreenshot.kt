package net.kuecker.lodestar.features.mosaic

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import net.kuecker.lodestar.app.AppModel
import net.kuecker.lodestar.app.ScreenshotMode
import net.kuecker.lodestar.app.ScreenshotScreen
import net.kuecker.lodestar.app.ScreenshotScreenEffect
import net.kuecker.lodestar.shared.LocalAppModel
import net.kuecker.lodestar.shared.rememberRetained

/**
 * Debug screenshots only (it does nothing otherwise): chooses what the mosaic plan covers (`mosaic=`) and, for
 * `screen=mosaic-running`, starts the mosaic of the target so that the Aim and Sky chart screens show it running.
 * Lives at the root, because the mosaic is shown on more than one tab.
 */
@Composable
fun MosaicScreenshotEffect() {
    val app = LocalAppModel.current
    val session = rememberRetained(MosaicSession.KEY) { MosaicSession() }
    val source = ScreenshotMode.current?.mosaicSource
    if (source != null) {
        LaunchedEffect(source) { session.setup = session.setup.copy(source = source) }
    }
    ScreenshotScreenEffect(ScreenshotScreen.MOSAIC_RUNNING) {
        val setup = session.setup
        val target = if (setup.source == MosaicSource.MILKY_WAY) null else snapshotFlow { app.target }.filterNotNull().first()
        val observer = snapshotFlow { app.observerContext?.observer }.filterNotNull().first()
        val outcome = withContext(Dispatchers.Default) {
            MosaicPlanning.plan(MosaicPlanningInputs(setup, target, app.lensSetup, observer, null, app.clock.now))
        }
        if (outcome is MosaicPlanOutcome.Ready) session.start(outcome.plan, outcome.title)
    }
}

/** Debug screenshots only: waits until the Aim target is known and the sky behind the sheet has had time to draw. */
suspend fun awaitScreenshotAim(app: AppModel) {
    snapshotFlow { app.target }.filterNotNull().first()
    delay(AIM_SETTLE_MS)
}

private const val AIM_SETTLE_MS = 1_500L
