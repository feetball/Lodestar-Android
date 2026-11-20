package net.kuecker.lodestar.features.system

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import net.kuecker.lodestar.R
import net.kuecker.lodestar.app.AppModel
import net.kuecker.lodestar.features.satellites.SatelliteAimSession
import net.kuecker.lodestar.kit.catalog.CelestialObject
import java.time.ZoneId

/**
 * The launcher's long-press shortcuts: Aim, Red Torch and Night Mode are static (`res/xml/shortcuts.xml`); the favourites are
 * published as "Aim at ..." shortcuts here, so that a long press on the icon (and an assistant) can aim at one.
 */
internal object SystemShortcuts {
    /** The most favourites published; the launcher shows only a few shortcuts anyway. */
    const val MAXIMUM_DYNAMIC = 3

    /** The identifier of the shortcut that aims at the object [objectID]. */
    fun shortcutID(objectID: String): String = "aim.$objectID"

    /** "Aim at M 31". */
    fun label(name: String): String = "Aim at $name"

    /** The favourites to publish: the first [MAXIMUM_DYNAMIC] that the catalog knows, in the order of the favourites. */
    fun choose(favoriteIDs: List<String>, find: (String) -> CelestialObject?): List<CelestialObject> =
        favoriteIDs.asSequence()
            .filter { it.length <= SystemLaunch.MAXIMUM_OBJECT_ID_LENGTH }
            .mapNotNull(find)
            .take(MAXIMUM_DYNAMIC)
            .toList()

    /** Replaces the dynamic shortcuts by "Aim at" shortcuts for [objects]. */
    fun publish(context: Context, objects: List<CelestialObject>) {
        val shortcuts = objects.map { obj ->
            ShortcutInfoCompat.Builder(context, shortcutID(obj.id))
                .setShortLabel(obj.name.take(24))
                .setLongLabel(label(obj.name))
                .setIcon(IconCompat.createWithResource(context, R.drawable.ic_system_aim))
                .setIntent(SystemLaunch.intent(context, SystemLaunch.ACTION_AIM_AT, obj.id))
                .build()
        }
        runCatching { ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts) }
    }
}

/**
 * Keeps the system's views of the app in step with it: the "Aim at" shortcuts follow the favourites, and the countdown
 * notification follows the satellite pass that Aim follows or waits for. Put once in the root screen.
 */
@Composable
internal fun SystemSync(app: AppModel) {
    val context = LocalContext.current.applicationContext
    val favorites = app.settings.favoriteObjectIDs
    val catalogReady = app.catalog.catalog != null
    LaunchedEffect(favorites, catalogReady) {
        if (!catalogReady) return@LaunchedEffect
        SystemShortcuts.publish(context, SystemShortcuts.choose(favorites, app.catalog::objectWithID))
    }

    val session = remember { app.retained.getOrPut(SatelliteAimSession.KEY) { SatelliteAimSession() } }
    val source = session.source
    LaunchedEffect(source) {
        val plan = source?.let { SatellitePassPlan.of(it, app.observerContext?.timeZone?.id ?: ZoneId.systemDefault().id) }
        if (plan == null) {
            SatellitePassNotifier.end(context)
            return@LaunchedEffect
        }
        val permitted = SatellitePassNotifier.canPost(context) || (app.notificationPrompt?.invoke() == true)
        if (permitted) SatellitePassNotifier.begin(context, plan) else SatellitePassNotifier.end(context)
    }
}
