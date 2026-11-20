package net.kuecker.lodestar.features.objects

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import net.kuecker.lodestar.features.smallbodies.SmallBodyInfo
import net.kuecker.lodestar.kit.catalog.CelestialObject
import net.kuecker.lodestar.kit.catalog.currentMagnitude
import net.kuecker.lodestar.kit.catalog.horizontal
import net.kuecker.lodestar.kit.catalog.riseTransitSet
import net.kuecker.lodestar.kit.ephemeris.RiseSetCalculator
import net.kuecker.lodestar.kit.ephemeris.RiseTransitSet
import net.kuecker.lodestar.kit.foundation.DateInterval
import net.kuecker.lodestar.kit.foundation.Observer
import net.kuecker.lodestar.kit.foundation.SkyFrame
import net.kuecker.lodestar.kit.foundation.timeIntervalSince
import net.kuecker.lodestar.kit.horizon.HorizonProfile
import net.kuecker.lodestar.kit.horizon.HorizonVisibility
import net.kuecker.lodestar.kit.planning.UnobstructedVisibility
import net.kuecker.lodestar.model.SkyClock
import java.time.Instant
import java.time.ZoneId

/** Where one object is in the observer's sky at the moment it was computed, and when it next rises. */
internal data class ObjectSkyPosition(
    /** Apparent altitude in degrees (refraction included), as the Aim screen shows it. */
    val altitude: Double,
    /** Azimuth in degrees from north through east. */
    val azimuth: Double,
    /** The next rising within [ObjectsPositionCalculator.RISE_SEARCH_WINDOW], if there is one. */
    val nextRise: Instant?,
    /** `true` when the object stays below the horizon throughout the search window. */
    val neverRises: Boolean,
    /** The current magnitude of the Sun, the Moon, the planets, comets and asteroids, whose brightness changes; `null` for other objects. */
    val magnitude: Double?,
    /** The instant the position was computed for. */
    val computedAt: Instant,
    /** `true` when a recorded skyline, not a flat horizon, decides whether the object is up. */
    val usesSkyline: Boolean = false,
    /** `true` when the object is up: above the horizon and, with a skyline, above the skyline at its azimuth. */
    val isUp: Boolean = altitude > 0,
    /** `true` when the object rises at this place but only ever sinks behind or stays behind the skyline in the window. */
    val isBlockedBySkyline: Boolean = false,
    /** For a comet or an asteroid: distances, elongation and warnings now; `null` for other objects and when its orbit cannot be evaluated. */
    val smallBody: SmallBodyInfo? = null,
) {
    /** `true` when the object is above the true horizon, whatever the skyline does. */
    val isAboveHorizon: Boolean get() = altitude > 0

    /** `true` when the object is above the horizon but hidden behind the recorded skyline. */
    val isBehindSkyline: Boolean get() = usesSkyline && isAboveHorizon && !isUp
}

/** Computes sky positions for lists of objects. Pure, so it can run on any thread. */
internal object ObjectsPositionCalculator {
    /**
     * How far ahead the next rising is looked for, in seconds. A little over a day, so every object that rises at
     * all has a rising in the window even when its rising time moves by almost an hour a day, as the Moon's does.
     */
    const val RISE_SEARCH_WINDOW = 25.0 * 3600

    /**
     * The position of every object in [objects] at [date], keyed by object identifier. With a [skyline] an object is up
     * only above it, and it rises when it clears it.
     */
    fun positions(
        objects: List<CelestialObject>,
        observer: Observer,
        date: Instant,
        skyline: HorizonProfile? = null,
    ): Map<String, ObjectSkyPosition> {
        val frame = SkyFrame(date, observer)
        val window = DateInterval(date, RISE_SEARCH_WINDOW)
        val horizon = skyline?.let(::HorizonVisibility)
        val result = HashMap<String, ObjectSkyPosition>(objects.size * 2)
        for (obj in objects) {
            val horizontal = frame.horizontal(obj, true)
            val flat = horizon == null
            val events = if (horizon != null) {
                horizon.riseTransitSet(obj, observer, window)
            } else {
                RiseSetCalculator.riseTransitSet(obj, observer, window)
            }
            val isHiddenAllWindow = events.circumstance == RiseTransitSet.Circumstance.ALWAYS_DOWN
            // Behind the skyline all window is not the same as never rising: the flat horizon tells them apart.
            val neverRises = isHiddenAllWindow &&
                (flat || RiseSetCalculator.riseTransitSet(obj, observer, window).circumstance ==
                    RiseTransitSet.Circumstance.ALWAYS_DOWN)
            result[obj.id] = ObjectSkyPosition(
                altitude = horizontal.altitude,
                azimuth = horizontal.azimuth,
                nextRise = events.rise,
                neverRises = neverRises,
                magnitude = if (obj.isSolarSystem) frame.currentMagnitude(obj) else null,
                computedAt = date,
                usesSkyline = !flat,
                isUp = horizon?.isVisible(horizontal.altitude, horizontal.azimuth, refracted = true) ?: (horizontal.altitude > 0),
                isBlockedBySkyline = isHiddenAllWindow && !neverRises,
                smallBody = obj.smallBody?.let { SmallBodyInfo.compute(it, frame) },
            )
        }
        return result
    }
}

/**
 * Keeps the sky positions of the objects on screen up to date for the current observer.
 *
 * The Objects list sorts and filters by these positions and shows them in every row. They are computed off the
 * main thread and refreshed every 30 s while the list is visible. [positions] is Compose state, so readers
 * recompose when a pass finishes. Main-thread only.
 *
 * When the list asks for them (the "Hours Visible Tonight" order or filter), the same loop also works out how many
 * hours tonight each tracked object is above the skyline ([tonight]). That is done once per observer, skyline and
 * night, for the objects that have no hours yet, and kept until one of them changes.
 */
internal class ObjectsPositionModel {
    /** The latest position of each tracked object, keyed by object identifier. */
    var positions: Map<String, ObjectSkyPosition> by mutableStateOf(emptyMap())
        private set

    /** Tonight's hours above the skyline of each tracked object, keyed by object identifier; empty until computed. */
    var tonight: Map<String, UnobstructedVisibility> by mutableStateOf(emptyMap())
        private set

    private var observer: Observer? = null
    private var skyline: HorizonProfile? = null
    private var tonightKey: TonightKey? = null

    /** What tonight's hours were computed for. */
    private data class TonightKey(val observer: Observer, val skyline: HorizonProfile?, val night: ObjectsNight)

    /** The latest position of the object with [id], or `null` when it is not tracked or not yet computed. */
    fun position(id: String): ObjectSkyPosition? = positions[id]

    /**
     * Computes the positions of the objects [tracked] returns now and then every 30 s until the calling coroutine
     * is cancelled. [tracked] is asked again on every pass, so the objects can change over time. With [wantsTonight]
     * it also keeps tonight's hours above the skyline up to date; [zone] decides which night is tonight.
     */
    suspend fun keepFresh(
        observer: Observer,
        skyline: HorizonProfile?,
        zone: ZoneId,
        clock: SkyClock,
        wantsTonight: Boolean,
        tracked: () -> List<CelestialObject>,
    ) {
        while (currentCoroutineContext().isActive) {
            val objects = tracked()
            refresh(objects, observer, skyline, clock.now)
            if (wantsTonight) refreshTonight(objects, observer, skyline, zone, clock.now)
            delay(REFRESH_INTERVAL_MS)
        }
    }

    /** Forgets every position and every hour, for when there is no observer. */
    fun clear() {
        positions = emptyMap()
        observer = null
        skyline = null
        tonight = emptyMap()
        tonightKey = null
    }

    private suspend fun refresh(objects: List<CelestialObject>, observer: Observer, skyline: HorizonProfile?, date: Instant) {
        val isNewObserver = this.observer != observer || this.skyline != skyline
        val outdated = if (isNewObserver) {
            objects
        } else {
            objects.filter { obj ->
                val known = positions[obj.id] ?: return@filter true
                date.timeIntervalSince(known.computedAt) > REUSE_AGE_SECONDS
            }
        }
        if (outdated.isEmpty()) return
        val computed = withContext(Dispatchers.Default) {
            ObjectsPositionCalculator.positions(outdated, observer, date, skyline)
        }
        currentCoroutineContext().ensureActive()
        if (isNewObserver) {
            positions = computed
            this.observer = observer
            this.skyline = skyline
        } else {
            positions = positions + computed
        }
    }

    /**
     * Works out tonight's hours for the [objects] that have none. Another observer, another skyline or the next night
     * makes every known hour wrong, so those start again.
     */
    private suspend fun refreshTonight(
        objects: List<CelestialObject>,
        observer: Observer,
        skyline: HorizonProfile?,
        zone: ZoneId,
        now: Instant,
    ) {
        val night = withContext(Dispatchers.Default) { ObjectsTonight.night(observer, zone, now) }
        val key = TonightKey(observer, skyline, night)
        val isNewKey = tonightKey != key
        val missing = if (isNewKey) objects else objects.filter { it.id !in tonight }
        if (missing.isEmpty()) return
        val computed = withContext(Dispatchers.Default) { ObjectsTonight.visibility(missing, observer, night, skyline) }
        currentCoroutineContext().ensureActive()
        tonight = if (isNewKey) computed else tonight + computed
        tonightKey = key
    }

    private companion object {
        /** How often positions are recomputed while the list is on screen. */
        const val REFRESH_INTERVAL_MS = 30_000L

        /** Positions computed less than this many seconds ago are reused when the tracked objects change. */
        const val REUSE_AGE_SECONDS = 25.0
    }
}
