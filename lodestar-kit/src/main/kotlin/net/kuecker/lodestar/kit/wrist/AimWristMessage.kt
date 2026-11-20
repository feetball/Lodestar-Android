package net.kuecker.lodestar.kit.wrist

import net.kuecker.lodestar.kit.foundation.*
import net.kuecker.lodestar.kit.pointing.Guidance
import net.kuecker.lodestar.kit.pointing.GuidanceSpeech
import net.kuecker.lodestar.kit.pointing.GuidanceState
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import kotlin.math.abs
import kotlin.math.atan2

/**
 * What the phone sends the watch's Aim page while it aims: the pan and tilt still to make, how far away the target is
 * and a few flags, a few dozen bytes about twice a second. The iOS app sends the same values as a WatchConnectivity
 * dictionary (`AimWristMessage` in the iOS repository's `WidgetShared/Core`); here they travel as one line of text
 * (see [encode]) in a Wear OS data layer message at [PATH].
 *
 * Decoding is defensive: a message that is not an aim message, or holds a value that is not finite or outside its
 * range, is dropped. The words the watch shows ([moves], [distanceText], [spokenSummary]) are the iOS ones.
 */
data class AimWristMessage(
    /** When the phone sent it, seconds since 1970 on the phone's clock; grows with every message, so it orders them. */
    val sent: Double,
    /** Degrees to pan to centre the target; positive means right. */
    val pan: Double,
    /** Degrees to tilt; positive means up. */
    val tilt: Double,
    /** Angle between the camera and the target, degrees. */
    val separation: Double,
    val state: GuidanceState,
    val isBelowHorizon: Boolean,
    /** Within a few degrees of the zenith, where azimuth (the pan) is a poor guide. */
    val isNearZenith: Boolean,
    /** Whether the phone is in night mode, so the watch dims to red as well. */
    val isNight: Boolean,
    val targetName: String,
) {
    /**
     * Direction of the shortest way to the target on the head's axes, radians counter-clockwise from "pan right": up
     * is tilting up. Near the zenith the pan is left out, as the spoken guidance does.
     */
    val arrowAngle: Double
        get() {
            val horizontal = if (isNearZenith && wholeTilt >= 1) 0.0 else pan
            return atan2(tilt, horizontal)
        }

    val isOnTarget: Boolean get() = state == GuidanceState.ON_TARGET && !isBelowHorizon

    /**
     * The moves to make, in whole degrees: "Pan right 12°", "Tilt up 3°". A move that rounds to nothing is left out;
     * within 2° of the target, or with nothing to move, "Almost there" stands in.
     */
    val moves: List<String>
        get() {
            if (separation < GuidanceSpeech.almostThereSeparation) return listOf("Almost there")
            val lines = mutableListOf<String>()
            if (wholePan >= 1 && !(isNearZenith && wholeTilt >= 1)) {
                lines.add("Pan ${if (pan > 0) "right" else "left"} $wholePan°")
            }
            if (wholeTilt >= 1) {
                lines.add("Tilt ${if (tilt > 0) "up" else "down"} $wholeTilt°")
            }
            return lines.ifEmpty { listOf("Almost there") }
        }

    /** "26° away", with a decimal below 10°. */
    val distanceText: String
        get() = if (separation < 10) {
            "${BigDecimal(separation).setScale(1, RoundingMode.HALF_EVEN).toPlainString()}° away"
        } else {
            "${separation.roundedHalfAwayFromZero().toInt()}° away"
        }

    /** The whole screen for TalkBack: "Pan right 12°, Tilt up 3°, 12° away, Orion Nebula". */
    val spokenSummary: String
        get() {
            if (isBelowHorizon) return "Target is below the horizon"
            if (isOnTarget) return if (targetName.isEmpty()) "On target" else "On target, $targetName"
            val parts = moves + distanceText + (if (targetName.isEmpty()) emptyList() else listOf(targetName))
            return parts.joinToString(", ")
        }

    private val wholePan: Int get() = abs(pan).roundedHalfAwayFromZero().toInt()
    private val wholeTilt: Int get() = abs(tilt).roundedHalfAwayFromZero().toInt()

    /**
     * The message as UTF-8 text: `LA1|sent|pan|tilt|separation|state|below|zenith|night|target`, the flags as 0 or 1.
     * The target name comes last, so it may hold any character, and is cut to [MAXIMUM_NAME_LENGTH] characters.
     */
    fun encode(): ByteArray =
        listOf(
            TAG, sent, pan, tilt, separation, state.rawValue, flag(isBelowHorizon), flag(isNearZenith), flag(isNight),
            targetName.take(MAXIMUM_NAME_LENGTH),
        ).joinToString("|").toByteArray(Charsets.UTF_8)

    companion object {
        /** The data layer path messages are sent on. */
        const val PATH = "/lodestar/aim"

        /** The capability the watch app declares, so the phone knows which watches can show the page. */
        const val CAPABILITY = "lodestar_wear_aim"

        /** The longest target name kept, in characters. */
        const val MAXIMUM_NAME_LENGTH = 40

        private const val TAG = "LA1"
        private const val FIELD_COUNT = 10

        /** The message for [guidance], sent at [sent]. */
        fun of(guidance: Guidance, targetName: String, isNight: Boolean, sent: Instant): AimWristMessage =
            AimWristMessage(
                sent = sent.epochSecond + sent.nano / 1e9,
                pan = guidance.panDelta,
                tilt = guidance.tiltDelta,
                separation = guidance.separation,
                state = guidance.state,
                isBelowHorizon = guidance.targetBelowHorizon,
                isNearZenith = guidance.isNearZenith,
                isNight = isNight,
                targetName = targetName.take(MAXIMUM_NAME_LENGTH),
            )

        /** The message in [bytes], or null if it is not a valid aim message. */
        fun decode(bytes: ByteArray): AimWristMessage? {
            val fields = String(bytes, Charsets.UTF_8).split('|', limit = FIELD_COUNT)
            if (fields.size != FIELD_COUNT || fields[0] != TAG) return null
            val sent = finite(fields[1]) ?: return null
            val pan = finite(fields[2])?.takeIf { abs(it) <= 180 } ?: return null
            val tilt = finite(fields[3])?.takeIf { abs(it) <= 180 } ?: return null
            val separation = finite(fields[4])?.takeIf { it in 0.0..180.0 } ?: return null
            val state = GuidanceState.entries.firstOrNull { it.rawValue == fields[5] } ?: return null
            return AimWristMessage(
                sent = sent, pan = pan, tilt = tilt, separation = separation, state = state,
                isBelowHorizon = fields[6] == "1", isNearZenith = fields[7] == "1", isNight = fields[8] == "1",
                targetName = fields[9].take(MAXIMUM_NAME_LENGTH),
            )
        }

        private fun finite(text: String): Double? = text.toDoubleOrNull()?.takeIf { it.isFinite() }

        private fun flag(value: Boolean): String = if (value) "1" else "0"
    }
}
