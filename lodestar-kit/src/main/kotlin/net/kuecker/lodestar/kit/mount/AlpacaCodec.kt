package net.kuecker.lodestar.kit.mount

import net.kuecker.lodestar.kit.foundation.roundedHalfAwayFromZero
import java.time.Instant
import kotlin.math.abs
import kotlin.math.floor

/** The HTTP method of an Alpaca request: `GET` reads (parameters in the query), `PUT` writes (parameters in the body). */
enum class AlpacaMethod(
    /** The HTTP method name. */
    val rawValue: String,
) {
    /** Parameters travel in the URL query string. */
    GET("GET"),

    /** Parameters travel in an `application/x-www-form-urlencoded` body. */
    PUT("PUT"),
}

/** One request parameter: a name and its text value (encoded when the request is turned into a URL or body). */
data class AlpacaField(val name: String, val value: String)

/**
 * An Alpaca request as a value. The app's HTTP client sends `method` to `http://host:port` + [target] with [body] and
 * [contentType] (for `PUT`) and hands the status code and body text back as an [AlpacaHTTPResponse].
 */
data class AlpacaHTTPRequest(
    /** `GET` or `PUT`. */
    val method: AlpacaMethod,
    /** The path, for example `/api/v1/telescope/0/rightascension`. */
    val path: String,
    /** Query parameters (`GET`), including `ClientID` and `ClientTransactionID`. */
    val query: List<AlpacaField>,
    /** Form parameters (`PUT`), including `ClientID` and `ClientTransactionID`. */
    val form: List<AlpacaField>,
    /** The `ClientTransactionID` sent, which the reply must echo. */
    val clientTransactionID: Int,
) {
    /** The request target: the path, followed by `?` and the encoded query when there is one. */
    val target: String
        get() = if (query.isEmpty()) path else "$path?${AlpacaCodec.encodeFields(query)}"

    /** The encoded form body of a `PUT`; empty for a `GET`. */
    val body: String
        get() = AlpacaCodec.encodeFields(form)

    /** The `Content-Type` header of a `PUT`; null for a `GET`. */
    val contentType: String?
        get() = if (method == AlpacaMethod.PUT) "application/x-www-form-urlencoded" else null
}

/** The status code and body text of an HTTP reply. */
data class AlpacaHTTPResponse(
    /** The HTTP status code. */
    val statusCode: Int,
    /** The body decoded as UTF-8. */
    val body: String,
)

/** The directions of `pulseguide`. */
enum class AlpacaGuideDirection(
    /** The value sent. */
    val code: Int,
) {
    /** Towards increasing declination. */
    NORTH(0),

    /** Towards decreasing declination. */
    SOUTH(1),

    /** Towards increasing right ascension. */
    EAST(2),

    /** Towards decreasing right ascension. */
    WEST(3),
}

/** The mechanical axes of `moveaxis`. */
enum class AlpacaAxis(
    /** The value sent. */
    val code: Int,
) {
    /** Right ascension or azimuth. */
    PRIMARY(0),

    /** Declination or altitude. */
    SECONDARY(1),

    /** Rotator axis of some mounts. */
    TERTIARY(2),
}

/** The `Value` of a successful reply and the transaction numbers it carried. */
data class AlpacaReply(
    /** The `ClientTransactionID` the device echoed, if it did. */
    val clientTransactionID: Long?,
    /** The `ServerTransactionID` of the device, if it sent one. */
    val serverTransactionID: Long?,
    /** The `Value` member, absent for methods that return nothing. */
    val value: MountJSON?,
) {
    /** The value as a boolean. */
    @Throws(MountError::class)
    fun booleanValue(): Boolean = (value as? MountJSON.Bool)?.value ?: throw wrongType("a boolean")

    /** The value as a finite number. */
    @Throws(MountError::class)
    fun doubleValue(): Double = (value as? MountJSON.Number)?.value ?: throw wrongType("a number")

    /** The value as an integer. */
    @Throws(MountError::class)
    fun integerValue(): Int = AlpacaCodec.integer(value) ?: throw wrongType("an integer")

    /** The value as text. */
    @Throws(MountError::class)
    fun textValue(): String = (value as? MountJSON.Text)?.value ?: throw wrongType("text")

    /** The value as a list of integers. */
    @Throws(MountError::class)
    fun integerList(): List<Int> {
        val items = (value as? MountJSON.Array)?.items ?: throw wrongType("a list")
        if (items.size > 64) throw wrongType("a shorter list")
        return items.map { AlpacaCodec.integer(it) ?: throw wrongType("a list of integers") }
    }

    private fun wrongType(expected: String): MountError = MountError.MalformedReply("the value is not $expected")
}

/** A device listed by the management API. */
data class AlpacaDevice(
    /** A short display name. */
    val deviceName: String,
    /** The ASCOM device type, for example `Telescope`. */
    val deviceType: String,
    /** The number to use in device URLs. */
    val deviceNumber: Int,
    /** A unique identifier of the device. */
    val uniqueID: String,
) {
    /** True for a telescope (mount). */
    val isTelescope: Boolean get() = MountText.asciiLowercased(deviceType) == "telescope"
}

/** The server description of the management API. */
data class AlpacaServerDescription(
    /** The server's name. */
    val serverName: String,
    /** The manufacturer. */
    val manufacturer: String,
    /** The server's version. */
    val manufacturerVersion: String,
    /** Where the server is. */
    val location: String,
)

/** Where an Alpaca server listens. */
data class AlpacaEndpoint(
    /** The host name or IP address (an IPv6 address without brackets). */
    val host: String,
    /** The TCP port. */
    val port: Int,
) {
    /** `http://host:port`, with brackets around an IPv6 address. */
    val baseURL: String get() = if (host.contains(':')) "http://[$host]:$port" else "http://$host:$port"

    companion object {
        /** The endpoint, or null if the host is empty, too long, contains blanks or slashes, or the port is not 1 to 65535. */
        fun validated(host: String, port: Int): AlpacaEndpoint? {
            if (host.isEmpty() || host.length > 253 || port !in 1..65_535) return null
            for (ch in host) if (ch.code <= 0x20 || ch == '/' || ch == '?' || ch == '#' || ch == '@' || ch == '[' || ch == ']') return null
            return AlpacaEndpoint(host, port)
        }
    }
}

/**
 * Alpaca encodings: parameter formatting, URL encoding, error numbers and the discovery datagram. Sources: ASCOM Alpaca
 * Device API v1 and Management API v1 (OpenAPI definitions, MIT licence, https://ascom-standards.org/api/), the
 * `ASCOM.Common.Alpaca.AlpacaErrors` enumeration and the Alpaca discovery reference implementations (ASCOM alpyca).
 */
object AlpacaCodec {
    /** Reply texts longer than this are cut when they are put into an error. */
    const val maximumMessageLength: Int = 160

    /** Percent-encodes [text] as UTF-8 (unreserved characters `A–Z a–z 0–9 - . _ ~` stay). */
    fun percentEncode(text: String): String {
        val result = StringBuilder()
        for (byte in text.toByteArray(Charsets.UTF_8)) {
            val code = byte.toInt() and 0xFF
            val ch = code.toChar()
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '-' || ch == '.' || ch == '_' || ch == '~') {
                result.append(ch)
            } else {
                result.append('%')
                result.append("0123456789ABCDEF"[code shr 4])
                result.append("0123456789ABCDEF"[code and 15])
            }
        }
        return result.toString()
    }

    /** `name=value` pairs joined by `&`, names and values percent-encoded. */
    fun encodeFields(fields: List<AlpacaField>): String =
        fields.joinToString("&") { "${percentEncode(it.name)}=${percentEncode(it.value)}" }

    /** A decimal number with at most 9 fraction digits and no exponent, `.` as the separator (`12.5`, `-0.25`, `3.0`). */
    @Throws(MountError::class)
    fun formatNumber(value: Double): String {
        if (!value.isFinite() || abs(value) > 1.0e9) throw MountError.InvalidInput("the number is out of range")
        val scaled = (abs(value) * 1.0e9).roundedHalfAwayFromZero().toLong()
        val whole = scaled / 1_000_000_000L
        val digits = (scaled % 1_000_000_000L).toString().padStart(9, '0').trimEnd('0')
        val sign = if (value < 0 && scaled > 0) "-" else ""
        return "$sign$whole.${digits.ifEmpty { "0" }}"
    }

    /** `True` or `False`. */
    fun formatBoolean(value: Boolean): String = if (value) "True" else "False"

    /** The ISO 8601 UTC form `yyyy-MM-ddTHH:mm:ss.fffZ` that `UTCDate` takes, or null outside the years 1970 to 9999. */
    fun formatUTCDate(date: Instant): String? {
        val f = MountCalendar.fields(date) ?: return null
        return "${f.year.toString().padStart(4, '0')}-${MountCalendar.twoDigits(f.month)}-${MountCalendar.twoDigits(f.day)}T" +
            "${MountCalendar.twoDigits(f.hour)}:${MountCalendar.twoDigits(f.minute)}:${MountCalendar.twoDigits(f.second)}." +
            "${MountCalendar.threeDigits(f.millisecond)}Z"
    }

    /** The JSON number [value] as an `Int` if it is a whole number in range. */
    internal fun integer(value: MountJSON?): Int? {
        val number = (value as? MountJSON.Number)?.value ?: return null
        if (number != floor(number) || abs(number) > Int.MAX_VALUE.toDouble()) return null
        return number.toInt()
    }

    /**
     * The typed error for a non-zero `ErrorNumber`. The reserved numbers of the Alpaca standard (0x400…0x40E, 0x4FF)
     * map to specific cases; driver-specific numbers (0x500…0xFFF) and `InvalidOperation` are classified by the words
     * of the message (horizon, limit, park, align, track, slewing) so the user gets an explanation.
     */
    fun error(number: Int, message: String): MountError {
        val text = MountText.sanitized(message, maximumMessageLength)
        return when (number) {
            1024, 1036 -> MountError.NotSupported(MountFeature.OTHER)
            1025, 1026 -> MountError.Rejected(text)
            1031 -> MountError.NotConnected
            1032 -> MountError.Parked
            1033 -> MountError.Rejected(if (text.isEmpty()) "the mount is slaved" else text)
            else -> classify(number, text)
        }
    }

    private fun classify(number: Int, text: String): MountError {
        val lowered = MountText.asciiLowercased(text)
        return when {
            lowered.contains("park") -> MountError.Parked
            lowered.contains("align") -> MountError.NotAligned
            lowered.contains("horizon") || lowered.contains("below") || lowered.contains("altitude") ->
                MountError.MountHorizonLimit
            lowered.contains("limit") -> MountError.OutsideMountLimits
            lowered.contains("tracking") -> MountError.NotTracking
            lowered.contains("slewing") || lowered.contains("in progress") || lowered.contains("moving") ||
                lowered.contains("busy") -> MountError.Busy
            else -> MountError.Remote(number, text)
        }
    }
}

/**
 * Builds the requests of the Alpaca Telescope and Management APIs. Every request carries the `ClientID` and an
 * increasing `ClientTransactionID` (1, 2, 3 … wrapping to 1 after 2 147 483 647), which
 * [AlpacaResponseParser.parse] checks in the reply.
 */
class AlpacaRequestBuilder(
    /** The device number from the management API (0 for the first telescope). */
    val deviceNumber: Int = 0,
    clientID: Int = 1,
) {
    /** The client's identifier, 1 to 2 147 483 647. */
    val clientID: Int = clientID.coerceIn(1, Int.MAX_VALUE)
    private var lastTransactionID: Int = 0

    internal constructor(deviceNumber: Int, clientID: Int, lastTransactionID: Int) : this(deviceNumber, clientID) {
        this.lastTransactionID = lastTransactionID
    }

    private fun nextTransactionID(): Int {
        lastTransactionID = if (lastTransactionID >= Int.MAX_VALUE) 1 else lastTransactionID + 1
        return lastTransactionID
    }

    private fun get(path: String, parameters: List<AlpacaField>): AlpacaHTTPRequest {
        val id = nextTransactionID()
        val query = parameters + AlpacaField("ClientID", clientID.toString()) + AlpacaField("ClientTransactionID", id.toString())
        return AlpacaHTTPRequest(AlpacaMethod.GET, path, query, emptyList(), id)
    }

    private fun put(path: String, parameters: List<AlpacaField>): AlpacaHTTPRequest {
        val id = nextTransactionID()
        val form = parameters + AlpacaField("ClientID", clientID.toString()) + AlpacaField("ClientTransactionID", id.toString())
        return AlpacaHTTPRequest(AlpacaMethod.PUT, path, emptyList(), form, id)
    }

    private fun telescope(method: String): String = "/api/v1/telescope/$deviceNumber/$method"

    // MARK: Management API

    /** `GET /management/apiversions`. */
    fun apiVersions(): AlpacaHTTPRequest = get("/management/apiversions", emptyList())

    /** `GET /management/v1/description`. */
    fun serverDescription(): AlpacaHTTPRequest = get("/management/v1/description", emptyList())

    /** `GET /management/v1/configureddevices`. */
    fun configuredDevices(): AlpacaHTTPRequest = get("/management/v1/configureddevices", emptyList())

    // MARK: Telescope reads

    /** `GET connected`. */
    fun connected(): AlpacaHTTPRequest = get(telescope("connected"), emptyList())

    /** `GET name`. */
    fun name(): AlpacaHTTPRequest = get(telescope("name"), emptyList())

    /** `GET canslewasync`. */
    fun canSlewAsync(): AlpacaHTTPRequest = get(telescope("canslewasync"), emptyList())

    /** `GET cansync`. */
    fun canSync(): AlpacaHTTPRequest = get(telescope("cansync"), emptyList())

    /** `GET canpulseguide`. */
    fun canPulseGuide(): AlpacaHTTPRequest = get(telescope("canpulseguide"), emptyList())

    /** `GET cansettracking`. */
    fun canSetTracking(): AlpacaHTTPRequest = get(telescope("cansettracking"), emptyList())

    /** `GET canmoveaxis?Axis=…`. */
    fun canMoveAxis(axis: AlpacaAxis): AlpacaHTTPRequest =
        get(telescope("canmoveaxis"), listOf(AlpacaField("Axis", axis.code.toString())))

    /** `GET equatorialsystem`: 1 topocentric (apparent of date), 2 J2000, 0 other, 3 J2050, 4 B1950. */
    fun equatorialSystem(): AlpacaHTTPRequest = get(telescope("equatorialsystem"), emptyList())

    /** `GET rightascension` (hours). */
    fun rightAscension(): AlpacaHTTPRequest = get(telescope("rightascension"), emptyList())

    /** `GET declination` (degrees). */
    fun declination(): AlpacaHTTPRequest = get(telescope("declination"), emptyList())

    /** `GET slewing`. */
    fun slewing(): AlpacaHTTPRequest = get(telescope("slewing"), emptyList())

    /** `GET tracking`. */
    fun tracking(): AlpacaHTTPRequest = get(telescope("tracking"), emptyList())

    /** `GET atpark`. */
    fun atPark(): AlpacaHTTPRequest = get(telescope("atpark"), emptyList())

    /** `GET trackingrates`: the supported drive rates (0 sidereal, 1 lunar, 2 solar, 3 king). */
    fun trackingRates(): AlpacaHTTPRequest = get(telescope("trackingrates"), emptyList())

    // MARK: Telescope writes

    /** `PUT connected`. */
    fun setConnected(connected: Boolean): AlpacaHTTPRequest =
        put(telescope("connected"), listOf(AlpacaField("Connected", AlpacaCodec.formatBoolean(connected))))

    /** `PUT slewtocoordinatesasync`: [rightAscensionHours] in 0 to 24 (exclusive), [declination] in ±90. */
    @Throws(MountError::class)
    fun slewToCoordinatesAsync(rightAscensionHours: Double, declination: Double): AlpacaHTTPRequest =
        put(telescope("slewtocoordinatesasync"), coordinateFields(rightAscensionHours, declination))

    /** `PUT synctocoordinates`. */
    @Throws(MountError::class)
    fun syncToCoordinates(rightAscensionHours: Double, declination: Double): AlpacaHTTPRequest =
        put(telescope("synctocoordinates"), coordinateFields(rightAscensionHours, declination))

    /** `PUT abortslew`. */
    fun abortSlew(): AlpacaHTTPRequest = put(telescope("abortslew"), emptyList())

    /** `PUT tracking`. */
    fun setTracking(tracking: Boolean): AlpacaHTTPRequest =
        put(telescope("tracking"), listOf(AlpacaField("Tracking", AlpacaCodec.formatBoolean(tracking))))

    /** `PUT pulseguide`: move in [direction] at the guide rate for [durationMilliseconds] (1 to 30 000). */
    @Throws(MountError::class)
    fun pulseGuide(direction: AlpacaGuideDirection, durationMilliseconds: Int): AlpacaHTTPRequest {
        if (durationMilliseconds !in 1..30_000) throw MountError.InvalidInput("the pulse must last 1 to 30000 ms")
        return put(
            telescope("pulseguide"),
            listOf(
                AlpacaField("Direction", direction.code.toString()),
                AlpacaField("Duration", durationMilliseconds.toString()),
            ),
        )
    }

    /** `PUT moveaxis`: turn [axis] at [degreesPerSecond] (signed; 0 stops). */
    @Throws(MountError::class)
    fun moveAxis(axis: AlpacaAxis, degreesPerSecond: Double): AlpacaHTTPRequest =
        put(
            telescope("moveaxis"),
            listOf(AlpacaField("Axis", axis.code.toString()), AlpacaField("Rate", AlpacaCodec.formatNumber(degreesPerSecond))),
        )

    /** `PUT sitelatitude`. */
    @Throws(MountError::class)
    fun setSiteLatitude(degrees: Double): AlpacaHTTPRequest =
        put(telescope("sitelatitude"), listOf(AlpacaField("SiteLatitude", AlpacaCodec.formatNumber(degrees))))

    /** `PUT sitelongitude` (east positive). */
    @Throws(MountError::class)
    fun setSiteLongitude(degrees: Double): AlpacaHTTPRequest =
        put(telescope("sitelongitude"), listOf(AlpacaField("SiteLongitude", AlpacaCodec.formatNumber(degrees))))

    /** `PUT siteelevation` (metres). */
    @Throws(MountError::class)
    fun setSiteElevation(metres: Double): AlpacaHTTPRequest =
        put(telescope("siteelevation"), listOf(AlpacaField("SiteElevation", AlpacaCodec.formatNumber(metres))))

    /** `PUT utcdate`. */
    @Throws(MountError::class)
    fun setUTCDate(date: Instant): AlpacaHTTPRequest {
        val text = AlpacaCodec.formatUTCDate(date) ?: throw MountError.InvalidInput("the date is outside 1970 to 9999")
        return put(telescope("utcdate"), listOf(AlpacaField("UTCDate", text)))
    }

    private fun coordinateFields(rightAscensionHours: Double, declination: Double): List<AlpacaField> {
        if (!rightAscensionHours.isFinite() || rightAscensionHours < 0 || rightAscensionHours >= 24) {
            throw MountError.InvalidInput("the right ascension must be 0 to 24 hours")
        }
        if (!declination.isFinite() || abs(declination) > 90) throw MountError.InvalidInput("the declination must be within ±90°")
        val hours = AlpacaCodec.formatNumber(rightAscensionHours)
        val checkedHours = if (hours.startsWith("24.")) "0.0" else hours
        return listOf(
            AlpacaField("RightAscension", checkedHours),
            AlpacaField("Declination", AlpacaCodec.formatNumber(declination)),
        )
    }
}

/** Validates replies of the Alpaca Device and Management APIs. */
object AlpacaResponseParser {
    /**
     * Turns the HTTP reply to [request] into an [AlpacaReply].
     *
     * Fails with [MountError.Remote] for HTTP status codes other than 200 (the body text is the message), with
     * [MountError.MalformedReply] if the body is not a JSON object, a field has the wrong type or the echoed
     * `ClientTransactionID` belongs to another request, and with the error mapped from `ErrorNumber` (see
     * [AlpacaCodec.error]) if the device reports one.
     */
    @Throws(MountError::class)
    fun parse(response: AlpacaHTTPResponse, request: AlpacaHTTPRequest): AlpacaReply {
        if (response.statusCode != 200) {
            throw MountError.Remote(response.statusCode, MountText.sanitized(response.body, AlpacaCodec.maximumMessageLength))
        }
        val root = MountJSON.parse(response.body)
        if (root !is MountJSON.Object) throw MountError.MalformedReply("the reply is not a JSON object")
        val errorField = root.field("ErrorNumber")
        if (errorField != null) {
            val number = AlpacaCodec.integer(errorField) ?: throw MountError.MalformedReply("ErrorNumber is not an integer")
            if (number != 0) {
                val message = (root.field("ErrorMessage") as? MountJSON.Text)?.value ?: ""
                throw AlpacaCodec.error(number, message)
            }
        }
        val echoed = optionalTransactionID(root, "ClientTransactionID")
        if (echoed != null && echoed != 0L && echoed != request.clientTransactionID.toLong()) {
            throw MountError.MalformedReply("the reply belongs to another request")
        }
        return AlpacaReply(echoed, optionalTransactionID(root, "ServerTransactionID"), root.field("Value"))
    }

    /** The versions of the Alpaca API the server supports (`/management/apiversions`). */
    @Throws(MountError::class)
    fun apiVersions(response: AlpacaHTTPResponse, request: AlpacaHTTPRequest): List<Int> =
        parse(response, request).integerList()

    /** The server description (`/management/v1/description`). */
    @Throws(MountError::class)
    fun serverDescription(response: AlpacaHTTPResponse, request: AlpacaHTTPRequest): AlpacaServerDescription {
        val value = parse(response, request).value
        if (value !is MountJSON.Object) throw MountError.MalformedReply("the description is not an object")
        return AlpacaServerDescription(
            serverName = text(value, "ServerName"),
            manufacturer = text(value, "Manufacturer"),
            manufacturerVersion = text(value, "ManufacturerVersion"),
            location = text(value, "Location"),
        )
    }

    /** The devices the server offers (`/management/v1/configureddevices`), at most 64. */
    @Throws(MountError::class)
    fun configuredDevices(response: AlpacaHTTPResponse, request: AlpacaHTTPRequest): List<AlpacaDevice> {
        val items = (parse(response, request).value as? MountJSON.Array)?.items
            ?: throw MountError.MalformedReply("the device list is not an array")
        if (items.size > 64) throw MountError.MalformedReply("too many devices")
        return items.map { item ->
            if (item !is MountJSON.Object) throw MountError.MalformedReply("a device is not an object")
            val number = AlpacaCodec.integer(item.field("DeviceNumber"))
            if (number == null || number < 0) throw MountError.MalformedReply("a device has no valid number")
            AlpacaDevice(
                deviceName = text(item, "DeviceName"),
                deviceType = text(item, "DeviceType"),
                deviceNumber = number,
                uniqueID = text(item, "UniqueID"),
            )
        }
    }

    /**
     * A transaction id member: absent, or a whole number up to the 4 294 967 295 of the uint32 the Alpaca API defines (a
     * server may well count milliseconds or hash into it).
     */
    private fun optionalTransactionID(root: MountJSON, name: String): Long? {
        val field = root.field(name) ?: return null
        val number = (field as? MountJSON.Number)?.value
        if (number == null || number != floor(number) || number < -2_147_483_647.0 || number > 4_294_967_295.0) {
            throw MountError.MalformedReply("$name is not an integer")
        }
        return number.toLong()
    }

    private fun text(root: MountJSON, name: String): String =
        MountText.sanitized((root.field(name) as? MountJSON.Text)?.value ?: "", AlpacaCodec.maximumMessageLength)
}

/** The Alpaca discovery datagram (UDP port 32227) and its reply. */
object AlpacaDiscovery {
    /** The UDP port devices listen on. */
    const val port: Int = 32_227

    /** The IPv6 link-local multicast group for discovery. */
    const val ipv6MulticastGroup: String = "ff12::a1:9aca"

    /** The 16 ASCII bytes `alpacadiscovery1` to broadcast (IPv4 broadcast address of each network, or the IPv6 group). */
    val requestPayload: ByteArray get() = MountText.ascii("alpacadiscovery1")

    /** True if [bytes] are a discovery request (what a responder or a test double checks). */
    fun isRequest(bytes: ByteArray): Boolean = bytes.contentEquals(requestPayload)

    /** The reply a device sends: `{"AlpacaPort": <port>}`. */
    fun responsePayload(alpacaPort: Int): ByteArray = MountText.ascii("{\"AlpacaPort\": $alpacaPort}")

    /** Reads the port of the Alpaca server from a discovery reply (at most 1024 bytes of JSON). */
    @Throws(MountError::class)
    fun parseResponse(bytes: ByteArray): Int {
        if (bytes.isEmpty() || bytes.size > 1024) throw MountError.MalformedReply("discovery reply length")
        val root = MountJSON.parse(String(bytes, Charsets.UTF_8))
        val port = AlpacaCodec.integer(root.field("AlpacaPort"))
        if (port == null || port !in 1..65_535) throw MountError.MalformedReply("discovery reply has no valid AlpacaPort")
        return port
    }
}
