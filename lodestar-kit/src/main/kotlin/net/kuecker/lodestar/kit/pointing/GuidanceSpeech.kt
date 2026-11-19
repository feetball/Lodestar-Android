package net.kuecker.lodestar.kit.pointing

import net.kuecker.lodestar.kit.foundation.*
import java.time.Instant
import kotlin.math.abs

/** What a spoken phrase was about; [GuidanceSpeech] uses it to decide what is worth saying next. */
enum class SpokenKind(
    /** The stable string for this kind. */
    val rawValue: String,
) {
    /** The pan and tilt to make. */
    GUIDANCE("guidance"),

    /** Within [GuidanceSpeech.almostThereSeparation] of the target but not on it yet. */
    ALMOST_THERE("almostThere"),

    /** The camera is on target. */
    ON_TARGET("onTarget"),

    /** The target is below the horizon. */
    BELOW_HORIZON("belowHorizon");

    companion object {
        /** The kind with the given raw value, or null if there is none. */
        fun fromRawValue(raw: String): SpokenKind? = entries.firstOrNull { it.rawValue == raw }
    }
}

/**
 * What was last spoken: the timing and the values [GuidanceSpeech.phrase] compares the next guidance with. Keep the
 * state of the last phrase that was actually spoken, pass it back as `previous`, and drop it (use null) when a
 * session ends or the target changes.
 *
 * Values are produced by [GuidanceSpeech.phrase]; the constructor is internal.
 */
@ConsistentCopyVisibility
data class SpokenState internal constructor(
    /** What the phrase was about. */
    val kind: SpokenKind,
    /** The pan change (degrees, positive to the right) of the guidance it was spoken for. */
    val pan: Double,
    /** The tilt change (degrees, positive up) of the guidance it was spoken for. */
    val tilt: Double,
    /** When it was spoken. */
    val time: Instant,
    /** Whether the word "degrees" has been said in this session. */
    val hasSaidDegrees: Boolean,
)

/** A phrase to speak and the state to remember once it has been spoken. */
data class SpokenPhrase(
    /** The words, ready for a speech synthesiser. */
    val text: String,
    /** Pass this as `previous` to the next [GuidanceSpeech.phrase] call if the phrase was spoken. */
    val state: SpokenState,
)

/**
 * The words of spoken aiming guidance, shared by both apps so that they say the same things. A pure function: the
 * timing state travels in the value it returns, so nothing here depends on a clock or a speech engine.
 *
 * - Off target: "Pan right 12, tilt up 3", whole degrees, the word "degrees" only in the first phrase with numbers
 *   of a session. A component that rounds to 0° is left out; within 5° of the zenith, where azimuth is a poor
 *   guide (`Guidance.isNearZenith`), the pan is left out while there is a tilt to make.
 * - Within [almostThereSeparation] of the target but not on it: "Almost there".
 * - On target: "On target", once; then silence until the camera has drifted off (the tracker's hysteresis).
 * - Target below the horizon: "Target is below the horizon", once; nothing else is said while it is.
 * - A guidance phrase is repeated only when the pan or the tilt changed by [changeThreshold] degrees or more since
 *   the last one, or [repeatInterval] seconds have passed; so is "Almost there". A change of kind is spoken at once.
 *   "Almost there" is left again only at [almostThereSeparation] times [GuidanceTracker.hysteresisFactor], so a
 *   camera hovering around the limit does not chatter.
 *
 * Cost: a few comparisons and one short string per call.
 */
object GuidanceSpeech {
    /** Separation in degrees below which "Almost there" is said instead of the pan and tilt. */
    const val almostThereSeparation: Double = 2.0

    /** The change in pan or tilt, in degrees, that makes the guidance worth repeating. */
    const val changeThreshold: Double = 1.0

    /** Seconds after which the guidance is repeated even if it has not changed. */
    const val repeatInterval: Double = 5.0

    /**
     * What to say now for [current], or null for silence. [previous] is the state of the last phrase spoken in this
     * session (null at the start); [now] is the time of this call. The returned [SpokenPhrase.state] describes the
     * phrase once it has been spoken: do not pass it back if the phrase was dropped. Null also when [current] holds
     * a value that is not finite.
     */
    fun phrase(current: Guidance, previous: SpokenState?, now: Instant): SpokenPhrase? {
        if (!(current.separation.isFinite() && current.panDelta.isFinite() && current.tiltDelta.isFinite())) return null
        val saidDegrees = previous?.hasSaidDegrees ?: false
        // A clock that went backwards counts as a long time: the remembered moment is no use.
        val isDue = previous == null || now.timeIntervalSince(previous.time).let { it < 0 || it >= repeatInterval }

        if (current.targetBelowHorizon) {
            if (previous?.kind == SpokenKind.BELOW_HORIZON) return null
            return spoken("Target is below the horizon", SpokenKind.BELOW_HORIZON, current, now, saidDegrees)
        }
        if (current.state == GuidanceState.ON_TARGET) {
            if (previous?.kind == SpokenKind.ON_TARGET) return null
            return spoken("On target", SpokenKind.ON_TARGET, current, now, saidDegrees)
        }
        val almostLimit = if (previous?.kind == SpokenKind.ALMOST_THERE) {
            almostThereSeparation * GuidanceTracker.hysteresisFactor
        } else {
            almostThereSeparation
        }
        if (current.separation < almostLimit) {
            if (previous?.kind == SpokenKind.ALMOST_THERE && !isDue) return null
            return spoken("Almost there", SpokenKind.ALMOST_THERE, current, now, saidDegrees)
        }

        val panWhole = abs(current.panDelta).roundedHalfAwayFromZero()
        val tiltWhole = abs(current.tiltDelta).roundedHalfAwayFromZero()
        val includeTilt = tiltWhole >= 1
        val includePan = panWhole >= 1 && !(current.isNearZenith && includeTilt)
        if (!includePan && !includeTilt) return null
        if (previous != null && previous.kind == SpokenKind.GUIDANCE && !isDue) {
            val panChanged = includePan &&
                abs(AstroMath.normalizeSignedDegrees(current.panDelta - previous.pan)) >= changeThreshold
            val tiltChanged = includeTilt && abs(current.tiltDelta - previous.tilt) >= changeThreshold
            if (!panChanged && !tiltChanged) return null
        }

        var text = ""
        var lastWhole = 0
        if (includePan) {
            lastWhole = panWhole.toInt()
            text = "Pan ${if (current.panDelta > 0) "right" else "left"} $lastWhole"
        }
        if (includeTilt) {
            lastWhole = tiltWhole.toInt()
            val words = "${if (current.tiltDelta > 0) "up" else "down"} $lastWhole"
            text = if (includePan) "$text, tilt $words" else "Tilt $words"
        }
        if (!saidDegrees) text += if (lastWhole == 1) " degree" else " degrees"
        return spoken(text, SpokenKind.GUIDANCE, current, now, hasSaidDegrees = true)
    }

    private fun spoken(
        text: String,
        kind: SpokenKind,
        current: Guidance,
        now: Instant,
        hasSaidDegrees: Boolean,
    ): SpokenPhrase =
        SpokenPhrase(text, SpokenState(kind, current.panDelta, current.tiltDelta, now, hasSaidDegrees))
}
