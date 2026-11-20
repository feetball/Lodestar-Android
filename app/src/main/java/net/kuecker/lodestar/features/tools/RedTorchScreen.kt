package net.kuecker.lodestar.features.tools

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.kuecker.lodestar.app.AppModel
import net.kuecker.lodestar.features.system.TorchLight
import kotlin.math.roundToInt

private val TorchRed = Color(0xFFFF0000)

/**
 * The red torch: the whole screen in pure red, to see by without losing night vision. The screen brightness is the bar's
 * ([AppModel.syncServices] sets it and keeps the screen awake while the torch is shown). One tap asks whether to switch
 * to white light and the answer confirms; a tap on white goes back to red; a double tap, or Back, closes the torch.
 *
 * The colours are the torch's own, not the palette's: the light has to be the full red of the panel.
 */
@Composable
fun RedTorchScreen(app: AppModel) {
    var light by remember { mutableStateOf(TorchLight.RED) }
    val brightness = app.settings.torchBrightness
    val close = { app.isTorchShown = false }
    BackHandler { close() }

    Box(
        Modifier
            .fillMaxSize()
            .background(if (light == TorchLight.WHITE) Color.White else TorchRed)
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { close() }, onTap = { light = light.afterTap })
            }
            .semantics {
                role = Role.Button
                contentDescription = if (light == TorchLight.WHITE) "White light" else "Red torch"
                stateDescription = "Tap to switch the colour. Use Close to leave."
                onClick { light = light.afterTap; true }
            }
            .testTag("redTorch"),
    ) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                if (light == TorchLight.WHITE) "Tap for red · double-tap to close" else "Tap for white light · double-tap to close",
                color = Color.Black, fontSize = 13.sp, textAlign = TextAlign.Center,
            )
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (light == TorchLight.CONFIRMING_WHITE) {
                    Confirmation(onKeepRed = { light = light.afterCancel }, onWhite = { light = light.afterConfirm })
                }
            }
            Controls(
                brightness = brightness,
                onBrightness = { value -> app.settingsStore.update { it.copy(torchBrightness = value) } },
                onClose = close,
            )
        }
    }
}

/** Asks before the screen turns white: white light costs the night vision of everybody around. */
@Composable
private fun Confirmation(onKeepRed: () -> Unit, onWhite: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(16.dp)).background(Color.Black).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Switch to white light?", color = TorchRed, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "It spoils your night vision, and anyone else's nearby.",
            color = TorchRed, fontSize = 13.sp, textAlign = TextAlign.Center,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TorchButton("Keep red", filled = false, onClick = onKeepRed, modifier = Modifier.weight(1f))
            TorchButton("White light", filled = true, onClick = onWhite, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun Controls(brightness: Double, onBrightness: (Double) -> Unit, onClose: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = 0.12f)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Brightness", color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text("${(brightness * 100).roundToInt()}%", color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
        BrightnessBar(brightness, onBrightness)
        TorchButton("Close", filled = true, onClick = onClose, onLight = true, modifier = Modifier.fillMaxWidth())
    }
}

/**
 * The brightness control: a bar to touch or drag, drawn in black over the torch's own colour, because the system slider's
 * track is grey and would put green and blue light into a red screen.
 */
@Composable
private fun BrightnessBar(value: Double, onValue: (Double) -> Unit) {
    var width by remember { mutableStateOf(1f) }
    val fraction = ((value - MINIMUM) / (1.0 - MINIMUM)).toFloat().coerceIn(0f, 1f)
    fun set(x: Float) = onValue(MINIMUM + (x / width).coerceIn(0f, 1f) * (1.0 - MINIMUM))
    Box(
        Modifier
            .fillMaxWidth()
            .height(28.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.25f))
            .onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(Unit) { detectTapGestures { set(it.x) } }
            .pointerInput(Unit) { detectDragGestures { change, _ -> set(change.position.x) } }
            .semantics {
                contentDescription = "Torch brightness"
                stateDescription = "${(value * 100).roundToInt()}%"
                setProgress { target -> onValue(target.toDouble().coerceIn(MINIMUM, 1.0)); true }
            }
            .testTag("torchBrightness"),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction.coerceAtLeast(0.04f))
                .clip(CircleShape)
                .background(Color.Black),
        )
    }
}

private const val MINIMUM = 0.01

/** A pill button: red on black inside the confirmation, black on the torch's own colour in the controls. */
@Composable
private fun TorchButton(text: String, filled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, onLight: Boolean = false) {
    val ink = if (onLight) Color.Black else TorchRed
    val label = if (filled) (if (onLight) TorchRed else Color.Black) else ink
    Box(
        modifier
            .height(48.dp)
            .clip(CircleShape)
            .then(if (filled) Modifier.background(ink) else Modifier)
            .border(1.5.dp, ink, CircleShape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}
