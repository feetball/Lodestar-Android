package net.kuecker.lodestar.app

import net.kuecker.lodestar.BuildConfig
import net.kuecker.lodestar.kit.foundation.HorizontalCoordinate
import java.time.Instant

/**
 * Debug builds only: a fixed site, time, tab, target and camera pointing for store screenshots, passed as one
 * intent extra, e.g. `adb shell am start -n net.kuecker.lodestar/.app.MainActivity --es lodestarScreenshot
 * "tab=aim date=2026-07-17T05:30:00Z lat=36.0544 lon=-112.1401 elevation=2130 site=Grand_Canyon
 * tz=America/Phoenix target=special.galacticCenter pointing=172,18 night=0"`.
 */
data class ScreenshotMode(
    val tab: AppTab,
    val date: Instant,
    val latitude: Double,
    val longitude: Double,
    val elevation: Double,
    val siteName: String,
    val timeZoneIdentifier: String?,
    val targetID: String?,
    val pointing: HorizontalCoordinate?,
    val nightMode: Boolean,
) {
    companion object {
        const val EXTRA = "lodestarScreenshot"

        /** The active screenshot configuration (debug builds, set by the activity before launch). */
        var current: ScreenshotMode? = null
            private set

        fun install(argument: String?) {
            if (BuildConfig.DEBUG && argument != null) current = parse(argument)
        }

        fun parse(argument: String): ScreenshotMode? {
            val values = argument.trim().split(Regex("\\s+")).mapNotNull { part ->
                val index = part.indexOf('=')
                if (index > 0) part.substring(0, index) to part.substring(index + 1) else null
            }.toMap()
            val date = values["date"]?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
            val latitude = values["lat"]?.toDoubleOrNull() ?: return null
            val longitude = values["lon"]?.toDoubleOrNull() ?: return null
            val pointing = values["pointing"]?.split(",")?.mapNotNull(String::toDoubleOrNull)
                ?.takeIf { it.size == 2 }?.let { HorizontalCoordinate(it[0], it[1]) }
            return ScreenshotMode(
                tab = AppTab.entries.firstOrNull { it.name.equals(values["tab"], ignoreCase = true) } ?: AppTab.AIM,
                date = date,
                latitude = latitude,
                longitude = longitude,
                elevation = values["elevation"]?.toDoubleOrNull() ?: 0.0,
                siteName = values["site"]?.replace('_', ' ') ?: "Demo Site",
                timeZoneIdentifier = values["tz"],
                targetID = values["target"],
                pointing = pointing,
                nightMode = values["night"] == "1",
            )
        }
    }
}
