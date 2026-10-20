package net.kuecker.lodestar.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.launch
import net.kuecker.lodestar.theme.LodestarTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import net.kuecker.lodestar.theme.LocalPalette
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * A themed form section: palette-coloured header and footer, rows on the palette's surface colour. Put each row in
 * a [SettingsRow] and separate rows with [SettingsDivider].
 */
@Composable
fun SettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = LocalPalette.current
    val shape = RoundedCornerShape(12.dp)
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            title.uppercase(),
            color = palette.secondaryText,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(start = 16.dp, bottom = 6.dp),
        )
        Column(
            Modifier.fillMaxWidth().background(palette.surface, shape).border(0.5.dp, palette.stroke, shape),
            content = content,
        )
        if (footer != null) {
            Text(
                footer,
                color = palette.secondaryText,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp),
            )
        }
    }
}

/** One row of a [SettingsSection] with the standard insets; clickable when [onClick] is set. */
@Composable
fun SettingsRow(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val clickable = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    Column(
        modifier.fillMaxWidth().then(clickable).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        content = content,
    )
}

/** Hairline between rows of a [SettingsSection]. */
@Composable
fun SettingsDivider() {
    HorizontalDivider(Modifier.padding(start = 16.dp), thickness = 0.5.dp, color = LocalPalette.current.stroke)
}

/** Secondary explanatory text under a control. */
@Composable
fun SettingsCaption(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = LocalPalette.current.secondaryText,
        style = MaterialTheme.typography.bodySmall,
        modifier = modifier.fillMaxWidth(),
    )
}

/** A switch with an optional explanation underneath. */
@Composable
fun SettingsToggleRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, caption: String? = null) {
    val palette = LocalPalette.current
    SettingsRow(onClick = { onCheckedChange(!checked) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = palette.primaryText, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = palette.onAccent,
                    checkedTrackColor = palette.accent,
                    uncheckedThumbColor = palette.secondaryText,
                    uncheckedTrackColor = palette.surfaceRaised,
                    uncheckedBorderColor = palette.stroke,
                ),
            )
        }
        if (caption != null) SettingsCaption(caption)
    }
}

/** A slider with its title and current value on one line. Accessibility services read [valueText]. */
@Composable
fun SettingsSliderRow(
    title: String,
    valueText: String,
    value: Double,
    onValueChange: (Double) -> Unit,
    range: ClosedFloatingPointRange<Double>,
    step: Double,
) {
    val palette = LocalPalette.current
    val steps = if (step > 0) ((range.endInclusive - range.start) / step).toInt() - 1 else 0
    SettingsRow {
        Row {
            Text(title, color = palette.primaryText, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(valueText, color = palette.secondaryText, style = MaterialTheme.typography.bodyLarge.monospacedDigits())
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toDouble().coerceIn(range)) },
            valueRange = range.start.toFloat()..range.endInclusive.toFloat(),
            steps = steps.coerceAtLeast(0),
            colors = SliderDefaults.colors(
                thumbColor = palette.accent,
                activeTrackColor = palette.accent,
                inactiveTrackColor = palette.stroke,
                activeTickColor = palette.onAccent.copy(alpha = 0f),
                inactiveTickColor = palette.stroke.copy(alpha = 0f),
            ),
            modifier = Modifier.semantics {
                contentDescription = title
                stateDescription = valueText
            },
        )
    }
}

/** One choice of a [SettingsMenuPicker]. */
data class SettingsMenuOption<T>(val title: String, val value: T)

/** A row that opens a menu to pick one value; the chosen value is drawn in the accent colour. */
@Composable
fun <T> SettingsMenuPicker(
    title: String,
    selection: T,
    options: List<SettingsMenuOption<T>>,
    onSelect: (T) -> Unit,
) {
    val palette = LocalPalette.current
    var expanded by remember { mutableStateOf(false) }
    val selectedTitle = options.firstOrNull { it.value == selection }?.title ?: AppFormat.MISSING
    SettingsRow(
        onClick = { expanded = true },
        modifier = Modifier.semantics {
            contentDescription = title
            stateDescription = selectedTitle
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, color = palette.primaryText, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Box {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(selectedTitle, color = palette.accent, style = MaterialTheme.typography.bodyLarge)
                    Icon(Icons.Outlined.UnfoldMore, contentDescription = null, tint = palette.accent, modifier = Modifier.size(18.dp))
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    containerColor = palette.surfaceRaised,
                ) {
                    for (option in options) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    option.title,
                                    color = if (option.value == selection) palette.accent else palette.primaryText,
                                )
                            },
                            onClick = {
                                expanded = false
                                onSelect(option.value)
                            },
                        )
                    }
                }
            }
        }
    }
}

/** Reading and writing the text of a numeric settings field. */
object SettingsNumberText {
    /** The longest text that is read as a number. */
    private const val MAXIMUM_LENGTH = 16
    private val number = Regex("^[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?$")

    /**
     * The number the text spells, or `null` when it is not a finite number inside [range]. "." and "," both work as
     * the decimal mark, and U+2212 as the minus sign.
     */
    fun parse(text: String, range: ClosedFloatingPointRange<Double>): Double? {
        val normalized = text.trim().replace(',', '.').replace('−', '-')
        if (normalized.length > MAXIMUM_LENGTH || !number.matches(normalized)) return null
        val value = normalized.toDoubleOrNull() ?: return null
        return value.takeIf { it.isFinite() && it in range }
    }

    /**
     * The value as text with at most [fractionDigits] decimals and no trailing zeros, e.g. "2.8" or "24". Always "."
     * and no grouping, so the text can be edited and parsed back. Empty for `null` and non-finite numbers.
     */
    fun text(value: Double?, fractionDigits: Int): String {
        if (value == null || !value.isFinite()) return ""
        val rounded = BigDecimal(value).setScale(fractionDigits, RoundingMode.HALF_EVEN).stripTrailingZeros()
        return if (rounded.signum() == 0) "0" else rounded.toPlainString()
    }
}

/**
 * A labelled numeric text field for a value that always exists; clearing the field does not change it. Valid input
 * is applied while typing; invalid input is flagged and reverted when editing ends. [prefix] is shown before the
 * number (e.g. "f/"), [unit] after it.
 */
@Composable
fun SettingsNumberField(
    title: String,
    value: Double,
    onValueChange: (Double) -> Unit,
    range: ClosedFloatingPointRange<Double>,
    prefix: String = "",
    unit: String = "",
    fractionDigits: Int = 1,
) {
    NumberField(title, value, { it?.let(onValueChange) }, range, prefix, unit, fractionDigits, "", isRequired = true)
}

/** A labelled numeric text field for a value that may be unknown; clearing the field sets it to `null`. */
@Composable
fun SettingsOptionalNumberField(
    title: String,
    value: Double?,
    onValueChange: (Double?) -> Unit,
    range: ClosedFloatingPointRange<Double>,
    prefix: String = "",
    unit: String = "",
    fractionDigits: Int = 1,
    placeholder: String = "Unknown",
) {
    NumberField(title, value, onValueChange, range, prefix, unit, fractionDigits, placeholder, isRequired = false)
}

@Composable
private fun NumberField(
    title: String,
    value: Double?,
    onValueChange: (Double?) -> Unit,
    range: ClosedFloatingPointRange<Double>,
    prefix: String,
    unit: String,
    fractionDigits: Int,
    placeholder: String,
    isRequired: Boolean,
) {
    val palette = LocalPalette.current
    val focus = LocalFocusManager.current
    var text by remember { mutableStateOf(SettingsNumberText.text(value, fractionDigits)) }
    var focused by remember { mutableStateOf(false) }
    val measurer = rememberTextMeasurer()
    val invalid = if (text.isBlank()) isRequired else SettingsNumberText.parse(text, range) == null
    LaunchedEffect(value, focused) {
        if (!focused) text = SettingsNumberText.text(value, fractionDigits)
    }
    SettingsRow {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, color = palette.primaryText, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            if (prefix.isNotEmpty()) Text(prefix, color = palette.secondaryText, style = MaterialTheme.typography.bodyLarge)
            // The field hugs its text, so a prefix such as "f/" sits right next to the number.
            val fieldStyle = MaterialTheme.typography.bodyLarge.monospacedDigits()
            val fieldWidth = with(LocalDensity.current) {
                measurer.measure(text.ifEmpty { "0" }, fieldStyle).size.width.toDp() + 4.dp
            }.coerceIn(24.dp, 120.dp)
            // The unit follows the number; a degree sign hugs it ("20°"), a word such as "mm" or "s" keeps a gap.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (unit == "°") 0.dp else 8.dp),
            ) {
                Box(contentAlignment = Alignment.CenterEnd) {
                    if (text.isEmpty() && placeholder.isNotEmpty()) {
                        Text(placeholder, color = palette.tertiaryText, style = MaterialTheme.typography.bodyLarge)
                    }
                    BasicTextField(
                        value = text,
                        onValueChange = { newText ->
                            text = newText
                            if (newText.isBlank()) {
                                if (!isRequired) onValueChange(null)
                            } else {
                                SettingsNumberText.parse(newText, range)?.let { parsed -> if (parsed != value) onValueChange(parsed) }
                            }
                        },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = if (invalid) palette.critical else palette.primaryText,
                            textAlign = TextAlign.End,
                        ).monospacedDigits(),
                        cursorBrush = SolidColor(palette.accent),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = if (range.start < 0) KeyboardType.Text else KeyboardType.Decimal,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                        modifier = Modifier
                            .width(fieldWidth)
                            .onFocusChanged { focused = it.isFocused }
                            .semantics { contentDescription = title },
                    )
                }
                if (unit.isNotEmpty()) {
                    val unitColor = if (unit == "°") palette.primaryText else palette.secondaryText
                    Text(unit, color = unitColor, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        if (invalid) {
            val lower = SettingsNumberText.text(range.start, fractionDigits)
            val upper = SettingsNumberText.text(range.endInclusive, fractionDigits)
            Text("Enter a value from $lower to $upper.", color = palette.critical, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Inside a [LodestarSheet]'s content: closes the sheet with its slide-out animation, then calls its `onDismiss`. */
val LocalSheetDismiss = staticCompositionLocalOf<() -> Unit> { {} }

/**
 * A modal bottom sheet in the app palette (night-safe), skipping the half-expanded state unless [allowPartial].
 * The sheet is a separate window, so it applies the theme (and the experimental red filter) and hides the system
 * bars itself when the app hides them. Close it from inside with `LocalSheetDismiss.current()` to animate.
 * [scrimColor] dims what is behind the sheet; pass a light one to keep the background readable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LodestarSheet(
    onDismiss: () -> Unit,
    allowPartial: Boolean = false,
    scrimColor: Color = Color.Black.copy(alpha = 0.6f),
    content: @Composable ColumnScope.() -> Unit,
) {
    val app = LocalAppModel.current
    val palette = LocalPalette.current
    val hideBars = LocalSystemBarsHidden.current
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = !allowPartial)
    val scope = rememberCoroutineScope()
    val latestOnDismiss by rememberUpdatedState(onDismiss)
    val dismiss: () -> Unit = remember(state, scope) {
        { scope.launch { state.hide() }.invokeOnCompletion { latestOnDismiss() } }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = palette.background,
        contentColor = palette.primaryText,
        scrimColor = scrimColor,
    ) {
        if (hideBars) HideSystemBarsInWindow()
        LodestarTheme(palette, redFilter = app.settings.redFilterOverlay) {
            CompositionLocalProvider(LocalSheetDismiss provides dismiss) {
                Column(content = content)
            }
        }
    }
}

/** Vertical space between sections of a scrolling form. */
@Composable
fun SettingsSpacer() = Spacer(Modifier.size(8.dp))
