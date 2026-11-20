package net.kuecker.lodestar.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import net.kuecker.lodestar.features.horizon.TerrainSummary
import net.kuecker.lodestar.features.horizon.TerrainText
import net.kuecker.lodestar.kit.horizon.TerrainHorizon
import net.kuecker.lodestar.model.SavedSite
import net.kuecker.lodestar.services.OnlineFeed
import net.kuecker.lodestar.services.TerrainComputation
import net.kuecker.lodestar.services.TerrainHorizonLoader
import net.kuecker.lodestar.shared.LocalAppModel
import net.kuecker.lodestar.shared.SettingsCaption
import net.kuecker.lodestar.shared.SettingsDivider
import net.kuecker.lodestar.shared.SettingsRow
import net.kuecker.lodestar.shared.SettingsSection
import net.kuecker.lodestar.theme.LocalPalette
import kotlin.math.roundToInt

/** What the terrain horizon section is doing. */
private sealed interface TerrainPhase {
    data object Idle : TerrainPhase

    /** Working; [text] is the progress line. */
    class Working(val text: String) : TerrainPhase

    class Done(val summary: TerrainSummary) : TerrainPhase

    class Failed(val message: String) : TerrainPhase
}

/**
 * The Terrain Horizon section of the site editor: the state of the site's terrain horizon, "Compute from Elevation Data…"
 * (which asks first, then downloads the elevation tiles, works out the skyline off the main thread, stores it with the
 * site and offers the ground elevation) and "Remove Terrain Horizon…". Needs the Terrain horizons switch in Settings ▸
 * Online Data. [site] is the site as it was stored when the editor opened; the section follows what is stored now,
 * because computing saves at once, like recording. [movedAway] says that the position being edited is not where the site
 * was stored; the terrain horizon is computed for the stored place only. [draftElevation] is the elevation in the editor;
 * [onUseElevation] puts a new one into the draft.
 */
@Composable
internal fun SiteTerrainSection(site: SavedSite?, movedAway: Boolean, draftElevation: Double, onUseElevation: (Double) -> Unit) {
    val app = LocalAppModel.current
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf<TerrainPhase>(TerrainPhase.Idle) }
    var job by remember { mutableStateOf<Job?>(null) }
    var asksToDownload by remember { mutableStateOf(false) }
    var asksToRemove by remember { mutableStateOf(false) }
    val stored = site?.let { original -> app.settings.savedSites.firstOrNull { it.id == original.id } }
    val isOn = app.online.isEnabled(OnlineFeed.TERRAIN)
    // Leaving the editor stops a download that is still running.
    DisposableEffect(Unit) { onDispose { job?.cancel() } }

    SettingsSection(title = TerrainText.SECTION_TITLE, footer = TerrainText.FOOTER) {
        if (stored == null) {
            SettingsRow { SettingsCaption(TerrainText.SAVE_FIRST) }
            return@SettingsSection
        }
        val terrain = stored.terrainHorizon
        val isWorking = phase is TerrainPhase.Working
        SettingsRow {
            Text(
                terrain?.let(TerrainText::status) ?: TerrainText.NOT_COMPUTED,
                color = palette.primaryText,
                style = MaterialTheme.typography.bodyLarge,
            )
            if (movedAway) SettingsCaption(if (terrain != null) TerrainText.MOVED_STORED else TerrainText.MOVED)
            if (!isOn) SettingsCaption(TerrainText.SWITCHED_OFF)
        }
        when (val current = phase) {
            TerrainPhase.Idle -> Unit
            is TerrainPhase.Working -> {
                SettingsDivider()
                SettingsRow {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(color = palette.accent, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                        Text(current.text, color = palette.secondaryText, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            is TerrainPhase.Failed -> {
                SettingsDivider()
                SettingsRow { Text(current.message, color = palette.warning, style = MaterialTheme.typography.bodySmall) }
            }
            is TerrainPhase.Done -> {
                SettingsDivider()
                SettingsRow { for (line in current.summary.lines) SettingsCaption(line) }
                val ground = current.summary.groundElevationMeters
                if (ground != null && ground != draftElevation.roundToInt()) {
                    SettingsDivider()
                    SettingsActionRow(title = TerrainText.useElevation(ground), onClick = { onUseElevation(ground.toDouble()) })
                }
            }
        }
        SettingsDivider()
        if (isWorking) {
            SettingsActionRow(title = TerrainText.CANCEL, onClick = { job?.cancel() })
        } else {
            SettingsActionRow(
                title = TerrainText.COMPUTE,
                icon = Icons.Outlined.Cloud,
                enabled = isOn && !movedAway,
                onClick = { asksToDownload = true },
            )
        }
        if (terrain != null && !isWorking) {
            SettingsDivider()
            SettingsActionRow(title = TerrainText.REMOVE, isDestructive = true, onClick = { asksToRemove = true })
        }
    }

    if (asksToDownload && stored != null) {
        val tileCount = minOf(TerrainHorizon.requiredTiles(stored.location).size, TerrainHorizonLoader.MAXIMUM_TILES)
        TerrainDialog(
            title = TerrainText.CONFIRM_TITLE,
            message = TerrainText.confirmMessage(tileCount),
            confirm = TerrainText.CONFIRM_BUTTON,
            dismiss = TerrainText.CANCEL,
            isDestructive = false,
            onConfirm = {
                asksToDownload = false
                phase = TerrainPhase.Working(
                    TerrainHorizon.requiredTiles(stored.location).firstOrNull()
                        ?.let { TerrainText.progress(TerrainHorizonLoader.Progress.Downloading(1, tileCount, it)) }
                        ?: TerrainText.COMPUTING,
                )
                job = scope.launch {
                    val outcome = app.online.computeTerrainHorizon(stored.location) { progress ->
                        phase = TerrainPhase.Working(TerrainText.progress(progress))
                    }
                    job = null
                    phase = when (outcome) {
                        is TerrainComputation.Done -> finish(app, stored, outcome)
                        is TerrainComputation.Failed -> TerrainPhase.Failed(outcome.message)
                        TerrainComputation.Cancelled -> TerrainPhase.Failed(TerrainText.CANCELLED)
                    }
                }
            },
            onDismiss = { asksToDownload = false },
        )
    }
    if (asksToRemove && stored != null) {
        TerrainDialog(
            title = TerrainText.REMOVE_TITLE,
            message = TerrainText.REMOVE_MESSAGE,
            confirm = TerrainText.REMOVE_BUTTON,
            dismiss = TerrainText.KEEP_BUTTON,
            isDestructive = true,
            onConfirm = {
                asksToRemove = false
                // The stored site may have changed since the dialog was asked for.
                val current = app.settings.savedSites.firstOrNull { it.id == stored.id } ?: stored
                current.edited(terrainHorizon = null)?.let(app.settingsStore::updateSite)
                phase = TerrainPhase.Idle
            },
            onDismiss = { asksToRemove = false },
        )
    }
}

/** Stores the terrain horizon of [outcome] with the site and says what happened. */
private fun finish(app: net.kuecker.lodestar.app.AppModel, stored: SavedSite, outcome: TerrainComputation.Done): TerrainPhase {
    // The stored site may have changed while the tiles came in (the editor only keeps a draft), so build on that one.
    val current = app.settings.savedSites.firstOrNull { it.id == stored.id } ?: stored
    val updated = current.edited(terrainHorizon = outcome.result.profile) ?: return TerrainPhase.Failed(TerrainText.UNREADABLE)
    app.settingsStore.updateSite(updated)
    return TerrainPhase.Done(TerrainText.summary(outcome))
}

/** A question with a confirming and a dismissing button, in the palette. */
@Composable
private fun TerrainDialog(
    title: String,
    message: String,
    confirm: String,
    dismiss: String,
    isDestructive: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalPalette.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.surfaceRaised,
        titleContentColor = palette.primaryText,
        textContentColor = palette.secondaryText,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = if (isDestructive) palette.critical else palette.accent),
            ) { Text(confirm) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, colors = ButtonDefaults.textButtonColors(contentColor = palette.accent)) {
                Text(dismiss)
            }
        },
    )
}
