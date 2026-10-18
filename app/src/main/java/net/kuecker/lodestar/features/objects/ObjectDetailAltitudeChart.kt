package net.kuecker.lodestar.features.objects

import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import net.kuecker.lodestar.kit.foundation.timeIntervalSince
import net.kuecker.lodestar.kit.formatting.AngleFormatter
import net.kuecker.lodestar.shared.AppFormat
import net.kuecker.lodestar.theme.LocalPalette
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * A line chart of an object's altitude through the night, with the horizon, the twilight bands and the present
 * moment marked. Drawn in palette colours only. Screen readers get the highest point of the curve in place of the
 * drawing.
 */
@Composable
internal fun ObjectDetailAltitudeChart(
    night: ObjectDetailNight,
    now: Instant?,
    zone: ZoneId,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val measurer = rememberTextMeasurer()
    val axisStyle = MaterialTheme.typography.labelSmall.copy(color = palette.secondaryText)
    val nowStyle = MaterialTheme.typography.labelSmall.copy(color = palette.primaryText, fontWeight = FontWeight.SemiBold)
    val degreeLabels = remember(axisStyle) {
        DEGREE_TICKS.map { measurer.measure(AngleFormatter.formatDegrees(it, 0), axisStyle) }
    }
    val hourLabels = remember(night, zone, axisStyle) {
        val formatter = hourFormatter(zone)
        night.hourTicks.map { measurer.measure(formatter.format(it), axisStyle) }
    }
    val nowLabel = remember(nowStyle) { measurer.measure("Now", nowStyle) }
    val summary = remember(night, zone) { summary(night, zone) }

    Canvas(
        modifier
            .fillMaxWidth()
            .height(200.dp)
            .semantics {
                contentDescription = "Altitude through the night"
                stateDescription = summary
            },
    ) {
        val labelGap = 6.dp.toPx()
        val tickLength = 3.dp.toPx()
        val labelHeight = max(hourLabels.maxOfOrNull { it.size.height } ?: 0, degreeLabels.maxOf { it.size.height })
        val plotLeft = 0f
        val plotTop = labelHeight / 2f
        val plotRight = size.width - degreeLabels.maxOf { it.size.width } - labelGap
        val plotBottom = size.height - labelHeight - tickLength - 2.dp.toPx()
        val plotWidth = plotRight - plotLeft
        val plotHeight = plotBottom - plotTop
        val start = night.chartRange.start
        val span = max(night.chartRange.duration, 1.0)
        fun x(date: Instant): Float = plotLeft + (date.timeIntervalSince(start) / span).toFloat() * plotWidth
        fun y(altitude: Double): Float =
            plotBottom - ((altitude - ALTITUDE_MINIMUM) / (ALTITUDE_MAXIMUM - ALTITUDE_MINIMUM)).toFloat() * plotHeight
        val hairline = 1f

        drawRect(palette.background, Offset(plotLeft, plotTop), Size(plotWidth, plotHeight))

        // Axes: grid lines, ticks and labels (a label that would touch the one before it is left out).
        var previousLabelEnd = Float.NEGATIVE_INFINITY
        night.hourTicks.forEachIndexed { index, tick ->
            val tickX = x(tick)
            drawLine(palette.stroke, Offset(tickX, plotTop), Offset(tickX, plotBottom + tickLength), hairline)
            val label = hourLabels[index]
            val labelX = (tickX - label.size.width / 2f).coerceIn(0f, size.width - label.size.width)
            if (labelX >= previousLabelEnd + labelGap) {
                drawText(label, topLeft = Offset(labelX, plotBottom + tickLength + 2.dp.toPx()))
                previousLabelEnd = labelX + label.size.width
            }
        }
        DEGREE_TICKS.forEachIndexed { index, degrees ->
            val tickY = y(degrees)
            drawLine(palette.stroke, Offset(plotLeft, tickY), Offset(plotRight, tickY), hairline)
            val label = degreeLabels[index]
            drawText(label, topLeft = Offset(plotRight + labelGap, tickY - label.size.height / 2f))
        }

        clipRect(plotLeft, plotTop, plotRight, plotBottom) {
            // Twilight: lighter sky shows as a stronger overlay.
            for (band in night.twilightBands) {
                val left = x(band.interval.start)
                val right = x(band.interval.end)
                drawRect(
                    palette.secondaryText.copy(alpha = bandOpacity(band.brightness)),
                    Offset(left, plotTop),
                    Size(right - left, plotHeight),
                )
            }
            // The horizon.
            drawLine(
                palette.horizonLine,
                Offset(plotLeft, y(0.0)),
                Offset(plotRight, y(0.0)),
                strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())),
            )
            val samples = night.samples
            // The recorded skyline in the object's direction: the ground it has to clear.
            if (night.usesSkyline && samples.isNotEmpty() && samples.all { it.skylineAltitude != null }) {
                val ground = Path()
                val line = Path()
                ground.moveTo(x(samples.first().date), y(ALTITUDE_MINIMUM))
                samples.forEachIndexed { index, sample ->
                    val groundX = x(sample.date)
                    val groundY = y(max(sample.skylineAltitude ?: 0.0, ALTITUDE_MINIMUM))
                    ground.lineTo(groundX, groundY)
                    if (index == 0) line.moveTo(groundX, groundY) else line.lineTo(groundX, groundY)
                }
                ground.lineTo(x(samples.last().date), y(ALTITUDE_MINIMUM))
                ground.close()
                drawPath(ground, palette.tertiaryText.copy(alpha = 0.3f))
                drawPath(line, palette.horizonLine, style = Stroke(width = 1.5.dp.toPx(), join = StrokeJoin.Round))
            }
            // The altitude curve, with the part above the horizon filled.
            if (samples.isNotEmpty()) {
                val area = Path()
                val curve = Path()
                samples.forEachIndexed { index, sample ->
                    val sampleX = x(sample.date)
                    val upper = y(max(sample.altitude, 0.0))
                    if (index == 0) {
                        area.moveTo(sampleX, y(0.0))
                        curve.moveTo(sampleX, y(max(sample.altitude, ALTITUDE_MINIMUM)))
                    } else {
                        curve.lineTo(sampleX, y(max(sample.altitude, ALTITUDE_MINIMUM)))
                    }
                    area.lineTo(sampleX, upper)
                }
                area.lineTo(x(samples.last().date), y(0.0))
                area.close()
                drawPath(area, palette.accent.copy(alpha = 0.18f))
                drawPath(
                    curve,
                    palette.accent,
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
        }

        // The present moment.
        if (now != null && now in night.chartRange) {
            val nowX = x(now)
            drawLine(palette.primaryText, Offset(nowX, plotTop), Offset(nowX, plotBottom), strokeWidth = 1.dp.toPx())
            val labelX = min(nowX + 3.dp.toPx(), plotRight - nowLabel.size.width)
            drawText(nowLabel, topLeft = Offset(labelX, plotTop))
        }
    }
}

/** The highest point of the curve, spoken in place of the drawing. */
private fun summary(night: ObjectDetailNight, zone: ZoneId): String {
    val highest = night.samples.filter { it.isUp }.maxByOrNull { it.altitude }
    if (highest == null) {
        val aboveHorizon = night.samples.any { it.altitude > 0 }
        return if (night.usesSkyline && aboveHorizon) "Behind your skyline all night" else "Below the horizon all night"
    }
    return "Highest ${AngleFormatter.formatDegrees(highest.altitude, 0)} at ${AppFormat.time(highest.date, zone)}"
}

/** The hour of a time-axis label in the user's 12 or 24-hour style, such as "21" or "9 PM". */
private fun hourFormatter(zone: ZoneId): DateTimeFormatter {
    val locale = Locale.getDefault()
    val pattern = DateFormat.getBestDateTimePattern(locale, if (AppFormat.use24HourClock) "H" else "h")
    return DateTimeFormatter.ofPattern(pattern, locale).withZone(zone)
}

/** Lighter sky shows as a stronger overlay: astronomical twilight is faint, daylight strongest. */
private fun bandOpacity(brightness: Int): Float = when {
    brightness <= 1 -> 0.07f
    brightness == 2 -> 0.14f
    brightness == 3 -> 0.24f
    else -> 0.36f
}

/** The altitudes the chart shows; the part below the horizon is cut off at the lower bound. */
private const val ALTITUDE_MINIMUM = -10.0
private const val ALTITUDE_MAXIMUM = 90.0

/** The altitudes with a grid line and a label. */
private val DEGREE_TICKS = listOf(0.0, 30.0, 60.0, 90.0)
