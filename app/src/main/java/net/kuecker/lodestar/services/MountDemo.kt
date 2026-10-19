package net.kuecker.lodestar.services

import net.kuecker.lodestar.kit.foundation.EquatorialCoordinate
import net.kuecker.lodestar.kit.foundation.GeographicLocation
import net.kuecker.lodestar.kit.foundation.HorizontalCoordinate
import net.kuecker.lodestar.kit.foundation.Observer
import net.kuecker.lodestar.kit.foundation.SkyFrame
import net.kuecker.lodestar.kit.mount.LX200Dialect
import net.kuecker.lodestar.kit.mount.LX200Driver
import net.kuecker.lodestar.kit.mount.LX200Simulator
import net.kuecker.lodestar.kit.mount.ManualMountClock
import net.kuecker.lodestar.kit.mount.MountClock
import net.kuecker.lodestar.kit.mount.MountEpoch
import net.kuecker.lodestar.kit.mount.MountSimulatorModel
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
import java.time.Instant
import kotlin.math.abs

/**
 * The simulated clock of the demo mount: the engine's simulators run on a [ManualMountClock], so this one follows the
 * real time (every reading first moves the manual clock on by the time that passed) and never really sleeps.
 */
class MountDemoClock(start: Instant, private val nanoTime: () -> Long = System::nanoTime) : MountClock {
    /** The clock the simulated mount runs on. */
    val manual = ManualMountClock(start)
    private var last = nanoTime()

    @Synchronized
    override fun now(): Instant {
        val current = nanoTime()
        manual.advance((current - last) / NANOSECONDS_PER_SECOND)
        last = current
        return manual.now()
    }

    override fun sleep(seconds: Double) = manual.advance(seconds)

    private companion object {
        const val NANOSECONDS_PER_SECOND = 1e9
    }
}

/**
 * A mount without hardware, so the whole feature can be tried (and reviewed) with no telescope. It speaks the real protocol
 * through the real driver; only the bytes never leave the phone.
 *
 * - [MountDemoKind.LX200]: an OnStep-like mount that slews at 4° per second and waits high in the south.
 * - [MountDemoKind.SKYWATCHER]: a Sky-Watcher AZ-GTi at its home position (level, pointing north) with a few degrees of set-up
 *   error, which has to be aligned on stars before the first Go to. A helper stands in for the nudge arrows: it puts the
 *   simulated telescope on the target, as if the user had centred it.
 */
class MountDemoLinkFactory(
    private val kind: MountDemoKind,
    private val observerProvider: () -> Observer?,
    private val now: () -> Instant = Instant::now,
) : MountLinkFactory {
    override fun open(
        connection: MountConnection,
        advanced: MountAdvanced,
        clock: MountClock,
        location: GeographicLocation,
        track: (Closeable) -> Unit,
    ): MountOpenedLink = when (kind) {
        MountDemoKind.LX200 -> openOnStep(advanced)
        MountDemoKind.SKYWATCHER -> openSkyWatcher(advanced, location)
    }

    private fun openOnStep(advanced: MountAdvanced): MountOpenedLink {
        val start = now()
        val demoClock = MountDemoClock(start)
        val epoch = advanced.epoch ?: MountEpoch.MEAN_OF_DATE
        val parked = parkPosition(observerProvider(), start, epoch)
        val simulated = MountSimulatorModel(demoClock.manual, parked, slewRateDegreesPerSecond = DEMO_SLEW_RATE)
        val transport = LX200Simulator(demoClock.manual, simulated, LX200Dialect.ONSTEP)
        val driver = LX200Driver(transport, demoClock)
        return MountOpenedLink(driver, demoClock, emptyList(), isDemo = true)
    }

    private fun openSkyWatcher(advanced: MountAdvanced, location: GeographicLocation): MountOpenedLink {
        val demoClock = MountDemoClock(now())
        val mode = advanced.skyWatcherMode ?: SkyWatcherMountMode.ALT_AZIMUTH
        val simulator = SkyWatcherSimulator(
            clock = demoClock.manual,
            location = location,
            mode = mode,
            errors = SET_UP_ERRORS,
            version = if (mode == SkyWatcherMountMode.ALT_AZIMUTH) SkyWatcherVersion(3, 0x22, 0xA5) else SkyWatcherVersion(3, 0x40, 0x0C),
        )
        val driver = SkyWatcherDriver(simulator, demoClock, SkyWatcherConfiguration(location = location, mode = mode))
        val helper = MountDemoHelper { star -> centre(simulator, demoClock, star) }
        return MountOpenedLink(driver, demoClock, emptyList(), isDemo = true, demoHelper = helper)
    }

    /** Turns the simulated axes so that the telescope (with its real, slightly wrong geometry) points at [star] now. */
    private fun centre(simulator: SkyWatcherSimulator, clock: MountDemoClock, star: EquatorialCoordinate) {
        val frame = SkyFrame(clock.now(), Observer(simulator.location))
        val place = MountEpoch.APPARENT_OF_DATE.fromJ2000(star, frame)
        val direction = SkyWatcherSky.localDirection(place, frame.localApparentSiderealTime, simulator.location.latitude, simulator.mode)
        val truth = SkyWatcherPointingModel(simulator.mode, simulator.location.latitude < 0, simulator.effectiveErrors)
        val options = truth.solutions(direction, simulator.axis1Degrees)
        val allowed = options.filter {
            if (simulator.mode == SkyWatcherMountMode.EQUATORIAL) abs(truth.modelAxis1(it.axis1)) <= MERIDIAN_LIMIT else it.pierSide == SkyWatcherPierSide.EAST
        }
        val best = (allowed.ifEmpty { options }).minByOrNull { abs(it.axis1 - simulator.axis1Degrees) + abs(it.axis2 - simulator.axis2Degrees) }
            ?: return
        simulator.placeAxes(best.axis1, best.axis2)
    }

    /** Due south at 50° altitude, in the frame the simulated mount reports. */
    private fun parkPosition(observer: Observer?, date: Instant, epoch: MountEpoch): EquatorialCoordinate {
        if (observer == null) return EquatorialCoordinate.fromRightAscensionHours(12.0, 45.0)
        val frame = SkyFrame(date, observer)
        val j2000 = frame.j2000(HorizontalCoordinate(PARK_AZIMUTH, PARK_ALTITUDE), refracted = false)
        return epoch.fromJ2000(j2000, frame)
    }

    private companion object {
        const val DEMO_SLEW_RATE = 4.0
        const val PARK_AZIMUTH = 180.0
        const val PARK_ALTITUDE = 50.0
        const val MERIDIAN_LIMIT = 95.0

        /** What a mount set up by hand typically has wrong: a few degrees of home position, a fraction of a degree of tilt. */
        val SET_UP_ERRORS = SkyWatcherPointingErrors(
            axis1Index = 2.0, axis2Index = 1.1, baseTiltX = 0.5, baseTiltY = -0.4, orthogonality = 0.1, cone = 0.12,
        )
    }
}
