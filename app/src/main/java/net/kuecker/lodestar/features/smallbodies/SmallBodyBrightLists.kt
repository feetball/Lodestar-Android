package net.kuecker.lodestar.features.smallbodies

import net.kuecker.lodestar.kit.catalog.Catalog
import net.kuecker.lodestar.kit.catalog.CelestialObject
import net.kuecker.lodestar.kit.comets.BrightSmallBody
import net.kuecker.lodestar.kit.comets.SmallBody
import net.kuecker.lodestar.kit.comets.SmallBodyKind
import net.kuecker.lodestar.kit.comets.SmallBodySelection
import net.kuecker.lodestar.kit.foundation.DateInterval
import java.time.Instant

/** The period a list of bright comets covers. */
internal enum class SmallBodyListPeriod(val title: String) {
    /** Bright at this moment. */
    NOW("Now"),

    /** Brightest at some point in the next 31 days, with the date it is brightest. */
    THIS_MONTH("This month"),
}

/** Which bodies a list shows. */
internal enum class SmallBodyListKind(val title: String, val kind: SmallBodyKind?) {
    COMETS("Comets", SmallBodyKind.COMET),
    ASTEROIDS("Asteroids", SmallBodyKind.ASTEROID),
    BOTH("Both", null),
    ;

    /** "comet", "asteroid" or "comet or asteroid", for the empty state. */
    val noun: String
        get() = when (this) {
            COMETS -> "comet"
            ASTEROIDS -> "asteroid"
            BOTH -> "comet or asteroid"
        }
}

/** A magnitude limit the lists offer, and what it is good for. */
internal enum class SmallBodyMagnitudeLimit(val value: Double, val title: String, val caption: String) {
    NAKED_EYE(6.0, "Mag 6", "Naked-eye brightness"),
    BINOCULARS(8.0, "Mag 8", "Binoculars or a wide-field camera"),
    CAMERA(10.0, "Mag 10", "A camera on a tripod"),
    ;

    companion object {
        val default: SmallBodyMagnitudeLimit = CAMERA
    }
}

/** One row of a list: the catalog object, how bright it gets and where it stands then. */
internal class SmallBodyListEntry(val obj: CelestialObject, val bright: BrightSmallBody) {
    /** The state at the moment of the peak: for [SmallBodyListPeriod.NOW] that is now. */
    val info: SmallBodyInfo = SmallBodyInfo.of(bright.peak)

    val id: String get() = obj.id
    val peakDate: Instant get() = bright.peakDate
}

/** Which comets and asteroids are bright now and this month (`SmallBodySelection`, 1–2 ms for the whole file). */
internal object SmallBodyBrightLists {
    /** The days "this month" covers. */
    const val MONTH_DAYS = 31

    /** The most rows a list holds. */
    const val MAXIMUM_COUNT = 50

    /** The comets and asteroids of [catalog]: the bodies the lists choose from. */
    fun bodies(catalog: Catalog): List<SmallBody> = catalog.smallBodies.mapNotNull { it.smallBody }

    /**
     * The bright bodies of [period] starting at [date], brightest first. A body is bright when it reaches
     * [limit] magnitude while at least 25° from the Sun.
     */
    fun select(
        bodies: List<SmallBody>,
        date: Instant,
        period: SmallBodyListPeriod,
        limit: Double,
        kind: SmallBodyListKind,
        maximumCount: Int = MAXIMUM_COUNT,
    ): List<BrightSmallBody> = when (period) {
        SmallBodyListPeriod.NOW ->
            SmallBodySelection.brightNow(bodies, date, limit, kind = kind.kind, maximumCount = maximumCount)
        SmallBodyListPeriod.THIS_MONTH -> SmallBodySelection.brightest(
            bodies, DateInterval(date, MONTH_DAYS * SECONDS_PER_DAY), limit, kind = kind.kind, maximumCount = maximumCount,
        )
    }

    /** [select] with the bodies of [catalog], each with its catalog object (a body the catalog lost is left out). */
    fun entries(
        catalog: Catalog,
        date: Instant,
        period: SmallBodyListPeriod,
        limit: Double,
        kind: SmallBodyListKind,
        maximumCount: Int = MAXIMUM_COUNT,
    ): List<SmallBodyListEntry> = select(bodies(catalog), date, period, limit, kind, maximumCount).mapNotNull { bright ->
        catalog.objectWithID(bright.body.id)?.let { SmallBodyListEntry(it, bright) }
    }

    /** "No comet is brighter than magnitude 10 right now. That is normal; bright comets are rare." */
    fun emptyMessage(period: SmallBodyListPeriod, limit: Double, kind: SmallBodyListKind): String {
        val timing = if (period == SmallBodyListPeriod.NOW) "right now" else "in the next $MONTH_DAYS days"
        val first = "No ${kind.noun} is brighter than magnitude ${limit.toInt()} $timing."
        return if (kind == SmallBodyListKind.ASTEROIDS) first else "$first That is normal; bright comets are rare."
    }

    private const val SECONDS_PER_DAY = 86_400.0
}
