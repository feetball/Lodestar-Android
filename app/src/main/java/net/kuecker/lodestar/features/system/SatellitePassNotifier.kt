package net.kuecker.lodestar.features.system

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import net.kuecker.lodestar.R
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The countdown notification of the pass Aim follows or waits for: the system draws the countdown itself (a chronometer),
 * so it keeps running while the app is closed. Work set for the rise and the peak changes the words and the target of
 * the countdown on time, and the notification removes itself after the pass.
 */
internal object SatellitePassNotifier {
    const val CHANNEL_ID = "lodestar.satellite.pass"
    const val NOTIFICATION_ID = 4_101
    const val WORK_NAME = "lodestar.satellite.pass.countdown"

    /** Whether notifications can be posted: the permission (Android 13 and later) is granted and they are not switched off. */
    fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /** Shows the notification for [plan] and sets the work that moves it on at the next change. */
    fun begin(context: Context, plan: SatellitePassPlan, now: Instant = Instant.now()) {
        if (plan.stage(now) == SatellitePassPlan.Stage.OVER) {
            end(context)
            return
        }
        if (show(context, plan, now)) schedule(context, plan, now)
    }

    /** Removes the notification and the work that moves it on. */
    fun end(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        remove(context)
    }

    /** Removes the notification only. */
    fun remove(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    /** Posts (or replaces) the notification for the stage at [now]. `false` when it could not be posted. */
    fun show(context: Context, plan: SatellitePassPlan, now: Instant): Boolean {
        if (!canPost(context)) return false
        ensureChannel(context)
        val stage = plan.stage(now)
        val formatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
            .withZone(runCatching { ZoneId.of(plan.zoneID) }.getOrDefault(ZoneId.systemDefault()))
        val body = SatellitePassWords.body(plan, stage) { formatter.format(it) }
        val open = PendingIntent.getActivity(
            context, 0, SystemLaunch.intent(context, SystemLaunch.ACTION_AIM),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_system_aim)
            .setContentTitle(SatellitePassWords.title(plan))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSubText(SatellitePassWords.note(stage))
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setLocalOnly(true)
            .setTimeoutAfter(plan.lifetime(now).toMillis())
        val target = plan.countdownTarget(stage)
        if (target != null) {
            builder.setShowWhen(true).setWhen(target.toEpochMilli()).setUsesChronometer(true).setChronometerCountDown(true)
        } else {
            builder.setShowWhen(false)
        }
        return try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
            true
        } catch (_: SecurityException) {
            false // the permission was taken away since canPost
        }
    }

    /** Sets the work for the next change of [plan] after [now], replacing the earlier one. */
    fun schedule(context: Context, plan: SatellitePassPlan, now: Instant) {
        val next = plan.nextChange(now) ?: return
        // A moment past the change, so that the stage has changed when the worker asks for it.
        val delay = Duration.between(now, next).plusSeconds(1).coerceAtLeast(Duration.ZERO)
        val request = OneTimeWorkRequestBuilder<SatellitePassWorker>()
            .setInitialDelay(delay)
            .setInputData(workDataOf(*plan.toMap().toList().toTypedArray()))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    private fun ensureChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL_ID, SatellitePassWords.CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW)
        channel.description = SatellitePassWords.CHANNEL_DESCRIPTION
        channel.setShowBadge(false)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun Duration.coerceAtLeast(minimum: Duration): Duration = if (this < minimum) minimum else this
}

/** Moves the countdown notification on to the stage the clock says, and sets the work for the stage after. */
internal class SatellitePassWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val plan = SatellitePassPlan.fromMap(inputData.keyValueMap) ?: return Result.failure()
        val now = Instant.now()
        if (plan.stage(now) == SatellitePassPlan.Stage.OVER) {
            // The notification times out by itself; this just makes sure that it is gone.
            SatellitePassNotifier.remove(applicationContext)
            return Result.success()
        }
        if (SatellitePassNotifier.show(applicationContext, plan, now)) SatellitePassNotifier.schedule(applicationContext, plan, now)
        return Result.success()
    }
}
