package net.kuecker.lodestar.features.skychart

import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import net.kuecker.lodestar.kit.foundation.roundedHalfAwayFromZero
import net.kuecker.lodestar.kit.formatting.AngleFormatter
import net.kuecker.lodestar.kit.formatting.formatDuration
import net.kuecker.lodestar.model.SkyClock
import net.kuecker.lodestar.shared.AppFormat
import net.kuecker.lodestar.shared.ChipTone
import net.kuecker.lodestar.shared.LocalAppModel
import net.kuecker.lodestar.shared.LodestarButton
import net.kuecker.lodestar.shared.LodestarButtonKind
import net.kuecker.lodestar.shared.StatusChip
import net.kuecker.lodestar.shared.monospacedDigits
import net.kuecker.lodestar.theme.LocalPalette
import net.kuecker.lodestar.theme.card
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** The range of the slider, in hours from now. */
private val SLIDER_RANGE = -12.0..12.0

/** Below this many hours (one minute) the slider counts as "now". */
private const val NOW_HOURS = 1.0 / 60

/**
 * The sky chart's clock: the displayed date and time in the observer's time zone, a slider for ±12 hours around now,
 * one-hour steps, a "Now" button and a way to pick any date.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TimeTravelBar(timeZone: ZoneId, onChooseDate: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val clock = LocalAppModel.current.clock
    CappedFontScale {
        Column(modifier.fillMaxWidth().card(palette, 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // At large text sizes the buttons move below the readout.
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Readout(clock, timeZone, Modifier.padding(end = 4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LodestarButton(
                        "Now",
                        onClick = clock::returnToNow,
                        kind = LodestarButtonKind.SECONDARY,
                        enabled = !clock.isLive,
                    )
                    LodestarButton(onClick = onChooseDate, kind = LodestarButtonKind.SECONDARY) {
                        Icon(Icons.Outlined.CalendarMonth, contentDescription = "Choose date and time")
                    }
                }
            }
            // Time runs left to right in every language: the earlier-hour button, the slider and the later-hour button.
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    StepButton("−1h", "One hour earlier") { clock.travel(-3600.0) }
                    SkyChartTimeSlider(
                        hours = clock.offset / 3600,
                        onHoursChange = { hours -> travelTo(clock, hours) },
                        range = SLIDER_RANGE,
                        modifier = Modifier.weight(1f),
                    )
                    StepButton("+1h", "One hour later") { clock.travel(3600.0) }
                }
            }
        }
    }
}

/** Limits the font scale inside [content] to [maximum], so that overlay cards do not outgrow the chart. */
@Composable
internal fun CappedFontScale(maximum: Float = 1.65f, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    if (density.fontScale <= maximum) {
        content()
    } else {
        CompositionLocalProvider(LocalDensity provides Density(density.density, maximum)) { content() }
    }
}

/** A secondary button whose short label ([text]) is spoken as [description]. */
@Composable
private fun StepButton(text: String, description: String, onClick: () -> Unit) {
    LodestarButton(onClick = onClick, kind = LodestarButtonKind.SECONDARY) {
        Text(text, Modifier.clearAndSetSemantics { contentDescription = description })
    }
}

/** The displayed date and time, and below it either "Live" or how far from now it is and in which time zone. */
@Composable
private fun Readout(clock: SkyClock, timeZone: ZoneId, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    Column(modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            AppFormat.dateTime(clock.displayDate, timeZone),
            color = palette.primaryText,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold).monospacedDigits(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (clock.isLive) {
            StatusChip(Icons.Outlined.Sensors, "Live", tone = ChipTone.POSITIVE)
        } else {
            Text(
                "${AppFormat.relative(clock.displayDate, clock.now)} · ${zoneName(timeZone, clock.displayDate)}",
                color = palette.secondaryText,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The abbreviation of the observer's time zone at [date], such as "PDT" or "GMT+2". */
private fun zoneName(zone: ZoneId, date: Instant): String =
    DateTimeFormatter.ofPattern("zzz", Locale.getDefault()).format(ZonedDateTime.ofInstant(date, zone))

/** Moves the displayed time to [hours] from now, in whole minutes; the middle of the slider returns to live. */
private fun travelTo(clock: SkyClock, hours: Double) {
    if (abs(hours) < NOW_HOURS) {
        clock.returnToNow()
    } else {
        val seconds = (hours * 60).roundedHalfAwayFromZero() * 60
        clock.travel(clock.now.plusMillis((seconds * 1000).toLong()))
    }
}

/**
 * A slider for the time offset, drawn from palette colours: the track fills outwards from the middle ("now") to the
 * thumb, so the colour reads as "how far from now". The thumb follows the finger from the first touch.
 */
@Composable
internal fun SkyChartTimeSlider(
    hours: Double,
    onHoursChange: (Double) -> Unit,
    range: ClosedFloatingPointRange<Double>,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val change by rememberUpdatedState(onHoursChange)
    Canvas(
        modifier
            .height(44.dp)
            .pointerInput(range) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    val thumb = THUMB_SIZE.dp.toPx()
                    change(hoursAt(down.position.x, size.width.toFloat(), thumb, range))
                    drag(down.id) { move ->
                        change(hoursAt(move.position.x, size.width.toFloat(), thumb, range))
                        move.consume()
                    }
                }
            }
            .semantics {
                contentDescription = "Time offset from now"
                stateDescription = accessibilityValue(hours)
                progressBarRangeInfo = ProgressBarRangeInfo(
                    hours.coerceIn(range).toFloat(), range.start.toFloat()..range.endInclusive.toFloat(),
                    steps = ((range.endInclusive - range.start) / ACCESSIBILITY_STEP).toInt() - 1,
                )
                setProgress { target ->
                    change(target.toDouble().coerceIn(range))
                    true
                }
            },
    ) {
        val thumb = THUMB_SIZE.dp.toPx()
        val trackHeight = 4.dp.toPx()
        val centerY = size.height / 2
        val middle = size.width / 2
        val x = thumbX(hours, size.width, thumb, range)
        val corner = CornerRadius(trackHeight / 2)
        drawRoundRect(palette.stroke, Offset(0f, centerY - trackHeight / 2), Size(size.width, trackHeight), corner)
        drawRoundRect(
            palette.accent.copy(alpha = palette.accent.alpha * 0.7f),
            Offset(min(x, middle), centerY - trackHeight / 2), Size(abs(x - middle), trackHeight), corner,
        )
        drawNowTick(middle, centerY, palette.tertiaryText)
        drawCircle(palette.accent, thumb / 2, Offset(x, centerY))
    }
}

private fun DrawScope.drawNowTick(middle: Float, centerY: Float, color: Color) {
    val width = 1.5.dp.toPx()
    val height = 14.dp.toPx()
    drawRect(color, Offset(middle - width / 2, centerY - height / 2), Size(width, height))
}

private const val THUMB_SIZE = 26f

/** How far an accessibility service's increment and decrement actions move the time, in hours. */
private const val ACCESSIBILITY_STEP = 0.25

private fun thumbX(hours: Double, width: Float, thumb: Float, range: ClosedFloatingPointRange<Double>): Float {
    val span = range.endInclusive - range.start
    val fraction = ((hours.coerceIn(range) - range.start) / span).toFloat()
    return thumb / 2 + fraction * (width - thumb)
}

private fun hoursAt(x: Float, width: Float, thumb: Float, range: ClosedFloatingPointRange<Double>): Double {
    val travel = max(width - thumb, 1f)
    val fraction = ((x - thumb / 2) / travel).coerceIn(0f, 1f).toDouble()
    return range.start + fraction * (range.endInclusive - range.start)
}

private fun accessibilityValue(hours: Double): String {
    if (abs(hours) < NOW_HOURS) return "Now"
    val minutes = (abs(hours) * 60).roundedHalfAwayFromZero().toInt()
    val text = AngleFormatter.formatDuration(minutes * 60.0)
    return if (hours < 0) "$text earlier" else "$text later"
}
