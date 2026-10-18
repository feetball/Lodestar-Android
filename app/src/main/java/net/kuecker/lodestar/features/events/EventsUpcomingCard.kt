package net.kuecker.lodestar.features.events

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.kuecker.lodestar.features.tonight.rememberPeriodicNow
import net.kuecker.lodestar.kit.events.AstronomicalEvent
import net.kuecker.lodestar.kit.events.EventDetails
import net.kuecker.lodestar.model.ObserverContext
import net.kuecker.lodestar.shared.CardTitle
import net.kuecker.lodestar.shared.LocalAppModel
import net.kuecker.lodestar.shared.LodestarButton
import net.kuecker.lodestar.shared.LodestarButtonKind
import net.kuecker.lodestar.theme.LocalPalette
import net.kuecker.lodestar.theme.card
import java.time.Instant
import java.time.temporal.ChronoUnit

/** How many events the card lists. */
private const val UPCOMING_COUNT = 4

/** How far ahead the card looks, in days; when nothing falls in it the next few events are shown anyway. */
private const val UPCOMING_DAYS = 56L

/**
 * The next few astronomical events of the coming weeks, each opening its detail, and a button to the calendar of the
 * next twelve months. The calendar is worked out once per observer and day on a background thread
 * ([EventsModel]); Moon conjunctions stay in the calendar unless the Moon covers the planet or star from here.
 */
@Composable
internal fun EventsUpcomingCard(
    context: ObserverContext,
    onOpenEvent: (AstronomicalEvent) -> Unit,
    onOpenCalendar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val app = LocalAppModel.current
    val palette = LocalPalette.current
    val model = rememberEventsModel()
    val scope = rememberCoroutineScope()
    // The card only needs to notice the day change and events that pass, not every tick of the clock.
    val now = rememberPeriodicNow(5 * 60_000L)
    val today = Snapshot.withoutReadObservation { app.clock.now }
    val key = remember(context, now) { EventsCalculator.key(context, today) }

    LaunchedEffect(key) { model.refresh(context, app.clock.now) }

    val result = model.result?.takeIf { it.key == key }
    val upcoming = remember(result, now) { result?.let { upcomingEvents(it.events, now) } }
    val failure = model.failure

    Column(modifier.fillMaxWidth().card(palette), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CardTitle("Upcoming", icon = Icons.Outlined.CalendarMonth)
        when {
            upcoming != null && upcoming.isNotEmpty() -> {
                for ((index, event) in upcoming.withIndex()) {
                    if (index > 0) HorizontalDivider(thickness = 0.5.dp, color = palette.stroke)
                    EventRow(event, context, onClick = { onOpenEvent(event) }, horizontalPadding = 0.dp)
                }
            }
            upcoming != null -> Note("No events are coming up in the next weeks.")
            failure != null -> {
                Note(failure)
                LodestarButton(
                    "Try Again",
                    onClick = { scope.launch { model.refresh(context, app.clock.now) } },
                    modifier = Modifier.fillMaxWidth(),
                    kind = LodestarButtonKind.SECONDARY,
                    icon = Icons.Outlined.Refresh,
                )
            }
            else -> Note("Working out the events…")
        }
        LodestarButton(
            "Calendar",
            onClick = onOpenCalendar,
            modifier = Modifier.fillMaxWidth(),
            kind = LodestarButtonKind.SECONDARY,
            icon = Icons.Outlined.CalendarMonth,
        )
    }
}

@Composable
private fun Note(text: String) {
    Text(text, color = LocalPalette.current.secondaryText, style = MaterialTheme.typography.bodyMedium)
}

/** The first [UPCOMING_COUNT] events from [now] on, within [UPCOMING_DAYS] days (else the next three, however far). */
internal fun upcomingEvents(events: List<AstronomicalEvent>, now: Instant): List<AstronomicalEvent> {
    val ahead = events.filter { EventsFormat.displayDate(it) >= now && isWorthTheCard(it) }
    val horizon = now.plus(UPCOMING_DAYS, ChronoUnit.DAYS)
    val soon = ahead.filter { EventsFormat.displayDate(it) <= horizon }.take(UPCOMING_COUNT)
    return soon.ifEmpty { ahead.take(3) }
}

/** Moon conjunctions come every few days; the card keeps the ones where the Moon covers the other body from here. */
private fun isWorthTheCard(event: AstronomicalEvent): Boolean {
    val conjunction = (event.details as? EventDetails.Conjunction)?.conjunction ?: return true
    if (!conjunction.involvesMoon) return true
    return conjunction.topocentric?.isOccultation == true
}
