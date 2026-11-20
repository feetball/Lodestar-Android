package net.kuecker.lodestar.features.sessionlog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import net.kuecker.lodestar.kit.interchange.SessionLogEntry
import net.kuecker.lodestar.services.SessionLogStore
import net.kuecker.lodestar.shared.LocalAppModel
import net.kuecker.lodestar.shared.LocalSheetDismiss
import net.kuecker.lodestar.shared.LodestarButton
import net.kuecker.lodestar.shared.LodestarSheet
import net.kuecker.lodestar.shared.SettingsCaption
import net.kuecker.lodestar.shared.SettingsDivider
import net.kuecker.lodestar.shared.SettingsRow
import net.kuecker.lodestar.shared.SettingsSection
import net.kuecker.lodestar.shared.SettingsToggleRow
import net.kuecker.lodestar.theme.LocalPalette

/** A log sheet that is open: a new entry (from an object's page) or an edited one (from a list). */
internal class SessionLogSheetItem(val draft: SessionLogDraft, val isNew: Boolean)

/** The sheet for one session log entry: where and what was shot (see [SessionLogEntryContent]). */
@Composable
internal fun SessionLogEntrySheet(item: SessionLogSheetItem, onDismiss: () -> Unit) {
    LodestarSheet(onDismiss) { SessionLogEntryContent(item, LocalSheetDismiss.current) }
}

/** The form of the log sheet. "Save" adds or replaces the entry and calls [onDismiss]; it needs a valid date and numbers. */
@Composable
internal fun SessionLogEntryContent(item: SessionLogSheetItem, onDismiss: () -> Unit) {
    val app = LocalAppModel.current
    val palette = LocalPalette.current
    var draft by remember { mutableStateOf(item.draft) }
    var isShowingFull by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .navigationBarsPadding()
            .padding(bottom = 12.dp),
    ) {
        SheetBar(if (item.isNew) "Log a Session" else "Edit Entry", onCancel = onDismiss)
        SettingsSection(
            title = "Session",
            footer = "Turn on Imaged when you got the shot. A plan or a failed attempt can be logged too.",
        ) {
            SettingsRow {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Target", color = palette.primaryText, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        draft.targetName,
                        color = palette.secondaryText,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            SettingsDivider()
            SettingsRow {
                FieldRow("Started", draft.dateText, { draft = draft.copy(dateText = it) }, "2026-11-18 20:15", KeyboardType.Text, draft.parsedDate != null)
            }
            SettingsDivider()
            SettingsRow {
                FieldRow("Site", draft.siteName, { draft = draft.copy(siteName = it) }, "Where you shot", KeyboardType.Text)
            }
            SettingsDivider()
            SettingsToggleRow("Imaged", draft.isImaged, { draft = draft.copy(isImaged = it) })
        }
        SettingsSection(
            title = "Frames",
            footer = "The exposure starts at the longest time before stars trail (the NPF rule) for the lens in Settings. " +
                "Leave a field empty when you do not know it.",
        ) {
            SettingsRow {
                FieldRow("Exposure (s)", draft.exposureText, { draft = draft.copy(exposureText = it) }, "120", KeyboardType.Decimal, draft.exposureSeconds != null)
            }
            SettingsDivider()
            SettingsRow {
                FieldRow("ISO", draft.isoText, { draft = draft.copy(isoText = it) }, "1600", KeyboardType.Number, draft.iso != null)
            }
            SettingsDivider()
            SettingsRow {
                FieldRow("Frames", draft.framesText, { draft = draft.copy(framesText = it) }, "30", KeyboardType.Number, draft.frameCount != null)
            }
            SettingsDivider()
            SettingsRow {
                FieldRow("Filter", draft.filter, { draft = draft.copy(filter = it) }, "None", KeyboardType.Text)
            }
        }
        SettingsSection(title = "Gear") {
            SettingsRow {
                FieldRow("Camera", draft.camera, { draft = draft.copy(camera = it) }, "Sensor", KeyboardType.Text)
            }
            val focalLength = draft.focalLength
            val aperture = draft.lensAperture
            if (focalLength != null && aperture != null) {
                SettingsDivider()
                SettingsRow {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Lens", color = palette.primaryText, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "${plain(focalLength)} mm f/${plain(aperture)}",
                            color = palette.secondaryText,
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.End,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
        SettingsSection(title = "Notes") {
            SettingsRow {
                NotesField(draft.notes) { draft = draft.copy(notes = it.take(SessionLogEntry.maximumNotesLength)) }
                SettingsCaption("${draft.notes.length} of ${SessionLogEntry.maximumNotesLength} characters")
            }
        }
        LodestarButton(
            text = "Save",
            onClick = {
                if (draft.isValid) {
                    val entry = draft.entry()
                    val saved = if (item.isNew) app.sessionLog.add(entry) else true.also { app.sessionLog.update(entry) }
                    if (saved) onDismiss() else isShowingFull = true
                }
            },
            enabled = draft.isValid,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
    if (isShowingFull) {
        AlertDialog(
            onDismissRequest = { isShowingFull = false },
            containerColor = palette.surfaceRaised,
            titleContentColor = palette.primaryText,
            textContentColor = palette.secondaryText,
            title = { Text("The Log Is Full") },
            text = {
                Text("The session log holds ${SessionLogStore.MAXIMUM_ENTRIES} entries. Delete old ones in Settings ▸ Session Log to make room.")
            },
            confirmButton = {
                TextButton(
                    onClick = { isShowingFull = false },
                    colors = ButtonDefaults.textButtonColors(contentColor = palette.accent),
                ) { Text("OK") }
            },
        )
    }
}

/** [value] with at most one decimal and no trailing zero: "135" or "2.8". */
private fun plain(value: Double): String =
    if (value == Math.rint(value)) value.toLong().toString() else "%.1f".format(java.util.Locale.ROOT, value)

/** The sheet's title with the Cancel button (the iOS navigation bar of the sheet). */
@Composable
private fun SheetBar(title: String, onCancel: () -> Unit) {
    val palette = LocalPalette.current
    Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 8.dp)) {
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                .clickable(role = Role.Button, onClick = onCancel)
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("Cancel", color = palette.accent, style = MaterialTheme.typography.bodyLarge)
        }
        Text(
            title,
            color = palette.primaryText,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.align(Alignment.Center).semantics { heading() },
        )
    }
}

/** A label with a single-line text field on the same line; text that is not valid is drawn in the critical colour. */
@Composable
private fun FieldRow(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    keyboardType: KeyboardType,
    isValid: Boolean = true,
) {
    val palette = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, color = palette.primaryText, style = MaterialTheme.typography.bodyLarge)
        Box(Modifier.weight(1f).heightIn(min = 32.dp), contentAlignment = Alignment.CenterEnd) {
            if (value.isEmpty()) {
                Text(
                    placeholder,
                    color = palette.tertiaryText,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = if (isValid) palette.primaryText else palette.critical,
                    textAlign = TextAlign.End,
                ),
                cursorBrush = SolidColor(palette.accent),
                keyboardOptions = KeyboardOptions(
                    capitalization = if (keyboardType == KeyboardType.Text) KeyboardCapitalization.Sentences else KeyboardCapitalization.None,
                    keyboardType = keyboardType,
                ),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = title },
            )
        }
    }
}

/** The multi-line notes field. */
@Composable
private fun NotesField(value: String, onValueChange: (String) -> Unit) {
    val palette = LocalPalette.current
    Box(Modifier.fillMaxWidth().heightIn(min = 96.dp)) {
        if (value.isEmpty()) {
            Text("Seeing, sky, what went wrong", color = palette.tertiaryText, style = MaterialTheme.typography.bodyLarge)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            minLines = 3,
            maxLines = 8,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = palette.primaryText),
            cursorBrush = SolidColor(palette.accent),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Notes" },
        )
    }
}
