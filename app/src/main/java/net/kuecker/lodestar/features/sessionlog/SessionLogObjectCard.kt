package net.kuecker.lodestar.features.sessionlog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import net.kuecker.lodestar.kit.catalog.CelestialObject
import net.kuecker.lodestar.kit.interchange.SessionLogEntry
import net.kuecker.lodestar.shared.CardTitle
import net.kuecker.lodestar.shared.LocalAppModel
import net.kuecker.lodestar.shared.LodestarButton
import net.kuecker.lodestar.shared.LodestarButtonKind
import net.kuecker.lodestar.theme.LocalPalette
import net.kuecker.lodestar.theme.card

/** The "Session log" card of an object's page: a button that logs a session and the object's earlier entries. */
@Composable
internal fun SessionLogObjectCard(obj: CelestialObject, modifier: Modifier = Modifier) {
    val app = LocalAppModel.current
    val palette = LocalPalette.current
    var presented by remember { mutableStateOf<SessionLogSheetItem?>(null) }
    var entryToDelete by remember { mutableStateOf<SessionLogEntry?>(null) }
    val logged = app.sessionLog.entriesForTarget(obj.id)

    Column(modifier.fillMaxWidth().card(palette), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CardTitle("Session log", icon = Icons.Outlined.Book)
        LodestarButton(
            text = "Log a Session",
            onClick = {
                val draft = SessionLogDraft.new(obj, app.observerContext, app.lensSetup, app.clock.now)
                presented = SessionLogSheetItem(draft, isNew = true)
            },
            kind = LodestarButtonKind.SECONDARY,
            icon = Icons.Outlined.Edit,
            modifier = Modifier.fillMaxWidth(),
        )
        if (logged.isEmpty()) {
            Text("Nothing logged for this object yet.", color = palette.secondaryText, style = MaterialTheme.typography.bodySmall)
        } else {
            for (entry in logged) {
                Row(verticalAlignment = Alignment.Top) {
                    Column(
                        Modifier
                            .weight(1f)
                            .clickable(role = Role.Button) {
                                presented = SessionLogSheetItem(SessionLogDraft.editing(entry), isNew = false)
                            }
                            .padding(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            SessionLogFormat.dateText(entry),
                            color = palette.primaryText,
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(SessionLogFormat.summary(entry), color = palette.secondaryText, style = MaterialTheme.typography.bodySmall)
                        if (entry.siteName.isNotEmpty()) {
                            Text(entry.siteName, color = palette.tertiaryText, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    IconButton(onClick = { entryToDelete = entry }) {
                        Icon(Icons.Outlined.Delete, contentDescription = "Delete entry", tint = palette.secondaryText)
                    }
                }
            }
        }
    }
    presented?.let { item -> SessionLogEntrySheet(item, onDismiss = { presented = null }) }
    entryToDelete?.let { entry -> SessionLogDeleteDialog(entry, onDismiss = { entryToDelete = null }) }
}

/** Asks whether to delete [entry]; "Delete" removes it and calls [onDismiss], as does "Cancel". */
@Composable
internal fun SessionLogDeleteDialog(entry: SessionLogEntry, onDismiss: () -> Unit) {
    val app = LocalAppModel.current
    val palette = LocalPalette.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.surfaceRaised,
        titleContentColor = palette.primaryText,
        textContentColor = palette.secondaryText,
        title = { Text("Delete This Entry?") },
        text = { Text("${SessionLogFormat.dateText(entry)} \u00B7 ${SessionLogFormat.summary(entry)}") },
        confirmButton = {
            TextButton(
                onClick = {
                    app.sessionLog.delete(setOf(entry.id))
                    onDismiss()
                },
                colors = ButtonDefaults.textButtonColors(contentColor = palette.accent),
            ) { Text("Delete") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, colors = ButtonDefaults.textButtonColors(contentColor = palette.accent)) {
                Text("Cancel")
            }
        },
    )
}
