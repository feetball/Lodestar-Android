package net.kuecker.lodestar.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.GpsFixed
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import net.kuecker.lodestar.kit.formatting.AngleFormatter
import net.kuecker.lodestar.model.HeadingSource
import net.kuecker.lodestar.shared.ChipTone
import net.kuecker.lodestar.shared.InfoRow
import net.kuecker.lodestar.shared.LocalAppModel
import net.kuecker.lodestar.shared.SettingsCaption
import net.kuecker.lodestar.shared.SettingsDivider
import net.kuecker.lodestar.shared.SettingsMenuOption
import net.kuecker.lodestar.shared.SettingsRow
import net.kuecker.lodestar.shared.SettingsSection
import net.kuecker.lodestar.shared.SettingsSliderRow
import net.kuecker.lodestar.shared.StatusChip
import net.kuecker.lodestar.theme.LocalPalette

/** Time constants below this many seconds switch smoothing off. */
private const val SMOOTHING_OFF_BELOW = 0.01

/**
 * Settings ▸ Mount: how the phone is clamped to the camera, where the heading comes from, how much the arrows are
 * smoothed, and the state of the star alignment.
 */
@Composable
internal fun SettingsMountSection(onOpen: (SettingsRoute) -> Unit) {
    val app = LocalAppModel.current
    val palette = LocalPalette.current
    val settings = app.settings
    val headingOptions = HeadingSource.entries.map { SettingsMenuOption(it.title, it) }
    SettingsSection(title = "Mount", footer = "Changing the mount or the heading source clears the star alignment.") {
        SettingsRow(
            onClick = { onOpen(SettingsRoute.MountPicker) },
            modifier = Modifier.semantics(mergeDescendants = true) { role = Role.Button },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MountPresetRowLabel(app.mountProfile, Modifier.weight(1f), showsSelection = false)
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = palette.tertiaryText)
            }
        }
        SettingsDivider()
        SettingsRow {
            SettingsSegmentedPicker(
                title = "Heading source",
                selection = settings.headingSource,
                options = headingOptions,
                onSelect = { source -> app.settingsStore.update { it.copy(headingSource = source) } },
            )
            SettingsCaption(explanation(settings.headingSource))
        }
        SettingsDivider()
        SettingsSliderRow(
            title = "Smoothing",
            valueText = smoothingText(settings.smoothingTimeConstant),
            value = settings.smoothingTimeConstant,
            onValueChange = { value -> app.settingsStore.update { it.copy(smoothingTimeConstant = value) } },
            range = 0.0..0.5,
            step = 0.01,
        )
        SettingsDivider()
        SettingsRow {
            SettingsCaption(
                "Less smoothing follows the camera faster; more smoothing steadies the arrows against shaky " +
                    "readings. 0.12 s suits most mounts.",
            )
        }
        SettingsDivider()
        SettingsAlignmentStatus()
    }
}

private fun explanation(source: HeadingSource): String = when (source) {
    HeadingSource.COMPASS ->
        "Reads the phone's compass and turns it into true north with the World Magnetic Model. It works at once, " +
            "but metal and electronics near the phone can pull it off by several degrees."
    HeadingSource.GYRO_LOCK ->
        "Copies the compass heading to the gyroscope once the phone holds still, then ignores the compass, so " +
            "tripod heads and camera bodies can no longer disturb it. Hold the phone steady for a moment after " +
            "opening the Aim screen. The heading drifts slowly; align on a star again if it does."
}

private fun smoothingText(seconds: Double): String =
    if (seconds < SMOOTHING_OFF_BELOW) "Off" else "${SettingsFormat.decimal(seconds, 2)} s"

/** The star alignment at a glance, with a button to throw it away. */
@Composable
private fun SettingsAlignmentStatus() {
    val app = LocalAppModel.current
    val alignment = app.alignment
    if (alignment.isAligned) {
        SettingsRow {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row { StatusChip(Icons.Outlined.Verified, "Aligned", tone = ChipTone.POSITIVE) }
                InfoRow("Reference points", "${alignment.points.size}")
                InfoRow("Fit residual (RMS)", AngleFormatter.formatDegrees(alignment.rmsResidual, 2))
                alignment.pointingUncertainty?.let {
                    InfoRow("Expected pointing error", "±" + AngleFormatter.formatDegrees(it, 1))
                }
                InfoRow("Compass correction", AngleFormatter.formatSignedDegrees(alignment.headingOffset, 1))
                InfoRow("Bracket tilt correction", AngleFormatter.formatDegrees(alignment.deviceCorrection.length, 1))
            }
        }
        SettingsDivider()
        SettingsActionRow("Reset Alignment", onClick = app::resetAlignment)
    } else {
        SettingsRow {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row { StatusChip(Icons.Outlined.GpsFixed, "Not aligned") }
                SettingsCaption(
                    "On the Aim screen, tap Align and center a bright star, a planet or the Moon in the camera. One " +
                        "point corrects the heading; several points at different heights in the sky also correct " +
                        "the tilt of the bracket.",
                )
            }
        }
    }
}
