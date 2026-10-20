package net.kuecker.lodestar.features.objects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.kuecker.lodestar.features.smallbodies.SmallBodyText
import net.kuecker.lodestar.kit.catalog.CelestialObject
import net.kuecker.lodestar.shared.AppFormat
import net.kuecker.lodestar.shared.ObjectIcon
import net.kuecker.lodestar.shared.monospacedDigits
import net.kuecker.lodestar.theme.LocalPalette
import java.time.ZoneId

/**
 * One object in the Objects list: icon, names, a one-line description and, on the right, where the object is in
 * the sky now or when it rises ([position] is `null` while positions are not known). With very large text the
 * position moves under the names. Screen readers get one summary of everything the row shows.
 */
@Composable
internal fun ObjectRow(
    obj: CelestialObject,
    modifier: Modifier = Modifier,
    position: ObjectSkyPosition? = null,
    isFavorite: Boolean = false,
    zone: ZoneId = ZoneId.systemDefault(),
    showsProminence: Boolean = false,
) {
    val isLargeText = LocalDensity.current.fontScale >= LARGE_TEXT_SCALE
    val summary = remember(obj, position, isFavorite, zone, showsProminence) {
        val spoken = ObjectsFormat.spokenSummary(obj, position, isFavorite, zone)
        if (showsProminence) "$spoken, ${obj.prominence.displayName}" else spoken
    }
    // The width of the row caps the position, so the names keep the rest even beside a long "Clears skyline tomorrow".
    BoxWithConstraints(modifier) {
        Row(
            Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = summary },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ObjectIcon(obj.category)
            if (isLargeText) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Names(obj, position, isFavorite, showsProminence, subtitleLines = 2)
                    SkyPosition(position, zone, isAlignedToEnd = false)
                }
            } else {
                Names(obj, position, isFavorite, showsProminence, subtitleLines = 2, modifier = Modifier.weight(1f))
                SkyPosition(
                    position,
                    zone,
                    isAlignedToEnd = true,
                    modifier = Modifier.widthIn(max = maxWidth * POSITION_MAXIMUM_SHARE),
                )
            }
        }
    }
}

/**
 * The name with the first designation and the favorite star, and the description ([subtitleLines] lines at most).
 * The name has priority for the width: the designation and the star move to the next line before the name is
 * squeezed.
 */
@Composable
private fun Names(
    obj: CelestialObject,
    position: ObjectSkyPosition?,
    isFavorite: Boolean,
    showsProminence: Boolean,
    subtitleLines: Int,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val designation = remember(obj) { ObjectsFormat.rowDesignations(obj) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.Bottom) {
            Text(
                SmallBodyText.displayName(obj),
                color = palette.primaryText,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                maxLines = MAXIMUM_NAME_LINES,
                overflow = TextOverflow.Ellipsis,
            )
            if (designation != null) {
                Text(
                    designation,
                    color = palette.tertiaryText,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (isFavorite) {
                Icon(
                    Icons.Filled.Star,
                    contentDescription = null,
                    tint = palette.accent,
                    modifier = Modifier.padding(bottom = 4.dp).size(12.dp),
                )
            }
        }
        Text(
            ObjectsFormat.rowSubtitle(obj, position?.magnitude, position) +
                if (showsProminence) "${AppFormat.NO_BREAK_SPACE}· ${AppFormat.unbreakable(obj.prominence.displayName)}" else "",
            color = palette.secondaryText,
            style = MaterialTheme.typography.bodySmall,
            // The kind and magnitude of a comet or an asteroid take one line, the elongation and warnings the next.
            maxLines = if (obj.smallBody != null) maxOf(subtitleLines, 3) else subtitleLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SkyPosition(
    position: ObjectSkyPosition?,
    zone: ZoneId,
    isAlignedToEnd: Boolean,
    modifier: Modifier = Modifier,
) {
    if (position == null) return
    val palette = LocalPalette.current
    val textAlign = if (isAlignedToEnd) TextAlign.End else TextAlign.Start
    Column(
        modifier,
        horizontalAlignment = if (isAlignedToEnd) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (position.isUp) {
            Text(
                ObjectsFormat.altitudeAndDirection(position),
                color = palette.accent,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold).monospacedDigits(),
                maxLines = 1,
                textAlign = textAlign,
            )
        } else {
            Text(
                if (position.isBehindSkyline) "Behind skyline" else "Below horizon",
                color = palette.tertiaryText,
                style = MaterialTheme.typography.bodyMedium.monospacedDigits(),
                maxLines = 2,
                textAlign = textAlign,
            )
            val rising = remember(position, zone) { ObjectsFormat.risingText(position, zone) }
            if (rising != null) {
                Text(
                    rising,
                    color = palette.secondaryText,
                    style = MaterialTheme.typography.bodySmall.monospacedDigits(),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = textAlign,
                )
            }
        }
    }
}

/** The system font scale from which the row stacks its position under the names (iOS accessibility sizes). */
private const val LARGE_TEXT_SCALE = 1.5f

/** Long names wrap onto this many lines (two on iOS; narrow phones leave less room beside the position). */
private const val MAXIMUM_NAME_LINES = 3

/** The share of the row's width the rise or altitude column may take; the names keep the rest. */
private const val POSITION_MAXIMUM_SHARE = 0.4f
