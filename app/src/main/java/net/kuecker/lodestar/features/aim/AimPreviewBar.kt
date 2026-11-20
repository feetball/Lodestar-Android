package net.kuecker.lodestar.features.aim

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.kuecker.lodestar.shared.AppFormat
import net.kuecker.lodestar.shared.ChipTone
import net.kuecker.lodestar.shared.StatusChip
import net.kuecker.lodestar.shared.monospacedDigits
import net.kuecker.lodestar.theme.LocalPalette
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The previewed moment in words for the readouts: [time] in the observer's time zone, the [day] (a short weekday) when
 * it is not today, and whether it lies ahead.
 */
internal class AimPreviewMoment(val time: String, val day: String?, val isFuture: Boolean)

/** The previewed moment, or `null` while the sky is live. */
internal fun AimViewModel.previewMoment(): AimPreviewMoment? {
    if (!isPreviewing) return null
    val zone = previewZone()
    val date = date
    val day = if (AimPreview.isSameDay(date, app.clock.now, zone)) null else weekday(date, zone)
    return AimPreviewMoment(AppFormat.time(date, zone), day, previewOffset > 0)
}

/** The time zone the preview's times are shown in: the observer's. */
internal fun AimViewModel.previewZone(): ZoneId = app.observerContext?.timeZone ?: ZoneId.systemDefault()

private var weekdayFormatter: Pair<Locale, DateTimeFormatter>? = null

/** "Fri": the short weekday of [date]. Main thread only (the formatter is kept between calls). */
private fun weekday(date: Instant, zone: ZoneId): String {
    val locale = Locale.getDefault()
    val formatter = weekdayFormatter?.takeIf { it.first == locale }?.second
        ?: DateTimeFormatter.ofPattern("EEE", locale).also { weekdayFormatter = locale to it }
    return formatter.withZone(zone).format(date)
}

/**
 * The chip at the top while the sky is previewed: "Preview 02:15" (or "Preview · Daylight at 14:20" when the Sun is up
 * then) with a "Live" button that ends the preview. Nothing while live.
 */
@Composable
internal fun AimPreviewChip(model: AimViewModel, modifier: Modifier = Modifier) {
    val moment = model.previewMoment() ?: return
    val palette = LocalPalette.current
    val isDaylight = model.isDaylightAtPreview == true
    val tone = palette.warning
    Row(
        modifier
            .background(palette.surface.copy(alpha = AIM_PANEL_ALPHA), CircleShape)
            .border(1.dp, tone.copy(alpha = 0.8f), CircleShape)
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Icons.Outlined.Schedule, contentDescription = null, tint = tone, modifier = Modifier.size(16.dp))
        Text(
            AimPreview.chipText(moment.time, moment.day, isDaylight),
            Modifier
                .weight(1f, fill = false)
                .semantics { contentDescription = AimPreview.spokenChip(moment.time, moment.day, isDaylight) },
            color = tone,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold).monospacedDigits(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box(
            Modifier
                .heightIn(min = 44.dp)
                .widthIn(min = 44.dp)
                .clip(CircleShape)
                .clickable(onClickLabel = "Return to the live sky", role = Role.Button, onClick = model::endPreview)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "Live",
                color = palette.accent,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            )
        }
    }
}

/**
 * The compact time bar above the controls: the previewed date and time, how far from now it is, a "Now" button, the
 * date and time picker, and steps of an hour and a quarter of an hour either way. Shown while the Time button of the
 * controls is on.
 */
@Composable
internal fun AimTimeBar(model: AimViewModel, onSheet: (AimSheet) -> Unit, modifier: Modifier = Modifier) {
    if (!model.isTimeBarOpen) return
    val palette = LocalPalette.current
    val zone = model.previewZone()
    val date = model.date
    Column(modifier.fillMaxWidth().aimPanel(palette, padding = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(
                Modifier.weight(1f).semantics(mergeDescendants = true) {},
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    AppFormat.dateTime(date, zone),
                    color = palette.primaryText,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold).monospacedDigits(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (model.isPreviewing) {
                    Text(
                        "${AppFormat.relative(date, model.app.clock.now)} · ${zoneName(zone, date)}",
                        color = palette.secondaryText,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    StatusChip(Icons.Outlined.Sensors, "Live", tone = ChipTone.POSITIVE)
                }
            }
            TimeBarButton(onClick = model::endPreview, enabled = model.isPreviewing, description = "Back to now") {
                Text("Now")
            }
            TimeBarButton(onClick = { onSheet(AimSheet.PREVIEW_TIME) }, description = "Choose date and time") {
                Icon(Icons.Outlined.CalendarMonth, contentDescription = null, modifier = Modifier.size(22.dp))
            }
        }
        // Time runs left to right in every language: the earlier steps, then the later ones.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (step in AimPreview.Step.entries) {
                TimeBarButton(
                    onClick = { model.stepPreview(step) },
                    description = step.spokenTitle,
                    modifier = Modifier.weight(1f),
                    isFitted = true,
                ) {
                    AimFittingText(
                        step.title,
                        MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        palette.accent,
                        minimumScale = 0.7f,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/**
 * A capsule button of the bar, drawn from palette colours like the secondary [net.kuecker.lodestar.shared.LodestarButton]
 * but tighter, so that four of them share a phone's width. Its [content] is spoken as [description].
 */
@Composable
private fun TimeBarButton(
    onClick: () -> Unit,
    description: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isFitted: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    val palette = LocalPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        modifier
            .alpha(if (!enabled) 0.4f else if (pressed) 0.75f else 1f)
            .heightIn(min = 44.dp)
            .widthIn(min = 44.dp)
            .background(palette.surfaceRaised, CircleShape)
            .border(BorderStroke(1.dp, palette.accent.copy(alpha = 0.6f)), CircleShape)
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = if (isFitted) 4.dp else 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // What a screen reader says replaces the short label inside.
        Row(
            Modifier.clearAndSetSemantics { contentDescription = description },
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompositionLocalProvider(
                LocalContentColor provides palette.accent,
                LocalTextStyle provides MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = palette.accent,
                ),
            ) { content() }
        }
    }
}

/** The abbreviation of the observer's time zone at [date], such as "PDT" or "GMT+2". */
private fun zoneName(zone: ZoneId, date: Instant): String =
    DateTimeFormatter.ofPattern("zzz", Locale.getDefault()).format(ZonedDateTime.ofInstant(date, zone))
