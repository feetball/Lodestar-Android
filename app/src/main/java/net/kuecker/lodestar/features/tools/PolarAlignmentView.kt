package net.kuecker.lodestar.features.tools

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FormatListNumbered
import androidx.compose.material.icons.outlined.GpsFixed
import androidx.compose.material.icons.outlined.North
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import net.kuecker.lodestar.kit.formatting.AngleFormatter
import net.kuecker.lodestar.kit.foundation.SkyFrame
import net.kuecker.lodestar.kit.planning.PolarAlignment
import net.kuecker.lodestar.kit.planning.PolarScopeReading
import net.kuecker.lodestar.model.ObserverContext
import net.kuecker.lodestar.shared.AppFormat
import net.kuecker.lodestar.shared.CardTitle
import net.kuecker.lodestar.shared.InfoRow
import net.kuecker.lodestar.shared.LocalAppModel
import net.kuecker.lodestar.shared.LodestarButton
import net.kuecker.lodestar.shared.LodestarButtonKind
import net.kuecker.lodestar.shared.NeedsLocationView
import net.kuecker.lodestar.shared.monospacedDigits
import net.kuecker.lodestar.theme.LocalPalette
import net.kuecker.lodestar.theme.card
import java.time.Instant

/**
 * Shows where the pole star stands relative to the celestial pole right now, as a clock position on a polar-scope
 * reticle, with instructions for aligning an equatorial mount or star tracker. Updates every second.
 *
 * Push it onto a navigation stack (Settings ▸ Tools). The view option is kept while another tab is shown.
 */
@Composable
fun PolarAlignmentScreen(onBack: () -> Unit) {
    val context = LocalAppModel.current.observerContext
    ToolsScreenFrame(title = "Polar Alignment", onBack = onBack) {
        if (context != null) {
            PolarAlignmentContent(context)
        } else {
            NeedsLocationView(Modifier.weight(1f))
        }
    }
}

@Composable
private fun ColumnScope.PolarAlignmentContent(context: ObserverContext) {
    val app = LocalAppModel.current
    val showsInvertedView = rememberWhilePushed("tools.polar.inverted") { mutableStateOf(false) }
    val scroll = rememberWhilePushed("tools.polar.scroll") { ScrollState(0) }
    val now = app.clock.now
    val view = if (showsInvertedView.value) PolarAlignment.ScopeView.INVERTED else PolarAlignment.ScopeView.NAKED
    val reading = PolarAlignment.polarScopeReading(SkyFrame(now, context.observer), view)
    Column(
        Modifier.weight(1f).verticalScroll(scroll).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ReticleCard(reading, context, now)
        DetailsCard(reading)
        OptionsCard(reading, showsInvertedView.value) { showsInvertedView.value = it }
        InstructionsCard(reading)
    }
}

// MARK: Cards

@Composable
private fun ReticleCard(reading: PolarScopeReading, context: ObserverContext, now: Instant) {
    val palette = LocalPalette.current
    Column(
        Modifier.fillMaxWidth().card(palette),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ToolsPolarReticle(reading, Modifier.widthIn(max = 360.dp).fillMaxWidth())
        Column(
            Modifier.semantics(mergeDescendants = true) {},
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                ToolsPolarClock.text(reading.angle),
                color = palette.accent,
                style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.SemiBold)
                    .monospacedDigits(),
            )
            Text(
                "${reading.referenceStarName} on its circle, clock position",
                color = palette.secondaryText,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
            Text(
                "For ${AppFormat.time(now, context.timeZone)} at ${context.name}",
                color = palette.tertiaryText,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun DetailsCard(reading: PolarScopeReading) {
    val palette = LocalPalette.current
    val isNorth = reading.hemisphere == PolarAlignment.Hemisphere.NORTH
    Column(Modifier.fillMaxWidth().card(palette), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CardTitle("The Pole Now", icon = Icons.Outlined.North)
        InfoRow("Pole", if (isNorth) "North celestial pole" else "South celestial pole")
        InfoRow("Pole altitude", AngleFormatter.formatDegrees(reading.poleAltitude, 1))
        InfoRow("Pole direction", AppFormat.azimuth(reading.poleAzimuth))
        InfoRow(
            "${reading.referenceStarName} from the pole",
            AngleFormatter.formatDegrees(reading.separationFromPole, 2),
        )
        InfoRow("${reading.referenceStarName} hour angle", AngleFormatter.formatSignedDegrees(reading.hourAngle, 1))
        Text(
            "The altitude is geometric; refraction lifts the pole by about an arcminute at mid-latitudes.",
            color = palette.tertiaryText,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun OptionsCard(reading: PolarScopeReading, showsInvertedView: Boolean, onInvertedChange: (Boolean) -> Unit) {
    val app = LocalAppModel.current
    val palette = LocalPalette.current
    val isNorth = reading.hemisphere == PolarAlignment.Hemisphere.NORTH
    val pole = app.catalog.objectWithID(if (isNorth) "special.ncp" else "special.scp")
    Column(Modifier.fillMaxWidth().card(palette), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        InvertedViewToggle(showsInvertedView, onInvertedChange)
        Text(
            "Most polar scopes show the sky upside down. Turn this on to read the reticle as you see it through " +
                "the scope; leave it off for the view with the naked eye, zenith at the top.",
            color = palette.secondaryText,
            style = MaterialTheme.typography.bodySmall,
        )
        if (pole != null) {
            LodestarButton(
                text = "Aim at the ${if (isNorth) "North" else "South"} Celestial Pole",
                onClick = { app.aim(pole) },
                kind = LodestarButtonKind.SECONDARY,
                icon = Icons.Outlined.GpsFixed,
            )
        }
    }
}

/** The "Polar scope view (inverted)" switch: the whole row toggles it. */
@Composable
private fun InvertedViewToggle(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val palette = LocalPalette.current
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Polar scope view (inverted)",
            color = palette.primaryText,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = palette.onAccent,
                checkedTrackColor = palette.accent,
                uncheckedThumbColor = palette.secondaryText,
                uncheckedTrackColor = palette.surfaceRaised,
                uncheckedBorderColor = palette.stroke,
            ),
        )
    }
}

@Composable
private fun InstructionsCard(reading: PolarScopeReading) {
    val palette = LocalPalette.current
    Column(Modifier.fillMaxWidth().card(palette), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CardTitle("How to Align", icon = Icons.Outlined.FormatListNumbered)
        steps(reading).forEachIndexed { index, step ->
            Row(
                Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = "Step ${index + 1}. $step" },
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.size(22.dp).background(palette.accent, CircleShape), contentAlignment = Alignment.Center) {
                    Text(
                        "${index + 1}",
                        color = palette.onAccent,
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold)
                            .monospacedDigits(),
                    )
                }
                Text(step, color = palette.primaryText, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** The alignment procedure for the reading's hemisphere, with the numbers filled in. */
private fun steps(reading: PolarScopeReading): List<String> {
    val isNorth = reading.hemisphere == PolarAlignment.Hemisphere.NORTH
    val altitude = AngleFormatter.formatDegrees(reading.poleAltitude, 1)
    val clock = ToolsPolarClock.text(reading.angle)
    return buildList {
        add(
            "Level the tripod, set the mount's latitude scale to $altitude and point the polar axis roughly " +
                "${if (isNorth) "north" else "south"}. The Aim screen can help: aim at the celestial pole.",
        )
        add(
            "Turn the polar scope's reticle (or the mount's right-ascension axis) until the 12 o'clock mark is " +
                "straight up.",
        )
        add(
            "Find ${reading.referenceStarName} and use the mount's altitude and azimuth adjusters to put it on its " +
                "circle at $clock o'clock.",
        )
        if (!isNorth) {
            add(
                "${reading.referenceStarName} is faint, about magnitude 5.4. Let your eyes adapt, or use binoculars " +
                    "to find it.",
            )
        }
        add(
            "The pole star circles the pole once a day, 15° an hour. The clock position above updates every " +
                "second, so follow it if the alignment takes a while.",
        )
    }
}
