package net.kuecker.lodestar.wear

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import net.kuecker.lodestar.kit.wrist.AimWristMessage
import kotlin.math.PI

/** The colours of the page: light on dark by day, red shades only when the phone is in night mode. */
private data class AimColors(val background: Color, val primary: Color, val secondary: Color, val onTarget: Color) {
    companion object {
        val Day = AimColors(
            Color(0xFF05070D), Color(0xFFF2F4FF), Color(0xFF9AA3B8), Color(0xFF4DD973),
        )
        val Night = AimColors(
            Color.Black, Color(0xFFFF3830), Color(0xFF9E2420), Color(0xFFFF3830),
        )
    }
}

/**
 * What the phone's Aim screen asks of the photographer, readable at a glance while both hands are on the tripod head:
 * a big arrow along the pan and tilt axes, the moves in degrees, the distance and the target; a check mark when on
 * target; "Waiting for phone" when the phone says nothing. In the phone's night mode the page is pure red.
 */
@Composable
fun AimScreen(received: WearAimTracker.Received?) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    // Once in a while the page notices that the messages have stopped.
    LaunchedEffect(Unit) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(500)
        }
    }
    val message = received?.takeIf { now - it.at <= WearAimTracker.STALE_AFTER_MILLIS }?.message
    val colors = if (received?.message?.isNight == true) AimColors.Night else AimColors.Day
    Column(
        modifier = Modifier.fillMaxSize().background(colors.background).padding(horizontal = 28.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (message == null) Waiting(colors) else Aim(message, colors)
    }
}

@Composable
private fun Aim(message: AimWristMessage, colors: AimColors) {
    Column(
        modifier = Modifier.clearAndSetSemantics { contentDescription = message.spokenSummary },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        when {
            message.isBelowHorizon -> Line("Below the horizon", colors.primary, 18, bold = true)
            message.isOnTarget -> {
                Check(colors.onTarget)
                Line("On target", colors.onTarget, 18, bold = true)
            }
            else -> {
                Arrow(message.arrowAngle, colors.primary)
                for (move in message.moves) Line(move, colors.primary, 17, bold = true)
                Line(message.distanceText, colors.secondary, 12)
            }
        }
        if (message.targetName.isNotEmpty()) Line(message.targetName, colors.secondary, 12)
    }
}

@Composable
private fun Waiting(colors: AimColors) {
    Column(
        modifier = Modifier.clearAndSetSemantics { contentDescription = "Waiting for phone. Open Aim on your phone." },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Line("Waiting for phone…", colors.primary, 15, bold = true)
        Line("Open Aim on your phone and pick a target.", colors.secondary, 12, maxLines = 3)
    }
}

@Composable
private fun Line(text: String, color: Color, size: Int, bold: Boolean = false, maxLines: Int = 1) {
    BasicText(
        text = text,
        style = TextStyle(
            color = color,
            fontSize = size.sp,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            textAlign = TextAlign.Center,
        ),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/** An arrow pointing [angle] radians counter-clockwise from "right", so that up on the screen is tilting up. */
@Composable
private fun Arrow(angle: Double, color: Color) {
    Canvas(Modifier.size(56.dp)) {
        val r = size.minDimension / 2
        val width = r * 0.28f
        rotate(degrees = (-angle * 180 / PI).toFloat(), pivot = center) {
            val shaftStart = center - Offset(r * 0.8f, 0f)
            val head = Path().apply {
                moveTo(center.x + r * 0.25f, center.y - r * 0.55f)
                lineTo(center.x + r * 0.9f, center.y)
                lineTo(center.x + r * 0.25f, center.y + r * 0.55f)
            }
            drawLine(color, shaftStart, center + Offset(r * 0.8f, 0f), strokeWidth = width, cap = StrokeCap.Round)
            drawPath(head, color, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/** A ring with a check mark: the camera is on the target. */
@Composable
private fun Check(color: Color) {
    Canvas(Modifier.size(56.dp)) {
        val width = size.minDimension * 0.08f
        drawCircle(color, radius = size.minDimension / 2 - width, style = Stroke(width))
        val mark = Path().apply {
            moveTo(size.width * 0.3f, size.height * 0.52f)
            lineTo(size.width * 0.45f, size.height * 0.67f)
            lineTo(size.width * 0.72f, size.height * 0.36f)
        }
        drawPath(mark, color, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
