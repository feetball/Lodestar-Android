package net.kuecker.lodestar.features.widget

import android.content.Context
import android.content.Intent
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.state.GlanceStateDefinition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * The Tonight widget: the darkness window, the Moon, the Milky Way core and the next event. One layout family for both
 * of its sizes in the widget picker (small and medium); what it shows follows the room the widget is given.
 */
internal class TonightWidget(
    /** The size the generated preview in the widget picker is laid out for. */
    private val previewSize: DpSize = WidgetSizes.previewMedium,
) : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Responsive(WidgetSizes.all)

    override val previewSizeMode: SizeMode.Responsive = SizeMode.Responsive(setOf(previewSize))

    // The widgets keep no state of their own: every draw works from the app's stored inputs.
    override val stateDefinition: GlanceStateDefinition<*>? = null

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val model = WidgetModels.current(context)
        provideContent { TonightWidgetContent(model) }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        val model = WidgetModels.preview(context)
        provideContent { TonightWidgetContent(model) }
    }
}

/** Both widgets redraw when the system asks, and keep the refresh work going while any of them is on a screen. */
abstract class TonightWidgetReceiverBase : GlanceAppWidgetReceiver() {
    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetScheduler.start(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WidgetScheduler.stopIfUnused(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        // The widgets show times in the phone's zone, and in its 12 or 24-hour style.
        if (intent.action == Intent.ACTION_TIMEZONE_CHANGED || intent.action == Intent.ACTION_TIME_CHANGED) {
            WidgetScheduler.refreshSoon(context)
        }
    }
}

/** The medium widget of the picker (4 × 2). */
class TonightWidgetReceiver : TonightWidgetReceiverBase() {
    override val glanceAppWidget: GlanceAppWidget = TonightWidget(WidgetSizes.previewMedium)
}

/** The small widget of the picker (2 × 2). */
class TonightSmallWidgetReceiver : TonightWidgetReceiverBase() {
    override val glanceAppWidget: GlanceAppWidget = TonightWidget(WidgetSizes.previewSmall)
}

/** Remembers the latest [WidgetSnapshot], so that the refresh that works it out and the redraw that follows share it. */
internal object WidgetSnapshotCache {
    /** A snapshot is not reused for longer than this, in seconds. */
    private const val MAXIMUM_AGE_SECONDS = 600L

    private class Entry(val inputs: WidgetInputs, val snapshot: WidgetSnapshot)

    @Volatile
    private var entry: Entry? = null

    /** The snapshot for [inputs] at [now]: the remembered one while it is recent and nothing has changed since. */
    fun snapshot(inputs: WidgetInputs, now: Instant): WidgetSnapshot {
        val cached = entry
        if (cached != null && cached.inputs == inputs) {
            val computedAt = cached.snapshot.computedAt
            if (now >= computedAt && now < computedAt.plusSeconds(MAXIMUM_AGE_SECONDS) && now < cached.snapshot.nextRefresh) {
                return cached.snapshot
            }
        }
        return recompute(inputs, now)
    }

    /** A new snapshot, which is remembered. */
    fun recompute(inputs: WidgetInputs, now: Instant): WidgetSnapshot {
        val snapshot = WidgetCalculator.snapshot(inputs, now)
        entry = Entry(inputs, snapshot)
        return snapshot
    }
}

/** Builds what the widgets draw. */
internal object WidgetModels {
    /** The app's preferences file; the widget reads the saved settings from it when the app has not stored its inputs yet. */
    private const val APP_PREFERENCES = "lodestar"

    /** The time the widgets are worked out for: now. Only the debug widget host of the screenshots replaces it. */
    @Volatile
    var clock: () -> Instant = Instant::now

    /** The stored inputs, as the widgets and the refresh work use them. */
    fun inputs(context: Context): WidgetInputs {
        val app = context.applicationContext
        return WidgetInputsStore(app).readOrBootstrap(app.getSharedPreferences(APP_PREFERENCES, Context.MODE_PRIVATE))
    }

    /** The widget for the app's current observer, worked out on a background thread. */
    suspend fun current(context: Context): WidgetModel = withContext(Dispatchers.Default) {
        val app = context.applicationContext
        build(app, WidgetSnapshotCache.snapshot(inputs(app), clock()))
    }

    /** A sample for the widget picker: a dark-sky site on a summer evening, worked out by the same code. */
    suspend fun preview(context: Context): WidgetModel = withContext(Dispatchers.Default) {
        build(context.applicationContext, WidgetCalculator.snapshot(WidgetSample.inputs, WidgetSample.now))
    }

    fun build(context: Context, snapshot: WidgetSnapshot): WidgetModel {
        val night = snapshot.night
        val palette = snapshot.appearance.palette
        val copy = night?.let { WidgetCopy.of(it, snapshot.computedAt, WidgetClock(context)) }
        val moonImage = night?.moon?.let { moon ->
            WidgetMoonImage.render(
                WidgetMoonShape(moon.illuminatedFraction, moon.isWaxing, moon.isSouthernHemisphere),
                litColor = palette.primaryText.toArgb(),
                outlineColor = palette.secondaryText.toArgb(),
            )
        }
        return WidgetModel(snapshot.appearance, copy, moonImage, WidgetLaunch.intent(context))
    }
}
