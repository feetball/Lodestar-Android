package net.kuecker.lodestar.features.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.Constraints
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * The work that keeps the widgets current without waking the phone more than it must: a periodic refresh a few times a
 * day, and a one-time refresh set for the next moment the picture changes (darkness, the Moon or the Milky Way core
 * starting or ending, dusk and dawn, the night turning over). While no widget is on a screen there is no work at all.
 */
internal object WidgetScheduler {
    private const val PERIODIC_NAME = "lodestar.widget.periodic"
    private const val TRANSITION_NAME = "lodestar.widget.transition"

    /** The periodic refresh runs about this often, in hours: four times a day. */
    const val PERIOD_HOURS = 6L

    private val receivers = listOf(TonightWidgetReceiver::class.java, TonightSmallWidgetReceiver::class.java)

    /** Whether any Tonight widget is on a screen. */
    fun hasWidgets(context: Context): Boolean {
        val manager = AppWidgetManager.getInstance(context)
        return receivers.any { manager.getAppWidgetIds(ComponentName(context, it)).isNotEmpty() }
    }

    /** Starts the periodic refresh (once: a running one is kept) and has the widgets drawn and the next refresh set now. */
    fun start(context: Context) {
        ensurePeriodic(context)
        refreshSoon(context)
    }

    /** Keeps the periodic refresh going. Idempotent. */
    fun ensurePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<WidgetRefreshWorker>(PERIOD_HOURS, TimeUnit.HOURS)
            // No point in drawing a widget while the battery is nearly flat; it is drawn again as soon as it is not.
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Runs a refresh at once (in the background). Replaces the refresh that was set for later: it sets the next one. */
    fun refreshSoon(context: Context) = scheduleTransition(context, Instant.now(), Instant.now())

    /** Sets the one-time refresh for [at], replacing the one that was set. */
    fun scheduleTransition(context: Context, at: Instant, now: Instant) {
        val delay = Duration.between(now, at).coerceAtLeast(Duration.ZERO)
        val request = OneTimeWorkRequestBuilder<WidgetRefreshWorker>().setInitialDelay(delay).build()
        WorkManager.getInstance(context).enqueueUniqueWork(TRANSITION_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    /** Ends all refresh work when the last widget has gone. */
    fun stopIfUnused(context: Context) {
        if (hasWidgets(context)) return
        val manager = WorkManager.getInstance(context)
        manager.cancelUniqueWork(PERIODIC_NAME)
        manager.cancelUniqueWork(TRANSITION_NAME)
    }

    private fun Duration.coerceAtLeast(minimum: Duration): Duration = if (this < minimum) minimum else this
}

/** Redraws the widgets from the app's stored inputs and sets the next refresh. */
internal object WidgetRefresher {
    /** Works the night out afresh and redraws every Tonight widget. */
    suspend fun refresh(context: Context) {
        val app = context.applicationContext
        if (!WidgetScheduler.hasWidgets(app)) {
            WidgetScheduler.stopIfUnused(app)
            return
        }
        val now = Instant.now()
        val snapshot = withContext(Dispatchers.Default) { WidgetSnapshotCache.recompute(WidgetModels.inputs(app), now) }
        // Every widget, small or medium, is a TonightWidget as far as the system's bookkeeping goes.
        TonightWidget().updateAll(app)
        WidgetScheduler.scheduleTransition(app, snapshot.nextRefresh, now)
    }
}

/** The periodic and the one-time refresh. */
internal class WidgetRefreshWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        WidgetRefresher.refresh(applicationContext)
        return Result.success()
    }
}
