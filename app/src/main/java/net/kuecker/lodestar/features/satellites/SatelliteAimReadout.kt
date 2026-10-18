package net.kuecker.lodestar.features.satellites

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.SatelliteAlt
import androidx.compose.material.icons.outlined.VerticalAlignBottom
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.kuecker.lodestar.features.aim.AimFrame
import net.kuecker.lodestar.features.aim.AimGuidanceSummary
import net.kuecker.lodestar.features.aim.AimFittingText
import net.kuecker.lodestar.features.aim.AimMoveRow
import net.kuecker.lodestar.features.aim.AimViewModel
import net.kuecker.lodestar.features.aim.CardNote
import net.kuecker.lodestar.features.aim.aimPanel
import net.kuecker.lodestar.features.aim.rememberAimFrame
import net.kuecker.lodestar.shared.AppFormat
import net.kuecker.lodestar.shared.LocalAppModel
import net.kuecker.lodestar.shared.monospacedDigits
import net.kuecker.lodestar.theme.LocalPalette
import java.time.ZoneId

/**
 * The panel above the controls while Aim follows a satellite or waits for the peak of its pass: the name, where it is
 * now, what to read off (range, angular speed and light for a followed satellite; the countdown for the peak), a button
 * to stop, and how to reach it. It updates about ten times a second.
 */
@Composable
internal fun SatelliteAimReadout(model: AimViewModel, isActive: Boolean, modifier: Modifier = Modifier) {
    val source = model.satellite.source ?: return
    val frame = rememberAimFrame(model, 100, isActive)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SatelliteAimCard(source, frame, onStop = model.satellite::stop)
        frame?.guidance?.let { AimMoveRow(AimGuidanceSummary(it)) }
    }
}

@Composable
private fun SatelliteAimCard(source: SatelliteAimSource, frame: AimFrame?, onStop: () -> Unit) {
    val palette = LocalPalette.current
    val app = LocalAppModel.current
    val zone = app.observerContext?.timeZone ?: ZoneId.systemDefault()
    val fix = frame?.satellite
    val horizontal = fix?.horizontal
    val isPeak = source is SatellitePeakSource
    val subtitle = when (source) {
        is SatelliteFollowSource -> "Satellite · following"
        is SatellitePeakSource -> "Waiting for the peak · ${SatelliteText.timeWithSeconds(source.peak, zone)}"
    }
    Column(
        Modifier.fillMaxWidth().aimPanel(palette).semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Outlined.SatelliteAlt, contentDescription = null, tint = palette.accent, modifier = Modifier.size(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                AimFittingText(
                    source.title,
                    MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    palette.primaryText,
                    minimumScale = 0.8f,
                )
                Text(
                    subtitle,
                    color = palette.secondaryText,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (horizontal != null) {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    val style = MaterialTheme.typography.labelMedium.monospacedDigits()
                    Text("Alt ${AppFormat.altitude(horizontal.altitude)}", color = palette.secondaryText, style = style)
                    Text("Az ${AppFormat.azimuth(horizontal.azimuth)}", color = palette.secondaryText, style = style)
                }
            }
            StopButton(if (isPeak) "Stop waiting for the peak" else "Stop following", onStop)
        }
        if (fix == null) {
            Text("Locating the satellite…", color = palette.secondaryText, style = MaterialTheme.typography.labelMedium)
            return@Column
        }
        fix.secondsToPeak?.let { seconds ->
            Text(
                SatelliteText.peakCountdown(seconds),
                color = palette.guidance,
                style = MaterialTheme.typography.titleLarge.monospacedDigits(),
            )
        }
        fix.look?.let { look ->
            Text(
                SatelliteText.followInfo(look.rangeKilometers, look.angularSpeed, look.isSunlit),
                color = palette.secondaryText,
                style = MaterialTheme.typography.labelMedium.monospacedDigits(),
            )
        }
        if (horizontal != null && horizontal.altitude < 0) {
            CardNote(SatelliteText.RIDE_ALONG_BELOW, Icons.Outlined.VerticalAlignBottom, palette.warning)
        }
        val pass = (source as? SatelliteFollowSource)?.pass
        if (pass != null) {
            SatelliteText.passNote(app.clock.now, pass.pass.rise.date, pass.pass.set.date) { AppFormat.time(it, zone) }
                ?.let { CardNote(it, Icons.Outlined.VerticalAlignBottom, palette.warning) }
        }
    }
}

/** A 44 dp close button for the card's corner. */
@Composable
private fun StopButton(description: String, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Box(
        Modifier
            .size(44.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Outlined.Close, contentDescription = null, tint = palette.accent, modifier = Modifier.size(22.dp))
    }
}

/** What a screen reader says for the Aim screen while a satellite is the target. */
internal object SatelliteAimText {
    fun spoken(source: SatelliteAimSource, frame: AimFrame): String {
        val fix = frame.satellite ?: return "Locating ${source.title}."
        val guidance = frame.guidance?.let { AimGuidanceSummary(it) }
        val way = guidance?.let { " ${it.spokenText}." }.orEmpty()
        val below = if (fix.horizontal.altitude < 0) " Below the horizon." else ""
        return when (source) {
            is SatelliteFollowSource -> "Following ${source.title}.$way$below"
            is SatellitePeakSource -> {
                val countdown = fix.secondsToPeak?.let { " ${SatelliteText.peakCountdown(it)}." }.orEmpty()
                "Waiting for the peak of the ${source.title} pass.$countdown$way"
            }
        }
    }
}
