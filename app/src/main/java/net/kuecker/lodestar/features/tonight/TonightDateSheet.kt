package net.kuecker.lodestar.features.tonight

import net.kuecker.lodestar.shared.LocalSheetDismiss
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NightsStay
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.drop
import net.kuecker.lodestar.shared.LocalAppModel
import net.kuecker.lodestar.shared.LodestarButton
import net.kuecker.lodestar.shared.LodestarSheet
import net.kuecker.lodestar.theme.LocalPalette
import net.kuecker.lodestar.theme.LodestarTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Chooses the night to plan with a month calendar in palette colours.
 *
 * A night is named after the evening it starts on and runs from noon to noon, so each calendar day stands for the local
 * noon that starts its night. Tapping a day chooses its night and closes the sheet, as does "Tonight".
 *
 * Material's date picker works with UTC dates, so days are converted from and to local dates of [timeZone]. It marks the
 * device's current date; that is shown only when it is the date of [tonight], and it is hidden otherwise rather than
 * pointing at the wrong day.
 *
 * @param selected The night now shown.
 * @param tonight The night under way (or coming), marked in the calendar.
 * @param range The nights that can be chosen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TonightDateSheet(
    timeZone: ZoneId,
    selected: Instant,
    tonight: Instant,
    range: ClosedRange<Instant>,
    onSelect: (TonightNightChoice) -> Unit,
    onDismiss: () -> Unit,
) {
    val app = LocalAppModel.current
    LodestarSheet(onDismiss) {
        TonightDateSheetContent(timeZone, selected, tonight, range, onSelect, LocalSheetDismiss.current)
    }
}

/** The inside of [TonightDateSheet]: the header, the calendar and the "Tonight" button. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TonightDateSheetContent(
    timeZone: ZoneId,
    selected: Instant,
    tonight: Instant,
    range: ClosedRange<Instant>,
    onSelect: (TonightNightChoice) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalPalette.current
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val firstDay = range.start.atZone(timeZone).toLocalDate()
    val lastDay = range.endInclusive.atZone(timeZone).toLocalDate()
    val selectedDay = selected.atZone(timeZone).toLocalDate()
    val tonightDay = tonight.atZone(timeZone).toLocalDate()
    val years = minOf(firstDay.year, selectedDay.year)..maxOf(lastDay.year, selectedDay.year)
    val selectableDates = remember(firstDay, lastDay) {
        object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcDay(utcTimeMillis) in firstDay..lastDay
            override fun isSelectableYear(year: Int): Boolean = year in firstDay.year..lastDay.year
        }
    }
    val state = rememberDatePickerState(
        initialSelectedDateMillis = utcMillis(selectedDay),
        yearRange = years,
        selectableDates = selectableDates,
    )
    // The picker's own marker is for the device's today, which is only "tonight" when the dates agree.
    val marksTonight = tonightDay == LocalDate.now()
    val colors = DatePickerDefaults.colors(
        // Opaque, like the sheet: the year list is drawn over the month grid.
        containerColor = palette.background,
        titleContentColor = palette.secondaryText,
        headlineContentColor = palette.primaryText,
        weekdayContentColor = palette.secondaryText,
        subheadContentColor = palette.primaryText,
        navigationContentColor = palette.accent,
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
        todayContentColor = palette.primaryText,
        todayDateBorderColor = if (marksTonight) palette.accent else Color.Transparent,
        dayInSelectionRangeContentColor = palette.onAccent,
        dayInSelectionRangeContainerColor = palette.accent,
        dividerColor = palette.stroke,
    )

    // Choosing a day applies it at once. (Choosing the day that is already selected leaves the state unchanged: "Done".)
    LaunchedEffect(state, timeZone) {
        snapshotFlow { state.selectedDateMillis }.drop(1).collect { millis ->
            if (millis != null) {
                currentOnSelect(TonightNightChoice.Date(TonightCalculator.localNoon(utcDay(millis), timeZone)))
                currentOnDismiss()
            }
        }
    }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Choose a Night",
                color = palette.primaryText,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            TonightTextAction("Done", onDismiss)
        }
        DatePicker(
            state = state,
            modifier = Modifier.fillMaxWidth(),
            colors = colors,
            title = null,
            headline = null,
            showModeToggle = false,
        )
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            TonightNote(
                "A night is named after the evening it starts on and runs from noon to noon.",
                style = TonightNoteStyle.FOOTNOTE,
            )
            LodestarButton(
                "Tonight",
                onClick = {
                    onSelect(TonightNightChoice.Tonight)
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
                icon = Icons.Outlined.NightsStay,
            )
        }
    }
}

/** The UTC midnight that Material's date picker uses for [day]. */
private fun utcMillis(day: LocalDate): Long = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

/** The calendar day of a UTC-midnight timestamp from Material's date picker. */
private fun utcDay(utcTimeMillis: Long): LocalDate = Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate()
