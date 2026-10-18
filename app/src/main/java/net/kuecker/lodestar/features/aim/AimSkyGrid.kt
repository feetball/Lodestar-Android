package net.kuecker.lodestar.features.aim

import net.kuecker.lodestar.kit.formatting.AngleFormatter
import net.kuecker.lodestar.kit.formatting.CompassPoint
import net.kuecker.lodestar.kit.foundation.HorizontalCoordinate
import net.kuecker.lodestar.kit.foundation.Vector3

/**
 * The fixed lines of the horizon frame (altitude circles, azimuth lines, the horizon, the compass points) as ENU
 * directions, made once. Their label texts are made once too, so a frame builds no strings.
 */
object AimSkyGrid {
    class AltitudeCircle(val altitude: Double, val points: List<Vector3>) {
        /** "−30°" */
        val label: String = AngleFormatter.formatDegrees(altitude, decimals = 0)
    }

    class CompassLabel(
        val text: String,
        val direction: Vector3,
        /** `true` for N, E, S and W. */
        val isCardinal: Boolean,
    )

    /** A mark on the horizon every 5°; every second one is a major tick (10°). */
    class HorizonTick(val base: Vector3, val isMajor: Boolean)

    /** A major tick that is labelled with its azimuth ("10°", "20°", … but not the compass points). */
    class AzimuthLabel(val base: Vector3, val text: String)

    /** Altitude circles every 10° from −80° to 80°, except the horizon. */
    val altitudeCircles: List<AltitudeCircle> = (-8..8).filter { it != 0 }.map { step ->
        val altitude = step * 10.0
        AltitudeCircle(altitude, (0 until 180).map { HorizontalCoordinate(it * 2.0, altitude).unitVector })
    }

    /** Azimuth lines every 10°, from the nadir over the horizon to the zenith. */
    val azimuthLines: List<List<Vector3>> = (0 until 36).map { line ->
        (0..36).map { HorizontalCoordinate(line * 10.0, -90.0 + it * 5.0).unitVector }
    }

    val horizon: List<Vector3> = (0 until 180).map { HorizontalCoordinate(it * 2.0, 0.0).unitVector }

    /** Ticks every 5° along the horizon; every second one is a major tick (10°). */
    val horizonTicks: List<HorizonTick> = (0 until 72).map { index ->
        HorizonTick(HorizontalCoordinate(index * 5.0, 0.0).unitVector, index % 2 == 0)
    }

    /** Degrees of azimuth at the major ticks, except at the four compass points (every 18th tick). */
    val azimuthLabels: List<AzimuthLabel> = horizonTicks.withIndex()
        .filter { (index, tick) -> tick.isMajor && index % 18 != 0 }
        .map { (index, tick) -> AzimuthLabel(tick.base, AngleFormatter.formatDegrees(index * 5.0, decimals = 0)) }

    /** N, NE, E, SE, S, SW, W, NW on the horizon. */
    val compassLabels: List<CompassLabel> = CompassPoint.entries.filter { it.rawValue % 2 == 0 }.map { point ->
        CompassLabel(
            text = point.abbreviation,
            direction = HorizontalCoordinate(point.centerAzimuth, 0.0).unitVector,
            isCardinal = point.rawValue % 4 == 0,
        )
    }
}
