package net.kuecker.lodestar.features.events

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.kuecker.lodestar.kit.events.AstronomicalEvent
import net.kuecker.lodestar.kit.events.EventCalculator
import net.kuecker.lodestar.kit.events.EventDetails
import net.kuecker.lodestar.kit.events.EventKind
import net.kuecker.lodestar.kit.events.EventOptions
import net.kuecker.lodestar.model.ObserverContext
import net.kuecker.lodestar.shared.rememberRetained
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.roundToLong

/**
 * What a calendar was computed for: the place (to 0.05°, about 5 km, so a few steps of the GPS do not start it over),
 * the time zone and the local day. A calendar is good for the whole day; another place, zone or day needs a new one.
 */
internal data class EventsKey(
    val latitudeSteps: Long,
    val longitudeSteps: Long,
    val zone: ZoneId,
    val day: LocalDate,
)

/** The events of the next twelve months for one [key]. */
internal class EventsResult(val key: EventsKey, val events: List<AstronomicalEvent>)

/** The astronomy behind the events calendar. Pure, so it can run on any thread. */
internal object EventsCalculator {
    /** How far ahead the calendar looks, in days. */
    const val SPAN_DAYS = 366L

    /** The largest separation, in degrees, at which the Moon and a planet or star, or two planets, are in conjunction. */
    const val CONJUNCTION_THRESHOLD = 3.0

    fun key(context: ObserverContext, now: Instant): EventsKey {
        val location = context.location
        return EventsKey(
            latitudeSteps = (location.latitude / POSITION_STEP).roundToLong(),
            longitudeSteps = (location.longitude / POSITION_STEP).roundToLong(),
            zone = context.timeZone,
            day = now.atZone(context.timeZone).toLocalDate(),
        )
    }

    /** The next [SPAN_DAYS] days from the start of today, with visibility for the observer in [context]. */
    fun compute(context: ObserverContext, key: EventsKey): EventsResult {
        val from = key.day.atStartOfDay(key.zone).toInstant()
        val options = EventOptions(
            timeZone = key.zone,
            planetConjunctionThreshold = CONJUNCTION_THRESHOLD,
            moonConjunctionThreshold = CONJUNCTION_THRESHOLD,
        )
        val events = EventCalculator.events(from, from.plus(SPAN_DAYS, ChronoUnit.DAYS), context.observer, options)
        return EventsResult(key, events)
    }

    private const val POSITION_STEP = 0.05
}

/**
 * The calendar for the current observer, shared by the Tonight card and the calendar screen so it is worked out once
 * per observer and day (kept for the session with `rememberRetained("events.model")`). Main-thread state.
 */
internal class EventsModel {
    /** The latest calendar, or `null` before the first one has been worked out. */
    var result by mutableStateOf<EventsResult?>(null)
        private set

    /** Why the last attempt failed, or `null`. */
    var failure by mutableStateOf<String?>(null)
        private set

    private val lock = Mutex()

    /** The calendar if it answers [context] today, else `null`. */
    fun current(context: ObserverContext, now: Instant): EventsResult? =
        result?.takeIf { it.key == EventsCalculator.key(context, now) }

    /**
     * Makes sure the calendar for [context] and today is there, working it out on a background thread when it is
     * not. Callers that ask at the same time wait for the one computation. A computation that has started is finished
     * even when its caller leaves (the Tonight card gives way to the calendar), so the next screen finds it done.
     */
    suspend fun refresh(context: ObserverContext, now: Instant) {
        val key = EventsCalculator.key(context, now)
        lock.withLock {
            if (result?.key == key) return
            failure = null
            withContext(NonCancellable + Dispatchers.Default) {
                try {
                    // Compose state may be written from any thread.
                    result = EventsCalculator.compute(context, key)
                } catch (error: Exception) {
                    failure = error.message ?: "The events could not be worked out."
                }
            }
        }
    }
}

@Composable
internal fun rememberEventsModel(): EventsModel = rememberRetained("events.model") { EventsModel() }

/** The groups of events the calendar can be narrowed to; an event can belong to more than one. */
internal enum class EventsGroup(val title: String) {
    METEOR_SHOWERS("Meteor showers"),
    CONJUNCTIONS("Conjunctions"),
    ECLIPSES("Eclipses"),
    PLANETS("Planets"),
    MOON("Moon"),
    SEASONS("Seasons"),
    ;

    val icon: ImageVector get() = EventsFormat.icon(representative)

    private val representative: EventKind
        get() = when (this) {
            METEOR_SHOWERS -> EventKind.METEOR_SHOWER
            CONJUNCTIONS -> EventKind.CONJUNCTION
            ECLIPSES -> EventKind.LUNAR_ECLIPSE
            PLANETS -> EventKind.OPPOSITION
            MOON -> EventKind.FULL_MOON_SPECIAL
            SEASONS -> EventKind.SEASON
        }

    fun contains(event: AstronomicalEvent): Boolean = when (this) {
        METEOR_SHOWERS -> event.kind == EventKind.METEOR_SHOWER
        CONJUNCTIONS -> event.kind == EventKind.CONJUNCTION
        ECLIPSES -> event.kind == EventKind.LUNAR_ECLIPSE || event.kind == EventKind.SOLAR_ECLIPSE
        PLANETS -> event.kind == EventKind.OPPOSITION || event.kind == EventKind.GREATEST_ELONGATION
        MOON -> event.kind == EventKind.FULL_MOON_SPECIAL || (event.details as? EventDetails.Conjunction)?.conjunction?.involvesMoon == true
        SEASONS -> event.kind == EventKind.SEASON || event.kind == EventKind.EARTH_APSIS
    }
}

/** The calendar's filters, kept for the session (`rememberRetained("events.filter")`). */
internal class EventsFilter {
    /** The chosen groups; none chosen means every event. */
    var groups by mutableStateOf<Set<EventsGroup>>(emptySet())

    /** Hide the events the observer cannot see from here. */
    var isVisibleOnly by mutableStateOf(false)

    val isActive: Boolean get() = groups.isNotEmpty() || isVisibleOnly

    fun toggle(group: EventsGroup) {
        groups = if (group in groups) groups - group else groups + group
    }

    fun reset() {
        groups = emptySet()
        isVisibleOnly = false
    }

    /** [events] that pass the filters, in their order. Events with no visibility (the seasons) are never hidden by "visible only". */
    fun apply(events: List<AstronomicalEvent>): List<AstronomicalEvent> {
        val chosen = groups
        val visibleOnly = isVisibleOnly
        if (chosen.isEmpty() && !visibleOnly) return events
        return events.filter { event ->
            (chosen.isEmpty() || chosen.any { it.contains(event) }) &&
                (!visibleOnly || event.visibility?.isObservable != false)
        }
    }
}

@Composable
internal fun rememberEventsFilter(): EventsFilter = rememberRetained("events.filter") { EventsFilter() }
