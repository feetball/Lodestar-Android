package net.kuecker.lodestar.features.aim

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Rotate90DegreesCcw
import androidx.compose.material.icons.outlined.Rotate90DegreesCw
import androidx.compose.material.icons.outlined.Terrain
import androidx.compose.material.icons.outlined.VerticalAlignBottom
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.kuecker.lodestar.features.mosaic.MosaicSession
import net.kuecker.lodestar.kit.foundation.HorizontalCoordinate
import net.kuecker.lodestar.kit.mosaic.MosaicPanelPointing
import net.kuecker.lodestar.shared.AppFormat
import net.kuecker.lodestar.shared.monospacedDigits
import net.kuecker.lodestar.theme.LocalPalette

/**
 * The panel above the controls while a mosaic runs: which panel is being shot, how to reach and turn to it, and the
 * buttons to go back, to confirm the shot and to finish. It updates about ten times a second.
 */
@Composable
internal fun AimMosaicReadout(model: AimViewModel, isActive: Boolean, modifier: Modifier = Modifier) {
    val session = model.mosaic
    val frame = rememberAimFrame(model, 100, isActive)
    val panel = session.currentPanel
    val pointings = model.mosaicPointings?.takeIf { it.plan === session.plan }
    val pointing = panel?.let { pointings?.pointings?.getOrNull(it.index) }
    val aim = frame?.panel
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AimMosaicCard(
            session = session,
            panelID = panel?.id,
            horizontal = aim?.horizontal,
            pointing = pointing,
            isBehindSkyline = pointing != null && model.skyContent?.isBehindSkyline(pointing.direction.unitVector) == true,
            rollDifference = frame?.rollDifference,
        )
        if (!session.isComplete) frame?.guidance?.let { AimMoveRow(AimGuidanceSummary(it)) }
        AimMosaicButtons(session)
    }
}

/** The current panel's name and place, with the warnings and the roll instruction. */
@Composable
private fun AimMosaicCard(
    session: MosaicSession,
    panelID: String?,
    horizontal: HorizontalCoordinate?,
    pointing: MosaicPanelPointing?,
    isBehindSkyline: Boolean,
    rollDifference: Double?,
) {
    val palette = LocalPalette.current
    val subtitle = listOfNotNull(panelID, session.title.takeIf { it.isNotEmpty() }).joinToString(" · ")
    Column(
        Modifier.fillMaxWidth().aimPanel(palette).semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Outlined.GridView, contentDescription = null, tint = palette.accent, modifier = Modifier.size(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                AimFittingText(
                    session.progressText,
                    MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    palette.primaryText,
                    minimumScale = 0.8f,
                )
                Text(
                    subtitle,
                    color = palette.secondaryText,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (horizontal != null && !session.isComplete) {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    val style = MaterialTheme.typography.labelMedium.monospacedDigits()
                    Text("Alt ${AppFormat.altitude(horizontal.altitude)}", color = palette.secondaryText, style = style)
                    Text("Az ${AppFormat.azimuth(horizontal.azimuth)}", color = palette.secondaryText, style = style)
                }
            }
        }
        if (session.isComplete) return@Column
        if (pointing != null && !pointing.isAboveHorizon) {
            CardNote("This panel is below the horizon. Skip it or wait for it to rise.", Icons.Outlined.VerticalAlignBottom, palette.warning)
        } else if (pointing != null && pointing.lowestAltitude < 0) {
            CardNote("Part of this panel is below the horizon.", Icons.Outlined.VerticalAlignBottom, palette.warning)
        }
        if (isBehindSkyline) CardNote("This panel is behind the recorded skyline.", Icons.Outlined.Terrain, palette.warning)
        if (rollDifference != null) {
            val text = AimMosaicPointings.rollText(rollDifference)
            when {
                text == null -> CardNote("Camera roll matches the panel", Icons.Filled.Check, palette.positive)
                rollDifference > 0 -> CardNote(text, Icons.Outlined.Rotate90DegreesCw, palette.guidance)
                else -> CardNote(text, Icons.Outlined.Rotate90DegreesCcw, palette.guidance)
            }
        }
    }
}

/** Previous, "Shot taken → Next" and Finish; after the last panel, Previous and Finish. */
@Composable
private fun AimMosaicButtons(session: MosaicSession) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AimMosaicButton(
            text = "Previous", onClick = session::previous, enabled = session.step > 0, isPrimary = false,
            description = "Previous panel", modifier = Modifier.weight(1f),
        )
        if (session.isComplete) {
            AimMosaicButton(
                text = "Finish", onClick = session::finish, enabled = true, isPrimary = true,
                description = "Finish the mosaic", modifier = Modifier.weight(1.8f),
            )
        } else {
            AimMosaicButton(
                text = "Shot taken → Next", onClick = session::next, enabled = true, isPrimary = true,
                description = if (session.step + 1 >= session.panelCount) "Shot taken, the last panel" else "Shot taken, go to the next panel",
                modifier = Modifier.weight(1.8f),
            )
            AimMosaicButton(
                text = "Finish", onClick = session::finish, enabled = true, isPrimary = false,
                description = "Finish the mosaic", modifier = Modifier.weight(1f),
            )
        }
    }
}

/** A capsule button of the HUD, drawn from palette colours; the text shrinks rather than wraps. */
@Composable
private fun AimMosaicButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean,
    isPrimary: Boolean,
    description: String,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val shape = RoundedCornerShape(percent = 50)
    Row(
        modifier
            .alpha(if (enabled) 1f else 0.4f)
            .heightIn(min = 44.dp)
            .background(if (isPrimary) palette.accent else palette.surface.copy(alpha = 0.88f), shape)
            .border(1.dp, if (isPrimary) palette.accent else palette.stroke, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AimFittingText(
            text,
            MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            if (isPrimary) palette.onAccent else palette.accent,
            textAlign = TextAlign.Center,
            minimumScale = 0.7f,
        )
    }
}
