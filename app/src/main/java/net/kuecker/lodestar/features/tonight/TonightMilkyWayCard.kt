package net.kuecker.lodestar.features.tonight

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NightsStay
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.kuecker.lodestar.kit.formatting.AngleFormatter
import net.kuecker.lodestar.kit.planning.MilkyWayCoreVisibility
import net.kuecker.lodestar.kit.planning.NightSummary
import net.kuecker.lodestar.shared.AppFormat
import net.kuecker.lodestar.shared.CardTitle
import net.kuecker.lodestar.shared.InfoRow
import net.kuecker.lodestar.theme.LocalPalette
import net.kuecker.lodestar.theme.card
import java.time.ZoneId

/** When and where the Milky Way's core, the Galactic Centre in Sagittarius, can be photographed. */
@Composable
internal fun TonightMilkyWayCard(summary: NightSummary, zone: ZoneId, isSkylineApplied: Boolean) {
    val palette = LocalPalette.current
    val core = summary.milkyWayCore
    val minimum = MilkyWayCoreVisibility.minimumAltitude.toInt()
    Column(Modifier.fillMaxWidth().card(palette), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CardTitle("Milky Way Core", icon = Icons.Outlined.NightsStay)
        if (!summary.hasAstronomicalDarkness) {
            TonightNote("There is no astronomical darkness on this night, so the core cannot be photographed.")
        } else if (core.visibleIntervals.isEmpty()) {
            TonightNote(
                if (isSkylineApplied) {
                    "The Galactic Centre stays behind your skyline or below $minimum° while it is dark."
                } else {
                    "The Galactic Centre stays below $minimum° while it is dark."
                },
            )
            HorizonRows(core, zone, isSkylineApplied)
        } else {
            VisibleRows(core, zone, minimum, isSkylineApplied)
        }
        if (summary.hasAstronomicalDarkness && isSkylineApplied) {
            TonightSkylineNote("The core counts as visible only while it is above your recorded skyline.")
        }
    }
}

/** When the core rises and sets, for a night on which it never gets high enough in the dark. */
@Composable
private fun HorizonRows(core: MilkyWayCoreVisibility, zone: ZoneId, isSkylineApplied: Boolean) {
    core.riseTransitSet.rise?.let {
        InfoRow(if (isSkylineApplied) "Clears the skyline" else "Rises", AppFormat.time(it, zone))
    }
    core.riseTransitSet.set?.let {
        InfoRow(if (isSkylineApplied) "Sinks behind the skyline" else "Sets", AppFormat.time(it, zone))
    }
}

@Composable
private fun VisibleRows(core: MilkyWayCoreVisibility, zone: ZoneId, minimum: Int, isSkylineApplied: Boolean) {
    InfoRow("Visible window", TonightFormat.windows(core.visibleIntervals, zone))
    if (core.moonFreeVisibleIntervals != core.visibleIntervals) {
        InfoRow(
            "Without the Moon",
            if (core.moonFreeVisibleIntervals.isEmpty()) {
                "The Moon is up the whole time"
            } else {
                TonightFormat.windows(core.moonFreeVisibleIntervals, zone)
            },
        )
    }
    InfoRow("Best time", AppFormat.time(core.bestTime, zone))
    InfoRow("Max altitude", core.maxAltitude?.let { AngleFormatter.formatDegrees(it, 0) } ?: AppFormat.MISSING)
    InfoRow("Direction", directionText(core))
    TonightNote(
        "Visible means at least $minimum° above the ${if (isSkylineApplied) "horizon and your skyline" else "horizon"} " +
            "during astronomical darkness. Direction and height are for the best time.",
        style = TonightNoteStyle.FOOTNOTE,
    )
}

/** The compass point of the core at its best time. */
private fun directionText(core: MilkyWayCoreVisibility): String {
    val azimuth = core.bestAzimuth ?: return AppFormat.MISSING
    val altitude = core.maxAltitude ?: return AppFormat.MISSING
    return TonightFormat.direction(azimuth, altitude)
}
