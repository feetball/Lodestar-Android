package net.kuecker.lodestar.features.objects

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Adjust
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.kuecker.lodestar.features.events.EventsFormat
import net.kuecker.lodestar.features.tonight.TonightFormat
import net.kuecker.lodestar.kit.ephemeris.GalileanMoonEvent
import net.kuecker.lodestar.kit.ephemeris.GalileanMoons
import net.kuecker.lodestar.kit.ephemeris.GalileanMoonsState
import net.kuecker.lodestar.kit.ephemeris.GreatRedSpot
import net.kuecker.lodestar.kit.events.AstronomicalEvent
import net.kuecker.lodestar.kit.events.EventCalculator
import net.kuecker.lodestar.kit.events.EventDetails
import net.kuecker.lodestar.kit.events.EventKind
import net.kuecker.lodestar.kit.events.EventOptions
import net.kuecker.lodestar.kit.foundation.JulianDate
import net.kuecker.lodestar.model.ObserverContext
import net.kuecker.lodestar.shared.AppFormat
import net.kuecker.lodestar.shared.CardTitle
import net.kuecker.lodestar.shared.LocalAppModel
import net.kuecker.lodestar.theme.LocalPalette
import net.kuecker.lodestar.theme.card
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/** The numbers behind Jupiter's "Moons Tonight" card. Pure, so it can be tested and run on any thread. */
internal object JupiterMoons {
    /** How far ahead "tonight" looks, in hours: the evening and the night to come, whenever it is asked. */
    const val TONIGHT_HOURS = 24L

    /** How many moon events the card lists. */
    const val EVENT_LIMIT = 6

    /** The narrowest strip, in Jupiter radii either side of the planet. */
    private const val MINIMUM_HALF_RANGE = 12.0

    /** The widest strip: Callisto's orbit is 26.4 radii wide. */
    private const val MAXIMUM_HALF_RANGE = 30.0

    /**
     * The Great Red Spot transits and moon events that can be seen from [context] (Jupiter above 10° with the Sun below
     * −6° while they happen) from [from] for [TONIGHT_HOURS] hours, in time order.
     */
    fun tonight(context: ObserverContext, from: Instant, spot: GreatRedSpot): List<AstronomicalEvent> {
        val options = EventOptions(
            kinds = setOf(EventKind.GREAT_RED_SPOT_TRANSIT, EventKind.JUPITER_MOON_EVENT),
            timeZone = context.timeZone,
            visibleOnly = true,
            jupiterEventDays = TONIGHT_HOURS / 24.0,
            greatRedSpot = spot,
        )
        return EventCalculator.events(from, from.plus(TONIGHT_HOURS, ChronoUnit.HOURS), context.observer, options)
    }

    /** The first Great Red Spot transit among [events] at or after [from]. */
    fun nextTransit(events: List<AstronomicalEvent>, from: Instant): AstronomicalEvent? =
        events.firstOrNull { it.date >= from && it.details is EventDetails.GreatRedSpotTransit }

    /** The first [EVENT_LIMIT] moon events among [events] at or after [from]. */
    fun moonEvents(events: List<AstronomicalEvent>, from: Instant): List<AstronomicalEvent> =
        events.filter { it.date >= from && it.details is EventDetails.JupiterMoonEvent }.take(EVENT_LIMIT)

    /** Half the width of the strip in Jupiter radii: 12, 18, 24 or 30, enough for the farthest moon. */
    fun halfRange(state: GalileanMoonsState): Double {
        val farthest = state.moons.maxOf { abs(it.x) }
        return (ceil(farthest / 6.0) * 6.0).coerceIn(MINIMUM_HALF_RANGE, MAXIMUM_HALF_RANGE)
    }

    /** The diagram in words: where each moon stands, east or west of Jupiter, and in front, behind or in the shadow. */
    fun summary(state: GalileanMoonsState): String {
        val parts = state.moons.map { moon ->
            val radii = EventsFormat.number(abs(moon.x), 1)
            val side = if (moon.x >= 0) "west" else "east"
            val where = when {
                moon.isInTransit -> ", in front of the disc"
                moon.isBehindDisc -> ", behind Jupiter"
                moon.isEclipsed -> ", in Jupiter's shadow"
                else -> ""
            }
            "${moon.moon.displayName} $radii radii $side$where"
        }
        val shadows = state.moons.filter { it.shadow != null }.map { "${it.moon.displayName}'s shadow" }
        val shadowText = if (shadows.isEmpty()) "" else ". " + shadows.joinToString(" and ") + " on the disc"
        return "East is on the left. " + parts.joinToString(". ") + shadowText
    }
}

/**
 * Jupiter's four moons as a strip, east on the left and west on the right as in the sky, refreshed with [date], and what
 * happens tonight: the next Great Red Spot transit and the moon events that can be seen from here.
 */
@Composable
internal fun JupiterMoonsCard(date: Instant?, context: ObserverContext, modifier: Modifier = Modifier) {
    val app = LocalAppModel.current
    val palette = LocalPalette.current
    val spot = app.settings.greatRedSpotOrDefault
    val state = remember(date) { date?.let { GalileanMoons.positions(JulianDate(it)) } }
    val hour = date?.truncatedTo(ChronoUnit.HOURS)
    val events by produceState<List<AstronomicalEvent>?>(null, context, spot, hour) {
        value = hour?.let { withContext(Dispatchers.Default) { JupiterMoons.tonight(context, it, spot) } }
    }
    Column(modifier.fillMaxWidth().card(palette), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CardTitle("Moons Tonight", icon = Icons.Outlined.Adjust)
        if (state != null) JupiterMoonsStrip(state)
        Text(
            "East is on the left, west on the right, as in the sky. The scale is in Jupiter's radii.",
            color = palette.tertiaryText,
            style = MaterialTheme.typography.labelMedium,
        )
        val zone = context.timeZone
        val listed = events
        if (date != null && listed != null) {
            val transit = JupiterMoons.nextTransit(listed, date)
            val moonEvents = JupiterMoons.moonEvents(listed, date)
            if (transit == null && moonEvents.isEmpty()) {
                Text(
                    "No Great Red Spot transit or moon event can be seen from here in the next day: Jupiter is down or the sky is bright.",
                    color = palette.secondaryText,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (transit != null) TransitLine(transit, zone)
            for (event in moonEvents) MoonEventLine(event, zone)
            Text(
                "Moon positions and times are good to about ${GalileanMoons.accuracyMinutes.roundToInt()} minutes. " +
                    "The Great Red Spot's longitude can be set in Settings.",
                color = palette.tertiaryText,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun TransitLine(event: AstronomicalEvent, zone: ZoneId) {
    val palette = LocalPalette.current
    val transit = (event.details as EventDetails.GreatRedSpotTransit).transit
    val text = "Great Red Spot crosses the central meridian at ${AppFormat.time(event.date, zone)}; " +
        "well placed ${TonightFormat.window(transit.wellPlaced, zone)}"
    Text(text, color = palette.primaryText, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun MoonEventLine(event: AstronomicalEvent, zone: ZoneId) {
    val palette = LocalPalette.current
    val moonEvent: GalileanMoonEvent = (event.details as EventDetails.JupiterMoonEvent).event
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            AppFormat.time(event.date, zone),
            color = palette.secondaryText,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "${moonEvent.title} (±${GalileanMoons.accuracyMinutes.roundToInt()} min)",
            color = palette.primaryText,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** The diagram: Jupiter's disc on a line, each moon a disc with its initial, a shadow as a dark spot on the planet. */
@Composable
private fun JupiterMoonsStrip(state: GalileanMoonsState, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val measurer = rememberTextMeasurer()
    val initialStyle = MaterialTheme.typography.labelMedium.copy(color = palette.onAccent, fontWeight = FontWeight.Bold)
    val sideStyle = MaterialTheme.typography.labelSmall.copy(color = palette.secondaryText)
    val summary = remember(state) { JupiterMoons.summary(state) }
    val east = remember(sideStyle) { measurer.measure("E", sideStyle) }
    val west = remember(sideStyle) { measurer.measure("W", sideStyle) }
    val initials = remember(initialStyle) { state.moons.map { measurer.measure(it.moon.initial, initialStyle) } }
    Canvas(
        modifier
            .fillMaxWidth()
            .height(76.dp)
            .semantics {
                contentDescription = "Jupiter's moons"
                stateDescription = summary
            },
    ) {
        val margin = 14.dp.toPx()
        val moonRadius = 9.dp.toPx()
        val centerX = size.width / 2f
        val centerY = size.height / 2f
        val half = JupiterMoons.halfRange(state)
        val scale = ((size.width / 2f) - margin - moonRadius) / half.toFloat()
        val discRadius = max(scale, 5.dp.toPx())
        drawLine(palette.stroke, Offset(margin, centerY), Offset(size.width - margin, centerY), 1f)
        // Tick marks every six radii.
        var tick = 6.0
        while (tick <= half) {
            for (side in listOf(-1f, 1f)) {
                val x = centerX + side * tick.toFloat() * scale
                drawLine(palette.stroke, Offset(x, centerY - 3.dp.toPx()), Offset(x, centerY + 3.dp.toPx()), 1f)
            }
            tick += 6.0
        }
        drawText(east, topLeft = Offset(0f, size.height - east.size.height.toFloat()))
        drawText(west, topLeft = Offset(size.width - west.size.width.toFloat(), size.height - west.size.height.toFloat()))
        drawCircle(palette.surfaceRaised, discRadius, Offset(centerX, centerY))
        drawCircle(palette.accent, discRadius, Offset(centerX, centerY), style = Stroke(1.5.dp.toPx()))
        // Moons behind the disc are drawn first, then the planet's shadow spots, then the moons in front.
        state.moons.forEachIndexed { index, moon ->
            val x = centerX + moon.x.toFloat() * scale
            val y = centerY - moon.y.toFloat() * scale
            when {
                moon.isBehindDisc || moon.isEclipsed ->
                    drawCircle(palette.tertiaryText, moonRadius, Offset(x, y), style = Stroke(1.dp.toPx()))
                else -> drawCircle(palette.accent, moonRadius, Offset(x, y))
            }
            if (!(moon.isBehindDisc || moon.isEclipsed)) {
                val label = initials[index]
                drawText(label, topLeft = Offset(x - label.size.width / 2f, y - label.size.height / 2f))
            }
        }
        for (moon in state.moons) {
            val shadow = moon.shadow ?: continue
            val x = centerX + shadow.x.toFloat() * discRadius
            val y = centerY - shadow.y.toFloat() * discRadius
            drawCircle(palette.primaryText, 3.dp.toPx(), Offset(x, y))
        }
    }
}
