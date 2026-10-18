package net.kuecker.lodestar.kit.pointing

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.encodeStructure
import kotlinx.serialization.json.JsonElement
import net.kuecker.lodestar.kit.foundation.*
import kotlin.math.PI
import kotlin.math.atan2

/**
 * How the phone is attached to the camera: the camera's optical axis and "up" direction expressed
 * in the phone's device frame, and how a movement of the camera is drawn on the phone's screen.
 *
 * "Photographer" below is the person standing behind the camera and looking through it, so the
 * camera's *right* is `cameraAxis × cameraUp`.
 *
 * The on-screen arrow is drawn in device coordinates (x towards the right edge, y towards the top
 * edge of the portrait phone). The camera's right and up directions are laid onto the screen plane
 * by the smallest rotation that makes the optical axis point into the screen, the way a person
 * reading it looks. For a back-camera mount that rotation is the identity: the arrow is the
 * physical direction on the screen, whichever way the phone is turned in the clamp. For a phone
 * lying on top of the camera the picture is tilted up to face the reader, so the top of the screen
 * means "tilt the lens up".
 *
 * The constructor is private; use a preset or [custom]. The JSON form has the keys `id`, `name`,
 * `cameraAxis` and `cameraUp` (the screen directions are derived), and decoding applies the checks of
 * [custom] to the stored geometry.
 */
@Serializable(with = MountProfileSerializer::class)
@ConsistentCopyVisibility
data class MountProfile private constructor(
    /** Stable identifier, e.g. `"backCamera.portrait"`. */
    val id: String,
    /** Display name. */
    val name: String,
    /** The camera's optical axis (towards the scene) in device coordinates; a unit vector. */
    val cameraAxis: Vector3,
    /**
     * The camera's "up" (towards the top of the picture) in device coordinates; a unit vector
     * perpendicular to [cameraAxis].
     */
    val cameraUp: Vector3,
    /**
     * Device-frame direction in which a movement to the camera's right is drawn on the screen:
     * a unit vector in the screen plane (z = 0).
     */
    val screenRight: Vector3,
    /**
     * Device-frame direction in which a movement to the camera's up is drawn on the screen:
     * a unit vector in the screen plane (z = 0).
     */
    val screenUp: Vector3,
) {
    /** The camera's right (towards the right of the picture) in device coordinates. */
    val cameraRight: Vector3
        get() = cameraAxis.cross(cameraUp)

    companion object {
        private const val maximumIdentifierLength = 64
        private const val maximumNameLength = 80

        /** Smallest sine of the angle between `cameraUp` and `cameraAxis` that is accepted. */
        private const val minimumAxisUpSine = 0.1

        // MARK: Presets

        /**
         * The phone's back camera looks along the lens, phone upright in portrait: the camera's up is
         * the top edge of the phone, its right the right edge.
         */
        val backCameraPortrait: MountProfile = preset(
            id = "backCamera.portrait", name = "Back camera, portrait",
            axis = Vector3(0.0, 0.0, -1.0), up = Vector3(0.0, 1.0, 0.0),
        )

        /** Back camera, phone upside down in the clamp: the camera's up is the bottom edge. */
        val backCameraPortraitUpsideDown: MountProfile = preset(
            id = "backCamera.portraitUpsideDown", name = "Back camera, upside down",
            axis = Vector3(0.0, 0.0, -1.0), up = Vector3(0.0, -1.0, 0.0),
        )

        /**
         * Back camera, phone turned like `UIDeviceOrientation.landscapeLeft`: top edge towards the
         * photographer's left, home button / charging port on the right. The camera's up is the
         * device +x axis (the right edge of the portrait phone), its right the device −y axis.
         */
        val backCameraLandscapeLeft: MountProfile = preset(
            id = "backCamera.landscapeLeft", name = "Back camera, landscape (top edge left)",
            axis = Vector3(0.0, 0.0, -1.0), up = Vector3(1.0, 0.0, 0.0),
        )

        /**
         * Back camera, phone turned like `UIDeviceOrientation.landscapeRight`: top edge towards the
         * photographer's right, home button / charging port on the left. The camera's up is the
         * device −x axis, its right the device +y axis.
         */
        val backCameraLandscapeRight: MountProfile = preset(
            id = "backCamera.landscapeRight", name = "Back camera, landscape (top edge right)",
            axis = Vector3(0.0, 0.0, -1.0), up = Vector3(-1.0, 0.0, 0.0),
        )

        /**
         * The phone lies screen-up on top of the camera with its top edge pointing along the lens.
         * The optical axis is device +y and the camera's up is the screen normal (+z); the camera's
         * right is the phone's right edge. The screen draws "tilt up" towards the top edge.
         */
        val flatOnTopTopEdgeForward: MountProfile = preset(
            id = "flatOnTop.topEdgeForward", name = "Flat on top, top edge forward",
            axis = Vector3(0.0, 1.0, 0.0), up = Vector3(0.0, 0.0, 1.0),
        )

        /**
         * The phone lies screen-up on top of the camera with its top edge towards the photographer
         * (the lens points along the bottom edge). The optical axis is device −y, the camera's up the
         * screen normal, the camera's right the phone's left edge (device −x).
         */
        val flatOnTopTopEdgeBack: MountProfile = preset(
            id = "flatOnTop.topEdgeBack", name = "Flat on top, top edge back",
            axis = Vector3(0.0, -1.0, 0.0), up = Vector3(0.0, 0.0, 1.0),
        )

        /** All built-in presets in the order a settings screen should list them. */
        val presets: List<MountProfile> = listOf(
            backCameraPortrait, backCameraPortraitUpsideDown, backCameraLandscapeLeft, backCameraLandscapeRight,
            flatOnTopTopEdgeForward, flatOnTopTopEdgeBack,
        )

        /**
         * A mount with arbitrary geometry. [cameraUp] is made perpendicular to [cameraAxis]; null is
         * returned when a vector is not finite, has no length, or the two directions are within
         * about 6° of being parallel.
         */
        fun custom(name: String = "Custom", cameraAxis: Vector3, cameraUp: Vector3): MountProfile? =
            make(id = "custom", name = name, axis = cameraAxis, up = cameraUp)

        /** Builds a preset from an `axis` and `up` that are already perpendicular unit vectors. */
        private fun preset(id: String, name: String, axis: Vector3, up: Vector3): MountProfile {
            val screen = screenAxes(axis, up)
            return MountProfile(id, name, axis, up, screen.right, screen.up)
        }

        /**
         * The checks shared by [custom] and decoding: finite, non-zero vectors that are not nearly
         * parallel. Identifier and name are shortened to 64 and 80 characters.
         */
        internal fun make(id: String, name: String, axis: Vector3, up: Vector3): MountProfile? {
            if (!(axis.isFinite && up.isFinite)) return null
            val unitAxis = axis.normalized
            val upLength = up.length
            if (PointingMath.isZero(unitAxis) || !(upLength > 0)) return null
            val perpendicular = up - unitAxis * unitAxis.dot(up)
            if (!(perpendicular.length >= minimumAxisUpSine * upLength)) return null
            val unitUp = perpendicular.normalized
            val screen = screenAxes(unitAxis, unitUp)
            return MountProfile(
                id.prefixCharacters(maximumIdentifierLength),
                name.prefixCharacters(maximumNameLength),
                unitAxis,
                unitUp,
                screen.right,
                screen.up,
            )
        }

        /** The camera's right and up laid onto the screen plane (see the type documentation). */
        private fun screenAxes(axis: Vector3, up: Vector3): ScreenAxes {
            val readerLooks = Vector3(0.0, 0.0, -1.0) // into the screen
            val turn = axis.cross(readerLooks)
            val rotation: Matrix3 = if (turn.length < 1e-12) {
                // Parallel: nothing to do. Anti-parallel (axis out of the screen): half a turn about "up".
                if (axis.dot(readerLooks) > 0) Matrix3.identity else Matrix3.rotation(up, PI)
            } else {
                Matrix3.rotation(turn, atan2(turn.length, axis.dot(readerLooks)))
            }
            val right = rotation * axis.cross(up)
            val screenUp = rotation * up
            return ScreenAxes(Vector3(right.x, right.y, 0.0).normalized, Vector3(screenUp.x, screenUp.y, 0.0).normalized)
        }

        private class ScreenAxes(val right: Vector3, val up: Vector3)
    }
}

/**
 * Serializes a [MountProfile] as a JSON object with the keys `id`, `name`, `cameraAxis` and `cameraUp`.
 * Decoding reads the object itself, so unknown keys are ignored (like Swift's `JSONDecoder`); a missing
 * key, a value of the wrong type or geometry that does not describe a valid mount throws a
 * [SerializationException]. The screen directions are recomputed from the stored vectors.
 */
internal object MountProfileSerializer : KSerializer<MountProfile> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("net.kuecker.lodestar.kit.pointing.MountProfile") {
            element<String>("id")
            element<String>("name")
            element("cameraAxis", Vector3.serializer().descriptor)
            element("cameraUp", Vector3.serializer().descriptor)
        }

    override fun serialize(encoder: Encoder, value: MountProfile) {
        encoder.encodeStructure(descriptor) {
            encodeStringElement(descriptor, 0, value.id)
            encodeStringElement(descriptor, 1, value.name)
            encodeSerializableElement(descriptor, 2, Vector3.serializer(), value.cameraAxis)
            encodeSerializableElement(descriptor, 3, Vector3.serializer(), value.cameraUp)
        }
    }

    override fun deserialize(decoder: Decoder): MountProfile = decode(decoder.decodeJsonTree("mount profile"))

    /** Decodes and validates a mount profile from its JSON representation. */
    fun decode(element: JsonElement): MountProfile {
        val fields = element.toJsonObject("a mount profile")
        val id = fields.requiredField("id").toStrictString("id")
        val name = fields.requiredField("name").toStrictString("name")
        val axis = fields.requiredField("cameraAxis").toVector3("cameraAxis")
        val up = fields.requiredField("cameraUp").toVector3("cameraUp")
        return MountProfile.make(id, name, axis, up)
            ?: throw SerializationException("The camera axis and up vectors do not describe a valid mount.")
    }
}
