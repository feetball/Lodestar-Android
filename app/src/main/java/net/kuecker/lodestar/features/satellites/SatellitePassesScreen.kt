package net.kuecker.lodestar.features.satellites

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SatelliteAlt
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import net.kuecker.lodestar.features.online.rememberSkyNow
import net.kuecker.lodestar.features.settings.SettingsSegmentedPicker
import net.kuecker.lodestar.features.settings.SettingsTopBar
import net.kuecker.lodestar.services.OnlineFeed
import net.kuecker.lodestar.services.SatellitePassFilter
import net.kuecker.lodestar.services.SatellitePassItem
import net.kuecker.lodestar.services.SatellitePasses
import net.kuecker.lodestar.shared.EmptyStateView
import net.kuecker.lodestar.shared.LocalAppModel
import net.kuecker.lodestar.shared.SettingsMenuOption
import net.kuecker.lodestar.theme.LocalPalette
import net.kuecker.lodestar.theme.screenBackground
import java.time.Instant
import java.time.ZoneId

/**
 * The visible passes of the next three days, grouped by day, with a filter for the ISS alone or all bright satellites,
 * a warning when the orbit data is too old for exact times, and a way into each pass.
 */
@Composable
fun SatellitePassesScreen(onBack: () -> Unit) {
    val app = LocalAppModel.current
    val palette = LocalPalette.current
    val requests = rememberSatelliteRequests()
    val context = app.observerContext
    val zone = context?.timeZone ?: ZoneId.systemDefault()
    val now = rememberSkyNow()
    val board = rememberSatellitePassBoard(context?.observer)
    val library = app.online.satellites
    var filter by remember { mutableStateOf(SatellitePassFilter.ISS) }
    val options = remember { SatellitePassFilter.entries.map { SettingsMenuOption(it.title, it) } }
    Column(Modifier.fillMaxSize().screenBackground(palette)) {
        SettingsTopBar(SatelliteText.PASSES_TITLE, onBack)
        if (!app.online.isEnabled(OnlineFeed.SATELLITE_PASSES)) {
            EmptyStateView(
                title = "Satellite Passes Are Off",
                icon = Icons.Outlined.SatelliteAlt,
                message = "Switch on Satellite passes in Settings ▸ Online Data to see when the ISS and other bright " +
                    "satellites cross your sky.",
            )
            return@Column
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                SettingsSegmentedPicker(
                    title = "Show", selection = filter, options = options, onSelect = { filter = it },
                )
            }
            if (library.hasPassData && library.isPassDataStale(now)) {
                item { Text(SatelliteText.STALE, color = palette.warning, style = MaterialTheme.typography.bodyMedium) }
            }
            val items = board.items
            when {
                !library.hasPassData -> item { Note(SatelliteText.LOADING) }
                items == null -> item { Note(SatelliteText.FINDING) }
                else -> {
                    val passes = SatellitePasses.upcoming(SatellitePasses.filtered(items, filter), now)
                    if (passes.isEmpty()) {
                        item { Note(SatelliteText.NO_PASSES) }
                    } else {
                        for ((day, dayPasses) in passes.groupBy { dayLabel(it, now, zone) }) {
                            item(key = "day-$day") { DayHeader(day) }
                            items(dayPasses, key = { it.id }) { pass -> PassCard(pass, zone, now, requests) }
                        }
                    }
                }
            }
            item { Note(SatelliteText.VISIBLE_ONLY, isFootnote = true) }
            item { Note(SatelliteText.ATTRIBUTION, isFootnote = true) }
        }
    }
}

@Composable
private fun Note(text: String, isFootnote: Boolean = false) {
    val palette = LocalPalette.current
    Text(
        text,
        color = if (isFootnote) palette.tertiaryText else palette.secondaryText,
        style = if (isFootnote) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun DayHeader(text: String) {
    val palette = LocalPalette.current
    Text(
        text.uppercase(),
        color = palette.secondaryText,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(top = 8.dp).semantics { heading() },
    )
}

@Composable
private fun PassCard(item: SatellitePassItem, zone: ZoneId, now: Instant, requests: SatelliteRequests) {
    val palette = LocalPalette.current
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(palette.surface, shape).padding(horizontal = 14.dp),
    ) {
        PassRow(item, zone, now, onClick = { requests.open(SatelliteScreen.Detail(item)) })
    }
}
