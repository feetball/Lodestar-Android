package net.kuecker.lodestar.features.widget

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.glance.appwidget.GlanceAppWidgetManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import net.kuecker.lodestar.BuildConfig
import net.kuecker.lodestar.app.AppModel
import net.kuecker.lodestar.kit.foundation.GeographicLocation
import net.kuecker.lodestar.model.AppSettings

/**
 * Keeps the widgets in step with the app. Put once in the root screen: whenever the observer (a saved site, or the
 * device location) or the night-mode preference changes, the widgets are given the new inputs and redrawn, and
 * the first time after the app starts they are redrawn so that they are current when the user goes home.
 */
@Composable
internal fun WidgetSync(app: AppModel) {
    val context = LocalContext.current.applicationContext
    val settings = app.settings
    val device = app.location.stableLocation
    LaunchedEffect(
        settings.locationSource, settings.savedSites, settings.temperatureCelsius, settings.pressureMillibars,
        settings.nightMode, settings.nightIntensity, device,
    ) {
        WidgetUpdater.sync(context, settings, device)
    }
}

/** What the app does for the widgets. */
internal object WidgetUpdater {
    /** Lets the app finish starting before the first redraw's work begins. */
    private const val LAUNCH_DELAY_MS = 2_000L

    @Volatile
    private var hasRefreshedThisLaunch = false

    /**
     * Stores the inputs the widgets compute from ([settings] and the [liveDevice] location, which is remembered for
     * when the app is not running), then redraws them when they changed or it is the first time since the app started.
     */
    suspend fun sync(context: Context, settings: AppSettings, liveDevice: GeographicLocation?) {
        val isFirst = !hasRefreshedThisLaunch
        if (isFirst) delay(LAUNCH_DELAY_MS)
        withContext(Dispatchers.IO) {
            val store = WidgetInputsStore(context)
            if (liveDevice != null) store.rememberDeviceLocation(liveDevice)
            val changed = store.write(WidgetInputsResolver.resolve(settings, liveDevice, store.lastDeviceLocation()))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) publishPreviews(context, store)
            if (!WidgetScheduler.hasWidgets(context)) return@withContext
            WidgetScheduler.ensurePeriodic(context)
            if (changed || isFirst) WidgetRefresher.refresh(context)
        }
        hasRefreshedThisLaunch = true
    }

    /** Publishes the previews of the widget picker (Android 15 and later) once for each version of the app. */
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private suspend fun publishPreviews(context: Context, store: WidgetInputsStore) {
        if (store.previewVersion == BuildConfig.VERSION_CODE) return
        val manager = GlanceAppWidgetManager(context)
        val results = listOf(TonightWidgetReceiver::class, TonightSmallWidgetReceiver::class).map { manager.setWidgetPreviews(it) }
        // A rate-limited call is tried again at the next start.
        if (results.all { it == GlanceAppWidgetManager.SET_WIDGET_PREVIEWS_RESULT_SUCCESS }) {
            store.previewVersion = BuildConfig.VERSION_CODE
        }
    }
}
