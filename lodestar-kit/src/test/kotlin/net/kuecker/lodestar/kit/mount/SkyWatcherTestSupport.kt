package net.kuecker.lodestar.kit.mount

import net.kuecker.lodestar.kit.foundation.EquatorialCoordinate
import net.kuecker.lodestar.kit.foundation.GeographicLocation
import net.kuecker.lodestar.kit.foundation.Observer
import net.kuecker.lodestar.kit.foundation.SkyFrame

/** A controller wired to a Sky-Watcher simulator, with everything a test wants to look at. */
class SkyWatcherRig(
    val clock: ManualMountClock,
    val simulator: SkyWatcherSimulator,
    val driver: SkyWatcherDriver,
    val controller: MountController,
) {
    fun frame(): SkyFrame = SkyFrame(clock.now(), Observer(simulator.location))

    /** Where the simulated telescope really points now, as J2000. */
    fun trueJ2000(): EquatorialCoordinate = MountEpoch.APPARENT_OF_DATE.toJ2000(simulator.truePlace(), frame())

    /** The angle in degrees between the simulated telescope's real pointing and the J2000 place [target]. */
    fun errorTo(target: EquatorialCoordinate): Double = trueJ2000().angularSeparation(target)

    /** Centres [star] by hand (the simulated telescope is placed exactly on it through the axes) and syncs. */
    fun syncOn(star: EquatorialCoordinate): MountSyncResult {
        placeOn(star)
        return controller.sync(star)
    }

    /** Moves the simulated axes so that the real telescope points exactly at [star] now (what a user does with the nudge buttons). */
    fun placeOn(star: EquatorialCoordinate) {
        val frame = frame()
        val place = MountEpoch.APPARENT_OF_DATE.fromJ2000(star, frame)
        val direction = SkyWatcherSky.localDirection(place, frame.localApparentSiderealTime, simulator.location.latitude, simulator.mode)
        val truth = SkyWatcherPointingModel(simulator.mode, simulator.location.latitude < 0, simulator.effectiveErrors)
        val options = truth.solutions(direction, simulator.axis1Degrees)
        var best = options[0]
        var bestCost = Double.MAX_VALUE
        for (option in options) {
            val cost = kotlin.math.abs(option.axis1 - simulator.axis1Degrees) + kotlin.math.abs(option.axis2 - simulator.axis2Degrees)
            val allowed = if (simulator.mode == SkyWatcherMountMode.EQUATORIAL) {
                kotlin.math.abs(truth.modelAxis1(option.axis1)) <= 95
            } else {
                option.pierSide == SkyWatcherPierSide.EAST
            }
            if (allowed && cost < bestCost) {
                best = option
                bestCost = cost
            }
        }
        simulator.placeAxes(best.axis1, best.axis2)
    }
}

object SkyWatcherTestSupport {
    val boulder = MountTestSupport.boulder
    val start = MountTestSupport.start

    // J2000 places of stars; their position at `start` (2026-10-18 04:00 UTC, local sidereal time 22.76 h) in Boulder.
    val vega = EquatorialCoordinate.fromRightAscensionHours(18.6156, 38.7837)       // hour angle +4.1 h, altitude 43, west
    val mirfak = EquatorialCoordinate.fromRightAscensionHours(3.4054, 49.8612)      // -4.7 h, altitude 41, north-east
    val enif = EquatorialCoordinate.fromRightAscensionHours(21.7364, 9.875)         // +1.0 h, altitude 57, south-west
    val deneb = MountTestSupport.deneb                                              // +2.1 h, altitude 67
    val altair = MountTestSupport.altair                                            // +2.9 h, altitude 40
    val andromeda = MountTestSupport.andromeda                                      // -1.9 h, altitude 68
    val alpheratz = EquatorialCoordinate.fromRightAscensionHours(0.1398, 29.0905)   // -1.4 h, altitude 70
    val markab = EquatorialCoordinate.fromRightAscensionHours(23.0794, 15.2053)     // -0.3 h, altitude 65, on the meridian
    val hamal = EquatorialCoordinate.fromRightAscensionHours(2.1196, 23.4624)       // -3.4 h, altitude 45, east
    val albireo = EquatorialCoordinate.fromRightAscensionHours(19.512, 27.96)       // +3.3 h, altitude 48
    val capella = EquatorialCoordinate.fromRightAscensionHours(5.2782, 45.998)      // -6.5 h, altitude 23, low in the north-east
    val rasalhague = EquatorialCoordinate.fromRightAscensionHours(17.582, 12.56)    // +5.2 h, altitude 17, low in the west
    val arcturus = EquatorialCoordinate.fromRightAscensionHours(14.261, 19.182)     // below the horizon

    val boulderLocation: GeographicLocation = boulder.location

    /** Sydney: a southern observer. At `start` (local sidereal time 15.86 h) the stars below are up. */
    val sydney = Observer(GeographicLocation(latitude = -33.8688, longitude = 151.2093, elevation = 20.0))
    val acrux = EquatorialCoordinate.fromRightAscensionHours(12.4433, -63.0991)     // +3.4 h, altitude 47
    val nunki = EquatorialCoordinate.fromRightAscensionHours(18.9211, -26.2967)     // -3.1 h, altitude 50
    val peacock = EquatorialCoordinate.fromRightAscensionHours(20.4275, -56.7351)   // -4.6 h, altitude 39
    val spica = EquatorialCoordinate.fromRightAscensionHours(13.4199, -11.1613)     // +2.4 h, altitude 50
    val kaus = EquatorialCoordinate.fromRightAscensionHours(18.4029, -34.3846)      // -2.5 h, altitude 59
    val shaula = EquatorialCoordinate.fromRightAscensionHours(17.5601, -37.1038)    // -1.7 h, altitude 69
    val betaCentauri = EquatorialCoordinate.fromRightAscensionHours(14.0637, -60.373) // +1.8 h, altitude 58
    val arcturusSouth = EquatorialCoordinate.fromRightAscensionHours(14.261, 19.182) // +1.6 h, altitude 32
    val alnair = EquatorialCoordinate.fromRightAscensionHours(22.1372, -46.961)     // -6.3 h, altitude 21

    /** Errors of an imperfectly set up equatorial mount. */
    val equatorialErrors = SkyWatcherPointingErrors(
        axis1Index = 1.3, axis2Index = -0.8, baseTiltX = 0.4, baseTiltY = -0.3, orthogonality = 0.15, cone = -0.1,
    )

    /** Errors of an imperfectly set up alt-azimuth mount. */
    val altAzimuthErrors = SkyWatcherPointingErrors(
        axis1Index = 2.0, axis2Index = 1.1, baseTiltX = 0.5, baseTiltY = -0.4, orthogonality = 0.1, cone = 0.12,
    )

    fun rig(
        mode: SkyWatcherMountMode = SkyWatcherMountMode.ALT_AZIMUTH,
        errors: SkyWatcherPointingErrors = SkyWatcherPointingErrors(),
        settings: MountSettings = MountSettings.standard,
        configuration: SkyWatcherConfiguration? = null,
        observer: Observer = boulder,
        location: GeographicLocation = observer.location,
        simulatorLocation: GeographicLocation = location,
        version: SkyWatcherVersion? = null,
        latencySeconds: Double = 0.05,
        axis1Inverted: Boolean = false,
        axis2Inverted: Boolean = false,
        chunkSize: Int = 0,
        configure: (SkyWatcherSimulator) -> Unit = {},
    ): SkyWatcherRig {
        val clock = ManualMountClock(start)
        val simulator = SkyWatcherSimulator(
            clock = clock,
            location = simulatorLocation,
            mode = mode,
            errors = errors,
            version = version ?: if (mode == SkyWatcherMountMode.ALT_AZIMUTH) SkyWatcherVersion(3, 0x22, 0xA5) else SkyWatcherVersion(3, 0x40, 0x0C),
            latencySeconds = latencySeconds,
            axis1Inverted = axis1Inverted,
            axis2Inverted = axis2Inverted,
            chunkSize = chunkSize,
        )
        configure(simulator)
        val driverConfiguration = configuration ?: SkyWatcherConfiguration(
            location = location, mode = mode, invertsAxis1 = axis1Inverted, invertsAxis2 = axis2Inverted,
        )
        val driver = SkyWatcherDriver(simulator, clock, driverConfiguration)
        val controller = MountController(driver, observer, settings, clock)
        return SkyWatcherRig(clock, simulator, driver, controller)
    }

    /** Polls once a second for [seconds] (what the apps' background worker does). */
    fun poll(rig: SkyWatcherRig, seconds: Int) {
        for (second in 0 until seconds) {
            rig.controller.poll()
            rig.clock.advance(1.0)
        }
    }
}
