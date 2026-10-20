package net.kuecker.lodestar.features.objects

import net.kuecker.lodestar.features.smallbodies.SmallBodyText
import net.kuecker.lodestar.kit.catalog.CelestialObject
import net.kuecker.lodestar.kit.formatting.AngleFormatter
import net.kuecker.lodestar.shared.AppFormat
import java.text.Normalizer
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Text for object rows: names, one-line descriptions, positions and rising times. */
internal object ObjectsFormat {
    private val combiningMarks = Regex("\\p{M}+")

    /** The first designation of an object when it differs from its name, such as "M 31" for the Andromeda Galaxy. */
    fun extraDesignation(obj: CelestialObject): String? =
        obj.designations.firstOrNull()?.takeUnless { isName(obj, it) }

    /**
     * The first few designations that differ from the object's name, such as "M 31 · NGC 224" or, for a deep-sky
     * object in several catalogues, "NGC 7000 · Caldwell 20 · Sh2-117"; `null` when there are none.
     */
    fun designationsLine(obj: CelestialObject): String? {
        val others = obj.designations.filterNot { isName(obj, it) }
        return if (others.isEmpty()) null else others.take(5).joinToString(" · ")
    }

    /**
     * The catalogue numbers a list row shows beside the name, at most two, such as "C 9 · Sh2-155" for the Cave
     * Nebula or "M 31 · NGC 224" for the Andromeda Galaxy; objects with no catalogue numbers show their first other
     * designation, if any.
     */
    fun rowDesignations(obj: CelestialObject): String? {
        val labels = obj.catalogNumbers.map { it.label }.filterNot { isName(obj, it) }.distinct()
        if (labels.isNotEmpty()) return labels.take(2).joinToString(" · ")
        return extraDesignation(obj)
    }

    /** Whether [designation] is the object's name, ignoring case and diacritics. */
    private fun isName(obj: CelestialObject, designation: String): Boolean = folded(designation) == folded(obj.name)

    private fun folded(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(combiningMarks, "").lowercase(Locale.ROOT)

    /**
     * Type, constellation and magnitude on one line, such as "Galaxy · Andromeda · mag 3.4".
     *
     * [magnitude] overrides the catalog magnitude for objects whose brightness changes. With [spoken] the parts are
     * separated by commas and "magnitude" is written out.
     */
    fun subtitle(obj: CelestialObject, magnitude: Double?, spoken: Boolean = false, position: ObjectSkyPosition? = null): String {
        // A comet or an asteroid: kind, magnitude estimate, elongation and warnings.
        if (obj.smallBody != null) return SmallBodyText.subtitle(obj, position?.smallBody, position != null, spoken)
        val parts = mutableListOf(obj.category.displayName)
        obj.constellationName?.let(parts::add)
        (magnitude ?: obj.magnitude)?.let { value ->
            val text = AppFormat.magnitude(value)
            parts.add(if (spoken) text.replace("mag ", "magnitude ") else text)
        }
        return parts.joinToString(if (spoken) ", " else " · ")
    }

    /**
     * [subtitle] for a list row: each part ("mag 3.4", "62° from the Sun") stays whole and the dot stays with the part
     * before it, so a narrow row wraps between the parts; a comet or an asteroid takes two lines.
     */
    fun rowSubtitle(obj: CelestialObject, magnitude: Double?, position: ObjectSkyPosition? = null): String {
        if (obj.smallBody != null) return SmallBodyText.rowSubtitle(obj, position?.smallBody, position != null)
        return AppFormat.unbreakableParts(subtitle(obj, magnitude, position = position))
    }

    /** Altitude and compass direction of an object above the horizon, such as "↑34° SE". */
    fun altitudeAndDirection(position: ObjectSkyPosition): String {
        val altitude = AngleFormatter.formatDegrees(position.altitude, 0)
        val direction = AngleFormatter.compassPoint(position.azimuth).abbreviation
        return "↑$altitude $direction"
    }

    /**
     * When an object below the horizon next rises, such as "Rises 21:34" or "Rises tomorrow 02:10"; `null` when no
     * rising is known.
     */
    fun risingText(position: ObjectSkyPosition, zone: ZoneId): String? {
        if (position.neverRises) return "Never rises here"
        if (position.isBlockedBySkyline) return "Hidden by your skyline"
        val rise = position.nextRise ?: return null
        val verb = if (position.usesSkyline) "Clears skyline" else "Rises"
        val days = ChronoUnit.DAYS.between(
            position.computedAt.atZone(zone).toLocalDate(),
            rise.atZone(zone).toLocalDate(),
        )
        // A narrow row wraps the text, but never between a time and its AM or PM.
        val time = AppFormat.unbreakable(AppFormat.time(rise, zone))
        return when {
            days < 1 -> "$verb $time"
            days == 1L -> "$verb tomorrow $time"
            else -> "$verb ${AppFormat.dateTime(rise, zone)}"
        }
    }

    /** The row's position as a spoken phrase, such as "34° above the horizon, southeast". */
    private fun spokenPosition(position: ObjectSkyPosition, zone: ZoneId): String {
        if (position.isUp) {
            val altitude = AngleFormatter.formatDegrees(position.altitude, 0)
            val direction = AngleFormatter.compassPoint(position.azimuth).fullName.lowercase(Locale.ROOT)
            return "$altitude above the horizon, $direction"
        }
        val state = if (position.isBehindSkyline) "Behind your skyline" else "Below the horizon"
        val rising = risingText(position, zone) ?: return state
        return "$state, ${rising.lowercase(Locale.ROOT)}"
    }

    /** Everything a row shows, as one screen-reader label. */
    fun spokenSummary(obj: CelestialObject, position: ObjectSkyPosition?, isFavorite: Boolean, zone: ZoneId): String {
        val parts = mutableListOf(SmallBodyText.displayName(obj))
        rowDesignations(obj)?.let(parts::add)
        parts.add(subtitle(obj, position?.magnitude, spoken = true, position = position))
        if (position != null) parts.add(spokenPosition(position, zone))
        if (isFavorite) parts.add("Favorite")
        return parts.joinToString(", ")
    }
}
