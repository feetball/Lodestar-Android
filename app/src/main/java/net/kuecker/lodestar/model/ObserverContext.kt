package net.kuecker.lodestar.model

import net.kuecker.lodestar.kit.foundation.GeographicLocation
import net.kuecker.lodestar.kit.foundation.Observer
import net.kuecker.lodestar.kit.horizon.HorizonProfile
import java.time.Instant
import java.time.ZoneId

/** The resolved observing context every astronomy computation in the app uses. */
data class ObserverContext(
    /** Location and atmosphere for the kit. */
    val observer: Observer,
    /** Time zone used to present times (rise/set, twilight) for this location. */
    val timeZone: ZoneId,
    /** Display name, e.g. "Current Location" or a saved site's name. */
    val name: String,
    val source: LocationSource,
    /** Horizontal accuracy in metres (device location only). */
    val horizontalAccuracy: Double? = null,
    /** When the device location was measured (device location only). */
    val timestamp: Instant? = null,
    /**
     * The skyline that applies at the site: its recorded skyline, the terrain horizon computed from elevation data, or
     * the recorded one with the terrain filling the stretches that were not swept; null for an open horizon (always
     * null for the device location).
     */
    val horizon: HorizonProfile? = null,
    /** Where [horizon] comes from; null when there is none. */
    val horizonSource: HorizonSource? = null,
) {
    val location: GeographicLocation get() = observer.location
}

/** Where the skyline of an [ObserverContext] comes from. [label] is how the screens name it. */
enum class HorizonSource(val label: String) {
    /** The skyline the user recorded by sweeping the phone. */
    RECORDED("Recorded skyline"),

    /** The skyline computed from elevation data. */
    TERRAIN("Terrain horizon"),

    /** The recorded skyline where the sweep reached, the terrain horizon in the stretches it left open. */
    RECORDED_AND_TERRAIN("Recorded + terrain"),
}
