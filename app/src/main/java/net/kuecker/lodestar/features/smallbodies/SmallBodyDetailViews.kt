package net.kuecker.lodestar.features.smallbodies

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.kuecker.lodestar.features.objects.ObjectDetailDistance
import net.kuecker.lodestar.kit.formatting.AngleFormatter
import net.kuecker.lodestar.shared.CardTitle
import net.kuecker.lodestar.shared.ChipTone
import net.kuecker.lodestar.shared.InfoRow
import net.kuecker.lodestar.shared.StatusChip
import net.kuecker.lodestar.theme.LocalPalette
import net.kuecker.lodestar.theme.card

/**
 * The rows of a comet or an asteroid in the detail screen's "Now" card: the badges ("Photographable", the warnings),
 * the brightness estimate and the distances, elongation, phase and age of the orbit. [detail] is `null` when the orbit
 * cannot be evaluated now.
 */
@Composable
internal fun SmallBodyNowRows(detail: SmallBodyDetail?) {
    if (detail == null) {
        StatusChip(Icons.Outlined.Warning, SmallBodyText.capitalized(SmallBodyText.NO_CURRENT_ORBIT), tone = ChipTone.WARNING)
        return
    }
    val info = detail.info
    val warnings = info.warnings
    if (info.isPhotographable || warnings.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (info.isPhotographable) {
                StatusChip(Icons.Outlined.CameraAlt, SmallBodyText.PHOTOGRAPHABLE, tone = ChipTone.POSITIVE)
            }
            for (warning in warnings) {
                StatusChip(Icons.Outlined.Warning, SmallBodyText.sentence(warning), tone = ChipTone.WARNING)
            }
        }
    }
    InfoRow("Brightness", SmallBodyText.magnitude(info.magnitude, sentence = true))
    InfoRow("Distance from Earth", ObjectDetailDistance.AstronomicalUnits(info.earthDistanceAU).text)
    InfoRow("Distance from Sun", ObjectDetailDistance.AstronomicalUnits(info.sunDistanceAU).text)
    InfoRow("Elongation", SmallBodyText.elongation(info.elongation))
    InfoRow("Phase angle", AngleFormatter.formatDegrees(info.phaseAngle, 0))
    InfoRow("Orbit elements", SmallBodyText.elementsDate(info.body))
}

/**
 * Which way a comet's ion tail points and how far to tilt the camera to lay it along the frame. Nothing is drawn for an
 * asteroid, a comet without a tail direction, or while [detail] is being computed.
 */
@Composable
internal fun SmallBodyTailCard(detail: SmallBodyDetail?, modifier: Modifier = Modifier) {
    val tail = detail?.tail ?: return
    val palette = LocalPalette.current
    Column(modifier.fillMaxWidth().card(palette), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CardTitle("Comet Tail", icon = Icons.Outlined.AutoFixHigh)
        Text(SmallBodyText.TAIL_TITLE, color = palette.secondaryText, style = MaterialTheme.typography.bodyMedium)
        InfoRow("Points", SmallBodyText.tailDirection(tail.angleFromVertical))
        InfoRow("Camera tilt", SmallBodyText.cameraTilt(tail.cameraTilt))
        Text(SmallBodyText.TAIL_NOTE, color = palette.tertiaryText, style = MaterialTheme.typography.labelMedium)
        if (tail.isForeshortened) {
            Text(SmallBodyText.TAIL_FORESHORTENED, color = palette.warning, style = MaterialTheme.typography.bodySmall)
        }
    }
}
