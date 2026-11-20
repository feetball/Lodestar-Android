package net.kuecker.lodestar.features.alignmentfinder

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import net.kuecker.lodestar.features.smallbodies.SmallBodyText
import net.kuecker.lodestar.kit.catalog.CelestialObject
import net.kuecker.lodestar.shared.LocalAppModel
import net.kuecker.lodestar.shared.LocalSheetDismiss
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

/** The share of the screen the sheet fills. */
private const val SHEET_HEIGHT_SHARE = 0.85f

/**
 * Chooses the object the finder searches for: favorites and recent objects, and a search over the whole catalog.
 * Choosing an object never changes the Aim target.
 */
@Composable
internal fun AlignmentFinderObjectSheet(chosen: CelestialObject?, onChoose: (CelestialObject) -> Unit, onDismiss: () -> Unit) {
    LodestarSheet(onDismiss = onDismiss) { SheetContent(chosen, onChoose, LocalSheetDismiss.current) }
}

@Composable
private fun SheetContent(chosen: CelestialObject?, onChoose: (CelestialObject) -> Unit, onDismiss: () -> Unit) {
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
        onChoose(obj)
        onDismiss()
    }

    val favorites = remember(app.catalog.catalog, app.settings.favoriteObjectIDs) {
        app.settings.favoriteObjectIDs.mapNotNull { app.catalog.objectWithID(it) }.take(MAXIMUM_SHORTCUTS)
    }
    val recents = remember(app.catalog.catalog, app.settings.recentObjectIDs, favorites) {
        app.settings.recentObjectIDs.mapNotNull { app.catalog.objectWithID(it) }
            .filter { recent -> favorites.none { it.id == recent.id } }.take(MAXIMUM_SHORTCUTS)
    }

    Column(Modifier.fillMaxWidth().imePadding().fillMaxHeight(SHEET_HEIGHT_SHARE)) {
        SheetHeader(onDismiss)
        SearchField(query, onQueryChange = { query = it.limitedTo(MAXIMUM_QUERY_LENGTH) })
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (isSearching) {
                if (results.isNotEmpty()) {
                    items(results, key = { it.id }) { obj -> ObjectRow(obj, obj.id == chosen?.id, ::choose) }
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
                if (favorites.isNotEmpty()) {
                    item(key = "favorites-title") { SectionTitle("Favorites") }
                    items(favorites, key = { "favorite.${it.id}" }) { obj -> ObjectRow(obj, obj.id == chosen?.id, ::choose) }
                }
                if (recents.isNotEmpty()) {
                    item(key = "recents-title") { SectionTitle("Recent") }
                    items(recents, key = { "recent.${it.id}" }) { obj -> ObjectRow(obj, obj.id == chosen?.id, ::choose) }
                }
                if (favorites.isEmpty() && recents.isEmpty()) {
                    item {
                        Text(
                            "Search for a star, planet, comet, Messier or NGC object.",
                            color = LocalPalette.current.secondaryText,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetHeader(onDismiss: () -> Unit) {
    val palette = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Choose object",
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
                    "Search stars, planets, comets, Messier, NGC…",
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

@Composable
private fun SectionTitle(title: String) {
    Text(
        title.uppercase(),
        color = LocalPalette.current.secondaryText,
        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
        modifier = Modifier.padding(top = 8.dp).semantics { heading() },
    )
}

/** An object as a row: icon, name, kind and a check mark while it is the chosen one. */
@Composable
private fun ObjectRow(obj: CelestialObject, isChosen: Boolean, onChoose: (CelestialObject) -> Unit) {
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
                SmallBodyText.displayName(obj),
                color = palette.primaryText,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
            )
            Text(obj.subtitle, color = palette.secondaryText, style = MaterialTheme.typography.labelMedium)
        }
        if (isChosen) {
            Icon(Icons.Filled.Check, contentDescription = "Chosen object", tint = palette.accent, modifier = Modifier.size(20.dp))
        }
    }
}

/** The text cut to at most [maximum] characters (Unicode code points, so no character is split). */
private fun String.limitedTo(maximum: Int): String {
    if (codePointCount(0, length) <= maximum) return this
    return substring(0, offsetByCodePoints(0, maximum))
}
