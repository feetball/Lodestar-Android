package net.kuecker.lodestar.services

import net.kuecker.lodestar.kit.mount.MountEpoch
import net.kuecker.lodestar.kit.mount.MountNudgeRate
import net.kuecker.lodestar.kit.mount.MountProtocol
import net.kuecker.lodestar.kit.mount.SkyWatcherMountMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MountPreferencesTest {
    private val store = MapMountStore()

    @Test
    fun aNewInstallStartsWithTheDefaultsOfTheFirstProtocol() {
        val preferences = MountPreferences(store)
        val first = MountProtocols.all.first()
        assertEquals(first.defaultConnection(), preferences.lastConnection)
        assertEquals(MountSettingsValues(), preferences.settings)
        assertEquals(5.0, preferences.settings.altitudeLimit)
        assertFalse(preferences.hasConnectedBefore)
    }

    @Test
    fun theLastConnectionAndEachProtocolsAddressAreRemembered() {
        val preferences = MountPreferences(store)
        val onStep = MountConnection(MountProtocol.LX200, MountLinkKind.TCP, "10.0.0.7", 4030)
        val synScan = MountConnection(MountProtocol.NEXSTAR, MountLinkKind.TCP, "1.2.3.4", 2001)
        val alpaca = MountConnection(MountProtocol.ALPACA, MountLinkKind.HTTP, "192.168.1.50", 32323, deviceNumber = 1, deviceName = "Mount")
        preferences.remember(onStep)
        preferences.remember(synScan)
        preferences.remember(alpaca)
        preferences.markConnected()

        val restarted = MountPreferences(store)
        assertEquals(alpaca, restarted.lastConnection)
        assertTrue(restarted.hasConnectedBefore)
        assertEquals(onStep, restarted.connection(MountProtocol.LX200))
        assertEquals(synScan, restarted.connection(MountProtocol.NEXSTAR))
        // The connection type of a protocol is remembered too, and a protocol's other type keeps its own default.
        assertEquals(MountLinkKind.TCP, restarted.connection(MountProtocol.NEXSTAR).link)
        assertEquals(MountProtocols.option(MountProtocol.NEXSTAR).defaults.getValue(MountLinkKind.UDP).port, restarted.connection(MountProtocol.NEXSTAR, MountLinkKind.UDP).port)
    }

    @Test
    fun aChosenAlpacaTelescopeIsForgottenWhenTheNextConnectionHasNone() {
        val preferences = MountPreferences(store)
        preferences.remember(MountConnection(MountProtocol.ALPACA, MountLinkKind.HTTP, "h", 1, 3, "Named"))
        preferences.remember(MountConnection(MountProtocol.ALPACA, MountLinkKind.HTTP, "h", 1, 0, null))
        assertNull(MountPreferences(store).connection(MountProtocol.ALPACA).deviceName)
    }

    @Test
    fun settingsAreKeptAndClamped() {
        val preferences = MountPreferences(store)
        preferences.update {
            it.copy(
                altitudeLimit = 500.0, maximumSyncOffset = 10.0, nudgeRate = MountNudgeRate.FIND, sendsSiteAndTime = false,
                advanced = MountAdvanced(epoch = MountEpoch.J2000, swapEastWest = true),
            )
        }
        assertEquals(60.0, preferences.settings.altitudeLimit)
        preferences.update { it.copy(altitudeLimit = -50.0) }
        assertEquals(-10.0, preferences.settings.altitudeLimit)
        preferences.update { it.copy(altitudeLimit = Double.NaN, maximumSyncOffset = Double.NaN) }
        assertEquals(MountSettingsValues.DEFAULT_ALTITUDE_LIMIT, preferences.settings.altitudeLimit)
        assertEquals(MountSettingsValues.NO_SYNC_LIMIT, preferences.settings.maximumSyncOffset)
        preferences.update { it.copy(altitudeLimit = 12.0, maximumSyncOffset = 10.0) }

        val restarted = MountPreferences(store).settings
        assertEquals(12.0, restarted.altitudeLimit)
        assertEquals(10.0, restarted.maximumSyncOffset)
        assertEquals(MountNudgeRate.FIND, restarted.nudgeRate)
        assertFalse(restarted.sendsSiteAndTime)
        assertEquals(MountAdvanced(MountEpoch.J2000, swapEastWest = true, swapNorthSouth = false), restarted.advanced)
    }

    @Test
    fun theSkyWatcherOptionsAreKept() {
        val preferences = MountPreferences(store)
        preferences.update {
            it.copy(advanced = it.advanced.copy(skyWatcherMode = SkyWatcherMountMode.EQUATORIAL, reverseAxis1 = true, reverseAxis2 = true))
        }
        val restarted = MountPreferences(store).settings.advanced
        assertEquals(SkyWatcherMountMode.EQUATORIAL, restarted.skyWatcherMode)
        assertTrue(restarted.reverseAxis1 && restarted.reverseAxis2)
        preferences.update { it.copy(advanced = it.advanced.copy(skyWatcherMode = null, reverseAxis1 = false)) }
        val automatic = MountPreferences(store).settings.advanced
        assertNull(automatic.skyWatcherMode)
        assertFalse(automatic.reverseAxis1)
        assertTrue(automatic.reverseAxis2)
        store.putString("skyWatcherArrangement", "sideways")
        assertNull(MountPreferences(store).settings.advanced.skyWatcherMode)
    }

    @Test
    fun damagedStoredValuesFallBackToDefaults() {
        store.putString("protocol", "not-a-protocol")
        store.putString("altitudeLimit", "high")
        store.putString("nudgeRate", "ludicrous")
        store.putString("lx200.tcp.port", "99999")
        store.putString("lx200.tcp.host", "")
        store.putString("lx200.tcp.device", "-4")
        val preferences = MountPreferences(store)
        val defaults = MountProtocols.option(MountProtocol.LX200).defaultConnection()
        assertEquals(defaults, preferences.connection(MountProtocol.LX200))
        assertEquals(MountSettingsValues(), preferences.settings)
        assertEquals(MountProtocols.all.first().protocol, preferences.lastConnection.protocol)
    }
}

class MountDemoClockTest {
    @Test
    fun theDemoClockFollowsTheRealTimeAndNeverSleeps() {
        var nanos = 0L
        val clock = MountDemoClock(MountTestSky.start) { nanos }
        assertEquals(MountTestSky.start, clock.now())
        nanos += 2_500_000_000L
        assertEquals(MountTestSky.start.plusMillis(2_500), clock.now())
        // Sleeping moves the simulated time without waiting.
        val before = System.nanoTime()
        clock.sleep(30.0)
        assertTrue((System.nanoTime() - before) / 1e9 < 1.0)
        assertEquals(MountTestSky.start.plusMillis(32_500), clock.now())
    }
}
