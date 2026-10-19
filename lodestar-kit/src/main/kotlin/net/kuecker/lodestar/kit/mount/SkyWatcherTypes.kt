package net.kuecker.lodestar.kit.mount

import net.kuecker.lodestar.kit.foundation.GeographicLocation

/** The two motor channels of a Sky-Watcher motor controller (`*1` / `*2` in the command set). */
enum class SkyWatcherAxis(
    /** The stable string used for storage. */
    val rawValue: String,
    /** The channel character sent on the wire. */
    val channel: Char,
) {
    /** Channel "1": right ascension (equatorial mounts) or azimuth (alt-azimuth mounts). */
    AXIS1("axis1", '1'),

    /** Channel "2": declination (equatorial mounts) or altitude (alt-azimuth mounts). */
    AXIS2("axis2", '2');

    /** 0 for [AXIS1], 1 for [AXIS2]. */
    val index: Int get() = if (this == AXIS1) 0 else 1

    companion object {
        /** The axis with the given raw value, or null if there is none. */
        fun fromRawValue(raw: String): SkyWatcherAxis? = entries.firstOrNull { it.rawValue == raw }
    }
}

/** How the two axes are arranged: which kind of pointing model applies. */
enum class SkyWatcherMountMode(
    /** The stable string used for storage. */
    val rawValue: String,
) {
    /** Hour-angle / declination axes on a polar axis (Star Adventurer GTi, EQ mounts, an AZ-GTi on a wedge). */
    EQUATORIAL("equatorial"),

    /** Azimuth / altitude axes on a level base (AZ-GTi, AZ-GTe and other alt-azimuth mounts). */
    ALT_AZIMUTH("altAzimuth");

    companion object {
        /** The mode with the given raw value, or null if there is none. */
        fun fromRawValue(raw: String): SkyWatcherMountMode? = entries.firstOrNull { it.rawValue == raw }
    }
}

/**
 * Which side of the mount the telescope is on, named as ASCOM names the pier sides. On an equatorial mount [EAST] is the
 * normal pointing state (counterweight down, telescope looking west, declination axis angle 0° to 180°) and [WEST] the
 * state "through the pole" (looking east, declination axis angle -180° to 0°). On an alt-azimuth mount [EAST] is the
 * normal state (altitude axis -90° to 90°) and [WEST] means over the top (beyond the zenith).
 */
enum class SkyWatcherPierSide(
    /** The stable string used for storage. */
    val rawValue: String,
) {
    /** Normal pointing state. */
    EAST("east"),

    /** Through-the-pole (equatorial) or over-the-top (alt-azimuth) state. */
    WEST("west");

    companion object {
        /** The side with the given raw value, or null if there is none. */
        fun fromRawValue(raw: String): SkyWatcherPierSide? = entries.firstOrNull { it.rawValue == raw }
    }
}

/** How the driver keeps the telescope on the sky while tracking. */
enum class SkyWatcherTrackingStrategy(
    /** The stable string used for storage. */
    val rawValue: String,
) {
    /** The right ascension axis runs at the sidereal rate on its own; the declination axis stands still (equatorial mounts). */
    SIDEREAL("sidereal"),

    /**
     * Both axes get new rates computed from the target's motion about once a second (alt-azimuth mounts, which have no
     * tracking of their own); the app must keep polling.
     */
    SERVO("servo");

    companion object {
        /** The strategy with the given raw value, or null if there is none. */
        fun fromRawValue(raw: String): SkyWatcherTrackingStrategy? = entries.firstOrNull { it.rawValue == raw }
    }
}

/** The arrangements a mount model supports. */
enum class SkyWatcherMountClass(
    /** The stable string used for storage. */
    val rawValue: String,
) {
    /** Equatorial mounts. */
    EQUATORIAL_ONLY("equatorialOnly"),

    /** Alt-azimuth mounts. */
    ALT_AZIMUTH_ONLY("altAzimuthOnly"),

    /** Mounts that can be used either way (AZ-GTi on a wedge, Star Adventurer GTi, AZ-EQ and Wave mounts). */
    EITHER("either");

    companion object {
        /** The class with the given raw value, or null if there is none. */
        fun fromRawValue(raw: String): SkyWatcherMountClass? = entries.firstOrNull { it.rawValue == raw }
    }
}

/** The motion modes of the `G` command (first data digit): what the next `J` (start) does. */
enum class SkyWatcherMotionMode(
    /** The character sent on the wire. */
    val code: Char,
) {
    /** High-speed GOTO: moves by the goto increment with acceleration and a brake point. */
    GOTO_FAST('0'),

    /** Speed ("tracking") mode at low speed: runs at the step period until stopped. */
    TRACK_SLOW('1'),

    /** Low-speed GOTO: moves by the goto increment at a constant slow speed. */
    GOTO_SLOW('2'),

    /** Speed mode at high speed: the step period is multiplied by the controller's high-speed ratio. */
    TRACK_FAST('3');

    /** True for the two GOTO modes. */
    val isGoto: Boolean get() = this == GOTO_FAST || this == GOTO_SLOW

    /** True for the two high-speed modes. */
    val isFast: Boolean get() = this == GOTO_FAST || this == TRACK_FAST

    companion object {
        /** The mode sent as [code], or null if there is none. */
        fun fromCode(code: Char): SkyWatcherMotionMode? = entries.firstOrNull { it.code == code }
    }
}

/** The direction digit of the `G` command (second data digit). */
enum class SkyWatcherDirection(
    /** The character sent on the wire. */
    val code: Char,
) {
    /** The position count increases. */
    INCREASING('0'),

    /** The position count decreases. */
    DECREASING('1');

    companion object {
        /** The direction sent as [code], or null if there is none. */
        fun fromCode(code: Char): SkyWatcherDirection? = entries.firstOrNull { it.code == code }
    }
}

/** What the `f` command reports for one axis. */
data class SkyWatcherAxisStatus(
    /** True in speed ("tracking") mode, false in GOTO mode. */
    val isSpeedMode: Boolean,
    /** True when the axis turns counter-clockwise (the position count decreases). */
    val isDecreasing: Boolean,
    /** True in the high-speed variant of the mode. */
    val isFast: Boolean,
    /** True while the axis moves. */
    val isRunning: Boolean,
    /** True when the controller reports the axis as blocked. */
    val isBlocked: Boolean,
    /** True once the `F` command (initialization done) has been accepted. */
    val isInitialized: Boolean,
    /** True while the level (home) switch is on. */
    val isLevelSwitchOn: Boolean,
) {
    /** True while the axis executes a GOTO. */
    val isGoto: Boolean get() = isRunning && !isSpeedMode
}

/** The error codes of a `!` reply. */
enum class SkyWatcherErrorCode(
    /** The code sent after the `!`. */
    val code: Int,
    /** The documented name. */
    val title: String,
) {
    /** Code 0. */
    UNKNOWN_COMMAND(0, "unknown command"),

    /** Code 1. */
    COMMAND_LENGTH(1, "command length error"),

    /** Code 2: the command needs a stopped motor. */
    MOTOR_NOT_STOPPED(2, "motor not stopped"),

    /** Code 3. */
    INVALID_CHARACTER(3, "invalid character"),

    /** Code 4: the `F` command has not been sent since power-up. */
    NOT_INITIALIZED(4, "not initialized"),

    /** Code 5. */
    DRIVER_SLEEPING(5, "driver sleeping"),

    /** Code 7. */
    PEC_TRAINING_RUNNING(7, "PEC training is running"),

    /** Code 8. */
    NO_VALID_PEC_DATA(8, "no valid PEC data");

    companion object {
        /** The error with the given code, or null if the code is not documented (6 and 9 to 15). */
        fun fromCode(code: Int): SkyWatcherErrorCode? = entries.firstOrNull { it.code == code }
    }
}

/**
 * The reply to `:e` (motor board version): firmware version and mount model code.
 *
 * The six hex digits `AABBCC` are read as firmware `AA.BB` and mount code `CC` (INDI's `skywatcherAPI` reads the board
 * version the same way); the mount codes come from INDI's mount-type table.
 */
data class SkyWatcherVersion(
    /** The first byte of the version reply. */
    val major: Int,
    /** The second byte of the version reply. */
    val minor: Int,
    /** The mount model code (last byte of the reply). */
    val mountCode: Int,
) {
    /** The firmware as text: the major byte in decimal, the minor byte as two hex digits ("3.22"). */
    val description: String get() = "$major.${SkyWatcherMountCatalog.hex2(minor)}"

    /** The mount model's name. */
    val mountName: String get() = SkyWatcherMountCatalog.name(mountCode)

    /** The arrangements this model supports. */
    val mountClass: SkyWatcherMountClass get() = SkyWatcherMountCatalog.mountClass(mountCode)
}

/** Mount model codes of the motor controller (from INDI's `skywatcherAPI.h` and `skywatcher.cpp` mount-type tables). */
object SkyWatcherMountCatalog {
    /** The model name for a code ("Sky-Watcher motor controller" for codes that are not listed). */
    fun name(code: Int): String = when (code) {
        0x00 -> "EQ6"
        0x01 -> "HEQ5"
        0x02 -> "EQ5"
        0x03 -> "EQ3"
        0x04 -> "EQ8"
        0x05 -> "AZ-EQ6"
        0x06 -> "AZ-EQ5"
        0x0A -> "Star Adventurer"
        0x0C -> "Star Adventurer GTi"
        0x20 -> "EQ8-R Pro"
        0x22 -> "AZ-EQ6 Pro"
        0x23 -> "EQ6-R Pro"
        0x24 -> "EQ6 Pro"
        0x25 -> "CQ350 Pro"
        0x31 -> "EQ5 Pro"
        0x44 -> "Wave 100i"
        0x45 -> "Wave 150i"
        0x80 -> "GT"
        0x81 -> "MF"
        0x82 -> "114GT"
        0x90 -> "Dob"
        0xA2 -> "AZ-GTe"
        0xA5 -> "AZ-GTi"
        else -> "Sky-Watcher motor controller"
    }

    /** Which arrangements the model with this code supports (unknown codes: either). */
    fun mountClass(code: Int): SkyWatcherMountClass = when (code) {
        0x00, 0x01, 0x02, 0x03, 0x04, 0x0A, 0x20, 0x23, 0x24, 0x25, 0x31 -> SkyWatcherMountClass.EQUATORIAL_ONLY
        0x80, 0x81, 0x82, 0x90 -> SkyWatcherMountClass.ALT_AZIMUTH_ONLY
        else -> SkyWatcherMountClass.EITHER
    }

    /** The arrangement to assume for the model with this code: alt-azimuth for the AZ-GTi family and GT/MF/Dob mounts, equatorial otherwise. */
    fun defaultMode(code: Int): SkyWatcherMountMode = when (code) {
        0x80, 0x81, 0x82, 0x90, 0xA2, 0xA5 -> SkyWatcherMountMode.ALT_AZIMUTH
        else -> SkyWatcherMountMode.EQUATORIAL
    }

    internal fun hex2(value: Int): String {
        val digits = "0123456789ABCDEF"
        val v = value and 0xFF
        return "${digits[v shr 4]}${digits[v and 15]}"
    }
}

/**
 * Limits of what the driver lets the mount do. Values are clamped to their ranges when the limits are created.
 */
@ConsistentCopyVisibility
data class SkyWatcherLimits private constructor(
    /** Targets lower than this geometric altitude are refused ([MountError.MountHorizonLimit]); -20 to 60, default -10 so the controller's own altitude limit decides. */
    val minimumAltitudeDegrees: Double,
    /** Alt-azimuth only: targets higher than this are refused ([MountError.MountOverheadLimit]), and tracking stops above it; 60 to 90, default 88. */
    val maximumAltitudeDegrees: Double,
    /** Alt-azimuth only: the lowest azimuth axis angle (from the home position), the cable-wrap limit; -330 to 0, default -270. */
    val azimuthMinimumDegrees: Double,
    /** Alt-azimuth only: the highest azimuth axis angle; 0 to 330, default 270. */
    val azimuthMaximumDegrees: Double,
    /** Equatorial only: how far past the meridian (counterweight horizontal) the right ascension axis may turn; 0 to 30, default 5. */
    val meridianOverlapDegrees: Double,
    /** Equatorial only: slews may dip this far below the minimum altitude on the way (tripod clearance); 0 to 30, default 5. */
    val pathMarginDegrees: Double,
    /** The fastest an axis may be asked to turn while tracking or nudging; 0.1 to 4, default 3. */
    val maximumAxisRateDegreesPerSecond: Double,
) {
    companion object {
        /** The default limits. */
        val standard: SkyWatcherLimits = invoke()

        /** Creates limits; values are clamped to their ranges (NaN becomes the default). */
        operator fun invoke(
            minimumAltitudeDegrees: Double = -10.0,
            maximumAltitudeDegrees: Double = 88.0,
            azimuthMinimumDegrees: Double = -270.0,
            azimuthMaximumDegrees: Double = 270.0,
            meridianOverlapDegrees: Double = 5.0,
            pathMarginDegrees: Double = 5.0,
            maximumAxisRateDegreesPerSecond: Double = 3.0,
        ): SkyWatcherLimits = SkyWatcherLimits(
            minimumAltitudeDegrees = sane(minimumAltitudeDegrees, -10.0, -20.0, 60.0),
            maximumAltitudeDegrees = sane(maximumAltitudeDegrees, 88.0, 60.0, 90.0),
            azimuthMinimumDegrees = sane(azimuthMinimumDegrees, -270.0, -330.0, 0.0),
            azimuthMaximumDegrees = sane(azimuthMaximumDegrees, 270.0, 0.0, 330.0),
            meridianOverlapDegrees = sane(meridianOverlapDegrees, 5.0, 0.0, 30.0),
            pathMarginDegrees = sane(pathMarginDegrees, 5.0, 0.0, 30.0),
            maximumAxisRateDegreesPerSecond = sane(maximumAxisRateDegreesPerSecond, 3.0, 0.1, 4.0),
        )

        private fun sane(value: Double, default: Double, lower: Double, upper: Double): Double =
            if (value.isNaN()) default else value.coerceIn(lower, upper)
    }
}

/**
 * Settings of a [SkyWatcherDriver]. Values are clamped to their ranges when the configuration is created.
 */
@ConsistentCopyVisibility
data class SkyWatcherConfiguration private constructor(
    /** Where the mount stands; the pointing model needs the latitude and the sidereal time. [MountController.setSiteAndTime] replaces it. */
    val location: GeographicLocation,
    /** The mount's arrangement; null (the default) picks it from the mount model found at connect time. */
    val mode: SkyWatcherMountMode?,
    /** Set when the position count of axis 1 decreases while the axis turns in the positive direction (clockwise seen from above / towards the west). */
    val invertsAxis1: Boolean,
    /** Set when the position count of axis 2 decreases while the axis turns in the positive direction (up / towards a smaller declination). */
    val invertsAxis2: Boolean,
    /** How long one attempt waits for the mount's reply (0.2 to 5 s, default 0.6). */
    val exchangeTimeoutSeconds: Double,
    /** How often a command is sent before the exchange fails (1 to 6, default 3); UDP loses datagrams. */
    val attempts: Int,
    /** Axis, altitude and cable-wrap limits. */
    val limits: SkyWatcherLimits,
    /** How tracking is kept; null (the default) uses [SkyWatcherTrackingStrategy.SIDEREAL] for equatorial and [SkyWatcherTrackingStrategy.SERVO] for alt-azimuth mounts. */
    val trackingStrategy: SkyWatcherTrackingStrategy?,
    /** The least time between two rate updates of a servo tracker or a nudge (0.25 to 30 s, default 1). */
    val trackingUpdateIntervalSeconds: Double,
    /** Allow slews before any alignment star was synced (the model then assumes a perfect home position); default false. */
    val allowsGotoWithoutAlignment: Boolean,
    /** Send GOTO targets as absolute positions (`S`) instead of increments (`H`); default false (what INDI does). */
    val usesAbsoluteGotoTarget: Boolean,
    /** The slew speed the planner assumes when it predicts where a moving target will be on arrival (0.1 to 10 degrees per second, default 2.5). */
    val assumedSlewDegreesPerSecond: Double,
    /** A slew is repeated with a short correction while the mount stops more than this far from the target (0.002 to 0.5 degrees, default 0.02). */
    val refinementToleranceDegrees: Double,
    /** A sync that would leave a residual larger than this on any alignment star is refused (0.1 to 10 degrees, default 1). */
    val maximumAlignmentResidualDegrees: Double,
) {
    companion object {
        /** Creates a configuration; out-of-range values are clamped (NaN becomes the default). */
        operator fun invoke(
            location: GeographicLocation,
            mode: SkyWatcherMountMode? = null,
            invertsAxis1: Boolean = false,
            invertsAxis2: Boolean = false,
            exchangeTimeoutSeconds: Double = 0.6,
            attempts: Int = 3,
            limits: SkyWatcherLimits = SkyWatcherLimits.standard,
            trackingStrategy: SkyWatcherTrackingStrategy? = null,
            trackingUpdateIntervalSeconds: Double = 1.0,
            allowsGotoWithoutAlignment: Boolean = false,
            usesAbsoluteGotoTarget: Boolean = false,
            assumedSlewDegreesPerSecond: Double = 2.5,
            refinementToleranceDegrees: Double = 0.02,
            maximumAlignmentResidualDegrees: Double = 1.0,
        ): SkyWatcherConfiguration = SkyWatcherConfiguration(
            location = location,
            mode = mode,
            invertsAxis1 = invertsAxis1,
            invertsAxis2 = invertsAxis2,
            exchangeTimeoutSeconds = sane(exchangeTimeoutSeconds, 0.6, 0.2, 5.0),
            attempts = attempts.coerceIn(1, 6),
            limits = limits,
            trackingStrategy = trackingStrategy,
            trackingUpdateIntervalSeconds = sane(trackingUpdateIntervalSeconds, 1.0, 0.25, 30.0),
            allowsGotoWithoutAlignment = allowsGotoWithoutAlignment,
            usesAbsoluteGotoTarget = usesAbsoluteGotoTarget,
            assumedSlewDegreesPerSecond = sane(assumedSlewDegreesPerSecond, 2.5, 0.1, 10.0),
            refinementToleranceDegrees = sane(refinementToleranceDegrees, 0.02, 0.002, 0.5),
            maximumAlignmentResidualDegrees = sane(maximumAlignmentResidualDegrees, 1.0, 0.1, 10.0),
        )

        private fun sane(value: Double, default: Double, lower: Double, upper: Double): Double =
            if (value.isNaN()) default else value.coerceIn(lower, upper)
    }
}
