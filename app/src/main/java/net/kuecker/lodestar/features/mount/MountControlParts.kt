package net.kuecker.lodestar.features.mount

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import net.kuecker.lodestar.kit.mount.MountEpoch
import net.kuecker.lodestar.kit.mount.SkyWatcherMountMode
import net.kuecker.lodestar.services.MountAdvanced
import net.kuecker.lodestar.services.MountAdvancedField
import net.kuecker.lodestar.services.MountProtocols
import net.kuecker.lodestar.services.MountService
import net.kuecker.lodestar.shared.SettingsCaption
import net.kuecker.lodestar.shared.SettingsDivider
import net.kuecker.lodestar.shared.SettingsMenuOption
import net.kuecker.lodestar.shared.SettingsMenuPicker
import net.kuecker.lodestar.shared.SettingsRow
import net.kuecker.lodestar.shared.SettingsSection
import net.kuecker.lodestar.shared.SettingsToggleRow
import net.kuecker.lodestar.theme.LocalPalette

/** A row with a title and a text field on the right, drawn in the palette (the stock field would not be red at night). */
@Composable
internal fun MountTextRow(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "",
) {
    val palette = LocalPalette.current
    val focus = LocalFocusManager.current
    SettingsRow {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, color = palette.primaryText, style = MaterialTheme.typography.bodyLarge)
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                if (value.isEmpty()) Text(placeholder, color = palette.tertiaryText, style = MaterialTheme.typography.bodyLarge)
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = palette.primaryText, textAlign = TextAlign.End),
                    cursorBrush = SolidColor(palette.accent),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                    modifier = Modifier.fillMaxWidth().widthIn(min = 120.dp).semantics { contentDescription = title },
                )
            }
        }
    }
}

/** The options only a few mounts need: the coordinate frame and the sense of the fast moves. */
@Composable
internal fun MountAdvancedRows(service: MountService, draft: MountDraft) {
    val preferences = service.preferences
    val advanced = preferences.settings.advanced
    val fields = draft.option.advancedFields
    val epochOptions = listOf<SettingsMenuOption<MountEpoch?>>(
        SettingsMenuOption("Automatic", null),
        SettingsMenuOption("J2000", MountEpoch.J2000),
        SettingsMenuOption("Current epoch (JNow)", MountEpoch.MEAN_OF_DATE),
    )
    fun change(transform: (MountAdvanced) -> MountAdvanced) = preferences.update { it.copy(advanced = transform(it.advanced)) }
    if (MountAdvancedField.EPOCH in fields) {
        SettingsDivider()
        SettingsMenuPicker("Coordinate frame", advanced.epoch, epochOptions) { value -> change { it.copy(epoch = value) } }
        SettingsRow {
            SettingsCaption(
                "Which sky coordinates the mount expects. Automatic is right for nearly every mount: JNow for LX200 " +
                    "and Celestron, J2000 for current SynScan firmware. Applies the next time you connect.",
            )
        }
    }
    if (MountAdvancedField.SWAP_EAST_WEST in fields) {
        SettingsDivider()
        SettingsToggleRow(
            title = "Swap east and west",
            checked = advanced.swapEastWest,
            onCheckedChange = { value -> change { it.copy(swapEastWest = value) } },
            caption = "Turn on if the east and west arrows move the mount the wrong way. Applies the next time you connect.",
        )
    }
    if (MountAdvancedField.ARRANGEMENT in fields) {
        val modes = buildList<SettingsMenuOption<SkyWatcherMountMode?>> {
            add(SettingsMenuOption("Automatic", null))
            for (mode in SkyWatcherMountMode.entries) add(SettingsMenuOption(mountArrangementTitle(mode), mode))
        }
        SettingsDivider()
        SettingsMenuPicker("Mount arrangement", advanced.skyWatcherMode, modes) { value -> change { it.copy(skyWatcherMode = value) } }
        SettingsRow {
            SettingsCaption(
                "Automatic takes it from the mount model when it connects: the AZ-GTi and AZ-GTe are alt-azimuth, the Star " +
                    "Adventurer GTi and EQ mounts equatorial. Choose Equatorial for an AZ-GTi on a wedge. Applies the next " +
                    "time you connect.",
            )
        }
    }
    if (MountAdvancedField.REVERSE_AXIS_1 in fields || MountAdvancedField.REVERSE_AXIS_2 in fields) {
        SettingsDivider()
        SettingsRow {
            SettingsCaption(
                "Check after the first star, with tracking on: tap East on the nudge pad. The view must move east, so the " +
                    "stars drift west in the camera. Tap North: the view must move north. If East and North are swapped, or " +
                    "one of them runs backwards, reverse an axis here. Changing these applies the next time you connect " +
                    "and clears the alignment.",
            )
        }
    }
    if (MountAdvancedField.REVERSE_AXIS_1 in fields) {
        SettingsDivider()
        SettingsToggleRow(
            title = "Reverse axis 1",
            checked = advanced.reverseAxis1,
            onCheckedChange = { value -> change { it.copy(reverseAxis1 = value) } },
            caption = "The azimuth axis of an alt-azimuth mount, the right ascension axis of an equatorial one. Turn on if East " +
                "and West move the view the wrong way.",
        )
    }
    if (MountAdvancedField.REVERSE_AXIS_2 in fields) {
        SettingsDivider()
        SettingsToggleRow(
            title = "Reverse axis 2",
            checked = advanced.reverseAxis2,
            onCheckedChange = { value -> change { it.copy(reverseAxis2 = value) } },
            caption = "The altitude axis of an alt-azimuth mount, the declination axis of an equatorial one. Turn on if North " +
                "and South move the view the wrong way.",
        )
    }
    if (MountAdvancedField.SWAP_NORTH_SOUTH in fields) {
        SettingsDivider()
        SettingsToggleRow(
            title = "Swap north and south",
            checked = advanced.swapNorthSouth,
            onCheckedChange = { value -> change { it.copy(swapNorthSouth = value) } },
            caption = "Turn on if the north and south arrows move the mount the wrong way. Applies the next time you connect.",
        )
    }
}

/** The rules that keep people and equipment safe, in plain words. */
@Composable
internal fun MountSafetySection() {
    val palette = LocalPalette.current
    SettingsSection(title = "Safety") {
        SettingsRow {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.Shield, contentDescription = null, tint = palette.accent, modifier = Modifier.size(20.dp))
                Text(
                    "Before you slew",
                    color = palette.primaryText,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.semantics { heading() },
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (rule in SAFETY_RULES) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("•", color = palette.secondaryText, style = MaterialTheme.typography.bodyMedium)
                        Text(rule, color = palette.secondaryText, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

private val SAFETY_RULES = listOf(
    "Keep people, hands and cables clear of the mount. It can move fast and with force.",
    "The mount only moves when you tap Go to or hold a nudge arrow. Connecting, aiming and syncing never start a slew.",
    "Stop is always on the Aim screen while a mount is connected, and it stops the mount ahead of everything else. " +
        "Keep the mount's own controls or power switch in reach as well.",
    "Leaving the app stops the mount and disconnects it. It reconnects when you come back, without moving.",
    "Lodestar will not slew below the altitude limit, and the mount's own limits still apply.",
    "Lodestar never slews to the Sun, but it does not check the path or the field of view. Do not use GoTo by day " +
        "unless the optics have a certified solar filter.",
)

/** What each kind of mount works with, from the protocol list (so a newly supported kind shows up here too). */
@Composable
internal fun MountProtocolsSection() {
    val palette = LocalPalette.current
    SettingsSection(title = "What works with what") {
        MountProtocols.all.forEachIndexed { index, option ->
            if (index > 0) SettingsDivider()
            SettingsRow {
                Text(
                    option.title,
                    color = palette.primaryText,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.semantics { heading() },
                )
                SettingsCaption(option.explanation)
            }
        }
    }
}
