package net.kuecker.lodestar.features.aim

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.GpsFixed
import androidx.compose.material.icons.outlined.NearMe
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Terrain
import androidx.compose.material.icons.outlined.VideocamOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import net.kuecker.lodestar.features.satellites.SatelliteStreakChip
import net.kuecker.lodestar.kit.formatting.AngleFormatter
import net.kuecker.lodestar.kit.pointing.AlignmentModel
import net.kuecker.lodestar.model.HeadingSource
import net.kuecker.lodestar.model.LocationSource
import net.kuecker.lodestar.services.CameraAuthorization
import net.kuecker.lodestar.services.CompassQuality
import net.kuecker.lodestar.services.MotionService
import net.kuecker.lodestar.services.MotionStatus
import net.kuecker.lodestar.shared.ChipTone
import net.kuecker.lodestar.shared.LocalAppModel
import net.kuecker.lodestar.shared.StatusChip

/** Fractional deviation of the measured magnetic field from the expected one above which the compass is suspect. */
private const val INTERFERENCE_THRESHOLD = 0.25

/** One chip. Chips that explain a problem with an Android permission open the app's settings when tapped. */
private data class ChipItem(
    val id: String,
    val icon: ImageVector,
    val text: String,
    val tone: ChipTone = ChipTone.NEUTRAL,
    val opensSettings: Boolean = false,
)

/** The row of status chips at the top: sensors, compass, alignment, location and camera. */
@Composable
internal fun AimStatusChips(model: AimViewModel, modifier: Modifier = Modifier) {
    val app = LocalAppModel.current
    val context = LocalContext.current
    val items = sensorItems(app.motion.status) + headingItems(app.motion) + alignmentItems(app.alignment) +
        locationItems(app.location.isReducedAccuracy && app.observerContext?.source == LocationSource.Device) +
        skylineItems(app.observerContext?.horizon != null) +
        cameraItems(model.configuration.wantsCameraPreview, app.camera.authorization, app.camera.errorDescription != null)
    AimFlowLayout(modifier.fillMaxWidth()) {
        for (item in items) {
            key(item.id) {
                if (item.opensSettings) {
                    // Padded to the minimum touch target.
                    StatusChip(
                        item.icon, item.text, tone = item.tone,
                        modifier = Modifier
                            .clickable(
                                onClickLabel = "Open the app settings to allow camera access. The virtual sky still works.",
                                role = Role.Button,
                                onClick = { openAppSettings(context) },
                            )
                            .padding(vertical = 9.dp),
                    )
                } else {
                    StatusChip(item.icon, item.text, tone = item.tone)
                }
            }
        }
    }
    SatelliteStreakChip(model)
}

// Sensors and heading

private fun sensorItems(status: MotionStatus): List<ChipItem> = when (status) {
    MotionStatus.Starting -> listOf(ChipItem("sensors", Icons.Outlined.Sensors, "Starting sensors"))
    is MotionStatus.Failed ->
        listOf(ChipItem("sensors", Icons.Filled.Warning, "Sensor problem", ChipTone.CRITICAL))
    MotionStatus.Idle, MotionStatus.Unavailable, MotionStatus.Running -> emptyList()
}

private fun headingItems(motion: MotionService): List<ChipItem> {
    val items = mutableListOf<ChipItem>()
    if (motion.headingSource == HeadingSource.GYRO_LOCK) {
        items.add(ChipItem("heading", Icons.Outlined.Sensors, "Gyro lock"))
        if (!motion.isGyroHeadingReferenced) {
            items.add(
                ChipItem("heading-reference", Icons.Filled.Warning, "Set heading: align on a star", ChipTone.WARNING),
            )
        }
    } else {
        items.add(compassItem(motion.compassQuality))
    }
    val disturbance = motion.magneticDisturbance
    if (disturbance != null && disturbance > INTERFERENCE_THRESHOLD) {
        items.add(ChipItem("interference", Icons.Filled.Bolt, "Magnetic interference", ChipTone.WARNING))
    }
    return items
}

private fun compassItem(quality: CompassQuality): ChipItem = when (quality) {
    CompassQuality.HIGH -> ChipItem("heading", Icons.Outlined.Explore, "Compass good", ChipTone.POSITIVE)
    CompassQuality.MEDIUM -> ChipItem("heading", Icons.Outlined.Explore, "Compass fair")
    CompassQuality.LOW, CompassQuality.UNCALIBRATED ->
        ChipItem("heading", Icons.Outlined.Explore, "Compass poor", ChipTone.WARNING)
    CompassQuality.UNAVAILABLE -> ChipItem("heading", Icons.Outlined.Explore, "No compass", ChipTone.WARNING)
}

// Alignment, location and camera

private fun alignmentItems(alignment: AlignmentModel): List<ChipItem> {
    if (!alignment.isAligned) return listOf(ChipItem("alignment", Icons.Outlined.GpsFixed, "Not aligned"))
    val error = alignment.pointingUncertainty ?: alignment.rmsResidual
    val count = alignment.points.size
    val accuracy = AngleFormatter.formatDegrees(error, if (error < 10) 1 else 0)
    return listOf(
        ChipItem(
            "alignment", Icons.Outlined.GpsFixed, "Aligned ±$accuracy · $count pt${if (count == 1) "" else "s"}",
            tone = if (error <= 1) ChipTone.POSITIVE else if (error <= 3) ChipTone.NEUTRAL else ChipTone.WARNING,
        ),
    )
}

private fun locationItems(isApproximate: Boolean): List<ChipItem> =
    if (isApproximate) listOf(ChipItem("location", Icons.Outlined.NearMe, "Approx. location")) else emptyList()

private fun skylineItems(hasSkyline: Boolean): List<ChipItem> =
    if (hasSkyline) listOf(ChipItem("skyline", Icons.Outlined.Terrain, "Skyline")) else emptyList()

private fun cameraItems(wantsPreview: Boolean, authorization: CameraAuthorization, hasError: Boolean): List<ChipItem> {
    if (!wantsPreview) return listOf(ChipItem("camera", Icons.Outlined.AutoAwesome, "Virtual sky"))
    return when (authorization) {
        CameraAuthorization.DENIED ->
            listOf(ChipItem("camera", Icons.Outlined.VideocamOff, "Camera off · Settings", ChipTone.WARNING, opensSettings = true))
        CameraAuthorization.UNAVAILABLE ->
            listOf(ChipItem("camera", Icons.Outlined.VideocamOff, "No camera · virtual sky"))
        CameraAuthorization.NOT_DETERMINED, CameraAuthorization.AUTHORIZED ->
            if (hasError) listOf(ChipItem("camera", Icons.Outlined.VideocamOff, "Camera paused", ChipTone.WARNING)) else emptyList()
    }
}

/** Opens the app's page in the system settings, where the camera permission can be allowed. */
private fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // A device without a settings app has nothing to open; the virtual sky works without the camera.
    }
}
