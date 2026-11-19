package net.kuecker.lodestar.features.settings

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import net.kuecker.lodestar.model.AppSettings
import net.kuecker.lodestar.model.CameraProfile
import net.kuecker.lodestar.shared.LocalAppModel
import net.kuecker.lodestar.shared.LocalSheetDismiss
import net.kuecker.lodestar.shared.LodestarSheet
import net.kuecker.lodestar.shared.SettingsCaption
import net.kuecker.lodestar.shared.SettingsDivider
import net.kuecker.lodestar.shared.SettingsRow
import net.kuecker.lodestar.shared.SettingsSection
import net.kuecker.lodestar.shared.SettingsToggleRow
import net.kuecker.lodestar.theme.LocalPalette

/**
 * Settings ▸ Camera & Lens: the cameras and lenses the photographer uses, one of them active. The active profile gives the
 * framing rectangle on the Aim screen and the sky chart and the numbers of the exposure tools. Tap a profile to use it,
 * the pencil to edit it, press and hold for more; "Add Preset…" starts from a smart telescope or a plain camera and
 * "From a Photo…" reads the camera and lens from the EXIF data of a picture.
 */
@Composable
internal fun SettingsCameraSection() {
    val app = LocalAppModel.current
    val store = app.settingsStore
    val settings = app.settings
    val isFull = settings.cameraProfiles.size >= AppSettings.MAXIMUM_CAMERA_PROFILES
    val canDelete = settings.cameraProfiles.size > 1
    var editorTarget by remember { mutableStateOf<CameraProfileEditorTarget?>(null) }
    var showsPresets by remember { mutableStateOf(false) }
    val photoImport = rememberCameraPhotoImport { result -> editorTarget = CameraProfileEditorTarget.FromPhoto(result) }

    SettingsSection(
        title = "Camera & Lens",
        footer = "Describe the camera on the tripod, not the phone. The active profile gives the framing rectangle on " +
            "the Aim screen and the sky chart, and the numbers of the exposure tools. A fisheye lens draws a curved " +
            "frame. Exposure rules use the real focal length, as for any lens.",
    ) {
        for ((index, profile) in settings.cameraProfiles.withIndex()) {
            if (index > 0) SettingsDivider()
            CameraProfileRow(
                profile = profile,
                isActive = profile.id == settings.activeCameraProfile.id,
                canDelete = canDelete,
                onActivate = { store.activateCameraProfile(profile.id) },
                onEdit = { editorTarget = CameraProfileEditorTarget.Existing(profile) },
                onDelete = { store.removeCameraProfile(profile.id) },
            )
        }
        SettingsDivider()
        SettingsActionRow(
            title = "Add Preset…",
            icon = Icons.Outlined.AddCircleOutline,
            enabled = !isFull,
            onClick = { showsPresets = true },
            caption = "Smart telescopes such as the Seestar and the Dwarf, or a plain full-frame camera to start from.",
        )
        SettingsDivider()
        SettingsActionRow(
            title = "From a Photo…",
            icon = Icons.Outlined.PhotoLibrary,
            enabled = !isFull,
            onClick = photoImport.pick,
            caption = "Reads the camera and lens from the data in a picture you took. The picture is not kept.",
        )
        photoImport.message?.let {
            SettingsDivider()
            SettingsRow { SettingsCaption(it) }
        }
        if (isFull) {
            SettingsDivider()
            SettingsRow { SettingsCaption("You have reached the limit of ${AppSettings.MAXIMUM_CAMERA_PROFILES} profiles.") }
        }
        SettingsDivider()
        SettingsFieldOfViewReadout(app.lensSetup)
        SettingsDivider()
        SettingsToggleRow(
            title = "Camera preview",
            checked = settings.cameraPreviewEnabled,
            onCheckedChange = { enabled -> store.update { it.copy(cameraPreviewEnabled = enabled) } },
            caption = "Shows the phone's camera behind the sky on the Aim screen. Lodestar asks for camera access " +
                "the first time you turn it on.",
        )
    }

    if (showsPresets) {
        LodestarSheet(onDismiss = { showsPresets = false }) {
            CameraPresetPicker(onPick = { preset ->
                showsPresets = false
                editorTarget = CameraProfileEditorTarget.FromPreset(preset)
            })
        }
    }

    editorTarget?.let { target ->
        LodestarSheet(onDismiss = { editorTarget = null }) {
            CameraProfileEditor(target = target, onClose = LocalSheetDismiss.current, modifier = Modifier.weight(1f))
        }
    }
}

/**
 * A profile in the list: tap to use it, the pencil to edit it, press and hold for a menu with Edit and Delete (the last
 * profile cannot be deleted). Accessibility services get the same two actions.
 */
@Composable
private fun CameraProfileRow(
    profile: CameraProfile,
    isActive: Boolean,
    canDelete: Boolean,
    onActivate: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val palette = LocalPalette.current
    val haptics = LocalHapticFeedback.current
    var isMenuOpen by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        role = Role.RadioButton,
                        onClickLabel = "Use this camera",
                        onLongClickLabel = "More actions",
                        onLongClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            isMenuOpen = true
                        },
                        onClick = onActivate,
                    )
                    .semantics {
                        selected = isActive
                        customActions = buildList {
                            add(CustomAccessibilityAction("Edit") { onEdit(); true })
                            if (canDelete) add(CustomAccessibilityAction("Delete") { onDelete(); true })
                        }
                    }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SettingsSelectionMark(isActive)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(profile.name, color = palette.primaryText, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        CameraProfileText.summary(profile), color = palette.secondaryText,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            DropdownMenu(expanded = isMenuOpen, onDismissRequest = { isMenuOpen = false }, containerColor = palette.surfaceRaised) {
                DropdownMenuItem(
                    text = { Text("Edit", color = palette.primaryText) },
                    onClick = { isMenuOpen = false; onEdit() },
                )
                DropdownMenuItem(
                    text = { Text(if (canDelete) "Delete" else "Delete (keep one camera)", color = if (canDelete) palette.critical else palette.tertiaryText) },
                    enabled = canDelete,
                    onClick = { isMenuOpen = false; onDelete() },
                )
            }
        }
        IconButton(onClick = onEdit) {
            Icon(Icons.Outlined.Edit, contentDescription = "Edit ${profile.name}", tint = palette.accent)
        }
    }
}
