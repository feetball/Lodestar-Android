package net.kuecker.lodestar.shared

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LocationOff
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.LocationSearching
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.kuecker.lodestar.app.AppTab
import net.kuecker.lodestar.kit.catalog.ObjectCategory
import net.kuecker.lodestar.kit.catalog.ObjectGroup
import net.kuecker.lodestar.theme.LocalPalette

/** Text style with tabular (monospaced) digits, for values that update in place. */
fun TextStyle.monospacedDigits(): TextStyle = copy(fontFeatureSettings = "tnum")

/** The icon for an object category, coloured from the palette. */
@Composable
fun ObjectIcon(category: ObjectCategory, modifier: Modifier = Modifier, size: Dp = 18.dp) {
    val palette = LocalPalette.current
    val tint = when (category.group) {
        ObjectGroup.SOLAR_SYSTEM -> palette.planetMarker
        ObjectGroup.STARS -> palette.primaryText
        ObjectGroup.GALAXIES, ObjectGroup.NEBULAE, ObjectGroup.CLUSTERS -> palette.deepSkyMarker
        ObjectGroup.SPECIAL -> palette.accent
    }
    Box(modifier.size(size * 1.6f), contentAlignment = Alignment.Center) {
        Icon(category.icon, contentDescription = null, tint = tint, modifier = Modifier.size(size))
    }
}

/** Visual tone of a status chip. */
enum class ChipTone { NEUTRAL, POSITIVE, WARNING, CRITICAL }

/** A compact capsule with an icon and a short label, used for sensor/alignment status. */
@Composable
fun StatusChip(icon: ImageVector, text: String, modifier: Modifier = Modifier, tone: ChipTone = ChipTone.NEUTRAL) {
    val palette = LocalPalette.current
    val foreground = when (tone) {
        ChipTone.NEUTRAL -> palette.secondaryText
        ChipTone.POSITIVE -> palette.positive
        ChipTone.WARNING -> palette.warning
        ChipTone.CRITICAL -> palette.critical
    }
    Row(
        modifier
            .background(palette.surface.copy(alpha = 0.85f), CircleShape)
            .border(0.75.dp, foreground.copy(alpha = 0.45f), CircleShape)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, contentDescription = null, tint = foreground, modifier = Modifier.size(14.dp))
        Text(
            text,
            color = foreground,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A label/value row used in detail cards. */
@Composable
fun InfoRow(label: String, value: String, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    Row(modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.Top) {
        Text(label, color = palette.secondaryText, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(12.dp).weight(1f))
        Text(
            value,
            color = palette.primaryText,
            style = MaterialTheme.typography.bodyMedium.monospacedDigits(),
            textAlign = TextAlign.End,
        )
    }
}

/** Section title used inside scrolling card layouts. */
@Composable
fun CardTitle(title: String, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val palette = LocalPalette.current
    Row(
        modifier.fillMaxWidth().semantics { heading() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = palette.secondaryText, modifier = Modifier.size(15.dp))
        Text(
            title.uppercase(),
            color = palette.secondaryText,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp),
        )
    }
}

/** Filled (primary) or outlined (secondary) capsule button. */
enum class LodestarButtonKind { PRIMARY, SECONDARY }

/**
 * Capsule button drawn entirely with palette colours, so it stays red-only in night mode. Use it instead of
 * Material's filled buttons for prominent actions (as `.lodestarPrimary` / `.lodestarSecondary` on iOS).
 */
@Composable
fun LodestarButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: LodestarButtonKind = LodestarButtonKind.PRIMARY,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val palette = LocalPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val primary = kind == LodestarButtonKind.PRIMARY
    val foreground = if (primary) palette.onAccent else palette.accent
    Row(
        modifier
            .alpha(if (!enabled) 0.4f else if (pressed) 0.75f else 1f)
            .heightIn(min = 44.dp)
            .background(if (primary) palette.accent else palette.surfaceRaised, CircleShape)
            .border(BorderStroke(1.dp, if (primary) Color.Transparent else palette.accent.copy(alpha = 0.6f)), CircleShape)
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides foreground,
            androidx.compose.material3.LocalTextStyle provides MaterialTheme.typography.bodyLarge.copy(
                fontWeight = FontWeight.SemiBold,
                color = foreground,
            ),
        ) { content() }
    }
}

/** [LodestarButton] with a text label. */
@Composable
fun LodestarButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: LodestarButtonKind = LodestarButtonKind.PRIMARY,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    LodestarButton(onClick, modifier, kind, enabled) {
        if (icon != null) Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(text)
    }
}

/** Palette-coloured empty state with optional actions below the message. */
@Composable
fun EmptyStateView(
    title: String,
    icon: ImageVector,
    message: String,
    modifier: Modifier = Modifier,
    actions: @Composable ColumnScope.() -> Unit = {},
) {
    val palette = LocalPalette.current
    Column(
        modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
    ) {
        Icon(icon, contentDescription = null, tint = palette.tertiaryText, modifier = Modifier.size(44.dp))
        Text(
            title,
            color = palette.primaryText,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
            textAlign = TextAlign.Center,
        )
        Text(message, color = palette.secondaryText, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        Column(
            Modifier.padding(top = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = actions,
        )
    }
}

/**
 * Shown when no observing location is available yet. Offers the next step for the current state: asking for
 * permission, waiting for the first fix, or asking again (Android opens the app's settings when it no longer shows
 * the dialog) — and always the alternative of choosing an observing site.
 */
@Composable
fun NeedsLocationView(modifier: Modifier = Modifier) {
    val app = LocalAppModel.current
    val location = app.location
    val error = location.lastErrorDescription
    val (title, icon, message) = when {
        location.isDenied -> Triple(
            "Location Access Is Off",
            Icons.Outlined.LocationOff,
            "Allow location access for Lodestar, or choose an observing site on the map.",
        )
        location.isAuthorized -> Triple(
            "Finding Your Location",
            Icons.Outlined.LocationSearching,
            error ?: "Waiting for the first position fix. Under open sky this takes a few seconds.",
        )
        else -> Triple(
            "Location Needed",
            Icons.Outlined.LocationOn,
            "Lodestar needs to know where you are to work out where things are in your sky. Approximate location is enough.",
        )
    }
    EmptyStateView(title, icon, message, modifier) {
        when {
            location.isDenied -> LodestarButton("Allow Location Access", onClick = location::requestAgain)
            !location.isAuthorized -> LodestarButton("Allow Location Access", onClick = location::requestAuthorization)
        }
        LodestarButton(
            "Choose an Observing Site",
            onClick = { app.selectedTab = AppTab.SETTINGS },
            kind = LodestarButtonKind.SECONDARY,
        )
    }
}
