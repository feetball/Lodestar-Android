package net.kuecker.lodestar.features.smallbodies

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import net.kuecker.lodestar.features.objects.ObjectsTopBar
import net.kuecker.lodestar.kit.catalog.CelestialObject
import net.kuecker.lodestar.shared.AppFormat
import net.kuecker.lodestar.shared.EmptyStateView
import net.kuecker.lodestar.shared.LocalAppModel
import net.kuecker.lodestar.shared.ObjectIcon
import net.kuecker.lodestar.shared.rememberRetained
import net.kuecker.lodestar.theme.LocalPalette
import net.kuecker.lodestar.theme.screenBackground
import java.time.ZoneId

/** What the list screen is set to; kept for the session. */
internal class SmallBodyListChoice {
    var period by mutableStateOf(SmallBodyListPeriod.NOW)
    var limit by mutableStateOf(SmallBodyMagnitudeLimit.default)
    var kind by mutableStateOf(SmallBodyListKind.COMETS)
}

/** How often the list is worked out again while the screen is open. */
private const val REFRESH_MILLIS = 600_000L

/**
 * The bright comets and asteroids, now or in the next 31 days, for a magnitude limit of 6 (naked eye), 8 (binoculars,
 * wide-field camera) or 10 (camera on a tripod). A row opens the object's detail screen.
 */
@Composable
fun SmallBodyListScreen(onBack: () -> Unit, onOpenObject: (CelestialObject) -> Unit) {
    val app = LocalAppModel.current
    val palette = LocalPalette.current
    val choice = rememberRetained("smallbodies.choice") { SmallBodyListChoice() }
    val catalog = app.catalog.catalog
    val zone = app.observerContext?.timeZone ?: ZoneId.systemDefault()
    var entries by remember { mutableStateOf<List<SmallBodyListEntry>?>(null) }

    LaunchedEffect(Unit) { app.catalog.load() }
    LaunchedEffect(catalog, choice.period, choice.limit, choice.kind) {
        if (catalog == null) return@LaunchedEffect
        while (true) {
            val now = Snapshot.withoutReadObservation { app.clock.now }
            entries = withContext(Dispatchers.Default) {
                SmallBodyBrightLists.entries(catalog, now, choice.period, choice.limit.value, choice.kind)
            }
            delay(REFRESH_MILLIS)
        }
    }

    Column(Modifier.fillMaxSize().screenBackground(palette)) {
        ObjectsTopBar("Comets and Asteroids", onBack = onBack)
        SmallBodyChoiceChips(SmallBodyListPeriod.entries, choice.period, { it.title }) { choice.period = it }
        SmallBodyChoiceChips(SmallBodyListKind.entries, choice.kind, { it.title }) { choice.kind = it }
        SmallBodyChoiceChips(SmallBodyMagnitudeLimit.entries, choice.limit, { it.title }) { choice.limit = it }
        Text(
            choice.limit.caption,
            color = palette.secondaryText,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
        )
        val shown = entries
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                shown == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = palette.accent, trackColor = palette.stroke)
                }
                shown.isEmpty() -> EmptyStateView(
                    title = "Nothing Bright",
                    icon = Icons.Outlined.AutoFixHigh,
                    message = SmallBodyBrightLists.emptyMessage(choice.period, choice.limit.value, choice.kind),
                )
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(shown, key = { it.id }) { entry ->
                        SmallBodyListRow(
                            entry, zone, showsPeakDate = choice.period == SmallBodyListPeriod.THIS_MONTH,
                            modifier = Modifier.background(palette.surface, RoundedCornerShape(12.dp)),
                            onClick = { onOpenObject(entry.obj) },
                        )
                    }
                    item(key = "footnote") {
                        Text(
                            SmallBodyText.FOOTNOTE,
                            color = palette.tertiaryText,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

/** A row of exclusive choices as capsules, scrolling sideways when they do not fit. */
@Composable
private fun <T> SmallBodyChoiceChips(options: List<T>, selected: T, title: (T) -> String, onSelect: (T) -> Unit) {
    val palette = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).selectableGroup().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (option in options) {
            val isSelected = option == selected
            Box(
                Modifier
                    .heightIn(min = 48.dp)
                    .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(option) }),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    title(option),
                    color = if (isSelected) palette.onAccent else palette.secondaryText,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    modifier = Modifier
                        .background(if (isSelected) palette.accent else palette.surface, CircleShape)
                        .border(0.75.dp, if (isSelected) palette.accent else palette.stroke, CircleShape)
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                )
            }
        }
    }
}

/**
 * One bright comet or asteroid: icon, name with designation, kind with the magnitude estimate and the elongation, and
 * with [showsPeakDate] the day it is brightest. Screen readers get one sentence.
 */
@Composable
internal fun SmallBodyListRow(
    entry: SmallBodyListEntry,
    zone: ZoneId,
    showsPeakDate: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val obj = entry.obj
    val name = SmallBodyText.displayName(obj)
    val designation = obj.designations.firstOrNull()?.takeUnless { it == name }
    val summary = remember(entry) { SmallBodyText.summary(obj, entry.info) }
    val peak = if (showsPeakDate) "Brightest ${AppFormat.day(entry.peakDate, zone)}" else null
    val spoken = remember(entry, peak) {
        listOfNotNull(name, designation, SmallBodyText.subtitle(obj, entry.info, isComputed = true, spoken = true), peak)
            .joinToString(", ")
    }
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClickLabel = "Open details", role = Role.Button, onClick = onClick)
            .clearAndSetSemantics { contentDescription = spoken }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ObjectIcon(obj.category)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
                Text(
                    name,
                    color = palette.primaryText,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (designation != null) {
                    Text(
                        designation,
                        color = palette.tertiaryText,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                    )
                }
            }
            Text(summary, color = palette.secondaryText, style = MaterialTheme.typography.bodySmall)
            if (peak != null) Text(peak, color = palette.tertiaryText, style = MaterialTheme.typography.bodySmall)
        }
        Icon(
            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = palette.tertiaryText,
            modifier = Modifier.size(20.dp),
        )
    }
}
