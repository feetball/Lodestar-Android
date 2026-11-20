package net.kuecker.lodestar.features.aim

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerColors
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerColors
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.kuecker.lodestar.shared.AppFormat
import net.kuecker.lodestar.shared.LodestarButton
import net.kuecker.lodestar.shared.LodestarButtonKind
import net.kuecker.lodestar.shared.LodestarSheet
import net.kuecker.lodestar.shared.LocalSheetDismiss
import net.kuecker.lodestar.shared.monospacedDigits
import net.kuecker.lodestar.theme.LocalPalette
import net.kuecker.lodestar.theme.Palette
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.ceil

/** Which picker the sheet shows. */
private enum class PreviewPicker(val title: String) { DATE("Date"), TIME("Time") }

/** The sheet that sets the previewed date and time: a bottom sheet around [AimPreviewTimeContent]. */
@Composable
internal fun AimPreviewTimeSheet(model: AimViewModel, onDismiss: () -> Unit) {
    LodestarSheet(onDismiss) { AimPreviewTimeContent(model, LocalSheetDismiss.current) }
}

/**
 * Sets the previewed date and time with Material pickers drawn from palette colours, so they stay red in night mode.
 * Every change applies at once; the readout above shows the result. Dates and times are those of the observer's time
 * zone. The pickers and the preview follow each other: a jump from outside the pickers ("Now") moves them, and the
 * preview never leaves its range of 48 hours from now.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AimPreviewTimeContent(model: AimViewModel, onDismiss: () -> Unit) {
    val palette = LocalPalette.current
    val zone = model.previewZone()
    var mode by remember { mutableStateOf(PreviewPicker.DATE) }

    val displayed = model.date.atZone(zone)
    val limitDays = ceil(AimPreview.LIMIT_SECONDS / 86_400).toLong()
    val today = model.app.clock.now.atZone(zone).toLocalDate()
    val earliest = today.minusDays(limitDays)
    val latest = today.plusDays(limitDays)
    val dateState = rememberDatePickerState(
        initialSelectedDateMillis = utcMillis(displayed.toLocalDate()),
        yearRange = minOf(earliest.year, displayed.year)..maxOf(latest.year, displayed.year),
        selectableDates = remember(earliest, latest) { dateRange(earliest, latest) },
    )
    val timeState = rememberTimePickerState(displayed.hour, displayed.minute, AppFormat.use24HourClock)

    // The pickers move the preview...
    LaunchedEffect(dateState, zone) {
        snapshotFlow { dateState.selectedDateMillis }.collect { millis ->
            val date = millis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() } ?: return@collect
            val current = model.date.atZone(zone)
            if (date != current.toLocalDate()) model.previewAt(date.atTime(current.toLocalTime()).atZone(zone).toInstant())
        }
    }
    LaunchedEffect(timeState, zone) {
        snapshotFlow { timeState.hour * 60 + timeState.minute }.collect { minutes ->
            val current = model.date.atZone(zone)
            if (minutes != current.hour * 60 + current.minute) {
                model.previewAt(current.toLocalDate().atTime(minutes / 60, minutes % 60).atZone(zone).toInstant())
            }
        }
    }
    // ...and the preview moves the pickers when it jumps (or is held back at the end of its range).
    LaunchedEffect(model.previewOffset, zone) {
        val current = model.date.atZone(zone)
        val millis = utcMillis(current.toLocalDate())
        // One snapshot, so that the collectors above never see a half-moved time.
        Snapshot.withMutableSnapshot {
            if (dateState.selectedDateMillis != millis && current.year in dateState.yearRange) {
                dateState.selectedDateMillis = millis
                dateState.displayedMonthMillis = millis
            }
            if (timeState.hour != current.hour) timeState.hour = current.hour
            if (timeState.minute != current.minute) timeState.minute = current.minute
        }
    }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Preview Time",
                Modifier.weight(1f).semantics { heading() },
                color = palette.primaryText,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            )
            TextButton(onClick = onDismiss, colors = ButtonDefaults.textButtonColors(contentColor = palette.accent)) {
                Text("Done")
            }
        }
        Readout(model, zone)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (option in PreviewPicker.entries) {
                LodestarButton(
                    option.title,
                    onClick = { mode = option },
                    modifier = Modifier.weight(1f).semantics { if (mode == option) stateDescription = "Selected" },
                    kind = if (mode == option) LodestarButtonKind.PRIMARY else LodestarButtonKind.SECONDARY,
                )
            }
        }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            when (mode) {
                PreviewPicker.DATE -> DatePicker(
                    dateState,
                    title = null,
                    headline = null,
                    showModeToggle = false,
                    colors = datePickerColors(palette),
                )
                PreviewPicker.TIME -> TimePicker(timeState, colors = timePickerColors(palette))
            }
        }
        LodestarButton("Now", onClick = model::endPreview, enabled = model.isPreviewing)
    }
}

/** The previewed day and time, large, and how far from now it is. */
@Composable
private fun Readout(model: AimViewModel, zone: ZoneId) {
    val palette = LocalPalette.current
    val date = model.date
    Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            AppFormat.day(date, zone),
            color = palette.primaryText,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        )
        Text(
            AppFormat.time(date, zone),
            color = palette.primaryText,
            style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.SemiBold).monospacedDigits(),
        )
        Text(
            if (model.isPreviewing) AppFormat.relative(date, model.app.clock.now) else "Live",
            color = palette.secondaryText,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** The midnight (UTC) that starts [date]: the form in which Material's date picker holds a selected day. */
private fun utcMillis(date: LocalDate): Long = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

/** The dates from [earliest] to [latest] inclusive. */
@OptIn(ExperimentalMaterial3Api::class)
private fun dateRange(earliest: LocalDate, latest: LocalDate): SelectableDates = object : SelectableDates {
    override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis in utcMillis(earliest)..utcMillis(latest)

    override fun isSelectableYear(year: Int): Boolean = year in earliest.year..latest.year
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun datePickerColors(palette: Palette): DatePickerColors = DatePickerDefaults.colors(
    containerColor = Color.Transparent,
    titleContentColor = palette.secondaryText,
    headlineContentColor = palette.primaryText,
    weekdayContentColor = palette.secondaryText,
    subheadContentColor = palette.secondaryText,
    navigationContentColor = palette.primaryText,
    yearContentColor = palette.primaryText,
    disabledYearContentColor = palette.tertiaryText,
    currentYearContentColor = palette.accent,
    selectedYearContentColor = palette.onAccent,
    disabledSelectedYearContentColor = palette.tertiaryText,
    selectedYearContainerColor = palette.accent,
    disabledSelectedYearContainerColor = palette.surfaceRaised,
    dayContentColor = palette.primaryText,
    disabledDayContentColor = palette.tertiaryText,
    selectedDayContentColor = palette.onAccent,
    disabledSelectedDayContentColor = palette.tertiaryText,
    selectedDayContainerColor = palette.accent,
    disabledSelectedDayContainerColor = palette.surfaceRaised,
    todayContentColor = palette.accent,
    todayDateBorderColor = palette.accent,
    dayInSelectionRangeContentColor = palette.primaryText,
    dayInSelectionRangeContainerColor = palette.surfaceRaised,
    dividerColor = palette.stroke,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun timePickerColors(palette: Palette): TimePickerColors = TimePickerDefaults.colors(
    clockDialColor = palette.surface,
    clockDialSelectedContentColor = palette.onAccent,
    clockDialUnselectedContentColor = palette.primaryText,
    selectorColor = palette.accent,
    containerColor = Color.Transparent,
    periodSelectorBorderColor = palette.stroke,
    periodSelectorSelectedContainerColor = palette.accent,
    periodSelectorUnselectedContainerColor = Color.Transparent,
    periodSelectorSelectedContentColor = palette.onAccent,
    periodSelectorUnselectedContentColor = palette.secondaryText,
    timeSelectorSelectedContainerColor = palette.accent,
    timeSelectorUnselectedContainerColor = palette.surfaceRaised,
    timeSelectorSelectedContentColor = palette.onAccent,
    timeSelectorUnselectedContentColor = palette.primaryText,
)
