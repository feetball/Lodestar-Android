package net.kuecker.lodestar.features.events

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.GpsFixed
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.kuecker.lodestar.kit.events.AstronomicalEvent
import net.kuecker.lodestar.kit.events.MeteorShower
import net.kuecker.lodestar.model.ObserverContext
import net.kuecker.lodestar.shared.CardTitle
import net.kuecker.lodestar.shared.LocalAppModel
import net.kuecker.lodestar.shared.LodestarButton
import net.kuecker.lodestar.shared.LodestarButtonKind
import net.kuecker.lodestar.theme.LocalPalette
import net.kuecker.lodestar.theme.card

/**
 * Where to point the camera for a meteor shower, how many meteors to expect in the frame of the user's lens, and a button
 * that aims at that place. The advice is for the moment of the night when the radiant is highest in the dark; it is worked
 * out off the main thread.
 */
@Composable
internal fun EventsMeteorCameraCard(event: AstronomicalEvent, shower: MeteorShower, context: ObserverContext) {
    val app = LocalAppModel.current
    val palette = LocalPalette.current
    // Keyed on the place and the zone, not on the whole context: the GPS accuracy changing must not start it again.
    val state by produceState<MeteorCameraState>(MeteorCameraState.Loading, event, shower, context.location, context.timeZone) {
        value = withContext(Dispatchers.Default) { EventsMeteorCamera.state(event, shower, context) }
    }
    val lens = app.lensSetup

    Column(Modifier.fillMaxWidth().card(palette), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CardTitle("Camera", icon = Icons.Outlined.CameraAlt)
        when (val current = state) {
            MeteorCameraState.Loading ->
                Text(
                    "Working out where to point the camera…",
                    color = palette.secondaryText,
                    style = MaterialTheme.typography.bodyMedium,
                )
            MeteorCameraState.NoDarkness ->
                Text(
                    "The sky does not get fully dark at your location that night, so there is no camera advice.",
                    color = palette.secondaryText,
                    style = MaterialTheme.typography.bodyMedium,
                )
            MeteorCameraState.RadiantBelowHorizon ->
                Text(
                    "The radiant stays below the horizon all night from here, so there is no place to frame.",
                    color = palette.secondaryText,
                    style = MaterialTheme.typography.bodyMedium,
                )
            is MeteorCameraState.Ready -> {
                val plan = current.plan
                val advice = plan.advice
                val perHour = EventsMeteorCamera.expectedPerHour(plan, shower, lens)
                Column(
                    Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        EventsMeteorCamera.aimText(advice),
                        color = palette.primaryText,
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    )
                    Text(advice.reason, color = palette.secondaryText, style = MaterialTheme.typography.bodyMedium)
                    advice.moonNote?.let {
                        Text(it, color = palette.secondaryText, style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(
                        EventsMeteorCamera.countText(perHour, plan.radiantAltitude, EventsMeteorCamera.lensText(lens)),
                        color = palette.primaryText,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "For ${EventsFormat.whenText(plan.time, context.timeZone)}, when the radiant is highest in the " +
                            "dark. ${EventsMeteorCamera.ESTIMATE_NOTE}",
                        color = palette.tertiaryText,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                LodestarButton(
                    "Aim camera there",
                    onClick = { app.aim(EventsMeteorCamera.target(shower, advice)) },
                    modifier = Modifier.fillMaxWidth(),
                    kind = LodestarButtonKind.SECONDARY,
                    icon = Icons.Outlined.GpsFixed,
                )
            }
        }
        Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(
                Icons.Outlined.Lightbulb,
                contentDescription = null,
                tint = palette.accent,
                modifier = Modifier.padding(top = 2.dp).size(16.dp),
            )
            Text(
                EventsMeteorCamera.TIP,
                color = palette.primaryText,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
