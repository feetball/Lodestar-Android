package net.kuecker.lodestar.features.events

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.kuecker.lodestar.kit.events.AstronomicalEvent
import net.kuecker.lodestar.model.ObserverContext
import net.kuecker.lodestar.shared.ChipTone
import net.kuecker.lodestar.shared.StatusChip
import net.kuecker.lodestar.shared.monospacedDigits
import net.kuecker.lodestar.theme.LocalPalette
import java.time.Instant

/**
 * One event in the Upcoming card and the calendar: its kind's icon, title, local date and time, a one-line summary,
 * and badges for whether it can be seen from here and, for a meteor shower, what the Moon does to it. Screen readers
 * get one summary of everything the row shows.
 */
@Composable
internal fun EventRow(
    event: AstronomicalEvent,
    context: ObserverContext,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 14.dp,
) {
    val palette = LocalPalette.current
    val zone = context.timeZone
    val date = remember(event) { EventsFormat.displayDate(event) }
    val summary = remember(event, context) { EventsFormat.summary(event, context) }
    val spoken = remember(event, context) { spokenSummary(event, context, date, summary) }
    Row(
        modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Open details", role = Role.Button, onClick = onClick)
            .clearAndSetSemantics { contentDescription = spoken }
            .heightIn(min = 56.dp)
            .padding(horizontal = horizontalPadding, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier.size(34.dp).clip(CircleShape).background(palette.surfaceRaised),
            contentAlignment = Alignment.Center,
        ) {
            Icon(EventsFormat.icon(event.kind), contentDescription = null, tint = palette.accent, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                event.title,
                color = palette.primaryText,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                EventsFormat.whenText(date, zone),
                color = palette.secondaryText,
                style = MaterialTheme.typography.bodySmall.monospacedDigits(),
            )
            Text(summary, color = palette.secondaryText, style = MaterialTheme.typography.bodySmall)
            EventBadges(event, Modifier.padding(top = 3.dp))
        }
        Icon(
            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = palette.tertiaryText,
            modifier = Modifier.padding(top = 8.dp).size(18.dp),
        )
    }
}

/** "Visible from here" or "Not visible from here" (nothing for events that are not seen in the sky) and a meteor shower's Moon. */
@Composable
internal fun EventBadges(event: AstronomicalEvent, modifier: Modifier = Modifier) {
    val isVisible = EventsFormat.isVisibleFromHere(event)
    val moon = EventsFormat.moonInterference(event)
    if (isVisible == null && moon == null) return
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (isVisible != null) {
            StatusChip(
                icon = if (isVisible) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                text = if (isVisible) "Visible from here" else "Not visible from here",
                tone = if (isVisible) ChipTone.POSITIVE else ChipTone.NEUTRAL,
            )
        }
        if (moon != null) {
            StatusChip(
                icon = Icons.Outlined.DarkMode,
                text = EventsFormat.moonText(event).orEmpty(),
                tone = when (moon) {
                    MoonInterference.LITTLE -> ChipTone.POSITIVE
                    MoonInterference.MODERATE -> ChipTone.NEUTRAL
                    MoonInterference.STRONG -> ChipTone.WARNING
                },
            )
        }
    }
}

private fun spokenSummary(
    event: AstronomicalEvent,
    context: ObserverContext,
    date: Instant,
    summary: String,
): String {
    val parts = arrayListOf(event.title, EventsFormat.spokenWhen(date, context.timeZone), summary.replace(" · ", ", "))
    when (EventsFormat.isVisibleFromHere(event)) {
        true -> parts.add("Visible from here")
        false -> parts.add("Not visible from here")
        null -> Unit
    }
    EventsFormat.moonText(event)?.let { parts.add(it.replace(" · ", ", ")) }
    return parts.joinToString(", ")
}
