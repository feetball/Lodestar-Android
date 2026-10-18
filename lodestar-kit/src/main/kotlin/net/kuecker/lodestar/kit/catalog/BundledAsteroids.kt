package net.kuecker.lodestar.kit.catalog

import net.kuecker.lodestar.kit.comets.MinorPlanetElementsParser
import net.kuecker.lodestar.kit.comets.SmallBody
import net.kuecker.lodestar.kit.foundation.JulianDate
import java.io.IOException
import java.time.Instant

/**
 * The bright asteroids that ship with the library, so that Ceres, Vesta, Pallas and the other asteroids a camera can
 * reach work offline: 216 asteroids as `SmallBody` values, ready for [Catalog.withSmallBodies].
 *
 * ```kotlin
 * val asteroids = BundledAsteroids.load()
 * val catalog = Catalog.loadBundled().withSmallBodies(asteroids.bodies + downloadedComets)
 * if (asteroids.ageDays(Instant.now()) > 365) { /* say "elements of ${asteroids.oldestEpoch}" */ }
 * ```
 *
 * **Content.** An extract of the Minor Planet Center's `MPCORB.DAT` (file of 2026-10-18; MPC one-line format, read by
 * [MinorPlanetElementsParser]): the asteroids with `H ≤ 10.5` whose best-case peak magnitude
 * ([net.kuecker.lodestar.kit.comets.SmallBodyBrightness.peakMagnitudeEstimate]) is 11 or brighter. That is the main-belt
 * asteroids from (4) Vesta (best case 5.2) to (471) Papagena (8.5) and fainter, plus (433) Eros and (1036) Ganymed.
 * 47 kB; built by `Tools/build_bright_asteroids.py` from a pinned snapshot (see `Tools/asteroids.lock.json`).
 *
 * **Freshness.** The elements are osculating at the epoch of each record ([oldestEpoch] to [newestEpoch]: 2026-06-09 for
 * all 216); positions are two-body extrapolations from there. Measured against JPL Horizons (Ceres, Pallas, Juno,
 * Vesta, Iris, Hebe, Eros): at most 0.3″ after 30 days, 5.3″ after 131 days, 37″ after a year and 13′ after two years
 * (median 3′). That is good for aiming within the field of view of a lens for a year; after that, and whenever there are
 * newer elements, replace them: pass the downloaded bodies after these in the list given to [Catalog.withSmallBodies],
 * where the last body with an identifier wins. Show "elements of <date>" when [ageDays] exceeds 365.
 *
 * @property bodies The asteroids, in the order of the file (by number).
 * @property rejectedLineCount Lines of the file that were not valid orbit lines; 0 for the shipped file.
 * @property oldestEpoch The earliest osculating epoch among the bodies.
 * @property newestEpoch The latest osculating epoch among the bodies.
 */
@ConsistentCopyVisibility
data class BundledAsteroids internal constructor(
    val bodies: List<SmallBody>,
    val rejectedLineCount: Int,
    val oldestEpoch: Instant,
    val newestEpoch: Instant,
) {
    /** The days from [oldestEpoch] to [date] (negative before it): how far the elements are extrapolated. */
    fun ageDays(date: Instant): Double = (date.toEpochMilli() - oldestEpoch.toEpochMilli()) / millisecondsPerDay

    companion object {
        /** The name of the bundled file in the library's resources. */
        const val resourceFileName: String = "asteroids.txt"

        /** The largest file that is accepted, 1 MiB (the shipped one is 47 kB). */
        const val maximumFileBytes: Int = 1024 * 1024

        /** The cut's best-case peak magnitude: no asteroid that can never reach this magnitude is in the list. */
        const val peakMagnitudeLimit: Double = 11.0

        /** The cut's absolute magnitude. */
        const val maximumAbsoluteMagnitude: Double = 10.5

        private const val millisecondsPerDay = 86_400_000.0

        /**
         * Reads the bundled file; a few milliseconds.
         *
         * @throws CatalogError.BrightAsteroidsUnavailable when the file is missing, unreadable, too large or has no
         * valid line.
         */
        fun load(): BundledAsteroids = parse(bundledData())

        /** The raw contents of the bundled file (a resource in the package folder of [Catalog]). */
        internal fun bundledData(): ByteArray {
            val stream = BundledAsteroids::class.java.getResourceAsStream(Catalog.RESOURCE_DIRECTORY + resourceFileName)
                ?: throw CatalogError.BrightAsteroidsUnavailable("the resource $resourceFileName is missing")
            try {
                return stream.use { it.readBytes() }
            } catch (error: IOException) {
                throw CatalogError.BrightAsteroidsUnavailable(error.message ?: error.toString())
            }
        }

        /** Reads the contents of such a file; [load] is this with the bundled bytes. */
        internal fun parse(data: ByteArray): BundledAsteroids {
            if (data.size > maximumFileBytes) {
                throw CatalogError.BrightAsteroidsUnavailable("the file is larger than $maximumFileBytes bytes")
            }
            val result = MinorPlanetElementsParser.parse(String(data, Charsets.UTF_8))
            val bodies = result.bodies
            if (bodies.isEmpty()) {
                throw CatalogError.BrightAsteroidsUnavailable("no valid line (${result.rejectedLineCount} rejected)")
            }
            val epochs = bodies.map { it.orbit.epochJulianDayTT }
            return BundledAsteroids(
                bodies = bodies,
                rejectedLineCount = result.rejectedLineCount,
                oldestEpoch = JulianDate.fromTt(epochs.min()).date,
                newestEpoch = JulianDate.fromTt(epochs.max()).date,
            )
        }
    }
}
