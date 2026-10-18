package net.kuecker.lodestar.services

import net.kuecker.lodestar.kit.mount.AlpacaDriver
import net.kuecker.lodestar.kit.mount.LX200Driver
import net.kuecker.lodestar.kit.mount.ManualMountClock
import net.kuecker.lodestar.kit.mount.MountEpoch
import net.kuecker.lodestar.kit.mount.MountError
import net.kuecker.lodestar.kit.mount.MountProtocol
import net.kuecker.lodestar.kit.mount.NexStarDriver
import net.kuecker.lodestar.kit.mount.SkyWatcherDriver
import net.kuecker.lodestar.kit.mount.SkyWatcherMountMode
import java.io.Closeable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MountProtocolsTest {
    @Test
    fun everyProtocolOfTheEngineHasAnOptionInTheForm() {
        // A protocol added to the engine must be listed in MountProtocols to appear in the connection form.
        assertEquals(emptyList(), MountProtocols.missing, "the form has no entry for ${MountProtocols.missing}")
    }

    @Test
    fun theOptionsAreConsistent() {
        assertEquals(MountProtocols.all.size, MountProtocols.all.map { it.protocol }.toSet().size)
        for (option in MountProtocols.all) {
            assertTrue(option.title.isNotBlank() && option.summary.isNotBlank() && option.explanation.isNotBlank(), option.title)
            val default = option.defaultConnection()
            assertEquals(option.protocol, default.protocol)
            assertEquals(option.links.first(), default.link)
            assertTrue(default.port in 1..65_535 && default.host.isNotBlank(), option.title)
            for (link in option.links) {
                assertEquals(link, option.defaultConnection(link).link)
            }
            // A connection type the protocol does not have falls back to its first.
            assertEquals(option.links.first(), option.defaultConnection(MountLinkKind.entries.first { it !in option.links }).link)
        }
    }

    @Test
    fun theSkyWatcherOptionIsListedWithItsDefaults() {
        val option = MountProtocols.option(MountProtocol.SKYWATCHER)
        assertEquals(MountProtocol.SKYWATCHER, option.protocol)
        assertEquals(SkyWatcherDriver.menuTitle, option.title)
        assertEquals("Sky-Watcher Wi-Fi (AZ-GTi, Star Adventurer GTi)", option.title)
        assertEquals(listOf(MountLinkKind.UDP), option.links)
        assertEquals(MountEndpoint("192.168.4.1", 11880), option.defaults.getValue(MountLinkKind.UDP))
        assertEquals(MountDiscoveryKind.SKYWATCHER, option.discovery)
        assertEquals("Find mount", option.discovery?.buttonTitle)
        assertEquals(
            setOf(MountAdvancedField.ARRANGEMENT, MountAdvancedField.REVERSE_AXIS_1, MountAdvancedField.REVERSE_AXIS_2),
            option.advancedFields,
        )
        assertTrue(MountProtocols.all.indexOf(option) in 1..2, "listed next to the first")
    }

    @Test
    fun theNexStarTextPointsToTheSkyWatcherOption() {
        val text = MountProtocols.option(MountProtocol.NEXSTAR).explanation
        assertTrue(!text.contains("does not talk"), text)
        assertTrue(text.contains("choose Sky-Watcher Wi-Fi"), text)
    }

    @Test
    fun anUnknownProtocolFallsBackToTheFirstOption() {
        assertEquals(MountProtocols.all.first(), MountProtocols.option(null))
    }

    @Test
    fun eachOptionBuildsItsDriver() {
        val clock = ManualMountClock(MountTestSky.start)
        for (option in MountProtocols.all) {
            val connection = option.defaultConnection()
            val tcp = TcpMountTransport("127.0.0.1", 1)
            val udp = UdpMountTransport("127.0.0.1", 1)
            val http = AlpacaHttpTransport(net.kuecker.lodestar.kit.mount.AlpacaEndpoint("127.0.0.1", 1))
            val context = MountDriverContext(
                connection, MountAdvanced(), clock, MountTestSky.boulder.location,
                stream = tcp.takeUnless { connection.link == MountLinkKind.UDP } ?: udp, http = http,
            )
            val driver = option.createDriver(context)
            assertEquals(option.protocol, driver.kind)
            // A protocol added later has no entry here yet; its driver is then only checked for its kind above.
            val expected = mapOf(
                MountProtocol.LX200 to LX200Driver::class,
                MountProtocol.NEXSTAR to NexStarDriver::class,
                MountProtocol.ALPACA to AlpacaDriver::class,
                MountProtocol.SKYWATCHER to SkyWatcherDriver::class,
            )[option.protocol]
            if (expected != null) assertTrue(expected.isInstance(driver), "${option.title}: $driver")
        }
    }

    @Test
    fun theAdvancedOptionsReachTheDrivers() {
        val clock = ManualMountClock(MountTestSky.start)
        val transport = TcpMountTransport("127.0.0.1", 1)
        val lx200 = MountProtocols.option(MountProtocol.LX200)
        val driver = lx200.createDriver(MountDriverContext(lx200.defaultConnection(), MountAdvanced(epoch = MountEpoch.J2000), clock, MountTestSky.boulder.location, transport, null))
        assertEquals(MountEpoch.J2000, driver.epoch)
        val nexstar = MountProtocols.option(MountProtocol.NEXSTAR)
        val swapped = nexstar.createDriver(
            MountDriverContext(nexstar.defaultConnection(), MountAdvanced(swapEastWest = true), clock, MountTestSky.boulder.location, transport, null),
        ) as NexStarDriver
        assertTrue(swapped.configuration.invertsEastWest)
        val alpaca = MountProtocols.option(MountProtocol.ALPACA)
        val connection = alpaca.defaultConnection().copy(deviceNumber = 3)
        val http = AlpacaHttpTransport(net.kuecker.lodestar.kit.mount.AlpacaEndpoint("127.0.0.1", 1))
        val alpacaDriver = alpaca.createDriver(
            MountDriverContext(connection, MountAdvanced(swapEastWest = true, swapNorthSouth = true), clock, MountTestSky.boulder.location, null, http),
        ) as AlpacaDriver
        assertEquals(3, alpacaDriver.configuration.deviceNumber)
        val skyWatcher = MountProtocols.option(MountProtocol.SKYWATCHER)
        val swDriver = skyWatcher.createDriver(
            MountDriverContext(
                skyWatcher.defaultConnection(),
                MountAdvanced(skyWatcherMode = SkyWatcherMountMode.EQUATORIAL, reverseAxis1 = true),
                clock, MountTestSky.boulder.location, UdpMountTransport("127.0.0.1", 1), null,
            ),
        ) as SkyWatcherDriver
        assertEquals(SkyWatcherMountMode.EQUATORIAL, swDriver.configuration.mode)
        assertTrue(swDriver.configuration.invertsAxis1 && !swDriver.configuration.invertsAxis2)
        assertEquals(MountTestSky.boulder.location, swDriver.configuration.location)
        assertEquals(false, alpacaDriver.configuration.primaryPositiveIsEast)
        assertEquals(false, alpacaDriver.configuration.secondaryPositiveIsNorth)
    }

    @Test
    fun theSocketFactoryRefusesWhatItCannotOpen() {
        val factory = SocketMountLinkFactory()
        val clock = ManualMountClock(MountTestSky.start)
        val track: (Closeable) -> Unit = {}
        val noHost = MountProtocols.all.first().defaultConnection().copy(host = " ")
        assertIs<MountError.InvalidInput>(assertFailsWith<MountError> { factory.open(noHost, MountAdvanced(), clock, MountTestSky.boulder.location, track) })
        val wrongLink = MountProtocols.option(MountProtocol.LX200).defaultConnection().copy(link = MountLinkKind.HTTP)
        assertIs<MountError.InvalidInput>(assertFailsWith<MountError> { factory.open(wrongLink, MountAdvanced(), clock, MountTestSky.boulder.location, track) })
        val badAddress = MountProtocols.option(MountProtocol.ALPACA).defaultConnection().copy(host = "a b/c")
        assertIs<MountError.InvalidInput>(assertFailsWith<MountError> { factory.open(badAddress, MountAdvanced(), clock, MountTestSky.boulder.location, track) })
    }
}
