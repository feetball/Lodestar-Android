package net.kuecker.lodestar.kit.planning

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.kuecker.lodestar.kit.ephemeris.*
import net.kuecker.lodestar.kit.foundation.*
import java.time.Instant

/**
 * Which dark time a visibility calculation counts.
 *
 * Serialized as its [rawValue] (`"astronomical"`, `"moonFree"`), like Swift's raw-value `Codable`.
 */
@Serializable
enum class DarknessKind(val rawValue: String) {
    /** The Sun is more than 18° below the horizon. */
    @SerialName("astronomical")
    ASTRONOMICAL("astronomical"),

    /**
     * The Sun is more than 18° below the horizon and the Moon is below the horizon too (the Moon's upper
     * limb, after standard refraction, as in [RiseSetCalculator]).
     */
    @SerialName("moonFree")
    MOON_FREE("moonFree"),
    ;

    companion object {
        /** The kind whose [rawValue] is [raw], or `null` (Swift `init?(rawValue:)`). */
        fun fromRawValue(raw: String): DarknessKind? = entries.firstOrNull { it.rawValue == raw }
    }
}

/**
 * How well a target can be observed during the dark hours of one night.
 *
 * Altitudes are geometric (no refraction), the altitude of the target's centre seen from the
 * observer. The target is judged only while it is dark; [riseTransitSet] covers the whole night.
 *
 * @property riseTransitSet When the target rises, culminates and sets in the night, with the horizon at
 * −0.5667° (see [RiseSetCalculator]).
 * @property minimumAltitude The altitude, in degrees, that [intervalsAboveMinimum] and [hoursAboveMinimum]
 * refer to.
 * @property maxAltitudeDuringDarkness The highest altitude reached while it is dark, in degrees. It is
 * negative for a target that stays below the horizon, and `null` when there is no darkness at all. The
 * value is good to a few thousandths of a degree, and to 0.03° for a target that passes within a degree of
 * the zenith.
 * @property bestTime The instant of [maxAltitudeDuringDarkness]; `null` when there is no darkness.
 * @property intervalsAboveMinimum The stretches of darkness in which the target is at or above
 * [minimumAltitude], in time order.
 */
data class TargetVisibility(
    val riseTransitSet: RiseTransitSet,
    val minimumAltitude: Double,
    val maxAltitudeDuringDarkness: Double?,
    val bestTime: Instant?,
    val intervalsAboveMinimum: List<DateInterval>,
) {
    /** The hours of darkness the target spends at or above [minimumAltitude]. */
    val hoursAboveMinimum: Double
        get() = IntervalSet.totalDuration(intervalsAboveMinimum) / 3600
}
