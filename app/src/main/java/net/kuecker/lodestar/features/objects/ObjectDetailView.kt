package net.kuecker.lodestar.features.objects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.GpsFixed
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import net.kuecker.lodestar.kit.catalog.CelestialObject
import net.kuecker.lodestar.kit.catalog.ObjectCategory
import net.kuecker.lodestar.model.AppSettings
import net.kuecker.lodestar.shared.AppFormat
import net.kuecker.lodestar.shared.ChipTone
import net.kuecker.lodestar.shared.LocalAppModel
import net.kuecker.lodestar.shared.LodestarButton
import net.kuecker.lodestar.shared.LodestarButtonKind
import net.kuecker.lodestar.shared.NeedsLocationView
import net.kuecker.lodestar.shared.ObjectIcon
import net.kuecker.lodestar.shared.StatusChip
import net.kuecker.lodestar.shared.icon
import net.kuecker.lodestar.theme.LocalPalette
import net.kuecker.lodestar.theme.card
import net.kuecker.lodestar.theme.screenBackground
import java.time.ZoneId

/**
 * Everything about one object: what it is, where it is now, how tonight goes, and how to photograph it.
 *
 * Push it on any navigation stack: it draws its own top bar whose back button calls [onBack], and its "Aim" button
 * points the Aim screen at the object.
 */
@Composable
fun ObjectDetailScreen(obj: CelestialObject, onBack: () -> Unit) {
    // A different object starts from scratch instead of showing the previous one's data.
    key(obj.id) { ObjectDetailContent(obj, onBack) }
}

/** The detail screen's scrolling content and the effect that keeps its data fresh. */
@Composable
private fun ObjectDetailContent(obj: CelestialObject, onBack: () -> Unit) {
    val app = LocalAppModel.current
    val palette = LocalPalette.current
    val context = app.observerContext
    val isForeground = app.isForeground
    var snapshot by remember { mutableStateOf<ObjectDetailSnapshot?>(null) }

    // Computes the snapshot right away and then every 30 s until the screen leaves or the observer changes.
    LaunchedEffect(context, isForeground) {
        if (context == null) {
            snapshot = null
            return@LaunchedEffect
        }
        if (!isForeground) return@LaunchedEffect
        var night: ObjectDetailNight? = null
        while (isActive) {
            val input = ObjectDetailInput(obj, context, app.clock.now, night)
            val computed = withContext(Dispatchers.Default) { ObjectDetailCalculator.snapshot(input) }
            night = computed.night
            snapshot = computed
            delay(REFRESH_INTERVAL_MS)
        }
    }

    Column(Modifier.fillMaxSize().screenBackground(palette)) {
        ObjectsTopBar(obj.name, onBack = onBack)
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ObjectDetailHeader(obj)
            if (context != null) {
                ObjectDetailNowCard(snapshot?.current, app.settings.coordinateStyle)
                ObjectDetailTonightCard(obj, snapshot?.night, snapshot?.current?.date, context.timeZone)
            } else {
                NeedsLocationView(Modifier.fillMaxWidth().card(palette).heightIn(min = 300.dp))
            }
            ObjectDetailPhotographyCard(obj, snapshot, app.lensSetup, context?.timeZone ?: ZoneId.systemDefault())
            if (hasAboutContent(obj)) ObjectDetailAboutCard(obj)
        }
    }
}

/** How often the object's position and the night's data are refreshed while the screen is open. */
private const val REFRESH_INTERVAL_MS = 30_000L

/** The top card of the detail screen: names, type chips and the Aim and favorite buttons. */
@Composable
internal fun ObjectDetailHeader(obj: CelestialObject, modifier: Modifier = Modifier) {
    val app = LocalAppModel.current
    val palette = LocalPalette.current
    val favoriteIDs = app.settings.favoriteObjectIDs
    val isFavorite = obj.id in favoriteIDs
    // Custom targets are not in the catalog, so they could not be found again as favorites.
    val canBeFavorite = !obj.id.startsWith("custom.")
    val designations = remember(obj) { ObjectsFormat.designationsLine(obj) }

    Column(modifier.fillMaxWidth().card(palette), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ObjectIcon(obj.category, size = 26.dp)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    obj.name,
                    color = palette.primaryText,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                )
                if (designations != null) {
                    Text(designations, color = palette.secondaryText, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusChip(obj.category.icon, obj.category.displayName)
            obj.constellationName?.let { StatusChip(Icons.Outlined.Map, it) }
            obj.magnitude?.let { StatusChip(Icons.Outlined.AutoAwesome, AppFormat.magnitude(it)) }
            if (obj.category == ObjectCategory.SUN) {
                StatusChip(Icons.Outlined.Warning, "Solar filter required", tone = ChipTone.WARNING)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LodestarButton(onClick = { app.aim(obj) }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Outlined.GpsFixed, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(
                    "Aim at ${obj.name}",
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            if (canBeFavorite) {
                LodestarButton(
                    onClick = { app.settingsStore.toggleFavorite(obj.id) },
                    kind = LodestarButtonKind.SECONDARY,
                    enabled = isFavorite || favoriteIDs.size < AppSettings.MAXIMUM_FAVORITES,
                ) {
                    Icon(
                        if (isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                        contentDescription = if (isFavorite) "Remove from favorites" else "Add to favorites",
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
    }
}
