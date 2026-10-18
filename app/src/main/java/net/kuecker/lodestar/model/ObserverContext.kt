package net.kuecker.lodestar.model

import net.kuecker.lodestar.kit.foundation.GeographicLocation
import net.kuecker.lodestar.kit.foundation.Observer
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
) {
    val location: GeographicLocation get() = observer.location
}
