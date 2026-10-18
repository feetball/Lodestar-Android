package net.kuecker.lodestar.features.tonight

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.kuecker.lodestar.app.ScreenshotScreen
import net.kuecker.lodestar.app.ScreenshotScreenEffect
import net.kuecker.lodestar.features.events.EventDetailScreen
import net.kuecker.lodestar.features.events.EventsCalendarScreen
import net.kuecker.lodestar.features.events.rememberEventsModel
import net.kuecker.lodestar.features.events.screenshotEvent
import net.kuecker.lodestar.features.objects.ObjectDetailScreen
import net.kuecker.lodestar.features.tools.PolarAlignmentScreen
import net.kuecker.lodestar.kit.catalog.CelestialObject
import net.kuecker.lodestar.kit.events.AstronomicalEvent
import net.kuecker.lodestar.shared.LocalAppModel
import net.kuecker.lodestar.shared.NavStack
import net.kuecker.lodestar.shared.NavStackHost
import net.kuecker.lodestar.shared.NeedsLocationView
import net.kuecker.lodestar.shared.rememberRetained
import net.kuecker.lodestar.theme.LocalPalette
import net.kuecker.lodestar.theme.screenBackground

/** Where the Tonight tab can go. */
private sealed interface TonightRoute {
    /** The planner itself. */
    data object Root : TonightRoute

    /** The detail screen of a planet or target. */
    data class ObjectDetail(val obj: CelestialObject) : TonightRoute

    /** The full Polar Alignment tool. */
    data object PolarAlignment : TonightRoute

    /** The calendar of astronomical events for the next twelve months. */
    data object EventsCalendar : TonightRoute

    /** The detail screen of one astronomical event. */
    data class EventDetail(val event: AstronomicalEvent) : TonightRoute
}

/**
 * The Tonight tab: plan a night of astrophotography. It shows when it is dark, where the Moon is, when the Milky Way's
 * core can be photographed, which planets and deep-sky objects are worth the trouble, and where the pole star stands
 * for aligning a tracker.
 *
 * The night defaults to the one under way (or the coming one by day); the selector and calendar reach a year either
 * way. Planets and targets open the object detail screen; the Upcoming card opens the events calendar and the detail of
 * an astronomical event.
 */
@Composable
fun TonightScreen() {
    val app = LocalAppModel.current
    val palette = LocalPalette.current
    val stack = rememberRetained("tonight.nav") { NavStack<TonightRoute>(TonightRoute.Root) }
    LaunchedEffect(Unit) { app.catalog.load() }
    val events = rememberEventsModel()
    ScreenshotScreenEffect(ScreenshotScreen.EVENTS, ScreenshotScreen.EVENT_DETAIL) { screen ->
        stack.push(TonightRoute.EventsCalendar)
        if (screen == ScreenshotScreen.EVENT_DETAIL) events.screenshotEvent(app)?.let { stack.push(TonightRoute.EventDetail(it)) }
    }
    NavStackHost(stack, Modifier.fillMaxSize()) { route ->
        Box(Modifier.fillMaxSize().screenBackground(palette)) {
            when (route) {
                TonightRoute.Root -> TonightRoot(
                    // A second tap during the slide must not stack the same screen twice.
                    onOpenObject = { obj -> if (stack.top == TonightRoute.Root) stack.push(TonightRoute.ObjectDetail(obj)) },
                    onOpenPolarAlignment = { if (stack.top == TonightRoute.Root) stack.push(TonightRoute.PolarAlignment) },
                    onOpenEvent = { event -> if (stack.top == TonightRoute.Root) stack.push(TonightRoute.EventDetail(event)) },
                    onOpenCalendar = { if (stack.top == TonightRoute.Root) stack.push(TonightRoute.EventsCalendar) },
                )
                is TonightRoute.ObjectDetail -> ObjectDetailScreen(route.obj, onBack = { stack.pop() })
                TonightRoute.PolarAlignment -> PolarAlignmentScreen(onBack = { stack.pop() })
                TonightRoute.EventsCalendar -> EventsCalendarScreen(
                    onBack = { stack.pop() },
                    onOpenEvent = { event -> if (stack.top == TonightRoute.EventsCalendar) stack.push(TonightRoute.EventDetail(event)) },
                )
                is TonightRoute.EventDetail -> EventDetailScreen(route.event, onBack = { stack.pop() })
            }
        }
    }
}

/** The planner once an observing place is known, otherwise the request for one. */
@Composable
private fun TonightRoot(
    onOpenObject: (CelestialObject) -> Unit,
    onOpenPolarAlignment: () -> Unit,
    onOpenEvent: (AstronomicalEvent) -> Unit,
    onOpenCalendar: () -> Unit,
) {
    val context = LocalAppModel.current.observerContext
    if (context != null) {
        TonightContent(context, onOpenObject, onOpenPolarAlignment, onOpenEvent, onOpenCalendar)
    } else {
        Column(Modifier.fillMaxSize()) {
            TonightTopBar()
            NeedsLocationView(Modifier.weight(1f))
        }
    }
}

/** The tab's top bar: its title and, on the right, [actions]. */
@Composable
internal fun TonightTopBar(actions: @Composable RowScope.() -> Unit = {}) {
    val palette = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Tonight",
            color = palette.primaryText,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        actions()
    }
}

/** A text button in the accent colour, for the top bar and sheet headers. */
@Composable
internal fun TonightTextAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, onClickLabel: String? = null) {
    val palette = LocalPalette.current
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClickLabel = onClickLabel, role = Role.Button, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = palette.accent,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
        )
    }
}
