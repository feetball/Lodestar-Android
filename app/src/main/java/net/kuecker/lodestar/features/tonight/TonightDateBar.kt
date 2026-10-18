package net.kuecker.lodestar.features.tonight

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.kuecker.lodestar.shared.LodestarButton
import net.kuecker.lodestar.shared.LodestarButtonKind
import net.kuecker.lodestar.theme.LocalPalette

/**
 * The night selector: previous and next night buttons around the date, which opens the calendar.
 *
 * @param title The date the night starts on, such as "Fri, Oct 17".
 * @param subtitle How far the night is from tonight, such as "Tomorrow night".
 */
@Composable
internal fun TonightDateBar(
    title: String,
    subtitle: String,
    canGoBack: Boolean,
    canGoForward: Boolean,
    goBack: () -> Unit,
    goForward: () -> Unit,
    pickDate: () -> Unit,
) {
    val palette = LocalPalette.current
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepButton(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, "Previous night", canGoBack, goBack)
        Column(
            Modifier
                .weight(1f)
                .clip(shape)
                .background(palette.surface, shape)
                .border(0.5.dp, palette.stroke, shape)
                .clickable(onClickLabel = "Open a calendar to choose another night", role = Role.Button, onClick = pickDate)
                .clearAndSetSemantics { contentDescription = "Night of $title, $subtitle" }
                .heightIn(min = 56.dp)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
        ) {
            Text(
                "Night of $title",
                color = palette.primaryText,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(
                    Icons.Outlined.CalendarMonth,
                    contentDescription = null,
                    tint = palette.secondaryText,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    subtitle,
                    color = palette.secondaryText,
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        StepButton(Icons.AutoMirrored.Outlined.KeyboardArrowRight, "Next night", canGoForward, goForward)
    }
}

@Composable
private fun StepButton(icon: ImageVector, label: String, isEnabled: Boolean, onClick: () -> Unit) {
    LodestarButton(onClick, kind = LodestarButtonKind.SECONDARY, enabled = isEnabled) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(24.dp))
    }
}
