package net.kuecker.lodestar.features.online

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Air
import androidx.compose.material.icons.outlined.BlurOn
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material.icons.outlined.WaterDrop
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import net.kuecker.lodestar.features.tonight.TonightNote
import net.kuecker.lodestar.features.tonight.TonightNoteStyle
import net.kuecker.lodestar.services.CloudSlot
import net.kuecker.lodestar.services.ForecastChipKind
import net.kuecker.lodestar.services.ForecastNight
import net.kuecker.lodestar.services.ForecastUnitSystem
import net.kuecker.lodestar.services.ForecastUnits
import net.kuecker.lodestar.services.ForecastWords
import net.kuecker.lodestar.services.NightHour
import net.kuecker.lodestar.shared.ChipTone
import net.kuecker.lodestar.shared.StatusChip
import net.kuecker.lodestar.shared.monospacedDigits
import net.kuecker.lodestar.theme.LocalPalette
import java.time.Instant

/** The words of the weather part of the "Clouds tonight" card. The iOS app uses the same texts. */
internal object CloudConditionsText {
    const val HOURLY = "Hourly"
    const val SHOW_HOURLY = "Show the hourly forecast"
    const val HIDE_HOURLY = "Hide the hourly forecast"
    const val DEW_GAP_NOTE = "Dew gap: how far the air temperature is above the dew point. Dew forms on a cold lens when it is small."
}

/**
 * The night's weather under the cloud strip: chips for dew, wind, fog and temperature (in the warning tone where they
 * matter), a line for the most important warning and the hourly table. Everything comes from [slots], the forecast cut
 * to the night; [timeText] formats a time of day.
 */
@Composable
internal fun CloudConditions(slots: List<CloudSlot>, timeText: (Instant) -> String) {
    // The locale comes from the configuration, so a change of the system's region redraws the card.
    val locale = LocalConfiguration.current.locales[0]
    val system = remember(locale) { ForecastUnits.systemOf(locale) }
    val conditions = ForecastNight.conditions(slots)
    val chips = ForecastWords.chips(conditions, system, timeText)
    if (chips.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (chip in chips) {
                Box(Modifier.clearAndSetSemantics { contentDescription = chip.spoken }) {
                    StatusChip(
                        icon = icon(chip.kind),
                        text = chip.text,
                        tone = if (chip.isWarning) ChipTone.WARNING else ChipTone.NEUTRAL,
                        maxLines = 2,
                    )
                }
            }
        }
    }
    ForecastWords.hint(conditions, timeText)?.let { TonightNote(it, style = TonightNoteStyle.WARNING) }
    CloudHourlyTable(conditions.hourly, system, timeText)
}

private fun icon(kind: ForecastChipKind): ImageVector = when (kind) {
    ForecastChipKind.DEW -> Icons.Outlined.WaterDrop
    ForecastChipKind.WIND -> Icons.Outlined.Air
    ForecastChipKind.FOG -> Icons.Outlined.BlurOn
    ForecastChipKind.TEMPERATURE -> Icons.Outlined.Thermostat
}

// The hourly table

private val COLUMN_WEIGHTS = listOf(1.15f, 0.9f, 1.1f, 1.25f, 1.1f)

/** "Hourly": a row that opens the table of time, cloud, temperature, dew gap and wind, one row per forecast slot. */
@Composable
private fun CloudHourlyTable(hours: List<NightHour>, system: ForecastUnitSystem, timeText: (Instant) -> String) {
    if (hours.isEmpty()) return
    val palette = LocalPalette.current
    var isExpanded by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .clickable(
                    onClickLabel = if (isExpanded) CloudConditionsText.HIDE_HOURLY else CloudConditionsText.SHOW_HOURLY,
                    role = Role.Button,
                ) { isExpanded = !isExpanded }
                .clearAndSetSemantics {
                    contentDescription = CloudConditionsText.HOURLY
                    stateDescription = if (isExpanded) "Expanded" else "Collapsed"
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                CloudConditionsText.HOURLY,
                color = palette.secondaryText,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (isExpanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                contentDescription = null,
                tint = palette.secondaryText,
                modifier = Modifier.size(22.dp),
            )
        }
        if (isExpanded) {
            TableRow(ForecastWords.hourlyHeaders(system), isHeader = true, description = null)
            for (hour in hours) {
                TableRow(
                    ForecastWords.hourlyCells(hour, system, timeText),
                    isHeader = false,
                    description = ForecastWords.hourlySpoken(hour, system, timeText),
                )
            }
            TonightNote(CloudConditionsText.DEW_GAP_NOTE, style = TonightNoteStyle.FOOTNOTE)
        }
    }
}

@Composable
private fun TableRow(cells: List<String>, isHeader: Boolean, description: String?) {
    val palette = LocalPalette.current
    val style = if (isHeader) {
        MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold)
    } else {
        MaterialTheme.typography.bodySmall.monospacedDigits()
    }
    Row(
        Modifier.fillMaxWidth().let { base ->
            if (description != null) base.clearAndSetSemantics { contentDescription = description } else base
        },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        for ((index, cell) in cells.withIndex()) {
            Text(
                cell,
                modifier = Modifier.weight(COLUMN_WEIGHTS[index]),
                color = if (isHeader) palette.tertiaryText else palette.primaryText,
                style = style,
                textAlign = if (index == 0) TextAlign.Start else TextAlign.End,
            )
        }
    }
}
