package net.kuecker.lodestar.features.objects

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material.icons.outlined.NorthEast
import androidx.compose.material.icons.outlined.Stars
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import net.kuecker.lodestar.theme.LocalPalette

/**
 * The top-bar menu that groups, orders and filters the Objects list. Its icon is filled when any option differs
 * from the default list; [hasLocation] enables the horizon filter, which needs an observer; [hasSkyline] says the
 * site has a recorded skyline, which the horizon filter then follows; [enabled] is off while a search is showing.
 */
@Composable
internal fun ObjectsOptionsMenu(
    options: ObjectsListOptions,
    onOptionsChange: (ObjectsListOptions) -> Unit,
    hasLocation: Boolean,
    hasSkyline: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    var isExpanded by remember { mutableStateOf(false) }
    fun choose(change: ObjectsListOptions) {
        isExpanded = false
        onOptionsChange(change)
    }
    Box(modifier) {
        IconButton(onClick = { isExpanded = true }, enabled = enabled) {
            Icon(
                if (options.isCustomized) Icons.Filled.FilterAlt else Icons.Outlined.FilterAlt,
                contentDescription = "Group, Sort and Filter",
                tint = if (enabled) palette.accent else palette.tertiaryText,
            )
        }
        DropdownMenu(
            expanded = isExpanded,
            onDismissRequest = { isExpanded = false },
            containerColor = palette.surfaceRaised,
        ) {
            MenuSectionTitle("Group By")
            for (option in ObjectsGrouping.entries) {
                MenuOption(option.title, option.icon, isChosen = options.grouping == option) {
                    choose(options.copy(grouping = option))
                }
            }
            HorizontalDivider(color = palette.stroke)
            MenuSectionTitle("Sort By")
            for (option in ObjectsSortOrder.entries) {
                MenuOption(
                    option.title,
                    option.icon,
                    isChosen = options.sortOrder == option,
                    // A catalogue number only orders a catalogue.
                    enabled = option != ObjectsSortOrder.NUMBER || options.catalogue.isCatalogue,
                ) {
                    choose(options.copy(sortOrder = option))
                }
            }
            HorizontalDivider(color = palette.stroke)
            MenuSectionTitle("Filters")
            MenuOption(
                if (hasSkyline) "Above Skyline Now" else "Above Horizon Now",
                Icons.Outlined.NorthEast,
                isChosen = options.showsOnlyAboveHorizon,
                isToggle = true,
                enabled = hasLocation,
            ) {
                choose(options.copy(showsOnlyAboveHorizon = !options.showsOnlyAboveHorizon))
            }
            MenuOption(
                "Popular Objects Only",
                Icons.Outlined.Stars,
                isChosen = options.showsOnlyPopular,
                isToggle = true,
            ) {
                choose(options.copy(showsOnlyPopular = !options.showsOnlyPopular))
            }
            MenuOption(
                "Hide Faint (mag > ${ObjectsListOptions.FAINT_MAGNITUDE_LIMIT.toInt()})",
                Icons.Outlined.VisibilityOff,
                isChosen = options.hidesFaintObjects,
                isToggle = true,
            ) {
                choose(options.copy(hidesFaintObjects = !options.hidesFaintObjects))
            }
        }
    }
}

@Composable
private fun MenuSectionTitle(title: String) {
    Text(
        title,
        color = LocalPalette.current.secondaryText,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp).semantics { heading() },
    )
}

/** One choice of the menu: a radio-style choice, or with [isToggle] a switch; the chosen one shows a check mark. */
@Composable
private fun MenuOption(
    title: String,
    icon: ImageVector,
    isChosen: Boolean,
    isToggle: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val palette = LocalPalette.current
    DropdownMenuItem(
        text = { Text(title) },
        onClick = onClick,
        leadingIcon = { Icon(icon, contentDescription = null) },
        trailingIcon = if (isChosen) {
            { Icon(Icons.Outlined.Check, contentDescription = null) }
        } else {
            null
        },
        enabled = enabled,
        colors = MenuDefaults.itemColors(
            textColor = if (isChosen) palette.accent else palette.primaryText,
            leadingIconColor = if (isChosen) palette.accent else palette.secondaryText,
            trailingIconColor = palette.accent,
            disabledTextColor = palette.tertiaryText,
            disabledLeadingIconColor = palette.tertiaryText,
            disabledTrailingIconColor = palette.tertiaryText,
        ),
        modifier = Modifier.semantics {
            stateDescription = when {
                isToggle -> if (isChosen) "On" else "Off"
                else -> if (isChosen) "Selected" else "Not selected"
            }
        },
    )
}
