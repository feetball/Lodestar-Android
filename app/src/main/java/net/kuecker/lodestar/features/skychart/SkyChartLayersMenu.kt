package net.kuecker.lodestar.features.skychart

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import net.kuecker.lodestar.model.SettingsStore
import net.kuecker.lodestar.shared.AppFormat
import net.kuecker.lodestar.shared.monospacedDigits
import net.kuecker.lodestar.theme.LocalPalette
import net.kuecker.lodestar.theme.card

/** The range of the "faintest star" setting the menu offers, in magnitudes. */
private val STAR_LIMIT_RANGE = 3.0..6.5
private const val STAR_LIMIT_STEP = 0.5

/**
 * The menu that shows and hides the chart's layers.
 *
 * It is drawn by the app, not by the system, so it stays red in night mode. The first four layers are the "Sky Display"
 * preferences of the Settings screen, shared with the Aim screen; the rest belong to the chart alone ([preferences]).
 */
@Composable
internal fun SkyChartLayersMenu(
    store: SettingsStore,
    preferences: SkyChartPreferences,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val settings = store.settings
    Column(
        modifier
            .width(270.dp)
            .blocksTouches()
            .card(palette, 12.dp)
            .semantics {
                paneTitle = "Layers"
                dismiss {
                    onDismiss()
                    true
                }
            },
    ) {
        LayerRow("Constellation lines", settings.showConstellationLines) { isOn ->
            store.update { it.copy(showConstellationLines = isOn) }
        }
        LayerRow("Constellation names", settings.showConstellationNames) { isOn ->
            store.update { it.copy(showConstellationNames = isOn) }
        }
        LayerRow("Milky Way", settings.showMilkyWay) { isOn -> store.update { it.copy(showMilkyWay = isOn) } }
        LayerRow("Deep-sky objects", settings.showDeepSkyObjects) { isOn ->
            store.update { it.copy(showDeepSkyObjects = isOn) }
        }
        LayerRow("Star names", preferences.showsStarNames) { preferences.showsStarNames = it }
        LayerRow("Altitude grid", preferences.showsAltitudeGrid) { preferences.showsAltitudeGrid = it }
        LayerRow("Camera view", preferences.showsCameraView) { preferences.showsCameraView = it }
        StarLimitRow(settings.starMagnitudeLimit) { limit ->
            store.update { it.copy(starMagnitudeLimit = limit.coerceIn(STAR_LIMIT_RANGE)) }
        }
    }
}

@Composable
private fun StarLimitRow(limit: Double, onLimitChange: (Double) -> Unit) {
    val palette = LocalPalette.current
    // Tight spacing: the menu is only 270 dp wide, and the title should stay on one line at the default text size.
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Faintest star", Modifier.weight(1f), color = palette.primaryText, style = MaterialTheme.typography.bodyLarge)
        StepButton(Icons.Outlined.RemoveCircleOutline, "Show fewer stars", limit <= STAR_LIMIT_RANGE.start) {
            onLimitChange(limit - STAR_LIMIT_STEP)
        }
        Text(
            AppFormat.magnitude(limit),
            Modifier.widthIn(min = 56.dp),
            color = palette.secondaryText,
            style = MaterialTheme.typography.bodyLarge.monospacedDigits(),
            textAlign = TextAlign.Center,
        )
        StepButton(Icons.Outlined.AddCircleOutline, "Show more stars", limit >= STAR_LIMIT_RANGE.endInclusive) {
            onLimitChange(limit + STAR_LIMIT_STEP)
        }
    }
}

/**
 * A step button that dims by tint when it cannot step any further. It is 36 × 44 dp, as on iOS: the menu is only 270 dp
 * wide, and the full 48 dp touch target would leave "Faintest star" no room.
 */
@Composable
private fun StepButton(icon: ImageVector, description: String, isDisabled: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Box(
        Modifier.size(36.dp, 44.dp).clickable(enabled = !isDisabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = if (isDisabled) palette.tertiaryText else palette.accent,
            modifier = Modifier.size(28.dp),
        )
    }
}

/** One layer of the menu: a title and a check mark. */
@Composable
private fun LayerRow(title: String, isOn: Boolean, onChange: (Boolean) -> Unit) {
    val palette = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .toggleable(value = isOn, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            if (isOn) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (isOn) palette.accent else palette.tertiaryText,
            modifier = Modifier.size(24.dp),
        )
        Text(title, color = palette.primaryText, style = MaterialTheme.typography.bodyLarge)
    }
}
