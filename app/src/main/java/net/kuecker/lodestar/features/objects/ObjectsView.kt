package net.kuecker.lodestar.features.objects

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.kuecker.lodestar.kit.catalog.Catalog
import net.kuecker.lodestar.kit.catalog.CelestialObject
import net.kuecker.lodestar.services.CatalogService
import net.kuecker.lodestar.shared.EmptyStateView
import net.kuecker.lodestar.shared.LocalAppModel
import net.kuecker.lodestar.shared.LodestarButton
import net.kuecker.lodestar.shared.LodestarButtonKind
import net.kuecker.lodestar.shared.NavStack
import net.kuecker.lodestar.shared.NavStackHost
import net.kuecker.lodestar.shared.rememberRetained
import net.kuecker.lodestar.theme.LocalPalette
import java.time.ZoneId

/** Where the Objects tab is: the browse list or the detail of one object. */
internal sealed interface ObjectsRoute {
    data object Browse : ObjectsRoute

    data class Detail(val obj: CelestialObject) : ObjectsRoute
}

/** The latest catalog search and the query it answers. */
internal class ObjectsSearchOutcome(val query: String, val objects: List<CelestialObject>) {
    companion object {
        val EMPTY = ObjectsSearchOutcome("", emptyList())
    }
}

/** The browse index of one catalog. */
internal class ObjectsIndexedCatalog(val catalog: Catalog, val index: ObjectsCatalogIndex)

/**
 * The browse list's state that outlives the screen's composition (kept with `rememberRetained("objects.browse")`):
 * what was typed, what it found, the sorted catalog index, the tracked sky positions and where the list was
 * scrolled to. It holds no UI objects.
 */
internal class ObjectsBrowseState {
    var query by mutableStateOf("")
    var outcome by mutableStateOf(ObjectsSearchOutcome.EMPTY)
    var indexed by mutableStateOf<ObjectsIndexedCatalog?>(null)
    val positions = ObjectsPositionModel()
    var scrollIndex = 0
    var scrollOffset = 0

    val trimmedQuery: String get() = query.trim()
}

/**
 * The Objects tab: browse and search everything Lodestar can aim at, see where it is in the sky now, and choose a
 * target. Rows open the object's detail; swipes favorite an object or aim at it.
 */
@Composable
fun ObjectsScreen() {
    val stack = rememberRetained("objects.nav") { NavStack<ObjectsRoute>(ObjectsRoute.Browse) }
    val browse = rememberRetained("objects.browse") { ObjectsBrowseState() }
    val options = rememberObjectsOptions()
    ObjectsBackgroundWork(browse)
    NavStackHost(stack, Modifier.fillMaxSize()) { route ->
        when (route) {
            ObjectsRoute.Browse -> ObjectsBrowse(browse, options) { stack.push(ObjectsRoute.Detail(it)) }
            is ObjectsRoute.Detail -> ObjectDetailScreen(route.obj, onBack = { stack.pop() })
        }
    }
}

/** The most search results shown. */
private const val SEARCH_RESULT_LIMIT = 50

/** How long typing must pause before the catalog is searched. */
private const val SEARCH_DEBOUNCE_MS = 150L

/**
 * The work behind the Objects tab, which keeps running while an object's detail is open: loading the catalog,
 * indexing it, searching it as the query changes, and keeping the sky positions of the listed objects fresh.
 */
@Composable
private fun ObjectsBackgroundWork(browse: ObjectsBrowseState) {
    val app = LocalAppModel.current
    val catalog = app.catalog.catalog
    val settings = app.settings
    val observer = app.observerContext?.observer
    val isForeground = app.isForeground
    val trimmedQuery = browse.trimmedQuery

    LaunchedEffect(Unit) { app.catalog.load() }

    LaunchedEffect(catalog) {
        if (catalog == null) {
            browse.indexed = null
        } else if (browse.indexed?.catalog !== catalog) {
            val index = withContext(Dispatchers.Default) { ObjectsCatalogIndex(catalog) }
            browse.indexed = ObjectsIndexedCatalog(catalog, index)
        }
    }

    // Searches the catalog once typing pauses; clearing the field shows the browse list at once.
    LaunchedEffect(trimmedQuery, catalog != null) {
        if (trimmedQuery.isEmpty()) {
            browse.outcome = ObjectsSearchOutcome.EMPTY
            return@LaunchedEffect
        }
        delay(SEARCH_DEBOUNCE_MS)
        val results = withContext(Dispatchers.Default) {
            app.catalog.search(trimmedQuery, SEARCH_RESULT_LIMIT).map { it.obj }.distinctBy { it.id }
        }
        browse.outcome = ObjectsSearchOutcome(trimmedQuery, results)
    }

    // Favorites, recents and search results that the browse listing does not include are tracked in addition to
    // the listed objects.
    val outcome = browse.outcome
    val extraObjects = remember(catalog, settings.favoriteObjectIDs, settings.recentObjectIDs, outcome) {
        offListObjects(catalog, settings.favoriteObjectIDs, settings.recentObjectIDs, outcome.objects)
    }
    val extraIDs = extraObjects.map { it.id }
    LaunchedEffect(observer, catalog, extraIDs, isForeground) {
        if (!isForeground) return@LaunchedEffect
        if (observer == null) {
            browse.positions.clear()
            return@LaunchedEffect
        }
        val listed = catalog?.listedObjects ?: emptyList()
        browse.positions.keepFresh(observer, app.clock) { listed + extraObjects }
    }
}

/**
 * Favorites, recents and search results that the browse listing does not include (stars that are only found by
 * search), without repeats.
 */
private fun offListObjects(
    catalog: Catalog?,
    favoriteIDs: List<String>,
    recentIDs: List<String>,
    results: List<CelestialObject>,
): List<CelestialObject> {
    if (catalog == null) return emptyList()
    val candidates = (favoriteIDs + recentIDs).mapNotNull { catalog.objectWithID(it) } + results
    val seen = HashSet<String>()
    return candidates.filter { !it.isListed && seen.add(it.id) }
}

// Browse screen

/**
 * The browse list under its top bar (custom target button, options menu) and search field; the catalog's loading and
 * failure states take the list's place. [onOpen] shows an object's detail.
 */
@Composable
private fun ObjectsBrowse(browse: ObjectsBrowseState, options: ObjectsOptionsState, onOpen: (CelestialObject) -> Unit) {
    val app = LocalAppModel.current
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    var isShowingCustomTarget by rememberSaveable { mutableStateOf(false) }
    val catalog = app.catalog.catalog
    val indexed = browse.indexed?.takeIf { it.catalog === catalog }
    val loadState = app.catalog.loadState

    Column(Modifier.fillMaxSize().imePadding()) {
        ObjectsTopBar("Objects") {
            IconButton(onClick = { isShowingCustomTarget = true }) {
                Icon(Icons.Outlined.Add, contentDescription = "Custom Target", tint = palette.accent)
            }
            ObjectsOptionsMenu(
                options = options.options,
                onOptionsChange = { next -> options.update { next } },
                hasLocation = app.observerContext != null,
                enabled = browse.trimmedQuery.isEmpty(),
            )
        }
        ObjectsSearchField(browse.query, onQueryChange = { browse.query = it })
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                catalog != null && indexed != null -> ObjectsList(
                    browse = browse,
                    options = options.options,
                    onOptionsChange = { next -> options.update { next } },
                    catalog = catalog,
                    index = indexed.index,
                    onOpen = onOpen,
                    onAddCustomTarget = { isShowingCustomTarget = true },
                )
                loadState is CatalogService.LoadState.Failed -> EmptyStateView(
                    title = "Catalog Unavailable",
                    icon = Icons.Outlined.Warning,
                    message = loadState.message,
                ) {
                    LodestarButton("Try Again", onClick = { scope.launch { app.catalog.load() } })
                }
                else -> ObjectsLoadingView()
            }
        }
    }
    if (isShowingCustomTarget) CustomTargetSheet(onDismiss = { isShowingCustomTarget = false })
}

/** The search field under the top bar, always visible (the iOS drawer search bar). */
@Composable
private fun ObjectsSearchField(query: String, onQueryChange: (String) -> Unit) {
    val palette = LocalPalette.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .heightIn(min = 48.dp)
            .background(palette.surface, shape)
            .border(0.5.dp, palette.stroke, shape)
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Search,
            contentDescription = null,
            tint = palette.tertiaryText,
            modifier = Modifier.size(20.dp),
        )
        Box(Modifier.weight(1f).padding(start = 8.dp), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                Text("Name, M31, NGC 7000", color = palette.tertiaryText, style = MaterialTheme.typography.bodyLarge)
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
                keyboardActions = KeyboardActions(
                    onSearch = {
                        keyboard?.hide()
                        focus.clearFocus()
                    },
                ),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Search objects" },
            )
        }
        if (query.isNotEmpty()) {
            IconButton(onClick = { onQueryChange("") }) {
                Icon(Icons.Outlined.Close, contentDescription = "Clear search", tint = palette.secondaryText)
            }
        } else {
            Spacer(Modifier.width(12.dp))
        }
    }
}

/** The sections of rows, or the empty state when nothing is left to show. */
@Composable
private fun ObjectsList(
    browse: ObjectsBrowseState,
    options: ObjectsListOptions,
    onOptionsChange: (ObjectsListOptions) -> Unit,
    catalog: Catalog,
    index: ObjectsCatalogIndex,
    onOpen: (CelestialObject) -> Unit,
    onAddCustomTarget: () -> Unit,
) {
    val app = LocalAppModel.current
    val settings = app.settings
    val observerContext = app.observerContext
    val zone = observerContext?.timeZone ?: ZoneId.systemDefault()
    val outcome = browse.outcome
    val isShowingResults = outcome.query.isNotEmpty()
    val positions = browse.positions
    val positionMap = positions.positions
    val sections = remember(outcome, options, catalog, index, settings.favoriteObjectIDs, settings.recentObjectIDs, positionMap) {
        if (outcome.query.isNotEmpty()) {
            if (outcome.objects.isEmpty()) {
                emptyList()
            } else {
                listOf(ObjectsListSection(ObjectsListSection.Kind.Results, outcome.objects))
            }
        } else {
            ObjectsListBuilder.sections(
                options, catalog, index, settings.favoriteObjectIDs, settings.recentObjectIDs, positionMap,
            )
        }
    }
    val favorites = remember(settings.favoriteObjectIDs) { settings.favoriteObjectIDs.toHashSet() }

    // The list keeps its place while an object's detail is open and starts at the top for every new search.
    val listState = rememberLazyListState(browse.scrollIndex, browse.scrollOffset)
    DisposableEffect(listState) {
        onDispose {
            browse.scrollIndex = listState.firstVisibleItemIndex
            browse.scrollOffset = listState.firstVisibleItemScrollOffset
        }
    }
    val firstOutcome = remember { outcome }
    LaunchedEffect(outcome) {
        if (outcome !== firstOutcome) listState.scrollToItem(0)
    }

    // Dragging the list puts the keyboard away.
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val dismissKeyboard = remember(keyboard, focus) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput) {
                    keyboard?.hide()
                    focus.clearFocus()
                }
                return Offset.Zero
            }
        }
    }

    Box(Modifier.fillMaxSize().nestedScroll(dismissKeyboard)) {
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 16.dp)) {
            if (observerContext == null && !isShowingResults) {
                item(key = "location-notice") { ObjectsLocationNotice() }
            }
            for (section in sections) {
                stickyHeader(key = "header/${section.id}") {
                    ObjectsSectionHeader(section.title, section.icon, section.objects.size)
                }
                val lastIndex = section.objects.lastIndex
                itemsIndexed(section.objects, key = { _, obj -> "${section.id}/${obj.id}" }) { itemIndex, obj ->
                    ObjectsSwipeRow(
                        obj = obj,
                        position = positions.position(obj.id),
                        isFavorite = obj.id in favorites,
                        zone = zone,
                        shape = rowShape(isFirst = itemIndex == 0, isLast = itemIndex == lastIndex),
                        showsDivider = itemIndex != lastIndex,
                        onOpen = { onOpen(obj) },
                        onToggleFavorite = { app.settingsStore.toggleFavorite(obj.id) },
                        onAim = { app.aim(obj) },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
        }
        if (sections.isEmpty()) {
            if (isShowingResults) {
                EmptyStateView(
                    title = "No Results",
                    icon = Icons.Outlined.Search,
                    message = "Nothing in the catalog matches “${outcome.query}”. " +
                        "You can aim at any position with a custom target.",
                ) {
                    LodestarButton("Add Custom Target", onClick = onAddCustomTarget, kind = LodestarButtonKind.SECONDARY)
                }
            } else {
                EmptyStateView(
                    title = "Nothing to Show",
                    icon = Icons.Outlined.FilterList,
                    message = "No objects match the current filters.",
                ) {
                    LodestarButton(
                        "Reset Filters",
                        onClick = {
                            onOptionsChange(options.copy(showsOnlyAboveHorizon = false, hidesFaintObjects = false))
                        },
                        kind = LodestarButtonKind.SECONDARY,
                    )
                }
            }
        }
    }
}

// Rows of a section form one rounded block, like an inset grouped list.
private val RoundedTop = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
private val RoundedBottom = RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)
private val RoundedAll = RoundedCornerShape(12.dp)
private val Square = RoundedCornerShape(0.dp)

private fun rowShape(isFirst: Boolean, isLast: Boolean): Shape = when {
    isFirst && isLast -> RoundedAll
    isFirst -> RoundedTop
    isLast -> RoundedBottom
    else -> Square
}
