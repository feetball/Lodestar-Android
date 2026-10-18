package net.kuecker.lodestar.features.aim

import net.kuecker.lodestar.kit.foundation.AstroMath
import net.kuecker.lodestar.kit.foundation.HorizontalCoordinate
import net.kuecker.lodestar.kit.foundation.SkyFrame
import net.kuecker.lodestar.kit.foundation.Vector3
import net.kuecker.lodestar.kit.mosaic.MosaicGuidance
import net.kuecker.lodestar.kit.mosaic.MosaicOrientation
import net.kuecker.lodestar.kit.mosaic.MosaicPanelPointing
import net.kuecker.lodestar.kit.mosaic.MosaicPlan
import net.kuecker.lodestar.kit.pointing.PointingSolution
import java.time.Instant
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Where the camera is to point for the panel of a mosaic that is being shot, and how the picture has to be turned.
 * Guidance leads to [horizontal] instead of the target object while the engine holds one.
 *
 * @property key Tells panels apart: the guidance tracker starts afresh when it changes, and only then.
 * @property roll The turn of the picture that keeps the panel's edges aligned with the plan, see
 * [MosaicGuidance.roll].
 * @property isPortrait Whether the camera is turned a quarter turn: the top of the picture is then the sensor's side.
 * @property label The panel in words, for the target ring: "Panel 3 of 12".
 */
class AimPanelAim(
    val key: Int,
    val horizontal: HorizontalCoordinate,
    val roll: Double,
    val isPortrait: Boolean,
    val label: String,
) {
    /** The panel's centre as a unit vector. */
    val direction: Vector3 = horizontal.unitVector

    /**
     * How far the camera has to turn about its axis for the panel, degrees: positive clockwise as the photographer
     * sees it. A picture turned by half a turn covers the same sky, so the answer is the shorter way, within ±90°;
     * the same camera held either way up is right.
     */
    fun rollDifference(pointing: PointingSolution): Double =
        rollDifference(pointing.boresightVector, if (isPortrait) pointing.cameraRightVector else pointing.cameraUpVector)

    /**
     * [rollDifference] for a camera that looks along [boresight] with the top of the picture towards [pictureTop]
     * (the camera's up in landscape, its side in portrait).
     */
    fun rollDifference(boresight: Vector3, pictureTop: Vector3): Double {
        val camera = MosaicGuidance.roll(boresight, pictureTop)
        var difference = AstroMath.normalizeSignedDegrees(roll - camera)
        if (difference > 90) difference -= 180 else if (difference <= -90) difference += 180
        return difference
    }

    companion object {
        /** The keys of the panels of one plan are made from the plan's identity and the panel's index. */
        fun key(plan: MosaicPlan, index: Int): Int = 31 * System.identityHashCode(plan) + index
    }
}

/**
 * The panels of a plan as they stand in the sky at one instant, with the geometry the overlay draws from. Made off the
 * main thread about once a second (equatorial panels drift with the sky); immutable. Indexed like `plan.panels`.
 */
class AimMosaicPointings private constructor(
    val plan: MosaicPlan,
    val pointings: List<MosaicPanelPointing>,
    val date: Instant,
) {
    val panelCount: Int = plan.panels.size

    /** The orientation of the camera the plan was made for. */
    val isPortrait: Boolean = plan.orientation == MosaicOrientation.PORTRAIT

    /** Panel indices in shooting order (`plan.shootingOrder`). */
    val order: IntArray = IntArray(panelCount) { plan.shootingOrder[it] }

    /** The place of each panel in the shooting order. */
    val positions: IntArray = IntArray(panelCount) { plan.panels[it].shootingPosition }

    /** The corner directions of each panel (top left, top right, bottom right, bottom left), for stroking outlines. */
    val outlines: Array<List<Vector3>> = Array(panelCount) { index -> pointings[index].corners.map { it.unitVector } }

    /** The same corners packed as x, y, z, for clipping and filling. */
    val corners: Array<DoubleArray> = Array(panelCount) { index ->
        val packed = DoubleArray(12)
        for ((corner, vector) in outlines[index].withIndex()) {
            packed[3 * corner] = vector.x
            packed[3 * corner + 1] = vector.y
            packed[3 * corner + 2] = vector.z
        }
        packed
    }

    /** The centre direction of each panel, packed as x, y, z. */
    val centers: DoubleArray = DoubleArray(3 * panelCount).also { packed ->
        for (index in 0 until panelCount) {
            val vector = pointings[index].direction.unitVector
            packed[3 * index] = vector.x
            packed[3 * index + 1] = vector.y
            packed[3 * index + 2] = vector.z
        }
    }

    /** The angle from each panel's centre to its farthest corner, degrees. */
    val radii: DoubleArray = DoubleArray(panelCount) { index ->
        val center = pointings[index].direction.unitVector
        outlines[index].maxOf { center.angle(it) }
    }

    /** The label of each panel on the sky: its place in the shooting order, counted from 1. */
    val numbers: Array<String> = Array(panelCount) { (positions[it] + 1).toString() }

    /** The guidance target for the panel in the [step]-th place of the shooting order, or `null` past the last. */
    fun aim(step: Int): AimPanelAim? {
        if (step !in 0 until panelCount) return null
        val index = order[step]
        val pointing = pointings[index]
        return AimPanelAim(
            key = AimPanelAim.key(plan, index),
            horizontal = pointing.direction,
            roll = pointing.roll,
            isPortrait = isPortrait,
            label = "Panel ${step + 1} of $panelCount",
        )
    }

    /** How the panel in the [step]-th place stands to the shooting: `DONE`, `CURRENT`, `NEXT` or `PENDING`. */
    fun kind(index: Int, step: Int): Int {
        val position = positions[index]
        return when {
            position < step -> DONE
            position == step -> CURRENT
            position == step + 1 -> NEXT
            else -> PENDING
        }
    }

    companion object {
        const val DONE = 0
        const val CURRENT = 1
        const val NEXT = 2
        const val PENDING = 3

        /** The pointings of every panel of [plan] at the instant of [frame]. Pure: call it off the main thread. */
        fun make(plan: MosaicPlan, frame: SkyFrame): AimMosaicPointings =
            AimMosaicPointings(plan, MosaicGuidance.pointings(plan, frame), frame.date)

        /** The words for a roll difference: "Rotate the camera 12° clockwise", or `null` while it is within a degree. */
        fun rollText(difference: Double): String? {
            val size = abs(difference)
            if (!(size > ROLL_TOLERANCE)) return null
            val degrees = if (size >= 5) "${size.roundToInt()}°" else AimFormat.angle(size)
            return "Rotate the camera $degrees ${if (difference > 0) "clockwise" else "counterclockwise"}"
        }

        /** The same for TalkBack. */
        fun spokenRollText(difference: Double): String? {
            val size = abs(difference)
            if (!(size > ROLL_TOLERANCE)) return null
            return "Rotate the camera ${AimFormat.spoken(size)} ${if (difference > 0) "clockwise" else "counterclockwise"}"
        }

        /** Differences up to this many degrees need no correction. */
        const val ROLL_TOLERANCE = 1.0
    }
}
