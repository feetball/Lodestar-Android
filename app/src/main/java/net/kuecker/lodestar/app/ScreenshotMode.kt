package net.kuecker.lodestar.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import net.kuecker.lodestar.BuildConfig
import net.kuecker.lodestar.features.mosaic.MosaicSource
import net.kuecker.lodestar.kit.events.EventKind
import net.kuecker.lodestar.kit.foundation.HorizontalCoordinate
import net.kuecker.lodestar.kit.horizon.HorizonProfile
import java.time.Instant
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/**
 * The sub-screens and sheets a screenshot can open after launch (`screen=<id>`). Each owner of a screen reacts to its
 * own ids with [ScreenshotScreenEffect]; a screen that is not asked for is never touched.
 */
enum class ScreenshotScreen(val id: String) {
    /** Settings, Mount Control, not connected: the form to connect. */
    MOUNT_SETUP("mount-setup"),

    /** Settings, Mount Control, connected to the built-in simulated mount. */
    MOUNT_CONTROL("mount-control"),

    /** Aim with the simulated mount connected: the mount card. */
    AIM_MOUNT("aim-mount"),

    /** Aim with the simulated mount slewing to the target. */
    AIM_SLEW("aim-slew"),

    /** Aim, simulated mount connected, the Mount sheet open. */
    AIM_MOUNT_SHEET("aim-mount-sheet"),

    /** Aim, simulated mount connected, the confirmation of Go to the target. */
    AIM_GOTO("aim-goto"),

    /** Aim, simulated mount connected, the confirmation of Sync to the target. */
    AIM_SYNC("aim-sync"),

    /** Aim, simulated mount connected, the nudge pad. */
    AIM_NUDGE("aim-nudge"),

    /** Tonight, the calendar of astronomical events. */
    EVENTS("events"),

    /** Tonight, the calendar, and the detail of one event (see `event=`). */
    EVENT_DETAIL("event-detail"),

    /** Aim with the mosaic planning sheet open. */
    MOSAIC("mosaic"),

    /** A mosaic of the target is running (Aim and Sky chart guide through its panels). */
    MOSAIC_RUNNING("mosaic-running"),

    /** Settings, the screen that records the skyline of the site (with a partial sweep played into it). */
    SKYLINE("skyline"),

    /** Settings, the editor of the observing site, with its Horizon section. */
    SITE_EDITOR("site-editor"),

    /** Settings, Star Trails & Time-Lapse, on star trails. */
    STAR_TRAILS("star-trails"),

    /** Settings, Star Trails & Time-Lapse, on time-lapse. */
    TIME_LAPSE("time-lapse"),
    ;

    companion object {
        fun named(id: String): ScreenshotScreen? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Debug builds only: a fixed site, time, tab, target and camera pointing for store screenshots, passed as one
 * intent extra, e.g. `adb shell am start -n net.kuecker.lodestar/.app.MainActivity --es lodestarScreenshot
 * "tab=aim date=2026-07-17T05:30:00Z lat=36.0544 lon=-112.1401 elevation=2130 site=Grand_Canyon
 * tz=America/Phoenix target=special.galacticCenter pointing=172,18 night=0"`.
 *
 * Optional keys for reviewing sub-screens: `screen=<id>` ([ScreenshotScreen]) opens a sub-screen or sheet after launch,
 * `event=<kind>` picks the event of `screen=event-detail`, `mosaic=target|milkyWay|custom` the source of the mosaic
 * plan, `skyline=1` gives the site a made-up skyline, `camera=1` turns the camera picture on and `focal=<mm>` sets the
 * focal length of the lens (24 mm without it). Unknown keys are
 * ignored; a known key with a value that does not exist makes the whole argument invalid, so a typo shows at once.
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
    val screen: ScreenshotScreen? = null,
    val eventKind: EventKind? = null,
    val mosaicSource: MosaicSource? = null,
    val hasSkyline: Boolean = false,
    val showsCamera: Boolean = false,
    val focalLength: Double? = null,
) {
    companion object {
        const val EXTRA = "lodestarScreenshot"

        /** The focal lengths (mm) the lens settings allow. */
        private val FOCAL_LENGTHS = 4.0..2000.0

        /** The active screenshot configuration (debug builds, set by the activity before launch). */
        var current: ScreenshotMode? = null
            private set

        fun install(argument: String?) {
            if (BuildConfig.DEBUG && argument != null) current = parse(argument)
        }

        /** The `key=value` pairs of [argument]; parts without a key are dropped. */
        fun values(argument: String): Map<String, String> =
            argument.trim().split(Regex("\\s+")).mapNotNull { part ->
                val index = part.indexOf('=')
                if (index > 0) part.substring(0, index) to part.substring(index + 1) else null
            }.toMap()

        fun parse(argument: String): ScreenshotMode? {
            val values = values(argument)
            val date = values["date"]?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
            val latitude = values["lat"]?.toDoubleOrNull() ?: return null
            val longitude = values["lon"]?.toDoubleOrNull() ?: return null
            val pointing = values["pointing"]?.split(",")?.mapNotNull(String::toDoubleOrNull)
                ?.takeIf { it.size == 2 }?.let { HorizontalCoordinate(it[0], it[1]) }
            val screen = values["screen"]?.let { ScreenshotScreen.named(it) ?: return null }
            val eventKind = values["event"]?.let { name ->
                EventKind.entries.firstOrNull { it.rawValue.equals(name, ignoreCase = true) } ?: return null
            }
            val mosaicSource = values["mosaic"]?.let { name ->
                MosaicSource.entries.firstOrNull { it.name.replace("_", "").equals(name, ignoreCase = true) } ?: return null
            }
            val focalLength = values["focal"]?.let { text ->
                text.toDoubleOrNull()?.takeIf { it in FOCAL_LENGTHS } ?: return null
            }
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
                screen = screen,
                eventKind = eventKind,
                mosaicSource = mosaicSource,
                hasSkyline = values["skyline"] == "1",
                showsCamera = values["camera"] == "1",
                focalLength = focalLength,
            )
        }

        /**
         * A made-up skyline for screenshots: low hills all round, a ridge in the east, tall trees in the west and a
         * mountain in the north.
         */
        fun demoSkyline(): HorizonProfile = HorizonProfile.create(
            List(HorizonProfile.binCount) { azimuth ->
                fun bump(center: Double, width: Double): Double {
                    val offset = ((azimuth - center + 540.0) % 360.0) - 180.0
                    return exp(-(offset / width) * (offset / width))
                }
                val hills = 3.5 + 2.5 * sin(azimuth * PI / 180.0 * 3.0 + 1.0)
                val trees = if (azimuth in 235..300) 2.5 * abs(sin(azimuth * PI / 180.0 * 17.0)) else 0.0
                (hills + 13.0 * bump(80.0, 25.0) + 9.0 * bump(265.0, 30.0) + 18.0 * bump(355.0, 12.0) + trees)
                    .coerceIn(0.0, 40.0)
            },
        ) ?: HorizonProfile.flat
    }
}

/**
 * Runs [action] when the current screenshot asks for one of [screens] and the composable enters the composition (debug
 * builds only; elsewhere this composes nothing). Put it in the screen that owns the navigation or the sheet the id
 * stands for.
 */
@Composable
fun ScreenshotScreenEffect(vararg screens: ScreenshotScreen, action: suspend (ScreenshotScreen) -> Unit) {
    val wanted = ScreenshotMode.current?.screen?.takeIf { it in screens } ?: return
    LaunchedEffect(wanted) { action(wanted) }
}
