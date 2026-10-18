package net.kuecker.lodestar.features.tonight

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.NightsStay
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.kuecker.lodestar.kit.ephemeris.RiseTransitSet
import net.kuecker.lodestar.kit.planning.MoonSummary
import net.kuecker.lodestar.kit.planning.NightSummary
import net.kuecker.lodestar.shared.AppFormat
import net.kuecker.lodestar.shared.CardTitle
import net.kuecker.lodestar.shared.ChipTone
import net.kuecker.lodestar.shared.InfoRow
import net.kuecker.lodestar.shared.StatusChip
import net.kuecker.lodestar.theme.LocalPalette
import net.kuecker.lodestar.theme.card
import java.text.NumberFormat
import java.time.ZoneId
import kotlin.math.abs

/** The Moon's phase, when it is up, and how much of the dark it leaves alone. */
@Composable
internal fun TonightMoonCard(summary: NightSummary, zone: ZoneId) {
    val palette = LocalPalette.current
    Column(Modifier.fillMaxWidth().card(palette), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CardTitle("Moon", icon = Icons.Outlined.DarkMode)
        MoonHeader(summary, zone)
        MoonlightChip(summary)
        HorizonRows(summary.moon, zone)
        DarkHoursRow(summary)
    }
}

// Phase

@Composable
private fun MoonHeader(summary: NightSummary, zone: ZoneId) {
    val palette = LocalPalette.current
    val moon = summary.moon
    val phase = moon.phase
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MoonPhaseIcon(
            illuminatedFraction = phase.illuminatedFraction,
            isWaxing = phase.isWaxing,
            isSouthernHemisphere = summary.observer.location.latitude < 0,
            modifier = Modifier.size(46.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                phase.name.displayName,
                color = palette.primaryText,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            )
            Text(
                "${AppFormat.percent(moon.illuminatedFraction)} illuminated",
                color = palette.secondaryText,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Age ${oneDecimal(phase.ageDays)} days · at ${AppFormat.time(phase.date, zone)}",
                color = palette.tertiaryText,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** The ages are shown with one decimal in the user's number format, e.g. "12.3". */
private fun oneDecimal(value: Double): String =
    NumberFormat.getNumberInstance().apply {
        minimumFractionDigits = 1
        maximumFractionDigits = 1
    }.format(value)

/**
 * The Moon's phase as the lit part of its disc, drawn from the illuminated fraction: the limb of the lit side and the
 * terminator, an ellipse as wide as the phase says. Phases are drawn as seen from the northern hemisphere (the lit side
 * of a waxing Moon on the right) and mirrored for the southern one, as the system symbols on iOS are.
 */
@Composable
private fun MoonPhaseIcon(
    illuminatedFraction: Double,
    isWaxing: Boolean,
    isSouthernHemisphere: Boolean,
    modifier: Modifier = Modifier,
) {
    val color = LocalPalette.current.primaryText
    val fraction = if (illuminatedFraction.isFinite()) illuminatedFraction.coerceIn(0.0, 1.0) else 0.0
    // The lit side is on the right for a waxing Moon in the north and for a waning one in the south.
    val isMirrored = isWaxing == isSouthernHemisphere
    Canvas(modifier.clearAndSetSemantics {}) {
        val strokeWidth = 1.5.dp.toPx()
        val radius = size.minDimension / 2 - strokeWidth / 2
        val center = Offset(size.width / 2, size.height / 2)
        val limb = Rect(center.x - radius, center.y - radius, center.x + radius, center.y + radius)
        // The terminator's half width: the whole radius at new and full Moon, nothing at the quarters.
        val terminatorHalfWidth = radius * abs(1 - 2 * fraction).toFloat()
        val terminator = Rect(center.x - terminatorHalfWidth, limb.top, center.x + terminatorHalfWidth, limb.bottom)
        val lit = Path().apply {
            arcTo(limb, startAngleDegrees = -90f, sweepAngleDegrees = 180f, forceMoveTo = true)
            // Back from the bottom to the top: bulging towards the lit side for a crescent, away from it for a gibbous Moon.
            val sweep = if (fraction < 0.5) -180f else 180f
            arcTo(terminator, startAngleDegrees = 90f, sweepAngleDegrees = sweep, forceMoveTo = false)
            close()
        }
        withTransform({ if (isMirrored) scale(-1f, 1f, pivot = center) }) {
            drawPath(lit, color)
            drawCircle(color, radius, center, style = Stroke(strokeWidth))
        }
    }
}

/** A one-word verdict on how much of the dark the Moon spoils. */
@Composable
private fun MoonlightChip(summary: NightSummary) {
    if (!summary.hasAstronomicalDarkness) return
    val moonFree = summary.moonFreeDarkHours / summary.astronomicalDarkHours
    when {
        moonFree >= 0.75 -> StatusChip(Icons.Outlined.NightsStay, "Mostly moon-free dark", tone = ChipTone.POSITIVE)
        moonFree <= 0.25 -> StatusChip(Icons.Filled.DarkMode, "Moon up for most of the dark", tone = ChipTone.WARNING)
        else -> StatusChip(Icons.Outlined.DarkMode, "Partly moonlit")
    }
}

// Times

@Composable
private fun HorizonRows(moon: MoonSummary, zone: ZoneId) {
    val events = moon.riseTransitSet
    when (events.circumstance) {
        RiseTransitSet.Circumstance.ALWAYS_UP -> InfoRow("Moon", "Above the horizon all night")
        RiseTransitSet.Circumstance.ALWAYS_DOWN -> InfoRow("Moon", "Below the horizon all night")
        RiseTransitSet.Circumstance.NORMAL -> {
            val rise = events.rise
            val set = events.set
            if (rise != null && set != null && set < rise) {
                InfoRow("Moonset", AppFormat.time(set, zone))
                InfoRow("Moonrise", AppFormat.time(rise, zone))
            } else {
                InfoRow("Moonrise", AppFormat.time(rise, zone))
                InfoRow("Moonset", AppFormat.time(set, zone))
            }
        }
    }
}

@Composable
private fun DarkHoursRow(summary: NightSummary) {
    if (summary.hasAstronomicalDarkness) {
        InfoRow(
            "Moon-free dark hours",
            "${TonightFormat.duration(summary.moonFreeDarkHours)} of ${TonightFormat.duration(summary.astronomicalDarkHours)}",
        )
    } else {
        InfoRow("Moon-free dark hours", "No astronomical darkness")
    }
}
