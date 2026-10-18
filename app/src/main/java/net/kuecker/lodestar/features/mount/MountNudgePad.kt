package net.kuecker.lodestar.features.mount

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.kuecker.lodestar.features.settings.SettingsSegmentedPicker
import net.kuecker.lodestar.kit.mount.MountNudgeDirection
import net.kuecker.lodestar.kit.mount.MountNudgeRate
import net.kuecker.lodestar.services.MountService
import net.kuecker.lodestar.shared.SettingsMenuOption
import net.kuecker.lodestar.theme.LocalPalette

/** Milliseconds between the repeats of a nudge while an arrow is held; each nudge lasts half a second. */
private const val NUDGE_REPEAT_MS = 400L

/**
 * Four arrows for fine centring: north and south move along declination, east and west along right ascension
 * (north up, east left, as on a star chart). Hold an arrow to keep moving; the mount stops when the finger lifts. With a
 * screen reader an arrow is a button that moves the mount for half a second. The middle button is Stop.
 */
@Composable
internal fun MountNudgePad(service: MountService, rate: MountNudgeRate, isEnabled: Boolean, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        NudgeArrow(service, MountNudgeDirection.NORTH, rate, isEnabled, 0f, "N")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            NudgeArrow(service, MountNudgeDirection.EAST, rate, isEnabled, -90f, "E")
            MountStopButton(onStop = service::stop, modifier = Modifier.size(width = 88.dp, height = 64.dp))
            NudgeArrow(service, MountNudgeDirection.WEST, rate, isEnabled, 90f, "W")
        }
        NudgeArrow(service, MountNudgeDirection.SOUTH, rate, isEnabled, 180f, "S")
    }
}

@Composable
private fun NudgeArrow(
    service: MountService,
    direction: MountNudgeDirection,
    rate: MountNudgeRate,
    isEnabled: Boolean,
    arrowDegrees: Float,
    letter: String,
) {
    val palette = LocalPalette.current
    val name = MountText.direction(direction)
    val currentRate by rememberUpdatedState(rate)
    var isPressed by remember { mutableStateOf(false) }
    Box(
        Modifier
            .size(64.dp)
            .alpha(if (!isEnabled) 0.4f else if (isPressed) 0.7f else 1f)
            .background(if (isPressed) palette.accent else palette.surfaceRaised, CircleShape)
            .border(1.dp, palette.accent.copy(alpha = 0.6f), CircleShape)
            .pointerInput(isEnabled, direction) {
                if (!isEnabled) return@pointerInput
                detectTapGestures(
                    onPress = {
                        isPressed = true
                        try {
                            coroutineScope {
                                val repeat = launch {
                                    while (true) {
                                        service.nudge(direction, currentRate)
                                        delay(NUDGE_REPEAT_MS)
                                    }
                                }
                                tryAwaitRelease()
                                repeat.cancel()
                            }
                        } finally {
                            isPressed = false
                            service.stopNudge()
                        }
                    },
                )
            }
            .semantics {
                role = Role.Button
                contentDescription = "Nudge $name"
                if (!isEnabled) {
                    disabled()
                } else {
                    // The nudge ends by itself after half a second.
                    onClick(label = "Moves the mount $name for half a second") {
                        service.nudge(direction, currentRate)
                        true
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Filled.ArrowUpward,
                contentDescription = null,
                tint = if (isPressed) palette.onAccent else palette.accent,
                modifier = Modifier.size(24.dp).rotate(arrowDegrees),
            )
            Text(
                letter,
                color = if (isPressed) palette.onAccent else palette.primaryText,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** The rates as a segmented control, with the chosen one described underneath. */
@Composable
internal fun MountRatePicker(rate: MountNudgeRate, onSelect: (MountNudgeRate) -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val options = MountNudgeRate.entries.map { SettingsMenuOption(MountText.rateName(it), it) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SettingsSegmentedPicker(
            title = "Nudge rate",
            selection = rate,
            options = options,
            onSelect = onSelect,
        )
        Text(MountText.rateDescription(rate), color = palette.secondaryText, style = MaterialTheme.typography.bodySmall)
    }
}
