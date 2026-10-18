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
import net.kuecker.lodestar.model.SkyClock
import java.time.Instant

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
    /** The current magnitude of the Sun, the Moon and the planets, whose brightness changes; `null` for other objects. */
    val magnitude: Double?,
    /** The instant the position was computed for. */
    val computedAt: Instant,
) {
    val isAboveHorizon: Boolean get() = altitude > 0
}

/** Computes sky positions for lists of objects. Pure, so it can run on any thread. */
internal object ObjectsPositionCalculator {
    /**
     * How far ahead the next rising is looked for, in seconds. A little over a day, so every object that rises at
     * all has a rising in the window even when its rising time moves by almost an hour a day, as the Moon's does.
     */
    const val RISE_SEARCH_WINDOW = 25.0 * 3600

    /** The position of every object in [objects] at [date], keyed by object identifier. */
    fun positions(objects: List<CelestialObject>, observer: Observer, date: Instant): Map<String, ObjectSkyPosition> {
        val frame = SkyFrame(date, observer)
        val window = DateInterval(date, RISE_SEARCH_WINDOW)
        val result = HashMap<String, ObjectSkyPosition>(objects.size * 2)
        for (obj in objects) {
            val horizontal = frame.horizontal(obj, true)
            val events = RiseSetCalculator.riseTransitSet(obj, observer, window)
            result[obj.id] = ObjectSkyPosition(
                altitude = horizontal.altitude,
                azimuth = horizontal.azimuth,
                nextRise = events.rise,
                neverRises = events.circumstance == RiseTransitSet.Circumstance.ALWAYS_DOWN,
                magnitude = if (obj.isSolarSystem) frame.currentMagnitude(obj) else null,
                computedAt = date,
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
 */
internal class ObjectsPositionModel {
    /** The latest position of each tracked object, keyed by object identifier. */
    var positions: Map<String, ObjectSkyPosition> by mutableStateOf(emptyMap())
        private set

    private var observer: Observer? = null

    /** The latest position of the object with [id], or `null` when it is not tracked or not yet computed. */
    fun position(id: String): ObjectSkyPosition? = positions[id]

    /**
     * Computes the positions of the objects [tracked] returns now and then every 30 s until the calling coroutine
     * is cancelled. [tracked] is asked again on every pass, so the objects can change over time.
     */
    suspend fun keepFresh(observer: Observer, clock: SkyClock, tracked: () -> List<CelestialObject>) {
        while (currentCoroutineContext().isActive) {
            refresh(tracked(), observer, clock.now)
            delay(REFRESH_INTERVAL_MS)
        }
    }

    /** Forgets every position, for when there is no observer. */
    fun clear() {
        positions = emptyMap()
        observer = null
    }

    private suspend fun refresh(objects: List<CelestialObject>, observer: Observer, date: Instant) {
        val isNewObserver = this.observer != observer
        val outdated = if (isNewObserver) {
            objects
        } else {
            objects.filter { obj ->
                val known = positions[obj.id] ?: return@filter true
                date.timeIntervalSince(known.computedAt) > REUSE_AGE_SECONDS
            }
        }
        if (outdated.isEmpty()) return
        val computed = withContext(Dispatchers.Default) { ObjectsPositionCalculator.positions(outdated, observer, date) }
        currentCoroutineContext().ensureActive()
        if (isNewObserver) {
            positions = computed
            this.observer = observer
        } else {
            positions = positions + computed
        }
    }

    private companion object {
        /** How often positions are recomputed while the list is on screen. */
        const val REFRESH_INTERVAL_MS = 30_000L

        /** Positions computed less than this many seconds ago are reused when the tracked objects change. */
        const val REUSE_AGE_SECONDS = 25.0
    }
}
