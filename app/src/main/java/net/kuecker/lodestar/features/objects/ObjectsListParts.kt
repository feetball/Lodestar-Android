package net.kuecker.lodestar.features.objects

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.GpsFixed
import androidx.compose.material.icons.outlined.LocationOff
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Terrain
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import net.kuecker.lodestar.app.AppTab
import net.kuecker.lodestar.kit.catalog.CelestialObject
import net.kuecker.lodestar.shared.LocalAppModel
import net.kuecker.lodestar.shared.monospacedDigits
import net.kuecker.lodestar.theme.LocalPalette
import java.text.NumberFormat
import java.time.ZoneId

/**
 * The top bar of the Objects screens: an optional back button, the title and actions at the end. Drawn with palette
 * colours (the Android counterpart of the navigation bar).
 */
@Composable
internal fun ObjectsTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val palette = LocalPalette.current
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = palette.accent)
            }
        } else {
            Spacer(Modifier.width(12.dp))
        }
        Text(
            title,
            color = palette.primaryText,
            style = if (onBack == null) {
                MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
            } else {
                MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 4.dp).semantics { heading() },
        )
        actions()
    }
}

/** A section title with the number of objects in the section. */
@Composable
internal fun ObjectsSectionHeader(title: String, icon: ImageVector, count: Int, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    Row(
        modifier
            .fillMaxWidth()
            .background(palette.background)
            .padding(start = 32.dp, end = 32.dp, top = 12.dp, bottom = 6.dp)
            .semantics(mergeDescendants = true) {
                heading()
                contentDescription = "$title, $count ${if (count == 1) "object" else "objects"}"
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = palette.secondaryText, modifier = Modifier.size(15.dp))
        Text(
            title.uppercase(),
            color = palette.secondaryText,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp),
        )
        Text(
            NumberFormat.getIntegerInstance().format(count),
            color = palette.tertiaryText,
            style = MaterialTheme.typography.bodySmall.monospacedDigits(),
        )
    }
}

/**
 * The catalogue filter under the search field: one chip for each deep-sky catalogue (and "All"), exactly one chosen. A
 * catalogue lists every object numbered in it, in catalogue order.
 */
@Composable
internal fun ObjectsCatalogueChips(
    selected: ObjectsCatalogueFilter,
    onSelect: (ObjectsCatalogueFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .selectableGroup()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (filter in ObjectsCatalogueFilter.entries) {
            val isSelected = filter == selected
            Box(
                Modifier
                    .heightIn(min = 48.dp)
                    .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(filter) }),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    filter.title,
                    color = if (isSelected) palette.onAccent else palette.secondaryText,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    modifier = Modifier
                        .background(if (isSelected) palette.accent else palette.surface, CircleShape)
                        .border(0.75.dp, if (isSelected) palette.accent else palette.stroke, CircleShape)
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                )
            }
        }
    }
}

/** A one-line note that the recorded skyline, not a flat horizon, decides which objects are up. */
@Composable
internal fun ObjectsSkylineNote(modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 6.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Icons.Outlined.Terrain, contentDescription = null, tint = palette.secondaryText, modifier = Modifier.size(14.dp))
        Text(
            "Skyline applied: up now, rising and the horizon filter follow your recorded skyline.",
            color = palette.tertiaryText,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** Shown while the catalog loads. */
@Composable
internal fun ObjectsLoadingView(modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    Column(
        modifier.fillMaxSize().semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        CircularProgressIndicator(color = palette.accent, trackColor = palette.stroke)
        Text(
            "Loading the sky catalog…",
            color = palette.secondaryText,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** A list row explaining why altitudes and rising times are missing. */
@Composable
internal fun ObjectsLocationNotice(modifier: Modifier = Modifier) {
    val app = LocalAppModel.current
    val palette = LocalPalette.current
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .background(palette.surface, RoundedCornerShape(12.dp))
            .padding(start = 16.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Outlined.LocationOff, contentDescription = null, tint = palette.tertiaryText)
        Text(
            "Add a location to see where objects are in your sky.",
            color = palette.secondaryText,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        Box(
            Modifier
                .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                .clickable(role = Role.Button) { app.selectedTab = AppTab.SETTINGS }
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "Settings",
                color = palette.accent,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            )
        }
    }
}

/**
 * A browse-list row that reveals actions when swiped (iOS leading and trailing swipe actions): swiping towards the
 * end favorites or unfavorites the object, swiping towards the start aims at it. The action runs when a swipe
 * completes (far enough or fast enough) and the row then slides back instead of leaving the list. The same actions
 * are in the long-press menu and in the accessibility actions. The row is drawn in palette colours only, including
 * the revealed actions, and pressing it fills it with the raised surface colour.
 */
@Composable
internal fun ObjectsSwipeRow(
    obj: CelestialObject,
    position: ObjectSkyPosition?,
    isFavorite: Boolean,
    showsProminence: Boolean,
    zone: ZoneId,
    shape: Shape,
    showsDivider: Boolean,
    onOpen: () -> Unit,
    onToggleFavorite: () -> Unit,
    onAim: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val dismissState = rememberSwipeToDismissBoxState(positionalThreshold = { distance -> distance * SWIPE_FRACTION })
    val interaction = remember { MutableInteractionSource() }
    val isPressed by interaction.collectIsPressedAsState()
    var isMenuOpen by remember { mutableStateOf(false) }
    val favoriteLabel = if (isFavorite) "Remove from favorites" else "Add to favorites"
    val aimLabel = "Aim at ${obj.name}"

    SwipeToDismissBox(
        state = dismissState,
        onDismiss = { target ->
            when (target) {
                SwipeToDismissBoxValue.StartToEnd -> onToggleFavorite()
                SwipeToDismissBoxValue.EndToStart -> onAim()
                SwipeToDismissBoxValue.Settled -> Unit
            }
            // The row stays where it is: it slides back instead of leaving the list.
            scope.launch { dismissState.reset() }
        },
        backgroundContent = {
            val direction = dismissState.dismissDirection
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        when (direction) {
                            SwipeToDismissBoxValue.StartToEnd -> palette.surfaceRaised
                            SwipeToDismissBoxValue.EndToStart -> palette.accent
                            SwipeToDismissBoxValue.Settled -> Color.Transparent
                        },
                    )
                    .padding(horizontal = 24.dp),
                contentAlignment = if (direction == SwipeToDismissBoxValue.EndToStart) Alignment.CenterEnd else Alignment.CenterStart,
            ) {
                when (direction) {
                    SwipeToDismissBoxValue.StartToEnd ->
                        SwipeActionLabel(
                            icon = if (isFavorite) Icons.Outlined.StarBorder else Icons.Filled.Star,
                            text = if (isFavorite) "Unfavorite" else "Favorite",
                            tint = palette.accent,
                        )
                    SwipeToDismissBoxValue.EndToStart ->
                        SwipeActionLabel(icon = Icons.Outlined.GpsFixed, text = "Aim", tint = palette.onAccent)
                    SwipeToDismissBoxValue.Settled -> Unit
                }
            }
        },
        modifier = modifier.clip(shape),
    ) {
        Box {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(if (isPressed) palette.surfaceRaised else palette.surface)
                    .drawBehind {
                        if (showsDivider) {
                            val stroke = 0.5.dp.toPx()
                            drawLine(palette.stroke, Offset(16.dp.toPx(), size.height - stroke / 2), Offset(size.width, size.height - stroke / 2), stroke)
                        }
                    }
                    .combinedClickable(
                        interactionSource = interaction,
                        indication = null,
                        onClickLabel = "Show details",
                        role = Role.Button,
                        onLongClickLabel = "More actions",
                        onLongClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            isMenuOpen = true
                        },
                        onClick = onOpen,
                    )
                    .semantics {
                        customActions = listOf(
                            CustomAccessibilityAction(favoriteLabel) {
                                onToggleFavorite()
                                true
                            },
                            CustomAccessibilityAction(aimLabel) {
                                onAim()
                                true
                            },
                        )
                    }
                    .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ObjectRow(obj, Modifier.weight(1f), position, isFavorite, zone, showsProminence)
                // The disclosure chevron is drawn in a palette colour; the system's would be grey.
                Icon(
                    Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = null,
                    tint = palette.tertiaryText,
                    modifier = Modifier.size(20.dp),
                )
            }
            DropdownMenu(
                expanded = isMenuOpen,
                onDismissRequest = { isMenuOpen = false },
                containerColor = palette.surfaceRaised,
            ) {
                val colors = MenuDefaults.itemColors(
                    textColor = palette.primaryText,
                    leadingIconColor = palette.accent,
                )
                DropdownMenuItem(
                    text = { Text(favoriteLabel) },
                    leadingIcon = {
                        Icon(if (isFavorite) Icons.Outlined.StarBorder else Icons.Filled.Star, contentDescription = null)
                    },
                    colors = colors,
                    onClick = {
                        isMenuOpen = false
                        onToggleFavorite()
                    },
                )
                DropdownMenuItem(
                    text = { Text(aimLabel) },
                    leadingIcon = { Icon(Icons.Outlined.GpsFixed, contentDescription = null) },
                    colors = colors,
                    onClick = {
                        isMenuOpen = false
                        onAim()
                    },
                )
            }
        }
    }
}

/** The icon and caption revealed under a swiped row, in one fixed colour. */
@Composable
private fun SwipeActionLabel(icon: ImageVector, text: String, tint: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        Text(text, color = tint, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold))
    }
}

/** How far a row must be swiped, as a fraction of its width, before its action runs. */
private const val SWIPE_FRACTION = 0.4f
