package net.kuecker.lodestar.kit.mount

import net.kuecker.lodestar.kit.foundation.EquatorialCoordinate
import net.kuecker.lodestar.kit.foundation.GeographicLocation
import net.kuecker.lodestar.kit.foundation.Observer
import net.kuecker.lodestar.kit.foundation.SkyFrame
import net.kuecker.lodestar.kit.foundation.instantFromTimeIntervalSince1970
import java.time.Instant

/** A controller wired to a simulator, with everything a test wants to look at. */
class MountRig(
    val name: String,
    val clock: ManualMountClock,
    val mount: MountSimulatorModel,
    val controller: MountController,
    val driver: MountDriver,
    val streamSimulator: MountSimulatedStreamTransport?,
    val alpacaSimulator: AlpacaSimulator?,
) {
    /** The frame of the coordinates the simulated mount works in. */
    val epoch: MountEpoch get() = driver.epoch

    fun frame(): SkyFrame = SkyFrame(clock.now(), MountTestSupport.boulder)

    /** The place the simulated mount points at, as J2000. */
    fun mountJ2000(): EquatorialCoordinate = epoch.toJ2000(mount.position, frame())
}

object MountTestSupport {
    val boulder = Observer(GeographicLocation(latitude = 40.015, longitude = -105.27, elevation = 1650.0))

    /** 2026-10-18 04:00 UTC, about 22:04 the evening before in Colorado: right ascension 22.7 h is near the meridian. */
    val start = instantFromTimeIntervalSince1970(1_792_296_000.0)

    /**
     * 2026-10-18 19:00 UTC, 13:00 in Colorado (local apparent noon is at 18:47 UTC): the Sun stands about 40 degrees up in
     * the south, at right ascension 13.5 h and declination -9.6 degrees.
     */
    val midday = instantFromTimeIntervalSince1970(1_792_350_000.0)

    /** The Andromeda galaxy's centre (J2000): high in the east at [start]. */
    val andromeda = EquatorialCoordinate.fromRightAscensionHours(0.7123, 41.2689)

    /** Deneb (J2000): high in the west at [start]. */
    val deneb = EquatorialCoordinate.fromRightAscensionHours(20.6905, 45.2803)

    /** Altair (J2000): about 40 degrees up in the west at [start] and setting. */
    val altair = EquatorialCoordinate.fromRightAscensionHours(19.8464, 8.8683)

    /** The Orion nebula (J2000): below the horizon at [start]. */
    val orionNebula = EquatorialCoordinate.fromRightAscensionHours(5.5881, -5.3911)

    /** Where the mounts start: a place in the mount's own frame, not far from Deneb. */
    val parkPosition = EquatorialCoordinate.fromRightAscensionHours(20.0, 30.0)

    val rigNames = listOf("lx200-onstep", "lx200-meade-low", "nexstar-celestron", "nexstar-synscan", "alpaca-topocentric", "alpaca-j2000")

    fun rig(
        name: String,
        settings: MountSettings = MountSettings.standard,
        chunkSize: Int = 0,
        slewRate: Double = 4.0,
        begin: Instant = start,
        configure: (MountRig) -> Unit = {},
    ): MountRig {
        val clock = ManualMountClock(begin)
        val mount = MountSimulatorModel(clock, parkPosition, slewRate)
        val driver: MountDriver
        var stream: MountSimulatedStreamTransport? = null
        var alpaca: AlpacaSimulator? = null
        when (name) {
            "lx200-onstep" -> {
                val simulator = LX200Simulator(clock, mount, LX200Dialect.ONSTEP, chunkSize = chunkSize)
                stream = simulator
                driver = LX200Driver(simulator, clock)
            }
            "lx200-meade-low" -> {
                val simulator = LX200Simulator(clock, mount, LX200Dialect.MEADE, highPrecision = false, chunkSize = chunkSize)
                stream = simulator
                driver = LX200Driver(simulator, clock)
            }
            "nexstar-celestron" -> {
                val simulator = NexStarSimulator(clock, mount, NexStarFlavor.CELESTRON, 5, 28, 0, model = 12, chunkSize = chunkSize)
                stream = simulator
                driver = NexStarDriver(simulator, clock)
            }
            "nexstar-synscan" -> {
                val simulator = NexStarSimulator(clock, mount, NexStarFlavor.SYNSCAN, 4, 38, 6, model = 128, chunkSize = chunkSize)
                stream = simulator
                driver = NexStarDriver(simulator, clock)
            }
            "alpaca-topocentric" -> {
                val simulator = AlpacaSimulator(clock, mount, equatorialSystem = 1)
                alpaca = simulator
                driver = AlpacaDriver(simulator)
            }
            "alpaca-j2000" -> {
                val simulator = AlpacaSimulator(clock, mount, equatorialSystem = 2)
                alpaca = simulator
                driver = AlpacaDriver(simulator)
            }
            else -> throw IllegalArgumentException("unknown rig $name")
        }
        val controller = MountController(driver, boulder, settings, clock)
        val rig = MountRig(name, clock, mount, controller, driver, stream, alpaca)
        configure(rig)
        return rig
    }

    /** The altitude of [target] at the rig's time, geometric. */
    fun altitude(target: EquatorialCoordinate, clock: ManualMountClock): Double =
        SkyFrame(clock.now(), boulder).horizontal(target, null, false).altitude
}
