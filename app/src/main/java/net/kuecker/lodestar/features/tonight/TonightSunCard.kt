package net.kuecker.lodestar.features.tonight

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WbTwilight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.kuecker.lodestar.kit.planning.NightSummary
import net.kuecker.lodestar.shared.AppFormat
import net.kuecker.lodestar.shared.CardTitle
import net.kuecker.lodestar.shared.InfoRow
import net.kuecker.lodestar.theme.LocalPalette
import net.kuecker.lodestar.theme.card
import java.time.Instant
import java.time.ZoneId

/** Sunset, the three twilights and sunrise, with the length of the dark in between. */
@Composable
internal fun TonightSunCard(summary: NightSummary, zone: ZoneId) {
    val palette = LocalPalette.current
    val twilight = summary.twilight
    Column(Modifier.fillMaxWidth().card(palette), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CardTitle("Sun & Twilight", icon = Icons.Outlined.WbTwilight)
        TimeRow("Sunset", twilight.sunset, zone)
        TimeRow("Civil dusk", twilight.civilDusk, zone)
        TimeRow("Nautical dusk", twilight.nauticalDusk, zone)
        TimeRow("Astronomical dusk", twilight.astronomicalDusk, zone)
        if (summary.hasAstronomicalDarkness) {
            InfoRow(
                "Dark sky",
                TonightFormat.windows(summary.astronomicalDarknessIntervals, zone) +
                    " (${TonightFormat.duration(summary.astronomicalDarkHours)})",
            )
        } else {
            InfoRow("Dark sky", "No astronomical darkness")
        }
        TimeRow("Astronomical dawn", twilight.astronomicalDawn, zone)
        TimeRow("Nautical dawn", twilight.nauticalDawn, zone)
        TimeRow("Civil dawn", twilight.civilDawn, zone)
        TimeRow("Sunrise", twilight.sunrise, zone)
        TonightNote(
            "Dusk and dawn are when the Sun is 6°, 12° and 18° below the horizon. The sky is fully dark between " +
                "astronomical dusk and dawn.",
            style = TonightNoteStyle.FOOTNOTE,
        )
    }
}

@Composable
private fun TimeRow(label: String, date: Instant?, zone: ZoneId) {
    InfoRow(label, AppFormat.time(date, zone))
}
