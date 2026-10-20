package net.kuecker.lodestar.features.events

import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import net.kuecker.lodestar.app.AppModel
import net.kuecker.lodestar.app.ScreenshotMode
import net.kuecker.lodestar.kit.events.AstronomicalEvent

/**
 * Debug screenshots only: the event `screen=event-detail` shows, which is the first of the calendar or, with
 * `event=<kind>`, the first of that kind. Works the calendar out first if it is not there yet.
 */
internal suspend fun EventsModel.screenshotEvent(app: AppModel): AstronomicalEvent? {
    val context = snapshotFlow { app.observerContext }.filterNotNull().first()
    val now = app.clock.now
    refresh(context, now)
    val kind = ScreenshotMode.current?.eventKind
    return current(context, now)?.events?.firstOrNull { kind == null || it.kind == kind }
}
