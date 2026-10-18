package net.kuecker.lodestar.features.aim

import net.kuecker.lodestar.shared.LocalSheetDismiss
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import net.kuecker.lodestar.app.AppTab
import net.kuecker.lodestar.kit.catalog.CelestialObject
import net.kuecker.lodestar.shared.LocalAppModel
import net.kuecker.lodestar.shared.LodestarButton
import net.kuecker.lodestar.shared.LodestarButtonKind
import net.kuecker.lodestar.shared.LodestarSheet
import net.kuecker.lodestar.shared.ObjectIcon
import net.kuecker.lodestar.theme.LocalPalette

/** Longest search text accepted, in characters. */
private const val MAXIMUM_QUERY_LENGTH = 64
private const val MAXIMUM_RESULTS = 30
private const val MAXIMUM_SHORTCUTS = 6

/** Pause after the last keystroke before the catalog is searched, so typing does not search for every letter. */
private const val SEARCH_DELAY_MS = 150L

/** The share of the screen the sheet fills (iOS offers a medium and a large detent; this sheet keeps the large one). */
private const val SHEET_HEIGHT_SHARE = 0.85f

/** Chooses what to aim at: quick picks, favorites and recent targets, and a search over the whole catalog. */
@Composable
internal fun AimTargetPickerSheet(model: AimViewModel, onDismiss: () -> Unit) {
    LodestarSheet(onDismiss = onDismiss) {
        PickerContent(model, LocalSheetDismiss.current)
    }
}

@Composable
private fun PickerContent(model: AimViewModel, onDismiss: () -> Unit) {
    val app = LocalAppModel.current
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<CelestialObject>>(emptyList()) }
    // The text `results` were found for, so "no matches" is not claimed for a text that has not been searched yet.
    var searchedText by remember { mutableStateOf("") }
    val trimmedQuery = query.trim()
    val isSearching = trimmedQuery.isNotEmpty()

    LaunchedEffect(trimmedQuery) {
        if (trimmedQuery.isEmpty()) {
            results = emptyList()
            searchedText = ""
            return@LaunchedEffect
        }
        delay(SEARCH_DELAY_MS)
        val found = withContext(Dispatchers.Default) { app.catalog.search(trimmedQuery, MAXIMUM_RESULTS).map { it.obj } }
        results = found
        searchedText = trimmedQuery
    }

    fun choose(obj: CelestialObject) {
        app.select(obj)
        onDismiss()
    }

    Column(Modifier.fillMaxWidth().imePadding().fillMaxHeight(SHEET_HEIGHT_SHARE)) {
        PickerHeader(onDismiss)
        SearchField(query, onQueryChange = { query = it.limitedTo(MAXIMUM_QUERY_LENGTH) })
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (isSearching) {
                if (results.isNotEmpty()) {
                    items(results, key = { it.id }) { obj -> ObjectRow(obj, ::choose) }
                } else if (searchedText == trimmedQuery) {
                    item {
                        Text(
                            "No matches for “$trimmedQuery”",
                            color = LocalPalette.current.secondaryText,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }
            } else {
                shortcuts(model, ::choose, onDismiss)
            }
        }
    }
}

@Composable
private fun PickerHeader(onDismiss: () -> Unit) {
    val palette = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Aim at",
            color = palette.primaryText,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        LodestarButton("Done", onClick = onDismiss, kind = LodestarButtonKind.SECONDARY)
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    val palette = LocalPalette.current
    val focus = LocalFocusManager.current
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .background(palette.surface, shape)
            .border(0.5.dp, palette.stroke, shape)
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Outlined.Search, contentDescription = null, tint = palette.tertiaryText, modifier = Modifier.size(20.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                Text(
                    "Search stars, planets, Messier, NGC…",
                    color = palette.tertiaryText,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = palette.primaryText),
                cursorBrush = SolidColor(palette.accent),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Search,
                ),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Search objects" },
            )
        }
        if (query.isNotEmpty()) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .clickable(role = Role.Button, onClickLabel = "Clear search") { onQueryChange("") },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Cancel,
                    contentDescription = "Clear search",
                    tint = palette.tertiaryText,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/** The list shown while nothing is typed: quick picks, favorites, recents and ways to go elsewhere. */
private fun LazyListScope.shortcuts(
    model: AimViewModel,
    choose: (CelestialObject) -> Unit,
    onDismiss: () -> Unit,
) {
    item(key = "quick-title") { SectionTitle("Quick picks") }
    item(key = "quick-picks") { QuickPickFlow(model, choose) }
    item(key = "shortcut-sections") { ShortcutSections(choose, onDismiss) }
}

@Composable
private fun QuickPickFlow(model: AimViewModel, choose: (CelestialObject) -> Unit) {
    val app = LocalAppModel.current
    val snapshot = model.skyContent?.snapshot
    val picks = remember(app.catalog.catalog, snapshot) { AimQuickPicks.picks(app.catalog, snapshot, planetLimit = 5) }
    AimFlowLayout(Modifier.fillMaxWidth(), spacing = 8.dp, lineSpacing = 8.dp) {
        for (pick in picks) {
            key(pick.id) { AimQuickPickButton(pick, onClick = { choose(pick.obj) }) }
        }
    }
}

/** Favorites, recent targets and the buttons for browsing and clearing, below the quick picks. */
@Composable
private fun ShortcutSections(choose: (CelestialObject) -> Unit, onDismiss: () -> Unit) {
    val app = LocalAppModel.current
    fun objects(identifiers: List<String>): List<CelestialObject> =
        identifiers.mapNotNull { app.catalog.objectWithID(it) }.take(MAXIMUM_SHORTCUTS)

    val favorites = objects(app.settings.favoriteObjectIDs)
    val recents = objects(app.settings.recentObjectIDs).filter { recent -> favorites.none { it.id == recent.id } }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (favorites.isNotEmpty()) {
            SectionTitle("Favorites")
            for (obj in favorites) key(obj.id) { ObjectRow(obj, choose) }
        }
        if (recents.isNotEmpty()) {
            SectionTitle("Recent")
            for (obj in recents) key(obj.id) { ObjectRow(obj, choose) }
        }
        SectionTitle("More")
        LodestarButton(
            "Browse all objects",
            onClick = {
                app.selectedTab = AppTab.OBJECTS
                onDismiss()
            },
            kind = LodestarButtonKind.SECONDARY,
            icon = Icons.Outlined.AutoAwesome,
        )
        if (app.target != null) {
            LodestarButton(
                "Clear target",
                onClick = {
                    app.clearTarget()
                    onDismiss()
                },
                kind = LodestarButtonKind.SECONDARY,
                icon = Icons.Outlined.Close,
            )
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        title.uppercase(),
        color = LocalPalette.current.secondaryText,
        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
        modifier = Modifier.padding(top = 8.dp).semantics { heading() },
    )
}

/** An object as a row: icon, name, kind and a check mark while it is the current target. */
@Composable
private fun ObjectRow(obj: CelestialObject, onChoose: (CelestialObject) -> Unit) {
    val app = LocalAppModel.current
    val palette = LocalPalette.current
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(palette.surface, shape)
            .clickable(role = Role.Button) { onChoose(obj) }
            .semantics(mergeDescendants = true) {}
            .heightIn(min = 44.dp)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ObjectIcon(obj.category)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                obj.name,
                color = palette.primaryText,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
            )
            Text(obj.subtitle, color = palette.secondaryText, style = MaterialTheme.typography.labelMedium)
        }
        if (app.target?.id == obj.id) {
            Icon(Icons.Filled.Check, contentDescription = "Current target", tint = palette.accent, modifier = Modifier.size(20.dp))
        }
    }
}

/** The text cut to at most [maximum] characters (Unicode code points, so no character is split). */
private fun String.limitedTo(maximum: Int): String {
    if (codePointCount(0, length) <= maximum) return this
    return substring(0, offsetByCodePoints(0, maximum))
}
