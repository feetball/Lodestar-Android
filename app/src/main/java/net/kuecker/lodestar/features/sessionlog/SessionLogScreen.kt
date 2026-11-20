package net.kuecker.lodestar.features.sessionlog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import net.kuecker.lodestar.features.settings.SettingsNavigationRow
import net.kuecker.lodestar.features.settings.SettingsRoute
import net.kuecker.lodestar.features.settings.SettingsTopBar
import net.kuecker.lodestar.kit.formatting.AngleFormatter
import net.kuecker.lodestar.kit.formatting.formatDuration
import net.kuecker.lodestar.kit.interchange.SessionLogCodec
import net.kuecker.lodestar.kit.interchange.SessionLogEntry
import net.kuecker.lodestar.shared.AppFormat
import net.kuecker.lodestar.shared.EmptyStateView
import net.kuecker.lodestar.shared.InfoRow
import net.kuecker.lodestar.shared.LocalAppModel
import net.kuecker.lodestar.shared.LodestarButton
import net.kuecker.lodestar.shared.LodestarButtonKind
import net.kuecker.lodestar.shared.SettingsDivider
import net.kuecker.lodestar.shared.SettingsRow
import net.kuecker.lodestar.shared.SettingsSection
import net.kuecker.lodestar.theme.LocalPalette
import net.kuecker.lodestar.theme.screenBackground

/** The Session Log row of Settings. */
@Composable
internal fun SettingsSessionLogSection(onOpen: (SettingsRoute) -> Unit) {
    val app = LocalAppModel.current
    SettingsSection(
        title = "Session Log",
        footer = "A private diary of what you shot, kept on this phone. Export it as a table or as an OpenAstronomyLog file.",
    ) {
        SettingsNavigationRow("Session Log (${app.sessionLog.entries.size})", Icons.Outlined.Book) { onOpen(SettingsRoute.SessionLog) }
    }
}

/** One night's entries. */
private class Night(val night: Long, val entries: List<SessionLogEntry>)

/** Settings ▸ Session Log: every entry grouped by night, with search, editing, deleting and export. */
@Composable
internal fun SessionLogScreen(onBack: () -> Unit) {
    val app = LocalAppModel.current
    val palette = LocalPalette.current
    var query by rememberSaveable { mutableStateOf("") }
    var presented by remember { mutableStateOf<SessionLogSheetItem?>(null) }
    var entryToDelete by remember { mutableStateOf<SessionLogEntry?>(null) }
    val export = rememberInterchangeExporter()
    val all = app.sessionLog.entries
    val trimmed = query.trim()
    val nights = remember(all, trimmed) {
        val matching = if (trimmed.isEmpty()) {
            all
        } else {
            all.filter { entry ->
                listOf(entry.targetName, entry.siteName, entry.filter, entry.camera, entry.notes)
                    .any { it.contains(trimmed, ignoreCase = true) }
            }
        }
        matching.groupBy { it.night }.map { Night(it.key, it.value) }.sortedByDescending { it.night }
    }

    Column(Modifier.fillMaxSize().screenBackground(palette)) {
        SettingsTopBar("Session Log", onBack)
        LazyColumn(Modifier.weight(1f)) {
            if (all.isEmpty()) {
                item {
                    EmptyStateView(
                        title = "Nothing Logged Yet",
                        icon = Icons.Outlined.Book,
                        message = "Open an object in Objects and tap Log a Session after a night out.",
                    )
                }
            } else {
                item {
                    SettingsSection(title = "Summary") {
                        SettingsRow { InfoRow("Entries", all.size.toString()) }
                        SettingsDivider()
                        SettingsRow { InfoRow("Imaged objects", app.sessionLog.imagedTargetIDs.size.toString()) }
                        SettingsDivider()
                        SettingsRow { InfoRow("Total exposure", totalExposureText(all)) }
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        LodestarButton(
                            text = "Export CSV…",
                            onClick = {
                                export(InterchangeFile(SessionLogCodec.encodeCSV(all), InterchangeFileKind.CSV, "Lodestar Session Log.csv"))
                            },
                            kind = LodestarButtonKind.SECONDARY,
                            modifier = Modifier.weight(1f),
                        )
                        LodestarButton(
                            text = "Export OpenAstronomyLog…",
                            onClick = {
                                export(InterchangeFile(SessionLogCodec.encodeOpenAstronomyLog(all), InterchangeFileKind.XML, "Lodestar Session Log.xml"))
                            },
                            kind = LodestarButtonKind.SECONDARY,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                item { SearchField(query) { query = it } }
                if (nights.isEmpty()) {
                    item {
                        EmptyStateView(title = "No Results", icon = Icons.Outlined.Search, message = "No entry matches “$trimmed”.")
                    }
                }
                for (night in nights) {
                    item(key = "night${night.night}") {
                        Text(
                            SessionLogFormat.nightTitle(night.night).uppercase(),
                            color = palette.secondaryText,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(start = 32.dp, end = 16.dp, top = 16.dp, bottom = 6.dp),
                        )
                    }
                    items(night.entries, key = { it.id }) { entry ->
                        EntryRow(
                            entry,
                            onEdit = { presented = SessionLogSheetItem(SessionLogDraft.editing(entry), isNew = false) },
                            onDelete = { entryToDelete = entry },
                        )
                    }
                }
            }
        }
    }
    presented?.let { item -> SessionLogEntrySheet(item, onDismiss = { presented = null }) }
    entryToDelete?.let { entry -> SessionLogDeleteDialog(entry, onDismiss = { entryToDelete = null }) }
}

/** The exposure time of everything marked as imaged. */
private fun totalExposureText(entries: List<SessionLogEntry>): String {
    val seconds = entries.sumOf { if (it.isImaged) it.totalExposureSeconds else 0.0 }
    return if (seconds > 0) AngleFormatter.formatDuration(seconds) else AppFormat.MISSING
}

@Composable
private fun EntryRow(entry: SessionLogEntry, onEdit: () -> Unit, onDelete: () -> Unit) {
    val palette = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier
                .weight(1f)
                .clickable(role = Role.Button, onClick = onEdit)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(entry.targetName, color = palette.primaryText, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f, fill = false))
                if (entry.isImaged) {
                    Icon(Icons.Outlined.CheckCircle, contentDescription = "Imaged", tint = palette.accent)
                }
            }
            Text(SessionLogFormat.summary(entry), color = palette.secondaryText, style = MaterialTheme.typography.bodySmall)
            val time = SessionLogFormat.timeText(entry)
            Text(
                if (entry.siteName.isEmpty()) time else "$time · ${entry.siteName}",
                color = palette.tertiaryText,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Outlined.Delete, contentDescription = "Delete entry", tint = palette.secondaryText)
        }
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    val palette = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Outlined.Search, contentDescription = null, tint = palette.secondaryText)
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text("Target, site, notes", color = palette.tertiaryText, style = MaterialTheme.typography.bodyLarge)
            }
            BasicTextField(
                value = query,
                onValueChange = onChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = palette.primaryText),
                cursorBrush = SolidColor(palette.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Search the session log" },
            )
        }
    }
}
