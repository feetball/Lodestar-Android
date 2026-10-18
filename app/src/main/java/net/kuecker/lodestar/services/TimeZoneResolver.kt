package net.kuecker.lodestar.services

import android.content.Context
import android.icu.util.TimeZone as IcuTimeZone
import android.location.Address
import android.location.Geocoder
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.kuecker.lodestar.kit.foundation.GeographicLocation
import net.kuecker.lodestar.kit.foundation.roundedHalfAwayFromZero
import java.io.IOException
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** The time zone of a place as far as the phone's geocoder can tell. */
sealed interface ResolvedTimeZone {
    val zone: ZoneId

    /** The place's country has just this one zone, so it is certain. */
    data class Exact(override val zone: ZoneId) : ResolvedTimeZone

    /**
     * The country has several zones and nothing on the phone tells their borders: [zone] is a guess, the zone whose
     * reference city is nearest to the place. The user should confirm it (see [TimeZoneResolver.zonesOfCountry]).
     */
    data class Estimated(override val zone: ZoneId) : ResolvedTimeZone
}

/**
 * Finds the time zone of an observing site so rise, set and twilight times can be shown in local time.
 *
 * Android has no reverse geocoder that returns a zone, so the lookup takes two steps: the phone's [Geocoder] (on
 * most phones Google's) turns the coordinate into a country, and the ICU zone data lists that country's zones. A
 * country with one zone is certain. In a country with several, the zone of the nearest reference city is guessed
 * (the principal cities of the tz database's zone.tab, see [ZONE_REFERENCE_POINTS]); without reference cities the
 * zone whose standard offset is closest to what the longitude suggests is used instead. Either guess is only a start
 * for the user to confirm; a city table is not a map of the borders.
 *
 * The geocoder call is the app's only network use besides map tiles, gives up after [LOOKUP_TIMEOUT_MS] and sends
 * nothing but the one user-chosen coordinate. Callers fall back to [nominalTimeZone] when it has no answer.
 */
object TimeZoneResolver {
    /** Longest wait for the geocoding service. */
    private const val LOOKUP_TIMEOUT_MS = 8_000L

    /** Zones closer than this to the best one, in hours, count as equally close. */
    private const val TIE_TOLERANCE_HOURS = 1e-6

    /** ICU's region code for zones that belong to no country (UTC, GMT, Etc/…). */
    private const val WORLD_REGION = "001"

    private const val EARTH_RADIUS_KILOMETRES = 6_371.0

    /** The reference cities by country, parsed from [ZONE_REFERENCE_POINTS] on first use. */
    private val referencePoints: Map<String, List<ReferencePoint>> by lazy {
        ZONE_REFERENCE_POINTS.lineSequence()
            .filter(String::isNotBlank)
            .map { line ->
                val field = line.trim().split(' ')
                field[0] to ReferencePoint(field[1], field[2].toDouble(), field[3].toDouble())
            }
            .groupBy({ it.first }, { it.second })
    }

    /** Runs the blocking geocoder call of Android 12 and older, which cannot be cancelled once it has started. */
    private val blockingLookups = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * The zone at [location], or `null` when the geocoder is missing, unreachable, slow or knows no country there
     * (e.g. the open sea).
     */
    suspend fun timeZone(context: Context, location: GeographicLocation): ResolvedTimeZone? {
        val country = countryCode(context.applicationContext, location) ?: return null
        return withContext(Dispatchers.Default) { resolve(country, location.latitude, location.longitude) }
    }

    /**
     * A fixed-offset zone ("GMT-06:00", or "GMT" at offset zero) from the longitude alone (15° per hour), for use
     * offline. It ignores political borders and daylight saving time, so it is only a stopgap. The "GMT" prefix
     * marks it as such (see `SettingsFormat.isNominal`).
     */
    fun nominalTimeZone(longitude: Double): ZoneId {
        val hours = if (longitude.isFinite()) (longitude / 15).roundedHalfAwayFromZero().coerceIn(-12.0, 14.0).toInt() else 0
        return ZoneId.ofOffset("GMT", ZoneOffset.ofHours(hours))
    }

    /**
     * All zones of the country that [zone] belongs to, from west to east, including [zone] itself. Empty for zones
     * that belong to no country, such as the nominal fallback. Needs no network.
     */
    fun zonesOfCountry(zone: ZoneId): List<ZoneId> {
        val region = try {
            IcuTimeZone.getRegion(zone.id)
        } catch (_: IllegalArgumentException) {
            return emptyList() // not a zone ICU knows (e.g. a fixed offset)
        }
        if (region == WORLD_REGION) return emptyList()
        val zones = zonesOfRegion(region)
        // An alias such as "Asia/Calcutta" stands in for its canonical zone instead of adding a duplicate.
        val canonical = canonicalId(zone)
        if (zones.none { canonicalId(it) == canonical }) return listOf(zone) + zones
        return zones.map { if (canonicalId(it) == canonical) zone else it }
    }

    // Geocoding

    private suspend fun countryCode(context: Context, location: GeographicLocation): String? {
        if (!Geocoder.isPresent()) return null
        val geocoder = Geocoder(context)
        return withTimeoutOrNull(LOOKUP_TIMEOUT_MS) {
            val addresses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                geocoder.addresses(location)
            } else {
                blockingAddresses(geocoder, location)
            }
            addresses.firstNotNullOfOrNull { it.countryCode }
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun Geocoder.addresses(location: GeographicLocation): List<Address> =
        suspendCancellableCoroutine { continuation ->
            try {
                getFromLocation(
                    location.latitude, location.longitude, 1,
                    object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: List<Address>) {
                            continuation.resume(addresses)
                        }

                        override fun onError(errorMessage: String?) {
                            continuation.resume(emptyList())
                        }
                    },
                )
            } catch (_: RuntimeException) {
                // The geocoder is an OEM component that can fail in many ways; every failure means "no answer".
                continuation.resume(emptyList())
            }
        }

    /** The blocking call runs on its own scope so a timeout can abandon it (the geocoder ignores cancellation). */
    @Suppress("DEPRECATION") // the asynchronous overload exists only from Android 13
    private suspend fun blockingAddresses(geocoder: Geocoder, location: GeographicLocation): List<Address> {
        val lookup = blockingLookups.async {
            try {
                geocoder.getFromLocation(location.latitude, location.longitude, 1).orEmpty()
            } catch (_: IOException) {
                emptyList() // no network, or the service is down
            } catch (_: RuntimeException) {
                emptyList() // an OEM geocoder can fail in many ways; every failure means "no answer"
            }
        }
        try {
            return lookup.await()
        } finally {
            lookup.cancel()
        }
    }

    // Zone data

    private fun resolve(countryCode: String, latitude: Double, longitude: Double): ResolvedTimeZone? {
        val zones = zonesOfRegion(countryCode)
        return when (zones.size) {
            0 -> null
            1 -> ResolvedTimeZone.Exact(zones.first())
            else -> ResolvedTimeZone.Estimated(
                nearestZone(countryCode, zones, latitude, longitude) ?: closestZone(zones, longitude),
            )
        }
    }

    /** The canonical location zones of an ISO 3166 region, from west to east, then by name. */
    private fun zonesOfRegion(region: String): List<ZoneId> {
        val now = Instant.now()
        val code = region.uppercase(Locale.ROOT)
        val ids: Collection<String> = if (Build.VERSION.SDK_INT >= 28) {
            IcuTimeZone.getAvailableIDs(IcuTimeZone.SystemTimeZoneType.CANONICAL_LOCATION, code, null)
        } else {
            // Android 8: the region's IDs include aliases; reduce them to the canonical location zones.
            IcuTimeZone.getAvailableIDs(code).mapNotNull { IcuTimeZone.getCanonicalID(it) }
                .filterNot { it.startsWith("Etc/") }
                .toSet()
        }
        return ids.mapNotNull(::zoneOrNull)
            .sortedWith(compareBy<ZoneId>({ standardOffsetSeconds(it, now) }, { it.id }))
    }

    /**
     * The zone whose nearest reference city is nearest to the position, or `null` when a zone has no reference city
     * (this phone's zone data knows a zone the table does not, or the country is not in the table).
     */
    private fun nearestZone(countryCode: String, zones: List<ZoneId>, latitude: Double, longitude: Double): ZoneId? {
        val pointsByZone = referencePoints[countryCode]?.groupBy { canonicalId(it.zone) } ?: return null
        var nearest: ZoneId? = null
        var nearestKilometres = Double.MAX_VALUE
        for (zone in zones) {
            val points = pointsByZone[canonicalId(zone)] ?: return null
            val kilometres = points.minOf { distanceKilometres(latitude, longitude, it.latitude, it.longitude) }
            if (kilometres < nearestKilometres) {
                nearest = zone
                nearestKilometres = kilometres
            }
        }
        return nearest
    }

    /**
     * The zone whose standard offset is nearest to what the longitude suggests (longitude ÷ 15 hours). Zones that
     * are equally near are told apart by preferring the phone's own zone, a good guess when the site is in the
     * country the user lives in, and otherwise the first by name.
     */
    private fun closestZone(zones: List<ZoneId>, longitude: Double): ZoneId {
        val now = Instant.now()
        val nominalHours = longitude / 15
        // Hours between the zone's offset and the nominal one, around the date line.
        fun distance(zone: ZoneId) = abs(Math.IEEEremainder(standardOffsetSeconds(zone, now) / 3600.0 - nominalHours, 24.0))
        val best = zones.minOf { distance(it) }
        val closest = zones.filter { distance(it) - best < TIE_TOLERANCE_HOURS }
        val phoneZone = canonicalId(ZoneId.systemDefault())
        return closest.firstOrNull { canonicalId(it) == phoneZone } ?: closest.first()
    }

    /** Great-circle distance between two positions on a spherical Earth. */
    private fun distanceKilometres(latitude1: Double, longitude1: Double, latitude2: Double, longitude2: Double): Double {
        val phi1 = Math.toRadians(latitude1)
        val phi2 = Math.toRadians(latitude2)
        val sinHalfLatitude = sin((phi2 - phi1) / 2)
        val sinHalfLongitude = sin(Math.toRadians(longitude2 - longitude1) / 2)
        val haversine = sinHalfLatitude * sinHalfLatitude + cos(phi1) * cos(phi2) * sinHalfLongitude * sinHalfLongitude
        return 2 * EARTH_RADIUS_KILOMETRES * asin(min(1.0, sqrt(haversine)))
    }

    /** The identifier ICU uses for the zone [id] names (it knows some zones under older names than the tz database). */
    private fun canonicalId(id: String): String = IcuTimeZone.getCanonicalID(id) ?: id

    private fun canonicalId(zone: ZoneId): String = canonicalId(zone.id)

    private fun standardOffsetSeconds(zone: ZoneId, at: Instant): Int = zone.rules.getStandardOffset(at).totalSeconds

    private fun zoneOrNull(id: String): ZoneId? =
        try {
            ZoneId.of(id)
        } catch (_: DateTimeException) {
            null // ICU knows a zone this Android's java.time data does not
        }
}

/** A city that stands for a time zone: [zone] is its tz database identifier. */
private class ReferencePoint(val zone: String, val latitude: Double, val longitude: Double)

/**
 * The reference cities of the time zones of the countries that have several, one per line: ISO 3166 country, tz
 * database zone, latitude and longitude in degrees. Most lines are the principal cities of the tz database's zone.tab
 * (release 2026c, public domain). The rest, at the end of each country, are large cities that lie far from the
 * principal city of their zone: the United States' Central, Mountain and Pacific zones, for example, are sampled by
 * zone.tab only in their north and east, so Dallas would otherwise be taken for a Mountain city.
 */
private const val ZONE_REFERENCE_POINTS = """
AR America/Argentina/Buenos_Aires -34.60 -58.45
AR America/Argentina/Cordoba -31.40 -64.18
AR America/Argentina/Salta -24.78 -65.42
AR America/Argentina/Jujuy -24.18 -65.30
AR America/Argentina/Tucuman -26.82 -65.22
AR America/Argentina/Catamarca -28.47 -65.78
AR America/Argentina/La_Rioja -29.43 -66.85
AR America/Argentina/San_Juan -31.53 -68.52
AR America/Argentina/Mendoza -32.88 -68.82
AR America/Argentina/San_Luis -33.32 -66.35
AR America/Argentina/Rio_Gallegos -51.63 -69.22
AR America/Argentina/Ushuaia -54.80 -68.30
AU Australia/Lord_Howe -31.55 159.08
AU Antarctica/Macquarie -54.50 158.95
AU Australia/Hobart -42.88 147.32
AU Australia/Melbourne -37.82 144.97
AU Australia/Sydney -33.87 151.22
AU Australia/Broken_Hill -31.95 141.45
AU Australia/Brisbane -27.47 153.03
AU Australia/Lindeman -20.27 149.00
AU Australia/Adelaide -34.92 138.58
AU Australia/Darwin -12.47 130.83
AU Australia/Perth -31.95 115.85
AU Australia/Eucla -31.72 128.87
AU Australia/Darwin -23.70 133.88
AU Australia/Sydney -35.28 149.13
AU Australia/Perth -30.75 121.47
BR America/Noronha -3.85 -32.42
BR America/Belem -1.45 -48.48
BR America/Fortaleza -3.72 -38.50
BR America/Recife -8.05 -34.90
BR America/Araguaina -7.20 -48.20
BR America/Maceio -9.67 -35.72
BR America/Bahia -12.98 -38.52
BR America/Sao_Paulo -23.53 -46.62
BR America/Campo_Grande -20.45 -54.62
BR America/Cuiaba -15.58 -56.08
BR America/Santarem -2.43 -54.87
BR America/Porto_Velho -8.77 -63.90
BR America/Boa_Vista 2.82 -60.67
BR America/Manaus -3.13 -60.02
BR America/Eirunepe -6.67 -69.87
BR America/Rio_Branco -9.97 -67.80
CA America/St_Johns 47.57 -52.72
CA America/Halifax 44.65 -63.60
CA America/Glace_Bay 46.20 -59.95
CA America/Moncton 46.10 -64.78
CA America/Goose_Bay 53.33 -60.42
CA America/Blanc-Sablon 51.42 -57.12
CA America/Toronto 43.65 -79.38
CA America/Iqaluit 63.73 -68.47
CA America/Atikokan 48.76 -91.62
CA America/Winnipeg 49.88 -97.15
CA America/Resolute 74.70 -94.83
CA America/Rankin_Inlet 62.82 -92.08
CA America/Regina 50.40 -104.65
CA America/Swift_Current 50.28 -107.83
CA America/Edmonton 53.55 -113.47
CA America/Cambridge_Bay 69.11 -105.05
CA America/Inuvik 68.35 -133.72
CA America/Vancouver 49.27 -123.12
CA America/Creston 49.10 -116.52
CA America/Dawson_Creek 55.77 -120.23
CA America/Fort_Nelson 58.80 -122.70
CA America/Whitehorse 60.72 -135.05
CA America/Dawson 64.07 -139.42
CA America/Edmonton 51.05 -114.07
CA America/Toronto 45.50 -73.57
CA America/Toronto 45.42 -75.70
CA America/Vancouver 48.43 -123.37
CD Africa/Kinshasa -4.30 15.30
CD Africa/Lubumbashi -11.67 27.47
CL America/Santiago -33.45 -70.67
CL America/Coyhaique -45.57 -72.07
CL America/Punta_Arenas -53.15 -70.92
CL Pacific/Easter -27.15 -109.43
CN Asia/Shanghai 31.23 121.47
CN Asia/Urumqi 43.80 87.58
CN Asia/Shanghai 29.65 91.10
CN Asia/Shanghai 30.57 104.07
CY Asia/Nicosia 35.17 33.37
CY Asia/Famagusta 35.12 33.95
DE Europe/Berlin 52.50 13.37
DE Europe/Busingen 47.70 8.68
EC America/Guayaquil -2.17 -79.83
EC Pacific/Galapagos -0.90 -89.60
ES Europe/Madrid 40.40 -3.68
ES Africa/Ceuta 35.88 -5.32
ES Atlantic/Canary 28.10 -15.40
FM Pacific/Chuuk 7.42 151.78
FM Pacific/Pohnpei 6.97 158.22
FM Pacific/Kosrae 5.32 162.98
GL America/Nuuk 64.18 -51.73
GL America/Danmarkshavn 76.77 -18.67
GL America/Scoresbysund 70.48 -21.97
GL America/Thule 76.57 -68.78
ID Asia/Jakarta -6.17 106.80
ID Asia/Pontianak -0.03 109.33
ID Asia/Makassar -5.12 119.40
ID Asia/Jayapura -2.53 140.70
KI Pacific/Tarawa 1.42 173.00
KI Pacific/Kanton -2.78 -171.72
KI Pacific/Kiritimati 1.87 -157.33
KZ Asia/Almaty 43.25 76.95
KZ Asia/Qyzylorda 44.80 65.47
KZ Asia/Qostanay 53.20 63.62
KZ Asia/Aqtobe 50.28 57.17
KZ Asia/Aqtau 44.52 50.27
KZ Asia/Atyrau 47.12 51.93
KZ Asia/Oral 51.22 51.35
MH Pacific/Majuro 7.15 171.20
MH Pacific/Kwajalein 9.08 167.33
MN Asia/Ulaanbaatar 47.92 106.88
MN Asia/Hovd 48.02 91.65
MX America/Mexico_City 19.40 -99.15
MX America/Cancun 21.08 -86.77
MX America/Merida 20.97 -89.62
MX America/Monterrey 25.67 -100.32
MX America/Matamoros 25.83 -97.50
MX America/Chihuahua 28.63 -106.08
MX America/Ciudad_Juarez 31.73 -106.48
MX America/Ojinaga 29.57 -104.42
MX America/Mazatlan 23.22 -106.42
MX America/Bahia_Banderas 20.80 -105.25
MX America/Hermosillo 29.07 -110.97
MX America/Tijuana 32.53 -117.02
MY Asia/Kuala_Lumpur 3.17 101.70
MY Asia/Kuching 1.55 110.33
NZ Pacific/Auckland -36.87 174.77
NZ Pacific/Chatham -43.95 -176.55
PF Pacific/Tahiti -17.53 -149.57
PF Pacific/Marquesas -9.00 -139.50
PF Pacific/Gambier -23.13 -134.95
PG Pacific/Port_Moresby -9.50 147.17
PG Pacific/Bougainville -6.22 155.57
PS Asia/Gaza 31.50 34.47
PS Asia/Hebron 31.53 35.09
PT Europe/Lisbon 38.72 -9.13
PT Atlantic/Madeira 32.63 -16.90
PT Atlantic/Azores 37.73 -25.67
RU Europe/Kaliningrad 54.72 20.50
RU Europe/Moscow 55.76 37.62
RU Europe/Kirov 58.60 49.65
RU Europe/Volgograd 48.73 44.42
RU Europe/Astrakhan 46.35 48.05
RU Europe/Saratov 51.57 46.03
RU Europe/Ulyanovsk 54.33 48.40
RU Europe/Samara 53.20 50.15
RU Asia/Yekaterinburg 56.85 60.60
RU Asia/Omsk 55.00 73.40
RU Asia/Novosibirsk 55.03 82.92
RU Asia/Barnaul 53.37 83.75
RU Asia/Tomsk 56.50 84.97
RU Asia/Novokuznetsk 53.75 87.12
RU Asia/Krasnoyarsk 56.02 92.83
RU Asia/Irkutsk 52.27 104.33
RU Asia/Chita 52.05 113.47
RU Asia/Yakutsk 62.00 129.67
RU Asia/Khandyga 62.66 135.55
RU Asia/Vladivostok 43.17 131.93
RU Asia/Ust-Nera 64.56 143.23
RU Asia/Magadan 59.57 150.80
RU Asia/Sakhalin 46.97 142.70
RU Asia/Srednekolymsk 67.47 153.72
RU Asia/Kamchatka 53.02 158.65
RU Asia/Anadyr 64.75 177.48
RU Europe/Moscow 55.80 49.10
RU Europe/Moscow 59.93 30.34
UA Europe/Simferopol 44.95 34.10
UA Europe/Kyiv 50.43 30.52
UM Pacific/Midway 28.22 -177.37
UM Pacific/Wake 19.28 166.62
US America/New_York 40.71 -74.01
US America/Detroit 42.33 -83.05
US America/Kentucky/Louisville 38.25 -85.76
US America/Kentucky/Monticello 36.83 -84.85
US America/Indiana/Indianapolis 39.77 -86.16
US America/Indiana/Vincennes 38.68 -87.53
US America/Indiana/Winamac 41.05 -86.60
US America/Indiana/Marengo 38.38 -86.34
US America/Indiana/Petersburg 38.49 -87.28
US America/Indiana/Vevay 38.75 -85.07
US America/Chicago 41.85 -87.65
US America/Indiana/Tell_City 37.95 -86.76
US America/Indiana/Knox 41.30 -86.62
US America/Menominee 45.11 -87.61
US America/North_Dakota/Center 47.12 -101.30
US America/North_Dakota/New_Salem 46.84 -101.41
US America/North_Dakota/Beulah 47.26 -101.78
US America/Denver 39.74 -104.98
US America/Boise 43.61 -116.20
US America/Phoenix 33.45 -112.07
US America/Los_Angeles 34.05 -118.24
US America/Anchorage 61.22 -149.90
US America/Juneau 58.30 -134.42
US America/Sitka 57.18 -135.30
US America/Metlakatla 55.13 -131.58
US America/Yakutat 59.55 -139.73
US America/Nome 64.50 -165.41
US America/Adak 51.88 -176.66
US Pacific/Honolulu 21.31 -157.86
US America/New_York 39.95 -75.17
US America/New_York 33.75 -84.39
US America/New_York 25.76 -80.19
US America/New_York 38.91 -77.04
US America/New_York 35.23 -80.84
US America/Chicago 32.78 -96.80
US America/Chicago 29.76 -95.37
US America/Chicago 29.42 -98.49
US America/Chicago 30.27 -97.74
US America/Chicago 35.47 -97.52
US America/Chicago 39.10 -94.58
US America/Chicago 44.98 -93.27
US America/Chicago 29.95 -90.07
US America/Chicago 35.15 -90.05
US America/Chicago 36.16 -86.78
US America/Chicago 33.58 -101.86
US America/Denver 40.76 -111.89
US America/Denver 35.08 -106.65
US America/Denver 31.76 -106.49
US America/Denver 45.78 -108.50
US America/Denver 44.08 -103.23
US America/Phoenix 32.22 -110.97
US America/Phoenix 35.20 -111.65
US America/Los_Angeles 37.77 -122.42
US America/Los_Angeles 47.61 -122.33
US America/Los_Angeles 45.52 -122.68
US America/Los_Angeles 36.17 -115.14
US America/Los_Angeles 32.72 -117.16
US America/Los_Angeles 39.53 -119.81
UZ Asia/Samarkand 39.67 66.80
UZ Asia/Tashkent 41.33 69.30
"""
