package net.kuecker.lodestar.model

import net.kuecker.lodestar.kit.phototools.CameraPhotoMetadata
import net.kuecker.lodestar.kit.phototools.CameraPreset
import net.kuecker.lodestar.kit.phototools.LensProjection
import net.kuecker.lodestar.kit.phototools.LensSetup
import net.kuecker.lodestar.kit.phototools.SensorFormat
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CameraProfileTest {
    private fun profile(name: String = "Body", focalLength: Double = 24.0) =
        CameraProfile.create(name, focalLength = focalLength)

    private fun settings(vararg profiles: CameraProfile, active: CameraProfile? = null) =
        AppSettings(cameraProfiles = profiles.toList(), activeCameraProfileID = (active ?: profiles.first()).id).sanitized()

    @Test
    fun valuesAreLimitedToTheirRanges() {
        val profile = CameraProfile.create(
            "  ${"x".repeat(80)}  ", sensorID = "nonsense", customSensorWidth = 500.0, customSensorHeight = Double.NaN,
            focalLength = 1e9, aperture = -4.0, pixelPitch = 99.0, imageCircleDiameter = 0.5, id = "not-a-uuid",
        )
        assertEquals("x".repeat(CameraProfile.MAXIMUM_NAME_LENGTH), profile.name)
        assertEquals(CameraProfile.CUSTOM_SENSOR_ID, profile.sensorID)
        assertEquals(100.0, profile.customSensorWidth)
        assertEquals(24.0, profile.customSensorHeight)
        assertEquals(2000.0, profile.focalLength)
        assertEquals(0.95, profile.aperture)
        assertNull(profile.pixelPitch)
        assertNull(profile.imageCircleDiameter)
        assertNotNull(runCatching { UUID.fromString(profile.id) }.getOrNull())
        assertEquals("Camera", CameraProfile.create("   ").name)
    }

    @Test
    fun aNameIsNotCutThroughASurrogatePair() {
        val name = "a".repeat(CameraProfile.MAXIMUM_NAME_LENGTH - 1) + "📷📷"
        val limited = CameraProfile.limitedName(name)
        assertEquals("a".repeat(CameraProfile.MAXIMUM_NAME_LENGTH - 1), limited)
        assertTrue(limited.length <= CameraProfile.MAXIMUM_NAME_LENGTH)
    }

    @Test
    fun aProfileIsTheLensSetupItDescribes() {
        val fisheye = CameraProfile.create(
            "Fisheye", sensorID = SensorFormat.apsC.id, focalLength = 8.0, aperture = 3.5, pixelPitch = 4.0,
            projection = LensProjection.FISHEYE_EQUISOLID, imageCircleDiameter = 14.0,
        )
        assertEquals(
            LensSetup(SensorFormat.apsC, 8.0, 3.5, 4.0, LensProjection.FISHEYE_EQUISOLID, 14.0), fisheye.lensSetup,
        )
        assertTrue(!fisheye.isCustomSensor)
        val custom = CameraProfile.create("Custom", customSensorWidth = 20.0, customSensorHeight = 10.0, sensorID = "custom")
        assertTrue(custom.isCustomSensor)
        assertEquals(20.0, custom.lensSetup.sensor.widthMillimeters)
        assertEquals(10.0, custom.lensSetup.sensor.heightMillimeters)
    }

    @Test
    fun editingKeepsTheIdentifierAndLimitsTheValues() {
        val original = profile()
        val edited = original.edited(focalLength = 3000.0, name = "Renamed")
        assertEquals(original.id, edited.id)
        assertEquals("Renamed", edited.name)
        assertEquals(2000.0, edited.focalLength)
        assertNull(original.edited(imageCircleDiameter = null).imageCircleDiameter)
    }

    @Test
    fun theJsonRoundTripKeepsEveryValue() {
        val fisheye = CameraProfile.create(
            "Fisheye", sensorID = "custom", customSensorWidth = 22.0, customSensorHeight = 14.0, focalLength = 8.0,
            aperture = 3.5, pixelPitch = 4.5, projection = LensProjection.FISHEYE_STEREOGRAPHIC, imageCircleDiameter = 15.0,
        )
        val plain = profile("Plain")
        for (original in listOf(fisheye, plain)) {
            assertEquals(original, CameraProfile.fromJson(CameraProfile.toJson(original)))
        }
        assertEquals(
            setOf("id", "name", "sensorID", "customSensorWidth", "customSensorHeight", "focalLength", "aperture", "projection"),
            (CameraProfile.toJson(plain) as kotlinx.serialization.json.JsonObject).keys,
        )
    }

    @Test
    fun aDamagedStoredProfileKeepsWhatIsValid() {
        val id = UUID.randomUUID().toString()
        val decoded = AppSettings.decode(
            """{"cameraProfiles":[{"id":"$id","name":"Old","focalLength":"fifty","aperture":4,"projection":"cylindrical","imageCircleDiameter":900},
               {"id":"nope","name":"Bad id"},"not an object",{"name":"No id"}]}""",
        )
        val profile = decoded.cameraProfiles.single()
        assertEquals(id, profile.id)
        assertEquals("Old", profile.name)
        assertEquals(24.0, profile.focalLength)
        assertEquals(4.0, profile.aperture)
        assertEquals(LensProjection.RECTILINEAR, profile.projection)
        assertNull(profile.imageCircleDiameter)
    }

    @Test
    fun aPresetBecomesAProfileWithItsSensorAndLens() {
        val seestar = CameraProfile.fromPreset(CameraPreset.seestarS50)
        assertEquals("Seestar S50", seestar.name)
        assertEquals(CameraProfile.CUSTOM_SENSOR_ID, seestar.sensorID)
        assertEquals(CameraPreset.seestarS50.sensor.widthMillimeters, seestar.customSensorWidth)
        assertEquals(250.0, seestar.focalLength)
        assertEquals(2.9, seestar.pixelPitch)
        assertEquals(CameraPreset.seestarS50.setup.fieldOfView, seestar.lensSetup.fieldOfView)
        val starter = CameraProfile.fromPreset(CameraPreset.fullFrame24)
        assertEquals(SensorFormat.fullFrame.id, starter.sensorID)
        assertEquals(LensSetup(SensorFormat.fullFrame, 24.0, 2.8), starter.lensSetup)
        for (preset in CameraPreset.all) {
            assertEquals(preset.setup.fieldOfView, CameraProfile.fromPreset(preset).lensSetup.fieldOfView, preset.id)
            assertEquals(preset.setup.pixelPitchMicrometers, CameraProfile.fromPreset(preset).lensSetup.pixelPitchMicrometers, preset.id)
        }
    }

    @Test
    fun aPhotoBecomesAProfileAndKeepsTheDefaultsForWhatItDoesNotSay() {
        val camera = assertNotNull(CameraPhotoMetadata("NIKON D850", 24.0, 24.0, 2.8, 8256, 5504).suggestion())
        val fromCamera = CameraProfile.fromPhoto(camera)
        assertEquals("NIKON D850", fromCamera.name)
        assertEquals(SensorFormat.fullFrame.id, fromCamera.sensorID)
        assertEquals(4.3605, assertNotNull(fromCamera.pixelPitch), 1e-4)
        val phone = assertNotNull(CameraPhotoMetadata(null, 6.9, 24.0, null, 4000, 3000).suggestion())
        val fromPhone = CameraProfile.fromPhoto(phone)
        assertEquals(CameraProfile.PHOTO_NAME, fromPhone.name)
        assertEquals(CameraProfile.CUSTOM_SENSOR_ID, fromPhone.sensorID)
        assertEquals(9.9513, fromPhone.customSensorWidth, 1e-4)
        assertEquals(2.8, fromPhone.aperture)
        val bare = assertNotNull(CameraPhotoMetadata(null, 50.0, null, null, null, null).suggestion())
        val fromBare = CameraProfile.fromPhoto(bare)
        assertEquals(SensorFormat.fullFrame.id, fromBare.sensorID)
        assertEquals(50.0, fromBare.focalLength)
        assertNull(fromBare.pixelPitch)
    }

    // MARK: Settings

    @Test
    fun theSingleCameraOfEarlierVersionsBecomesMyCamera() {
        val decoded = AppSettings.decode(
            """{"sensorID":"apsC","focalLength":35,"aperture":1.8,"pixelPitch":3.9}""",
        )
        val profile = decoded.cameraProfiles.single()
        assertEquals(CameraProfile.DEFAULT_NAME, profile.name)
        assertEquals(CameraProfile.MIGRATED_ID, profile.id)
        assertEquals(SensorFormat.apsC.id, profile.sensorID)
        assertEquals(35.0, profile.focalLength)
        assertEquals(1.8, profile.aperture)
        assertEquals(3.9, profile.pixelPitch)
        assertEquals(profile.id, decoded.activeCameraProfileID)
        assertEquals(LensSetup(SensorFormat.apsC, 35.0, 1.8, 3.9), decoded.activeCameraProfile.lensSetup)
    }

    @Test
    fun aFreshInstallationHasOneProfile() {
        val fresh = AppSettings().sanitized()
        assertEquals(listOf(CameraProfile.DEFAULT_NAME), fresh.cameraProfiles.map { it.name })
        assertEquals(LensSetup(SensorFormat.fullFrame, 24.0, 2.8), fresh.activeCameraProfile.lensSetup)
        // Even before the settings are sanitised the active camera is the single camera.
        assertEquals(fresh.activeCameraProfile.lensSetup, AppSettings().activeCameraProfile.lensSetup)
    }

    @Test
    fun theSingleCameraKeysFollowTheActiveProfile() {
        val wide = profile("Wide", 14.0)
        val tele = profile("Tele", 200.0).edited(sensorID = SensorFormat.apsC.id, pixelPitch = 3.9, aperture = 4.0)
        val first = settings(wide, tele, active = wide)
        assertEquals(14.0, first.focalLength)
        val second = first.copy(activeCameraProfileID = tele.id).sanitized()
        assertEquals(200.0, second.focalLength)
        assertEquals(4.0, second.aperture)
        assertEquals(3.9, second.pixelPitch)
        assertEquals(SensorFormat.apsC.id, second.sensorID)
        assertEquals(tele, second.activeCameraProfile)
    }

    @Test
    fun aChangeToTheSingleCameraKeysAloneGoesToTheActiveProfile() {
        val wide = profile("Wide", 14.0)
        val tele = profile("Tele", 200.0)
        val before = settings(wide, tele, active = tele)
        val after = before.copy(focalLength = 300.0, pixelPitch = 4.1).reconcilingCamera(before).sanitized()
        assertEquals(300.0, after.activeCameraProfile.focalLength)
        assertEquals(4.1, after.activeCameraProfile.pixelPitch)
        assertEquals(tele.id, after.activeCameraProfile.id)
        assertEquals("Tele", after.activeCameraProfile.name)
        assertEquals(wide, after.cameraProfiles.first())
        assertEquals(300.0, after.focalLength)
    }

    @Test
    fun aChangeToTheProfilesWinsOverTheSingleCameraKeys() {
        val wide = profile("Wide", 14.0)
        val tele = profile("Tele", 200.0)
        val before = settings(wide, tele, active = wide)
        val changed = before.copy(focalLength = 99.0, activeCameraProfileID = tele.id)
        val after = changed.reconcilingCamera(before).sanitized()
        assertEquals(tele.id, after.activeCameraProfile.id)
        assertEquals(200.0, after.activeCameraProfile.focalLength)
        assertEquals(200.0, after.focalLength)
    }

    @Test
    fun profilesAreCappedUniqueAndTheActiveOneIsRepaired() {
        val many = List(AppSettings.MAXIMUM_CAMERA_PROFILES + 10) { profile("Camera $it") }
        val capped = AppSettings(cameraProfiles = many + many.first(), activeCameraProfileID = "gone").sanitized()
        assertEquals(AppSettings.MAXIMUM_CAMERA_PROFILES, capped.cameraProfiles.size)
        assertEquals(capped.cameraProfiles.size, capped.cameraProfiles.map { it.id }.toSet().size)
        assertEquals(many.first().id, capped.activeCameraProfileID)
        assertEquals(50, AppSettings.MAXIMUM_CAMERA_PROFILES)
    }

    @Test
    fun theProfilesAndTheActiveOneSurviveTheRoundTrip() {
        val fisheye = CameraProfile.create(
            "Fisheye", sensorID = "fullFrame", focalLength = 15.0, projection = LensProjection.FISHEYE_EQUISOLID,
            imageCircleDiameter = 30.0,
        )
        val original = settings(profile("Body"), fisheye, active = fisheye)
        val decoded = AppSettings.decode(AppSettings.encode(original))
        assertEquals(original, decoded)
        assertEquals(fisheye, decoded.activeCameraProfile)
        assertEquals(15.0, decoded.focalLength)
        assertNotEquals(original.cameraProfiles.first().id, decoded.activeCameraProfileID)
    }
}
