package net.kuecker.lodestar.services

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import net.kuecker.lodestar.kit.mount.MountEpoch
import net.kuecker.lodestar.kit.mount.MountNudgeRate
import net.kuecker.lodestar.kit.mount.MountProtocol
import net.kuecker.lodestar.kit.mount.SkyWatcherMountMode

/** A few named values that survive restarts; backed by shared preferences in the app, by a map in tests. */
interface MountStore {
    fun getString(key: String): String?

    fun putString(key: String, value: String)

    fun remove(key: String)
}

/** [MountStore] in the device-local `lodestar.mount` preferences (not part of the backup: addresses are per network). */
class SharedPreferencesMountStore(private val preferences: SharedPreferences) : MountStore {
    override fun getString(key: String): String? = preferences.getString(key, null)

    override fun putString(key: String, value: String) = preferences.edit { putString(key, value) }

    override fun remove(key: String) = preferences.edit { remove(key) }
}

/** The settings of the mount feature that apply to every connection. */
data class MountSettingsValues(
    /** The lowest altitude, in degrees, Lodestar slews to. */
    val altitudeLimit: Double = DEFAULT_ALTITUDE_LIMIT,
    /** A sync that would move the mount by more than this many degrees is refused; 180 means no limit. */
    val maximumSyncOffset: Double = NO_SYNC_LIMIT,
    val nudgeRate: MountNudgeRate = MountNudgeRate.CENTER,
    /** Send the phone's location and UTC time to the mount when connecting. */
    val sendsSiteAndTime: Boolean = true,
    val advanced: MountAdvanced = MountAdvanced(),
) {
    companion object {
        const val DEFAULT_ALTITUDE_LIMIT = 5.0
        const val NO_SYNC_LIMIT = 180.0
        val altitudeLimitRange = -10.0..60.0

        /** The choices for the sync offset limit, in degrees. */
        val syncLimits = listOf(5.0, 10.0, 30.0, NO_SYNC_LIMIT)
    }
}

/**
 * The remembered state of the mount feature: the connection last used for each protocol (address and port), which
 * protocol was last used, and the settings. Reads and writes happen on the main thread; the values are Compose state.
 * Anything stored that is out of range or unknown is replaced by its default.
 */
class MountPreferences(private val store: MountStore) {
    var settings: MountSettingsValues by mutableStateOf(loadSettings())
        private set

    var lastConnection: MountConnection by mutableStateOf(loadLastConnection())
        private set

    /** Whether a connection was ever made, which tells a returning user from a new one. */
    var hasConnectedBefore: Boolean by mutableStateOf(store.getString(KEY_CONNECTED_BEFORE) == "1")
        private set

    /** The remembered connection of [protocol], or its default when none was stored. */
    fun connection(protocol: MountProtocol, link: MountLinkKind? = null): MountConnection {
        val option = MountProtocols.option(protocol)
        val chosen = link?.takeIf { it in option.links } ?: storedLink(option) ?: option.links.first()
        val endpoint = option.defaults.getValue(chosen)
        val host = store.getString(key(option, chosen, "host"))?.takeIf { validHost(it) } ?: endpoint.host
        val port = store.getString(key(option, chosen, "port"))?.toIntOrNull()?.takeIf { it in 1..65_535 } ?: endpoint.port
        val device = store.getString(key(option, chosen, "device"))?.toIntOrNull()?.takeIf { it >= 0 } ?: 0
        val name = store.getString(key(option, chosen, "deviceName"))?.takeIf { it.isNotBlank() }
        return MountConnection(option.protocol, chosen, host, port, device, name)
    }

    /** Remembers [connection] as the last used (called when a connection is attempted). */
    fun remember(connection: MountConnection) {
        val option = MountProtocols.option(connection.protocol)
        store.putString(KEY_PROTOCOL, option.protocol.rawValue)
        store.putString(key(option, null, "link"), connection.link.rawValue)
        store.putString(key(option, connection.link, "host"), connection.host)
        store.putString(key(option, connection.link, "port"), connection.port.toString())
        store.putString(key(option, connection.link, "device"), connection.deviceNumber.toString())
        val name = connection.deviceName
        if (name.isNullOrBlank()) store.remove(key(option, connection.link, "deviceName")) else store.putString(key(option, connection.link, "deviceName"), name)
        lastConnection = connection
    }

    /** Records that a connection succeeded. */
    fun markConnected() {
        store.putString(KEY_CONNECTED_BEFORE, "1")
        hasConnectedBefore = true
    }

    /** Stores new settings (every value is clamped into its range). */
    fun update(transform: (MountSettingsValues) -> MountSettingsValues) {
        val next = clamped(transform(settings))
        if (next == settings) return
        settings = next
        store.putString(KEY_ALTITUDE_LIMIT, next.altitudeLimit.toString())
        store.putString(KEY_SYNC_LIMIT, next.maximumSyncOffset.toString())
        store.putString(KEY_NUDGE_RATE, next.nudgeRate.rawValue)
        store.putString(KEY_SITE_AND_TIME, if (next.sendsSiteAndTime) "1" else "0")
        store.putString(KEY_EPOCH, next.advanced.epoch?.rawValue ?: AUTOMATIC)
        store.putString(KEY_SWAP_EAST_WEST, if (next.advanced.swapEastWest) "1" else "0")
        store.putString(KEY_SWAP_NORTH_SOUTH, if (next.advanced.swapNorthSouth) "1" else "0")
        store.putString(KEY_ARRANGEMENT, next.advanced.skyWatcherMode?.rawValue ?: AUTOMATIC)
        store.putString(KEY_REVERSE_AXIS_1, if (next.advanced.reverseAxis1) "1" else "0")
        store.putString(KEY_REVERSE_AXIS_2, if (next.advanced.reverseAxis2) "1" else "0")
    }

    private fun loadSettings(): MountSettingsValues {
        val defaults = MountSettingsValues()
        return clamped(
            MountSettingsValues(
                altitudeLimit = store.getString(KEY_ALTITUDE_LIMIT)?.toDoubleOrNull() ?: defaults.altitudeLimit,
                maximumSyncOffset = store.getString(KEY_SYNC_LIMIT)?.toDoubleOrNull() ?: defaults.maximumSyncOffset,
                nudgeRate = MountNudgeRate.fromRawValue(store.getString(KEY_NUDGE_RATE).orEmpty()) ?: defaults.nudgeRate,
                sendsSiteAndTime = store.getString(KEY_SITE_AND_TIME)?.let { it == "1" } ?: defaults.sendsSiteAndTime,
                advanced = MountAdvanced(
                    epoch = MountEpoch.entries.firstOrNull { it.rawValue == store.getString(KEY_EPOCH) },
                    swapEastWest = store.getString(KEY_SWAP_EAST_WEST) == "1",
                    swapNorthSouth = store.getString(KEY_SWAP_NORTH_SOUTH) == "1",
                    skyWatcherMode = SkyWatcherMountMode.entries.firstOrNull { it.rawValue == store.getString(KEY_ARRANGEMENT) },
                    reverseAxis1 = store.getString(KEY_REVERSE_AXIS_1) == "1",
                    reverseAxis2 = store.getString(KEY_REVERSE_AXIS_2) == "1",
                ),
            ),
        )
    }

    private fun loadLastConnection(): MountConnection {
        val protocol = MountProtocol.fromRawValue(store.getString(KEY_PROTOCOL).orEmpty())
        return connection(MountProtocols.option(protocol).protocol)
    }

    private fun storedLink(option: MountProtocolOption): MountLinkKind? =
        MountLinkKind.fromRawValue(store.getString(key(option, null, "link")))?.takeIf { it in option.links }

    private fun clamped(values: MountSettingsValues): MountSettingsValues = values.copy(
        altitudeLimit = values.altitudeLimit.takeIf { it.isFinite() }?.coerceIn(MountSettingsValues.altitudeLimitRange)
            ?: MountSettingsValues.DEFAULT_ALTITUDE_LIMIT,
        maximumSyncOffset = values.maximumSyncOffset.takeIf { it.isFinite() }?.coerceIn(0.1, MountSettingsValues.NO_SYNC_LIMIT)
            ?: MountSettingsValues.NO_SYNC_LIMIT,
    )

    private fun key(option: MountProtocolOption, link: MountLinkKind?, field: String): String =
        if (link == null) "${option.protocol.rawValue}.$field" else "${option.protocol.rawValue}.${link.rawValue}.$field"

    private fun validHost(host: String): Boolean = host.isNotBlank() && host.length <= MAXIMUM_HOST_LENGTH

    private companion object {
        const val KEY_PROTOCOL = "protocol"
        const val KEY_CONNECTED_BEFORE = "connectedBefore"
        const val KEY_ALTITUDE_LIMIT = "altitudeLimit"
        const val KEY_SYNC_LIMIT = "syncLimit"
        const val KEY_NUDGE_RATE = "nudgeRate"
        const val KEY_SITE_AND_TIME = "sendsSiteAndTime"
        const val KEY_EPOCH = "epoch"
        const val KEY_SWAP_EAST_WEST = "swapEastWest"
        const val KEY_SWAP_NORTH_SOUTH = "swapNorthSouth"
        const val KEY_ARRANGEMENT = "skyWatcherArrangement"
        const val KEY_REVERSE_AXIS_1 = "skyWatcherReverseAxis1"
        const val KEY_REVERSE_AXIS_2 = "skyWatcherReverseAxis2"
        const val AUTOMATIC = "automatic"
        const val MAXIMUM_HOST_LENGTH = 253
    }
}
