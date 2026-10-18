package net.kuecker.lodestar.features.objects

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import net.kuecker.lodestar.kit.catalog.CelestialObject
import net.kuecker.lodestar.kit.formatting.AngleFormatter
import net.kuecker.lodestar.kit.formatting.formatExposure
import net.kuecker.lodestar.kit.phototools.LensSetup
import net.kuecker.lodestar.shared.AppFormat
import net.kuecker.lodestar.shared.CardTitle
import net.kuecker.lodestar.shared.InfoRow
import net.kuecker.lodestar.theme.LocalPalette
import net.kuecker.lodestar.theme.Palette
import net.kuecker.lodestar.theme.card
import java.text.NumberFormat
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.min

/**
 * How the object fits the user's lens, how long to expose, and what the Moon will do to the shot. [snapshot] is
 * `null` while it is being computed; the catalog values are used until then.
 */
@Composable
internal fun ObjectDetailPhotographyCard(
    obj: CelestialObject,
    snapshot: ObjectDetailSnapshot?,
    lens: LensSetup,
    zone: ZoneId,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val current = snapshot?.current
    val photography = remember(obj, current, lens) {
        ObjectDetailCalculator.photography(
            obj = obj,
            size = current?.angularSize ?: obj.angularSize,
            declination = current?.equatorial?.declination ?: obj.j2000?.declination,
            lens = lens,
        )
    }
    Column(modifier.fillMaxWidth().card(palette), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CardTitle("Photography", icon = Icons.Outlined.CameraAlt)
        LensRows(photography, lens)
        photography.framing?.let { FramingRow(it, photography.frame.longSide / photography.frame.shortSide) }
        ExposureRows(photography)
        snapshot?.moon?.let { MoonRows(it, zone) }
    }
}

// Lens and framing

@Composable
private fun LensRows(photography: ObjectDetailPhotography, lens: LensSetup) {
    val setup = "${trimmed(lens.focalLengthMillimeters)} mm f/${trimmed(lens.aperture)} · ${lens.sensor.name}"
    val horizontal = AngleFormatter.formatDegrees(photography.frame.horizontal)
    val vertical = AngleFormatter.formatDegrees(photography.frame.vertical)
    InfoRow("Your lens", setup)
    InfoRow("Field of view", "$horizontal × $vertical")
}

/** A number with at most one decimal, such as "24" or "2.8". */
private fun trimmed(value: Double): String =
    NumberFormat.getNumberInstance().apply {
        minimumFractionDigits = 0
        maximumFractionDigits = 1
    }.format(value)

@Composable
private fun FramingRow(framing: ObjectDetailFraming, aspectRatio: Double) {
    val palette = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ObjectDetailFramingDiagram(framing, aspectRatio, Modifier.width(110.dp))
        Text(
            framingDescription(framing),
            color = palette.primaryText,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun framingDescription(framing: ObjectDetailFraming): String {
    if (framing.fitsInFrame) {
        return if (framing.fillFraction < 0.01) {
            "Fills under 1% of the frame"
        } else {
            "Fills ${AppFormat.percent(framing.fillFraction)} of the frame"
        }
    }
    val panels = "${framing.longSidePanels} × ${framing.shortSidePanels}"
    return "Larger than the frame: needs a mosaic of about $panels panels"
}

// Exposure

@Composable
private fun ExposureRows(photography: ObjectDetailPhotography) {
    val palette = LocalPalette.current
    val npf = photography.npf
    val rule500 = photography.rule500
    if (npf == null || rule500 == null) return
    Text(
        "Longest exposure before stars trail",
        color = palette.secondaryText,
        style = MaterialTheme.typography.bodySmall,
    )
    InfoRow("NPF rule", AngleFormatter.formatExposure(npf.seconds))
    InfoRow("500 rule", AngleFormatter.formatExposure(rule500.seconds))
    if (npf.notes.isNotEmpty()) {
        Text(
            npf.notes.joinToString(" "),
            color = palette.tertiaryText,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

// Moon

@Composable
private fun MoonRows(moon: ObjectDetailMoonConditions, zone: ZoneId) {
    val palette = LocalPalette.current
    val advice = moonAdvice(moon.impact, palette)
    InfoRow(
        if (moon.isNow) "Moon now" else "Moon at ${AppFormat.time(moon.date, zone)}",
        moonSummary(moon),
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(advice.icon, contentDescription = null, tint = advice.color, modifier = Modifier.size(16.dp))
        Text(advice.text, color = advice.color, style = MaterialTheme.typography.bodySmall)
    }
}

private fun moonSummary(moon: ObjectDetailMoonConditions): String {
    if (moon.impact == ObjectDetailMoonConditions.Impact.MOON_DOWN) return "Below the horizon"
    return "${AppFormat.separation(moon.separation)} away · ${AppFormat.percent(moon.illuminatedFraction)} lit"
}

private class MoonAdvice(val text: String, val icon: ImageVector, val color: Color)

private fun moonAdvice(impact: ObjectDetailMoonConditions.Impact, palette: Palette): MoonAdvice = when (impact) {
    ObjectDetailMoonConditions.Impact.MOON_DOWN ->
        MoonAdvice("The Moon is down, so the sky stays dark.", Icons.Outlined.Bedtime, palette.positive)
    ObjectDetailMoonConditions.Impact.MINOR ->
        MoonAdvice("The Moon has little effect on this target.", Icons.Outlined.DarkMode, palette.secondaryText)
    ObjectDetailMoonConditions.Impact.MODERATE ->
        MoonAdvice("Moonlight will brighten the sky around this target.", Icons.Filled.DarkMode, palette.warning)
    ObjectDetailMoonConditions.Impact.SEVERE ->
        MoonAdvice("A bright Moon close by will wash out faint detail.", Icons.Outlined.Warning, palette.critical)
}

/**
 * A sketch of the camera frame with the object drawn to scale along its long side. [aspectRatio] is the frame's
 * long side divided by its short side. Decorative: the text next to it says the same.
 */
@Composable
internal fun ObjectDetailFramingDiagram(framing: ObjectDetailFraming, aspectRatio: Double, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val ratio = aspectRatio.takeIf { it.isFinite() && it > 0 }?.toFloat() ?: 1.5f
    Canvas(modifier.aspectRatio(ratio).clearAndSetSemantics {}) {
        val corner = CornerRadius(6.dp.toPx())
        val line = 1.dp.toPx()
        drawRoundRect(palette.background, cornerRadius = corner)
        val width = max(size.width * min(framing.majorFraction, 1.0).toFloat(), 4.dp.toPx())
        val height = max(size.height * min(framing.minorFraction, 1.0).toFloat(), 4.dp.toPx())
        val topLeft = Offset((size.width - width) / 2, (size.height - height) / 2)
        drawOval(palette.accent.copy(alpha = 0.35f), topLeft, Size(width, height))
        drawOval(palette.accent, topLeft, Size(width, height), style = Stroke(line))
        drawRoundRect(
            palette.stroke,
            topLeft = Offset(line / 2, line / 2),
            size = Size(size.width - line, size.height - line),
            cornerRadius = corner,
            style = Stroke(line),
        )
    }
}
