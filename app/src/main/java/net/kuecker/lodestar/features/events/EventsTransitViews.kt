package net.kuecker.lodestar.features.events

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.GpsFixed
import androidx.compose.material.icons.outlined.NearMe
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.SatelliteAlt
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.kuecker.lodestar.app.AppModel
import net.kuecker.lodestar.app.AppTab
import net.kuecker.lodestar.kit.catalog.CelestialObject
import net.kuecker.lodestar.kit.ephemeris.SolarSystemBody
import net.kuecker.lodestar.kit.formatting.AngleFormatter
import net.kuecker.lodestar.model.ObserverContext
import net.kuecker.lodestar.services.OnlineFeed
import net.kuecker.lodestar.features.satellites.SatelliteText
import net.kuecker.lodestar.shared.AppFormat
import net.kuecker.lodestar.shared.CardTitle
import net.kuecker.lodestar.shared.LocalAppModel
import net.kuecker.lodestar.shared.LocalSheetDismiss
import net.kuecker.lodestar.shared.LodestarButton
import net.kuecker.lodestar.shared.LodestarButtonKind
import net.kuecker.lodestar.shared.LodestarSheet
import net.kuecker.lodestar.shared.monospacedDigits
import net.kuecker.lodestar.theme.LocalPalette
import net.kuecker.lodestar.theme.card
import java.time.Instant

// Section of the calendar

/**
 * "ISS transits of the Sun and Moon" at the top of the calendar: the next transits within 50 km, each a row that opens
 * [onOpen]. Without the satellite feed or with orbit data too old it is a one-line hint with a button to Online Data.
 */
@Composable
internal fun EventsTransitSection(
    context: ObserverContext,
    model: EventsTransitModel,
    now: Instant,
    onOpen: (EventsTransit) -> Unit,
) {
    val app = LocalAppModel.current
    val palette = LocalPalette.current
    val online = app.online
    val availability = EventsTransits.availability(online.isEnabled(OnlineFeed.SATELLITE_PASSES), online.satellites, now)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).card(palette),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CardTitle(EventsTransitText.TITLE, icon = Icons.Outlined.SatelliteAlt)
        when (availability) {
            EventsTransitAvailability.OFF -> Hint(EventsTransitText.OFF_HINT)
            EventsTransitAvailability.NEEDS_DATA -> Hint(EventsTransitText.DATA_HINT)
            EventsTransitAvailability.READY -> {
                val items = model.items
                if (items == null) {
                    Note(EventsTransitText.FINDING)
                } else {
                    val upcoming = EventsTransits.upcoming(items, now)
                    if (upcoming.isEmpty()) {
                        Note(EventsTransitText.NONE)
                    } else {
                        Column {
                            upcoming.forEachIndexed { index, item ->
                                if (index > 0) HorizontalDivider(thickness = 0.5.dp, color = palette.stroke)
                                EventsTransitRow(item, context, onClick = { onOpen(item) })
                            }
                        }
                        Text(EventsTransitText.NOTE, color = palette.tertiaryText, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        Text(SatelliteText.ATTRIBUTION, color = palette.tertiaryText, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Note(text: String) {
    Text(text, color = LocalPalette.current.secondaryText, style = MaterialTheme.typography.bodyMedium)
}

/** A one-line hint with the button to Settings ▸ Online Data. */
@Composable
private fun Hint(text: String) {
    val app = LocalAppModel.current
    Note(text)
    LodestarButton(
        EventsTransitText.ONLINE_DATA,
        onClick = { app.selectedTab = AppTab.SETTINGS },
        modifier = Modifier.fillMaxWidth(),
        kind = LodestarButtonKind.SECONDARY,
    )
}

/** One transit: the body's icon, "Sun transit" or "Moon transit from here", the time and centreline or duration, and the satellite. */
@Composable
private fun EventsTransitRow(item: EventsTransit, context: ObserverContext, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val zone = context.timeZone
    val spoken = remember(item, zone) { EventsTransitText.spokenRow(item, zone) }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Open details", role = Role.Button, onClick = onClick)
            .clearAndSetSemantics { contentDescription = spoken }
            .heightIn(min = 56.dp)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(bodyIcon(item.transit.body), contentDescription = null, tint = palette.accent, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                EventsTransitText.title(item),
                color = palette.primaryText,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
            )
            Text(
                EventsTransitText.rowDetail(item, zone),
                color = palette.secondaryText,
                style = MaterialTheme.typography.bodyMedium.monospacedDigits(),
            )
        }
        Text(item.satelliteName, color = palette.secondaryText, style = MaterialTheme.typography.bodyMedium)
        Icon(
            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = palette.tertiaryText,
            modifier = Modifier.size(18.dp),
        )
    }
}

private fun bodyIcon(body: SolarSystemBody) = if (body == SolarSystemBody.SUN) Icons.Outlined.WbSunny else Icons.Outlined.DarkMode

// Detail

/**
 * Everything about one transit in a sheet: when and where in the sky, how long it lasts, how big the satellite looks, where
 * the centreline is (with a button that copies its coordinates), how to expose, and a button to aim at the Sun or the Moon.
 */
@Composable
internal fun EventsTransitSheet(item: EventsTransit, context: ObserverContext, onDismiss: () -> Unit) {
    LodestarSheet(onDismiss = onDismiss) {
        EventsTransitDetail(item, context)
    }
}

@Composable
private fun EventsTransitDetail(item: EventsTransit, context: ObserverContext) {
    val palette = LocalPalette.current
    val transit = item.transit
    val zone = context.timeZone
    val body = EventsTransitText.bodyName(transit.body)
    val isSun = transit.body == SolarSystemBody.SUN
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                "${EventsTransitText.title(item)} · ${item.satelliteName}",
                color = palette.primaryText,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.semantics { heading() },
            )
            Text(
                "${AppFormat.day(transit.date, zone)} · ${SatelliteText.timeWithSeconds(transit.date, zone)}",
                color = palette.secondaryText,
                style = MaterialTheme.typography.bodyLarge.monospacedDigits(),
            )
        }
        if (isSun) SolarFilterBanner()
        DetailCard("Transit", Icons.Outlined.Schedule) {
            DetailRow("$body altitude", AngleFormatter.formatDegrees(transit.bodyAltitude, 1))
            DetailRow("$body azimuth", AppFormat.azimuth(transit.bodyAzimuth))
            DetailRow("Sky", EventsTransitText.sky(transit.isDaylight, transit.isNight))
            DetailRow("Seen from here", EventsTransitText.fromHere(transit.isFromSite, transit.minimumSeparation, transit.bodyAngularRadius))
            if (transit.isFromSite) DetailRow("Crossing here", EventsTransitText.duration(transit.siteDurationSeconds))
            DetailRow("Longest crossing", EventsTransitText.duration(transit.centrelineDurationSeconds))
            DetailRow("Satellite size", EventsTransitText.size(transit.catalogNumber, transit.satelliteAngularSize))
            DetailRow("Satellite range", EventsTransitText.range(transit.satelliteRangeKilometers))
        }
        CentrelineCard(item)
        DetailCard(EventsTransitText.EXPOSURE_TITLE, Icons.Outlined.PhotoCamera) {
            Text(
                if (isSun) EventsTransitText.EXPOSURE_SUN else EventsTransitText.EXPOSURE_MOON,
                color = palette.primaryText,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        AimButton(transit.body)
    }
}

/** The warning that goes with every Sun transit. */
@Composable
private fun SolarFilterBanner() {
    val palette = LocalPalette.current
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .background(palette.critical.copy(alpha = 0.16f), shape)
            .border(2.dp, palette.critical, shape)
            .padding(14.dp)
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Warning, contentDescription = null, tint = palette.critical, modifier = Modifier.size(26.dp))
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                EventsTransitText.SOLAR_FILTER_TITLE,
                color = palette.critical,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            )
            Text(SOLAR_FILTER_RULE, color = palette.primaryText, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Distance, direction and coordinates of the centreline, and a button that copies the coordinates. */
@Composable
private fun CentrelineCard(item: EventsTransit) {
    val palette = LocalPalette.current
    val centreline = item.transit.centreline
    DetailCard(EventsTransitText.CENTRELINE_TITLE, Icons.Outlined.NearMe) {
        if (centreline == null) {
            Text(EventsTransitText.NO_CENTRELINE, color = palette.secondaryText, style = MaterialTheme.typography.bodyMedium)
            return@DetailCard
        }
        val coordinates = EventsTransitText.coordinates(centreline.location)
        val clipboard = LocalClipboard.current
        val scope = rememberCoroutineScope()
        var isCopied by remember(coordinates) { mutableStateOf(false) }
        DetailRow("Distance", EventsTransitText.distance(centreline.distanceMeters))
        DetailRow("Direction", EventsTransitText.bearing(centreline.bearing))
        DetailRow("Coordinates", coordinates)
        LodestarButton(
            if (isCopied) EventsTransitText.COPIED else EventsTransitText.COPY,
            onClick = {
                scope.launch {
                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Transit centreline", coordinates)))
                    isCopied = true
                }
            },
            modifier = Modifier.fillMaxWidth(),
            kind = LodestarButtonKind.SECONDARY,
            icon = Icons.Outlined.ContentCopy,
        )
        Text(
            if (item.transit.isFromSite) EventsTransitText.FROM_HERE_NOTE else EventsTransitText.CENTRELINE_NOTE,
            color = palette.tertiaryText,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** "Aim at the Moon" / "Aim at the Sun"; the Sun first asks for the solar filter, as every Sun target does. */
@Composable
private fun AimButton(body: SolarSystemBody) {
    val app = LocalAppModel.current
    val dismiss = LocalSheetDismiss.current
    // The catalog arrives asynchronously; reading its state here makes the button appear when it has.
    val target: CelestialObject? = app.catalog.catalog?.objectWithID(if (body == SolarSystemBody.SUN) "sun" else "moon")
    var isAsking by remember { mutableStateOf(false) }
    if (target == null) return
    LodestarButton(
        EventsTransitText.aimLabel(body),
        onClick = { if (body == SolarSystemBody.SUN) isAsking = true else aim(app, target, dismiss) },
        modifier = Modifier.fillMaxWidth(),
        icon = Icons.Outlined.GpsFixed,
    )
    if (isAsking) {
        SolarFilterDialog(
            onConfirm = {
                isAsking = false
                aim(app, target, dismiss)
            },
            onDismiss = { isAsking = false },
        )
    }
}

private fun aim(app: AppModel, target: CelestialObject, dismiss: () -> Unit) {
    app.aim(target)
    dismiss()
}

@Composable
private fun DetailCard(title: String, icon: ImageVector, content: @Composable () -> Unit) {
    val palette = LocalPalette.current
    Column(Modifier.fillMaxWidth().card(palette), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CardTitle(title, icon = icon)
        content()
    }
}

/** A label on the left and its value on the right, read as one line by screen readers. */
@Composable
private fun DetailRow(label: String, value: String) {
    val palette = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, color = palette.secondaryText, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            value,
            color = palette.primaryText,
            style = MaterialTheme.typography.bodyMedium.monospacedDigits(),
            textAlign = TextAlign.End,
        )
    }
}
