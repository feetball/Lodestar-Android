package net.kuecker.lodestar.features.settings

import net.kuecker.lodestar.kit.foundation.AstroMath
import net.kuecker.lodestar.kit.foundation.Vector3
import net.kuecker.lodestar.kit.foundation.roundedHalfAwayFromZero
import net.kuecker.lodestar.kit.pointing.MountProfile
import kotlin.math.abs

/** How a phone mount looks, derived from the mount's geometry so every preset (and any future one) gets a picture. */
internal sealed interface MountLayout {
    /**
     * The phone is clamped to the back of the camera, screen towards the photographer, its own camera looking along
     * the lens. [rotation] is how far the phone is turned clockwise from upright, in degrees.
     */
    data class BackCamera(val rotation: Double) : MountLayout

    /** The phone lies screen-up on top of the camera, its top edge pointing along the lens or towards the photographer. */
    data class FlatOnTop(val topEdgeForward: Boolean) : MountLayout

    /** Any other geometry. */
    data object Custom : MountLayout

    /** One or two sentences on how the phone sits on the camera. */
    val summary: String
        get() = when (this) {
            is BackCamera -> when (rotation.roundedHalfAwayFromZero().toInt()) {
                0 -> "Upright on the back of the camera, screen towards you, its rear camera looking along the lens."
                180, -180 -> "Upside down on the back of the camera, its rear camera looking along the lens."
                -90 -> "On its side with the top edge to your left, its rear camera looking along the lens."
                90 -> "On its side with the top edge to your right, its rear camera looking along the lens."
                else -> "On the back of the camera, its rear camera looking along the lens."
            }
            is FlatOnTop ->
                if (topEdgeForward) {
                    "Flat on top of the camera, screen up, its top edge pointing along the lens."
                } else {
                    "Flat on top of the camera, screen up, its top edge pointing towards you."
                }
            Custom -> "A custom mount."
        }

    companion object {
        /** The layout of [profile]. */
        fun of(profile: MountProfile): MountLayout {
            val axis = profile.cameraAxis
            val up = profile.cameraUp
            return when {
                // `cameraUp` is the phone edge that ends up at the top of the picture.
                axis.dot(Vector3(0.0, 0.0, -1.0)) > 0.99 ->
                    BackCamera(AstroMath.normalizeSignedDegrees(-AstroMath.atan2Deg(up.x, up.y)))
                abs(axis.y) > 0.99 && up.dot(Vector3(0.0, 0.0, 1.0)) > 0.99 -> FlatOnTop(topEdgeForward = axis.y > 0)
                else -> Custom
            }
        }
    }
}
