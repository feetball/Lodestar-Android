package net.kuecker.lodestar.features.online

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.kuecker.lodestar.app.ScreenshotMode
import net.kuecker.lodestar.app.ScreenshotScreen
import net.kuecker.lodestar.app.ScreenshotScreenEffect
import net.kuecker.lodestar.services.OnlineCoordinates
import net.kuecker.lodestar.services.OnlineResource
import net.kuecker.lodestar.shared.LocalAppModel
import java.io.IOException

/** The fixture file of the debug build (`app/src/debug/assets/screenshot`) that stands for [resource]. */
private fun fixtureName(resource: OnlineResource): String = when (resource) {
    OnlineResource.STATIONS -> "stations.json"
    OnlineResource.VISUAL -> "visual.json"
    OnlineResource.STARLINK -> "starlink.json"
    OnlineResource.COMETS -> "CometEls.txt"
    OnlineResource.FORECAST -> "forecast.json"
}

/**
 * Debug screenshots only (it does nothing otherwise): switches the feeds `online=` names on and fills them from the
 * fixtures of the debug build, as if they had just been downloaded. No request is made. Put it at the root, because the
 * data is used on more than one tab.
 */
@Composable
fun OnlineScreenshotEffect() {
    val app = LocalAppModel.current
    val context = LocalContext.current
    val feeds = ScreenshotMode.current?.onlineFeeds.orEmpty()
    if (feeds.isEmpty()) return
    LaunchedEffect(feeds) {
        val online = app.online
        for (feed in feeds) online.setEnabled(feed, true)
        for (feed in feeds) {
            for (resource in feed.resources) {
                val bytes = try {
                    withContext(Dispatchers.IO) { context.assets.open("screenshot/${fixtureName(resource)}").use { it.readBytes() } }
                } catch (_: IOException) {
                    continue
                }
                // The forecast belongs to the place of the screenshot; the others have no key.
                val key = if (resource == OnlineResource.FORECAST) {
                    app.observerContext?.location?.let { OnlineCoordinates.key(it.latitude, it.longitude) }
                } else {
                    null
                }
                online.install(resource, bytes, key)
            }
        }
    }
}

/** Debug screenshots only: opens the explanation that comes before the first feed is switched on. */
@Composable
internal fun OnlineFirstTimeScreenshotEffect(open: () -> Unit) {
    ScreenshotScreenEffect(ScreenshotScreen.ONLINE_FIRST_TIME) { open() }
}
