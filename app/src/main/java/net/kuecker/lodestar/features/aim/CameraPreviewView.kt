package net.kuecker.lodestar.features.aim

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.kuecker.lodestar.services.CameraAuthorization
import net.kuecker.lodestar.services.CameraPreview
import net.kuecker.lodestar.shared.LocalAppModel
import net.kuecker.lodestar.shared.LodestarButton
import net.kuecker.lodestar.theme.LocalPalette
import net.kuecker.lodestar.theme.card

/**
 * The camera picture behind the sky, aspect-filled and dimmed at night, and the offer to turn the camera on while the
 * person has not yet decided about camera access. The picture itself is the core [CameraPreview]; the camera starts
 * and stops with the app's services, not here.
 *
 * [isWanted] says whether the picture is wanted at all (the preference, taking night mode into account).
 */
@Composable
internal fun CameraPreviewView(isWanted: Boolean, modifier: Modifier = Modifier) {
    val app = LocalAppModel.current
    Box(modifier.fillMaxSize()) {
        if (isWanted) {
            AimCameraFrameImage()
            if (app.camera.authorization == CameraAuthorization.NOT_DETERMINED) AimCameraAccessPrompt()
        }
    }
}

/** The live picture. At night it is dimmed as well as red, by laying black over it. */
@Composable
private fun AimCameraFrameImage() {
    val app = LocalAppModel.current
    val palette = LocalPalette.current
    // The picture is scaled to cover the screen and cropped; the overlay's projection assumes exactly that.
    Box(Modifier.fillMaxSize().clearAndSetSemantics {}) {
        CameraPreview(app.camera, Modifier.fillMaxSize())
        if (palette.isNight) {
            val opacity = 0.35 + 0.45 * app.settings.nightIntensity
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = (1 - opacity).toFloat())))
        }
    }
}

/** Explains the camera picture and asks for access. */
@Composable
private fun AimCameraAccessPrompt() {
    val app = LocalAppModel.current
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .padding(horizontal = 32.dp)
                .offset(y = (-40).dp)
                .card(palette),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                Icons.Outlined.CameraAlt,
                contentDescription = null,
                tint = palette.accent,
                modifier = Modifier.size(28.dp),
            )
            Text(
                "Show the sky over your camera?",
                color = palette.primaryText,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                textAlign = TextAlign.Center,
            )
            Text(
                "The live picture helps you see what the overlay marks. Nothing is recorded or saved. " +
                    "Without it the virtual sky still works.",
                color = palette.secondaryText,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
            LodestarButton(
                "Enable camera",
                onClick = { scope.launch { app.camera.requestAccess() } },
                icon = Icons.Filled.CameraAlt,
            )
        }
    }
}
