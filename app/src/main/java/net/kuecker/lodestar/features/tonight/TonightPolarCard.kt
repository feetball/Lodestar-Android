package net.kuecker.lodestar.features.tonight

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GpsFixed
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.kuecker.lodestar.kit.formatting.AngleFormatter
import net.kuecker.lodestar.kit.foundation.AstroMath
import net.kuecker.lodestar.kit.foundation.Observer
import net.kuecker.lodestar.kit.foundation.SkyFrame
import net.kuecker.lodestar.kit.planning.PolarAlignment
import net.kuecker.lodestar.kit.planning.PolarScopeReading
import net.kuecker.lodestar.shared.AppFormat
import net.kuecker.lodestar.shared.CardTitle
import net.kuecker.lodestar.shared.LodestarButton
import net.kuecker.lodestar.shared.LodestarButtonKind
import net.kuecker.lodestar.shared.monospacedDigits
import net.kuecker.lodestar.shared.rememberRetained
import net.kuecker.lodestar.theme.LocalPalette
import net.kuecker.lodestar.theme.card
import java.time.Instant
import java.time.ZoneId
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Where the pole star stands against the celestial pole right now, as a clock position, with a way into the full Polar
 * Alignment tool ([onOpenPolarAlignment]).
 */
@Composable
internal fun TonightPolarCard(observer: Observer, zone: ZoneId, onOpenPolarAlignment: () -> Unit) {
    val palette = LocalPalette.current
    val invertedState = rememberRetained("tonight.polarInverted") { mutableStateOf(false) }
    var showsInvertedView by invertedState
    // The star moves a quarter of a degree a minute, so the reading is refreshed twice a minute.
    val date = rememberPeriodicNow(30_000)
    val reading = remember(date, observer, showsInvertedView) {
        PolarAlignment.polarScopeReading(
            SkyFrame(date, observer),
            view = if (showsInvertedView) PolarAlignment.ScopeView.INVERTED else PolarAlignment.ScopeView.NAKED,
        )
    }
    Column(Modifier.fillMaxWidth().card(palette), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CardTitle("Polar Alignment", icon = Icons.Outlined.Navigation)
        val spoken = spokenReading(reading)
        Row(
            Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = spoken },
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TonightPolarReticle(reading, Modifier.size(128.dp))
            Readout(reading, date, zone, Modifier.weight(1f))
        }
        Row(
            Modifier
                .fillMaxWidth()
                .toggleable(value = showsInvertedView, role = Role.Switch, onValueChange = { showsInvertedView = it }),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Polar scope view (inverted)",
                color = palette.primaryText,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = showsInvertedView,
                onCheckedChange = null,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = palette.onAccent,
                    checkedTrackColor = palette.accent,
                    uncheckedThumbColor = palette.secondaryText,
                    uncheckedTrackColor = palette.surfaceRaised,
                    uncheckedBorderColor = palette.stroke,
                ),
            )
        }
        LodestarButton(
            "Open Polar Alignment",
            onClick = onOpenPolarAlignment,
            modifier = Modifier.fillMaxWidth(),
            kind = LodestarButtonKind.SECONDARY,
            icon = Icons.Outlined.GpsFixed,
        )
    }
}

@Composable
private fun Readout(reading: PolarScopeReading, date: Instant, zone: ZoneId, modifier: Modifier) {
    val palette = LocalPalette.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            TonightFormat.clockPosition(reading.angle),
            color = palette.accent,
            style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.SemiBold).monospacedDigits(),
        )
        Text(
            "${reading.referenceStarName} on its circle",
            color = palette.primaryText,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "${AngleFormatter.formatDegrees(reading.separationFromPole, 2)} from the pole",
            color = palette.secondaryText,
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "Right now, ${AppFormat.time(date, zone)}",
            color = palette.tertiaryText,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun spokenReading(reading: PolarScopeReading): String =
    "${reading.referenceStarName} is at ${TonightFormat.clockPosition(reading.angle)} o'clock on the polar scope " +
        "circle, ${AngleFormatter.formatDegrees(reading.separationFromPole, 2)} from the pole"

/** A small polar-scope reticle with the pole star drawn at its clock position; 12 o'clock is straight up. */
@Composable
private fun TonightPolarReticle(reading: PolarScopeReading, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val measurer = rememberTextMeasurer()
    val hours = listOf(12, 3, 6, 9)
    val labels = remember(measurer, palette) {
        val style = TextStyle(color = palette.primaryText, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        hours.map { measurer.measure("$it", style, maxLines = 1, softWrap = false) }
    }
    Canvas(modifier.clearAndSetSemantics {}) {
        val center = Offset(size.width / 2, size.height / 2)
        val labelInset = LABEL_INSET.toPx()
        val dial = min(size.width, size.height) / 2 - labelInset
        if (dial <= 0f) return@Canvas

        fun point(radius: Float, degrees: Double): Offset {
            val radians = AstroMath.radians(degrees)
            return Offset(center.x + radius * sin(radians).toFloat(), center.y - radius * cos(radians).toFloat())
        }

        // The dial, the circle the star travels on and the cross hairs through the pole.
        drawCircle(palette.surfaceRaised, dial, center)
        drawCircle(palette.stroke, dial, center, style = Stroke(1.5.dp.toPx()))
        val starRadius = dial * STAR_CIRCLE_FRACTION
        drawCircle(palette.tertiaryText, starRadius, center, style = Stroke(1.dp.toPx()))
        val crossHairs = Path().apply {
            moveTo(center.x - dial, center.y)
            lineTo(center.x + dial, center.y)
            moveTo(center.x, center.y - dial)
            lineTo(center.x, center.y + dial)
        }
        drawPath(crossHairs, palette.gridLine, style = Stroke(1.dp.toPx()))

        // Ticks on the hours, and the numbers at the quarters.
        val ticks = Path()
        for (hour in 0 until 12) {
            val angle = hour * 30.0
            val start = point(dial - 6.dp.toPx(), angle)
            val end = point(dial, angle)
            ticks.moveTo(start.x, start.y)
            ticks.lineTo(end.x, end.y)
        }
        drawPath(ticks, palette.secondaryText, style = Stroke(1.dp.toPx()))
        for ((index, hour) in hours.withIndex()) {
            val label = labels[index]
            val at = point(dial + labelInset / 2 + 1.dp.toPx(), hour * 30.0)
            drawText(label, topLeft = Offset(at.x - label.size.width / 2f, at.y - label.size.height / 2f))
        }

        // The pole, and the star on its circle joined to it by a line.
        drawCircle(palette.primaryText, 2.5.dp.toPx(), center, style = Stroke(1.5.dp.toPx()))
        val star = point(starRadius, reading.angle)
        drawLine(palette.guidance.copy(alpha = palette.guidance.alpha * 0.6f), center, star, strokeWidth = 1.dp.toPx())
        drawCircle(palette.guidance, 8.dp.toPx(), star, style = Stroke(1.5.dp.toPx()))
        drawCircle(palette.guidance, 3.5.dp.toPx(), star)
    }
}

/** Room outside the dial for the hour numbers. */
private val LABEL_INSET = 14.dp

/** Radius of the circle the pole star travels on, as a fraction of the dial's radius. */
private const val STAR_CIRCLE_FRACTION = 0.6f
