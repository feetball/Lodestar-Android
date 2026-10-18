package net.kuecker.lodestar.features.settings

import net.kuecker.lodestar.shared.LocalSheetDismiss
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.LocationSearching
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import net.kuecker.lodestar.model.AppSettings
import net.kuecker.lodestar.model.LocationSource
import net.kuecker.lodestar.model.SavedSite
import net.kuecker.lodestar.services.LocationService
import net.kuecker.lodestar.shared.ChipTone
import net.kuecker.lodestar.shared.InfoRow
import net.kuecker.lodestar.shared.LocalAppModel
import net.kuecker.lodestar.shared.LodestarButton
import net.kuecker.lodestar.shared.LodestarButtonKind
import net.kuecker.lodestar.shared.LodestarSheet
import net.kuecker.lodestar.shared.SettingsCaption
import net.kuecker.lodestar.shared.SettingsDivider
import net.kuecker.lodestar.shared.SettingsRow
import net.kuecker.lodestar.shared.SettingsSection
import net.kuecker.lodestar.shared.StatusChip
import net.kuecker.lodestar.theme.LocalPalette
import java.time.Instant

/**
 * Settings ▸ Location: where the sky is computed for. The device's position or one of the saved observing sites,
 * with the status of location access, and adding, editing and deleting sites.
 */
@Composable
internal fun SettingsLocationSection() {
    val app = LocalAppModel.current
    val store = app.settingsStore
    val settings = app.settings
    val location = app.location
    val observer = app.observerContext
    val isFull = settings.savedSites.size >= AppSettings.MAXIMUM_SAVED_SITES
    val now = Instant.now()
    var editorTarget by remember { mutableStateOf<SiteEditorTarget?>(null) }
    // Recording a skyline for the current place: explain, save the place as a site, then record it.
    var asksToSaveForSkyline by remember { mutableStateOf(false) }
    var recordsAfterSaving by remember { mutableStateOf(false) }
    var siteToRecord by remember { mutableStateOf<String?>(null) }
    var recordingSiteID by remember { mutableStateOf<String?>(null) }

    // Once the editor has closed after saving the place, its skyline is recorded.
    LaunchedEffect(editorTarget) {
        if (editorTarget == null) {
            if (siteToRecord != null) recordingSiteID = siteToRecord
            siteToRecord = null
            recordsAfterSaving = false
        }
    }

    SettingsSection(
        title = "Location",
        footer = "Sky positions are worked out for this place. A saved site lets you plan a dark-sky trip before " +
            "you get there.",
    ) {
        if (observer != null) {
            SettingsRow { InfoRow("Observing from", observer.name) }
            SettingsDivider()
            SettingsRow { InfoRow("Time zone", SettingsFormat.describe(observer.timeZone, now)) }
            SettingsDivider()
        }
        SettingsDeviceLocationRow(
            isSelected = settings.locationSource == LocationSource.Device,
            location = location,
            onSelect = {
                store.update { it.copy(locationSource = LocationSource.Device) }
                location.requestAuthorization()
            },
        )
        if (settings.locationSource == LocationSource.Device && !location.isAuthorized) {
            SettingsDivider()
            SettingsRow { AccessPrompt(location) }
        }
        if (settings.locationSource == LocationSource.Device) {
            SettingsDivider()
            SiteHorizonDeviceRow(onClick = { asksToSaveForSkyline = true })
        }
        for (site in settings.savedSites) {
            SettingsDivider()
            SettingsSavedSiteRow(
                site = site,
                isSelected = settings.locationSource == LocationSource.Site(site.id),
                now = now,
                onSelect = { store.update { it.copy(locationSource = LocationSource.Site(site.id)) } },
                onEdit = { editorTarget = SiteEditorTarget.Existing(site) },
            )
        }
        SettingsDivider()
        SettingsActionRow(
            title = "Add Site…",
            icon = Icons.Outlined.AddCircleOutline,
            enabled = !isFull,
            onClick = { editorTarget = SiteEditorTarget.New(SiteDraft(name = "Site ${settings.savedSites.size + 1}")) },
        )
        location.fix?.let { fix ->
            SettingsDivider()
            SettingsActionRow(
                title = "Save Current Location as Site…",
                icon = Icons.Outlined.MyLocation,
                enabled = !isFull,
                onClick = { editorTarget = SiteEditorTarget.New(SiteDraft.at("Current Location", fix)) },
            )
        }
        if (isFull) {
            SettingsDivider()
            SettingsRow { SettingsCaption("You have reached the limit of ${AppSettings.MAXIMUM_SAVED_SITES} saved sites.") }
        }
    }

    editorTarget?.let { target ->
        LodestarSheet(onDismiss = { editorTarget = null }) {
            SiteEditor(
                target = target,
                onClose = LocalSheetDismiss.current,
                modifier = Modifier.weight(1f),
                onSaved = { site ->
                    // A newly added site becomes the observing location, as in onboarding.
                    if (target is SiteEditorTarget.New) {
                        store.update { it.copy(locationSource = LocationSource.Site(site.id)) }
                        if (recordsAfterSaving) siteToRecord = site.id
                    }
                },
            )
        }
    }

    if (asksToSaveForSkyline) {
        val fix = location.fix
        SiteHorizonNeedsSiteDialog(
            blocker = when {
                fix == null -> "Lodestar does not have your position yet."
                isFull -> "You have reached the limit of ${AppSettings.MAXIMUM_SAVED_SITES} saved sites. " +
                    "Delete one first."
                else -> null
            },
            onSave = {
                asksToSaveForSkyline = false
                if (fix != null) {
                    recordsAfterSaving = true
                    editorTarget = SiteEditorTarget.New(SiteDraft.at("Current Location", fix))
                }
            },
            onDismiss = { asksToSaveForSkyline = false },
        )
    }
    recordingSiteID?.let { id ->
        settings.savedSites.firstOrNull { it.id == id }?.let { site ->
            SiteHorizonDialogs(site, SiteHorizonAction.RECORD) { recordingSiteID = null }
        }
    }
}

/** What to do when the device location is the source but it cannot be used yet. */
@Composable
private fun AccessPrompt(location: LocationService) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (location.isDenied) {
            SettingsCaption(
                "Location access is turned off for Lodestar. Allow it again in the phone's permission settings, " +
                    "or choose a saved site.",
            )
            LodestarButton("Allow Location Access", onClick = location::requestAgain, kind = LodestarButtonKind.SECONDARY)
        } else {
            SettingsCaption(
                "Lodestar uses your location only while it is open, only to work out the sky. An approximate " +
                    "location is enough.",
            )
            LodestarButton("Allow Location Access", onClick = location::requestAuthorization, kind = LodestarButtonKind.SECONDARY)
        }
    }
}

/** A round selection mark: filled with a check when chosen. */
@Composable
internal fun SettingsSelectionMark(isSelected: Boolean) {
    val palette = LocalPalette.current
    Icon(
        if (isSelected) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
        contentDescription = null,
        tint = if (isSelected) palette.accent else palette.tertiaryText,
        modifier = Modifier.size(24.dp),
    )
}

/** The "Current Location" choice with the state of location access and accuracy. */
@Composable
private fun SettingsDeviceLocationRow(isSelected: Boolean, location: LocationService, onSelect: () -> Unit) {
    val palette = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = isSelected, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingsSelectionMark(isSelected)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Current Location", color = palette.primaryText, style = MaterialTheme.typography.bodyLarge)
            Text(statusText(location), color = palette.secondaryText, style = MaterialTheme.typography.bodySmall)
        }
        if (location.isAuthorized && location.isReducedAccuracy) {
            StatusChip(Icons.Outlined.LocationSearching, "Approximate", tone = ChipTone.WARNING)
        }
    }
}

private fun statusText(location: LocationService): String {
    if (location.isDenied) return "Location access is off"
    if (!location.isAuthorized) return "Tap to allow location access"
    val position = location.stableLocation
        ?: return location.lastErrorDescription ?: "Waiting for a location fix…"
    val fix = location.fix ?: return SettingsFormat.coordinates(position)
    return SettingsFormat.coordinates(position) + " · " + SettingsFormat.accuracy(fix.horizontalAccuracy)
}

/** A saved site: tap the name to observe from it, tap the pencil to edit it. */
@Composable
private fun SettingsSavedSiteRow(site: SavedSite, isSelected: Boolean, now: Instant, onSelect: () -> Unit, onEdit: () -> Unit) {
    val palette = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .weight(1f)
                .selectable(selected = isSelected, role = Role.RadioButton, onClick = onSelect)
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsSelectionMark(isSelected)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(site.name, color = palette.primaryText, style = MaterialTheme.typography.bodyLarge)
                Text(subtitle(site, now), color = palette.secondaryText, style = MaterialTheme.typography.bodySmall)
                if (site.horizon != null) {
                    Text(
                        "Horizon: ${rememberSiteHorizonStatus(site)}",
                        color = palette.secondaryText,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        SiteHorizonMenuButton(site)
        IconButton(onClick = onEdit) {
            Icon(Icons.Outlined.Edit, contentDescription = "Edit ${site.name}", tint = palette.accent)
        }
    }
}

private fun subtitle(site: SavedSite, now: Instant): String {
    val parts = mutableListOf(SettingsFormat.coordinates(site.location), SettingsFormat.elevation(site.elevation))
    site.timeZone?.let { parts.add(SettingsFormat.utcOffset(it, now)) }
    return parts.joinToString(" · ")
}
