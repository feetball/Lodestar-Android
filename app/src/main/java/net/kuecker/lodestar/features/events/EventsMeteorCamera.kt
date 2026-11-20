package net.kuecker.lodestar.features.events

import net.kuecker.lodestar.kit.catalog.CelestialObject
import net.kuecker.lodestar.kit.events.AstronomicalEvent
import net.kuecker.lodestar.kit.events.MeteorAimAdvice
import net.kuecker.lodestar.kit.events.MeteorCameraAdvice
import net.kuecker.lodestar.kit.events.MeteorShower
import net.kuecker.lodestar.kit.foundation.SkyFrame
import net.kuecker.lodestar.kit.foundation.roundedHalfAwayFromZero
import net.kuecker.lodestar.kit.formatting.AngleFormatter
import net.kuecker.lodestar.kit.phototools.LensSetup
import net.kuecker.lodestar.kit.planning.NightPlanner
import net.kuecker.lodestar.kit.planning.summary
import net.kuecker.lodestar.model.ObserverContext
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** Where the meteor-shower camera card stands: working, or what the night allows. */
internal sealed interface MeteorCameraState {
    /** The advice is being worked out. */
    data object Loading : MeteorCameraState

    /** The Sun does not get 18° below the horizon that night. */
    data object NoDarkness : MeteorCameraState

    /** The radiant stays too far below the horizon the whole dark. */
    data object RadiantBelowHorizon : MeteorCameraState

    /** The advice for [plan]. */
    data class Ready(val plan: MeteorCameraPlan) : MeteorCameraState
}

/**
 * The camera advice for one night of a shower.
 *
 * @property time The moment it is for: the instant of the night's darkness with the radiant highest.
 * @property advice Where to point the camera at [time].
 * @property radiantAltitude The radiant's geometric altitude at [time], in degrees.
 */
internal data class MeteorCameraPlan(val time: Instant, val advice: MeteorAimAdvice, val radiantAltitude: Double)

/** The numbers and words of the "Camera" card of a meteor shower. Pure, so the astronomy runs off the main thread. */
internal object EventsMeteorCamera {
    /** The one tip under the advice. */
    const val TIP = "Use your widest lens, the NPF exposure, continuous shooting; keep the radiant in or near the frame edge."

    /** The note under the numbers: the count is an estimate for a particular kind of camera. */
    const val ESTIMATE_NOTE =
        "A planning estimate for a fast, wide lens, which records meteors to about magnitude 2. Fireballs and a " +
            "longer exposure add more."

    /**
     * The advice for the night of [event]: the night that holds its best time (or the peak), the moment of that night's
     * darkness with the radiant highest, the best direction then. Works out a night of twilight and a few dozen sky
     * frames: run it off the main thread.
     */
    fun state(event: AstronomicalEvent, shower: MeteorShower, context: ObserverContext): MeteorCameraState {
        val reference = event.visibility?.bestTime ?: event.date
        val zone = context.timeZone
        val summary = NightPlanner.summary(nightStart(reference, zone), context.observer, zone)
        val time = MeteorCameraAdvice.bestTimeTonight(shower, summary) ?: return MeteorCameraState.NoDarkness
        val advice = MeteorCameraAdvice.bestAim(shower, context.observer, time) ?: return MeteorCameraState.RadiantBelowHorizon
        val radiant = SkyFrame(time, context.observer).horizontal(shower.radiant(time), null, false)
        return MeteorCameraState.Ready(MeteorCameraPlan(time, advice, radiant.altitude))
    }

    /**
     * An instant on the local date the night holding [instant] starts on: that night runs from local noon to local noon,
     * so before noon it is the previous date's. The result is local noon of that date.
     */
    fun nightStart(instant: Instant, zone: ZoneId): Instant {
        val local = instant.atZone(zone)
        val date = if (local.toLocalTime() < LocalTime.NOON) local.toLocalDate().minusDays(1) else local.toLocalDate()
        return date.atTime(LocalTime.NOON).atZone(zone).toInstant()
    }

    /** The meteors an hour expected in the frame of [lens] at the plan's time. */
    fun expectedPerHour(plan: MeteorCameraPlan, shower: MeteorShower, lens: LensSetup): Double =
        MeteorCameraAdvice.expectedInFrame(MeteorCameraAdvice.observedHourlyRate(shower, plan.radiantAltitude), shower, lens)

    /** The custom target "Perseids camera field" at the advised place, to aim the camera at. */
    fun target(shower: MeteorShower, advice: MeteorAimAdvice): CelestialObject =
        CelestialObject.custom("${shower.name} camera field", advice.aimPoint)

    /** "Point the camera at azimuth 210° (SSW), altitude 55°, 40° from the radiant." */
    fun aimText(advice: MeteorAimAdvice): String {
        val point = AngleFormatter.compassPoint(advice.azimuth).abbreviation
        return "Point the camera at azimuth ${degrees(advice.azimuth)} ($point), altitude ${degrees(advice.altitude)}, " +
            "${degrees(advice.separationFromRadiant)} from the radiant."
    }

    /**
     * What to expect in the frame, [lensText] naming the camera: "Expect about one meteor every 1.6 hours in your frame
     * at 24 mm f/2.8 · Full Frame." The count is [perHour] meteors an hour; with the radiant at or below the horizon
     * ([radiantAltitude]) none are expected.
     */
    fun countText(perHour: Double, radiantAltitude: Double, lensText: String): String {
        if (!(radiantAltitude > 0) || !(perHour > 0)) return "The radiant is below the horizon then, so expect no meteors in your frame."
        val where = "in your frame at $lensText."
        val minutes = 60 / perHour
        return when {
            perHour >= 6 -> "Expect about ${perHour.roundedHalfAwayFromZero().toInt()} meteors an hour $where"
            perHour >= 2 -> "Expect a few meteors an hour $where"
            minutes <= 90 ->
                "Expect about one meteor every ${maxOf(5, (minutes / 5).roundedHalfAwayFromZero().toInt() * 5)} minutes $where"
            minutes > 600 -> "Expect fewer than one meteor in 10 hours $where"
            else -> {
                val hours = (minutes / 6).roundedHalfAwayFromZero() / 10
                "Expect about one meteor every ${oneDecimal(hours)} hours $where"
            }
        }
    }

    /** "24 mm f/2.8 · Full Frame". */
    fun lensText(lens: LensSetup): String =
        "${oneDecimal(lens.focalLengthMillimeters)} mm f/${oneDecimal(lens.aperture)} · ${lens.sensor.name}"

    private fun degrees(value: Double): String = AngleFormatter.formatDegrees(value, 0)

    /** A number with at most one decimal, such as "24" or "2.8". */
    private fun oneDecimal(value: Double): String =
        NumberFormat.getNumberInstance().apply {
            minimumFractionDigits = 0
            maximumFractionDigits = 1
        }.format(value)
}
