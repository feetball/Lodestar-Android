package net.kuecker.lodestar.features.tonight

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import net.kuecker.lodestar.app.AppModel
import net.kuecker.lodestar.kit.catalog.Catalog
import net.kuecker.lodestar.kit.catalog.CelestialObject
import net.kuecker.lodestar.kit.foundation.timeIntervalSince
import net.kuecker.lodestar.model.ObserverContext
import net.kuecker.lodestar.shared.AppFormat
import net.kuecker.lodestar.shared.LocalAppModel
import net.kuecker.lodestar.shared.rememberRetained
import net.kuecker.lodestar.theme.LocalPalette
import java.time.Instant
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.min

/** The most nights from tonight the selector reaches in either direction. */
private const val REACH_IN_DAYS = 366L

/**
 * The Tonight tab's content once an observing place is known: the night selector and the cards for the chosen night.
 *
 * The chosen night, the last report and the scroll position are kept for the session, so they survive tab switches and
 * a visit to a detail screen.
 */
@Composable
internal fun TonightContent(
    context: ObserverContext,
    onOpenObject: (CelestialObject) -> Unit,
    onOpenPolarAlignment: () -> Unit,
) {
    val app = LocalAppModel.current
    val zone = context.timeZone
    var choice by rememberRetained("tonight.choice") { mutableStateOf<TonightNightChoice>(TonightNightChoice.Tonight) }
    var latestReport by rememberRetained("tonight.report") { mutableStateOf<TonightReport?>(null) }
    val scroll = rememberRetained("tonight.scroll") { ScrollState(0) }
    var isShowingDateSheet by remember { mutableStateOf(false) }
    val catalog = app.catalog.catalog

    // The clock ticks every second; the screen must not recompose with it.
    val now = Snapshot.withoutReadObservation { app.clock.now }

    // The retained report may be for another place, or for a "tonight" that has ended while the screen was away.
    val report = latestReport?.takeIf { it.fits(context, choice, now) }

    // Local noon of the date of the night under way, before the first report has arrived too.
    val tonightStart = report?.tonightStart ?: TonightCalculator.localNoon(now, zone)

    // Local noon of the night the selector stands on: the one asked for, else the one shown.
    val selectedNight: Instant? = when (val current = choice) {
        TonightNightChoice.Tonight -> report?.nightStart
        is TonightNightChoice.Date -> current.date
    }

    // The nights the selector can reach.
    val reach = tonightStart.atZone(zone).minusDays(REACH_IN_DAYS).toInstant()..
        tonightStart.atZone(zone).plusDays(REACH_IN_DAYS).toInstant()

    fun movedNight(days: Long): Instant? = selectedNight?.atZone(zone)?.plusDays(days)?.toInstant()

    fun canMove(days: Long): Boolean = movedNight(days)?.let { it in reach } ?: false

    // Takes the chosen night; tonight's own date means "tonight", which moves on by itself.
    fun apply(newChoice: TonightNightChoice) {
        choice = if (newChoice is TonightNightChoice.Date && isSameDay(newChoice.date, tonightStart, zone)) {
            TonightNightChoice.Tonight
        } else {
            newChoice
        }
    }

    fun move(days: Long) {
        val night = movedNight(days) ?: return
        if (night !in reach) return
        apply(TonightNightChoice.Date(night))
    }

    // Works the night out off the main thread; another observer, another night or a catalog that has just arrived
    // starts a new computation.
    LaunchedEffect(context, choice, catalog) {
        keepReportFresh(app, TonightNightKey(context, choice), catalog) { latestReport = it }
    }

    Column(Modifier.fillMaxSize()) {
        TonightTopBar {
            if (choice != TonightNightChoice.Tonight) {
                TonightTextAction(
                    "Tonight",
                    onClick = { choice = TonightNightChoice.Tonight },
                    onClickLabel = "Go back to the night that is under way",
                )
            }
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TonightDateBar(
                title = report?.let { AppFormat.day(it.nightStart, it.timeZone) } ?: "Tonight",
                subtitle = report?.let { TonightFormat.nightLabel(it.nightsFromTonight) } ?: "Working it out",
                canGoBack = canMove(-1),
                canGoForward = canMove(1),
                goBack = { move(-1) },
                goForward = { move(1) },
                pickDate = { isShowingDateSheet = true },
            )
            PlaceLine("${context.name} · times in ${TonightFormat.zoneName(zone, report?.nightStart ?: now)}")
            if (report != null) {
                TonightReportCards(report, onOpenObject)
            } else {
                TonightPendingCard()
            }
            TonightPolarCard(context.observer, zone, onOpenPolarAlignment)
        }
    }

    if (isShowingDateSheet) {
        TonightDateSheet(
            timeZone = zone,
            selected = selectedNight ?: tonightStart,
            tonight = tonightStart,
            range = reach,
            onSelect = { apply(it) },
            onDismiss = { isShowingDateSheet = false },
        )
    }
}

/** Whether this report answers what the screen asks for: the same place and, for "tonight", a night not yet over. */
private fun TonightReport.fits(context: ObserverContext, choice: TonightNightChoice, now: Instant): Boolean =
    summary.observer == context.observer && timeZone == context.timeZone &&
        (choice != TonightNightChoice.Tonight || now < expiry)

/** Where the times are for: the site's name and its time zone. */
@Composable
private fun PlaceLine(text: String) {
    val palette = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Place, contentDescription = null, tint = palette.secondaryText, modifier = Modifier.size(14.dp))
        Text(text, color = palette.secondaryText, style = MaterialTheme.typography.bodySmall)
    }
}

private fun isSameDay(first: Instant, second: Instant, zone: ZoneId): Boolean =
    first.atZone(zone).toLocalDate() == second.atZone(zone).toLocalDate()

/**
 * Works the night out on a background thread and hands each result to [publish] on the calling (main) thread. For
 * "tonight" it keeps going: once the night is over the next one becomes tonight.
 */
private suspend fun keepReportFresh(app: AppModel, key: TonightNightKey, catalog: Catalog?, publish: (TonightReport) -> Unit) {
    while (true) {
        val input = TonightInput(key, app.clock.now, catalog)
        val computed = withContext(Dispatchers.Default) { TonightCalculator.report(input) }
        currentCoroutineContext().ensureActive()
        publish(computed)
        if (key.choice != TonightNightChoice.Tonight) return
        waitUntil(app, computed.expiry)
    }
}

/**
 * Returns once [date] has passed, waking at least every quarter of an hour in case the clock changed. It always waits
 * at least a second, so it can never spin.
 */
private suspend fun waitUntil(app: AppModel, date: Instant) {
    do {
        val remaining = date.timeIntervalSince(app.clock.now)
        delay((min(max(remaining, 1.0), 900.0) * 1000).toLong())
    } while (app.clock.now < date)
}
