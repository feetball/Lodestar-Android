package net.kuecker.lodestar.features.aim

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CropFree
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.kuecker.lodestar.shared.LocalAppModel
import net.kuecker.lodestar.theme.LocalPalette

/**
 * The frame button: a menu of the camera profiles for switching between bodies and lenses at the tripod. The frame on
 * the sky follows the one chosen. Shown only with more than one profile.
 */
@Composable
internal fun AimFrameMenuButton(minHeight: Dp, modifier: Modifier) {
    val app = LocalAppModel.current
    val palette = LocalPalette.current
    val active = app.settings.activeCameraProfile
    var isOpen by remember { mutableStateOf(false) }
    Box(modifier) {
        AimControlButton(
            onClick = { isOpen = true },
            modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = "Camera frame"
                stateDescription = active.name
            },
            minHeight = minHeight,
            onClickLabel = "Choose the camera and lens",
        ) {
            Icon(Icons.Outlined.CropFree, contentDescription = null, modifier = Modifier.size(22.dp))
            AimFittingText(
                "Frame",
                MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                LocalContentColor.current,
            )
        }
        DropdownMenu(expanded = isOpen, onDismissRequest = { isOpen = false }, containerColor = palette.surfaceRaised) {
            for (profile in app.settings.cameraProfiles) {
                val isActive = profile.id == active.id
                DropdownMenuItem(
                    text = { Text(profile.name, color = if (isActive) palette.accent else palette.primaryText) },
                    trailingIcon = if (isActive) {
                        { Icon(Icons.Outlined.Check, contentDescription = "In use", tint = palette.accent) }
                    } else {
                        null
                    },
                    onClick = {
                        isOpen = false
                        app.settingsStore.activateCameraProfile(profile.id)
                    },
                )
            }
        }
    }
}
