package net.kuecker.lodestar.features.settings

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.kuecker.lodestar.features.mount.MountControlScreen
import net.kuecker.lodestar.features.mount.SettingsMountControlSection
import net.kuecker.lodestar.features.tools.ExposureCalculatorScreen
import net.kuecker.lodestar.features.tools.PolarAlignmentScreen
import net.kuecker.lodestar.features.tools.SensorDiagnosticsScreen
import net.kuecker.lodestar.features.tools.TrailsTimeLapseScreen
import net.kuecker.lodestar.shared.NavStack
import net.kuecker.lodestar.shared.NavStackHost
import net.kuecker.lodestar.shared.SettingsMenuOption
import net.kuecker.lodestar.shared.SettingsRow
import net.kuecker.lodestar.shared.rememberRetained
import net.kuecker.lodestar.theme.LocalPalette
import net.kuecker.lodestar.theme.screenBackground

/** The screens the Settings tab moves between. */
internal sealed interface SettingsRoute {
    data object Root : SettingsRoute
    data object MountPicker : SettingsRoute
    data object MountControl : SettingsRoute
    data object AccuracyNotes : SettingsRoute
    data object DataCredits : SettingsRoute
    data object ExposureCalculator : SettingsRoute
    data object TrailsTimeLapse : SettingsRoute
    data object PolarAlignment : SettingsRoute
    data object SensorDiagnostics : SettingsRoute
}

/**
 * The Settings tab: observing location, phone mount, camera and lens, night mode and the other preferences, the
 * photography tools, and About. Its navigation stack and scroll position survive tab switches.
 */
@Composable
fun SettingsScreen() {
    val stack = rememberRetained("settings.nav") { NavStack<SettingsRoute>(SettingsRoute.Root) }
    val back: () -> Unit = { stack.pop() }
    NavStackHost(stack, Modifier.fillMaxSize()) { route ->
        when (route) {
            SettingsRoute.Root -> SettingsRoot(onOpen = stack::push)
            SettingsRoute.MountPicker -> MountPresetPickerView(onBack = back)
            SettingsRoute.MountControl -> MountControlScreen(onBack = back)
            SettingsRoute.AccuracyNotes -> AccuracyNotesView(onBack = back)
            SettingsRoute.DataCredits -> DataCreditsView(onBack = back)
            SettingsRoute.ExposureCalculator -> ExposureCalculatorScreen(onBack = back)
            SettingsRoute.TrailsTimeLapse -> TrailsTimeLapseScreen(onBack = back)
            SettingsRoute.PolarAlignment -> PolarAlignmentScreen(onBack = back)
            SettingsRoute.SensorDiagnostics -> SensorDiagnosticsScreen(onBack = back)
        }
    }
}

@Composable
private fun SettingsRoot(onOpen: (SettingsRoute) -> Unit) {
    val palette = LocalPalette.current
    val scroll = rememberRetained("settings.scroll") { ScrollState(0) }
    Column(Modifier.fillMaxSize().screenBackground(palette)) {
        Text(
            "Settings",
            color = palette.primaryText,
            style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp).semantics { heading() },
        )
        Column(Modifier.weight(1f).imePadding().verticalScroll(scroll)) {
            SettingsLocationSection()
            SettingsMountSection(onOpen)
            SettingsMountControlSection(onOpen = { onOpen(SettingsRoute.MountControl) })
            SettingsCameraSection()
            SettingsNightSection()
            SettingsFeedbackSection()
            SettingsSkyDisplaySection()
            SettingsAtmosphereSection()
            SettingsToolsSection(onOpen)
            SettingsAboutSection(onOpen)
            Spacer(Modifier.height(24.dp))
        }
    }
}

// Shared chrome of the Settings screens

/** The bar of a pushed screen: back arrow and [title]. */
@Composable
internal fun SettingsTopBar(title: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    Row(modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = palette.accent)
        }
        Text(
            title,
            color = palette.primaryText,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
    }
}

/** A pushed Settings screen: opaque background, [SettingsTopBar] and a scrolling column for the sections. */
@Composable
internal fun SettingsSubScreen(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val palette = LocalPalette.current
    Column(Modifier.fillMaxSize().screenBackground(palette)) {
        SettingsTopBar(title, onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            content()
            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * A title above a segmented control with one segment per option, drawn in the palette (the stock control would use
 * Material colours that are not red at night).
 */
@Composable
internal fun <T> SettingsSegmentedPicker(
    title: String,
    selection: T,
    options: List<SettingsMenuOption<T>>,
    onSelect: (T) -> Unit,
    titleStyle: TextStyle = MaterialTheme.typography.bodyLarge,
) {
    val palette = LocalPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = palette.primaryText, style = titleStyle)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = option.value == selection,
                    onClick = { onSelect(option.value) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = palette.surfaceRaised,
                        activeContentColor = palette.primaryText,
                        activeBorderColor = palette.accent.copy(alpha = 0.6f),
                        inactiveContainerColor = Color.Transparent,
                        inactiveContentColor = palette.secondaryText,
                        inactiveBorderColor = palette.stroke,
                    ),
                    icon = {},
                    label = { Text(option.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                )
            }
        }
    }
}

/** A tappable row with an accent-coloured label and optional [icon], the equivalent of a button in a form. */
@Composable
internal fun SettingsActionRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    isDestructive: Boolean = false,
) {
    val palette = LocalPalette.current
    val tint = if (isDestructive) palette.critical else palette.accent
    SettingsRow(
        onClick = if (enabled) onClick else null,
        modifier = modifier.alpha(if (enabled) 1f else 0.4f).semantics(mergeDescendants = true) {
            role = Role.Button
            if (!enabled) disabled()
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (icon != null) Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
            Text(title, color = tint, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** A row that opens another screen: [icon], label and a chevron. */
@Composable
internal fun SettingsNavigationRow(title: String, icon: ImageVector, onClick: () -> Unit) {
    val palette = LocalPalette.current
    SettingsRow(onClick = onClick, modifier = Modifier.semantics(mergeDescendants = true) { role = Role.Button }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, contentDescription = null, tint = palette.accent, modifier = Modifier.size(22.dp))
            Text(title, color = palette.primaryText, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = palette.tertiaryText)
        }
    }
}
