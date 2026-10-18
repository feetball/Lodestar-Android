package net.kuecker.lodestar.features.smallbodies

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.kuecker.lodestar.features.tonight.TonightNote
import net.kuecker.lodestar.features.tonight.TonightNoteStyle
import net.kuecker.lodestar.features.tonight.TonightRowSeparator
import net.kuecker.lodestar.kit.catalog.CelestialObject
import net.kuecker.lodestar.shared.CardTitle
import net.kuecker.lodestar.shared.LodestarButton
import net.kuecker.lodestar.shared.LodestarButtonKind
import net.kuecker.lodestar.theme.LocalPalette
import net.kuecker.lodestar.theme.card
import java.time.ZoneId

/**
 * The comets that are brighter than magnitude 10 and at least 25° from the Sun on the chosen night, brightest first,
 * each opening its detail screen; "Comets and asteroids" opens the full lists. An empty list is normal and says so.
 */
@Composable
internal fun SmallBodyTonightCard(
    entries: List<SmallBodyListEntry>,
    zone: ZoneId,
    onOpenObject: (CelestialObject) -> Unit,
    onOpenList: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    Column(modifier.fillMaxWidth().card(palette), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CardTitle("Bright Comets", icon = Icons.Outlined.AutoFixHigh)
        if (entries.isEmpty()) {
            TonightNote(
                "No comet is brighter than magnitude ${SmallBodyTonightPlanner.CARD_MAGNITUDE_LIMIT.toInt()} tonight. " +
                    "That is normal; bright comets are rare.",
            )
        } else {
            for (entry in entries) {
                if (entry.id != entries.first().id) TonightRowSeparator()
                SmallBodyListRow(entry, zone, showsPeakDate = false, onClick = { onOpenObject(entry.obj) })
            }
            TonightNote(SmallBodyText.FOOTNOTE, style = TonightNoteStyle.FOOTNOTE)
        }
        LodestarButton(
            "Comets and Asteroids",
            onClick = onOpenList,
            modifier = Modifier.fillMaxWidth(),
            kind = LodestarButtonKind.SECONDARY,
        )
    }
}
