package net.kuecker.lodestar.features.aim

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.ArrowCircleUp
import androidx.compose.material.icons.outlined.GpsFixed
import androidx.compose.material.icons.outlined.OpenWith
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.kuecker.lodestar.kit.formatting.AngleFormatter
import net.kuecker.lodestar.kit.pointing.AlignmentModel
import net.kuecker.lodestar.shared.AppFormat
import net.kuecker.lodestar.shared.CardTitle
import net.kuecker.lodestar.shared.InfoRow
import net.kuecker.lodestar.shared.LodestarButton
import net.kuecker.lodestar.shared.LodestarButtonKind
import net.kuecker.lodestar.shared.ObjectIcon
import net.kuecker.lodestar.shared.monospacedDigits
import net.kuecker.lodestar.theme.LocalPalette

/** A reference object in the alignment list: name, kind, brightness, position and distance from the camera. */
@Composable
internal fun AimCandidateRow(row: AimAlignmentRow, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val obj = row.candidate.obj
    val position = row.candidate.horizontal
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(palette.surface, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {}
            .heightIn(min = 44.dp)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ObjectIcon(obj.category)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                obj.name,
                color = palette.primaryText,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
            )
            Text(
                "${obj.category.displayName} · ${AppFormat.magnitude(row.candidate.magnitude)}",
                color = palette.secondaryText,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val style = MaterialTheme.typography.labelMedium.monospacedDigits()
            Text(
                "${AppFormat.altitude(position.altitude)} · ${AppFormat.azimuth(position.azimuth)}",
                color = palette.secondaryText,
                style = style,
            )
            row.separation?.let { Text("${AppFormat.separation(it)} away", color = palette.secondaryText, style = style) }
        }
    }
}

/** The chosen reference object with the distance still to go, refreshed four times a second. */
@Composable
internal fun AimReferenceCard(model: AimViewModel, candidate: AimAlignmentCandidate) {
    val palette = LocalPalette.current
    Column(
        Modifier.fillMaxWidth().aimPanel(palette, padding = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CardTitle("Center this object", icon = Icons.Outlined.GpsFixed)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ObjectIcon(candidate.obj.category)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    candidate.obj.name,
                    color = palette.primaryText,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                )
                Text(
                    "${AppFormat.altitude(candidate.horizontal.altitude)} · ${AppFormat.azimuth(candidate.horizontal.azimuth)}",
                    color = palette.secondaryText,
                    style = MaterialTheme.typography.labelMedium.monospacedDigits(),
                )
            }
        }
        val guidance = rememberAimFrame(model, 250, isActive = true)?.guidance
        if (guidance != null) {
            val summary = AimGuidanceSummary(guidance)
            when {
                summary.isOnTarget ->
                    ProgressLabel("Close — center it exactly, then Sync", Icons.Filled.CheckCircle, palette.positive)
                summary.isNearZenith -> ProgressLabel(
                    "${AimFormat.angle(summary.separation)} away. Near zenith — follow the arrow.",
                    Icons.Outlined.ArrowCircleUp, palette.guidance,
                )
                else -> ProgressLabel(
                    "${summary.pan.verb} ${AimFormat.angle(summary.pan.degrees)}, " +
                        "${summary.tilt.verb.lowercase()} ${AimFormat.angle(summary.tilt.degrees)}",
                    Icons.Outlined.OpenWith, palette.guidance,
                )
            }
        } else {
            ProgressLabel("Waiting for the sensors…", Icons.Outlined.Sensors, palette.secondaryText)
        }
    }
}

/** A line of progress with its icon. */
@Composable
private fun ProgressLabel(text: String, icon: ImageVector, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        Text(text, color = color, style = MaterialTheme.typography.bodyLarge)
    }
}

/** What the alignment has found so far, its points and a way to start over. */
@Composable
internal fun AimAlignmentSummaryCard(alignment: AlignmentModel, onRemove: (Int) -> Unit, onReset: () -> Unit) {
    val palette = LocalPalette.current
    Column(
        Modifier.fillMaxWidth().aimPanel(palette, padding = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CardTitle("Current alignment", icon = Icons.Outlined.Verified)
        if (alignment.isAligned) {
            InfoRow("Heading correction", AngleFormatter.formatSignedDegrees(alignment.headingOffset, 1))
            InfoRow("Bracket correction", AngleFormatter.formatDegrees(alignment.deviceCorrection.length, 1))
            InfoRow("Fit error (RMS)", AngleFormatter.formatDegrees(alignment.rmsResidual, 2))
            alignment.pointingUncertainty?.let { uncertainty ->
                InfoRow("Expected accuracy", "±${AngleFormatter.formatDegrees(uncertainty, if (uncertainty < 10) 1 else 0)}")
            }
            alignment.points.forEachIndexed { index, point ->
                PointRow(
                    name = point.name ?: "Point ${index + 1}",
                    residual = alignment.residuals.getOrNull(index),
                    onRemove = { onRemove(index) },
                )
            }
            LodestarButton(
                "Reset alignment", onClick = onReset, kind = LodestarButtonKind.SECONDARY, icon = Icons.Outlined.Replay,
            )
        } else {
            Text(
                "Not aligned yet. Without alignment the arrows trust the compass and assume the phone sits exactly " +
                    "as the mount in Settings says.",
                color = palette.secondaryText,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun PointRow(name: String, residual: Double?, onRemove: () -> Unit) {
    val palette = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                name,
                color = palette.primaryText,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            )
            if (residual != null) {
                Text(
                    "Off by ${AngleFormatter.formatDegrees(residual, 2)} after the fit",
                    color = palette.secondaryText,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(22.dp))
                .clickable(role = Role.Button, onClick = onRemove)
                .semantics { contentDescription = "Remove $name" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.RemoveCircleOutline, contentDescription = null, tint = palette.accent, modifier = Modifier.size(24.dp))
        }
    }
}
