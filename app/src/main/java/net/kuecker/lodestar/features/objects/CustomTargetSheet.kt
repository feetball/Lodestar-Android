package net.kuecker.lodestar.features.objects

import net.kuecker.lodestar.shared.LocalSheetDismiss
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Icon
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import net.kuecker.lodestar.kit.catalog.CelestialObject
import net.kuecker.lodestar.kit.foundation.EquatorialCoordinate
import net.kuecker.lodestar.kit.formatting.AngleFormatter
import net.kuecker.lodestar.kit.formatting.parseDeclination
import net.kuecker.lodestar.kit.formatting.parseRightAscension
import net.kuecker.lodestar.shared.LocalAppModel
import net.kuecker.lodestar.shared.LodestarButton
import net.kuecker.lodestar.shared.LodestarSheet
import net.kuecker.lodestar.shared.SettingsDivider
import net.kuecker.lodestar.shared.SettingsRow
import net.kuecker.lodestar.shared.SettingsSection
import net.kuecker.lodestar.theme.LocalPalette

private enum class Field { NAME, RIGHT_ASCENSION, DECLINATION }

/**
 * A sheet for aiming at a place in the sky that is not in the catalog: a name and a J2000 right ascension and
 * declination typed in (see [CustomTargetContent]).
 */
@Composable
internal fun CustomTargetSheet(onDismiss: () -> Unit) {
    LodestarSheet(onDismiss) { CustomTargetContent(LocalSheetDismiss.current) }
}

/**
 * The form of the custom target sheet. Each coordinate says what was understood from it, or why it was not; "Aim at
 * Target" is available once both parse, makes the target and calls [onDismiss].
 */
@Composable
internal fun CustomTargetContent(onDismiss: () -> Unit) {
    val app = LocalAppModel.current
    val focus = LocalFocusManager.current
    var name by rememberSaveable { mutableStateOf("") }
    var rightAscensionText by rememberSaveable { mutableStateOf("") }
    var declinationText by rememberSaveable { mutableStateOf("") }
    var focusedField by remember { mutableStateOf<Field?>(null) }

    // The right ascension in hours and the declination in degrees, when the text is a valid one.
    val rightAscension = remember(rightAscensionText) { AngleFormatter.parseRightAscension(rightAscensionText) }
    val declination = remember(declinationText) { AngleFormatter.parseDeclination(declinationText) }
    val position = if (rightAscension != null && declination != null) {
        EquatorialCoordinate.fromRightAscensionHours(rightAscension, declination)
    } else {
        null
    }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .navigationBarsPadding()
            .padding(bottom = 12.dp),
    ) {
        SheetBar(onCancel = onDismiss)
        SettingsSection(title = "Target") {
            SettingsRow {
                FieldRow(
                    title = "Name",
                    value = name,
                    onValueChange = { name = it.take(CelestialObject.maximumCustomNameLength) },
                    placeholder = "Optional",
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Words,
                        imeAction = ImeAction.Next,
                    ),
                    onFocusChange = { focusedField = focusChange(focusedField, Field.NAME, it) },
                )
            }
        }
        SettingsSection(
            title = "Position (J2000)",
            footer = "Catalogues list positions for the J2000 equinox. " +
                "A right ascension without a ° sign is read as hours.",
        ) {
            SettingsRow {
                FieldRow(
                    title = "Right Ascension",
                    value = rightAscensionText,
                    onValueChange = { rightAscensionText = it },
                    placeholder = "05h 35m 17s",
                    keyboardOptions = coordinateKeyboard(ImeAction.Next),
                    onFocusChange = { focusedField = focusChange(focusedField, Field.RIGHT_ASCENSION, it) },
                )
                FieldNote(
                    text = rightAscensionText,
                    isFocused = focusedField == Field.RIGHT_ASCENSION,
                    accepted = rightAscension?.let { AngleFormatter.formatRightAscension(it) },
                    rejection = "Not a right ascension. Try 5h 35m 17s, 05:35:17 or 83.8°.",
                )
            }
            SettingsDivider()
            SettingsRow {
                FieldRow(
                    title = "Declination",
                    value = declinationText,
                    onValueChange = { declinationText = it },
                    placeholder = "−05° 23′ 28″",
                    keyboardOptions = coordinateKeyboard(ImeAction.Done),
                    onFocusChange = { focusedField = focusChange(focusedField, Field.DECLINATION, it) },
                    // Done puts the keyboard away and ends editing, so a coordinate that is wrong gets flagged.
                    keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                )
                FieldNote(
                    text = declinationText,
                    isFocused = focusedField == Field.DECLINATION,
                    accepted = declination?.let { AngleFormatter.formatDeclination(it) },
                    rejection = "Not a declination. Try −05° 23′ 28″ or −5.391.",
                )
            }
        }
        LodestarButton(
            text = "Aim at Target",
            onClick = {
                if (position != null) {
                    app.aim(CelestialObject.custom(name, position))
                    onDismiss()
                }
            },
            enabled = position != null,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

/** The field that has focus after [field] gained or lost it. */
private fun focusChange(current: Field?, field: Field, isFocused: Boolean): Field? = when {
    isFocused -> field
    current == field -> null
    else -> current
}

/** Coordinates mix digits, letters and signs ("5h 35m 17s", "−05°"), so they need the full keyboard. */
private fun coordinateKeyboard(imeAction: ImeAction) = KeyboardOptions(
    capitalization = KeyboardCapitalization.None,
    autoCorrectEnabled = false,
    keyboardType = KeyboardType.Text,
    imeAction = imeAction,
)

/** The sheet's title with the Cancel button (the iOS navigation bar of the sheet). */
@Composable
private fun SheetBar(onCancel: () -> Unit) {
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
            "Custom Target",
            color = palette.primaryText,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.align(Alignment.Center).semantics { heading() },
        )
    }
}

/** A label with a single-line text field on the same line. */
@Composable
private fun FieldRow(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    keyboardOptions: KeyboardOptions,
    onFocusChange: (Boolean) -> Unit,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
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
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = palette.primaryText, textAlign = TextAlign.End),
                cursorBrush = SolidColor(palette.accent),
                keyboardOptions = keyboardOptions,
                keyboardActions = keyboardActions,
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { onFocusChange(it.isFocused) }
                    .semantics { contentDescription = title },
            )
        }
    }
}

/** What was understood from a coordinate, or why it was not; nothing while the field is empty or being typed in. */
@Composable
private fun FieldNote(text: String, isFocused: Boolean, accepted: String?, rejection: String) {
    val palette = LocalPalette.current
    when {
        accepted != null -> NoteLine(Icons.Outlined.CheckCircle, accepted, palette.secondaryText)
        text.isNotEmpty() && !isFocused -> NoteLine(Icons.Outlined.Warning, rejection, palette.critical)
    }
}

@Composable
private fun NoteLine(icon: ImageVector, text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        Text(text, color = color, style = MaterialTheme.typography.bodySmall)
    }
}
