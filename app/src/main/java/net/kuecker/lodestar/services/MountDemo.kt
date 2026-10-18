package net.kuecker.lodestar.services

import net.kuecker.lodestar.kit.foundation.EquatorialCoordinate
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
import java.io.Closeable
import java.time.Instant

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
 * A mount without hardware: an OnStep-like LX200 simulator that slews at 4° per second, starting at a place high in
 * the south, so the whole feature can be tried (and reviewed) with no telescope. It speaks the real protocol through
 * the real driver; only the bytes never leave the phone.
 */
class MountDemoLinkFactory(
    private val observerProvider: () -> Observer?,
    private val now: () -> Instant = Instant::now,
) : MountLinkFactory {
    override fun open(
        connection: MountConnection,
        advanced: MountAdvanced,
        clock: MountClock,
        track: (Closeable) -> Unit,
    ): MountOpenedLink {
        val start = now()
        val demoClock = MountDemoClock(start)
        val epoch = advanced.epoch ?: MountEpoch.MEAN_OF_DATE
        val parked = parkPosition(observerProvider(), start, epoch)
        val simulated = MountSimulatorModel(demoClock.manual, parked, slewRateDegreesPerSecond = DEMO_SLEW_RATE)
        val transport = LX200Simulator(demoClock.manual, simulated, LX200Dialect.ONSTEP)
        val driver = LX200Driver(transport, demoClock)
        return MountOpenedLink(driver, demoClock, emptyList(), isDemo = true)
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
    }
}
