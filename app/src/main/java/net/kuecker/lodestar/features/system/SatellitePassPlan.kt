package net.kuecker.lodestar.features.system

import net.kuecker.lodestar.features.satellites.SatelliteAimSource
import net.kuecker.lodestar.features.satellites.SatelliteFollowSource
import net.kuecker.lodestar.features.satellites.SatellitePeakSource
import net.kuecker.lodestar.kit.formatting.AngleFormatter
import net.kuecker.lodestar.services.SatellitePassItem
import java.time.Duration
import java.time.Instant
import kotlin.math.roundToInt

/**
 * The facts of one satellite pass that the countdown notification needs, as plain values: the moments of the rise, the
 * peak and the set, where to look at each, and the peak's height. Nothing here depends on Android, so the stages and
 * the words are testable, and the plan travels to the background work as a map of primitives ([toMap], [fromMap]).
 *
 * @property zoneID The IANA identifier of the observer's time zone, for the clock times in the words.
 */
internal data class SatellitePassPlan(
    val name: String,
    val rise: Instant,
    val peak: Instant,
    val set: Instant,
    val riseAzimuth: Double,
    val peakAzimuth: Double,
    val setAzimuth: Double,
    val peakAltitude: Double,
    val zoneID: String,
) {
    /** Where the pass is at one moment. */
    enum class Stage { WAITING, RISEN, PEAKED, OVER }

    /** The stage at [at]: before the rise, between rise and peak, between peak and set, after the set. */
    fun stage(at: Instant): Stage = when {
        at < rise -> Stage.WAITING
        at < peak -> Stage.RISEN
        at < set -> Stage.PEAKED
        else -> Stage.OVER
    }

    /** The moment the countdown runs to in [stage]: the rise before it, the peak after it, none later. */
    fun countdownTarget(stage: Stage): Instant? = when (stage) {
        Stage.WAITING -> rise
        Stage.RISEN -> peak
        Stage.PEAKED, Stage.OVER -> null
    }

    /** The moment the notification has to change next (the rise, the peak, the set), or `null` once the pass is over. */
    fun nextChange(at: Instant): Instant? = when (stage(at)) {
        Stage.WAITING -> rise
        Stage.RISEN -> peak
        Stage.PEAKED -> set
        Stage.OVER -> null
    }

    /** How long a notification posted at [at] may stay without an update: until [END_GRACE] after the set. */
    fun lifetime(at: Instant): Duration = Duration.between(at, set.plus(END_GRACE)).coerceAtLeast(Duration.ofSeconds(1))

    /** The plan as primitives that background work can carry. */
    fun toMap(): Map<String, Any> = mapOf(
        KEY_NAME to name,
        KEY_RISE to rise.toEpochMilli(),
        KEY_PEAK to peak.toEpochMilli(),
        KEY_SET to set.toEpochMilli(),
        KEY_RISE_AZIMUTH to riseAzimuth,
        KEY_PEAK_AZIMUTH to peakAzimuth,
        KEY_SET_AZIMUTH to setAzimuth,
        KEY_PEAK_ALTITUDE to peakAltitude,
        KEY_ZONE to zoneID,
    )

    companion object {
        /** A notification stays this long after the set when nothing removes it sooner (the system removes it then). */
        val END_GRACE: Duration = Duration.ofMinutes(5)

        private const val KEY_NAME = "name"
        private const val KEY_RISE = "rise"
        private const val KEY_PEAK = "peak"
        private const val KEY_SET = "set"
        private const val KEY_RISE_AZIMUTH = "riseAzimuth"
        private const val KEY_PEAK_AZIMUTH = "peakAzimuth"
        private const val KEY_SET_AZIMUTH = "setAzimuth"
        private const val KEY_PEAK_ALTITUDE = "peakAltitude"
        private const val KEY_ZONE = "zone"

        /** The plan [toMap] wrote, or `null` when a value is missing, of the wrong kind, not finite, or out of order. */
        fun fromMap(values: Map<String, Any?>): SatellitePassPlan? {
            fun moment(key: String) = (values[key] as? Long)?.let(Instant::ofEpochMilli)
            fun number(key: String) = (values[key] as? Double)?.takeIf { it.isFinite() }
            val plan = SatellitePassPlan(
                name = (values[KEY_NAME] as? String) ?: return null,
                rise = moment(KEY_RISE) ?: return null,
                peak = moment(KEY_PEAK) ?: return null,
                set = moment(KEY_SET) ?: return null,
                riseAzimuth = number(KEY_RISE_AZIMUTH) ?: return null,
                peakAzimuth = number(KEY_PEAK_AZIMUTH) ?: return null,
                setAzimuth = number(KEY_SET_AZIMUTH) ?: return null,
                peakAltitude = number(KEY_PEAK_ALTITUDE) ?: return null,
                zoneID = (values[KEY_ZONE] as? String) ?: return null,
            )
            return plan.takeIf { it.rise <= it.peak && it.peak <= it.set }
        }

        /** The plan of the pass a satellite source was chosen from, or `null` for a source that has no pass. */
        fun of(source: SatelliteAimSource, zoneID: String): SatellitePassPlan? {
            val item: SatellitePassItem = when (source) {
                is SatelliteFollowSource -> source.pass
                is SatellitePeakSource -> source.pass
            } ?: return null
            return of(item, zoneID)
        }

        /** The plan of [item]: its rise, culmination and set. */
        fun of(item: SatellitePassItem, zoneID: String): SatellitePassPlan {
            val pass = item.pass
            return SatellitePassPlan(
                name = item.name,
                rise = pass.rise.date,
                peak = pass.culmination.date,
                set = pass.set.date,
                riseAzimuth = pass.rise.azimuth,
                peakAzimuth = pass.culmination.azimuth,
                setAzimuth = pass.set.azimuth,
                peakAltitude = pass.culmination.altitude,
                zoneID = zoneID,
            )
        }
    }
}

/** The words of the countdown notification. The iOS Live Activity says the same things. */
internal object SatellitePassWords {
    const val CHANNEL_NAME = "Satellite pass countdown"
    const val CHANNEL_DESCRIPTION = "A countdown to the peak of the satellite pass you follow in Aim."
    const val COUNTING_NOTE = "Counting down to the peak"
    const val COUNTING_NOTE_RISE = "Counting down to the rise"

    fun title(plan: SatellitePassPlan): String = "${plan.name} pass"

    /** What the countdown counts to in [stage], or `null` when there is none (past the peak). */
    fun note(stage: SatellitePassPlan.Stage): String? = when (stage) {
        SatellitePassPlan.Stage.WAITING -> COUNTING_NOTE_RISE
        SatellitePassPlan.Stage.RISEN -> COUNTING_NOTE
        SatellitePassPlan.Stage.PEAKED, SatellitePassPlan.Stage.OVER -> null
    }

    /**
     * The line under the title for [stage]; [time] writes a clock time in the observer's zone.
     * Before the rise: when and where it comes up and where it peaks; after it: that it is up; after the peak: when it sets.
     */
    fun body(plan: SatellitePassPlan, stage: SatellitePassPlan.Stage, time: (Instant) -> String): String = when (stage) {
        SatellitePassPlan.Stage.WAITING ->
            "Rises ${time(plan.rise)} ${where(plan.riseAzimuth)} · peak ${height(plan)} ${where(plan.peakAzimuth)}"
        SatellitePassPlan.Stage.RISEN ->
            "Up now · peak ${height(plan)} ${where(plan.peakAzimuth)}"
        SatellitePassPlan.Stage.PEAKED, SatellitePassPlan.Stage.OVER ->
            "Past the peak · sets ${time(plan.set)} ${where(plan.setAzimuth)}"
    }

    /** "64°": how high the peak is. */
    private fun height(plan: SatellitePassPlan): String = "${plan.peakAltitude.roundToInt()}°"

    /** "in the NE (45°)". */
    private fun where(azimuth: Double): String =
        "in the ${AngleFormatter.compassPoint(azimuth).abbreviation} (${azimuth.roundToInt() % 360}°)"
}
