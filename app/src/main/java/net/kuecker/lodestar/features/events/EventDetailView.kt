package net.kuecker.lodestar.features.events

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BlurCircular
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.GpsFixed
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.kuecker.lodestar.app.AppTab
import net.kuecker.lodestar.features.objects.ObjectsTopBar
import net.kuecker.lodestar.kit.events.AstronomicalEvent
import net.kuecker.lodestar.kit.events.EventKind
import net.kuecker.lodestar.model.ObserverContext
import net.kuecker.lodestar.shared.CardTitle
import net.kuecker.lodestar.shared.LocalAppModel
import net.kuecker.lodestar.shared.LodestarButton
import net.kuecker.lodestar.shared.LodestarButtonKind
import net.kuecker.lodestar.shared.NeedsLocationView
import net.kuecker.lodestar.shared.monospacedDigits
import net.kuecker.lodestar.theme.LocalPalette
import net.kuecker.lodestar.theme.card
import net.kuecker.lodestar.theme.screenBackground

/**
 * Everything about one event: what it is, why to photograph it, how, when it happens here, whether it can be seen from
 * here, and buttons to aim at it or show it in the sky chart. Solar eclipses carry an unmissable safety notice.
 */
@Composable
internal fun EventDetailScreen(event: AstronomicalEvent, onBack: () -> Unit) {
    val palette = LocalPalette.current
    val context = LocalAppModel.current.observerContext
    Column(Modifier.fillMaxSize().screenBackground(palette)) {
        ObjectsTopBar(event.kind.displayName, onBack = onBack)
        if (context == null) {
            NeedsLocationView(Modifier.weight(1f))
        } else {
            EventDetailContent(event, context, Modifier.weight(1f))
        }
    }
}

@Composable
private fun EventDetailContent(event: AstronomicalEvent, context: ObserverContext, modifier: Modifier) {
    val app = LocalAppModel.current
    val palette = LocalPalette.current
    LaunchedEffect(Unit) { app.catalog.load() }
    val plan by produceState<EventPlan?>(null, event, context) {
        value = withContext(Dispatchers.Default) { EventsPlanner.plan(event, context) }
    }
    val lens = app.lensSetup
    val guide = remember(event, context, plan, lens) { EventsGuide.guide(event, context, plan, lens) }
    val times = remember(event, context, plan) { EventsTimes.times(event, context, plan) }
    val visibility = remember(event, context) { EventsTimes.visibilityRows(event, context) }
    val isSolarEclipse = event.kind == EventKind.SOLAR_ECLIPSE

    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (isSolarEclipse) EventsSolarSafetyNotice()
        EventDetailHeader(event, context)
        EventActions(event, context)
        if (visibility.isNotEmpty()) {
            EventInfoCard("From Here", Icons.Outlined.Visibility, visibility) {
                EventVisibilityNote(event)
            }
        }
        EventInfoCard("Local Times", Icons.Outlined.Schedule, times) {
            Text(
                "Times are in ${context.name}'s time zone.",
                color = palette.tertiaryText,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        EventTextCard("What It Is", Icons.Outlined.Info, guide.what)
        EventTextCard("Why Photograph It", Icons.Outlined.AutoAwesome, listOf(guide.why))
        EventTipsCard(guide.tips)
    }
}

// Header

@Composable
private fun EventDetailHeader(event: AstronomicalEvent, context: ObserverContext) {
    val palette = LocalPalette.current
    val app = LocalAppModel.current
    val date = remember(event) { EventsFormat.displayDate(event) }
    val now = Snapshot.withoutReadObservation { app.clock.now }
    Column(Modifier.fillMaxWidth().card(palette), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).background(palette.surfaceRaised, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(EventsFormat.icon(event.kind), contentDescription = null, tint = palette.accent, modifier = Modifier.size(24.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    event.title,
                    color = palette.primaryText,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    EventsFormat.whenText(date, context.timeZone),
                    color = palette.secondaryText,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    EventsFormat.distance(date, now, context.timeZone),
                    color = palette.tertiaryText,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Text(
            EventsFormat.summary(event, context),
            color = palette.primaryText,
            style = MaterialTheme.typography.bodyMedium,
        )
        EventBadges(event)
    }
}

// Safety

/** The notice at the top of every solar eclipse: pointing a camera or eyes at the Sun needs a certified filter. */
@Composable
internal fun EventsSolarSafetyNotice(modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier
            .fillMaxWidth()
            .background(palette.critical.copy(alpha = 0.16f), shape)
            .border(BorderStroke(2.dp, palette.critical), shape)
            .padding(16.dp)
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Warning, contentDescription = null, tint = palette.critical, modifier = Modifier.size(28.dp))
            Text(
                "Solar filter required",
                color = palette.critical,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.semantics { heading() },
            )
        }
        Text(
            SOLAR_FILTER_RULE,
            color = palette.primaryText,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
        )
        Text(
            "Looking at the Sun, even briefly or when it is partly covered, can blind you for life and burn a camera's " +
                "sensor in seconds. Use eclipse glasses (ISO 12312-2) for your eyes and a certified solar filter over " +
                "the front of the lens. Sunglasses, smoked glass and exposed film are not safe. Only during the total " +
                "phase of a total eclipse may the filter come off.",
            color = palette.secondaryText,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

// Actions

/** Aim at the event's subjects, and show the sky as it is when the event happens. */
@Composable
private fun EventActions(event: AstronomicalEvent, context: ObserverContext) {
    val app = LocalAppModel.current
    val palette = LocalPalette.current
    val now = Snapshot.withoutReadObservation { app.clock.now }
    // The catalog arrives asynchronously; reading its state here makes the buttons appear when it has.
    val catalog = app.catalog.catalog
    val options = remember(event, catalog) { aimOptions(event, app, now) }
    var pendingSun by remember { mutableStateOf<EventAimOption?>(null) }
    val chartDate = remember(event) { event.visibility?.bestTime ?: EventsFormat.displayDate(event) }

    Column(Modifier.fillMaxWidth().card(palette), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CardTitle("Actions", icon = Icons.Outlined.GpsFixed)
        for ((index, option) in options.withIndex()) {
            LodestarButton(
                option.label,
                onClick = { if (option.isSun) pendingSun = option else app.aim(option.obj) },
                modifier = Modifier.fillMaxWidth(),
                kind = if (index == 0) LodestarButtonKind.PRIMARY else LodestarButtonKind.SECONDARY,
                icon = Icons.Outlined.GpsFixed,
            )
        }
        if (options.isEmpty() && event.aimTargets.isNotEmpty()) {
            Text("Loading the sky catalog…", color = palette.secondaryText, style = MaterialTheme.typography.bodyMedium)
        }
        LodestarButton(
            "Show in Sky Chart",
            onClick = {
                app.clock.travel(chartDate)
                app.selectedTab = AppTab.SKY
            },
            modifier = Modifier.fillMaxWidth(),
            kind = LodestarButtonKind.SECONDARY,
            icon = Icons.Outlined.BlurCircular,
        )
        Text(
            "The sky chart opens at ${EventsFormat.whenText(chartDate, context.timeZone)}. Use its time bar to return to now.",
            color = palette.tertiaryText,
            style = MaterialTheme.typography.bodySmall,
        )
    }
    pendingSun?.let { option ->
        SolarFilterDialog(
            onConfirm = {
                pendingSun = null
                app.aim(option.obj)
            },
            onDismiss = { pendingSun = null },
        )
    }
}

// Cards

@Composable
private fun EventInfoCard(
    title: String,
    icon: ImageVector,
    rows: List<EventInfoRow>,
    footer: @Composable () -> Unit = {},
) {
    val palette = LocalPalette.current
    Column(Modifier.fillMaxWidth().card(palette), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CardTitle(title, icon = icon)
        for (row in rows) {
            EventInfo(row)
        }
        footer()
    }
}

@Composable
private fun EventInfo(row: EventInfoRow) {
    val palette = LocalPalette.current
    // Below the horizon: shown, but quieter.
    val labelColor = if (row.isDimmed) palette.tertiaryText else palette.secondaryText
    val valueColor = if (row.isDimmed) palette.tertiaryText else palette.primaryText
    // The longer of the two wraps and the shorter keeps its natural width, so neither is squeezed to a sliver.
    val labelWraps = row.label.length > row.value.length
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            row.label,
            color = labelColor,
            style = MaterialTheme.typography.bodyMedium,
            modifier = if (labelWraps) Modifier.weight(1f) else Modifier,
        )
        Text(
            row.value,
            color = valueColor,
            style = MaterialTheme.typography.bodyMedium.monospacedDigits(),
            textAlign = TextAlign.End,
            modifier = if (labelWraps) Modifier else Modifier.weight(1f),
        )
    }
}

@Composable
private fun EventVisibilityNote(event: AstronomicalEvent) {
    val palette = LocalPalette.current
    val visibility = event.visibility ?: return
    EventBadges(event)
    val rule = EventsGuide.windowRule(event.kind)
    val text = buildString {
        if (!visibility.isObservable) append("There is no viewing window at your location. ")
        if (rule != null) append(rule)
    }
    if (text.isNotBlank()) {
        Text(text.trim(), color = palette.tertiaryText, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun EventTextCard(title: String, icon: ImageVector, paragraphs: List<String>) {
    val palette = LocalPalette.current
    Column(Modifier.fillMaxWidth().card(palette), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CardTitle(title, icon = icon)
        for (paragraph in paragraphs) {
            Text(paragraph, color = palette.primaryText, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun EventTipsCard(tips: List<String>) {
    val palette = LocalPalette.current
    Column(Modifier.fillMaxWidth().card(palette), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CardTitle("Photography Tips", icon = Icons.Outlined.CameraAlt)
        for (tip in tips) {
            Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(
                    Icons.Outlined.Lightbulb,
                    contentDescription = null,
                    tint = palette.accent,
                    modifier = Modifier.padding(top = 2.dp).size(16.dp),
                )
                Text(tip, color = palette.primaryText, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
        }
    }
}
