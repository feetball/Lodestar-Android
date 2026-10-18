package net.kuecker.lodestar.features.tonight

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.GpsFixed
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.kuecker.lodestar.kit.catalog.CelestialObject
import net.kuecker.lodestar.kit.formatting.AngleFormatter
import net.kuecker.lodestar.services.CatalogService
import net.kuecker.lodestar.shared.AppFormat
import net.kuecker.lodestar.shared.CardTitle
import net.kuecker.lodestar.shared.LocalAppModel
import net.kuecker.lodestar.shared.LodestarButton
import net.kuecker.lodestar.shared.LodestarButtonKind
import net.kuecker.lodestar.shared.ObjectIcon
import net.kuecker.lodestar.shared.monospacedDigits
import net.kuecker.lodestar.theme.LocalPalette
import net.kuecker.lodestar.theme.card
import java.time.ZoneId

/** The deep-sky objects that rise highest in the dark, brightest and biggest first, each opening its detail screen. */
@Composable
internal fun TonightTargetsCard(outcome: TonightTargetsOutcome, zone: ZoneId, onOpenObject: (CelestialObject) -> Unit) {
    val palette = LocalPalette.current
    val minimum = TonightCalculator.minimumTargetAltitude.toInt()
    Column(Modifier.fillMaxWidth().card(palette), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CardTitle("Best Targets", icon = Icons.Outlined.GpsFixed)
        when (outcome) {
            is TonightTargetsOutcome.Ranked -> {
                if (outcome.targets.isEmpty()) {
                    TonightNote("No listed deep-sky object climbs above $minimum° while it is dark.")
                } else {
                    for (target in outcome.targets) {
                        if (target.id != outcome.targets.first().id) TonightRowSeparator()
                        TonightTargetRow(target, zone, onOpenObject)
                    }
                    TonightNote(
                        "Ranked by height in the dark, brightness and size. Only objects that climb above $minimum° " +
                            "are listed.",
                        style = TonightNoteStyle.FOOTNOTE,
                    )
                }
            }
            TonightTargetsOutcome.NoDarkness ->
                TonightNote("There is no astronomical darkness on this night, so there is nothing to rank.")
            TonightTargetsOutcome.CatalogPending -> CatalogStatus()
        }
    }
}

/** The catalog is still loading, or could not be loaded. */
@Composable
private fun CatalogStatus() {
    val app = LocalAppModel.current
    val scope = rememberCoroutineScope()
    val state = app.catalog.loadState
    if (state is CatalogService.LoadState.Failed) {
        TonightNote(state.message)
        LodestarButton(
            "Try Again",
            onClick = { scope.launch { app.catalog.load() } },
            modifier = Modifier.fillMaxWidth(),
            kind = LodestarButtonKind.SECONDARY,
            icon = Icons.Outlined.Refresh,
        )
    } else {
        TonightNote("Loading the sky catalog…")
    }
}

/** One target: what it is, how high it gets in the dark and when. */
@Composable
private fun TonightTargetRow(target: TonightTarget, zone: ZoneId, onOpenObject: (CelestialObject) -> Unit) {
    val palette = LocalPalette.current
    val obj = target.obj
    val spoken = spokenSummary(target, zone)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable(onClickLabel = "Open details") { onOpenObject(obj) }
            .clearAndSetSemantics { contentDescription = spoken }
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        ObjectIcon(obj.category)
        Column(Modifier.weight(1f).padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                title(obj),
                color = palette.primaryText,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
            )
            Text(TonightFormat.description(obj), color = palette.secondaryText, style = MaterialTheme.typography.bodySmall)
            Text(timing(target, zone), color = palette.tertiaryText, style = MaterialTheme.typography.bodySmall)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                AngleFormatter.formatDegrees(target.peakAltitude, 0),
                color = palette.accent,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold).monospacedDigits(),
            )
            Text("highest", color = palette.tertiaryText, style = MaterialTheme.typography.labelSmall)
        }
        Icon(
            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = palette.tertiaryText,
            modifier = Modifier.padding(top = 4.dp).size(18.dp),
        )
    }
}

/** The name, with the first catalog designation when it differs: "Andromeda Galaxy · M 31". */
private fun title(obj: CelestialObject): String =
    TonightFormat.designation(obj)?.let { "${obj.name} · $it" } ?: obj.name

/** "Best 23:40 facing SE · 7h 12m above 20°". */
private fun timing(target: TonightTarget, zone: ZoneId): String {
    val minimum = TonightCalculator.minimumTargetAltitude.toInt()
    return "Best ${AppFormat.time(target.peakTime, zone)} " +
        TonightFormat.facing(target.peakAzimuth, target.peakAltitude) +
        " · ${TonightFormat.duration(target.hoursAboveMinimum)} above $minimum°"
}

private fun spokenSummary(target: TonightTarget, zone: ZoneId): String {
    val obj = target.obj
    val parts = arrayListOf(obj.name, obj.category.displayName)
    obj.constellationName?.let(parts::add)
    parts.add(
        "highest ${AngleFormatter.formatDegrees(target.peakAltitude, 0)} at ${AppFormat.time(target.peakTime, zone)}" +
            ", " + TonightFormat.spokenFacing(target.peakAzimuth, target.peakAltitude),
    )
    parts.add(
        "${TonightFormat.duration(target.hoursAboveMinimum)} above ${TonightCalculator.minimumTargetAltitude.toInt()} degrees",
    )
    return parts.joinToString(", ")
}
