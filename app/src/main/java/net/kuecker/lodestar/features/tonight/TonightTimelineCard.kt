package net.kuecker.lodestar.features.tonight

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.kuecker.lodestar.kit.foundation.AstroMath
import net.kuecker.lodestar.kit.foundation.DateInterval
import net.kuecker.lodestar.kit.foundation.timeIntervalSince
import net.kuecker.lodestar.shared.AppFormat
import net.kuecker.lodestar.shared.CardTitle
import net.kuecker.lodestar.theme.LocalPalette
import net.kuecker.lodestar.theme.Palette
import net.kuecker.lodestar.theme.card
import java.time.Instant
import java.time.ZoneId
import kotlin.math.max

/**
 * The night at a glance: how dark the sky is, when the Moon is up, when it is dark without the Moon and when the Milky
 * Way's core can be photographed, with a marker for the present moment.
 */
@Composable
internal fun TonightTimelineCard(report: TonightReport) {
    val palette = LocalPalette.current
    val summary = report.summary
    val zone = report.timeZone
    val now = rememberPeriodicNow(30_000)
    val spoken = remember(report) { spokenSummary(report) }
    Column(Modifier.fillMaxWidth().card(palette), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CardTitle("Darkness", icon = Icons.Outlined.BarChart)
        Text(
            "Sunset ${AppFormat.time(summary.twilight.sunset, zone)} → " +
                "sunrise ${AppFormat.time(summary.twilight.sunrise, zone)}",
            color = palette.secondaryText,
            style = MaterialTheme.typography.bodySmall,
        )
        Box(
            Modifier.clearAndSetSemantics {
                contentDescription = "Darkness timeline"
                stateDescription = spoken
            },
        ) {
            TonightTimelineCanvas(report.timeline, zone, now)
        }
        TonightSkyLegend()
        if (!summary.hasAstronomicalDarkness) {
            TonightNote(
                "The Sun stays less than 18° below the horizon, so the sky never gets fully dark on this night.",
                style = TonightNoteStyle.WARNING,
            )
        }
    }
}

/** The drawing in words, for a screen reader. */
private fun spokenSummary(report: TonightReport): String {
    val summary = report.summary
    val zone = report.timeZone
    val sentences = ArrayList<String>()
    if (summary.hasAstronomicalDarkness) {
        sentences.add("Astronomical darkness from " + TonightFormat.spokenWindows(summary.astronomicalDarknessIntervals, zone))
    } else {
        sentences.add("No astronomical darkness")
    }
    if (summary.moon.upIntervals.isEmpty()) {
        sentences.add("The Moon stays down")
    } else {
        sentences.add("Moon up from " + TonightFormat.spokenWindows(summary.moon.upIntervals, zone))
    }
    if (summary.darknessIntervals.isNotEmpty()) {
        sentences.add("Dark without the Moon from " + TonightFormat.spokenWindows(summary.darknessIntervals, zone))
    }
    if (report.timeline.coreVisible.isEmpty()) {
        sentences.add("The Milky Way core is not visible")
    } else {
        sentences.add("Milky Way core visible from " + TonightFormat.spokenWindows(report.timeline.coreVisible, zone))
    }
    return sentences.joinToString(". ")
}

// Legend

/** Swatches for the levels of the sky bar, brightest first, in as many columns of at least 92 dp as fit. */
@Composable
private fun TonightSkyLegend() {
    BoxWithConstraints(Modifier.fillMaxWidth().clearAndSetSemantics {}) {
        val columns = ((maxWidth + LEGEND_SPACING) / (LEGEND_MINIMUM_WIDTH + LEGEND_SPACING)).toInt().coerceAtLeast(1)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (rowLevels in TonightSkyLevel.entries.reversed().chunked(columns)) {
                Row(horizontalArrangement = Arrangement.spacedBy(LEGEND_SPACING)) {
                    for (level in rowLevels) LegendItem(level, Modifier.weight(1f))
                    // Keep the columns of a short last row as wide as the others.
                    repeat(columns - rowLevels.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

private val LEGEND_MINIMUM_WIDTH = 92.dp
private val LEGEND_SPACING = 8.dp

@Composable
private fun LegendItem(level: TonightSkyLevel, modifier: Modifier) {
    val palette = LocalPalette.current
    val shape = RoundedCornerShape(2.dp)
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier
                .size(width = 16.dp, height = 10.dp)
                .background(palette.accent.opacity(level.fillOpacity), shape)
                .border(0.5.dp, palette.stroke, shape),
        )
        Text(
            level.title,
            color = palette.secondaryText,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
    }
}

// Drawing

/** The size of the labels; the bars and gaps scale with it, so everything follows the user's font size. */
private val LABEL_SIZE = 10.sp

/** The bars from top to bottom. */
private enum class TimelineRow(val title: String, val heightFactor: Float) {
    SKY("Sky", 2f),
    MOON("Moon up", 1f),
    DARK("Dark sky", 1f),
    CORE("Milky Way", 1f),
}

/** Vertical layout of the canvas, in pixels, from the label size and the screen density. */
private class TimelineMetrics(val labelSize: Float, density: Float) {
    val rowGap = labelSize * 0.5f

    /** Room above the bars for the "Now" label and its pointer. */
    val markerHeight = labelSize * 1.3f + 8 * density

    /** Room below the bars for the hour labels. */
    val axisHeight = labelSize * 1.3f + 6 * density

    val barsHeight = TimelineRow.entries.sumOf { (labelSize * it.heightFactor).toDouble() }.toFloat() +
        rowGap * (TimelineRow.entries.size - 1)

    val totalHeight = markerHeight + barsHeight + axisHeight

    /** Width of the column with the row titles. */
    val gutter = labelSize * 5.8f
}

/** Maps instants to horizontal positions. */
private class TimeAxis(private val range: DateInterval, val minX: Float, val width: Float) {
    fun x(date: Instant): Float {
        val fraction = date.timeIntervalSince(range.start) / range.duration
        return minX + width * AstroMath.clamp(fraction, 0.0, 1.0).toFloat()
    }
}

/** The drawing itself: four bars on a shared time axis. Drawn in palette colours only. */
@Composable
private fun TonightTimelineCanvas(model: TonightTimelineModel, zone: ZoneId, now: Instant) {
    val palette = LocalPalette.current
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val use24HourClock = AppFormat.use24HourClock
    val titles = remember(measurer, palette) {
        val style = TextStyle(color = palette.secondaryText, fontSize = LABEL_SIZE, fontWeight = FontWeight.Medium)
        TimelineRow.entries.map { measurer.label(it.title, style) }
    }
    val tickLabels = remember(measurer, palette, model, zone, use24HourClock) {
        val style = TextStyle(color = palette.secondaryText, fontSize = LABEL_SIZE, fontFeatureSettings = "tnum")
        model.hourTicks.map { measurer.label(TonightFormat.hourLabel(it, zone), style) }
    }
    val nowLabel = remember(measurer, palette) {
        measurer.label("Now", TextStyle(color = palette.primaryText, fontSize = LABEL_SIZE, fontWeight = FontWeight.SemiBold))
    }
    val metrics = TimelineMetrics(with(density) { LABEL_SIZE.toPx() }, density.density)

    Canvas(Modifier.fillMaxWidth().height(with(density) { metrics.totalHeight.toDp() })) {
        val range = model.range
        if (range.duration <= 0 || size.width <= 0f) return@Canvas
        val axis = TimeAxis(range, metrics.gutter, max(size.width - metrics.gutter, 1f))

        // The tracks of the four bars, with their titles.
        val tracks = ArrayList<Rect>(TimelineRow.entries.size)
        var top = metrics.markerHeight
        for (row in TimelineRow.entries) {
            val trackHeight = metrics.labelSize * row.heightFactor
            tracks.add(Rect(axis.minX, top, axis.minX + axis.width, top + trackHeight))
            top += trackHeight + metrics.rowGap
        }
        val barsBottom = top - metrics.rowGap
        for ((index, track) in tracks.withIndex()) {
            drawRoundRect(palette.surfaceRaised, track.topLeft, track.size, CornerRadius(track.height / 3))
            val title = titles[index]
            drawText(title, topLeft = Offset(0f, track.center.y - title.size.height / 2f))
        }

        drawHourLines(model, tickLabels, axis, palette, metrics, barsBottom)
        for ((index, track) in tracks.withIndex()) drawBar(TimelineRow.entries[index], track, model, axis, palette)
        if (now in range) drawNowMarker(nowLabel, axis, axis.x(now), palette, metrics, barsBottom)
    }
}

/** Hairlines and labels at the whole hours. */
private fun DrawScope.drawHourLines(
    model: TonightTimelineModel,
    labels: List<TextLayoutResult>,
    axis: TimeAxis,
    palette: Palette,
    metrics: TimelineMetrics,
    barsBottom: Float,
) {
    val lines = Path()
    for ((index, tick) in model.hourTicks.withIndex()) {
        val x = axis.x(tick)
        lines.moveTo(x, metrics.markerHeight)
        lines.lineTo(x, barsBottom)
        // A label that would run out of the card is left out; its line stays.
        if (x + metrics.labelSize * 2 > size.width) continue
        val label = labels[index]
        drawText(label, topLeft = Offset(x - label.size.width / 2f, barsBottom + 4.dp.toPx()))
    }
    drawPath(lines, palette.gridLine, style = Stroke(1.dp.toPx()))
}

/** One bar: its intervals filled, clipped to the rounded track. */
private fun DrawScope.drawBar(row: TimelineRow, track: Rect, model: TonightTimelineModel, axis: TimeAxis, palette: Palette) {
    val shape = Path().apply { addRoundRect(RoundRect(track, CornerRadius(track.height / 3))) }
    clipPath(shape) {
        when (row) {
            TimelineRow.SKY -> for (band in model.bands) {
                fill(listOf(band.interval), track, axis, palette.accent.opacity(band.level.fillOpacity))
            }
            TimelineRow.MOON -> fill(model.moonUp, track, axis, palette.secondaryText)
            TimelineRow.DARK -> fill(model.dark, track, axis, palette.positive)
            TimelineRow.CORE -> {
                fill(model.coreVisible, track, axis, palette.deepSkyMarker.opacity(0.4))
                fill(model.coreMoonFree, track, axis, palette.deepSkyMarker)
            }
        }
    }
}

private fun DrawScope.fill(intervals: List<DateInterval>, track: Rect, axis: TimeAxis, color: Color) {
    for (interval in intervals) {
        val start = axis.x(interval.start)
        val end = axis.x(interval.end)
        drawRect(color, Offset(start, track.top), Size(max(end - start, 1.dp.toPx()), track.height))
    }
}

/** A line through the bars at the present moment, with a pointer and the word "Now". */
private fun DrawScope.drawNowMarker(
    label: TextLayoutResult,
    axis: TimeAxis,
    x: Float,
    palette: Palette,
    metrics: TimelineMetrics,
    barsBottom: Float,
) {
    val color = palette.primaryText
    val markerHeight = metrics.markerHeight
    drawLine(color, Offset(x, markerHeight - 3.dp.toPx()), Offset(x, barsBottom + 2.dp.toPx()), strokeWidth = 1.5.dp.toPx())

    val pointer = Path().apply {
        moveTo(x - 4.dp.toPx(), markerHeight - 8.dp.toPx())
        lineTo(x + 4.dp.toPx(), markerHeight - 8.dp.toPx())
        lineTo(x, markerHeight - 2.dp.toPx())
        close()
    }
    drawPath(pointer, color)

    val inset = metrics.labelSize * 1.2f
    val labelX = AstroMath.clamp(x, axis.minX + inset, max(size.width - inset, axis.minX + inset))
    drawText(label, topLeft = Offset(labelX - label.size.width / 2f, markerHeight - 9.dp.toPx() - label.size.height))
}

private fun TextMeasurer.label(text: String, style: TextStyle): TextLayoutResult =
    measure(text, style, maxLines = 1, softWrap = false)

/** The colour at a fraction of its own opacity. */
private fun Color.opacity(factor: Double): Color = copy(alpha = alpha * factor.toFloat())
