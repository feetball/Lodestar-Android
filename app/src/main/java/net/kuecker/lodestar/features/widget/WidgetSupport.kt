package net.kuecker.lodestar.features.widget

import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import net.kuecker.lodestar.app.AppModel
import net.kuecker.lodestar.app.AppTab
import net.kuecker.lodestar.app.MainActivity
import net.kuecker.lodestar.model.NightModeSetting
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Writes times as the phone's settings say (12 or 24 hours, its language), for the widgets. */
internal class WidgetClock(context: Context) : WidgetTimeFormat {
    private val locale: Locale = Locale.getDefault()
    private val timeFormatter = formatter(if (DateFormat.is24HourFormat(context)) "Hm" else "hm")
    private val dayFormatter = formatter("EEEMMMd")

    private fun formatter(skeleton: String): DateTimeFormatter =
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale)

    override fun time(instant: Instant, zone: ZoneId): String = timeFormatter.withZone(zone).format(instant)

    override fun day(instant: Instant, zone: ZoneId): String = dayFormatter.withZone(zone).format(instant)
}

/** Tapping a widget opens the app on the Tonight tab. */
internal object WidgetLaunch {
    /** The extra of the launch intent that names the tab to open. */
    const val EXTRA_TAB = "net.kuecker.lodestar.widget.TAB"

    /**
     * The intent a widget starts: the app's activity on the Tonight tab. It reuses a running activity (and brings its
     * task forward) instead of stacking a second one on it.
     */
    fun intent(context: Context): Intent =
        Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_TAB, AppTab.TONIGHT.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    /** The tab called [name] in a launch intent, or `null` for no or an unknown name. */
    fun tabNamed(name: String?): AppTab? = AppTab.entries.firstOrNull { it.name == name }

    /** Selects the tab a widget's [intent] asked for. The request is used up, so a recreated activity does not repeat it. */
    fun handle(intent: Intent?, app: AppModel) {
        val name = intent?.getStringExtra(EXTRA_TAB) ?: return
        intent.removeExtra(EXTRA_TAB)
        tabNamed(name)?.let { app.selectedTab = it }
    }
}

/** The widgets as the widget picker previews them: a dark-sky site on a summer evening. */
internal object WidgetSample {
    val inputs = WidgetInputs(
        place = WidgetPlace(
            name = "Dark Sky Site",
            latitude = 38.9,
            longitude = -105.4,
            elevation = 2_300.0,
            siteID = "sample",
            timeZoneID = "America/Denver",
        ),
        // Always the normal palette, whatever the app's night mode.
        nightMode = NightModeSetting.OFF,
    )

    /** An August morning (8:00 at the site), so that the whole night is still to come and the Moon is on the way up. */
    val now: Instant = Instant.parse("2026-08-08T14:00:00Z")
}
