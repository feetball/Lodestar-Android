package net.kuecker.lodestar.services

import net.kuecker.lodestar.kit.foundation.EquatorialCoordinate
import net.kuecker.lodestar.kit.foundation.GeographicLocation
import net.kuecker.lodestar.kit.foundation.Observer
import net.kuecker.lodestar.kit.foundation.SkyFrame
import net.kuecker.lodestar.kit.foundation.instantFromTimeIntervalSince1970
import net.kuecker.lodestar.kit.mount.AlpacaSimulator
import net.kuecker.lodestar.kit.mount.AlpacaDriver
import net.kuecker.lodestar.kit.mount.LX200Dialect
import net.kuecker.lodestar.kit.mount.LX200Driver
import net.kuecker.lodestar.kit.mount.LX200Simulator
import net.kuecker.lodestar.kit.mount.ManualMountClock
import net.kuecker.lodestar.kit.mount.MountClock
import net.kuecker.lodestar.kit.mount.MountProtocol
import net.kuecker.lodestar.kit.mount.MountSimulatedStreamTransport
import net.kuecker.lodestar.kit.mount.MountSimulatorModel
import net.kuecker.lodestar.kit.mount.NexStarDriver
import net.kuecker.lodestar.kit.mount.NexStarFlavor
import net.kuecker.lodestar.kit.mount.NexStarSimulator
import java.io.Closeable

/** An in-memory [MountStore]. */
class MapMountStore : MountStore {
    val values = HashMap<String, String>()

    override fun getString(key: String): String? = values[key]

    override fun putString(key: String, value: String) {
        values[key] = value
    }

    override fun remove(key: String) {
        values.remove(key)
    }
}

/** Places and times the mount tests share: Boulder, about 22:04 local time on 2026-10-18 (04:00 UTC). */
object MountTestSky {
    val boulder = Observer(GeographicLocation(latitude = 40.015, longitude = -105.27, elevation = 1650.0))
    val start = instantFromTimeIntervalSince1970(1_792_296_000.0)

    /** High in the east at [start]. */
    val andromeda = EquatorialCoordinate.fromRightAscensionHours(0.7123, 41.2689)

    /** About 40° up in the west at [start], setting. */
    val altair = EquatorialCoordinate.fromRightAscensionHours(19.8464, 8.8683)

    /** Below the horizon at [start]. */
    val orionNebula = EquatorialCoordinate.fromRightAscensionHours(5.5881, -5.3911)

    /** Near where the simulated mounts start, in the mount's own frame. */
    val parkPosition = EquatorialCoordinate.fromRightAscensionHours(20.0, 30.0)

    fun target(name: String, j2000: EquatorialCoordinate): MountTarget {
        val horizontal = SkyFrame(start, boulder).horizontal(j2000, null, false)
        return MountTarget(name, j2000, null, horizontal, isSun = false)
    }

    val connection = MountConnection(MountProtocol.LX200, MountLinkKind.TCP, "192.168.0.1", 9999)
}

/**
 * Opens one of the engine's simulators instead of a socket, on a manual clock that only moves when the mount is asked
 * something, so the tests are quick and exact. [kind] is "lx200", "nexstar" or "alpaca".
 */
class SimulatedMountFactory(private val kind: String = "lx200") : MountLinkFactory {
    lateinit var model: MountSimulatorModel
    var stream: MountSimulatedStreamTransport? = null
    var alpaca: AlpacaSimulator? = null
    lateinit var clock: ManualMountClock
    var opened = 0
    var failure: Throwable? = null
    var tracked: Closeable? = null

    override fun open(connection: MountConnection, advanced: MountAdvanced, clock: MountClock, track: (Closeable) -> Unit): MountOpenedLink {
        failure?.let { throw it }
        opened++
        val manual = ManualMountClock(MountTestSky.start)
        this.clock = manual
        model = MountSimulatorModel(manual, MountTestSky.parkPosition)
        tracked = Closeable { }.also(track)
        val driver = when (kind) {
            "lx200" -> LX200Simulator(manual, model, LX200Dialect.ONSTEP).also { stream = it }.let { LX200Driver(it, manual) }
            "nexstar" -> NexStarSimulator(manual, model, NexStarFlavor.SYNSCAN, 4, 38, 6, model = 128).also { stream = it }
                .let { NexStarDriver(it, manual) }
            "alpaca" -> AlpacaSimulator(manual, model, equatorialSystem = 1).also { alpaca = it }.let { AlpacaDriver(it) }
            else -> throw IllegalArgumentException(kind)
        }
        return MountOpenedLink(driver, manual, emptyList())
    }
}
