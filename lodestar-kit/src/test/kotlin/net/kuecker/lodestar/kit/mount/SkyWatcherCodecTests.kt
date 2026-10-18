package net.kuecker.lodestar.kit.mount

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Sky-Watcher motor controller codec tests. Source: Sky-Watcher, "Motor Controller Command Set"
 * (`skywatcher_motor_controller_command_set.pdf`, sha256 a4c583bdf617b45ea64b3c742e8b8ab7009a953a2d42c97fdaacd67455d7f73a): command
 * format (`:` letter channel data CR), the data formats ("for HEX number 0x123456 … 5 6 3 4 1 2"), the 0x800000 position offset
 * ("0x000012 should be converted to 0x800012 … 0x801234 reported by the motor controller is 0x001234"), the status bits, the error
 * codes 0 to 8, the T1 preset formulas and the Wi-Fi notes (UDP port 11880, 192.168.4.1). Mount codes: INDI `skywatcherAPI.h`.
 */
class SkyWatcherCodecTests {
    private fun text(bytes: ByteArray): String = MountText.latin1(bytes)

    private fun bytes(value: String): ByteArray = MountText.latin1Bytes(value)

    // MARK: Numbers

    @Test
    fun testNumbersAreLittleEndianHex() {
        assertEquals("563412", SkyWatcherCodec.encode24(0x123456))
        assertEquals("3412", SkyWatcherCodec.encode16(0x1234))
        assertEquals("12", SkyWatcherCodec.encode8(0x12))
        assertEquals("000000", SkyWatcherCodec.encode24(0))
        assertEquals("FFFFFF", SkyWatcherCodec.encode24(0xFFFFFF))
        assertEquals(0x123456, SkyWatcherCodec.decode24("563412"))
        assertEquals(0x1234, SkyWatcherCodec.decode16("3412"))
        assertEquals(0x12, SkyWatcherCodec.decode8("12"))
        assertEquals(0xABCDEF, SkyWatcherCodec.decode24("EFCDAB"))
        for (value in listOf(0, 1, 255, 256, 65_535, 65_536, 0x7FFFFF, 0x800000, 0xFFFFFE, 0xFFFFFF)) {
            assertEquals(value, SkyWatcherCodec.decode24(SkyWatcherCodec.encode24(value)))
        }
    }

    @Test
    fun testNumberCodecsRejectBadValuesAndText() {
        assertFailsWith<MountError.InvalidInput> { SkyWatcherCodec.encode24(-1) }
        assertFailsWith<MountError.InvalidInput> { SkyWatcherCodec.encode24(0x1000000) }
        assertFailsWith<MountError.InvalidInput> { SkyWatcherCodec.encode16(0x10000) }
        assertFailsWith<MountError.InvalidInput> { SkyWatcherCodec.encode8(256) }
        for (bad in listOf("", "56341", "5634122", "56341G", "56341g", "56 412", "5634-2")) {
            assertFailsWith<MountError.MalformedReply>(bad) { SkyWatcherCodec.decode24(bad) }
        }
        assertFailsWith<MountError.MalformedReply> { SkyWatcherCodec.decode16("341") }
        assertFailsWith<MountError.MalformedReply> { SkyWatcherCodec.decode8("1") }
        assertFailsWith<MountError.MalformedReply> { SkyWatcherCodec.decode8("1x") }
    }

    @Test
    fun testPositionsAreOffsetBy0x800000() {
        // "0x000012 should be converted to 0x800012 when preparing the command" → digits 12 00 80.
        assertEquals("120080", SkyWatcherCodec.encodePosition(0x12))
        // "the true position of data 0x801234 reported by the motor controller is 0x001234".
        assertEquals(0x1234, SkyWatcherCodec.decodePosition("341280"))
        assertEquals("000080", SkyWatcherCodec.encodePosition(0))
        assertEquals("FFFF7F", SkyWatcherCodec.encodePosition(-1))
        assertEquals("000000", SkyWatcherCodec.encodePosition(-0x800000))
        assertEquals("FFFFFF", SkyWatcherCodec.encodePosition(0x7FFFFF))
        assertEquals(-1, SkyWatcherCodec.decodePosition("FFFF7F"))
        assertEquals(-0x800000, SkyWatcherCodec.decodePosition("000000"))
        for (counts in listOf(-0x800000, -123_456, -1, 0, 1, 99_999, 0x7FFFFF)) {
            assertEquals(counts, SkyWatcherCodec.decodePosition(SkyWatcherCodec.encodePosition(counts)))
        }
        assertFailsWith<MountError.InvalidInput> { SkyWatcherCodec.encodePosition(0x800000) }
        assertFailsWith<MountError.InvalidInput> { SkyWatcherCodec.encodePosition(-0x800001) }
    }

    // MARK: Requests

    @Test
    fun testRequestsFollowTheCommandFormat() {
        assertContentEquals(byteArrayOf(0x3A, 0x65, 0x31, 0x0D), SkyWatcherCodec.request(SkyWatcherCommand.INQUIRE_VERSION, SkyWatcherAxis.AXIS1))
        assertEquals(":e1\r", text(SkyWatcherCodec.inquire(SkyWatcherCommand.INQUIRE_VERSION, SkyWatcherAxis.AXIS1)))
        assertEquals(":a2\r", text(SkyWatcherCodec.inquire(SkyWatcherCommand.INQUIRE_COUNTS_PER_REVOLUTION, SkyWatcherAxis.AXIS2)))
        assertEquals(":b1\r", text(SkyWatcherCodec.inquire(SkyWatcherCommand.INQUIRE_TIMER_FREQUENCY, SkyWatcherAxis.AXIS1)))
        assertEquals(":j2\r", text(SkyWatcherCodec.inquire(SkyWatcherCommand.INQUIRE_POSITION, SkyWatcherAxis.AXIS2)))
        assertEquals(":f1\r", text(SkyWatcherCodec.inquire(SkyWatcherCommand.INQUIRE_STATUS, SkyWatcherAxis.AXIS1)))
        assertEquals(":g2\r", text(SkyWatcherCodec.inquire(SkyWatcherCommand.INQUIRE_HIGH_SPEED_RATIO, SkyWatcherAxis.AXIS2)))
        assertEquals(":q1010000\r", text(SkyWatcherCodec.inquire(SkyWatcherCommand.EXTENDED_INQUIRE, SkyWatcherAxis.AXIS1)))
        assertEquals(":G101\r", text(SkyWatcherCodec.setMotionMode(SkyWatcherAxis.AXIS1, SkyWatcherMotionMode.GOTO_FAST, SkyWatcherDirection.DECREASING)))
        assertEquals(":G230\r", text(SkyWatcherCodec.setMotionMode(SkyWatcherAxis.AXIS2, SkyWatcherMotionMode.TRACK_FAST, SkyWatcherDirection.INCREASING)))
        assertEquals(":G120\r", text(SkyWatcherCodec.setMotionMode(SkyWatcherAxis.AXIS1, SkyWatcherMotionMode.GOTO_SLOW, SkyWatcherDirection.INCREASING)))
        assertEquals(":G210\r", text(SkyWatcherCodec.setMotionMode(SkyWatcherAxis.AXIS2, SkyWatcherMotionMode.TRACK_SLOW, SkyWatcherDirection.INCREASING)))
        assertEquals(":H2563412\r", text(SkyWatcherCodec.setGotoTargetIncrement(SkyWatcherAxis.AXIS2, 0x123456)))
        assertEquals(":M1800C00\r", text(SkyWatcherCodec.setBrakePointIncrement(SkyWatcherAxis.AXIS1, 3200)))
        assertEquals(":S1FFFF7F\r", text(SkyWatcherCodec.setGotoTarget(SkyWatcherAxis.AXIS1, -1)))
        assertEquals(":I2C73600\r", text(SkyWatcherCodec.setStepPeriod(SkyWatcherAxis.AXIS2, 14_023)))
        assertEquals(":J1\r", text(SkyWatcherCodec.start(SkyWatcherAxis.AXIS1)))
        assertEquals(":K2\r", text(SkyWatcherCodec.stop(SkyWatcherAxis.AXIS2)))
        assertEquals(":L1\r", text(SkyWatcherCodec.stop(SkyWatcherAxis.AXIS1, instant = true)))
        assertEquals(":E1120080\r", text(SkyWatcherCodec.setPosition(SkyWatcherAxis.AXIS1, 0x12)))
        assertEquals(":F2\r", text(SkyWatcherCodec.initializationDone(SkyWatcherAxis.AXIS2)))
    }

    @Test
    fun testRequestsRejectWhatTheControllerWouldRefuse() {
        assertFailsWith<MountError.InvalidInput> { SkyWatcherCodec.inquire(SkyWatcherCommand.START_MOTION, SkyWatcherAxis.AXIS1) }
        assertFailsWith<MountError.InvalidInput> { SkyWatcherCodec.request(SkyWatcherCommand.SET_STEP_PERIOD, SkyWatcherAxis.AXIS1, "1234567") }
        assertFailsWith<MountError.InvalidInput> { SkyWatcherCodec.request(SkyWatcherCommand.SET_STEP_PERIOD, SkyWatcherAxis.AXIS1, "12ab") }
        assertFailsWith<MountError.InvalidInput> { SkyWatcherCodec.setGotoTargetIncrement(SkyWatcherAxis.AXIS1, -1) }
        assertFailsWith<MountError.InvalidInput> { SkyWatcherCodec.setStepPeriod(SkyWatcherAxis.AXIS1, 0x1000000) }
    }

    @Test
    fun testCommandTableIsConsistent() {
        val letters = SkyWatcherCommand.entries.map { it.letter }
        assertEquals(letters.size, letters.toSet().size)
        for (command in SkyWatcherCommand.entries) assertEquals(command, SkyWatcherCommand.fromLetter(command.letter))
        assertNull(SkyWatcherCommand.fromLetter('Q'))
        assertNull(SkyWatcherCommand.fromLetter('z'))
        assertEquals(setOf('a', 'b', 'c', 'h', 'i', 'j', 'k', 'm', 'f', 'g', 'D', 'd', 'e', 's', 'q'),
            SkyWatcherCommand.entries.filter { it.isInquiry }.map { it.letter }.toSet())
        assertEquals(3, SkyWatcherCommand.INQUIRE_STATUS.replyDigits)
        assertEquals(2, SkyWatcherCommand.INQUIRE_HIGH_SPEED_RATIO.replyDigits)
        assertEquals(6, SkyWatcherCommand.INQUIRE_POSITION.replyDigits)
        assertEquals(0, SkyWatcherCommand.START_MOTION.replyDigits)
    }

    // MARK: Replies

    @Test
    fun testRepliesParse() {
        assertEquals(SkyWatcherReply.Success(""), SkyWatcherCodec.parseReply(bytes("=")))
        assertEquals(SkyWatcherReply.Success("563412"), SkyWatcherCodec.parseReply(bytes("=563412")))
        assertEquals(SkyWatcherReply.Success("0322A5"), SkyWatcherCodec.parseReply(bytes("=0322A5")))
        assertEquals(SkyWatcherReply.Failure(2), SkyWatcherCodec.parseReply(bytes("!2")))
        assertEquals(SkyWatcherReply.Failure(8), SkyWatcherCodec.parseReply(bytes("!8")))
        assertEquals(SkyWatcherReply.Failure(10), SkyWatcherCodec.parseReply(bytes("!0A")))
        assertEquals(SkyWatcherReply.Success("1234"), SkyWatcherCodec.parseDatagram(bytes("=1234\r")))
        assertEquals(SkyWatcherReply.Success(""), SkyWatcherCodec.parseDatagram(bytes("=\r")))
        assertEquals(SkyWatcherReply.Failure(4), SkyWatcherCodec.parseDatagram(bytes("!4\r")))
        // Only the first reply of a datagram counts.
        assertEquals(SkyWatcherReply.Success("12"), SkyWatcherCodec.parseDatagram(bytes("=12\r=34\r")))
    }

    @Test
    fun testRepliesTolerateBytesInFrontOfTheMarker() {
        assertEquals(SkyWatcherReply.Success("12"), SkyWatcherCodec.parseReply(bytes("\n=12")))
        assertEquals(SkyWatcherReply.Success("1A2B3C"), SkyWatcherCodec.parseReply(bytes(":j1=1A2B3C")))
        assertEquals(SkyWatcherReply.Failure(1), SkyWatcherCodec.parseReply(bytes(" !1")))
    }

    @Test
    fun testMalformedRepliesAreRejected() {
        val bad = listOf(
            "", "563412", "=56341G", "=5634120", "=ab", "=12 34", "=1-2", "!", "!123", "!G", "!1G", "!!2", "x", ":", "?", "=ß1",
            "=12\u0000", "=1\u0001",
        )
        for (line in bad) {
            assertFailsWith<MountError.MalformedReply>("'$line'") { SkyWatcherCodec.parseReply(bytes(line)) }
        }
        assertFailsWith<MountError.MalformedReply> { SkyWatcherCodec.parseDatagram(bytes("=1234")) }
        assertFailsWith<MountError.MalformedReply> { SkyWatcherCodec.parseDatagram(bytes("")) }
        assertFailsWith<MountError.MalformedReply> { SkyWatcherCodec.parseDatagram(bytes("\r")) }
        assertFailsWith<MountError.MalformedReply> { SkyWatcherCodec.parseDatagram(bytes("=1G34\r")) }
    }

    @Test
    fun testErrorCodesMapToMountErrors() {
        assertEquals(MountError.NotSupported(MountFeature.OTHER), SkyWatcherCodec.error(0))
        assertTrue(SkyWatcherCodec.error(1) is MountError.Rejected)
        assertEquals(MountError.Busy, SkyWatcherCodec.error(2))
        assertTrue(SkyWatcherCodec.error(3) is MountError.Rejected)
        assertTrue((SkyWatcherCodec.error(4) as MountError.Rejected).detail.contains("not initialized"))
        assertEquals(MountError.Standby, SkyWatcherCodec.error(5))
        assertEquals(MountError.Busy, SkyWatcherCodec.error(7))
        assertTrue((SkyWatcherCodec.error(8) as MountError.Rejected).detail.contains("PEC"))
        for (code in listOf(6, 9, 10, 15)) assertEquals(MountError.Remote(code, "undocumented motor controller error"), SkyWatcherCodec.error(code))
        for (error in SkyWatcherErrorCode.entries) assertEquals(error, SkyWatcherErrorCode.fromCode(error.code))
        assertNull(SkyWatcherErrorCode.fromCode(6))
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 7, 8), SkyWatcherErrorCode.entries.map { it.code })
        // Only the errors a damaged request can cause are worth sending the command again for.
        for (code in 0..15) {
            assertEquals(code == 0 || code == 1 || code == 3, SkyWatcherCodec.isPossiblyCausedByDamagedRequest(code), "$code")
        }
        for (error in listOf(0, 1, 2, 3, 4, 5, 7, 8, 6, 12)) assertFalse(SkyWatcherCodec.error(error).isCommunicationFailure)
    }

    @Test
    fun testStatusReplyBits() {
        // B0: 1 = speed (tracking) mode, B1: 1 = counter-clockwise, B2: 1 = fast; second digit B0 running, B1 blocked; third B0 initialized, B1 level switch.
        val running = SkyWatcherCodec.decodeStatus("511")
        assertTrue(running.isSpeedMode && running.isFast && !running.isDecreasing)
        assertTrue(running.isRunning && !running.isBlocked && running.isInitialized && !running.isLevelSwitchOn)
        assertFalse(running.isGoto)
        val goto = SkyWatcherCodec.decodeStatus("211")
        assertFalse(goto.isSpeedMode)
        assertTrue(goto.isDecreasing && goto.isRunning && goto.isGoto && !goto.isFast)
        val idle = SkyWatcherCodec.decodeStatus("100")
        assertTrue(idle.isSpeedMode && !idle.isRunning && !idle.isInitialized && !idle.isGoto)
        val flags = SkyWatcherCodec.decodeStatus("033")
        assertTrue(flags.isRunning && flags.isBlocked && flags.isInitialized && flags.isLevelSwitchOn && !flags.isSpeedMode)
        val hexDigits = "01234567"
        for (first in hexDigits) {
            for (second in "0123") {
                for (third in "0123") {
                    val payload = "$first$second$third"
                    assertEquals(payload, SkyWatcherCodec.encodeStatus(SkyWatcherCodec.decodeStatus(payload)))
                }
            }
        }
        for (bad in listOf("", "11", "1111", "1G1", "1 1")) assertFailsWith<MountError.MalformedReply>(bad) { SkyWatcherCodec.decodeStatus(bad) }
    }

    @Test
    fun testVersionReply() {
        val azGti = SkyWatcherCodec.decodeVersion("0322A5")
        assertEquals(SkyWatcherVersion(3, 0x22, 0xA5), azGti)
        assertEquals("3.22", azGti.description)
        assertEquals("AZ-GTi", azGti.mountName)
        assertEquals(SkyWatcherMountClass.EITHER, azGti.mountClass)
        assertEquals(SkyWatcherMountMode.ALT_AZIMUTH, SkyWatcherMountCatalog.defaultMode(0xA5))
        val heq5 = SkyWatcherCodec.decodeVersion("010601")
        assertEquals("HEQ5", heq5.mountName)
        assertEquals("1.06", heq5.description)
        assertEquals(SkyWatcherMountClass.EQUATORIAL_ONLY, heq5.mountClass)
        assertEquals(SkyWatcherMountMode.EQUATORIAL, SkyWatcherMountCatalog.defaultMode(0x01))
        assertEquals("0322A5", SkyWatcherCodec.encodeVersion(azGti))
        assertEquals("Star Adventurer GTi", SkyWatcherCodec.decodeVersion("03400C").mountName)
        assertEquals("Sky-Watcher motor controller", SkyWatcherMountCatalog.name(0x77))
        assertEquals(SkyWatcherMountClass.EITHER, SkyWatcherMountCatalog.mountClass(0x77))
        val names = mapOf(
            0x00 to "EQ6", 0x01 to "HEQ5", 0x02 to "EQ5", 0x03 to "EQ3", 0x04 to "EQ8", 0x05 to "AZ-EQ6", 0x06 to "AZ-EQ5",
            0x0A to "Star Adventurer", 0x0C to "Star Adventurer GTi", 0x20 to "EQ8-R Pro", 0x22 to "AZ-EQ6 Pro", 0x23 to "EQ6-R Pro",
            0x24 to "EQ6 Pro", 0x25 to "CQ350 Pro", 0x31 to "EQ5 Pro", 0x44 to "Wave 100i", 0x45 to "Wave 150i", 0x80 to "GT",
            0x81 to "MF", 0x82 to "114GT", 0x90 to "Dob", 0xA2 to "AZ-GTe", 0xA5 to "AZ-GTi",
        )
        for ((code, name) in names) assertEquals(name, SkyWatcherMountCatalog.name(code), "0x${code.toString(16)}")
        assertEquals(SkyWatcherMountMode.EQUATORIAL, SkyWatcherMountCatalog.defaultMode(0x0C))
        assertEquals(SkyWatcherMountMode.ALT_AZIMUTH, SkyWatcherMountCatalog.defaultMode(0xA2))
        for (bad in listOf("", "0322A", "0322A55", "0322AG")) assertFailsWith<MountError.MalformedReply>(bad) { SkyWatcherCodec.decodeVersion(bad) }
        assertEquals(32, SkyWatcherCodec.decodeHighSpeedRatio("20"))
        assertFailsWith<MountError.MalformedReply> { SkyWatcherCodec.decodeHighSpeedRatio("00") }
        assertFailsWith<MountError.MalformedReply> { SkyWatcherCodec.decodeHighSpeedRatio("2") }
    }

    // MARK: Speeds

    private val counts = 9_216_000
    private val timer = 1_500_000
    private val ratio = 32

    @Test
    fun testStepPeriodsFollowTheDocumentedFormula() {
        // T1 = TMR_Freq × 360 / speed / CPR (low speed).
        val sidereal = SkyWatcherSky.siderealDegreesPerSecond
        val speed = assertNotNull(SkyWatcherCodec.speed(sidereal, counts, timer, ratio))
        assertEquals(14_024, speed.period)
        assertFalse(speed.isHighSpeed)
        assertEquals(sidereal, SkyWatcherCodec.degreesPerSecond(speed.period, false, counts, timer, ratio), sidereal * 1e-4)
        val slow = assertNotNull(SkyWatcherCodec.speed(0.5, counts, timer, ratio))
        assertEquals(117, slow.period)
        assertFalse(slow.isHighSpeed)
        // Negative speeds give the same period; the direction is the G command's business.
        assertEquals(slow, SkyWatcherCodec.speed(-0.5, counts, timer, ratio))
    }

    @Test
    fun testHighSpeedPeriodsUseTheHighSpeedRatio() {
        // T1 = N × TMR_Freq × 360 / speed / CPR above 128 times the sidereal rate: 3°/s gives 32 × 1.5e6 × 360 / (3 × 9.216e6) = 625.
        val fast = assertNotNull(SkyWatcherCodec.speed(3.0, counts, timer, ratio))
        assertEquals(625, fast.period)
        assertTrue(fast.isHighSpeed)
        assertEquals(3.0, SkyWatcherCodec.degreesPerSecond(625, true, counts, timer, ratio), 1e-12)
        val limit = SkyWatcherCodec.lowSpeedLimitDegreesPerSecond
        assertEquals(128 * SkyWatcherSky.siderealDegreesPerSecond, limit, 1e-15)
        assertFalse(assertNotNull(SkyWatcherCodec.speed(limit * 0.999, counts, timer, ratio)).isHighSpeed)
        val above = assertNotNull(SkyWatcherCodec.speed(limit * 1.001, counts, timer, ratio))
        assertTrue(above.isHighSpeed)
        assertTrue(kotlin.math.abs(above.period - 3_505) <= 5, "${above.period}")
        // The smallest period the engine asks for is 6; a faster request is limited to it.
        val limited = assertNotNull(SkyWatcherCodec.speed(1_000_000.0, counts, timer, ratio))
        assertEquals(SkyWatcherCodec.minimumPeriod, limited.period)
        assertTrue(limited.isHighSpeed)
    }

    @Test
    fun testUnrepresentableSpeedsGiveNoPeriod() {
        assertNull(SkyWatcherCodec.speed(0.0, counts, timer, ratio))
        assertNull(SkyWatcherCodec.speed(Double.NaN, counts, timer, ratio))
        assertNull(SkyWatcherCodec.speed(Double.POSITIVE_INFINITY, counts, timer, ratio))
        assertNull(SkyWatcherCodec.speed(1e-9, counts, timer, ratio))
        assertNull(SkyWatcherCodec.speed(1.0, 0, timer, ratio))
        assertNull(SkyWatcherCodec.speed(1.0, counts, 0, ratio))
        assertNull(SkyWatcherCodec.speed(1.0, counts, timer, 0))
        assertEquals(0.0, SkyWatcherCodec.degreesPerSecond(0, false, counts, timer, ratio), 0.0)
        // The slowest speed that still fits in 24 bits.
        val slowest = 1_500_000.0 * 360.0 / (16_777_215.0 * 9_216_000.0)
        assertEquals(0xFFFFFF, assertNotNull(SkyWatcherCodec.speed(slowest, counts, timer, ratio)).period)
        assertNull(SkyWatcherCodec.speed(slowest * 0.9999, counts, timer, ratio))
    }

    // MARK: Discovery

    @Test
    fun testDiscoveryProbe() {
        assertEquals(11880, SkyWatcherDiscovery.port)
        assertEquals("192.168.4.1", SkyWatcherDiscovery.accessPointAddress)
        assertEquals(":e1\r", text(SkyWatcherDiscovery.probePayload))
        assertTrue(SkyWatcherDiscovery.isProbe(bytes(":e1\r")))
        assertFalse(SkyWatcherDiscovery.isProbe(bytes(":e2\r")))
        assertEquals(SkyWatcherVersion(3, 0x22, 0xA5), SkyWatcherDiscovery.parseProbeReply(bytes("=0322A5\r")))
        assertFailsWith<MountError.MalformedReply> { SkyWatcherDiscovery.parseProbeReply(bytes("!0\r")) }
        assertFailsWith<MountError.MalformedReply> { SkyWatcherDiscovery.parseProbeReply(bytes("=0322A5")) }
        assertFailsWith<MountError.MalformedReply> { SkyWatcherDiscovery.parseProbeReply(bytes("=12\r")) }
        assertFailsWith<MountError.MalformedReply> { SkyWatcherDiscovery.parseProbeReply(bytes("hello\r")) }
    }

    @Test
    fun testDiscoveryAddresses() {
        assertEquals("192.168.1.255", SkyWatcherDiscovery.broadcastAddress("192.168.1.23", 24))
        assertEquals("10.0.255.255", SkyWatcherDiscovery.broadcastAddress("10.0.5.77", 16))
        assertEquals("172.16.15.255", SkyWatcherDiscovery.broadcastAddress("172.16.4.9", 20))
        assertEquals("192.168.4.3", SkyWatcherDiscovery.broadcastAddress("192.168.4.2", 30))
        for (bad in listOf("300.1.1.1", "1.2.3", "1.2.3.4.5", "a.b.c.d", "1..2.3", "", "1.2.3.-4", "1.2.3.1234")) {
            assertNull(SkyWatcherDiscovery.broadcastAddress(bad, 24), bad)
        }
        assertNull(SkyWatcherDiscovery.broadcastAddress("10.0.0.1", 31))
        assertNull(SkyWatcherDiscovery.broadcastAddress("10.0.0.1", 7))
        assertEquals(listOf("192.168.4.1"), SkyWatcherDiscovery.probeTargets("192.168.4.2", 24))
        assertEquals(listOf("192.168.1.255", "192.168.4.1"), SkyWatcherDiscovery.probeTargets("192.168.1.23", 24))
        assertEquals(listOf("192.168.4.1"), SkyWatcherDiscovery.probeTargets("10.0.0.5", 33))
        assertEquals(emptyList(), SkyWatcherDiscovery.probeTargets("nonsense", 24))
    }

    // MARK: Configuration

    @Test
    fun testConfigurationAndLimitsAreClamped() {
        val location = SkyWatcherTestSupport.boulderLocation
        val clamped = SkyWatcherConfiguration(
            location = location, exchangeTimeoutSeconds = 99.0, attempts = 0, trackingUpdateIntervalSeconds = 0.0,
            assumedSlewDegreesPerSecond = 1e9, refinementToleranceDegrees = -1.0, maximumAlignmentResidualDegrees = Double.NaN,
        )
        assertEquals(5.0, clamped.exchangeTimeoutSeconds, 0.0)
        assertEquals(1, clamped.attempts)
        assertEquals(0.25, clamped.trackingUpdateIntervalSeconds, 0.0)
        assertEquals(10.0, clamped.assumedSlewDegreesPerSecond, 0.0)
        assertEquals(0.002, clamped.refinementToleranceDegrees, 0.0)
        assertEquals(1.0, clamped.maximumAlignmentResidualDegrees, 0.0)
        assertEquals(6, SkyWatcherConfiguration(location, attempts = 99).attempts)
        assertEquals(0.2, SkyWatcherConfiguration(location, exchangeTimeoutSeconds = 0.0).exchangeTimeoutSeconds, 0.0)
        val limits = SkyWatcherLimits(
            minimumAltitudeDegrees = -90.0, maximumAltitudeDegrees = 10.0, azimuthMinimumDegrees = 5.0, azimuthMaximumDegrees = -5.0,
            meridianOverlapDegrees = 99.0, pathMarginDegrees = Double.NaN, maximumAxisRateDegreesPerSecond = 0.0,
        )
        assertEquals(-20.0, limits.minimumAltitudeDegrees, 0.0)
        assertEquals(60.0, limits.maximumAltitudeDegrees, 0.0)
        assertEquals(0.0, limits.azimuthMinimumDegrees, 0.0)
        assertEquals(0.0, limits.azimuthMaximumDegrees, 0.0)
        assertEquals(30.0, limits.meridianOverlapDegrees, 0.0)
        assertEquals(5.0, limits.pathMarginDegrees, 0.0)
        assertEquals(0.1, limits.maximumAxisRateDegreesPerSecond, 0.0)
        assertEquals(SkyWatcherLimits.standard, SkyWatcherLimits())
        assertEquals(MountProtocol.SKYWATCHER, MountProtocol.fromRawValue("skywatcher"))
        for (mode in SkyWatcherMountMode.entries) assertEquals(mode, SkyWatcherMountMode.fromRawValue(mode.rawValue))
        for (side in SkyWatcherPierSide.entries) assertEquals(side, SkyWatcherPierSide.fromRawValue(side.rawValue))
        for (axis in SkyWatcherAxis.entries) assertEquals(axis, SkyWatcherAxis.fromRawValue(axis.rawValue))
        for (strategy in SkyWatcherTrackingStrategy.entries) assertEquals(strategy, SkyWatcherTrackingStrategy.fromRawValue(strategy.rawValue))
        for (mountClass in SkyWatcherMountClass.entries) assertEquals(mountClass, SkyWatcherMountClass.fromRawValue(mountClass.rawValue))
        for (mode in SkyWatcherMotionMode.entries) assertEquals(mode, SkyWatcherMotionMode.fromCode(mode.code))
        for (direction in SkyWatcherDirection.entries) assertEquals(direction, SkyWatcherDirection.fromCode(direction.code))
        assertNull(SkyWatcherMotionMode.fromCode('4'))
        assertEquals(listOf(0, 1), SkyWatcherAxis.entries.map { it.index })
        assertEquals(listOf('1', '2'), SkyWatcherAxis.entries.map { it.channel })
    }
}
