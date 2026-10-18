package net.kuecker.lodestar.features.tonight

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NightsStay
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import net.kuecker.lodestar.kit.catalog.CelestialObject
import net.kuecker.lodestar.shared.LocalAppModel
import net.kuecker.lodestar.theme.LocalPalette
import net.kuecker.lodestar.theme.card
import java.time.Instant

/** The cards that describe one night, from the darkness timeline to the best targets. */
@Composable
internal fun TonightReportCards(report: TonightReport, onOpenObject: (CelestialObject) -> Unit) {
    TonightTimelineCard(report)
    TonightSunCard(report.summary, report.timeZone)
    TonightMoonCard(report.summary, report.timeZone)
    TonightMilkyWayCard(report.summary, report.timeZone)
    TonightPlanetsCard(report.summary, report.timeZone, onOpenObject)
    TonightTargetsCard(report.targets, report.timeZone, onOpenObject)
}

/** How a [TonightNote] reads. */
internal enum class TonightNoteStyle { BODY, FOOTNOTE, WARNING }

/** A sentence of explanation inside a card: ordinary text, a quieter footnote or a warning. */
@Composable
internal fun TonightNote(text: String, modifier: Modifier = Modifier, style: TonightNoteStyle = TonightNoteStyle.BODY) {
    val palette = LocalPalette.current
    Text(
        text,
        modifier = modifier,
        color = when (style) {
            TonightNoteStyle.BODY -> palette.secondaryText
            TonightNoteStyle.FOOTNOTE -> palette.tertiaryText
            TonightNoteStyle.WARNING -> palette.warning
        },
        style = if (style == TonightNoteStyle.BODY) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
    )
}

/** A hairline between the rows of a card, drawn in the palette's stroke colour. */
@Composable
internal fun TonightRowSeparator() {
    HorizontalDivider(thickness = 0.5.dp, color = LocalPalette.current.stroke)
}

/** Stands in for the cards while the first night is being worked out. */
@Composable
internal fun TonightPendingCard() {
    val palette = LocalPalette.current
    Column(
        Modifier
            .fillMaxWidth()
            // 160 dp of content inside the card's 14 dp padding.
            .heightIn(min = 188.dp)
            .card(palette)
            .semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        Icon(Icons.Outlined.NightsStay, contentDescription = null, tint = palette.tertiaryText, modifier = Modifier.size(36.dp))
        Text("Working out the night…", color = palette.secondaryText, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * The present moment, refreshed every [periodMillis] (the counterpart of `TimelineView(.periodic)`): cards that show
 * "now" markers or readings that drift slowly redraw only that often.
 */
@Composable
internal fun rememberPeriodicNow(periodMillis: Long): Instant {
    val clock = LocalAppModel.current.clock
    // The clock ticks every second; reading it here must not subscribe the caller to those ticks.
    var now by remember(clock) { mutableStateOf(Snapshot.withoutReadObservation { clock.now }) }
    LaunchedEffect(clock, periodMillis) {
        while (true) {
            delay(periodMillis)
            now = clock.now
        }
    }
    return now
}
