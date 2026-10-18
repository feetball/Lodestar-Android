package net.kuecker.lodestar.features.objects

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Numbers
import androidx.compose.material.icons.outlined.Stars
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.VerticalAlignTop
import androidx.compose.material.icons.outlined.WbTwilight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit
import net.kuecker.lodestar.kit.catalog.CelestialObject
import net.kuecker.lodestar.kit.catalog.DeepSkyCatalogue
import net.kuecker.lodestar.shared.rememberRetained

/** How the Objects list divides its objects into sections. */
internal enum class ObjectsGrouping(val rawValue: String, val title: String, val icon: ImageVector) {
    /** One section per object type group (Solar System, Stars, Galaxies, …). */
    TYPE("type", "Object Type", Icons.Outlined.GridView),

    /** One section per prominence, from showpieces to obscure objects. */
    PROMINENCE("prominence", "Prominence", Icons.Outlined.Stars),

    /** A single section holding every object. */
    NONE("none", "No Grouping", Icons.AutoMirrored.Outlined.List),
    ;

    companion object {
        fun fromRawValue(raw: String?): ObjectsGrouping? = entries.firstOrNull { it.rawValue == raw }
    }
}

/** How the objects inside a section are ordered. */
internal enum class ObjectsSortOrder(val rawValue: String, val title: String, val icon: ImageVector) {
    NAME("name", "Name", Icons.Outlined.TextFields),

    /** The number in the chosen catalogue (only with a catalogue chosen). */
    NUMBER("number", "Catalogue Number", Icons.Outlined.Numbers),

    /** Showpieces first, then by brightness; dark nebulae, which have no magnitude, rank by prominence. */
    PROMINENCE("prominence", "Prominence", Icons.Outlined.Stars),
    BRIGHTNESS("brightness", "Brightness", Icons.Outlined.AutoAwesome),
    ALTITUDE("altitude", "Altitude Now", Icons.Outlined.VerticalAlignTop),
    RISE_TIME("riseTime", "Rise Time", Icons.Outlined.WbTwilight),
    ;

    /** `true` when the order needs each object's position in the observer's sky. */
    val needsSkyPositions: Boolean get() = this == ALTITUDE || this == RISE_TIME

    companion object {
        fun fromRawValue(raw: String?): ObjectsSortOrder? = entries.firstOrNull { it.rawValue == raw }
    }
}

/**
 * Which deep-sky catalogue the Objects list is narrowed to. A catalogue lists every object with a number in it, the
 * obscure ones too, by number.
 */
internal enum class ObjectsCatalogueFilter(
    val rawValue: String,
    val title: String,
    val catalogues: List<DeepSkyCatalogue>,
) {
    ALL("all", "All", emptyList()),
    MESSIER("messier", "Messier", listOf(DeepSkyCatalogue.MESSIER)),
    CALDWELL("caldwell", "Caldwell", listOf(DeepSkyCatalogue.CALDWELL)),
    SHARPLESS("sharpless", "Sharpless", listOf(DeepSkyCatalogue.SHARPLESS)),
    BARNARD("barnard", "Barnard", listOf(DeepSkyCatalogue.BARNARD)),
    LYNDS("lynds", "Lynds", listOf(DeepSkyCatalogue.LYNDS)),
    NGC_IC("ngcic", "NGC / IC", listOf(DeepSkyCatalogue.NGC, DeepSkyCatalogue.IC)),
    CLUSTERS(
        "clusters", "Open Clusters",
        listOf(DeepSkyCatalogue.COLLINDER, DeepSkyCatalogue.MELOTTE, DeepSkyCatalogue.TRUMPLER, DeepSkyCatalogue.STOCK),
    ),
    ;

    /** `true` for a real catalogue (everything but [ALL]). */
    val isCatalogue: Boolean get() = this != ALL

    /**
     * The sort key of [obj] in this catalogue: the catalogue's place in [catalogues], then the number, then the letter
     * (B 67 before B 67a); `null` when the object has no number in it.
     */
    fun numberKey(obj: CelestialObject): Double? {
        var best: Double? = null
        for (entry in obj.catalogNumbers) {
            val place = catalogues.indexOf(entry.catalogue)
            if (place < 0) continue
            val letter = entry.suffix.firstOrNull()?.lowercaseChar()?.let { (it - 'a' + 1).coerceIn(1, 26) } ?: 0
            val key = place * 1e9 + entry.number * 100.0 + letter
            if (best == null || key < best) best = key
        }
        return best
    }

    companion object {
        fun fromRawValue(raw: String?): ObjectsCatalogueFilter? = entries.firstOrNull { it.rawValue == raw }
    }
}

/** What the Objects list shows: how it is grouped and ordered and which quick filters are on. */
internal data class ObjectsListOptions(
    val grouping: ObjectsGrouping = ObjectsGrouping.TYPE,
    val sortOrder: ObjectsSortOrder = ObjectsSortOrder.NAME,
    val showsOnlyAboveHorizon: Boolean = false,
    val hidesFaintObjects: Boolean = false,
    val catalogue: ObjectsCatalogueFilter = ObjectsCatalogueFilter.ALL,
    /** Only showpieces and prominent objects. */
    val showsOnlyPopular: Boolean = false,
) {
    /** The options after choosing [next]: a catalogue sorts by number, going back to all sorts by name again. */
    fun choosingCatalogue(next: ObjectsCatalogueFilter): ObjectsListOptions = copy(
        catalogue = next,
        sortOrder = when {
            next.isCatalogue && sortOrder == ObjectsSortOrder.NAME -> ObjectsSortOrder.NUMBER
            !next.isCatalogue && sortOrder == ObjectsSortOrder.NUMBER -> ObjectsSortOrder.NAME
            else -> sortOrder
        },
    )

    /** `true` when any option differs from the default list (the toolbar menu highlights its icon). */
    val isCustomized: Boolean get() = this != ObjectsListOptions()

    companion object {
        /** Objects fainter than this magnitude are hidden by [hidesFaintObjects]. */
        const val FAINT_MAGNITUDE_LIMIT = 10.0
    }
}

/**
 * The list options the user chose (the iOS `@AppStorage("lodestar.objects.*")` values). The holder is kept for the
 * whole session with `rememberRetained("objects.options")`, and every change is also written to the app's
 * preferences under the iOS keys, so the choice survives a restart.
 */
internal class ObjectsOptionsState(context: Context) {
    private val preferences: SharedPreferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    /** The current options; change them with [update]. */
    var options by mutableStateOf(load())
        private set

    /** Replaces the options with `transform(options)` and stores them. */
    fun update(transform: (ObjectsListOptions) -> ObjectsListOptions) {
        val next = transform(options)
        if (next == options) return
        options = next
        preferences.edit {
            putString(GROUPING_KEY, next.grouping.rawValue)
            putString(SORT_ORDER_KEY, next.sortOrder.rawValue)
            putBoolean(ABOVE_HORIZON_KEY, next.showsOnlyAboveHorizon)
            putBoolean(HIDE_FAINT_KEY, next.hidesFaintObjects)
            putString(CATALOGUE_KEY, next.catalogue.rawValue)
            putBoolean(POPULAR_KEY, next.showsOnlyPopular)
        }
    }

    private fun load(): ObjectsListOptions {
        val catalogue = ObjectsCatalogueFilter.fromRawValue(preferences.getString(CATALOGUE_KEY, null))
            ?: ObjectsCatalogueFilter.ALL
        var sortOrder = ObjectsSortOrder.fromRawValue(preferences.getString(SORT_ORDER_KEY, null)) ?: ObjectsSortOrder.NAME
        // Catalogue number only means something inside a catalogue.
        if (sortOrder == ObjectsSortOrder.NUMBER && !catalogue.isCatalogue) sortOrder = ObjectsSortOrder.NAME
        return ObjectsListOptions(
            grouping = ObjectsGrouping.fromRawValue(preferences.getString(GROUPING_KEY, null)) ?: ObjectsGrouping.TYPE,
            sortOrder = sortOrder,
            showsOnlyAboveHorizon = preferences.getBoolean(ABOVE_HORIZON_KEY, false),
            hidesFaintObjects = preferences.getBoolean(HIDE_FAINT_KEY, false),
            catalogue = catalogue,
            showsOnlyPopular = preferences.getBoolean(POPULAR_KEY, false),
        )
    }

    private companion object {
        /** The app's preferences file (the one [net.kuecker.lodestar.app.AppModel] stores the settings in). */
        const val PREFERENCES_NAME = "lodestar"
        const val GROUPING_KEY = "lodestar.objects.grouping"
        const val SORT_ORDER_KEY = "lodestar.objects.sortOrder"
        const val ABOVE_HORIZON_KEY = "lodestar.objects.aboveHorizonOnly"
        const val HIDE_FAINT_KEY = "lodestar.objects.hideFaint"
        const val CATALOGUE_KEY = "lodestar.objects.catalogue"
        const val POPULAR_KEY = "lodestar.objects.popularOnly"
    }
}

/** The session-retained list options (see [ObjectsOptionsState]). */
@Composable
internal fun rememberObjectsOptions(): ObjectsOptionsState {
    val context = LocalContext.current.applicationContext
    return rememberRetained("objects.options") { ObjectsOptionsState(context) }
}
