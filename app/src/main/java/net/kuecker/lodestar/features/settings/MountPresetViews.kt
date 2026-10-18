package net.kuecker.lodestar.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.kuecker.lodestar.kit.pointing.MountProfile
import net.kuecker.lodestar.shared.LocalAppModel
import net.kuecker.lodestar.shared.SettingsDivider
import net.kuecker.lodestar.shared.SettingsRow
import net.kuecker.lodestar.shared.SettingsSection
import net.kuecker.lodestar.theme.LocalPalette

/**
 * One mount preset as a row: its picture, name and description, and a check mark when it is the chosen one.
 * [showsSelection] reserves room for the check mark (off for a summary row that only shows the current mount).
 */
@Composable
internal fun MountPresetRowLabel(
    profile: MountProfile,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    showsSelection: Boolean = true,
) {
    val palette = LocalPalette.current
    val layout = remember(profile) { MountLayout.of(profile) }
    Row(
        modifier.fillMaxWidth().semantics(mergeDescendants = true) { selected = isSelected },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        MountIllustration(layout)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                profile.name,
                color = palette.primaryText,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
            )
            Text(layout.summary, color = palette.secondaryText, style = MaterialTheme.typography.bodySmall)
        }
        if (showsSelection) SettingsSelectionMark(isSelected)
    }
}

/** The list of mount presets; choosing one stores it in the settings. Changing the mount clears the star alignment. */
@Composable
internal fun MountPresetPickerView(onBack: () -> Unit) {
    val app = LocalAppModel.current
    SettingsSubScreen("Phone Mount", onBack) {
        SettingsSection(
            title = "Phone Mount",
            footer = "Choose how the phone is clamped to the camera. Lodestar uses it to work out which way the " +
                "camera points and which way the arrows turn. Changing it clears your star alignment.",
        ) {
            MountProfile.presets.forEachIndexed { index, profile ->
                if (index > 0) SettingsDivider()
                SettingsRow(onClick = { app.settingsStore.update { it.copy(mountPresetID = profile.id) } }) {
                    MountPresetRowLabel(profile, isSelected = profile.id == app.mountProfile.id)
                }
            }
        }
    }
}
