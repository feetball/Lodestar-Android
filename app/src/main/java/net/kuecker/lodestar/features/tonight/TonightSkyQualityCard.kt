package net.kuecker.lodestar.features.tonight

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import net.kuecker.lodestar.app.AppModel
import net.kuecker.lodestar.features.skyquality.SkyReading
import net.kuecker.lodestar.features.skyquality.rememberSkyReading
import net.kuecker.lodestar.kit.foundation.DateInterval
import net.kuecker.lodestar.kit.foundation.GeographicLocation
import net.kuecker.lodestar.kit.planning.NightSummary
import net.kuecker.lodestar.kit.skyquality.BortleEstimate
import net.kuecker.lodestar.kit.skyquality.ImagingRating
import net.kuecker.lodestar.kit.skyquality.ImagingScore
import net.kuecker.lodestar.kit.skyquality.ImagingScoreComponent
import net.kuecker.lodestar.kit.skyquality.ImagingScoreComponentKind
import net.kuecker.lodestar.kit.skyquality.SkyQualityScore
import net.kuecker.lodestar.kit.skyquality.darkestHint
import net.kuecker.lodestar.services.ForecastNight
import net.kuecker.lodestar.services.OnlineCoordinates
import net.kuecker.lodestar.services.OnlineFeed
import net.kuecker.lodestar.shared.AppFormat
import net.kuecker.lodestar.shared.CardTitle
import net.kuecker.lodestar.shared.InfoRow
import net.kuecker.lodestar.shared.LocalAppModel
import net.kuecker.lodestar.theme.LocalPalette
import net.kuecker.lodestar.theme.card
import java.text.NumberFormat
import java.time.Duration
import java.time.Instant

/** A forecast older than this does not go into the imaging score. */
private val FORECAST_FRESH_FOR: Duration = Duration.ofHours(12)

/**
 * The Sky quality card: the Bortle estimate of the site, the darkest direction and the imaging score of the night with
 * its parts. The cloud part appears only while the cloud forecast is on, matches the place, is under 12 hours old and
 * reaches the night. Nothing until the estimate is worked out, and nothing when the map is unavailable.
 */
@Composable
internal fun TonightSkyQualityCard(summary: NightSummary, night: DateInterval) {
    val app = LocalAppModel.current
    val place = summary.observer.location
    val reading by rememberSkyReading(place)
    val sky = reading ?: return
    val clouds = nightCloudFraction(app, place, night)
    val score = SkyQualityScore.imaging(summary, sky.estimate, clouds)
    SkyQualityCardContent(summary, sky, score)
}

@Composable
private fun SkyQualityCardContent(summary: NightSummary, sky: SkyReading, score: ImagingScore) {
    val palette = LocalPalette.current
    val estimate = sky.estimate
    val hint = sky.directions.darkestHint
    Column(Modifier.fillMaxWidth().card(palette), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CardTitle("Sky quality", icon = Icons.Outlined.DarkMode)
        InfoRow("Sky", estimate.summary)
        InfoRow("Darkest sky", hint?.let { "toward ${it.direction.abbreviation}" } ?: "No clear difference within 30 km")
        Text(
            "Imaging score ${oneDecimal(score.total)} / 10 · ${ratingText(score.rating)}",
            color = palette.primaryText,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics(mergeDescendants = true) {},
        )
        for (part in score.components) ScoreLine(part, detail(part, summary, estimate, score))
        TonightNote(
            if (score.includesClouds) {
                "Out of 10: dark hours, the Moon, the sky's brightness and the cloud forecast."
            } else {
                "Out of 10: dark hours, the Moon and the sky's brightness. The cloud forecast is not part of it " +
                    "(it is off or not up to date)."
            },
            style = TonightNoteStyle.FOOTNOTE,
        )
        TonightNote(BortleEstimate.caveat + ".", style = TonightNoteStyle.FOOTNOTE)
    }
}

/** One part of the score: its name and points, and a line saying what it rests on. */
@Composable
private fun ScoreLine(part: ImagingScoreComponent, detail: String) {
    val palette = LocalPalette.current
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Text(
                partName(part.kind),
                color = palette.secondaryText,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            )
            Spacer(Modifier.width(12.dp).weight(1f))
            Text(
                "${oneDecimal(part.points)} / ${oneDecimal(part.weight)}",
                color = palette.primaryText,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.End,
            )
        }
        Text(detail, color = palette.tertiaryText, style = MaterialTheme.typography.bodySmall)
    }
}

private fun partName(kind: ImagingScoreComponentKind): String = when (kind) {
    ImagingScoreComponentKind.DARKNESS_HOURS -> "Dark hours"
    ImagingScoreComponentKind.MOON -> "Moon"
    ImagingScoreComponentKind.SKY_BRIGHTNESS -> "Sky brightness"
    ImagingScoreComponentKind.CLOUDS -> "Clouds"
}

private fun ratingText(rating: ImagingRating): String = when (rating) {
    ImagingRating.POOR -> "Poor"
    ImagingRating.FAIR -> "Fair"
    ImagingRating.GOOD -> "Good"
    ImagingRating.EXCELLENT -> "Excellent"
}

/** What one part of the score rests on, in a few words. */
private fun detail(part: ImagingScoreComponent, summary: NightSummary, estimate: BortleEstimate, score: ImagingScore): String =
    when (part.kind) {
        ImagingScoreComponentKind.DARKNESS_HOURS ->
            if (summary.hasAstronomicalDarkness) {
                "${TonightFormat.duration(summary.astronomicalDarkHours)} of astronomical darkness"
            } else {
                "No astronomical darkness"
            }
        ImagingScoreComponentKind.MOON -> when {
            !summary.hasAstronomicalDarkness -> "No astronomical darkness"
            summary.astronomicalDarkHours - summary.moonFreeDarkHours < 1.0 / 60 -> "Not up in the dark"
            else -> "Up for ${TonightFormat.duration(summary.astronomicalDarkHours - summary.moonFreeDarkHours)} of the dark, " +
                "${AppFormat.percent(summary.moon.illuminatedFraction)} lit"
        }
        ImagingScoreComponentKind.SKY_BRIGHTNESS -> "Bortle ~${estimate.bortleClass}, ${estimate.label.lowercase()}"
        ImagingScoreComponentKind.CLOUDS ->
            "About ${AppFormat.percent(1.0 - part.quality)} cloud forecast for the night"
    }

private fun oneDecimal(value: Double): String =
    NumberFormat.getNumberInstance().apply {
        minimumFractionDigits = 1
        maximumFractionDigits = 1
    }.format(value)

/**
 * The cloud cover of [night] at [place] from the optional MET Norway forecast, 0 to 1, or `null` when the feed is off, the
 * forecast is for another place, older than 12 hours or does not reach the night.
 */
private fun nightCloudFraction(app: AppModel, place: GeographicLocation, night: DateInterval): Double? {
    val online = app.online
    if (!online.isEnabled(OnlineFeed.CLOUDS)) return null
    val forecast = online.forecast?.takeIf { it.key == OnlineCoordinates.key(place.latitude, place.longitude) } ?: return null
    // The clock ticks every second; the card must not recompose with it.
    val now: Instant = Snapshot.withoutReadObservation { app.clock.now }
    if (Duration.between(forecast.issuedAt, now) > FORECAST_FRESH_FOR) return null
    val slots = ForecastNight.slots(forecast, night)
    if (slots.isEmpty()) return null
    return ForecastNight.meanTotal(slots) / 100.0
}
