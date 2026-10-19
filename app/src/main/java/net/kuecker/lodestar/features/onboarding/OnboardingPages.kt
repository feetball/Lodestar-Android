package net.kuecker.lodestar.features.onboarding

import net.kuecker.lodestar.shared.LocalSheetDismiss
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.GpsFixed
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LocationOff
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.NightsStay
import androidx.compose.material.icons.outlined.NorthEast
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.kuecker.lodestar.features.settings.ColorFiltersGuideCard
import net.kuecker.lodestar.features.settings.MountPresetRowLabel
import net.kuecker.lodestar.features.settings.SettingsSegmentedPicker
import net.kuecker.lodestar.features.settings.SiteDraft
import net.kuecker.lodestar.features.settings.SiteEditor
import net.kuecker.lodestar.features.settings.SiteEditorTarget
import net.kuecker.lodestar.kit.pointing.MountProfile
import net.kuecker.lodestar.model.LocationSource
import net.kuecker.lodestar.model.NightModeSetting
import net.kuecker.lodestar.shared.ChipTone
import net.kuecker.lodestar.shared.LocalAppModel
import net.kuecker.lodestar.shared.LodestarButton
import net.kuecker.lodestar.shared.LodestarButtonKind
import net.kuecker.lodestar.shared.LodestarSheet
import net.kuecker.lodestar.shared.SettingsCaption
import net.kuecker.lodestar.shared.SettingsMenuOption
import net.kuecker.lodestar.shared.StatusChip
import net.kuecker.lodestar.theme.LocalPalette
import net.kuecker.lodestar.theme.card

/** The common layout of an onboarding step: a symbol, a title and an introduction above the step's own content. */
@Composable
internal fun OnboardingPage(icon: ImageVector, title: String, introduction: String, content: @Composable ColumnScope.() -> Unit) {
    val palette = LocalPalette.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Icon(icon, contentDescription = null, tint = palette.accent, modifier = Modifier.size(44.dp))
        Text(
            title,
            color = palette.primaryText,
            style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.semantics { heading() },
        )
        Text(introduction, color = palette.secondaryText, style = MaterialTheme.typography.bodyLarge)
        content()
    }
}

/** A symbol with a heading and a sentence, used for lists of facts and tips. */
@Composable
private fun OnboardingFactRow(icon: ImageVector, title: String, text: String) {
    val palette = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.width(30.dp), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = palette.accent, modifier = Modifier.size(24.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                color = palette.primaryText,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            )
            Text(text, color = palette.secondaryText, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

// Welcome

/** Step 1: what Lodestar does. */
@Composable
internal fun OnboardingWelcomePage() {
    OnboardingPage(
        icon = Icons.Outlined.GpsFixed,
        title = "Aim at the night sky",
        introduction = "Lodestar turns your phone, clamped to your camera, into a smart finder. Pick a target and " +
            "arrows tell you how to pan and tilt the tripod head until it is in the frame.",
    ) {
        OnboardingFactRow(
            Icons.Outlined.PhoneAndroid, "Clamp your phone to the camera",
            "Its sensors tell Lodestar where the camera points.",
        )
        OnboardingFactRow(
            Icons.Outlined.AutoAwesome, "Choose a target",
            "The Moon, planets, bright stars, nebulae, galaxies and the Milky Way core.",
        )
        OnboardingFactRow(
            Icons.Outlined.NorthEast, "Follow the arrows",
            "Turn the head until the target sits in the middle, then shoot.",
        )
        OnboardingFactRow(
            Icons.Outlined.Lock, "Private by design",
            "Everything is worked out on your phone. There are no accounts and no tracking.",
        )
    }
}

// Mounting

/** Step 2: how the phone is mounted. */
@Composable
internal fun OnboardingMountPage() {
    val app = LocalAppModel.current
    OnboardingPage(
        icon = Icons.Outlined.PhoneAndroid,
        title = "Mount your phone",
        introduction = "Tell Lodestar how the phone sits on the camera. You can change this later in Settings.",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (profile in MountProfile.presets) {
                MountPresetButton(
                    profile = profile,
                    isSelected = profile.id == app.mountProfile.id,
                    onClick = { app.settingsStore.update { it.copy(mountPresetID = profile.id) } },
                )
            }
        }
        OnboardingFactRow(
            Icons.Outlined.Build, "Use a rigid bracket",
            "Any wobble in the clamp shows up as aiming error.",
        )
        OnboardingFactRow(
            Icons.Outlined.CameraAlt, "Point the phone's camera along the lens",
            "Keep the two roughly parallel. Lodestar corrects small tilts when you align on a star.",
        )
    }
}

/** A mount preset as a card; the chosen one has an accent outline. */
@Composable
private fun MountPresetButton(profile: MountProfile, isSelected: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val shape = RoundedCornerShape(16.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(palette.surface, shape)
            .border(if (isSelected) 2.dp else 0.5.dp, if (isSelected) palette.accent else palette.stroke, shape)
            .selectable(selected = isSelected, role = Role.RadioButton, onClick = onClick)
            .padding(12.dp),
    ) {
        MountPresetRowLabel(profile, isSelected = isSelected)
    }
}

// Location

/** Step 3: why Lodestar wants the location, and the way around it. */
@Composable
internal fun OnboardingPermissionsPage() {
    val app = LocalAppModel.current
    var editorTarget by remember { mutableStateOf<SiteEditorTarget?>(null) }
    OnboardingPage(
        icon = Icons.Outlined.LocationOn,
        title = "Where are you?",
        introduction = "Lodestar needs your position to work out where things are in your sky. It asks only while " +
            "the app is open, an approximate position is enough, and it is never tracked. It stays on your phone; " +
            "only the optional cloud forecast sends it, rounded to about 10 km.",
    ) {
        LocationStatus()
        LocationActions(onChooseSite = { editorTarget = SiteEditorTarget.New(SiteDraft(name = "My Site")) })
        OnboardingFactRow(
            Icons.Outlined.CameraAlt, "The camera is optional",
            "Lodestar asks for camera access the first time you show the camera picture behind the sky on the Aim " +
                "screen.",
        )
    }
    editorTarget?.let { target ->
        LodestarSheet(onDismiss = { editorTarget = null }) {
            SiteEditor(
                target = target,
                onClose = LocalSheetDismiss.current,
                modifier = Modifier.weight(1f),
                onSaved = { site -> app.settingsStore.update { it.copy(locationSource = LocationSource.Site(site.id)) } },
            )
        }
    }
}

/** Where things stand: the observing site, the device location, or what is missing. */
@Composable
private fun LocationStatus() {
    val app = LocalAppModel.current
    when (app.settings.locationSource) {
        is LocationSource.Site -> StatusChip(
            Icons.Outlined.Place, "Observing from ${app.observerContext?.name ?: "your site"}", tone = ChipTone.POSITIVE,
        )
        LocationSource.Device -> when {
            app.location.isAuthorized ->
                StatusChip(Icons.Outlined.MyLocation, "Using your current location", tone = ChipTone.POSITIVE)
            app.location.isDenied ->
                StatusChip(Icons.Outlined.LocationOff, "Location access is off", tone = ChipTone.WARNING)
            else -> StatusChip(Icons.Outlined.LocationOn, "Location access not asked yet", tone = ChipTone.NEUTRAL)
        }
    }
}

@Composable
private fun LocationActions(onChooseSite: () -> Unit) {
    val app = LocalAppModel.current
    val location = app.location
    when (app.settings.locationSource) {
        LocationSource.Device -> {
            // After a refusal Android shows the dialog once more, then sends the user to the app's settings.
            if (location.isDenied) {
                OnboardingActionButton("Allow Location Access", isPrimary = true, onClick = location::requestAgain)
            } else if (!location.isAuthorized) {
                OnboardingActionButton("Allow Location Access", isPrimary = true, onClick = location::requestAuthorization)
            }
            OnboardingActionButton("Choose a Site Instead", isPrimary = false, onClick = onChooseSite)
            SettingsCaption("No GPS, or planning from home? Pick any place on the map or type its coordinates.")
        }
        is LocationSource.Site -> OnboardingActionButton("Use My Current Location Instead", isPrimary = false) {
            app.settingsStore.update { it.copy(locationSource = LocationSource.Device) }
            location.requestAuthorization()
        }
    }
}

/** A full-width button in the onboarding flow. */
@Composable
private fun OnboardingActionButton(title: String, isPrimary: Boolean, onClick: () -> Unit) {
    LodestarButton(
        title,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        kind = if (isPrimary) LodestarButtonKind.PRIMARY else LodestarButtonKind.SECONDARY,
    )
}

// Night vision

/** Step 4: night mode and the tip for dimming the whole phone. */
@Composable
internal fun OnboardingNightPage() {
    val app = LocalAppModel.current
    val palette = LocalPalette.current
    val modeOptions = NightModeSetting.entries.map { SettingsMenuOption(it.title, it) }
    OnboardingPage(
        icon = Icons.Outlined.NightsStay,
        title = "Protect your night vision",
        introduction = "A bright screen ruins dark adaptation. Night mode draws everything in pure red on black and " +
            "dims the screen. Try it now.",
    ) {
        Column(Modifier.fillMaxWidth().card(palette), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingsSegmentedPicker(
                title = "Night mode",
                selection = app.settings.nightMode,
                options = modeOptions,
                onSelect = { mode -> app.settingsStore.update { it.copy(nightMode = mode) } },
                titleStyle = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            )
            SettingsCaption(
                "After Sunset switches on by itself when the Sun is more than 6° below the horizon. You can " +
                    "fine-tune it in Settings.",
            )
        }
        Box(Modifier.fillMaxWidth().card(palette)) { ColorFiltersGuideCard() }
    }
}
