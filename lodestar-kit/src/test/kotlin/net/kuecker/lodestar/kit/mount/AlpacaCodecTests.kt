package net.kuecker.lodestar.kit.mount

import net.kuecker.lodestar.kit.foundation.instantFromTimeIntervalSince1970
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Alpaca codec tests. Sources: ASCOM Alpaca Device API v1 and Management API v1 (OpenAPI definitions,
 * https://ascom-standards.org/api/AlpacaDeviceAPI_v1.yaml sha256 d08d4bfe1a01da637a9084d6a26043d669f665ecee438f6923d078539e4b8276 and
 * AlpacaManagementAPI_v1.yaml sha256 bafa24883572ad0b31efb31aa2e39aa81a945e0f4e449baab376f6c85d18aa84: paths, parameter names, response
 * shapes, error number range), `ASCOM.Common.Alpaca.AlpacaErrors` (0x400 NotImplemented … 0x4FF UnspecifiedError, driver range 0x500 to
 * 0xFFF) and ASCOM alpyca's discovery module (UDP 32227, `alpacadiscovery1`, `{"AlpacaPort": n}`, IPv6 group `ff12::a1:9aca`).
 */
class AlpacaCodecTests {
    private fun ok(value: String?, client: Int, server: Long = 1): AlpacaHTTPResponse {
        val valueText = if (value == null) "" else "\"Value\": $value, "
        return AlpacaHTTPResponse(
            200, "{$valueText\"ClientTransactionID\": $client, \"ServerTransactionID\": $server, \"ErrorNumber\": 0, \"ErrorMessage\": \"\"}",
        )
    }

    private fun failure(number: Int, message: String, client: Int): AlpacaHTTPResponse =
        AlpacaHTTPResponse(200, "{\"ClientTransactionID\": $client, \"ServerTransactionID\": 3, \"ErrorNumber\": $number, \"ErrorMessage\": \"$message\"}")

    // MARK: Requests

    @Test
    fun testReadsGoInTheQueryWithClientIdentity() {
        val builder = AlpacaRequestBuilder(deviceNumber = 0, clientID = 7)
        val first = builder.rightAscension()
        assertEquals(AlpacaMethod.GET, first.method)
        assertEquals("/api/v1/telescope/0/rightascension", first.path)
        assertEquals(listOf(AlpacaField("ClientID", "7"), AlpacaField("ClientTransactionID", "1")), first.query)
        assertEquals("/api/v1/telescope/0/rightascension?ClientID=7&ClientTransactionID=1", first.target)
        assertEquals("", first.body)
        assertNull(first.contentType)
        assertEquals(1, first.clientTransactionID)
        val second = builder.declination()
        assertEquals("/api/v1/telescope/0/declination?ClientID=7&ClientTransactionID=2", second.target)
        assertEquals(2, second.clientTransactionID)
    }

    @Test
    fun testWritesGoInTheFormBody() {
        val builder = AlpacaRequestBuilder(deviceNumber = 2, clientID = 9)
        val request = builder.slewToCoordinatesAsync(12.5, -45.25)
        assertEquals(AlpacaMethod.PUT, request.method)
        assertEquals("/api/v1/telescope/2/slewtocoordinatesasync", request.path)
        assertEquals("/api/v1/telescope/2/slewtocoordinatesasync", request.target)
        assertEquals("RightAscension=12.5&Declination=-45.25&ClientID=9&ClientTransactionID=1", request.body)
        assertEquals("application/x-www-form-urlencoded", request.contentType)
        assertTrue(request.query.isEmpty())
    }

    @Test
    fun testEveryRequestUsesTheDocumentedNames() {
        val b = AlpacaRequestBuilder(0, 1)
        fun check(request: AlpacaHTTPRequest, method: AlpacaMethod, path: String, parameters: String) {
            assertEquals(method, request.method, path)
            assertEquals("/api/v1/telescope/0/$path", request.path)
            val actual = if (method == AlpacaMethod.GET) AlpacaCodec.encodeFields(request.query) else request.body
            val identity = "ClientID=1&ClientTransactionID=${request.clientTransactionID}"
            assertEquals(if (parameters.isEmpty()) identity else "$parameters&$identity", actual, path)
        }
        check(b.connected(), AlpacaMethod.GET, "connected", "")
        check(b.setConnected(true), AlpacaMethod.PUT, "connected", "Connected=True")
        check(b.name(), AlpacaMethod.GET, "name", "")
        check(b.canSlewAsync(), AlpacaMethod.GET, "canslewasync", "")
        check(b.canSync(), AlpacaMethod.GET, "cansync", "")
        check(b.canPulseGuide(), AlpacaMethod.GET, "canpulseguide", "")
        check(b.canSetTracking(), AlpacaMethod.GET, "cansettracking", "")
        check(b.canMoveAxis(AlpacaAxis.SECONDARY), AlpacaMethod.GET, "canmoveaxis", "Axis=1")
        check(b.equatorialSystem(), AlpacaMethod.GET, "equatorialsystem", "")
        check(b.slewing(), AlpacaMethod.GET, "slewing", "")
        check(b.tracking(), AlpacaMethod.GET, "tracking", "")
        check(b.atPark(), AlpacaMethod.GET, "atpark", "")
        check(b.trackingRates(), AlpacaMethod.GET, "trackingrates", "")
        check(b.setTracking(false), AlpacaMethod.PUT, "tracking", "Tracking=False")
        check(b.abortSlew(), AlpacaMethod.PUT, "abortslew", "")
        check(b.syncToCoordinates(1.0, 2.0), AlpacaMethod.PUT, "synctocoordinates", "RightAscension=1.0&Declination=2.0")
        check(b.pulseGuide(AlpacaGuideDirection.EAST, 500), AlpacaMethod.PUT, "pulseguide", "Direction=2&Duration=500")
        check(b.moveAxis(AlpacaAxis.PRIMARY, -0.25), AlpacaMethod.PUT, "moveaxis", "Axis=0&Rate=-0.25")
        check(b.setSiteLatitude(40.015), AlpacaMethod.PUT, "sitelatitude", "SiteLatitude=40.015")
        check(b.setSiteLongitude(-105.27), AlpacaMethod.PUT, "sitelongitude", "SiteLongitude=-105.27")
        check(b.setSiteElevation(1650.0), AlpacaMethod.PUT, "siteelevation", "SiteElevation=1650.0")
        val date = b.setUTCDate(instantFromTimeIntervalSince1970(1_792_296_000.0))
        assertTrue(date.body.startsWith("UTCDate=2026-10-18T04%3A00%3A00.000Z&ClientID=1"), date.body)
        val api = b.apiVersions()
        assertEquals("/management/apiversions?ClientID=1&ClientTransactionID=${api.clientTransactionID}", api.target)
        assertEquals("/management/v1/description", b.serverDescription().path)
        assertEquals("/management/v1/configureddevices", b.configuredDevices().path)
    }

    @Test
    fun testGuideDirectionAndAxisCodesFollowTheSpecification() {
        assertEquals(listOf(0, 1, 2, 3), AlpacaGuideDirection.entries.map { it.code })
        assertEquals(listOf(AlpacaGuideDirection.NORTH, AlpacaGuideDirection.SOUTH, AlpacaGuideDirection.EAST, AlpacaGuideDirection.WEST), AlpacaGuideDirection.entries)
        assertEquals(listOf(0, 1, 2), AlpacaAxis.entries.map { it.code })
    }

    @Test
    fun testTransactionIdentifiersWrapAndClientIdentifiersAreClamped() {
        val builder = AlpacaRequestBuilder(0, 1, lastTransactionID = Int.MAX_VALUE - 1)
        assertEquals(Int.MAX_VALUE, builder.slewing().clientTransactionID)
        assertEquals(1, builder.slewing().clientTransactionID)
        assertEquals(2, builder.slewing().clientTransactionID)
        assertEquals(1, AlpacaRequestBuilder(0, 0).clientID)
        assertEquals(1, AlpacaRequestBuilder(0, -5).clientID)
        assertEquals(65_535, AlpacaRequestBuilder(0, 65_535).clientID)
    }

    @Test
    fun testCoordinatesAreValidated() {
        val builder = AlpacaRequestBuilder()
        for (hours in listOf(-0.1, 24.0, 30.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertFailsWith<MountError.InvalidInput>("$hours") { builder.slewToCoordinatesAsync(hours, 0.0) }
        }
        for (degrees in listOf(90.1, -91.0, Double.NaN)) {
            assertFailsWith<MountError.InvalidInput>("$degrees") { builder.syncToCoordinates(1.0, degrees) }
        }
        // A right ascension that rounds up to 24 h is sent as 0.
        assertTrue(builder.slewToCoordinatesAsync(23.9999999999, 0.0).body.startsWith("RightAscension=0.0&"))
        assertTrue(builder.slewToCoordinatesAsync(0.0, 90.0).body.startsWith("RightAscension=0.0&Declination=90.0&"))
        assertTrue(builder.slewToCoordinatesAsync(23.999999, -90.0).body.startsWith("RightAscension=23.999999&Declination=-90.0&"))
    }

    @Test
    fun testPulseGuideDurationIsLimited() {
        val builder = AlpacaRequestBuilder()
        for (milliseconds in listOf(0, -5, 30_001)) {
            assertFailsWith<MountError.InvalidInput>("$milliseconds") { builder.pulseGuide(AlpacaGuideDirection.NORTH, milliseconds) }
        }
        assertTrue(builder.pulseGuide(AlpacaGuideDirection.NORTH, 1).body.contains("Duration=1&"))
        assertTrue(builder.pulseGuide(AlpacaGuideDirection.WEST, 30_000).body.contains("Direction=3&Duration=30000&"))
    }

    @Test
    fun testPercentEncoding() {
        assertEquals("", AlpacaCodec.percentEncode(""))
        assertEquals("AZaz09-._~", AlpacaCodec.percentEncode("AZaz09-._~"))
        assertEquals("a%20b%26c%3Dd%2F%C3%A9", AlpacaCodec.percentEncode("a b&c=d/é"))
        assertEquals("100%25%2B1", AlpacaCodec.percentEncode("100%+1"))
        assertEquals("%F0%9F%98%80", AlpacaCodec.percentEncode("😀"))
        assertEquals("a%3Db=x%26y&c=1", AlpacaCodec.encodeFields(listOf(AlpacaField("a=b", "x&y"), AlpacaField("c", "1"))))
    }

    @Test
    fun testNumberFormatting() {
        assertEquals("12.5", AlpacaCodec.formatNumber(12.5))
        assertEquals("-0.25", AlpacaCodec.formatNumber(-0.25))
        assertEquals("3.0", AlpacaCodec.formatNumber(3.0))
        assertEquals("0.0", AlpacaCodec.formatNumber(0.0))
        assertEquals("0.0", AlpacaCodec.formatNumber(-0.0))
        assertEquals("0.0", AlpacaCodec.formatNumber(-0.0000000001))
        assertEquals("0.000000001", AlpacaCodec.formatNumber(1e-9))
        assertEquals("0.3", AlpacaCodec.formatNumber(0.1 + 0.2))
        assertEquals("23.999999999", AlpacaCodec.formatNumber(23.999999999))
        assertEquals("359.123456789", AlpacaCodec.formatNumber(359.1234567891))
        assertEquals("1650.0", AlpacaCodec.formatNumber(1650.0))
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 2.0e9)) {
            assertFailsWith<MountError.InvalidInput>("$bad") { AlpacaCodec.formatNumber(bad) }
        }
        assertEquals("True", AlpacaCodec.formatBoolean(true))
        assertEquals("False", AlpacaCodec.formatBoolean(false))
    }

    @Test
    fun testUTCDateFormat() {
        assertEquals("2026-10-18T04:00:00.000Z", AlpacaCodec.formatUTCDate(instantFromTimeIntervalSince1970(1_792_296_000.0)))
        assertEquals("2000-02-29T23:59:59.123Z", AlpacaCodec.formatUTCDate(instantFromTimeIntervalSince1970(951_868_799.123)))
        assertEquals("1970-01-01T00:00:00.000Z", AlpacaCodec.formatUTCDate(instantFromTimeIntervalSince1970(0.0)))
        assertEquals("2100-03-01T00:00:00.000Z", AlpacaCodec.formatUTCDate(instantFromTimeIntervalSince1970(4_107_542_400.0)))
        assertNull(AlpacaCodec.formatUTCDate(instantFromTimeIntervalSince1970(-1.0)))
        assertNull(AlpacaCodec.formatUTCDate(instantFromTimeIntervalSince1970(1.0e12)))
    }

    // MARK: Replies

    @Test
    fun testSuccessfulRepliesGiveTypedValues() {
        val builder = AlpacaRequestBuilder(0, 1)
        val ra = builder.rightAscension()
        val reply = AlpacaResponseParser.parse(ok("12.5", ra.clientTransactionID, 17), ra)
        assertEquals(12.5, reply.doubleValue())
        assertEquals(ra.clientTransactionID.toLong(), reply.clientTransactionID)
        assertEquals(17L, reply.serverTransactionID)
        val slewing = builder.slewing()
        assertEquals(true, AlpacaResponseParser.parse(ok("true", slewing.clientTransactionID), slewing).booleanValue())
        val system = builder.equatorialSystem()
        assertEquals(2, AlpacaResponseParser.parse(ok("2", system.clientTransactionID), system).integerValue())
        val name = builder.name()
        assertEquals("Sim", AlpacaResponseParser.parse(ok("\"Sim\"", name.clientTransactionID), name).textValue())
        val rates = builder.trackingRates()
        assertEquals(listOf(0, 1, 2, 3), AlpacaResponseParser.parse(ok("[0,1,2,3]", rates.clientTransactionID), rates).integerList())
        val abort = builder.abortSlew()
        assertNull(AlpacaResponseParser.parse(ok(null, abort.clientTransactionID), abort).value)
    }

    @Test
    fun testTypedAccessorsRejectWrongTypes() {
        val builder = AlpacaRequestBuilder()
        val request = builder.slewing()
        val text = AlpacaResponseParser.parse(ok("\"yes\"", request.clientTransactionID), request)
        assertFailsWith<MountError.MalformedReply> { text.booleanValue() }
        assertFailsWith<MountError.MalformedReply> { text.doubleValue() }
        assertFailsWith<MountError.MalformedReply> { text.integerValue() }
        assertFailsWith<MountError.MalformedReply> { text.integerList() }
        val fraction = AlpacaResponseParser.parse(ok("1.5", request.clientTransactionID), request)
        assertFailsWith<MountError.MalformedReply> { fraction.integerValue() }
        assertFailsWith<MountError.MalformedReply> { fraction.textValue() }
        val mixed = AlpacaResponseParser.parse(ok("[1,\"a\"]", request.clientTransactionID), request)
        assertFailsWith<MountError.MalformedReply> { mixed.integerList() }
        val huge = AlpacaResponseParser.parse(ok("4294967296", request.clientTransactionID), request)
        assertFailsWith<MountError.MalformedReply> { huge.integerValue() }
        val missing = AlpacaResponseParser.parse(ok(null, request.clientTransactionID), request)
        assertFailsWith<MountError.MalformedReply> { missing.booleanValue() }
        val long = AlpacaResponseParser.parse(ok("[" + (1..65).joinToString(",") + "]", request.clientTransactionID), request)
        assertFailsWith<MountError.MalformedReply> { long.integerList() }
    }

    @Test
    fun testTheEchoedTransactionNumberMustBelongToTheRequest() {
        val builder = AlpacaRequestBuilder()
        val request = builder.slewing()
        assertFailsWith<MountError.MalformedReply> { AlpacaResponseParser.parse(ok("true", request.clientTransactionID + 1), request) }
        // Zero means the device did not receive a valid value; a missing member is tolerated as well.
        assertEquals(0, AlpacaResponseParser.parse(ok("true", 0), request).clientTransactionID)
        val bare = AlpacaResponseParser.parse(AlpacaHTTPResponse(200, "{\"Value\": true}"), request)
        assertEquals(true, bare.booleanValue())
        assertNull(bare.clientTransactionID)
        assertFailsWith<MountError.MalformedReply> {
            AlpacaResponseParser.parse(AlpacaHTTPResponse(200, "{\"Value\": true, \"ClientTransactionID\": \"1\"}"), request)
        }
        // Member names are case insensitive.
        assertEquals(
            true,
            AlpacaResponseParser.parse(AlpacaHTTPResponse(200, "{\"value\": true, \"clienttransactionid\": ${request.clientTransactionID}}"), request).booleanValue(),
        )
    }

    @Test
    fun testHttpErrorsBecomeRemoteErrors() {
        val request = AlpacaRequestBuilder().slewing()
        for (status in listOf(400, 403, 404, 500, 503)) {
            val error = assertFailsWith<MountError.Remote>("$status") {
                AlpacaResponseParser.parse(AlpacaHTTPResponse(status, "Invalid device number\r\n"), request)
            }
            assertEquals(status, error.code)
            assertEquals("Invalid device number", error.detail)
        }
    }

    @Test
    fun testErrorNumbersAreMappedToTypedErrors() {
        val request = AlpacaRequestBuilder().slewing()
        fun error(number: Int, message: String): MountError = assertFailsWith<MountError>("$number $message") {
            AlpacaResponseParser.parse(failure(number, message, request.clientTransactionID), request)
        }
        assertEquals(MountError.NotSupported(MountFeature.OTHER), error(0x400, "not implemented"))
        assertEquals(MountError.Rejected("bad value"), error(0x401, "bad value"))
        assertEquals(MountError.Rejected("not set"), error(0x402, "not set"))
        assertEquals(MountError.NotConnected, error(0x407, "Not connected"))
        assertEquals(MountError.Parked, error(0x408, "Parked"))
        assertEquals(MountError.Rejected("the mount is slaved"), error(0x409, ""))
        assertEquals(MountError.Remote(0x40B, "Operation failed"), error(0x40B, "Operation failed"))
        assertEquals(MountError.MountHorizonLimit, error(0x40B, "Target is below the horizon"))
        assertEquals(MountError.NotTracking, error(0x40B, "Tracking must be on"))
        assertEquals(MountError.NotSupported(MountFeature.OTHER), error(0x40C, "Action not implemented"))
        assertEquals(MountError.Remote(0x40E, "cancelled"), error(0x40E, "cancelled"))
        assertEquals(MountError.Remote(0x4FF, "unknown"), error(0x4FF, "unknown"))
        assertEquals(MountError.NotAligned, error(0x500, "Mount is not aligned"))
        assertEquals(MountError.OutsideMountLimits, error(0x501, "Meridian limit"))
        assertEquals(MountError.Busy, error(0x502, "Mount busy"))
        assertEquals(MountError.Parked, error(0xFFF, "mount is PARKED"))
        assertEquals(MountError.Remote(0xFFF, "Motor stalled"), error(0xFFF, "Motor stalled"))
        assertEquals(MountError.Remote(5, ""), error(5, ""))
    }

    @Test
    fun testErrorMessagesAreCleaned() {
        val request = AlpacaRequestBuilder().slewing()
        val error = assertFailsWith<MountError.Remote> {
            AlpacaResponseParser.parse(failure(0x4FF, "bad\\u0007 thing" + "x".repeat(300), request.clientTransactionID), request)
        }
        assertTrue(error.detail.length <= AlpacaCodec.maximumMessageLength)
        assertTrue(error.detail.startsWith("bad thing"))
    }

    @Test
    fun testTransactionIDsMayUseTheWholeUnsigned32BitRange() {
        // The Alpaca API defines both transaction ids as uint32: a server may count milliseconds or hash into it.
        val request = AlpacaRequestBuilder().slewing()
        val reply = AlpacaResponseParser.parse(ok("true", request.clientTransactionID, 3_000_000_000L), request)
        assertEquals(true, reply.booleanValue())
        assertEquals(3_000_000_000L, reply.serverTransactionID)
        val largest = AlpacaResponseParser.parse(ok("true", request.clientTransactionID, 4_294_967_295L), request)
        assertEquals(4_294_967_295L, largest.serverTransactionID)
        // Beyond uint32, negative-beyond-int32 and fractions are still garbage.
        for (server in listOf("4294967296", "-2147483648", "1.5", "1e10")) {
            val body = "{\"Value\": true, \"ClientTransactionID\": ${request.clientTransactionID}, \"ServerTransactionID\": $server, " +
                "\"ErrorNumber\": 0, \"ErrorMessage\": \"\"}"
            assertFailsWith<MountError.MalformedReply>(server) { AlpacaResponseParser.parse(AlpacaHTTPResponse(200, body), request) }
        }
        // The error number keeps its stricter range.
        val wild = "{\"ClientTransactionID\": ${request.clientTransactionID}, \"ErrorNumber\": 3000000000}"
        assertFailsWith<MountError.MalformedReply>("ErrorNumber") { AlpacaResponseParser.parse(AlpacaHTTPResponse(200, wild), request) }
    }

    @Test
    fun testBrokenBodiesAreRejected() {
        val request = AlpacaRequestBuilder().slewing()
        val bodies = listOf(
            "", "OK", "[1]", "true", "{\"Value\": true", "{\"Value\": }", "{\"ErrorNumber\": \"x\"}", "{\"ErrorNumber\": 1.5}",
            "{\"ServerTransactionID\": 1.5, \"Value\": true}", "<html></html>",
        )
        for (body in bodies) {
            assertFailsWith<MountError.MalformedReply>(body) { AlpacaResponseParser.parse(AlpacaHTTPResponse(200, body), request) }
        }
    }

    // MARK: Discovery

    @Test
    fun testDiscoveryDatagram() {
        assertEquals(32_227, AlpacaDiscovery.port)
        assertEquals("ff12::a1:9aca", AlpacaDiscovery.ipv6MulticastGroup)
        assertContentEquals("alpacadiscovery1".toByteArray(Charsets.US_ASCII), AlpacaDiscovery.requestPayload)
        assertEquals(16, AlpacaDiscovery.requestPayload.size)
        assertTrue(AlpacaDiscovery.isRequest("alpacadiscovery1".toByteArray()))
        assertFalse(AlpacaDiscovery.isRequest("alpacadiscovery2".toByteArray()))
        assertFalse(AlpacaDiscovery.isRequest(ByteArray(0)))
    }

    @Test
    fun testDiscoveryReplies() {
        assertEquals(32_323, AlpacaDiscovery.parseResponse("{\"AlpacaPort\": 32323}".toByteArray()))
        assertEquals(11_111, AlpacaDiscovery.parseResponse("{\"alpacaport\":11111,\"Extra\":[1]}".toByteArray()))
        assertEquals(1, AlpacaDiscovery.parseResponse("{\"AlpacaPort\":1}".toByteArray()))
        assertEquals(65_535, AlpacaDiscovery.parseResponse("{\"AlpacaPort\":65535}".toByteArray()))
        assertEquals(4_321, AlpacaDiscovery.parseResponse(AlpacaDiscovery.responsePayload(4_321)))
        val bad = listOf(
            "", "{}", "{\"AlpacaPort\":0}", "{\"AlpacaPort\":65536}", "{\"AlpacaPort\":-1}", "{\"AlpacaPort\":1.5}",
            "{\"AlpacaPort\":\"11111\"}", "[11111]", "AlpacaPort", "{\"AlpacaPort\":", "alpacadiscovery1",
        )
        for (text in bad) {
            assertFailsWith<MountError.MalformedReply>(text) { AlpacaDiscovery.parseResponse(text.toByteArray()) }
        }
        assertFailsWith<MountError.MalformedReply> { AlpacaDiscovery.parseResponse(ByteArray(1025) { ' '.code.toByte() }) }
    }

    @Test
    fun testEndpoints() {
        assertEquals("http://192.168.4.1:11111", AlpacaEndpoint("192.168.4.1", 11_111).baseURL)
        assertEquals("http://[fe80::1]:32323", AlpacaEndpoint("fe80::1", 32_323).baseURL)
        assertEquals("http://mount.local:80", AlpacaEndpoint.validated("mount.local", 80)?.baseURL)
        for (host in listOf("", "a b", "a/b", "a?b", "a#b", "u@h", "[::1]", "x".repeat(254))) {
            assertNull(AlpacaEndpoint.validated(host, 80), host)
        }
        assertNull(AlpacaEndpoint.validated("host", 0))
        assertNull(AlpacaEndpoint.validated("host", 65_536))
    }

    // MARK: Management API

    @Test
    fun testConfiguredDevices() {
        val builder = AlpacaRequestBuilder()
        val request = builder.configuredDevices()
        val body = "{\"Value\":[{\"DeviceName\":\"Star Adventurer GTi\",\"DeviceType\":\"Telescope\",\"DeviceNumber\":0," +
            "\"UniqueID\":\"3bf1\"},{\"DeviceName\":\"Cam\",\"DeviceType\":\"Camera\",\"DeviceNumber\":1,\"UniqueID\":\"c1\"}," +
            "{\"DeviceName\":\"Second\",\"DeviceType\":\"telescope\",\"DeviceNumber\":4,\"UniqueID\":\"x\"}]," +
            "\"ClientTransactionID\":${request.clientTransactionID},\"ServerTransactionID\":4}"
        val devices = AlpacaResponseParser.configuredDevices(AlpacaHTTPResponse(200, body), request)
        assertEquals(3, devices.size)
        assertEquals(AlpacaDevice("Star Adventurer GTi", "Telescope", 0, "3bf1"), devices[0])
        assertEquals(listOf(0, 4), devices.filter { it.isTelescope }.map { it.deviceNumber })
        assertFalse(devices[1].isTelescope)
    }

    @Test
    fun testConfiguredDevicesAreValidated() {
        val request = AlpacaRequestBuilder().configuredDevices()
        val bodies = listOf(
            "{\"Value\":{}}", "{\"Value\":[1]}", "{\"Value\":[{\"DeviceType\":\"Telescope\"}]}",
            "{\"Value\":[{\"DeviceType\":\"Telescope\",\"DeviceNumber\":-1}]}",
            "{\"Value\":[{\"DeviceType\":\"Telescope\",\"DeviceNumber\":1.5}]}",
            "{\"Value\":[" + (1..65).joinToString(",") { "{\"DeviceType\":\"Telescope\",\"DeviceNumber\":$it}" } + "]}",
        )
        for (body in bodies) {
            assertFailsWith<MountError.MalformedReply>(body) { AlpacaResponseParser.configuredDevices(AlpacaHTTPResponse(200, body), request) }
        }
        val empty = AlpacaResponseParser.configuredDevices(AlpacaHTTPResponse(200, "{\"Value\":[]}"), request)
        assertTrue(empty.isEmpty())
    }

    @Test
    fun testApiVersionsAndDescription() {
        val builder = AlpacaRequestBuilder()
        val versions = builder.apiVersions()
        assertEquals(listOf(1), AlpacaResponseParser.apiVersions(AlpacaHTTPResponse(200, "{\"Value\":[1]}"), versions))
        val description = builder.serverDescription()
        val body = "{\"Value\":{\"ServerName\":\"Omni\",\"Manufacturer\":\"ASCOM\",\"ManufacturerVersion\":\"7.0\",\"Location\":\"Home\"}}"
        assertEquals(
            AlpacaServerDescription("Omni", "ASCOM", "7.0", "Home"),
            AlpacaResponseParser.serverDescription(AlpacaHTTPResponse(200, body), description),
        )
        assertFailsWith<MountError.MalformedReply> { AlpacaResponseParser.serverDescription(AlpacaHTTPResponse(200, "{\"Value\":[]}"), description) }
        assertIs<MountError.Remote>(assertFailsWith<MountError> { AlpacaResponseParser.apiVersions(AlpacaHTTPResponse(403, "Forbidden"), versions) })
    }
}
