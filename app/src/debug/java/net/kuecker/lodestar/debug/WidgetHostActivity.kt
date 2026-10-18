package net.kuecker.lodestar.debug

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.SizeF
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.kuecker.lodestar.app.ScreenshotMode
import net.kuecker.lodestar.features.widget.TonightSmallWidgetReceiver
import net.kuecker.lodestar.features.widget.TonightWidgetReceiver
import net.kuecker.lodestar.features.widget.WidgetInputs
import net.kuecker.lodestar.features.widget.WidgetInputsStore
import net.kuecker.lodestar.features.widget.WidgetModels
import net.kuecker.lodestar.features.widget.WidgetPlace
import net.kuecker.lodestar.features.widget.WidgetRefresher
import net.kuecker.lodestar.model.NightModeSetting
import java.time.Instant

/**
 * Debug builds only: shows the Tonight widgets the way a launcher does, in an [AppWidgetHost], so that the review
 * script can photograph them in the day and the night palette at the sizes of the widget picker and resized.
 *
 * It takes the same argument as screenshot mode (`--es lodestarScreenshot "date=… lat=… lon=… site=… tz=… night=1"`)
 * and these keys of its own: `sizes=110x110,250x110` (width x height in dp, one widget each; the small widget of the
 * picker below 200 dp wide, the medium one from there) and `place=none` (the app has no location yet). Binding needs
 * the permission the shell can give: `adb shell appwidget grantbind --package net.kuecker.lodestar --user 0`.
 */
class WidgetHostActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var host: AppWidgetHost
    private val widgetIDs = mutableListOf<Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val values = ScreenshotMode.values(intent.getStringExtra(ScreenshotMode.EXTRA).orEmpty())
        val isNight = values["night"] == "1"
        val date = values["date"]?.let { runCatching { Instant.parse(it) }.getOrNull() }
        val sizes = (values["sizes"] ?: DEFAULT_SIZES).split(",").mapNotNull(::parseSize)

        // The widgets work from what the app stored last and from the clock: both are set here, so that a run does
        // not depend on the weather of the night it runs on.
        date?.let { fixed -> WidgetModels.clock = { fixed } }
        WidgetInputsStore(this).write(
            WidgetInputs(
                place = if (values["place"] == "none") null else place(values),
                nightMode = if (isNight) NightModeSetting.ON else NightModeSetting.OFF,
            ),
        )

        host = AppWidgetHost(this, HOST_ID)
        // Widgets of earlier runs (the app was force-stopped before it could delete them) are not wanted.
        host.deleteHost()
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val caption = if (isNight) "Night palette" else "Day palette"
        content.addView(label("Tonight widgets · $caption", isNight, bold = true))
        for (size in sizes) addWidget(content, size.first, size.second, isNight)

        val root = ScrollView(this).apply {
            setBackgroundColor(if (isNight) Color.BLACK else DAY_WALLPAPER)
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(dp(PAGE_PADDING), bars.top + dp(PAGE_PADDING), dp(PAGE_PADDING), bars.bottom + dp(PAGE_PADDING))
            WindowInsetsCompat.CONSUMED
        }
        setContentView(root)

        // The first draw happens when the system tells the provider about the new ids; this one makes sure of it.
        scope.launch {
            delay(REFRESH_DELAY_MS)
            WidgetRefresher.refresh(applicationContext)
        }
    }

    override fun onStart() {
        super.onStart()
        host.startListening()
    }

    override fun onStop() {
        host.stopListening()
        super.onStop()
    }

    override fun onDestroy() {
        scope.cancel()
        widgetIDs.forEach(host::deleteAppWidgetId)
        super.onDestroy()
    }

    private fun place(values: Map<String, String>): WidgetPlace = WidgetPlace(
        name = values["site"]?.replace('_', ' ') ?: "Demo Site",
        latitude = values["lat"]?.toDoubleOrNull() ?: DEFAULT_LATITUDE,
        longitude = values["lon"]?.toDoubleOrNull() ?: DEFAULT_LONGITUDE,
        elevation = values["elevation"]?.toDoubleOrNull() ?: 0.0,
        siteID = "screenshot",
        timeZoneID = values["tz"],
    )

    /** One widget of [width] × [height] dp, with a line saying which it is. */
    private fun addWidget(content: LinearLayout, width: Int, height: Int, isNight: Boolean) {
        val manager = AppWidgetManager.getInstance(this)
        val isSmall = width < MEDIUM_FROM_DP
        val provider = ComponentName(this, if (isSmall) TonightSmallWidgetReceiver::class.java else TonightWidgetReceiver::class.java)
        val title = (if (isSmall) "Small" else "Medium") + " · $width × $height dp"
        content.addView(label(title, isNight))

        val id = host.allocateAppWidgetId()
        widgetIDs += id
        if (!manager.bindAppWidgetIdIfAllowed(id, provider)) {
            Log.e(TAG, "Binding $provider was refused: run `adb shell appwidget grantbind --package $packageName --user 0`")
            content.addView(label("Binding was refused (grantbind is missing).", isNight))
            return
        }
        val info = manager.getAppWidgetInfo(id)
        val view = host.createView(this, id, info)
        val bounds = Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, width)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, width)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, height)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, height)
        }
        manager.updateAppWidgetOptions(id, bounds)
        tell(view, bounds, width, height)
        content.addView(
            FrameLayout(this).apply {
                addView(view, FrameLayout.LayoutParams(dp(width), dp(height)))
            },
            LinearLayout.LayoutParams(dp(width), dp(height)).apply { bottomMargin = dp(WIDGET_GAP) },
        )
    }

    /** Tells the widget view how much room it has, as a launcher does. */
    private fun tell(view: AppWidgetHostView, bounds: Bundle, width: Int, height: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            view.updateAppWidgetSize(bounds, listOf(SizeF(width.toFloat(), height.toFloat())))
        } else {
            @Suppress("DEPRECATION")
            view.updateAppWidgetSize(bounds, width, height, width, height)
        }
    }

    private fun label(text: String, isNight: Boolean, bold: Boolean = false): View = TextView(this).apply {
        this.text = text
        setTextColor(if (isNight) NIGHT_LABEL else Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, if (bold) 18f else 13f)
        gravity = Gravity.START
        setPadding(0, dp(if (bold) 0 else 4), 0, dp(4))
    }

    private fun parseSize(text: String): Pair<Int, Int>? {
        val parts = text.trim().lowercase().split("x")
        val width = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val height = parts.getOrNull(1)?.toIntOrNull() ?: return null
        return if (parts.size == 2 && width > 0 && height > 0) width to height else null
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "WidgetHost"
        const val HOST_ID = 0x4C53
        const val DEFAULT_SIZES = "110x110,250x110"
        const val DEFAULT_LATITUDE = 36.0544
        const val DEFAULT_LONGITUDE = -112.1401
        const val MEDIUM_FROM_DP = 200
        const val PAGE_PADDING = 16
        const val WIDGET_GAP = 12
        const val REFRESH_DELAY_MS = 1_500L
        const val DAY_WALLPAPER = 0xFF5F7F9F.toInt()
        const val NIGHT_LABEL = 0xFF7A0000.toInt()
    }
}
