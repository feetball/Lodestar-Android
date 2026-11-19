package net.kuecker.lodestar.features.events

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.kuecker.lodestar.kit.ephemeris.SolarSystemBody
import net.kuecker.lodestar.kit.foundation.Observer
import net.kuecker.lodestar.kit.satellites.SatelliteTransit
import net.kuecker.lodestar.kit.satellites.SatelliteTransitFinder
import net.kuecker.lodestar.services.OnlineFeed
import net.kuecker.lodestar.services.PassSatellite
import net.kuecker.lodestar.services.SatelliteLibrary
import net.kuecker.lodestar.shared.LocalAppModel
import net.kuecker.lodestar.shared.rememberRetained
import java.time.Duration
import java.time.Instant
import kotlin.math.roundToLong

/** One predicted transit of the ISS or of Tiangong across the Sun or the Moon, with the name the screens show. */
internal class EventsTransit(val transit: SatelliteTransit, val satelliteName: String) {
    /** Tells transits apart: the satellite, the body and the moment. */
    val id: String get() = "${transit.catalogNumber}/${transit.body.rawValue}/${transit.date.toEpochMilli()}"
}

/** What the transit section of the calendar can show at a given moment. */
internal enum class EventsTransitAvailability {
    /** The satellite feed is switched off. */
    OFF,

    /** The feed is on but there is no element set for the ISS or Tiangong, or it is too old for exact times. */
    NEEDS_DATA,

    /** Fresh element sets are there. */
    READY,
}

/** Predicting the transits the calendar lists. Pure. */
internal object EventsTransits {
    /** The calendar looks this many days ahead. */
    const val HORIZON_DAYS = 14L

    /** Transits are listed when the centreline is within this ground distance, in metres. */
    const val MAX_GROUND_DISTANCE_METERS = 50_000.0

    /** The most transits the section lists. */
    const val LIST_LIMIT = 20

    /** "Tiangong" for the core module of the Chinese space station. */
    private const val TIANGONG_NAME = "Tiangong"

    private val bodies = listOf(SolarSystemBody.SUN, SolarSystemBody.MOON)

    /** The ISS and Tiangong of [library] whose element sets are within their freshness limit at [now]. */
    fun satellites(library: SatelliteLibrary, now: Instant): List<PassSatellite> =
        library.passSatellites.filter {
            val number = it.satellite.catalogNumber
            (number == SatelliteLibrary.ISS_CATALOG_NUMBER || number == SatelliteLibrary.TIANGONG_CATALOG_NUMBER) && it.isFresh(now)
        }

    /** What the section shows for the feed switch and the library at [now]. */
    fun availability(isFeedOn: Boolean, library: SatelliteLibrary, now: Instant): EventsTransitAvailability = when {
        !isFeedOn -> EventsTransitAvailability.OFF
        satellites(library, now).isEmpty() -> EventsTransitAvailability.NEEDS_DATA
        else -> EventsTransitAvailability.READY
    }

    /**
     * The transits of the Sun and the Moon by [satellites] in the [HORIZON_DAYS] days from [start], from [observer]'s site
     * or within [MAX_GROUND_DISTANCE_METERS] of it, by time. About a quarter of a second on a phone: run it off the main
     * thread.
     */
    fun compute(satellites: List<PassSatellite>, observer: Observer, start: Instant): List<EventsTransit> {
        val end = start.plus(Duration.ofDays(HORIZON_DAYS))
        val found = ArrayList<EventsTransit>()
        for (entry in satellites) {
            val name = if (entry.satellite.catalogNumber == SatelliteLibrary.ISS_CATALOG_NUMBER) "ISS" else TIANGONG_NAME
            for (body in bodies) {
                for (transit in SatelliteTransitFinder.transits(entry.satellite, body, observer, start, end, MAX_GROUND_DISTANCE_METERS)) {
                    found.add(EventsTransit(transit, name))
                }
            }
        }
        found.sortBy { it.transit.date }
        return found
    }

    /** The transits that have not happened yet at [now], at most [LIST_LIMIT]. */
    fun upcoming(items: List<EventsTransit>, now: Instant): List<EventsTransit> =
        items.filter { it.transit.date >= now }.take(LIST_LIMIT)
}

/** What a set of transits was computed for: the element sets and the place (to 0.002°, about 200 m). */
internal data class EventsTransitKey(
    val elementSets: List<Pair<Int, Long>>,
    val latitudeSteps: Long,
    val longitudeSteps: Long,
) {
    companion object {
        private const val POSITION_STEP = 0.002

        fun of(satellites: List<PassSatellite>, observer: Observer): EventsTransitKey = EventsTransitKey(
            satellites.map { it.satellite.catalogNumber to it.satellite.elements.epoch.toEpochMilli() },
            (observer.location.latitude / POSITION_STEP).roundToLong(),
            (observer.location.longitude / POSITION_STEP).roundToLong(),
        )
    }
}

/**
 * The transits for the current satellite data and observing place, found once off the main thread and kept for the
 * session (`rememberRetained("events.transits")`), so opening the calendar again does not search again. They are found
 * again when an element set or the place changes, and every six hours as the window moves on. Main-thread state.
 */
internal class EventsTransitModel {
    /** The transits, by time; `null` until they have been worked out. */
    var items: List<EventsTransit>? by mutableStateOf(null)
        private set

    private val lock = Mutex()
    private var key: EventsTransitKey? = null
    private var computedAt: Instant = Instant.EPOCH

    /** Works the transits out for [satellites] and [observer] unless they are already current at [now]. */
    suspend fun ensure(satellites: List<PassSatellite>, observer: Observer, now: Instant) {
        val wanted = EventsTransitKey.of(satellites, observer)
        lock.withLock {
            if (key == wanted && Duration.between(computedAt, now) < REFRESH_AFTER) return
            val found = withContext(Dispatchers.Default) { EventsTransits.compute(satellites, observer, now) }
            key = wanted
            computedAt = now
            items = found
        }
    }

    /** Forgets the transits (the satellite data went away). */
    fun clear() {
        key = null
        items = null
    }

    companion object {
        /** The window is moved on after this long. */
        val REFRESH_AFTER: Duration = Duration.ofHours(6)
    }
}

/**
 * The shared [EventsTransitModel], kept up to date for as long as this is in the composition: it works the transits out
 * for the app's satellite data and [observer], again when they change and every few minutes check whether the window
 * has to move on.
 */
@Composable
internal fun rememberEventsTransits(observer: Observer?): EventsTransitModel {
    val app = LocalAppModel.current
    val model = rememberRetained("events.transits") { EventsTransitModel() }
    val library = app.online.satellites
    val isOn = app.online.isEnabled(OnlineFeed.SATELLITE_PASSES)
    LaunchedEffect(library, observer, isOn) {
        if (!isOn || observer == null) {
            model.clear()
            return@LaunchedEffect
        }
        while (true) {
            val now = Snapshot.withoutReadObservation { app.clock.now }
            val satellites = EventsTransits.satellites(library, now)
            if (satellites.isEmpty()) model.clear() else model.ensure(satellites, observer, now)
            delay(REFRESH_CHECK_MILLIS)
        }
    }
    return model
}

private const val REFRESH_CHECK_MILLIS = 5 * 60_000L
