package net.kuecker.lodestar.features.events

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import net.kuecker.lodestar.app.AppModel
import net.kuecker.lodestar.kit.catalog.CelestialObject
import net.kuecker.lodestar.kit.ephemeris.SolarSystemBody
import net.kuecker.lodestar.kit.events.AimTarget
import net.kuecker.lodestar.kit.events.AstronomicalEvent
import net.kuecker.lodestar.kit.events.EventDetails
import net.kuecker.lodestar.theme.LocalPalette
import java.time.Instant

/** One thing the Aim screen can be pointed at for an event. [isSun] targets need the solar-filter confirmation. */
internal class EventAimOption(val label: String, val obj: CelestialObject, val isSun: Boolean)

/** What to point at for an event, main subject first; empty while the sky catalog is still loading. */
internal fun aimOptions(event: AstronomicalEvent, app: AppModel, now: Instant): List<EventAimOption> {
    val catalog = app.catalog.catalog
    val names = (event.details as? EventDetails.Conjunction)?.conjunction?.let {
        listOf(it.first.displayName, it.second.displayName)
    }.orEmpty()
    return event.aimTargets.mapIndexedNotNull { index, target ->
        when (target) {
            is AimTarget.Body -> {
                val obj = catalog?.objectWithID(bodyID(target.body)) ?: return@mapIndexedNotNull null
                EventAimOption("Aim at ${obj.name}", obj, isSun = target.body == SolarSystemBody.SUN)
            }
            is AimTarget.Fixed -> {
                val name = names.getOrNull(index) ?: "Event target"
                val known = names.getOrNull(index)?.let { catalog?.search(it, 1)?.firstOrNull()?.obj }
                    ?.takeIf { (it.j2000?.angularSeparation(target.position) ?: Double.MAX_VALUE) < KNOWN_OBJECT_RADIUS }
                val obj = known ?: CelestialObject.custom(name, target.position)
                EventAimOption("Aim at ${obj.name}", obj, isSun = false)
            }
            is AimTarget.Radiant -> {
                val name = "${target.shower.name} radiant"
                EventAimOption("Aim at the $name", CelestialObject.custom(name, target.shower.radiant(now)), isSun = false)
            }
        }
    }
}

/** The catalog identifier of a solar-system body. */
private fun bodyID(body: SolarSystemBody): String = when (body) {
    SolarSystemBody.SUN -> "sun"
    SolarSystemBody.MOON -> "moon"
    else -> "planet.${body.rawValue}"
}

/** A catalog star or cluster counts as the reference place of a conjunction when it is this close to it, in degrees. */
private const val KNOWN_OBJECT_RADIUS = 1.0

/** The one-sentence rule everything about the Sun comes back to. */
internal const val SOLAR_FILTER_RULE =
    "Never point a camera, binoculars, a telescope or your eyes at the Sun without a certified solar filter."

/**
 * Asks for confirmation before the Aim screen is pointed at the Sun: a certified solar filter (ISO 12312-2) must be on
 * the lens first. [onConfirm] aims; [onDismiss] cancels.
 */
@Composable
internal fun SolarFilterDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val palette = LocalPalette.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.surfaceRaised,
        titleContentColor = palette.critical,
        textContentColor = palette.primaryText,
        title = { Text("Solar filter required", fontWeight = FontWeight.Bold) },
        text = {
            Text(
                "$SOLAR_FILTER_RULE Without one the Sun can blind you and burn your camera's sensor in seconds. " +
                    "Put a certified filter (ISO 12312-2) over the lens before you aim.",
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Filter Is On, Aim", color = palette.accent, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = palette.accent) }
        },
    )
}
