package net.kuecker.lodestar.kit.mount

import net.kuecker.lodestar.kit.foundation.EquatorialCoordinate
import net.kuecker.lodestar.kit.foundation.formatFixed

/** A defect the [AlpacaSimulator] can be told to produce in its next reply to a method. */
sealed interface AlpacaFault {
    /** The server answers with the HTTP status [statusCode] and [body] instead. */
    data class Status(val statusCode: Int, val body: String) : AlpacaFault

    /** The reply is a normal 200 whose body is [text] (garbled JSON, wrong types …). */
    data class Body(val text: String) : AlpacaFault

    /** The device reports an error: `ErrorNumber` and `ErrorMessage`. */
    data class Error(val number: Int, val message: String) : AlpacaFault

    /** The reply echoes another `ClientTransactionID`. */
    data object WrongTransactionID : AlpacaFault

    /** The server does not answer within the timeout. */
    data object Timeout : AlpacaFault
}

/**
 * An in-memory Alpaca server with one telescope behind an [AlpacaTransport], for tests and UI previews. It validates
 * method names, parameters and `PUT` vs `GET` like a real device, answers in the documented JSON shapes (including the
 * `ErrorNumber` conventions), serves the management API and drives a [MountSimulatorModel].
 */
class AlpacaSimulator(
    private val clock: ManualMountClock,
    /** The moving mount behind the protocol. */
    val mount: MountSimulatorModel,
    /** The `equatorialsystem` the telescope reports: 1 topocentric (apparent), 2 J2000. */
    val equatorialSystem: Int = 1,
    /** Whether `cansync` is true. */
    val canSync: Boolean = true,
    /** Whether `canpulseguide` is true. */
    val canPulseGuide: Boolean = true,
    /** Whether `canmoveaxis` is true for the primary and secondary axes. */
    val canMoveAxis: Boolean = true,
    /** The telescope's `name`. */
    val deviceName: String = "Simulated Alpaca Mount",
    /** The device number the telescope is served as. */
    val deviceNumber: Int = 0,
    /** Simulated round-trip time added to the clock for every request. */
    val latencySeconds: Double = 0.05,
) : AlpacaTransport {
    /** Whether the client has connected (`PUT connected`). */
    var isConnected: Boolean = false
        private set

    /** If true, `slewtocoordinatesasync` fails with `InvalidOperation` ("Tracking is off") while tracking is off. */
    var slewRequiresTracking: Boolean = true

    /** The site values and clock received by `PUT`, as `name=value` texts in order. */
    val siteValues: List<String> get() = siteLog

    /** The methods received, in order (`rightascension`, `slewtocoordinatesasync` …). */
    val methodLog: List<String> get() = log

    private val writeMethods = setOf(
        "connected", "slewtocoordinatesasync", "synctocoordinates", "abortslew", "tracking", "pulseguide", "moveaxis",
        "sitelatitude", "sitelongitude", "siteelevation", "utcdate",
    )
    private val siteLog = ArrayList<String>()
    private val log = ArrayList<String>()
    private val faults = ArrayList<Pair<String, AlpacaFault>>()
    private var serverTransaction = 0
    private var movingAxes = doubleArrayOf(0.0, 0.0)

    /** Arranges for the next [times] requests of [method] (lower case, e.g. `rightascension`) to suffer [fault]. */
    fun inject(method: String, fault: AlpacaFault, times: Int = 1) {
        for (count in 0 until times) faults.add(method to fault)
    }

    override fun send(request: AlpacaHTTPRequest, timeoutSeconds: Double): AlpacaHTTPResponse {
        clock.advance(latencySeconds)
        val segments = request.path.split('/').filter { it.isNotEmpty() }
        val method = segments.lastOrNull() ?: ""
        log.add(method)
        val index = faults.indexOfFirst { it.first == method }
        val fault = if (index >= 0) faults.removeAt(index).second else null
        if (fault is AlpacaFault.Timeout) {
            clock.advance(timeoutSeconds)
            throw MountError.Timeout("simulated")
        }
        if (fault is AlpacaFault.Status) return AlpacaHTTPResponse(fault.statusCode, fault.body)
        if (fault is AlpacaFault.Body) return AlpacaHTTPResponse(200, fault.text)
        val fields = if (request.method == AlpacaMethod.GET) request.query else request.form
        val echoed = if (fault is AlpacaFault.WrongTransactionID) request.clientTransactionID + 1000 else request.clientTransactionID
        serverTransaction += 1
        if (fault is AlpacaFault.Error) return json(null, echoed, fault.number, fault.message)

        if (segments.firstOrNull() == "management") return management(method, echoed)
        if (segments.size != 5 || segments[0] != "api" || segments[1] != "v1" || segments[2] != "telescope") {
            return AlpacaHTTPResponse(400, "Unknown path")
        }
        if (segments[3] != deviceNumber.toString()) return AlpacaHTTPResponse(400, "Invalid device number")
        return telescope(method, request.method, fields, echoed)
    }

    private fun management(method: String, echoed: Int): AlpacaHTTPResponse = when (method) {
        "apiversions" -> json("[1]", echoed)
        "description" -> json(
            "{\"ServerName\":\"Simulated Alpaca Server\",\"Manufacturer\":\"Lodestar\"," +
                "\"ManufacturerVersion\":\"1.0\",\"Location\":\"Test bench\"}",
            echoed,
        )
        "configureddevices" -> json(
            "[{\"DeviceName\":\"${escape(deviceName)}\",\"DeviceType\":\"Telescope\",\"DeviceNumber\":$deviceNumber," +
                "\"UniqueID\":\"6f0c1c9e-1111-4222-8333-444455556666\"}," +
                "{\"DeviceName\":\"Camera\",\"DeviceType\":\"Camera\",\"DeviceNumber\":0,\"UniqueID\":\"camera-0\"}]",
            echoed,
        )
        else -> AlpacaHTTPResponse(400, "Unknown management method")
    }

    private fun readValue(method: String): String? = when (method) {
        "connected" -> isConnected.toString()
        "name" -> "\"${escape(deviceName)}\""
        "canslewasync", "cansettracking" -> "true"
        "cansync" -> canSync.toString()
        "canpulseguide" -> canPulseGuide.toString()
        "equatorialsystem" -> equatorialSystem.toString()
        "rightascension" -> number(mount.position.rightAscension / 15.0)
        "declination" -> number(mount.position.declination)
        "slewing" -> (mount.isSlewing || movingAxes[0] != 0.0 || movingAxes[1] != 0.0).toString()
        "tracking" -> mount.isTracking.toString()
        "atpark" -> mount.isParked.toString()
        "trackingrates" -> "[0,1,2]"
        else -> null
    }

    private fun telescope(method: String, verb: AlpacaMethod, fields: List<AlpacaField>, echoed: Int): AlpacaHTTPResponse {
        if (method == "canmoveaxis") {
            if (verb != AlpacaMethod.GET) return AlpacaHTTPResponse(400, "Method not allowed")
            val axis = value(fields, "Axis")?.toIntOrNull() ?: return AlpacaHTTPResponse(400, "Axis missing")
            return json((canMoveAxis && axis in 0..1).toString(), echoed)
        }
        val read = readValue(method)
        if (read != null && verb == AlpacaMethod.GET) return json(read, echoed)
        if (!writeMethods.contains(method) || verb != AlpacaMethod.PUT) {
            return if (read != null || writeMethods.contains(method)) {
                AlpacaHTTPResponse(400, "Method not allowed")
            } else {
                AlpacaHTTPResponse(400, "Unknown method $method")
            }
        }
        if (method != "connected" && !isConnected) return json(null, echoed, 1031, "Not connected")
        return write(method, fields, echoed)
    }

    private fun value(fields: List<AlpacaField>, name: String): String? =
        fields.firstOrNull { MountText.asciiLowercased(it.name) == MountText.asciiLowercased(name) }?.value

    private fun flag(fields: List<AlpacaField>, name: String): Boolean? =
        value(fields, name)?.let { MountText.asciiLowercased(it) }?.let { if (it == "true") true else if (it == "false") false else null }

    private fun write(method: String, fields: List<AlpacaField>, echoed: Int): AlpacaHTTPResponse {
        when (method) {
            "connected" -> isConnected = flag(fields, "Connected") ?: return invalid(echoed, "Connected")
            "slewtocoordinatesasync", "synctocoordinates" -> {
                val hours = value(fields, "RightAscension")?.toDoubleOrNull()
                val declination = value(fields, "Declination")?.toDoubleOrNull()
                if (hours == null || declination == null || hours < 0 || hours >= 24 || declination < -90 || declination > 90) {
                    return json(null, echoed, 1025, "Invalid coordinates")
                }
                if (mount.isParked) return json(null, echoed, 1032, "The mount is parked")
                val target = EquatorialCoordinate.fromRightAscensionHours(hours, declination)
                if (method == "synctocoordinates") {
                    if (!canSync) return json(null, echoed, 1024, "SyncToCoordinates is not implemented")
                    mount.sync(target)
                } else {
                    if (slewRequiresTracking && !mount.isTracking) return json(null, echoed, 1035, "Tracking is off")
                    mount.beginSlew(target)
                }
            }
            "abortslew" -> {
                movingAxes = doubleArrayOf(0.0, 0.0)
                mount.stop()
            }
            "tracking" -> mount.isTracking = flag(fields, "Tracking") ?: return invalid(echoed, "Tracking")
            "pulseguide" -> {
                if (!canPulseGuide) return json(null, echoed, 1024, "PulseGuide is not implemented")
                val direction = value(fields, "Direction")?.toIntOrNull()
                val milliseconds = value(fields, "Duration")?.toIntOrNull()
                if (direction == null || direction !in 0..3 || milliseconds == null || milliseconds <= 0) {
                    return invalid(echoed, "Direction")
                }
                val degrees = MountNudgeRate.GUIDE.degreesPerSecond * milliseconds / 1000.0
                when (direction) {
                    0 -> mount.displace(0.0, degrees)
                    1 -> mount.displace(0.0, -degrees)
                    2 -> mount.displace(degrees, 0.0)
                    else -> mount.displace(-degrees, 0.0)
                }
            }
            "moveaxis" -> {
                if (!canMoveAxis) return json(null, echoed, 1024, "MoveAxis is not implemented")
                val axis = value(fields, "Axis")?.toIntOrNull()
                val rate = value(fields, "Rate")?.toDoubleOrNull()
                if (axis == null || axis !in 0..1 || rate == null) return invalid(echoed, "Axis")
                movingAxes[axis] = rate
                mount.setNudgeRates(movingAxes[0], movingAxes[1])
            }
            "sitelatitude" -> siteLog.add("SiteLatitude=${value(fields, "SiteLatitude") ?: ""}")
            "sitelongitude" -> siteLog.add("SiteLongitude=${value(fields, "SiteLongitude") ?: ""}")
            "siteelevation" -> siteLog.add("SiteElevation=${value(fields, "SiteElevation") ?: ""}")
            "utcdate" -> siteLog.add("UTCDate=${value(fields, "UTCDate") ?: ""}")
            else -> return AlpacaHTTPResponse(400, "Unknown method")
        }
        return json(null, echoed)
    }

    private fun invalid(echoed: Int, parameter: String): AlpacaHTTPResponse =
        json(null, echoed, 1025, "Invalid value for $parameter")

    private fun number(value: Double): String = formatFixed(value, 9)

    private fun escape(text: String): String = text.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun json(value: String?, echoed: Int, errorNumber: Int = 0, errorMessage: String = ""): AlpacaHTTPResponse {
        val valueText = if (value == null) "" else "\"Value\":$value,"
        return AlpacaHTTPResponse(
            200,
            "{$valueText\"ClientTransactionID\":$echoed,\"ServerTransactionID\":$serverTransaction," +
                "\"ErrorNumber\":$errorNumber,\"ErrorMessage\":\"${escape(errorMessage)}\"}",
        )
    }
}
