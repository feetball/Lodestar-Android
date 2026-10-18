package net.kuecker.lodestar.features.skychart

import net.kuecker.lodestar.shared.LocalSheetDismiss
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import net.kuecker.lodestar.model.SkyClock
import net.kuecker.lodestar.shared.AppFormat
import net.kuecker.lodestar.shared.CardTitle
import net.kuecker.lodestar.shared.LocalAppModel
import net.kuecker.lodestar.shared.LodestarButton
import net.kuecker.lodestar.shared.LodestarButtonKind
import net.kuecker.lodestar.shared.LodestarSheet
import net.kuecker.lodestar.shared.monospacedDigits
import net.kuecker.lodestar.theme.LocalPalette
import net.kuecker.lodestar.theme.Palette
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** The farthest the sky clock can be moved from now, in days. */
private const val CLOCK_RANGE_DAYS = 366L

/** Which picker the sheet shows. */
private enum class PickerMode(val title: String) { DATE("Date"), TIME("Time") }

/**
 * The sky chart's date sheet: a bottom sheet around [SkyChartDateSheetContent].
 */
@Composable
internal fun SkyChartDateSheet(timeZone: ZoneId, onDismiss: () -> Unit) {
    LodestarSheet(onDismiss) { SkyChartDateSheetContent(timeZone, LocalSheetDismiss.current) }
}

/**
 * Sets the sky chart's date and time with Material pickers drawn from palette colours, so they stay red in night mode,
 * and jumps to a few useful moments. Every change applies at once; the readout above shows the result.
 *
 * Dates and times are those of the observer's [timeZone]. The pickers and the clock follow each other: a jump from
 * outside the pickers ("Now") moves them, and the clock never leaves its range of a year from now.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SkyChartDateSheetContent(timeZone: ZoneId, onDismiss: () -> Unit) {
    val clock = LocalAppModel.current.clock
    val palette = LocalPalette.current
    var mode by remember { mutableStateOf(PickerMode.DATE) }

    val displayed = clock.displayDate.atZone(timeZone)
    val earliest = clock.now.atZone(timeZone).toLocalDate().minusDays(CLOCK_RANGE_DAYS)
    val latest = clock.now.atZone(timeZone).toLocalDate().plusDays(CLOCK_RANGE_DAYS)
    val dateState = rememberDatePickerState(
        initialSelectedDateMillis = utcMillis(displayed.toLocalDate()),
        // The displayed date can lie a second outside the range the clock allows; the picker must still show it.
        yearRange = minOf(earliest.year, displayed.year)..maxOf(latest.year, displayed.year),
        selectableDates = remember(earliest, latest) { dateRange(earliest, latest) },
    )
    val timeState = rememberTimePickerState(displayed.hour, displayed.minute, AppFormat.use24HourClock)

    // The pickers move the clock...
    LaunchedEffect(dateState, timeZone) {
        snapshotFlow { dateState.selectedDateMillis }.collect { millis ->
            val date = millis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() } ?: return@collect
            val current = clock.displayDate.atZone(timeZone)
            if (date != current.toLocalDate()) clock.travel(date.atTime(current.toLocalTime()).atZone(timeZone).toInstant())
        }
    }
    LaunchedEffect(timeState, timeZone) {
        snapshotFlow { timeState.hour * 60 + timeState.minute }.collect { minutes ->
            val current = clock.displayDate.atZone(timeZone)
            if (minutes != current.hour * 60 + current.minute) {
                clock.travel(current.toLocalDate().atTime(minutes / 60, minutes % 60).atZone(timeZone).toInstant())
            }
        }
    }
    // ...and the clock moves the pickers when it jumps (or is held back at the end of its range).
    LaunchedEffect(clock.offset, timeZone) {
        val current = clock.displayDate.atZone(timeZone)
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
                "Sky Time",
                Modifier.weight(1f).semantics { heading() },
                color = palette.primaryText,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            )
            TextButton(onClick = onDismiss, colors = ButtonDefaults.textButtonColors(contentColor = palette.accent)) {
                Text("Done")
            }
        }
        Readout(clock, timeZone)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (option in PickerMode.entries) {
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
                PickerMode.DATE -> DatePicker(
                    dateState,
                    title = null,
                    headline = null,
                    showModeToggle = false,
                    colors = datePickerColors(palette),
                )
                PickerMode.TIME -> TimePicker(timeState, colors = timePickerColors(palette))
            }
        }
        Presets(clock, timeZone)
    }
}

/** The displayed day and time, large, and how far from now it is. */
@Composable
private fun Readout(clock: SkyClock, timeZone: ZoneId) {
    val palette = LocalPalette.current
    Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            AppFormat.day(clock.displayDate, timeZone),
            color = palette.primaryText,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        )
        Text(
            AppFormat.time(clock.displayDate, timeZone),
            color = palette.primaryText,
            style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.SemiBold).monospacedDigits(),
        )
        Text(
            if (clock.isLive) "Live" else AppFormat.relative(clock.displayDate, clock.now),
            color = palette.secondaryText,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** The shortcuts: back to now, 22:00 today, and the next midnight. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Presets(clock: SkyClock, timeZone: ZoneId) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CardTitle("Jump to")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            LodestarButton("Now", onClick = clock::returnToNow, enabled = !clock.isLive)
            LodestarButton(
                "Tonight 22:00",
                onClick = { clock.travel(today(clock, timeZone).atTime(22, 0).atZone(timeZone).toInstant()) },
                kind = LodestarButtonKind.SECONDARY,
            )
            LodestarButton(
                "Midnight",
                onClick = { clock.travel(today(clock, timeZone).plusDays(1).atStartOfDay(timeZone).toInstant()) },
                kind = LodestarButtonKind.SECONDARY,
            )
        }
    }
}

/** The calendar day it is now (not on the chart) in [timeZone]. */
private fun today(clock: SkyClock, timeZone: ZoneId): LocalDate = clock.now.atZone(timeZone).toLocalDate()

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
