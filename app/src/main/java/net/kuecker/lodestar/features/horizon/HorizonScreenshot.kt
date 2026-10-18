package net.kuecker.lodestar.features.horizon

import kotlinx.coroutines.delay
import net.kuecker.lodestar.app.AppModel
import net.kuecker.lodestar.app.ScreenshotMode
import net.kuecker.lodestar.kit.foundation.HorizontalCoordinate

/** Where the played turn starts and stops, in degrees of azimuth: a little over half of the circle. */
private const val SWEEP_FROM = 40.0
private const val SWEEP_TO = 250.0

/** Degrees the camera moves between two samples, and the seconds of sensor time they are apart (15° a second). */
private const val SWEEP_STEP = 1.5
private const val SAMPLE_SECONDS = 0.1

/** The pause between two samples in real time, in milliseconds. */
private const val SAMPLE_PAUSE_MS = 40L

/** How far above the made-up skyline of the screenshots the played turn finds its own: 1.5°. */
private const val SKYLINE_OFFSET = 1.5

/**
 * Debug screenshots only: plays a turn of the camera along the made-up skyline into the recording screen, from
 * [SWEEP_FROM] to [SWEEP_TO], through the screenshot pointing of the motion service. It stops there, so the screen
 * shows a recording that is part way round.
 */
internal suspend fun playScreenshotSweep(app: AppModel) {
    val skyline = ScreenshotMode.demoSkyline()
    var azimuth = SWEEP_FROM
    var seconds = 2.0
    while (azimuth <= SWEEP_TO) {
        app.motion.useScreenshotPointing(HorizontalCoordinate(azimuth, skyline.altitude(azimuth) + SKYLINE_OFFSET), seconds)
        azimuth += SWEEP_STEP
        seconds += SAMPLE_SECONDS
        delay(SAMPLE_PAUSE_MS)
    }
}
