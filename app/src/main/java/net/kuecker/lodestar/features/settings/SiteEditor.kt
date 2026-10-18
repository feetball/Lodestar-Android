package net.kuecker.lodestar.features.settings

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import net.kuecker.lodestar.kit.foundation.GeographicLocation
import net.kuecker.lodestar.model.AppSettings
import net.kuecker.lodestar.model.SavedSite
import net.kuecker.lodestar.services.LocationFix
import net.kuecker.lodestar.services.ResolvedTimeZone
import net.kuecker.lodestar.services.TimeZoneResolver
import net.kuecker.lodestar.shared.LocalAppModel
import net.kuecker.lodestar.shared.SettingsCaption
import net.kuecker.lodestar.shared.SettingsDivider
import net.kuecker.lodestar.shared.SettingsMenuOption
import net.kuecker.lodestar.shared.SettingsMenuPicker
import net.kuecker.lodestar.shared.SettingsNumberField
import net.kuecker.lodestar.shared.SettingsRow
import net.kuecker.lodestar.shared.SettingsSection
import net.kuecker.lodestar.theme.LocalPalette
import java.text.BreakIterator
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlin.math.abs

/** What the site editor starts from. */
internal sealed interface SiteEditorTarget {
    /** A new site, starting from the draft. */
    data class New(val draft: SiteDraft) : SiteEditorTarget

    /** A saved site. */
    data class Existing(val site: SavedSite) : SiteEditorTarget
}

/** Wait after the last change to the position before the time zone is looked up (typing, map picks). */
private const val LOOKUP_DELAY_MS = 600L

/** The longest coordinate text taken from the keyboard or the clipboard. */
private const val MAXIMUM_COORDINATE_TEXT = 64

/** A saved site's position counts as unchanged when it moved less than this many degrees (about 10 cm). */
private const val UNCHANGED_POSITION_DEGREES = 1e-6

/**
 * Adds or edits a saved observing site: its name, its position (typed, picked on the map or taken from the device),
 * its elevation and the time zone found for that position. Show it inside a `LodestarSheet` and give it
 * `Modifier.weight(1f)` so it fills the sheet below the drag handle; [onClose] dismisses the sheet, and [onSaved] is
 * called with the site after it was added or updated, just before the editor closes.
 *
 * Android's geocoder only knows the country of a position. In a country with several time zones the editor guesses
 * the zone from the position, says so, and lets the user choose among the country's zones.
 */
@Composable
internal fun SiteEditor(
    target: SiteEditorTarget,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onSaved: ((SavedSite) -> Unit)? = null,
) {
    val app = LocalAppModel.current
    val palette = LocalPalette.current
    val context = LocalContext.current
    val existingSite = (target as? SiteEditorTarget.Existing)?.site
    var draft by remember { mutableStateOf(initialDraft(target)) }
    var timeZoneStatus by remember { mutableStateOf(initialStatus(existingSite)) }
    var lookupAttempt by remember { mutableIntStateOf(0) }
    var isSaving by remember { mutableStateOf(false) }
    var isConfirmingDelete by remember { mutableStateOf(false) }
    var failureMessage by remember { mutableStateOf<String?>(null) }
    var showsMap by remember { mutableStateOf(false) }

    val location = draft.location
    // The position alone: the lookup restarts when it changes, not when the name or elevation does.
    val position = remember(draft.latitudeText, draft.longitudeText) {
        draft.location?.let { GeographicLocation(it.latitude, it.longitude) }
    }
    val isLookupPending = when (timeZoneStatus) {
        SiteTimeZoneStatus.Resolving -> true
        SiteTimeZoneStatus.WaitingForCoordinates -> location != null
        is SiteTimeZoneStatus.Resolved, is SiteTimeZoneStatus.Approximate -> false
    }

    // Finds the zone of the drafted position. A saved site whose position did not change keeps its zone; otherwise
    // the geocoder is asked after a short pause, and the longitude-based estimate stands in when it cannot answer.
    // The effect is cancelled when the position changes or the editor closes.
    LaunchedEffect(position, lookupAttempt) {
        val point = position
        if (point == null) {
            timeZoneStatus = SiteTimeZoneStatus.WaitingForCoordinates
            return@LaunchedEffect
        }
        val storedZone = existingSite?.timeZone
        if (existingSite != null && storedZone != null && !SettingsFormat.isNominal(storedZone) && lookupAttempt == 0 &&
            abs(existingSite.latitude - point.latitude) < UNCHANGED_POSITION_DEGREES &&
            abs(existingSite.longitude - point.longitude) < UNCHANGED_POSITION_DEGREES
        ) {
            timeZoneStatus = SiteTimeZoneStatus.Resolved(storedZone)
            return@LaunchedEffect
        }
        timeZoneStatus = SiteTimeZoneStatus.Resolving
        delay(LOOKUP_DELAY_MS)
        timeZoneStatus = when (val answer = TimeZoneResolver.timeZone(context, point)) {
            is ResolvedTimeZone.Exact -> SiteTimeZoneStatus.Resolved(answer.zone)
            is ResolvedTimeZone.Estimated -> SiteTimeZoneStatus.Approximate(answer.zone)
            null -> SiteTimeZoneStatus.Approximate(TimeZoneResolver.nominalTimeZone(point.longitude))
        }
    }

    fun commit() {
        isSaving = false
        val point = draft.location ?: return
        val zone = timeZoneStatus.timeZone ?: TimeZoneResolver.nominalTimeZone(point.longitude)
        val site = SavedSite.create(
            name = draft.name, latitude = point.latitude, longitude = point.longitude, elevation = point.elevation,
            timeZoneIdentifier = zone.id, id = existingSite?.id ?: UUID.randomUUID().toString(),
        )
        if (site == null) {
            failureMessage = "This position cannot be saved. Check the latitude, longitude and elevation."
            return
        }
        val store = app.settingsStore
        if (existingSite != null) {
            store.updateSite(site)
        } else if (!store.addSite(site)) {
            failureMessage = "You can keep up to ${AppSettings.MAXIMUM_SAVED_SITES} sites. Delete one to add another."
            return
        }
        onSaved?.invoke(site)
        onClose()
    }

    // Saves now, or as soon as the time zone lookup that is under way has an answer.
    fun save() {
        if (location == null) return
        failureMessage = null
        if (isLookupPending) isSaving = true else commit()
    }

    LaunchedEffect(timeZoneStatus, isSaving) {
        if (isSaving && !isLookupPending) commit()
    }

    Box(modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxSize()) {
            SiteEditorBar(
                title = if (existingSite == null) "New Site" else "Edit Site",
                isSaving = isSaving,
                canSave = location != null,
                onCancel = onClose,
                onSave = ::save,
            )
            Column(
                Modifier.weight(1f).alpha(if (isSaving) 0.5f else 1f).imePadding().verticalScroll(rememberScrollState()),
            ) {
                SettingsSection(title = "Name") {
                    SettingsRow {
                        SiteTextField(
                            value = draft.name,
                            onValueChange = { draft = draft.copy(name = limitedName(it)) },
                            label = "Site name",
                            placeholder = "Site name",
                            capitalization = KeyboardCapitalization.Words,
                        )
                        failureMessage?.let {
                            Text(it, color = palette.critical, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                SettingsSection(
                    title = "Position",
                    footer = "Type decimal degrees (40.015 or −105.27) or degrees, minutes and seconds with N, S, E " +
                        "or W. The map cannot tell the elevation, so enter it if you know it.",
                ) {
                    SiteCoordinateField(
                        title = "Latitude", prompt = "40.0150° N", text = draft.latitudeText,
                        onTextChange = { draft = draft.copy(latitudeText = it.take(MAXIMUM_COORDINATE_TEXT)) },
                        isValid = draft.isLatitudeValid,
                        errorMessage = "Not a latitude between 90° S and 90° N.",
                    )
                    SettingsDivider()
                    SiteCoordinateField(
                        title = "Longitude", prompt = "105.2705° W", text = draft.longitudeText,
                        onTextChange = { draft = draft.copy(longitudeText = it.take(MAXIMUM_COORDINATE_TEXT)) },
                        isValid = draft.isLongitudeValid,
                        errorMessage = "Not a longitude between 180° W and 180° E.",
                    )
                    SettingsDivider()
                    SettingsNumberField(
                        title = "Elevation", value = draft.elevation,
                        onValueChange = { draft = draft.copy(elevation = it) },
                        range = GeographicLocation.elevationRange, unit = "m", fractionDigits = 0,
                    )
                    SettingsDivider()
                    SettingsNavigationRow("Choose on Map", Icons.Outlined.Map) { showsMap = true }
                    app.location.fix?.let { fix ->
                        SettingsDivider()
                        SettingsActionRow(
                            title = "Use My Current Position",
                            icon = Icons.Outlined.MyLocation,
                            onClick = { draft = withDevicePosition(draft, fix) },
                        )
                    }
                }
                TimeZoneSection(
                    status = timeZoneStatus,
                    onChoose = { timeZoneStatus = SiteTimeZoneStatus.Resolved(it) },
                    onLookUpAgain = { lookupAttempt += 1 },
                )
                if (existingSite != null) {
                    SettingsSection(title = "Delete") {
                        if (isConfirmingDelete) {
                            SettingsRow { SettingsCaption("Delete “${existingSite.name}”? This cannot be undone.") }
                            SettingsDivider()
                            SettingsActionRow(
                                title = "Delete Site",
                                isDestructive = true,
                                onClick = {
                                    app.settingsStore.removeSites(setOf(existingSite.id))
                                    onClose()
                                },
                            )
                            SettingsDivider()
                            SettingsActionRow(title = "Keep Site", onClick = { isConfirmingDelete = false })
                        } else {
                            SettingsActionRow(title = "Delete Site…", isDestructive = true, onClick = { isConfirmingDelete = true })
                        }
                    }
                }
            }
        }
        if (isSaving) {
            // The form is read-only while the save waits for the time zone.
            Box(Modifier.matchParentSize().pointerInput(Unit) { detectTapGestures { } })
        }
    }

    if (showsMap) {
        MapSitePicker(
            initialLocation = location,
            fallbackCenter = app.observerContext?.location,
            onSelect = { picked -> draft = draft.withPosition(picked) },
            onClose = { showsMap = false },
        )
    }
}

private fun initialDraft(target: SiteEditorTarget): SiteDraft = when (target) {
    is SiteEditorTarget.New -> target.draft
    is SiteEditorTarget.Existing -> SiteDraft.of(target.site)
}

private fun initialStatus(existingSite: SavedSite?): SiteTimeZoneStatus {
    val zone = existingSite?.timeZone ?: return SiteTimeZoneStatus.WaitingForCoordinates
    return if (SettingsFormat.isNominal(zone)) SiteTimeZoneStatus.Approximate(zone) else SiteTimeZoneStatus.Resolved(zone)
}

private fun withDevicePosition(draft: SiteDraft, fix: LocationFix): SiteDraft {
    val current = SiteDraft.at(draft.name, fix)
    return draft.copy(latitudeText = current.latitudeText, longitudeText = current.longitudeText, elevation = current.elevation)
}

/** [name] cut to the longest length a saved site keeps, at a character boundary. */
private fun limitedName(name: String): String {
    val maximum = SavedSite.MAXIMUM_NAME_LENGTH
    if (name.length <= maximum) return name
    val boundaries = BreakIterator.getCharacterInstance().apply { setText(name) }
    return name.substring(0, boundaries.preceding(maximum + 1))
}

/** The sheet's title bar: Cancel, the title, and Save (or a spinner while the save waits for the time zone). */
@Composable
private fun SiteEditorBar(title: String, isSaving: Boolean, canSave: Boolean, onCancel: () -> Unit, onSave: () -> Unit) {
    val palette = LocalPalette.current
    val colors = ButtonDefaults.textButtonColors(contentColor = palette.accent, disabledContentColor = palette.tertiaryText)
    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onCancel, colors = colors) { Text("Cancel") }
        Text(
            title,
            color = palette.primaryText,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        if (isSaving) {
            Box(Modifier.size(width = 64.dp, height = 48.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = palette.accent, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
            }
        } else {
            TextButton(onClick = onSave, enabled = canSave, colors = colors) {
                Text("Save", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** A single-line text field drawn with palette colours, with a [placeholder] while empty. */
@Composable
private fun SiteTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    capitalization: KeyboardCapitalization,
    color: Color = LocalPalette.current.primaryText,
) {
    val palette = LocalPalette.current
    val focus = LocalFocusManager.current
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = color),
        cursorBrush = SolidColor(palette.accent),
        keyboardOptions = KeyboardOptions(
            capitalization = capitalization,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Text,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
        decorationBox = { field ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) {
                    Text(placeholder, color = palette.tertiaryText, style = MaterialTheme.typography.bodyLarge)
                }
                field()
            }
        },
    )
}

/** A latitude or longitude text field with an inline message when the text cannot be read. */
@Composable
private fun SiteCoordinateField(
    title: String,
    prompt: String,
    text: String,
    onTextChange: (String) -> Unit,
    isValid: Boolean,
    errorMessage: String,
) {
    val palette = LocalPalette.current
    val showsError = !isValid && text.isNotBlank()
    SettingsRow {
        Text(title, color = palette.secondaryText, style = MaterialTheme.typography.bodySmall)
        SiteTextField(
            value = text,
            onValueChange = onTextChange,
            label = title,
            placeholder = prompt,
            capitalization = KeyboardCapitalization.Characters,
            color = if (showsError) palette.critical else palette.primaryText,
        )
        if (showsError) Text(errorMessage, color = palette.critical, style = MaterialTheme.typography.bodySmall)
    }
}

/** The result of the time zone lookup for the site being edited, and the way to correct it. */
@Composable
private fun TimeZoneSection(status: SiteTimeZoneStatus, onChoose: (ZoneId) -> Unit, onLookUpAgain: () -> Unit) {
    val now = Instant.now()
    val zone = status.timeZone
    // The zone data is read off the main thread; the list of the previous zone stays until the new one is ready.
    val choices by produceState(emptyList<ZoneId>(), zone) {
        value = withContext(Dispatchers.Default) { zone?.let(TimeZoneResolver::zonesOfCountry).orEmpty() }
    }
    SettingsSection(title = "Time Zone", footer = "Rise, set and twilight times at this site are shown in its local time.") {
        SettingsRow { SiteTimeZoneRow(status, now, hasChoices = choices.size > 1) }
        if (zone != null && choices.size > 1) {
            SettingsDivider()
            SettingsMenuPicker(
                title = "Time zone",
                selection = zone,
                options = choices.map { SettingsMenuOption(choiceTitle(it, now), it) },
                onSelect = onChoose,
            )
        }
        if (status is SiteTimeZoneStatus.Approximate) {
            SettingsDivider()
            SettingsActionRow(title = "Look Up Again", onClick = onLookUpAgain)
        }
    }
}

/** "Denver · UTC−7": the city of a zone and its offset, short enough for a menu row. */
private fun choiceTitle(zone: ZoneId, at: Instant): String =
    "${zone.id.substringAfterLast('/').replace('_', ' ')} · ${SettingsFormat.utcOffset(zone, at)}"

/** What the lookup has found so far; an approximate answer says why it is one and what the user can do. */
@Composable
private fun SiteTimeZoneRow(status: SiteTimeZoneStatus, date: Instant, hasChoices: Boolean) {
    val palette = LocalPalette.current
    when (status) {
        SiteTimeZoneStatus.WaitingForCoordinates ->
            SettingsCaption("Enter a valid latitude and longitude to find the time zone.")
        SiteTimeZoneStatus.Resolving -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CircularProgressIndicator(color = palette.accent, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
            Text("Looking up the time zone…", color = palette.secondaryText, style = MaterialTheme.typography.bodyLarge)
        }
        is SiteTimeZoneStatus.Resolved ->
            Text(SettingsFormat.describe(status.zone, date), color = palette.primaryText, style = MaterialTheme.typography.bodyLarge)
        is SiteTimeZoneStatus.Approximate -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(SettingsFormat.describe(status.zone, date), color = palette.primaryText, style = MaterialTheme.typography.bodyLarge)
            SettingsCaption(
                if (hasChoices) {
                    "This country has several time zones and the phone cannot tell which one the site is in. " +
                        "Lodestar made a guess from the site's position; if it is wrong, choose the right one " +
                        "below."
                } else {
                    "The time zone could not be looked up, perhaps because there is no network. Times at this " +
                        "site may be off by an hour."
                },
            )
        }
    }
}
