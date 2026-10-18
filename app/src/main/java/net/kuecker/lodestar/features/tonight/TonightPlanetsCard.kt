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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.kuecker.lodestar.kit.catalog.CelestialObject
import net.kuecker.lodestar.kit.catalog.ObjectCategory
import net.kuecker.lodestar.kit.formatting.AngleFormatter
import net.kuecker.lodestar.kit.planning.NightSummary
import net.kuecker.lodestar.kit.planning.PlanetVisibility
import net.kuecker.lodestar.shared.AppFormat
import net.kuecker.lodestar.shared.CardTitle
import net.kuecker.lodestar.shared.LocalAppModel
import net.kuecker.lodestar.shared.ObjectIcon
import net.kuecker.lodestar.shared.icon
import net.kuecker.lodestar.shared.monospacedDigits
import net.kuecker.lodestar.theme.LocalPalette
import net.kuecker.lodestar.theme.card
import java.time.ZoneId

/** The planets that are high enough in the dark to photograph, each opening its detail screen. */
@Composable
internal fun TonightPlanetsCard(summary: NightSummary, zone: ZoneId, onOpenObject: (CelestialObject) -> Unit) {
    val app = LocalAppModel.current
    val palette = LocalPalette.current
    val visible = summary.planets.filter { it.visibleDuringDarkness }
    val hidden = summary.planets.filter { !it.visibleDuringDarkness }
    Column(Modifier.fillMaxWidth().card(palette), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CardTitle("Planets", icon = ObjectCategory.PLANET.icon)
        if (!summary.hasAstronomicalDarkness) {
            TonightNote("There is no astronomical darkness on this night.")
        } else if (visible.isEmpty()) {
            TonightNote(
                "No planet is more than ${PlanetVisibility.minimumAltitude.toInt()}° above the horizon while it is dark.",
            )
        } else {
            for (planet in visible) {
                if (planet.body != visible.first().body) TonightRowSeparator()
                TonightPlanetRow(planet, app.catalog.objectWithID("planet.${planet.body.rawValue}"), zone, onOpenObject)
            }
            if (hidden.isNotEmpty()) {
                TonightNote(
                    "Not up in the dark: ${hidden.joinToString(", ") { it.body.displayName }}.",
                    style = TonightNoteStyle.FOOTNOTE,
                )
            }
        }
    }
}

/**
 * One visible planet: name, brightness, when it is up in the dark and how high it gets. [obj] is the catalog entry the
 * row opens; `null` while the catalog is loading.
 */
@Composable
private fun TonightPlanetRow(
    planet: PlanetVisibility,
    obj: CelestialObject?,
    zone: ZoneId,
    onOpenObject: (CelestialObject) -> Unit,
) {
    val palette = LocalPalette.current
    val spoken = spokenSummary(planet, zone)
    val base = Modifier.fillMaxWidth().heightIn(min = 44.dp)
    val tappable = if (obj != null) base.clickable(onClickLabel = "Open details") { onOpenObject(obj) } else base
    Row(
        tappable.clearAndSetSemantics { contentDescription = spoken }.padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        ObjectIcon(ObjectCategory.PLANET)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    planet.body.displayName,
                    color = palette.primaryText,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.weight(1f).alignByBaseline(),
                )
                Text(
                    AppFormat.magnitude(planet.magnitude),
                    color = palette.secondaryText,
                    style = MaterialTheme.typography.bodyMedium.monospacedDigits(),
                    modifier = Modifier.alignByBaseline(),
                )
            }
            Text(timing(planet, zone), color = palette.secondaryText, style = MaterialTheme.typography.bodySmall)
        }
        if (obj != null) {
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = palette.tertiaryText,
                modifier = Modifier.padding(top = 2.dp).size(18.dp),
            )
        }
    }
}

/** "Up 20:15 – 05:40 · highest 62°". */
private fun timing(planet: PlanetVisibility, zone: ZoneId): String {
    var text = "Up " + TonightFormat.windows(planet.visibleIntervals, zone)
    planet.maxAltitudeDuringDarkness?.let { text += " · highest " + AngleFormatter.formatDegrees(it, 0) }
    return text
}

private fun spokenSummary(planet: PlanetVisibility, zone: ZoneId): String {
    val parts = arrayListOf(planet.body.displayName)
    val magnitude = planet.magnitude
    if (magnitude != null && magnitude.isFinite()) {
        parts.add(AppFormat.magnitude(magnitude).replace("mag ", "magnitude "))
    }
    parts.add("up " + TonightFormat.spokenWindows(planet.visibleIntervals, zone))
    planet.maxAltitudeDuringDarkness?.let { parts.add("highest ${AngleFormatter.formatDegrees(it, 0)}") }
    return parts.joinToString(", ")
}
