package net.kuecker.lodestar.features.satellites

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SatelliteAlt
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.kuecker.lodestar.app.AppTab
import net.kuecker.lodestar.features.online.rememberSkyNow
import net.kuecker.lodestar.features.settings.SettingsTopBar
import net.kuecker.lodestar.kit.satellites.SatelliteLook
import net.kuecker.lodestar.services.SatellitePassItem
import net.kuecker.lodestar.shared.AppFormat
import net.kuecker.lodestar.shared.CardTitle
import net.kuecker.lodestar.shared.InfoRow
import net.kuecker.lodestar.shared.LocalAppModel
import net.kuecker.lodestar.shared.LodestarButton
import net.kuecker.lodestar.shared.LodestarButtonKind
import net.kuecker.lodestar.shared.rememberRetained
import net.kuecker.lodestar.theme.LocalPalette
import net.kuecker.lodestar.theme.card
import net.kuecker.lodestar.theme.screenBackground
import java.time.Duration
import java.time.ZoneId

/**
 * One pass: its sky path, the rise, peak and set with their direction and height, the visible stretch and the brightness,
 * and the two ways to Aim: follow the satellite live, or wait at the point where the pass peaks.
 */
@Composable
fun SatellitePassDetailScreen(item: SatellitePassItem, onBack: () -> Unit) {
    val app = LocalAppModel.current
    val palette = LocalPalette.current
    val context = app.observerContext
    val observer = context?.observer
    val zone = context?.timeZone ?: ZoneId.systemDefault()
    val now = rememberSkyNow(10_000)
    val pass = item.pass
    // The path: one point every five seconds from rise to set, worked out off the main thread.
    val looks by produceState<List<SatelliteLook>?>(null, item, observer) {
        value = if (observer == null) {
            null
        } else {
            withContext(Dispatchers.Default) { item.satellite.track(pass.rise.date, pass.set.date, 5.0, observer) }
        }
    }
    val session = rememberRetained(SatelliteAimSession.KEY) { SatelliteAimSession() }
    val peakPassed = now.isAfter(pass.culmination.date.plus(Duration.ofSeconds(PEAK_GRACE_SECONDS)))
    Column(Modifier.fillMaxSize().screenBackground(palette)) {
        SettingsTopBar(item.name, onBack)
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "${AppFormat.day(item.start, zone)} · ${SatelliteText.span(item, zone)}",
                color = palette.secondaryText,
                style = MaterialTheme.typography.bodyLarge,
            )
            val track = looks
            if (track != null && track.size >= 2) {
                val peak = observer?.let { item.satellite.look(pass.culmination.date, it) }
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    SatellitePathView(track, peak, SatelliteText.spokenPath(item, zone))
                    Text(
                        "Seen from below: north at the top, east on the left. The solid line is where you can see it; " +
                            "the rings mark 30° and 60° above the horizon.",
                        color = palette.tertiaryText,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            Column(Modifier.fillMaxWidth().card(palette), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CardTitle("Pass", icon = Icons.Outlined.Schedule)
                InfoRow("Rise", SatelliteText.point(pass.rise, zone))
                InfoRow("Peak", SatelliteText.point(pass.culmination, zone))
                InfoRow("Set", SatelliteText.point(pass.set, zone))
                pass.visibleSpan?.let { span ->
                    InfoRow(
                        "Visible",
                        "${SatelliteText.timeWithSeconds(span.start, zone)} – ${SatelliteText.timeWithSeconds(span.end, zone)}" +
                            " (${SatelliteText.duration(span.duration)})",
                    )
                }
                InfoRow("Magnitude", SatelliteText.magnitude(item))
                if (!item.isFresh(now)) {
                    Text(SatelliteText.STALE, color = palette.warning, style = MaterialTheme.typography.bodySmall)
                }
            }
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                CardTitle("Aim", icon = Icons.Outlined.SatelliteAlt)
                LodestarButton(
                    SatelliteText.FOLLOW,
                    onClick = {
                        if (observer != null) {
                            session.start(SatelliteFollowSource(item.satellite, item.name, observer, item), app.target?.id)
                            app.selectedTab = AppTab.AIM
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = observer != null,
                )
                LodestarButton(
                    SatelliteText.WAIT,
                    onClick = {
                        if (observer != null) {
                            session.start(SatellitePeakSource.create(item, observer), app.target?.id)
                            app.selectedTab = AppTab.AIM
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    kind = LodestarButtonKind.SECONDARY,
                    enabled = observer != null && !peakPassed,
                )
                Text(SatelliteText.ACTIONS_NOTE, color = palette.tertiaryText, style = MaterialTheme.typography.bodySmall)
            }
            Text(SatelliteText.ATTRIBUTION, color = palette.tertiaryText, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** The peak counts as passed this long after it, so a countdown can still be started at the moment itself. */
private const val PEAK_GRACE_SECONDS = 30L
