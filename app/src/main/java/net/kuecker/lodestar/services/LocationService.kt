package net.kuecker.lodestar.services

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import net.kuecker.lodestar.kit.foundation.AstroMath
import net.kuecker.lodestar.kit.foundation.GeographicLocation
import java.time.Instant
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** A device position reduced to plain values. */
data class LocationFix(
    val latitude: Double,
    val longitude: Double,
    /** Metres above sea level; meaningful only when [verticalAccuracy] is positive. */
    val altitude: Double,
    /** Radius of uncertainty in metres. */
    val horizontalAccuracy: Double,
    /** Altitude uncertainty in metres; not positive when the altitude is invalid. */
    val verticalAccuracy: Double,
    val timestamp: Instant,
) {
    /** Great-circle distance in metres to [other] (spherical Earth, accurate to a fraction of a percent). */
    fun distance(other: LocationFix): Double {
        val phi1 = AstroMath.radians(latitude)
        val phi2 = AstroMath.radians(other.latitude)
        val sinHalfLatitude = sin((phi2 - phi1) / 2)
        val sinHalfLongitude = sin(AstroMath.radians(other.longitude - longitude) / 2)
        val haversine = sinHalfLatitude * sinHalfLatitude + cos(phi1) * cos(phi2) * sinHalfLongitude * sinHalfLongitude
        return 2 * 6_371_000 * asin(min(1.0, sqrt(haversine)))
    }

    companion object {
        /** Copies an Android location; `null` when it has no valid position. */
        fun create(location: Location): LocationFix? {
            val latitude = location.latitude
            val longitude = location.longitude
            if (!latitude.isFinite() || !longitude.isFinite() || latitude !in -90.0..90.0 || longitude !in -180.0..180.0) {
                return null
            }
            val horizontal = if (location.hasAccuracy()) location.accuracy.toDouble() else 1_000.0
            if (horizontal < 0) return null
            val hasVertical = location.hasAltitude() && location.hasVerticalAccuracy()
            val altitude = when {
                Build.VERSION.SDK_INT >= 34 && location.hasMslAltitude() -> location.mslAltitudeMeters
                location.hasAltitude() -> location.altitude
                else -> 0.0
            }
            return LocationFix(
                latitude = latitude, longitude = longitude, altitude = altitude, horizontalAccuracy = horizontal,
                verticalAccuracy = if (hasVertical) location.verticalAccuracyMeters.toDouble() else -1.0,
                timestamp = Instant.ofEpochMilli(location.time),
            )
        }
    }
}

/**
 * Decides when a new fix replaces the observer location that all sky computations use.
 *
 * Every observer change recomputes the sky, so GPS jitter must not reach it: the first fix is adopted, later ones
 * only when the device moved noticeably or the position became much more precise.
 */
class StableLocationFilter {
    companion object {
        /** Metres the device must move before the location is replaced. */
        const val MOVEMENT_THRESHOLD = 250.0

        /** A fix replaces the location when its accuracy radius shrank by at least this factor … */
        const val ACCURACY_IMPROVEMENT_FACTOR = 2.0

        /** … and by at least this many metres. */
        const val MINIMUM_ACCURACY_GAIN = 500.0

        /** The fix as an observer location; the elevation is used only when the altitude is valid. */
        private fun location(fix: LocationFix): GeographicLocation? {
            val range = GeographicLocation.elevationRange
            val elevation = if (fix.verticalAccuracy > 0 && fix.altitude.isFinite()) {
                AstroMath.clamp(fix.altitude, range.start, range.endInclusive)
            } else {
                0.0
            }
            return GeographicLocation.validated(fix.latitude, fix.longitude, elevation)
        }
    }

    private var anchor: LocationFix? = null

    /** The location to use from now on if [fix] should replace the current one, otherwise `null`. */
    fun accept(fix: LocationFix): GeographicLocation? {
        anchor?.let { current ->
            val moved = current.distance(fix) > MOVEMENT_THRESHOLD
            val moreAccurate = fix.horizontalAccuracy * ACCURACY_IMPROVEMENT_FACTOR < current.horizontalAccuracy &&
                current.horizontalAccuracy - fix.horizontalAccuracy > MINIMUM_ACCURACY_GAIN
            if (!moved && !moreAccurate) return null
        }
        val location = location(fix) ?: return null
        anchor = fix
        return location
    }
}

/** Location permission as the app sees it. */
enum class LocationAuthorization {
    /** Not granted, and the last request (if any) was not refused: asking shows the system dialog. */
    NOT_DETERMINED,

    /** The user refused the last request. */
    DENIED,

    /** Precise or approximate location is granted. */
    AUTHORIZED,
}

/**
 * The device location, with while-in-use permission and a stabilised observer position.
 *
 * Approximate location is fine for the sky: a few kilometres change altitudes and azimuths by well under a tenth
 * of a degree. Heading is not used; aiming relies on [MotionService]. Uses the platform location providers (no
 * Google Play services), so it works on every Android device.
 */
class LocationService(private val context: Context, private val preferences: SharedPreferences) {
    private companion object {
        const val DENIED_KEY = "lodestar.location.lastRequestDenied"
        const val UPDATE_INTERVAL_MS = 10_000L
        const val MINIMUM_DISTANCE_M = 500f

        /** A cached fix older than this is not used to start with. */
        const val MAXIMUM_CACHED_AGE_MS = 30 * 60 * 1000L
    }

    var authorization by mutableStateOf(LocationAuthorization.NOT_DETERMINED)
        private set

    /** Only approximate location is granted. */
    var isReducedAccuracy by mutableStateOf(false)
        private set

    /** The latest raw fix. */
    var fix by mutableStateOf<LocationFix?>(null)
        private set

    /**
     * The observer location for sky computations: set on the first fix, then replaced only when the device moved
     * more than 250 m or its accuracy improved substantially. The elevation is the fix's when its vertical accuracy
     * is valid, otherwise 0.
     */
    var stableLocation by mutableStateOf<GeographicLocation?>(null)
        private set

    /** A message for the user (no coordinates) when location updates fail; cleared by the next good fix. */
    var lastErrorDescription by mutableStateOf<String?>(null)
        private set

    /** Shows the system permission dialog; installed by the activity. */
    var permissionPrompt: (() -> Unit)? = null

    private val manager = context.getSystemService(LocationManager::class.java)
    private val filter = StableLocationFilter()
    private var wantsUpdates = false
    private var isUpdating = false
    private val listener = object : LocationListenerCompat {
        override fun onLocationChanged(location: Location) {
            LocationFix.create(location)?.let(::receive)
        }

        override fun onProviderDisabled(provider: String) = refreshAuthorization()
        override fun onProviderEnabled(provider: String) = refreshAuthorization()
    }

    init {
        refreshAuthorization()
    }

    /** Location access is granted while the app is in use. */
    val isAuthorized: Boolean get() = authorization == LocationAuthorization.AUTHORIZED

    /** The user turned location access down. */
    val isDenied: Boolean get() = authorization == LocationAuthorization.DENIED

    /** Shows the system permission dialog, but only if the user has not refused it. */
    fun requestAuthorization() {
        if (authorization != LocationAuthorization.NOT_DETERMINED) return
        permissionPrompt?.invoke()
    }

    /** Asks again after a refusal (the activity opens the app's settings when Android no longer shows the dialog). */
    fun requestAgain() {
        permissionPrompt?.invoke()
    }

    /** Called by the activity with the result of the permission dialog. */
    fun onPermissionResult() {
        val granted = hasPermission()
        preferences.edit { putBoolean(DENIED_KEY, !granted) }
        refreshAuthorization()
    }

    /** Starts location updates as soon as access is granted. Idempotent; also re-checks the permission. */
    fun start() {
        wantsUpdates = true
        refreshAuthorization()
    }

    /** Stops location updates. Idempotent. */
    fun stop() {
        wantsUpdates = false
        if (!isUpdating) return
        manager?.let(::removeUpdates)
        isUpdating = false
    }

    // Private

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun hasPermission(): Boolean =
        hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) || hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)

    /** Reads the permission and starts or stops updates accordingly. Properties are written only when they change. */
    private fun refreshAuthorization() {
        val granted = hasPermission()
        val status = when {
            granted -> LocationAuthorization.AUTHORIZED
            preferences.getBoolean(DENIED_KEY, false) -> LocationAuthorization.DENIED
            else -> LocationAuthorization.NOT_DETERMINED
        }
        if (authorization != status) {
            authorization = status
            if (granted && lastErrorDescription != null) lastErrorDescription = null
        }
        val reduced = granted && !hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        if (isReducedAccuracy != reduced) isReducedAccuracy = reduced

        if (granted && wantsUpdates) {
            val enabled = manager?.let(LocationManagerCompat::isLocationEnabled) == true
            if (!enabled) {
                setError("Location is turned off in your phone's settings.")
                if (isUpdating) stopUpdates()
            } else if (!isUpdating) {
                startUpdates()
            }
        } else if (isUpdating) {
            stopUpdates()
        }
    }

    @SuppressLint("MissingPermission") // checked by the caller
    private fun startUpdates() {
        val manager = manager ?: return
        val providers = providers(manager)
        if (providers.isEmpty()) {
            setError("Your location could not be determined.")
            return
        }
        val request = LocationRequestCompat.Builder(UPDATE_INTERVAL_MS)
            .setMinUpdateDistanceMeters(MINIMUM_DISTANCE_M)
            .setQuality(LocationRequestCompat.QUALITY_BALANCED_POWER_ACCURACY)
            .build()
        val executor = ContextCompat.getMainExecutor(context)
        try {
            for (provider in providers) {
                LocationManagerCompat.requestLocationUpdates(manager, provider, request, executor, listener)
            }
            isUpdating = true
            providers.mapNotNull { manager.getLastKnownLocation(it) }
                .filter { System.currentTimeMillis() - it.time < MAXIMUM_CACHED_AGE_MS }
                .maxByOrNull { it.time }
                ?.let { LocationFix.create(it) }
                ?.let(::receive)
        } catch (_: SecurityException) {
            // The permission was revoked between the check and the request.
            removeUpdates(manager)
            isUpdating = false
        }
    }

    private fun stopUpdates() {
        manager?.let(::removeUpdates)
        isUpdating = false
    }

    private fun removeUpdates(manager: LocationManager) {
        try {
            LocationManagerCompat.removeUpdates(manager, listener)
        } catch (_: SecurityException) {
            // The permission is already gone, and with it every registration of this app.
        }
    }

    /** The fused provider when the platform has one, otherwise GPS (precise permission only) and network. */
    private fun providers(manager: LocationManager): List<String> {
        val all = manager.allProviders
        if (Build.VERSION.SDK_INT >= 31 && LocationManager.FUSED_PROVIDER in all) return listOf(LocationManager.FUSED_PROVIDER)
        val precise = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        return buildList {
            if (precise && LocationManager.GPS_PROVIDER in all) add(LocationManager.GPS_PROVIDER)
            if (LocationManager.NETWORK_PROVIDER in all) add(LocationManager.NETWORK_PROVIDER)
        }
    }

    private fun receive(newFix: LocationFix) {
        val current = fix
        // Providers deliver independently; never go back in time.
        if (current != null && newFix.timestamp < current.timestamp) return
        fix = newFix
        if (lastErrorDescription != null) lastErrorDescription = null
        filter.accept(newFix)?.let { stableLocation = it }
    }

    private fun setError(message: String) {
        if (lastErrorDescription != message) lastErrorDescription = message
    }
}
