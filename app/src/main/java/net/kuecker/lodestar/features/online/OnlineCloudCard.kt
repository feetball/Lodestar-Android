package net.kuecker.lodestar.features.online

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import net.kuecker.lodestar.features.tonight.TonightFormat
import net.kuecker.lodestar.kit.foundation.DateInterval
import net.kuecker.lodestar.kit.foundation.GeographicLocation
import net.kuecker.lodestar.services.CloudForecast
import net.kuecker.lodestar.services.CloudSlot
import net.kuecker.lodestar.services.ForecastNight
import net.kuecker.lodestar.services.OnlineCoordinates
import net.kuecker.lodestar.services.OnlineFeed
import net.kuecker.lodestar.shared.AppFormat
import net.kuecker.lodestar.shared.CardTitle
import net.kuecker.lodestar.shared.LocalAppModel
import net.kuecker.lodestar.theme.LocalPalette
import net.kuecker.lodestar.theme.Palette
import net.kuecker.lodestar.theme.card
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** The words of the "Clouds tonight" card. The iOS app uses the same texts. */
internal object CloudCardText {
    const val TITLE = "Clouds tonight"
    const val LOADING = "Loading the forecast…"
    const val ATTRIBUTION = "Forecast: MET Norway"
    const val ROUNDING = "Location rounded to about 10 km."
    const val BEFORE_FORECAST = "The forecast covers only the time from now on."
    const val BEYOND_FORECAST = "No forecast for this night yet. The forecast reaches about nine days ahead."
    const val COARSE = "Further ahead the forecast has one value every six hours."

    /** "Issued 19:17 · Forecast: MET Norway · Location rounded to about 10 km." */
    fun footnote(issuedText: String): String = "Issued $issuedText · $ATTRIBUTION · $ROUNDING"
}

/**
 * "Clouds tonight": the hourly cloud cover from dusk to dawn of the night shown, total and the low, medium and high
 * layers, as a compact strip with a summary of the clear spells. Appears only while the Cloud forecast is switched on.
 * [night] is the Tonight screen's own night (sunset to sunrise) at [place].
 */
@Composable
fun TonightCloudCard(place: GeographicLocation, night: DateInterval, zone: ZoneId) {
    val app = LocalAppModel.current
    val online = app.online
    if (!online.isEnabled(OnlineFeed.CLOUDS)) return
    val palette = LocalPalette.current
    val status = online.statuses.getValue(OnlineFeed.CLOUDS)
    val key = OnlineCoordinates.key(place.latitude, place.longitude)
    // A place that differs by more than the rounding is a new request; the same place waits for the server's Expires.
    // The forecast is looked at again every quarter of an hour while the card is in view; the server's Expires decides
    // whether anything is requested. Nothing runs while the app is in the background.
    LaunchedEffect(key, app.isForeground) {
        while (app.isForeground) {
            online.refreshForecast()
            delay(RECHECK_MILLIS)
        }
    }
    val forecast = online.forecast?.takeIf { it.key == key }
    Column(Modifier.fillMaxWidth().card(palette), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CardTitle(CloudCardText.TITLE, icon = Icons.Outlined.Cloud)
        if (forecast == null) {
            val text = when {
                status.isUpdating -> CloudCardText.LOADING
                status.error != null -> status.error
                else -> CloudCardText.LOADING
            }
            Text(text, color = palette.secondaryText, style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        CloudForecastContent(forecast, night, zone)
    }
}

@Composable
private fun CloudForecastContent(forecast: CloudForecast, night: DateInterval, zone: ZoneId) {
    val palette = LocalPalette.current
    val slots = ForecastNight.slots(forecast, night)
    val timeText: (Instant) -> String = { AppFormat.time(it, zone) }
    if (slots.isEmpty()) {
        val first = forecast.hours.firstOrNull()?.time
        val text = if (first != null && !night.end.isAfter(first)) CloudCardText.BEFORE_FORECAST else CloudCardText.BEYOND_FORECAST
        Text(text, color = palette.secondaryText, style = MaterialTheme.typography.bodyMedium)
    } else {
        val summary = ForecastNight.summary(slots, timeText)
        if (summary != null) {
            Text(summary, color = palette.primaryText, style = MaterialTheme.typography.titleMedium)
        }
        CloudStrip(slots, zone, ForecastNight.spokenSummary(slots, timeText).orEmpty())
        if (slots.any { it.seconds > 3600.0 + 1.0 }) {
            Text(CloudCardText.COARSE, color = palette.tertiaryText, style = MaterialTheme.typography.bodySmall)
        }
    }
    Text(
        CloudCardText.footnote(timeText(forecast.issuedAt)),
        color = palette.tertiaryText,
        style = MaterialTheme.typography.bodySmall,
    )
}

// The strip

private const val RECHECK_MILLIS = 15 * 60_000L

private val TOTAL_HEIGHT = 40.dp
private val LAYER_HEIGHT = 9.dp
private val GAP = 3.dp
private val AXIS_HEIGHT = 16.dp
private val LABEL_WIDTH = 38.dp

/**
 * One column per forecast slot: a bar as high as the total cloud, and under it three thin cells shaded by the high,
 * medium and low layers. Drawn from the palette only, so it is red at night. [description] is what a screen reader says.
 */
@Composable
private fun CloudStrip(slots: List<CloudSlot>, zone: ZoneId, description: String) {
    val palette = LocalPalette.current
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = palette.secondaryText, fontSize = 10.sp)
    Row(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = description }) {
        Column(Modifier.width(LABEL_WIDTH), verticalArrangement = Arrangement.spacedBy(GAP)) {
            LayerLabel("Total", TOTAL_HEIGHT)
            LayerLabel("High", LAYER_HEIGHT)
            LayerLabel("Mid", LAYER_HEIGHT)
            LayerLabel("Low", LAYER_HEIGHT)
        }
        val totalHeight = TOTAL_HEIGHT + (LAYER_HEIGHT + GAP) * 3 + AXIS_HEIGHT
        Canvas(Modifier.weight(1f).height(totalHeight)) {
            drawStrip(slots, zone, palette, measurer, labelStyle)
        }
    }
}

@Composable
private fun LayerLabel(text: String, height: Dp) {
    val palette = LocalPalette.current
    Box(Modifier.height(height), contentAlignment = Alignment.CenterStart) {
        Text(text, color = palette.secondaryText, style = MaterialTheme.typography.labelSmall)
    }
}

private fun DrawScope.drawStrip(
    slots: List<CloudSlot>,
    zone: ZoneId,
    palette: Palette,
    measurer: TextMeasurer,
    labelStyle: TextStyle,
) {
    val start = slots.first().start
    val end = slots.last().end
    val span = (end.toEpochMilli() - start.toEpochMilli()).toDouble()
    if (span <= 0) return
    val width = size.width
    fun x(time: Instant): Float = ((time.toEpochMilli() - start.toEpochMilli()) / span * width).toFloat()
    val totalHeight = TOTAL_HEIGHT.toPx()
    val layerHeight = LAYER_HEIGHT.toPx()
    val gap = GAP.toPx()
    val hairline = 1.dp.toPx()
    // A faint floor for the bars, so an empty (clear) hour still shows as a slot.
    drawRect(palette.stroke, Offset(0f, totalHeight - hairline), Size(width, hairline))
    for (slot in slots) {
        val left = x(slot.start)
        val slotWidth = (x(slot.end) - left - hairline).coerceAtLeast(1f)
        val barHeight = (slot.total / 100.0 * totalHeight).toFloat()
        drawRect(palette.accent.copy(alpha = 0.85f), Offset(left, totalHeight - barHeight), Size(slotWidth, barHeight))
        var top = totalHeight + gap
        for (layer in listOf(slot.high, slot.medium, slot.low)) {
            drawRect(palette.surfaceRaised, Offset(left, top), Size(slotWidth, layerHeight))
            drawRect(
                palette.primaryText.copy(alpha = (layer / 100.0).toFloat().coerceIn(0f, 1f) * 0.9f),
                Offset(left, top), Size(slotWidth, layerHeight),
            )
            top += layerHeight + gap
        }
    }
    // Hour labels on the whole hours of the observer's time zone.
    val axisTop = totalHeight + 3 * (layerHeight + gap)
    for (tick in hourTicks(start, end, zone)) {
        val label = measurer.measure(TonightFormat.hourLabel(tick, zone), labelStyle)
        val left = (x(tick) - label.size.width / 2f).coerceIn(0f, (width - label.size.width).coerceAtLeast(0f))
        drawText(label, topLeft = Offset(left, axisTop))
    }
}

/** The whole hours inside the night that get a label, every hour or every second, third, fourth or sixth one. */
internal fun hourTicks(start: Instant, end: Instant, zone: ZoneId): List<Instant> {
    val hours = (end.toEpochMilli() - start.toEpochMilli()) / 3_600_000.0
    val spacing = listOf(1, 2, 3, 4).firstOrNull { hours / it <= 7.5 } ?: 6
    val ticks = ArrayList<Instant>()
    var cursor = start.atZone(zone).truncatedTo(ChronoUnit.HOURS).plusHours(1)
    var guard = 0
    while (cursor.toInstant() < end && guard++ < 48) {
        if (cursor.hour % spacing == 0) ticks.add(cursor.toInstant())
        cursor = cursor.plusHours(1)
    }
    return ticks
}
