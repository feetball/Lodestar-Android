package net.kuecker.lodestar.features.satellites

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import net.kuecker.lodestar.kit.foundation.HorizontalCoordinate
import net.kuecker.lodestar.kit.foundation.Observer
import net.kuecker.lodestar.kit.foundation.Vector3
import net.kuecker.lodestar.kit.foundation.timeIntervalSince
import net.kuecker.lodestar.kit.satellites.Satellite
import net.kuecker.lodestar.kit.satellites.SatelliteLook
import net.kuecker.lodestar.kit.satellites.SatelliteSkyContext
import net.kuecker.lodestar.services.SatellitePassItem
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * Where Aim leads guidance for a satellite, at one instant.
 *
 * @property horizontal The direction to point the camera at: the apparent (refracted) direction.
 * @property label The marker's name on the sky.
 * @property look The live look of a followed satellite (range, angular speed, shadow); `null` for a fixed point.
 * @property secondsToPeak For "wait at the peak": seconds until the satellite is there, negative once it has passed.
 */
class SatelliteAimFix(
    val horizontal: HorizontalCoordinate,
    val label: String,
    val look: SatelliteLook?,
    val secondsToPeak: Double?,
) {
    /** The direction as an East–North–Up unit vector. */
    val direction: Vector3 get() = horizontal.unitVector
}

/**
 * What Aim aims at instead of an object while a satellite is chosen: a moving target ([SatelliteFollowSource]) or a fixed
 * point of the sky to wait at ([SatellitePeakSource]). Immutable and thread safe.
 */
sealed class SatelliteAimSource(val title: String, val observer: Observer) {
    /** Tells sources apart: guidance starts afresh when it changes, and only then. */
    val key: Int = counter.incrementAndGet()

    /** Where to point at [date]; `null` where the orbit cannot be computed. */
    abstract fun fix(date: Instant): SatelliteAimFix?

    private companion object {
        val counter = AtomicInteger()
    }
}

/**
 * A live, moving target: the satellite itself. Every call builds one [SatelliteSkyContext] for the instant and looks the
 * satellite up in it (`look(in:)`); the guidance target is that look's `apparentHorizontal`.
 *
 * @property pass The pass it was chosen from, for the note about when it rises or is over.
 */
class SatelliteFollowSource(
    val satellite: Satellite,
    title: String,
    observer: Observer,
    val pass: SatellitePassItem? = null,
) : SatelliteAimSource(title, observer) {
    override fun fix(date: Instant): SatelliteAimFix? {
        val look = satellite.look(SatelliteSkyContext(date, observer)) ?: return null
        return SatelliteAimFix(look.apparentHorizontal, title, look, null)
    }
}

/**
 * A fixed point of the sky: where the pass reaches its highest point, with the time until the satellite gets there.
 *
 * @property peak The instant of the culmination.
 * @property horizontal The (refracted) direction of the satellite at [peak].
 */
class SatellitePeakSource(
    title: String,
    observer: Observer,
    val peak: Instant,
    val horizontal: HorizontalCoordinate,
) : SatelliteAimSource(title, observer) {
    override fun fix(date: Instant): SatelliteAimFix =
        SatelliteAimFix(horizontal, "$title peak", null, peak.timeIntervalSince(date))

    companion object {
        /** The peak of [item]'s pass for [observer]. */
        fun create(item: SatellitePassItem, observer: Observer): SatellitePeakSource {
            val culmination = item.pass.culmination
            val look = item.satellite.look(culmination.date, observer)
            return SatellitePeakSource(item.name, observer, culmination.date, look?.apparentHorizontal ?: culmination.horizontal)
        }
    }
}

/**
 * The satellite Aim is following or waiting for, kept with the app model so it survives tab switches. Aim reads it; the
 * pass screens set it.
 */
class SatelliteAimSession {
    /** The chosen satellite target, or `null` for the ordinary target. */
    var source: SatelliteAimSource? by mutableStateOf(null)
        private set

    /** The id of the app's target when the satellite was chosen: choosing another target ends the satellite guidance. */
    var targetIdAtStart: String? = null
        private set

    /** Leads Aim to [source]; [currentTargetId] is the id of the target Aim has now. */
    fun start(source: SatelliteAimSource, currentTargetId: String?) {
        targetIdAtStart = currentTargetId
        this.source = source
    }

    /** Back to the ordinary target. */
    fun stop() {
        source = null
    }

    /** Ends the satellite guidance when the app's target is not the one it started with. */
    fun targetChanged(currentTargetId: String?) {
        if (source != null && currentTargetId != targetIdAtStart) stop()
    }

    companion object {
        /** The key under which the app model keeps the session. */
        const val KEY = "satellites.aim"
    }
}
