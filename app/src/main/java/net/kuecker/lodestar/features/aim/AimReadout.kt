package net.kuecker.lodestar.features.aim

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.ArrowCircleUp
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.VerticalAlignBottom
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.kuecker.lodestar.kit.catalog.CelestialObject
import net.kuecker.lodestar.kit.catalog.ObjectCategory
import net.kuecker.lodestar.kit.foundation.HorizontalCoordinate
import net.kuecker.lodestar.shared.AppFormat
import net.kuecker.lodestar.shared.LocalAppModel
import net.kuecker.lodestar.shared.LodestarButton
import net.kuecker.lodestar.shared.ObjectIcon
import net.kuecker.lodestar.shared.monospacedDigits
import net.kuecker.lodestar.theme.LocalPalette

/**
 * The panel above the controls: the target and how to reach it, or the offer to choose one. It updates about ten
 * times a second.
 */
@Composable
internal fun AimReadout(
    model: AimViewModel,
    onSheet: (AimSheet) -> Unit,
    isActive: Boolean,
    modifier: Modifier = Modifier,
) {
    val target = model.guidanceTarget
    if (target == null) {
        AimNoTargetPrompt(model, onSheet, modifier)
        return
    }
    val frame = rememberAimFrame(model, 100, isActive)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AimTargetCard(target, frame?.target?.horizontal, belowHorizonNote(model, target, frame))
        frame?.guidance?.let { AimMoveRow(AimGuidanceSummary(it)) }
    }
}

/** "Below the horizon · rises 23:41 (in 5h 12m)", or `null` while the target is up. */
private fun belowHorizonNote(model: AimViewModel, target: CelestialObject, frame: AimFrame?): String? {
    val altitude = frame?.target?.horizontal?.altitude ?: return null
    if (altitude >= 0) return null
    val info = model.riseInfo
    val timeZone = model.app.observerContext?.timeZone
    if (info == null || info.targetID != target.id || timeZone == null) return "Below the horizon"
    val rise = info.nextRise ?: return "Below the horizon · does not rise in the next 24 hours"
    return "Below the horizon · rises ${AppFormat.time(rise, timeZone)} (${AppFormat.relative(rise, model.app.clock.now)})"
}

/** The target's name, kind and position, with a warning while it is below the horizon. */
@Composable
private fun AimTargetCard(obj: CelestialObject, horizontal: HorizontalCoordinate?, belowHorizonNote: String?) {
    val palette = LocalPalette.current
    val subtitle = listOfNotNull(obj.category.displayName, obj.constellationName).joinToString(" · ")
    Column(
        Modifier.fillMaxWidth().aimPanel(palette).semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ObjectIcon(obj.category, size = 16.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                AimFittingText(
                    obj.name,
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
            if (horizontal != null) {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    val style = MaterialTheme.typography.labelMedium.monospacedDigits()
                    Text("Alt ${AppFormat.altitude(horizontal.altitude)}", color = palette.secondaryText, style = style)
                    Text("Az ${AppFormat.azimuth(horizontal.azimuth)}", color = palette.secondaryText, style = style)
                }
            }
        }
        if (belowHorizonNote != null) {
            CardNote(belowHorizonNote, Icons.Outlined.VerticalAlignBottom, palette.warning)
        }
        if (obj.category == ObjectCategory.SUN) {
            CardNote("Never point at the Sun without a certified solar filter", Icons.Filled.Warning, palette.critical)
        }
    }
}

/** A short, bold remark with an icon, inside the target card. */
@Composable
private fun CardNote(text: String, icon: ImageVector, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        Text(text, color = color, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
    }
}

/** The pan and tilt the head has to make, or the state that replaces them. */
@Composable
private fun AimMoveRow(summary: AimGuidanceSummary) {
    val palette = LocalPalette.current
    // The summary element of the screen speaks the same numbers once a second.
    Box(Modifier.fillMaxWidth().clearAndSetSemantics {}) {
        when {
            summary.isOnTarget -> Banner(
                "In your frame · ${AimFormat.angle(summary.separation)} from center", Icons.Filled.CheckCircle, palette.positive,
            )
            summary.isNearZenith -> Banner("Near zenith — follow the arrow", Icons.Outlined.ArrowCircleUp, palette.guidance)
            else -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AimMovePill(summary.pan, Modifier.weight(1f))
                AimMovePill(summary.tilt, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Banner(text: String, icon: ImageVector, color: Color) {
    val palette = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().aimPanel(palette, padding = 6.dp).heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        Text(text, color = color, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
    }
}

@Composable
private fun AimMovePill(move: AimGuidanceSummary.Move, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    Column(
        modifier.aimPanel(palette, padding = 4.dp).heightIn(min = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(move.icon, contentDescription = null, tint = palette.secondaryText, modifier = Modifier.size(14.dp))
            Text(
                move.verb,
                color = palette.secondaryText,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            )
        }
        AimFixedTextSize {
            AimFittingText(
                AimFormat.angle(move.degrees),
                TextStyle(fontSize = 30.sp, fontWeight = FontWeight.SemiBold).monospacedDigits(),
                palette.guidance,
                minimumScale = 0.7f,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Shown while there is no target: quick picks and a way to search. */
@Composable
private fun AimNoTargetPrompt(model: AimViewModel, onSheet: (AimSheet) -> Unit, modifier: Modifier = Modifier) {
    val app = LocalAppModel.current
    val palette = LocalPalette.current
    val snapshot = model.skyContent?.snapshot
    val picks = remember(app.catalog.catalog, snapshot) { AimQuickPicks.picks(app.catalog, snapshot, planetLimit = 2) }
    Column(
        modifier.fillMaxWidth().aimPanel(palette, padding = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "Choose what to aim at",
            color = palette.primaryText,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        )
        Text(
            "Lodestar then shows how to turn the camera towards it.",
            color = palette.secondaryText,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
        )
        AimFlowLayout(Modifier.fillMaxWidth()) {
            for (pick in picks) {
                key(pick.id) { AimQuickPickButton(pick, onClick = { app.select(pick.obj) }) }
            }
        }
        LodestarButton("Search all objects", onClick = { onSheet(AimSheet.TARGET_PICKER) }, icon = Icons.Outlined.Search)
    }
}
