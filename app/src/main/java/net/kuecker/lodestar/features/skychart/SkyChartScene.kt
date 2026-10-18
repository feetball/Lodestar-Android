package net.kuecker.lodestar.features.skychart

import net.kuecker.lodestar.features.smallbodies.SmallBodyChartMarkers
import net.kuecker.lodestar.features.smallbodies.SmallBodyChartMarker
import net.kuecker.lodestar.kit.catalog.CelestialObject
import net.kuecker.lodestar.kit.catalog.ObjectCategory
import net.kuecker.lodestar.kit.catalog.ObjectGroup
import net.kuecker.lodestar.kit.foundation.AstroMath
import net.kuecker.lodestar.kit.foundation.HorizontalCoordinate
import net.kuecker.lodestar.kit.foundation.Observer
import net.kuecker.lodestar.kit.foundation.Vector3
import net.kuecker.lodestar.kit.foundation.roundedHalfAwayFromZero
import net.kuecker.lodestar.kit.horizon.HorizonProfile
import net.kuecker.lodestar.kit.mosaic.MosaicPlan
import net.kuecker.lodestar.shared.SkySnapshot
import java.time.Instant
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min

/**
 * Dot sizes and colours of the stars on the chart. Stars are drawn in batches that share a size class and a colour
 * class, so a frame needs one draw call per batch instead of one per star.
 */
internal object SkyChartStarStyle {
    /** Dot diameters in dp, faintest class first. */
    val diameters = floatArrayOf(1.0f, 1.4f, 1.9f, 2.5f, 3.2f, 4.0f, 4.9f, 6.0f)

    /** Dot opacities, faintest class first. */
    val opacities = floatArrayOf(0.55f, 0.65f, 0.75f, 0.85f, 0.92f, 1f, 1f, 1f)

    /** A representative B−V colour index for each colour class: blue-white, white, yellow-white, orange, red. */
    val colorIndices = doubleArrayOf(-0.15, 0.2, 0.65, 1.0, 1.6)

    /** The size class of a star: 0 for magnitude 6 and fainter, 7 for the brightest stars. */
    fun sizeClass(magnitude: Double): Int {
        if (!magnitude.isFinite()) return 0
        return floor(7 - magnitude).coerceIn(0.0, (diameters.size - 1).toDouble()).toInt()
    }

    /** The colour class for a B−V colour index; stars without one count as white. */
    fun colorClass(colorIndex: Double?): Int {
        if (colorIndex == null || !colorIndex.isFinite()) return 1
        return when {
            colorIndex < 0.0 -> 0
            colorIndex < 0.45 -> 1
            colorIndex < 0.8 -> 2
            colorIndex < 1.2 -> 3
            else -> 4
        }
    }
}

/** Stars that share a dot size and colour, as chart points (x, y pairs). */
internal class SkyChartStarGroup(val sizeClass: Int, val colorClass: Int, val points: FloatArray)

/** One brightness level of the Milky Way as chart polygons. */
internal class SkyChartMilkyWayLevel(
    val level: Int,
    /** Filled rings and holes (x, y pairs); fill them together with the even–odd rule. */
    val rings: List<DoubleArray>,
    /**
     * `true` when an odd number of the rings enclose the nadir, which the projection sends to infinity: the sky the
     * level covers is then everything *outside* those rings, so the even–odd fill has to be run over a large square as
     * well.
     */
    val invertsFill: Boolean,
)

/** The Sun, the Moon or a planet. */
internal class SkyChartBodyMarker(
    val obj: CelestialObject,
    val kind: Kind,
    val x: Float,
    val y: Float,
    val horizontal: HorizontalCoordinate,
    val magnitude: Double?,
    /** The lit fraction of the disc, when known. */
    val illuminatedFraction: Double?,
    /**
     * The direction of the Sun seen from the body on the chart, in radians (chart axes, y down). Where the lit side of
     * the Moon points.
     */
    val towardsSunAngle: Double,
    /** `true` when the recorded skyline hides the body. */
    val isBehindSkyline: Boolean = false,
) {
    enum class Kind { SUN, MOON, PLANET }

    val id: String get() = obj.id
    val name: String get() = obj.name
    val isAboveHorizon: Boolean get() = horizontal.altitude >= 0

    /** Above the horizon and not hidden by the recorded skyline: the body is drawn, labelled and can be tapped. */
    val isShown: Boolean get() = isAboveHorizon && !isBehindSkyline
}

/** A deep-sky object. */
internal class SkyChartDeepSkyMarker(
    val x: Float,
    val y: Float,
    val group: ObjectGroup,
    /** How conspicuous the object is: 1 showpiece … 4 obscure. Zooming out leaves the least prominent out first. */
    val prominence: Int,
    /** The object's largest dimension in radians, when known. */
    val extent: Double?,
)

/**
 * The site's recorded skyline in chart space: its distance from the zenith, in chart units (the horizon circle is 1), at
 * every whole degree of azimuth. Where the skyline lies below the horizon the distance is capped at 1.
 */
internal class SkyChartSkyline(val profile: HorizonProfile) {
    val radii: DoubleArray = DoubleArray(HorizonProfile.binCount) { degree ->
        min(1.0, PlanisphereProjection.radiusOfAltitude(profile.altitude(degree.toDouble())))
    }
}

/** The aiming target. */
internal class SkyChartTargetMarker(val obj: CelestialObject, val direction: Vector3, val horizontal: HorizontalCoordinate) {
    val isAboveHorizon: Boolean get() = direction.z >= 0
}

/** A text label on the chart. */
internal class SkyChartLabel(
    /** Unique among the labels of a scene. */
    val id: String,
    val text: String,
    val style: Style,
    val x: Float,
    val y: Float,
    /** Labels that would overlap are resolved in favour of the higher priority. */
    val priority: Int,
    /** The label only appears once the chart is zoomed in this far. */
    val minimumZoom: Float,
    /**
     * Distance in dp between the labelled point and the label; the label sits beside the point when this is positive
     * and is centred on it when zero.
     */
    val offset: Float,
) {
    enum class Style { CONSTELLATION, STAR, DEEP_SKY, BODY, TARGET }

    companion object {
        /** Highest priority first, then by id so that equal priorities keep a stable order between scenes. */
        val PRIORITY_ORDER = Comparator<SkyChartLabel> { a, b ->
            if (a.priority != b.priority) b.priority.compareTo(a.priority) else a.id.compareTo(b.id)
        }
    }
}

/**
 * The least prominent level of deep-sky objects the chart draws at [zoom] (1 showpieces … 4 everything): the scene holds
 * everything the deep-sky detail setting allows, and zoomed out the obscure objects are left out so that the chart stays
 * readable. A tap does not pick what is not drawn.
 */
internal fun chartDeepSkyProminenceLimit(zoom: Float): Int = if (zoom < 2f) 3 else 4

/** Something a tap can select. */
internal class SkyChartPickable(
    val id: String,
    val kind: Kind,
    val x: Float,
    val y: Float,
    /** The object itself when the snapshot has it; stars are looked up in the catalog by [id]. */
    val obj: CelestialObject?,
    /** The prominence level of a deep-sky object (1 showpiece … 4 obscure); 0 for everything else. */
    val prominence: Int = 0,
) {
    enum class Kind(
        /** Scales the distance to a tap: objects that are rarer or more important win ties. */
        val distanceBias: Float,
    ) {
        TARGET(0.5f),
        BODY(0.5f),
        SMALL_BODY(0.6f),
        DEEP_SKY(0.8f),
        STAR(1f),
    }
}

/**
 * Everything the planisphere draws for one instant and observer, in chart space: derived from a [SkySnapshot] off the
 * main thread and rotated, scaled and clipped by the views.
 */
internal class SkyChartScene(
    val date: Instant,
    val observer: Observer,
    /** Faintest first, so brighter dots are drawn on top. */
    val starGroups: List<SkyChartStarGroup>,
    val constellationLines: List<FloatArray>,
    val milkyWay: List<SkyChartMilkyWayLevel>,
    /** The Sun, Moon and planets, including those below the horizon. */
    val bodies: List<SkyChartBodyMarker>,
    val deepSky: List<SkyChartDeepSkyMarker>,
    val target: SkyChartTargetMarker?,
    /** Highest priority first. */
    val labels: List<SkyChartLabel>,
    val pickables: List<SkyChartPickable>,
    /** The recorded skyline, or `null` for an open horizon. */
    val skyline: SkyChartSkyline? = null,
    /** The panels of the mosaic being shot, as they stand at the scene's instant; `null` without a mosaic. */
    val mosaic: SkyChartMosaic? = null,
    /** The comets and asteroids above the horizon: the target and the bright ones. */
    val smallBodies: List<SmallBodyChartMarker> = emptyList(),
) {
    /**
     * The thing a tap at ([tapX], [tapY]) selects: the nearest pickable within [tolerance] pixels, where a planet or the
     * target counts as closer than a star at the same distance.
     */
    fun pickable(
        tapX: Float,
        tapY: Float,
        viewport: PlanisphereViewport,
        tolerance: Float = 24f * viewport.layout.density,
    ): SkyChartPickable? {
        var best: SkyChartPickable? = null
        var bestScore = Float.MAX_VALUE
        val prominenceLimit = chartDeepSkyProminenceLimit(viewport.zoom)
        for (pickable in pickables) {
            if (pickable.prominence > prominenceLimit) continue
            val dx = viewport.screenX(pickable.x, pickable.y) - tapX
            val dy = viewport.screenY(pickable.x, pickable.y) - tapY
            val distance = hypot(dx, dy)
            if (distance > tolerance) continue
            val score = distance * pickable.kind.distanceBias
            if (best != null && bestScore <= score) continue
            best = pickable
            bestScore = score
        }
        return best
    }
}

/** Turns a [SkySnapshot] into a [SkyChartScene]. Pure, so it runs on any thread. */
internal object SkyChartSceneBuilder {
    /** The most labels of each kind a scene carries; the views place as many as fit. */
    private const val CONSTELLATION_LABEL_LIMIT = 60
    private const val STAR_LABEL_LIMIT = 30
    private const val DEEP_SKY_LABEL_LIMIT = 220

    /** Only stars at least this bright get a name label. */
    private const val STAR_LABEL_MAGNITUDE = 3.2

    /** Label anchors this far above the horizon (sine of the altitude) or higher are kept. */
    private const val LABEL_HORIZON = 0.03

    /**
     * Builds the scene for [snapshot]; [mosaic] is the plan being shot, whose panels are placed in the sky of the
     * snapshot's instant. Objects the snapshot flags as hidden by the recorded skyline are left out.
     */
    fun build(snapshot: SkySnapshot, mosaic: MosaicPlan? = null): SkyChartScene {
        val sun = snapshot.bodies.firstOrNull { it.obj.id == "sun" }
        val bodies = snapshot.bodies.map { bodyMarker(it, sun) }
        val deepSky = deepSkyMarkers(snapshot.deepSky)
        val target = snapshot.target?.let { SkyChartTargetMarker(it.obj, it.direction, it.horizontal) }
        val (constellationLines, constellationLabels) = constellations(snapshot.constellations)
        val smallBodies = SmallBodyChartMarkers.build(snapshot.smallBodies, snapshot.target?.obj?.id)

        val labels = ArrayList<SkyChartLabel>(constellationLabels)
        labels += starLabels(snapshot.stars)
        labels += deepSkyLabels(snapshot.deepSky)
        for (body in bodies) if (body.isShown) labels += bodyLabel(body)
        labels += SmallBodyChartMarkers.labels(smallBodies)
        if (target != null && target.isAboveHorizon) {
            val point = PlanisphereProjection.chartPoint(target.direction)
            labels += SkyChartLabel(
                "target.${target.obj.id}", target.obj.name, SkyChartLabel.Style.TARGET,
                point.x.toFloat(), point.y.toFloat(), priority = 100, minimumZoom = 1f, offset = 22f,
            )
        }
        labels.sortWith(SkyChartLabel.PRIORITY_ORDER)

        return SkyChartScene(
            date = snapshot.date,
            observer = snapshot.observer,
            starGroups = starGroups(snapshot),
            constellationLines = constellationLines,
            milkyWay = milkyWay(snapshot),
            bodies = bodies,
            deepSky = deepSky,
            target = target,
            labels = labels,
            pickables = pickables(snapshot, bodies, target) + SmallBodyChartMarkers.pickables(smallBodies),
            skyline = snapshot.horizon?.takeUnless { it.isFlat }?.let(::SkyChartSkyline),
            mosaic = mosaic?.let { SkyChartMosaicBuilder.build(it, snapshot.frame) },
            smallBodies = smallBodies,
        )
    }

    // Stars

    private fun starGroups(snapshot: SkySnapshot): List<SkyChartStarGroup> {
        val colorCount = SkyChartStarStyle.colorIndices.size
        val classCount = SkyChartStarStyle.diameters.size * colorCount
        val vectors = snapshot.starVectors
        val keys = IntArray(snapshot.stars.size) { -1 }
        val counts = IntArray(classCount)
        snapshot.stars.forEachIndexed { index, star ->
            if (vectors[index * 3 + 2] >= 0 && !star.isBehindSkyline) {
                val sizeClass = SkyChartStarStyle.sizeClass(star.magnitude)
                val key = sizeClass * colorCount + SkyChartStarStyle.colorClass(star.colorIndex)
                keys[index] = key
                counts[key] += 1
            }
        }
        val points = Array(classCount) { FloatArray(counts[it] * 2) }
        val filled = IntArray(classCount)
        for (index in keys.indices) {
            val key = keys[index]
            if (key < 0) continue
            val denominator = 1 + vectors[index * 3 + 2]
            points[key][filled[key]] = (-vectors[index * 3] / denominator).toFloat()
            points[key][filled[key] + 1] = (-vectors[index * 3 + 1] / denominator).toFloat()
            filled[key] += 2
        }
        return (0 until classCount).filter { counts[it] > 0 }.map {
            SkyChartStarGroup(sizeClass = it / colorCount, colorClass = it % colorCount, points = points[it])
        }
    }

    private fun starLabels(stars: List<SkySnapshot.Star>): List<SkyChartLabel> {
        val named = stars.filter {
            it.name != null && it.direction.z > LABEL_HORIZON && it.magnitude <= STAR_LABEL_MAGNITUDE && !it.isBehindSkyline
        }
        val brightest = named.sortedWith(compareBy<SkySnapshot.Star>({ it.magnitude }, { it.id }))
        return brightest.take(STAR_LABEL_LIMIT).mapNotNull { star ->
            val name = star.name ?: return@mapNotNull null
            val zoom = if (star.magnitude <= 1.5) 1f else if (star.magnitude <= 2.4) 1.6f else 2.5f
            val point = PlanisphereProjection.chartPoint(star.direction)
            SkyChartLabel(
                "star.${star.id}", name, SkyChartLabel.Style.STAR, point.x.toFloat(), point.y.toFloat(),
                priority = 50 + ((STAR_LABEL_MAGNITUDE - star.magnitude) * 4).roundedHalfAwayFromZero().toInt(),
                minimumZoom = zoom, offset = 6f,
            )
        }
    }

    // Constellations

    private fun constellations(figures: List<SkySnapshot.ConstellationFigure>): Pair<List<FloatArray>, List<SkyChartLabel>> {
        val lines = ArrayList<FloatArray>()
        val labels = ArrayList<SkyChartLabel>()
        for (figure in figures) {
            for (line in figure.lines) lines += PlanisphereProjection.visiblePolylines(line)
            if (!(figure.label.z > LABEL_HORIZON)) continue
            val size = figure.lines.sumOf { it.size }
            val point = PlanisphereProjection.chartPoint(figure.label)
            labels += SkyChartLabel(
                "constellation.${figure.abbreviation}", figure.name, SkyChartLabel.Style.CONSTELLATION,
                point.x.toFloat(), point.y.toFloat(), priority = 20 + min(size, 60) / 4, minimumZoom = 1f, offset = 0f,
            )
        }
        labels.sortWith(SkyChartLabel.PRIORITY_ORDER)
        return lines to labels.take(CONSTELLATION_LABEL_LIMIT)
    }

    // Milky Way

    private fun milkyWay(snapshot: SkySnapshot): List<SkyChartMilkyWayLevel> {
        if (snapshot.milkyWay.isEmpty()) return emptyList()
        // The celestial pole above the horizon lies outside every Milky Way outline; the outline rings that enclose it
        // in the projection are the ones that enclose the nadir instead.
        val pole = snapshot.frame.horizontalOfCelestialPole(snapshot.observer.location.latitude >= 0, true)
        val reference = PlanisphereProjection.retractedChartPoint(pole.unitVector)
        return snapshot.milkyWay.map { band ->
            val rings = (band.polygons + band.holes)
                .map { PlanisphereProjection.ring(it) }
                .filter { it.isNotEmpty() }
            val enclosing = rings.count { PlanisphereProjection.contains(reference.x, reference.y, it) }
            SkyChartMilkyWayLevel(band.level, rings, invertsFill = enclosing % 2 == 1)
        }
    }

    // Solar system

    private fun bodyMarker(body: SkySnapshot.Body, sun: SkySnapshot.Body?): SkyChartBodyMarker {
        val kind = when (body.obj.category) {
            ObjectCategory.SUN -> SkyChartBodyMarker.Kind.SUN
            ObjectCategory.MOON -> SkyChartBodyMarker.Kind.MOON
            else -> SkyChartBodyMarker.Kind.PLANET
        }
        val point = PlanisphereProjection.retractedChartPoint(body.direction)
        return SkyChartBodyMarker(
            obj = body.obj, kind = kind, x = point.x.toFloat(), y = point.y.toFloat(), horizontal = body.horizontal,
            magnitude = body.magnitude, illuminatedFraction = body.illuminatedFraction,
            towardsSunAngle = sun?.let { towardsAngle(body.direction, it.direction) } ?: 0.0,
            isBehindSkyline = body.isBehindSkyline,
        )
    }

    /** The direction on the chart in which the great circle from [origin] to [other] leaves [origin]. */
    private fun towardsAngle(origin: Vector3, other: Vector3): Double {
        val tangent = (other - origin * origin.dot(other)).normalized
        if (tangent == Vector3.zero) return 0.0
        val here = PlanisphereProjection.retractedChartPoint(origin)
        val ahead = PlanisphereProjection.retractedChartPoint((origin + tangent * 0.01).normalized)
        return atan2(ahead.y - here.y, ahead.x - here.x)
    }

    private fun bodyLabel(body: SkyChartBodyMarker): SkyChartLabel = SkyChartLabel(
        "body.${body.id}", body.name, SkyChartLabel.Style.BODY, body.x, body.y,
        priority = if (body.kind == SkyChartBodyMarker.Kind.PLANET) 90 else 95, minimumZoom = 1f, offset = 12f,
    )

    // Deep-sky objects

    private fun deepSkyMarkers(objects: List<SkySnapshot.DeepSky>): List<SkyChartDeepSkyMarker> =
        objects.filter { it.direction.z >= 0 && !it.isBehindSkyline }
            .sortedWith(compareBy<SkySnapshot.DeepSky>({ it.prominence }, { rankingMagnitude(it.obj) }, { it.obj.id }))
            .map { item ->
                val point = PlanisphereProjection.chartPoint(item.direction)
                SkyChartDeepSkyMarker(
                    x = point.x.toFloat(), y = point.y.toFloat(), group = item.obj.group,
                    prominence = item.prominence.rawValue, extent = item.majorAxis?.let { AstroMath.radians(it) },
                )
            }

    /**
     * The names of the most prominent deep-sky objects, each shown from the zoom its prominence needs: showpieces as the
     * chart is zoomed in a little, the obscure ones only far in.
     */
    private fun deepSkyLabels(objects: List<SkySnapshot.DeepSky>): List<SkyChartLabel> {
        val visible = objects.filter { it.direction.z > LABEL_HORIZON && !it.isBehindSkyline }
        val ranked = visible.sortedWith(
            compareBy<SkySnapshot.DeepSky>({ it.prominence }, { rankingMagnitude(it.obj) }, { it.obj.id }),
        )
        return ranked.take(DEEP_SKY_LABEL_LIMIT).map { item ->
            val magnitude = rankingMagnitude(item.obj)
            val level = item.prominence.rawValue
            val zoom = when (level) {
                1 -> 1.4f
                2 -> 2.2f
                3 -> 3.4f
                else -> 4.8f
            }
            val point = PlanisphereProjection.chartPoint(item.direction)
            SkyChartLabel(
                "deepSky.${item.obj.id}", item.obj.name, SkyChartLabel.Style.DEEP_SKY, point.x.toFloat(), point.y.toFloat(),
                priority = 26 - 3 * level - (min(magnitude, 12.0) / 6).toInt(), minimumZoom = zoom, offset = 7f,
            )
        }
    }

    /** The magnitude used to rank deep-sky objects of equal prominence; 99 when it is unknown. */
    private fun rankingMagnitude(obj: CelestialObject): Double {
        val magnitude = obj.magnitude ?: return 99.0
        return if (magnitude.isFinite()) magnitude else 99.0
    }

    // Picking

    private fun pickables(
        snapshot: SkySnapshot,
        bodies: List<SkyChartBodyMarker>,
        target: SkyChartTargetMarker?,
    ): List<SkyChartPickable> {
        val result = ArrayList<SkyChartPickable>(snapshot.stars.size + snapshot.deepSky.size + bodies.size + 1)
        if (target != null && target.isAboveHorizon) {
            val point = PlanisphereProjection.chartPoint(target.direction)
            result += SkyChartPickable(
                target.obj.id, SkyChartPickable.Kind.TARGET, point.x.toFloat(), point.y.toFloat(), target.obj,
            )
        }
        for (body in bodies) {
            if (!body.isShown) continue
            result += SkyChartPickable(body.id, SkyChartPickable.Kind.BODY, body.x, body.y, body.obj)
        }
        for (item in snapshot.deepSky) {
            if (item.direction.z < 0 || item.isBehindSkyline) continue
            val point = PlanisphereProjection.chartPoint(item.direction)
            result += SkyChartPickable(
                item.obj.id, SkyChartPickable.Kind.DEEP_SKY, point.x.toFloat(), point.y.toFloat(), item.obj,
                prominence = item.prominence.rawValue,
            )
        }
        val vectors = snapshot.starVectors
        snapshot.stars.forEachIndexed { index, star ->
            val z = vectors[index * 3 + 2]
            if (z >= 0 && !star.isBehindSkyline) {
                val denominator = 1 + z
                result += SkyChartPickable(
                    star.id, SkyChartPickable.Kind.STAR,
                    (-vectors[index * 3] / denominator).toFloat(), (-vectors[index * 3 + 1] / denominator).toFloat(), null,
                )
            }
        }
        return result
    }
}
