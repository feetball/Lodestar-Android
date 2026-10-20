package net.kuecker.lodestar.features.aim

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.kuecker.lodestar.shared.AppFormat
import net.kuecker.lodestar.shared.monospacedDigits
import net.kuecker.lodestar.theme.LocalPalette
import kotlin.math.abs

/**
 * Shows how far the camera is rolled about its optical axis, like the horizon of an aircraft's attitude indicator:
 * the line is the world's horizon as the camera sees it, so it is level when the camera is. Refreshed ten times a
 * second.
 */
@Composable
internal fun AimLevelGauge(model: AimViewModel, isActive: Boolean, modifier: Modifier = Modifier) {
    val frame = rememberAimFrame(model, 100, isActive)
    AimLevelDial(roll = frame?.pointing?.roll, modifier = modifier)
}

/** Rolls smaller than this count as level, in degrees. */
private const val LEVEL_TOLERANCE = 0.5

/** [roll] is `PointingSolution.roll` in degrees: positive when the camera is turned clockwise (its right side lower). */
@Composable
private fun AimLevelDial(roll: Double?, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val isLevel = roll != null && abs(roll) <= LEVEL_TOLERANCE
    val rollText = if (roll == null) AppFormat.MISSING else "${AimFormat.angle(roll)} ${if (roll > 0) "right" else "left"}"
    val color = if (isLevel) palette.positive else palette.guidance
    Column(
        modifier
            .width(78.dp)
            .clearAndSetSemantics {
                contentDescription = "Camera level"
                stateDescription = if (isLevel) "Level" else rollText
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // The same backing as the cards, so the sky labels under the dial and its caption do not run through them.
        val surface = palette.surface.copy(alpha = AIM_PANEL_ALPHA)
        Canvas(Modifier.size(58.dp)) {
            val center = Offset(size.width / 2, size.height / 2)
            drawCircle(surface, radius = size.width / 2, center = center)
            val border = 1.5.dp.toPx()
            drawCircle(palette.stroke, radius = (size.width - border) / 2, center = center, style = Stroke(border))
            // A camera rolled clockwise sees the horizon turn counter-clockwise; drawing turns clockwise.
            rotate(degrees = -(roll ?: 0.0).toFloat(), pivot = center) {
                val length = 42.dp.toPx()
                val thickness = 3.dp.toPx()
                drawRoundRect(
                    color,
                    topLeft = Offset(center.x - length / 2, center.y - thickness / 2),
                    size = Size(length, thickness),
                    cornerRadius = CornerRadius(thickness / 2),
                )
            }
            for (side in listOf(-1f, 1f)) {
                drawRect(
                    palette.tertiaryText,
                    topLeft = Offset(center.x + side * 26.dp.toPx() - 3.dp.toPx(), center.y - 0.5.dp.toPx()),
                    size = Size(6.dp.toPx(), 1.dp.toPx()),
                )
            }
        }
        Row(
            Modifier.background(surface, CircleShape).padding(horizontal = 4.dp, vertical = 1.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Icon(
                if (isLevel) Icons.Filled.Check else Icons.Outlined.Straighten,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(12.dp),
            )
            AimFittingText(
                text = if (isLevel) "Level" else rollText,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold).monospacedDigits(),
                color = color,
                minimumScale = 0.7f,
            )
        }
    }
}
