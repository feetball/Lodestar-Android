package net.kuecker.lodestar.features.aim

import net.kuecker.lodestar.kit.phototools.CameraFrameOutline
import net.kuecker.lodestar.kit.phototools.LensSetup
import net.kuecker.lodestar.kit.pointing.PointingSolution

/**
 * The border of the camera's picture for the lens in use, kept as directions in the camera's frame (x right, y up,
 * z along the optical axis) until the lens changes, so that drawing the frame for a new pointing only turns them into
 * world directions and allocates nothing. Main thread only; one per overlay.
 */
internal class AimLensFrame {
    private var lens: LensSetup? = null
    private var camera = DoubleArray(0)

    /** The number of directions in [world]. */
    var count = 0
        private set

    /** The border in world directions as x, y, z triples, as of the last [intoWorld]. */
    var world = DoubleArray(0)
        private set

    /** Works out the border of [lens], unless it is the lens the frame already holds. */
    fun update(lens: LensSetup) {
        if (lens == this.lens) return
        this.lens = lens
        val outline = CameraFrameOutline.directions(lens, samplesPerEdge(lens))
        count = outline.size
        camera = DoubleArray(3 * count)
        world = DoubleArray(3 * count)
        for ((index, direction) in outline.withIndex()) {
            camera[3 * index] = direction.x
            camera[3 * index + 1] = direction.y
            camera[3 * index + 2] = direction.z
        }
    }

    /** Turns the border into world directions for the pointing [solution]: `right · x + up · y + forward · z`. */
    fun intoWorld(solution: PointingSolution) {
        val right = solution.cameraRightVector
        val up = solution.cameraUpVector
        val forward = solution.boresightVector
        for (index in 0 until count) {
            val x = camera[3 * index]
            val y = camera[3 * index + 1]
            val z = camera[3 * index + 2]
            world[3 * index] = right.x * x + up.x * y + forward.x * z
            world[3 * index + 1] = right.y * x + up.y * y + forward.y * z
            world[3 * index + 2] = right.z * x + up.z * y + forward.z * z
        }
    }

    companion object {
        /** Points per edge of a rectilinear frame, whose edges are straight: the four edges need no more. */
        const val RECTILINEAR_SAMPLES_PER_EDGE = 4

        /** Points per edge of a fisheye frame, whose edges and circle are curves. */
        const val FISHEYE_SAMPLES_PER_EDGE = 24

        /** How many points per edge draw the frame of [lens] without visible corners. */
        fun samplesPerEdge(lens: LensSetup): Int =
            if (lens.projection.isFisheye) FISHEYE_SAMPLES_PER_EDGE else RECTILINEAR_SAMPLES_PER_EDGE
    }
}
