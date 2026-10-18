package net.kuecker.lodestar.features.objects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material.icons.outlined.NorthEast
import androidx.compose.material.icons.outlined.SouthEast
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import net.kuecker.lodestar.model.CoordinateStyle
import net.kuecker.lodestar.shared.AppFormat
import net.kuecker.lodestar.shared.CardTitle
import net.kuecker.lodestar.shared.ChipTone
import net.kuecker.lodestar.shared.InfoRow
import net.kuecker.lodestar.shared.StatusChip
import net.kuecker.lodestar.theme.LocalPalette
import net.kuecker.lodestar.theme.card

/** A spinner shown in a card while its data is being computed. */
@Composable
internal fun ObjectDetailPending(modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    Box(modifier.fillMaxWidth().heightIn(min = 60.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            color = palette.accent,
            trackColor = palette.stroke,
            modifier = Modifier.size(28.dp).semantics { contentDescription = "Calculating" },
        )
    }
}

/** Where the object is right now, and what it looks like ([current] is `null` while it is being computed). */
@Composable
internal fun ObjectDetailNowCard(current: ObjectDetailCurrent?, style: CoordinateStyle, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    Column(modifier.fillMaxWidth().card(palette), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CardTitle("Now", icon = Icons.Outlined.Navigation)
        if (current != null) {
            Rows(current, style)
        } else {
            ObjectDetailPending()
        }
    }
}

@Composable
private fun Rows(current: ObjectDetailCurrent, style: CoordinateStyle) {
    val isUp = current.horizontal.altitude > 0
    StatusChip(
        icon = if (isUp) Icons.Outlined.NorthEast else Icons.Outlined.SouthEast,
        text = if (isUp) "Above the horizon" else "Below the horizon",
        tone = if (isUp) ChipTone.POSITIVE else ChipTone.NEUTRAL,
    )
    InfoRow("Altitude", AppFormat.altitude(current.horizontal.altitude))
    InfoRow("Azimuth", AppFormat.azimuth(current.horizontal.azimuth))
    InfoRow("RA (of date)", AppFormat.rightAscension(current.equatorial, style))
    InfoRow("Dec (of date)", AppFormat.declination(current.equatorial, style))
    current.magnitude?.let { InfoRow("Brightness", AppFormat.magnitude(it)) }
    current.angularSize?.let { InfoRow("Apparent size", it.description) }
    current.distance?.let { InfoRow("Distance", it.text) }
    val phase = current.moonPhase
    val illuminated = current.illuminatedFraction
    if (phase != null) {
        InfoRow("Phase", "${phase.name.displayName} · ${AppFormat.percent(phase.illuminatedFraction)} lit")
    } else if (illuminated != null) {
        InfoRow("Illuminated", AppFormat.percent(illuminated))
    }
}
