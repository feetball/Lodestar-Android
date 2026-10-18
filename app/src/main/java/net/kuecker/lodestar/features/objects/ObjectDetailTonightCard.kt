package net.kuecker.lodestar.features.objects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NightsStay
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.kuecker.lodestar.kit.catalog.CelestialObject
import net.kuecker.lodestar.kit.catalog.ObjectCategory
import net.kuecker.lodestar.kit.ephemeris.RiseTransitSet
import net.kuecker.lodestar.shared.AppFormat
import net.kuecker.lodestar.shared.CardTitle
import net.kuecker.lodestar.shared.InfoRow
import net.kuecker.lodestar.theme.LocalPalette
import net.kuecker.lodestar.theme.card
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * When the object rises, culminates and sets tonight, with the altitude chart. [now] is the present moment, for the
 * chart's "now" line; [night] is `null` while it is being computed.
 */
@Composable
internal fun ObjectDetailTonightCard(
    obj: CelestialObject,
    night: ObjectDetailNight?,
    now: Instant?,
    zone: ZoneId,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    Column(modifier.fillMaxWidth().card(palette), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CardTitle("Tonight", icon = Icons.Outlined.NightsStay)
        if (night != null) {
            Content(obj, night, now, zone)
        } else {
            ObjectDetailPending()
        }
    }
}

@Composable
private fun Content(obj: CelestialObject, night: ObjectDetailNight, now: Instant?, zone: ZoneId) {
    val palette = LocalPalette.current
    val day = AppFormat.day(night.interval.start, zone)
    Text(
        "Night of $day · times in ${zoneName(zone, night.interval.start)}",
        color = palette.secondaryText,
        style = MaterialTheme.typography.bodySmall,
    )
    ObjectDetailAltitudeChart(night, now, zone)
    Text(
        "Lighter bands show twilight; the dashed line is the horizon.",
        color = palette.tertiaryText,
        style = MaterialTheme.typography.labelMedium,
    )
    EventRows(night, zone)
    DarknessRows(obj, night, zone)
}

/** The observer's time zone as people write it, such as "MDT". */
private fun zoneName(zone: ZoneId, date: Instant): String =
    DateTimeFormatter.ofPattern("zzz", Locale.getDefault()).format(date.atZone(zone))

@Composable
private fun EventRows(night: ObjectDetailNight, zone: ZoneId) {
    when (night.events.circumstance) {
        RiseTransitSet.Circumstance.ALWAYS_DOWN -> InfoRow("Visibility", "Stays below the horizon")
        RiseTransitSet.Circumstance.ALWAYS_UP -> {
            InfoRow("Visibility", "Always above the horizon")
            TimelineRows(night, zone)
        }
        RiseTransitSet.Circumstance.NORMAL -> TimelineRows(night, zone)
    }
}

@Composable
private fun TimelineRows(night: ObjectDetailNight, zone: ZoneId) {
    for (event in night.timeline) {
        val title = when (event.kind) {
            ObjectDetailEvent.Kind.RISE -> "Rises"
            ObjectDetailEvent.Kind.TRANSIT -> "Highest point"
            ObjectDetailEvent.Kind.SET -> "Sets"
        }
        InfoRow(title, eventValue(event, night, zone))
    }
}

/** The time of an event; for the highest point also the altitude reached. */
private fun eventValue(event: ObjectDetailEvent, night: ObjectDetailNight, zone: ZoneId): String {
    val time = AppFormat.time(event.date, zone)
    val altitude = night.transitAltitude
    if (event.kind != ObjectDetailEvent.Kind.TRANSIT || altitude == null) return time
    return "$time · ${AppFormat.altitude(altitude)}"
}

@Composable
private fun DarknessRows(obj: CelestialObject, night: ObjectDetailNight, zone: ZoneId) {
    val darkness = night.darkness
    if (darkness == null) {
        InfoRow("Dark sky", "No astronomical darkness tonight")
        return
    }
    InfoRow("Dark sky", "${AppFormat.time(darkness.start, zone)} – ${AppFormat.time(darkness.end, zone)}")
    val best = night.bestInDarkness
    if (obj.category != ObjectCategory.SUN && best != null) {
        val text = if (best.altitude > 0) {
            "${AppFormat.altitude(best.altitude)} at ${AppFormat.time(best.date, zone)}"
        } else {
            "Below the horizon"
        }
        InfoRow("Highest in the dark", text)
    }
}
