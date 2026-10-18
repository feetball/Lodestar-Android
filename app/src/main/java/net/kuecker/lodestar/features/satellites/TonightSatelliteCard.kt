package net.kuecker.lodestar.features.satellites

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SatelliteAlt
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.kuecker.lodestar.features.online.rememberSkyNow
import net.kuecker.lodestar.kit.foundation.DateInterval
import net.kuecker.lodestar.kit.foundation.Observer
import net.kuecker.lodestar.services.OnlineFeed
import net.kuecker.lodestar.services.SatellitePassItem
import net.kuecker.lodestar.services.SatellitePasses
import net.kuecker.lodestar.shared.AppFormat
import net.kuecker.lodestar.shared.CardTitle
import net.kuecker.lodestar.shared.LocalAppModel
import net.kuecker.lodestar.shared.LodestarButton
import net.kuecker.lodestar.shared.LodestarButtonKind
import net.kuecker.lodestar.shared.monospacedDigits
import net.kuecker.lodestar.theme.LocalPalette
import net.kuecker.lodestar.theme.card
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** The most passes the card lists. */
private const val CARD_LIMIT = 4

/**
 * "Satellite passes" on the Tonight screen: the next visible passes of the night shown, the ISS first, each with its time,
 * peak height and direction and brightness. Appears only while the Satellite passes feed is on; it opens the Passes
 * screen and the pass details. [night] is the Tonight screen's own night (sunset to sunrise).
 */
@Composable
fun TonightSatelliteCard(observer: Observer, night: DateInterval, zone: ZoneId) {
    val app = LocalAppModel.current
    val online = app.online
    if (!online.isEnabled(OnlineFeed.SATELLITE_PASSES)) return
    val palette = LocalPalette.current
    val requests = rememberSatelliteRequests()
    val now = rememberSkyNow()
    val board = rememberSatellitePassBoard(observer)
    val library = online.satellites
    val status = online.statuses.getValue(OnlineFeed.SATELLITE_PASSES)
    val items = board.items
    Column(Modifier.fillMaxWidth().card(palette), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CardTitle(SatelliteText.CARD_TITLE, icon = Icons.Outlined.SatelliteAlt)
        when {
            !library.hasPassData -> Note(if (status.isUpdating || status.error == null) SatelliteText.LOADING else status.error.orEmpty())
            items == null -> Note(SatelliteText.FINDING)
            !night.end.isAfter(now) -> Note(SatelliteText.NIGHT_OVER)
            night.start.isAfter(now.plus(Duration.ofDays(SatellitePasses.HORIZON_DAYS.toLong()))) -> Note(SatelliteText.TOO_FAR)
            else -> {
                val passes = SatellitePasses.tonight(items, night, now, CARD_LIMIT)
                if (passes.isEmpty()) {
                    Note(SatelliteText.NO_PASSES_TONIGHT)
                } else {
                    Column {
                        passes.forEachIndexed { index, item ->
                            if (index > 0) HorizontalDivider(thickness = 0.5.dp, color = palette.stroke)
                            PassRow(item, zone, now, onClick = { requests.open(SatelliteScreen.Detail(item)) })
                        }
                    }
                }
                if (library.isPassDataStale(now)) {
                    Text(SatelliteText.STALE, color = palette.warning, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (library.hasPassData) {
            LodestarButton(
                SatelliteText.ALL_PASSES, onClick = { requests.open(SatelliteScreen.Passes) },
                modifier = Modifier.fillMaxWidth(), kind = LodestarButtonKind.SECONDARY,
            )
        }
        Text(SatelliteText.ATTRIBUTION, color = palette.tertiaryText, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Note(text: String) {
    Text(text, color = LocalPalette.current.secondaryText, style = MaterialTheme.typography.bodyMedium)
}

/** One pass: the satellite and its brightness over the visible time, the peak and a tag when its orbit data is old. */
@Composable
internal fun PassRow(item: SatellitePassItem, zone: ZoneId, now: Instant, onClick: (() -> Unit)?) {
    val palette = LocalPalette.current
    val clickable = if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier
    Column(
        Modifier.fillMaxWidth().then(clickable).heightIn(min = 48.dp).padding(vertical = 8.dp)
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                item.name, color = palette.primaryText,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.weight(1f),
            )
            Text(SatelliteText.magnitude(item), color = palette.secondaryText, style = MaterialTheme.typography.bodyMedium.monospacedDigits())
        }
        val tag = if (item.isFresh(now)) "" else " · ${SatelliteText.STALE_TAG}"
        Text(
            "${SatelliteText.span(item, zone)} · ${SatelliteText.peak(item.pass.culmination)}$tag",
            color = if (item.isFresh(now)) palette.secondaryText else palette.warning,
            style = MaterialTheme.typography.bodyMedium.monospacedDigits(),
        )
    }
}

/** The weekday and date a pass is listed under: "Today", "Tomorrow" or "Sat, Oct 20". */
internal fun dayLabel(item: SatellitePassItem, now: Instant, zone: ZoneId): String {
    val day = item.start.atZone(zone).toLocalDate()
    val today = now.atZone(zone).toLocalDate()
    return when (day) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        else -> AppFormat.day(item.start, zone)
    }
}
