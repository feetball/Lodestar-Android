package net.kuecker.lodestar.features.objects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.kuecker.lodestar.features.sessionlog.InterchangeFile
import net.kuecker.lodestar.features.sessionlog.InterchangeFileKind
import net.kuecker.lodestar.kit.catalog.Catalog
import net.kuecker.lodestar.kit.catalog.CelestialObject
import net.kuecker.lodestar.kit.foundation.EquatorialCoordinate
import net.kuecker.lodestar.kit.interchange.ObservingListCodec
import net.kuecker.lodestar.kit.interchange.ObservingListEntry
import net.kuecker.lodestar.kit.interchange.ObservingListFormat
import net.kuecker.lodestar.kit.interchange.ObservingListImport
import net.kuecker.lodestar.kit.interchange.ObservingListMatch
import net.kuecker.lodestar.kit.interchange.ObservingListMatcher
import net.kuecker.lodestar.model.AppSettings
import net.kuecker.lodestar.shared.InfoRow
import net.kuecker.lodestar.shared.LocalAppModel
import net.kuecker.lodestar.shared.LocalSheetDismiss
import net.kuecker.lodestar.shared.LodestarButton
import net.kuecker.lodestar.shared.LodestarButtonKind
import net.kuecker.lodestar.shared.LodestarSheet
import net.kuecker.lodestar.shared.SettingsDivider
import net.kuecker.lodestar.shared.SettingsRow
import net.kuecker.lodestar.shared.SettingsSection
import net.kuecker.lodestar.theme.LocalPalette

/** Writing the favorites as lists. */
internal object ObjectsListFile {
    /** The favorites as list entries, in the order of the favorites. */
    fun entries(favoriteIDs: List<String>, catalog: Catalog): List<ObservingListEntry> =
        favoriteIDs.mapNotNull { catalog.objectWithID(it) }.map { obj ->
            val position = obj.j2000
            ObservingListEntry(
                name = obj.name,
                designation = obj.designations.firstOrNull(),
                rightAscension = position?.rightAscension,
                declination = position?.declination,
                typeName = obj.category.displayName,
            )
        }

    /** The favorites as a file in [format] (a table, or a SkySafari list), ready to be saved. */
    fun file(entries: List<ObservingListEntry>, format: ObservingListFormat): InterchangeFile = when (format) {
        ObservingListFormat.SKY_SAFARI ->
            InterchangeFile(ObservingListCodec.encodeSkySafari(entries), InterchangeFileKind.TEXT, "Lodestar Favorites.skylist")
        ObservingListFormat.GENERIC_CSV, ObservingListFormat.TELESCOPIUS_CSV ->
            InterchangeFile(ObservingListCodec.encodeCSV(entries), InterchangeFileKind.CSV, "Lodestar Favorites.csv")
    }
}

/** The rows of an imported list sorted by what can be done with them. */
internal class ObjectsImportPlan(matches: List<ObservingListMatch>) {
    /** A row that is in the catalog. */
    class Found(val id: Int, val label: String, val obj: CelestialObject)

    /**
     * A row that is not in the catalog.
     *
     * @property position The J2000 place of the row, when it has one: the row can then be aimed at as a custom target.
     */
    class Missing(val id: Int, val label: String, val position: EquatorialCoordinate?)

    val found = ArrayList<Found>()
    val missing = ArrayList<Missing>()

    init {
        val seen = HashSet<String>()
        for ((index, match) in matches.withIndex()) {
            val label = match.target.displayName ?: "Line ${match.target.line}"
            val obj = match.obj
            if (obj != null) {
                if (seen.add(obj.id)) found.add(Found(index, label, obj))
            } else {
                val rightAscension = match.target.rightAscension
                val declination = match.target.declination
                val position = if (rightAscension != null && declination != null) EquatorialCoordinate(rightAscension, declination) else null
                missing.add(Missing(index, label, position))
            }
        }
    }
}

/** The most rows of one kind the sheet lists. */
private const val LISTED_ROWS = 100

/** Shows what an imported list holds, which of it Lodestar knows, and adds the known objects to the favorites. */
@Composable
internal fun ObjectsImportSheet(imported: ObservingListImport, onDismiss: () -> Unit) {
    LodestarSheet(onDismiss) { ObjectsImportContent(imported, LocalSheetDismiss.current) }
}

@Composable
private fun ObjectsImportContent(imported: ObservingListImport, onDismiss: () -> Unit) {
    val app = LocalAppModel.current
    val palette = LocalPalette.current
    val catalog = app.catalog.catalog
    var plan by remember { mutableStateOf<ObjectsImportPlan?>(null) }
    LaunchedEffect(imported, catalog) {
        val targets = imported.targets
        plan = withContext(Dispatchers.Default) {
            ObjectsImportPlan(if (catalog == null) targets.map { ObservingListMatch(it, null) } else ObservingListMatcher.match(targets, catalog))
        }
    }
    val favorites = app.settings.favoriteObjectIDs.toHashSet()
    val ready = plan

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 12.dp)) {
        Text(
            "Import List",
            color = palette.primaryText,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        )
        if (ready == null) {
            Text("Matching the list…", color = palette.secondaryText, modifier = Modifier.padding(16.dp))
            return@Column
        }
        val fresh = ready.found.filter { it.obj.id !in favorites }
        val room = maxOf(AppSettings.MAXIMUM_FAVORITES - favorites.size, 0)
        val toAdd = minOf(fresh.size, room)
        SettingsSection(title = "List") {
            SettingsRow { InfoRow("Format", imported.format.displayName) }
            SettingsDivider()
            SettingsRow { InfoRow("Rows", imported.targets.size.toString()) }
            SettingsDivider()
            SettingsRow { InfoRow("In the catalog", ready.found.size.toString()) }
            SettingsDivider()
            SettingsRow { InfoRow("Not in the catalog", ready.missing.size.toString()) }
            if (imported.isTruncated) {
                SettingsDivider()
                SettingsRow { Text("Only the first ${ObservingListCodec.maximumTargets} rows were read.", color = palette.warning, style = MaterialTheme.typography.bodySmall) }
            }
            if (fresh.size > room) {
                SettingsDivider()
                SettingsRow { Text("Favorites hold ${AppSettings.MAXIMUM_FAVORITES} objects, so only $toAdd can be added.", color = palette.warning, style = MaterialTheme.typography.bodySmall) }
            }
        }
        LodestarButton(
            text = if (toAdd == 0) "Nothing to Add" else "Add $toAdd to Favorites",
            onClick = {
                app.settingsStore.update { s ->
                    val known = s.favoriteObjectIDs.toHashSet()
                    val ids = ArrayList(s.favoriteObjectIDs)
                    for (item in ready.found) {
                        if (ids.size >= AppSettings.MAXIMUM_FAVORITES) break
                        if (known.add(item.obj.id)) ids.add(item.obj.id)
                    }
                    s.copy(favoriteObjectIDs = ids)
                }
                onDismiss()
            },
            enabled = toAdd > 0,
            icon = Icons.Outlined.Star,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        if (ready.found.isNotEmpty()) {
            SettingsSection(title = "In the Catalog") {
                ready.found.take(LISTED_ROWS).forEachIndexed { index, item ->
                    if (index > 0) SettingsDivider()
                    SettingsRow {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(item.label, color = palette.primaryText, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            Text(
                                if (item.obj.id in favorites) "Already a favorite" else item.obj.name,
                                color = palette.secondaryText,
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.End,
                            )
                        }
                    }
                }
                if (ready.found.size > LISTED_ROWS) {
                    SettingsDivider()
                    SettingsRow { Text("and ${ready.found.size - LISTED_ROWS} more", color = palette.secondaryText, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        if (ready.missing.isNotEmpty()) {
            SettingsSection(
                title = "Not in the Catalog",
                footer = "A row with coordinates can be aimed at as a custom target. Custom targets are not kept in the favorites.",
            ) {
                ready.missing.take(LISTED_ROWS).forEachIndexed { index, item ->
                    if (index > 0) SettingsDivider()
                    SettingsRow {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(item.label, color = palette.primaryText, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    if (item.position == null) "Skipped: no coordinates" else "Has coordinates",
                                    color = palette.secondaryText,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            val position = item.position
                            if (position != null) {
                                LodestarButton(
                                    text = "Aim",
                                    onClick = {
                                        onDismiss()
                                        app.aim(CelestialObject.custom(item.label, position))
                                    },
                                    kind = LodestarButtonKind.SECONDARY,
                                )
                            }
                        }
                    }
                }
                if (ready.missing.size > LISTED_ROWS) {
                    SettingsDivider()
                    SettingsRow { Text("and ${ready.missing.size - LISTED_ROWS} more", color = palette.secondaryText, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        if (imported.errors.isNotEmpty()) {
            SettingsSection(title = "Problems") {
                imported.errors.take(LISTED_ROWS).forEachIndexed { index, error ->
                    if (index > 0) SettingsDivider()
                    SettingsRow { Text("Line ${error.line}: ${error.reason.message}", color = palette.secondaryText, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}
