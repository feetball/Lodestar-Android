package net.kuecker.lodestar.features.skychart

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.kuecker.lodestar.kit.catalog.ObjectCategory
import net.kuecker.lodestar.shared.AppFormat
import net.kuecker.lodestar.shared.LodestarButton
import net.kuecker.lodestar.shared.LodestarButtonKind
import net.kuecker.lodestar.shared.ObjectIcon
import net.kuecker.lodestar.shared.monospacedDigits
import net.kuecker.lodestar.theme.LocalPalette
import net.kuecker.lodestar.theme.card

/**
 * The card for the selected object: name, type, where it is now, and the actions "Aim" and "Details".
 *
 * @param isLive Whether the chart shows the present; if not, the position is that of the chart's time.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SkyChartCallout(
    selection: SkyChartSelection,
    isLive: Boolean,
    onAim: () -> Unit,
    onDetails: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    CappedFontScale {
        Column(
            modifier
                .fillMaxWidth()
                .blocksTouches()
                .card(palette, 12.dp)
                .semantics {
                    dismiss {
                        onDismiss()
                        true
                    }
                },
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ObjectIcon(selection.obj.category)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(
                        selection.obj.name,
                        color = palette.primaryText,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        subtitle(selection),
                        color = palette.secondaryText,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onDismiss, Modifier.size(44.dp)) {
                    Icon(Icons.Filled.Cancel, contentDescription = "Dismiss", tint = palette.tertiaryText)
                }
            }
            // At large text sizes the position and the buttons stack.
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Position(selection, isLive, Modifier.padding(end = 4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LodestarButton("Aim", onClick = onAim)
                    LodestarButton("Details", onClick = onDetails, kind = LodestarButtonKind.SECONDARY)
                }
            }
        }
    }
}

/**
 * Makes the element take the touches that land on it, so that they do not fall through to what lies below it (a card on
 * the chart must not select the star behind it). Buttons and other controls inside still get their own touches.
 */
internal fun Modifier.blocksTouches(): Modifier = pointerInput(Unit) {}

/** The object's type, constellation and current magnitude. */
private fun subtitle(selection: SkyChartSelection): String {
    val obj = selection.obj
    val parts = arrayListOf(obj.category.displayName)
    obj.constellationName?.let(parts::add)
    selection.magnitude?.let { parts.add(AppFormat.magnitude(it)) }
    return parts.joinToString(" · ")
}

@Composable
private fun Position(selection: SkyChartSelection, isLive: Boolean, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val horizontal = selection.horizontal
    Column(
        modifier.semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        val style = MaterialTheme.typography.bodyMedium.monospacedDigits()
        Text("Alt ${AppFormat.altitude(horizontal.altitude)}", color = palette.primaryText, style = style)
        Text("Az ${AppFormat.azimuth(horizontal.azimuth)}", color = palette.primaryText, style = style)
        if (horizontal.altitude < 0) {
            Text("Below the horizon", color = palette.warning, style = MaterialTheme.typography.bodySmall)
        }
        if (selection.obj.category == ObjectCategory.SUN) {
            Text("Needs a solar filter", color = palette.warning, style = MaterialTheme.typography.bodySmall)
        }
        if (!isLive) {
            Text("At the chart's time", color = palette.tertiaryText, style = MaterialTheme.typography.bodySmall)
        }
    }
}
