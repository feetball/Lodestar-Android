package net.kuecker.lodestar.features.aim

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay
import net.kuecker.lodestar.theme.LocalPalette
import net.kuecker.lodestar.theme.Palette

/** A translucent card for the panels over the sky, so the picture stays visible behind it. */
internal fun Modifier.aimPanel(palette: Palette, padding: Dp = 10.dp): Modifier {
    val shape = RoundedCornerShape(14.dp)
    return background(palette.surface.copy(alpha = 0.88f), shape)
        .border(0.5.dp, palette.stroke, shape)
        .padding(padding)
}

/**
 * A square-ish control for the bar at the bottom: a symbol over a caption (the [content]), filled when its function
 * is [isOn]. Drawn from palette colours only; dimmed while disabled and while pressed. [onClickLabel] describes the
 * action to accessibility services.
 */
@Composable
internal fun AimControlButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isOn: Boolean = false,
    enabled: Boolean = true,
    minHeight: Dp = 54.dp,
    onClickLabel: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = LocalPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(12.dp)
    val foreground = if (isOn) palette.onAccent else palette.accent
    Column(
        modifier
            .alpha(if (!enabled) 0.4f else if (pressed) 0.7f else 1f)
            .heightIn(min = minHeight)
            .background(if (isOn) palette.accent else palette.surface.copy(alpha = 0.88f), shape)
            .then(if (isOn) Modifier else Modifier.border(0.75.dp, palette.stroke, shape))
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClickLabel = onClickLabel,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
    ) {
        CompositionLocalProvider(LocalContentColor provides foreground) { content() }
    }
}

/**
 * Single-line text that shrinks down to [minimumScale] of its style's size instead of being cut off (SwiftUI's
 * `minimumScaleFactor`). The [style] needs a font size.
 */
@Composable
internal fun AimFittingText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    minimumScale: Float = 0.75f,
    textAlign: TextAlign? = null,
) {
    Text(
        text,
        modifier = modifier,
        color = color,
        autoSize = TextAutoSize.StepBased(
            minFontSize = style.fontSize * minimumScale,
            maxFontSize = style.fontSize,
            stepSize = 0.25.sp,
        ),
        textAlign = textAlign,
        overflow = TextOverflow.Ellipsis,
        maxLines = 1,
        style = style,
    )
}

/** Content whose text ignores the system font size, like SwiftUI's fixed-size system fonts. */
@Composable
internal fun AimFixedTextSize(content: @Composable () -> Unit) {
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1f), content = content)
}

/** Below this interval a timeline follows the display's frames; above it, it simply waits. Milliseconds. */
private const val FRAME_SYNCHRONIZED_BELOW_MS = 100L

/** A frame is taken once this share of the interval has passed, so that 60, 90 and 120 Hz displays all keep the rhythm. */
private const val FRAME_INTERVAL_SHARE = 0.85

/**
 * Stands in for SwiftUI's `TimelineView(.animation(minimumInterval:paused:))`: the returned value changes about every
 * [intervalMillis] while [isActive] and stands still while not. Read it in composition to recompose that often; use
 * [rememberAimFrame] to poll the aiming frame with it.
 */
@Composable
private fun rememberAimTimeline(intervalMillis: Long, isActive: Boolean): State<Long> {
    val tick = remember { mutableLongStateOf(0L) }
    LaunchedEffect(intervalMillis, isActive) {
        if (!isActive) return@LaunchedEffect
        if (intervalMillis < FRAME_SYNCHRONIZED_BELOW_MS) {
            val threshold = (intervalMillis * FRAME_INTERVAL_SHARE).toLong()
            var last = withFrameMillis { it }
            while (true) {
                val now = withFrameMillis { it }
                if (now - last >= threshold) {
                    last = now
                    tick.longValue = now
                }
            }
        } else {
            while (true) {
                delay(intervalMillis)
                tick.longValue += 1
            }
        }
    }
    return tick
}

/**
 * The newest aiming frame, polled from the engine about every [intervalMillis] while [isActive] (`null` while the
 * sensors deliver nothing). The sensor rate never reaches Compose: only this timeline recomposes the caller.
 */
@Composable
internal fun rememberAimFrame(model: AimViewModel, intervalMillis: Long, isActive: Boolean): AimFrame? {
    val tick by rememberAimTimeline(intervalMillis, isActive)
    return remember(model, tick) { model.engine.frame() }
}
