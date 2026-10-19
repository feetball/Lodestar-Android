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
import net.kuecker.lodestar.kit.mount.MountEpoch
import net.kuecker.lodestar.kit.mount.SkyWatcherConfiguration
import net.kuecker.lodestar.kit.mount.SkyWatcherDriver
import net.kuecker.lodestar.kit.mount.SkyWatcherMountMode
import net.kuecker.lodestar.kit.mount.SkyWatcherPierSide
import net.kuecker.lodestar.kit.mount.SkyWatcherPointingErrors
import net.kuecker.lodestar.kit.mount.SkyWatcherPointingModel
import net.kuecker.lodestar.kit.mount.SkyWatcherSimulator
import net.kuecker.lodestar.kit.mount.SkyWatcherSky
import net.kuecker.lodestar.kit.mount.SkyWatcherVersion
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

    /** Hour angle +4.1 h, altitude 43° at [start] (west). */
    val vega = EquatorialCoordinate.fromRightAscensionHours(18.6156, 38.7837)

    /** Hour angle -4.7 h, altitude 41° at [start] (north-east). */
    val mirfak = EquatorialCoordinate.fromRightAscensionHours(3.4054, 49.8612)

    /** Hour angle +1.0 h, altitude 57° at [start] (south-west). */
    val enif = EquatorialCoordinate.fromRightAscensionHours(21.7364, 9.875)

    /** Altitude 67° at [start]. */
    val deneb = EquatorialCoordinate.fromRightAscensionHours(20.6905, 45.2803)

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

    override fun open(
        connection: MountConnection,
        advanced: MountAdvanced,
        clock: MountClock,
        location: GeographicLocation,
        track: (Closeable) -> Unit,
    ): MountOpenedLink {
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

/**
 * Opens a simulated Sky-Watcher Wi-Fi mount with the given real geometry, on a manual clock. [placeOn] stands in for the nudge
 * arrows: it turns the simulated axes so that the real telescope points exactly at a star.
 */
class SimulatedSkyWatcherFactory(
    private val mode: SkyWatcherMountMode = SkyWatcherMountMode.ALT_AZIMUTH,
    private val errors: SkyWatcherPointingErrors = SkyWatcherPointingErrors(),
    private val startAxes: Pair<Double, Double>? = null,
) : MountLinkFactory {
    lateinit var simulator: SkyWatcherSimulator
    lateinit var clock: ManualMountClock
    var opened = 0
    var openedAdvanced: MountAdvanced? = null

    override fun open(
        connection: MountConnection,
        advanced: MountAdvanced,
        clock: MountClock,
        location: GeographicLocation,
        track: (Closeable) -> Unit,
    ): MountOpenedLink {
        opened++
        openedAdvanced = advanced
        val manual = ManualMountClock(MountTestSky.start)
        this.clock = manual
        val resolved = advanced.skyWatcherMode ?: mode
        simulator = SkyWatcherSimulator(
            manual, location, resolved, errors,
            version = if (resolved == SkyWatcherMountMode.ALT_AZIMUTH) SkyWatcherVersion(3, 0x22, 0xA5) else SkyWatcherVersion(3, 0x40, 0x0C),
            axis1Inverted = advanced.reverseAxis1,
            axis2Inverted = advanced.reverseAxis2,
        )
        startAxes?.let { simulator.placeAxes(it.first, it.second) }
        val driver = SkyWatcherDriver(
            simulator, manual,
            SkyWatcherConfiguration(location, mode = advanced.skyWatcherMode, invertsAxis1 = advanced.reverseAxis1, invertsAxis2 = advanced.reverseAxis2),
        )
        return MountOpenedLink(driver, manual, emptyList())
    }

    private fun frame() = SkyFrame(clock.now(), Observer(simulator.location))

    /** Where the real telescope points now, as J2000. */
    fun trueJ2000(): EquatorialCoordinate = MountEpoch.APPARENT_OF_DATE.toJ2000(simulator.truePlace(), frame())

    /** Turns the simulated axes so that the real telescope points exactly at [star] now. */
    fun placeOn(star: EquatorialCoordinate) {
        val frame = frame()
        val place = MountEpoch.APPARENT_OF_DATE.fromJ2000(star, frame)
        val direction = SkyWatcherSky.localDirection(place, frame.localApparentSiderealTime, simulator.location.latitude, simulator.mode)
        val truth = SkyWatcherPointingModel(simulator.mode, simulator.location.latitude < 0, simulator.effectiveErrors)
        val options = truth.solutions(direction, simulator.axis1Degrees)
        val allowed = options.filter {
            if (simulator.mode == SkyWatcherMountMode.EQUATORIAL) kotlin.math.abs(truth.modelAxis1(it.axis1)) <= 95 else it.pierSide == SkyWatcherPierSide.EAST
        }
        val best = (allowed.ifEmpty { options }).minBy { kotlin.math.abs(it.axis1 - simulator.axis1Degrees) + kotlin.math.abs(it.axis2 - simulator.axis2Degrees) }
        simulator.placeAxes(best.axis1, best.axis2)
    }
}
