package net.kuecker.lodestar.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Landscape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import net.kuecker.lodestar.features.horizon.HorizonCoverageMemory
import net.kuecker.lodestar.features.horizon.HorizonFormat
import net.kuecker.lodestar.features.horizon.HorizonRecordingScreen
import net.kuecker.lodestar.model.SavedSite
import net.kuecker.lodestar.shared.HideSystemBarsInWindow
import net.kuecker.lodestar.shared.LocalAppModel
import net.kuecker.lodestar.shared.LocalSystemBarsHidden
import net.kuecker.lodestar.shared.SettingsCaption
import net.kuecker.lodestar.shared.SettingsDivider
import net.kuecker.lodestar.shared.SettingsRow
import net.kuecker.lodestar.shared.SettingsSection
import net.kuecker.lodestar.theme.LocalPalette

/** What can be done to a site's skyline from a menu or a button. */
internal enum class SiteHorizonAction { RECORD, CLEAR }

/** The dialog or screen for [action] on [site]; [onFinished] is called when it is over. */
@Composable
internal fun SiteHorizonDialogs(site: SavedSite, action: SiteHorizonAction, onFinished: () -> Unit) {
    when (action) {
        SiteHorizonAction.RECORD -> HorizonRecordingScreen(site, onClose = onFinished)
        SiteHorizonAction.CLEAR -> SiteHorizonClearDialog(site, onFinished)
    }
}

/** "Open horizon" or "Recorded · 86% covered · highest 14° in the east" for [site], as stored now. */
@Composable
internal fun rememberSiteHorizonStatus(site: SavedSite): String {
    val context = LocalContext.current
    return remember(site.id, site.horizon) {
        HorizonFormat.status(site.horizon, HorizonCoverageMemory.recall(context, site))
    }
}

/**
 * The Horizon section of the site editor: the status of the site's skyline, and buttons to record it again or clear
 * it. A site that is not saved yet has no skyline to record. [site] is the site as it was stored when the editor
 * opened; the section follows what is stored now, because recording saves at once. [movedAway] says that the
 * position being edited is not where the site was.
 */
@Composable
internal fun SiteHorizonSection(site: SavedSite?, movedAway: Boolean) {
    val app = LocalAppModel.current
    var action by remember { mutableStateOf<SiteHorizonAction?>(null) }
    val stored = site?.let { original -> app.settings.savedSites.firstOrNull { it.id == original.id } }
    SettingsSection(
        title = "Horizon",
        footer = "Record the outline of trees, hills and buildings by turning slowly all the way round with the " +
            "camera on the skyline. Planning then counts what is behind it as hidden.",
    ) {
        if (stored == null) {
            SettingsRow { SettingsCaption("Save the site first, then record its skyline here.") }
        } else {
            val palette = LocalPalette.current
            val status = rememberSiteHorizonStatus(stored)
            SettingsRow {
                Text(status, color = palette.primaryText, style = MaterialTheme.typography.bodyLarge)
                if (movedAway && stored.horizon != null) {
                    SettingsCaption(
                        "You moved this site. A skyline belongs to one spot: record it again if the new place is " +
                            "different.",
                    )
                }
            }
            SettingsDivider()
            SettingsActionRow(
                title = "Record Skyline…",
                icon = Icons.Outlined.Landscape,
                onClick = { action = SiteHorizonAction.RECORD },
            )
            if (stored.horizon != null) {
                SettingsDivider()
                SettingsActionRow(
                    title = "Clear Skyline…",
                    isDestructive = true,
                    onClick = { action = SiteHorizonAction.CLEAR },
                )
            }
        }
    }
    val current = action
    if (current != null && stored != null) SiteHorizonDialogs(stored, current) { action = null }
}

/** The button on a saved site's row: a menu with Record Skyline and, once there is one, Clear Skyline. */
@Composable
internal fun SiteHorizonMenuButton(site: SavedSite) {
    val palette = LocalPalette.current
    var isOpen by remember { mutableStateOf(false) }
    var action by remember { mutableStateOf<SiteHorizonAction?>(null) }
    Box {
        IconButton(onClick = { isOpen = true }) {
            Icon(Icons.Outlined.Landscape, contentDescription = "Skyline options for ${site.name}", tint = palette.accent)
        }
        DropdownMenu(expanded = isOpen, onDismissRequest = { isOpen = false }, containerColor = palette.surfaceRaised) {
            DropdownMenuItem(
                text = { Text("Record Skyline…", color = palette.primaryText) },
                onClick = {
                    isOpen = false
                    action = SiteHorizonAction.RECORD
                },
            )
            if (site.horizon != null) {
                DropdownMenuItem(
                    text = { Text("Clear Skyline…", color = palette.critical) },
                    onClick = {
                        isOpen = false
                        action = SiteHorizonAction.CLEAR
                    },
                )
            }
        }
    }
    action?.let { SiteHorizonDialogs(site, it) { action = null } }
}

/** Asks to confirm that the skyline of [site] is deleted, then deletes it. */
@Composable
private fun SiteHorizonClearDialog(site: SavedSite, onFinished: () -> Unit) {
    val app = LocalAppModel.current
    val palette = LocalPalette.current
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onFinished,
        containerColor = palette.surfaceRaised,
        titleContentColor = palette.primaryText,
        textContentColor = palette.secondaryText,
        title = { Text("Clear the Skyline?") },
        text = {
            if (LocalSystemBarsHidden.current) HideSystemBarsInWindow()
            Text(
                "“${site.name}” goes back to an open horizon. You can record the skyline again, but this one cannot " +
                    "be brought back.",
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // The stored site may have changed since this dialog was asked for.
                    val current = app.settings.savedSites.firstOrNull { it.id == site.id } ?: site
                    current.edited(horizon = null)?.let(app.settingsStore::updateSite)
                    HorizonCoverageMemory.store(context, site.id, null, 0.0)
                    onFinished()
                },
                colors = ButtonDefaults.textButtonColors(contentColor = palette.critical),
            ) { Text("Clear Skyline") }
        },
        dismissButton = {
            TextButton(onClick = onFinished, colors = ButtonDefaults.textButtonColors(contentColor = palette.accent)) {
                Text("Keep")
            }
        },
    )
}

/**
 * The row under "Current Location": the device position has no skyline, because a skyline belongs to a place that can
 * be visited again. Tapping it explains that and offers to save the place as a site first.
 */
@Composable
internal fun SiteHorizonDeviceRow(onClick: () -> Unit) {
    val palette = LocalPalette.current
    SettingsRow(onClick = onClick, modifier = Modifier.semantics(mergeDescendants = true) { role = Role.Button }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(
                Icons.Outlined.Landscape,
                contentDescription = null,
                tint = palette.accent,
                modifier = Modifier.size(22.dp),
            )
            Column(Modifier.weight(1f)) {
                Text("Record Skyline…", color = palette.primaryText, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "${HorizonFormat.OPEN_HORIZON}. Skylines belong to saved sites.",
                    color = palette.secondaryText,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = palette.tertiaryText)
        }
    }
}

/**
 * Explains why a skyline needs a saved site and offers to save the current place as one. [blocker] says why that is
 * not possible right now, or is `null`.
 */
@Composable
internal fun SiteHorizonNeedsSiteDialog(blocker: String?, onSave: () -> Unit, onDismiss: () -> Unit) {
    val palette = LocalPalette.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.surfaceRaised,
        titleContentColor = palette.primaryText,
        textContentColor = palette.secondaryText,
        title = { Text("Save This Place First") },
        text = {
            if (LocalSystemBarsHidden.current) HideSystemBarsInWindow()
            Text(
                "A skyline belongs to a place you can come back to, so Lodestar keeps it with a saved site. " +
                    "Save this place as a site, then record its skyline." + (blocker?.let { " $it" } ?: ""),
            )
        },
        confirmButton = {
            TextButton(
                onClick = onSave,
                enabled = blocker == null,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = palette.accent,
                    disabledContentColor = palette.tertiaryText,
                ),
            ) { Text("Save This Place as a Site…") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, colors = ButtonDefaults.textButtonColors(contentColor = palette.accent)) {
                Text("Not Now")
            }
        },
    )
}
