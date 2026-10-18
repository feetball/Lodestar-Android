package net.kuecker.lodestar.features.aim

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.WbTwilight
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.GpsFixed
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.VideocamOff
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.kuecker.lodestar.kit.formatting.AngleFormatter
import net.kuecker.lodestar.model.NightModeSetting
import net.kuecker.lodestar.services.CameraAuthorization
import net.kuecker.lodestar.shared.LocalAppModel
import net.kuecker.lodestar.shared.monospacedDigits
import kotlin.math.abs
import kotlin.math.max

/**
 * The buttons of the screen: target, align, camera, night mode and zoom, in a row or, in a wide HUD, in a column
 * (`axis` = vertical).
 */
@Composable
internal fun AimControlsBar(
    model: AimViewModel,
    onSheet: (AimSheet) -> Unit,
    modifier: Modifier = Modifier,
    axis: Orientation = Orientation.Horizontal,
) {
    val app = LocalAppModel.current
    val scope = rememberCoroutineScope()
    val previewOn = model.configuration.wantsCameraPreview
    val cameraFollowsLens = model.configuration.phoneCameraFollowsLens
    val nightMode = app.settings.nightMode
    val isAligned = app.alignment.isAligned
    val buttonHeight = if (axis == Orientation.Horizontal) 54.dp else 46.dp

    // Both layouts hold the same buttons; each one gets the weight (row) or the full width (column) it needs.
    val buttons: @Composable (Modifier) -> Unit = { slot ->
        AimControl(
            title = "Target", icon = Icons.Outlined.GpsFixed, minHeight = buttonHeight,
            label = "Choose target", value = model.guidanceTarget?.name ?: "None",
            modifier = slot, onClick = { onSheet(AimSheet.TARGET_PICKER) },
        )
        AimControl(
            title = "Align", icon = Icons.Outlined.CenterFocusStrong, isOn = isAligned, minHeight = buttonHeight,
            label = "Align on a star", value = if (isAligned) "Aligned" else "Not aligned",
            modifier = slot, onClick = { onSheet(AimSheet.ALIGNMENT) },
        )
        AimControl(
            title = "Camera", icon = if (previewOn) Icons.Filled.CameraAlt else Icons.Outlined.VideocamOff,
            isOn = previewOn, enabled = cameraFollowsLens, minHeight = buttonHeight,
            label = "Camera picture",
            value = if (previewOn) "On" else if (cameraFollowsLens) "Off" else "Not available with this mount",
            modifier = slot,
            onClick = {
                // Turning the picture on is also the moment to ask for camera access, if it has not been granted.
                val turnsOn = !previewOn
                model.toggleCameraPreview()
                if (turnsOn && app.camera.authorization != CameraAuthorization.AUTHORIZED) {
                    scope.launch { app.camera.requestAccess() }
                }
            },
        )
        AimControl(
            title = nightTitle(nightMode), icon = nightIcon(nightMode), isOn = app.isNightModeActive,
            minHeight = buttonHeight, label = "Night mode", value = nightMode.title,
            hint = "Cycles between off, on and after sunset.",
            modifier = slot, onClick = model::cycleNightMode,
        )
        AimFieldOfViewButton(model, buttonHeight, slot)
    }
    if (axis == Orientation.Horizontal) {
        Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            buttons(Modifier.weight(1f))
        }
    } else {
        Column(modifier.width(66.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            buttons(Modifier.fillMaxWidth())
        }
    }
}

/** A button: a symbol over a caption, with the label and value that accessibility services speak. */
@Composable
private fun AimControl(
    title: String,
    icon: ImageVector,
    minHeight: Dp,
    label: String,
    value: String,
    modifier: Modifier,
    onClick: () -> Unit,
    isOn: Boolean = false,
    enabled: Boolean = true,
    hint: String? = null,
) {
    AimControlButton(
        onClick = onClick,
        modifier = modifier.semantics {
            contentDescription = label
            stateDescription = value
        },
        isOn = isOn,
        enabled = enabled,
        minHeight = minHeight,
        onClickLabel = hint,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
        AimFittingText(
            title,
            MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            LocalContentColor.current,
        )
    }
}

/**
 * Shows the field of view along the screen's long side and resets the zoom of the virtual sky. With the camera
 * picture the field of view is the camera's and cannot be changed.
 */
@Composable
private fun AimFieldOfViewButton(model: AimViewModel, minHeight: Dp, modifier: Modifier) {
    val camera = model.cameraGeometry
    val usesCamera = camera != null
    val fieldOfView = camera?.let { max(it.horizontalFOV, it.verticalFOV) } ?: model.virtualFieldOfView
    val isDefault = abs(model.virtualFieldOfView - AimProjectionFactory.defaultVirtualFieldOfView) < 0.5
    AimControlButton(
        onClick = model::resetFieldOfView,
        modifier = modifier.semantics {
            contentDescription = "Reset zoom"
            stateDescription = "${if (usesCamera) "Camera field of view" else "Field of view"} ${AimFormat.spoken(fieldOfView)}"
        },
        enabled = !(usesCamera || isDefault),
        minHeight = minHeight,
    ) {
        Icon(Icons.Outlined.Replay, contentDescription = null, modifier = Modifier.size(22.dp))
        AimFittingText(
            AngleFormatter.formatDegrees(fieldOfView, 0),
            MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold).monospacedDigits(),
            LocalContentColor.current,
        )
    }
}

private fun nightTitle(mode: NightModeSetting): String = when (mode) {
    NightModeSetting.OFF -> "Night off"
    NightModeSetting.ON -> "Night on"
    NightModeSetting.AUTOMATIC -> "Night auto"
}

private fun nightIcon(mode: NightModeSetting): ImageVector = when (mode) {
    NightModeSetting.OFF -> Icons.Outlined.DarkMode
    NightModeSetting.ON -> Icons.Filled.DarkMode
    NightModeSetting.AUTOMATIC -> Icons.Filled.WbTwilight
}
