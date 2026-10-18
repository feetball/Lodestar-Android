package net.kuecker.lodestar.features.events

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.EventBusy
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import net.kuecker.lodestar.features.objects.ObjectsTopBar
import net.kuecker.lodestar.features.tonight.TonightFormat
import net.kuecker.lodestar.features.tonight.rememberPeriodicNow
import net.kuecker.lodestar.kit.events.AstronomicalEvent
import net.kuecker.lodestar.model.ObserverContext
import net.kuecker.lodestar.shared.EmptyStateView
import net.kuecker.lodestar.shared.LocalAppModel
import net.kuecker.lodestar.shared.LodestarButton
import net.kuecker.lodestar.shared.LodestarButtonKind
import net.kuecker.lodestar.shared.NeedsLocationView
import net.kuecker.lodestar.shared.monospacedDigits
import net.kuecker.lodestar.shared.rememberRetained
import net.kuecker.lodestar.theme.LocalPalette
import net.kuecker.lodestar.theme.screenBackground
import java.text.NumberFormat
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/**
 * The astronomical events of the next twelve months for the observer, grouped by month. Chips narrow the list to
 * kinds of events, and a switch hides what cannot be seen from here. A row opens [onOpenEvent].
 */
@Composable
internal fun EventsCalendarScreen(onBack: () -> Unit, onOpenEvent: (AstronomicalEvent) -> Unit) {
    val palette = LocalPalette.current
    val context = LocalAppModel.current.observerContext
    Column(Modifier.fillMaxSize().screenBackground(palette)) {
        ObjectsTopBar("Events", onBack = onBack)
        if (context == null) {
            NeedsLocationView(Modifier.weight(1f))
        } else {
            EventsCalendarContent(context, onOpenEvent, Modifier.weight(1f))
        }
    }
}

@Composable
private fun EventsCalendarContent(context: ObserverContext, onOpenEvent: (AstronomicalEvent) -> Unit, modifier: Modifier) {
    val app = LocalAppModel.current
    val model = rememberEventsModel()
    val filter = rememberEventsFilter()
    val scope = rememberCoroutineScope()
    val listState = rememberRetained("events.calendar.list") { LazyListState(0, 0) }
    val now = rememberPeriodicNow(60_000L)
    val today = Snapshot.withoutReadObservation { app.clock.now }
    val key = remember(context, now) { EventsCalculator.key(context, today) }
    val zone = context.timeZone

    LaunchedEffect(key) { model.refresh(context, app.clock.now) }

    val result = model.result?.takeIf { it.key == key }
    val shown = remember(result, filter.groups, filter.isVisibleOnly) {
        result?.events?.let(filter::apply)?.sortedBy { EventsFormat.displayDate(it) }
    }
    val months = remember(shown, zone) { shown?.let { groupByMonth(it, zone) } }

    Column(modifier) {
        EventsFilterChips(filter)
        EventsVisibleOnlyRow(filter)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                months != null && months.isNotEmpty() -> EventsMonthList(months, context, now, listState, onOpenEvent)
                months != null -> EmptyStateView(
                    title = "No Events",
                    icon = Icons.Outlined.EventBusy,
                    message = "No events match the filters.",
                ) {
                    LodestarButton("Show All Events", onClick = filter::reset, kind = LodestarButtonKind.SECONDARY)
                }
                model.failure != null -> EmptyStateView(
                    title = "Events Unavailable",
                    icon = Icons.Outlined.EventBusy,
                    message = model.failure.orEmpty(),
                ) {
                    LodestarButton(
                        "Try Again",
                        onClick = { scope.launch { model.refresh(context, app.clock.now) } },
                        icon = Icons.Outlined.Refresh,
                    )
                }
                else -> EventsLoadingView()
            }
        }
    }
}

// Filters

/** One chip per group of events; chosen chips add their events to the list, none chosen shows everything. */
@Composable
private fun EventsFilterChips(filter: EventsFilter) {
    val groups = filter.groups
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (group in EventsGroup.entries) {
            EventsFilterChip(group.title, group.icon, isSelected = group in groups, onToggle = { filter.toggle(group) })
        }
    }
}

@Composable
private fun EventsFilterChip(title: String, icon: ImageVector, isSelected: Boolean, onToggle: () -> Unit) {
    val palette = LocalPalette.current
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .toggleable(value = isSelected, role = Role.Checkbox, onValueChange = { onToggle() })
            .semantics { stateDescription = if (isSelected) "Selected" else "Not selected" },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .clip(CircleShape)
                .background(if (isSelected) palette.accent else palette.surface, CircleShape)
                .border(0.75.dp, if (isSelected) palette.accent else palette.stroke, CircleShape)
                .padding(horizontal = 12.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val color = if (isSelected) palette.onAccent else palette.secondaryText
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
            Text(
                title,
                color = color,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun EventsVisibleOnlyRow(filter: EventsFilter) {
    val palette = LocalPalette.current
    val isOn = filter.isVisibleOnly
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = isOn, role = Role.Switch, onValueChange = { filter.isVisibleOnly = it })
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Visibility, contentDescription = null, tint = palette.secondaryText, modifier = Modifier.size(18.dp))
        Text(
            "Visible from here only",
            color = palette.primaryText,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        // The row is the switch for screen readers; the Switch itself only draws.
        Switch(
            checked = isOn,
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
}

// List

private class EventsMonth(val month: YearMonth, val title: String, val events: List<AstronomicalEvent>)

private fun groupByMonth(events: List<AstronomicalEvent>, zone: ZoneId): List<EventsMonth> {
    val groups = LinkedHashMap<YearMonth, MutableList<AstronomicalEvent>>()
    for (event in events) {
        groups.getOrPut(YearMonth.from(EventsFormat.displayDate(event).atZone(zone))) { ArrayList() }.add(event)
    }
    return groups.map { (month, members) ->
        EventsMonth(month, EventsFormat.month(month.atDay(1).atStartOfDay(zone).toInstant(), zone), members)
    }
}

@Composable
private fun EventsMonthList(
    months: List<EventsMonth>,
    context: ObserverContext,
    now: Instant,
    listState: LazyListState,
    onOpenEvent: (AstronomicalEvent) -> Unit,
) {
    val palette = LocalPalette.current
    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 16.dp)) {
        item(key = "intro") {
            Text(
                "Next 12 months · times in ${TonightFormat.zoneName(context.timeZone, now)} · ${context.name}",
                color = palette.tertiaryText,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp),
            )
        }
        for (month in months) {
            stickyHeader(key = "month/${month.month}") { EventsMonthHeader(month.title, month.events.size) }
            val lastIndex = month.events.lastIndex
            itemsIndexed(month.events, key = { _, event -> "${event.kind}/${event.date.toEpochMilli()}/${event.title}" }) { index, event ->
                Column(
                    Modifier
                        .padding(horizontal = 16.dp)
                        .clip(rowShape(index == 0, index == lastIndex))
                        .background(palette.surface),
                ) {
                    val isPast = EventsFormat.displayDate(event) < now
                    EventRow(
                        event,
                        context,
                        onClick = { onOpenEvent(event) },
                        modifier = Modifier.alpha(if (isPast) 0.6f else 1f),
                    )
                    if (index != lastIndex) {
                        HorizontalDivider(Modifier.padding(start = 60.dp), thickness = 0.5.dp, color = palette.stroke)
                    }
                }
            }
        }
    }
}

@Composable
private fun EventsMonthHeader(title: String, count: Int) {
    val palette = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(palette.background)
            .padding(start = 32.dp, end = 32.dp, top = 12.dp, bottom = 6.dp)
            .semantics(mergeDescendants = true) {
                heading()
                contentDescription = "$title, $count ${if (count == 1) "event" else "events"}"
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Outlined.CalendarMonth, contentDescription = null, tint = palette.secondaryText, modifier = Modifier.size(15.dp))
        Text(
            title.uppercase(),
            color = palette.secondaryText,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp),
        )
        Text(
            NumberFormat.getIntegerInstance().format(count),
            color = palette.tertiaryText,
            style = MaterialTheme.typography.bodySmall.monospacedDigits(),
        )
    }
}

@Composable
private fun EventsLoadingView() {
    val palette = LocalPalette.current
    Column(
        Modifier.fillMaxSize().semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        CircularProgressIndicator(color = palette.accent, trackColor = palette.stroke)
        Text("Working out the events…", color = palette.secondaryText, style = MaterialTheme.typography.bodyMedium)
    }
}

private val RoundedTop = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
private val RoundedBottom = RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)
private val RoundedAll = RoundedCornerShape(12.dp)
private val Square = RoundedCornerShape(0.dp)

private fun rowShape(isFirst: Boolean, isLast: Boolean): Shape = when {
    isFirst && isLast -> RoundedAll
    isFirst -> RoundedTop
    isLast -> RoundedBottom
    else -> Square
}
