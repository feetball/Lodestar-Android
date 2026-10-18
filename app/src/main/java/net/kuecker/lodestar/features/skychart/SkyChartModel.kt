package net.kuecker.lodestar.features.skychart

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import net.kuecker.lodestar.kit.catalog.Catalog
import net.kuecker.lodestar.kit.catalog.CelestialObject
import net.kuecker.lodestar.kit.catalog.currentMagnitude
import net.kuecker.lodestar.kit.catalog.horizontal
import net.kuecker.lodestar.kit.foundation.HorizontalCoordinate
import net.kuecker.lodestar.kit.foundation.Observer
import net.kuecker.lodestar.kit.foundation.SkyFrame
import net.kuecker.lodestar.kit.horizon.HorizonProfile
import net.kuecker.lodestar.kit.mosaic.MosaicPlan
import net.kuecker.lodestar.shared.SkySnapshotBuilder
import net.kuecker.lodestar.shared.SkySnapshotOptions
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/** Everything the chart's content depends on: the scene is rebuilt whenever one of these changes. */
internal data class SkyChartInputs(
    val observer: Observer,
    /** The instant to draw, already rounded down to [SkyChartModel.TIME_BUCKET_SECONDS] seconds. */
    val date: Instant,
    val options: SkySnapshotOptions,
    val target: CelestialObject?,
    /** The site's recorded skyline, or `null` for an open horizon. */
    val horizon: HorizonProfile?,
    /** The mosaic being shot, or `null` without one. */
    val mosaic: MosaicPlan?,
    /** Whether the catalog was loaded when these inputs were made (the chart cannot be built before). */
    val isCatalogLoaded: Boolean,
)

/** The object a tap selected, with its position at the chart's instant. */
internal data class SkyChartSelection(
    val obj: CelestialObject,
    /** Refreshed whenever the chart is rebuilt for another time. */
    val horizontal: HorizontalCoordinate,
    /** The current magnitude, when known (the Sun, Moon and planets change theirs). */
    val magnitude: Double?,
)

/**
 * Builds the sky chart's scene off the main thread and holds what the chart shows: the scene, the zoom and pan, and the
 * selected object. Retained across tab switches, so the chart comes back as it was left.
 *
 * The Sun, Moon and stars only move a little each second, so the scene is built for a rounded instant and at most four
 * times a second, however fast the time slider moves: requests that arrive while one is being built or waiting out the
 * rebuild interval replace each other, and only the newest is built. Use it from the main thread; [update] and
 * [runBuilds] are meant for the screen's composition scope.
 */
@Stable
internal class SkyChartModel {
    /** What the chart shows; `null` until the first scene is built. */
    var content by mutableStateOf<SkyChartContent?>(null)
        private set

    var selection by mutableStateOf<SkyChartSelection?>(null)
        private set

    var zoom by mutableFloatStateOf(1f)
        private set

    var pan by mutableStateOf(Offset.Zero)
        private set

    private var catalog: Catalog? = null
    private val requests = Channel<BuildRequest>(Channel.CONFLATED)
    private var lastBuildStart: TimeSource.Monotonic.ValueTimeMark? = null

    private class BuildRequest(val catalog: Catalog, val inputs: SkyChartInputs) {
        fun build(): Pair<SkyChartScene, SkyChartPaths> {
            val snapshot = SkySnapshotBuilder.build(
                catalog, inputs.observer, inputs.date, inputs.options, inputs.target, inputs.horizon,
            )
            val scene = SkyChartSceneBuilder.build(snapshot, inputs.mosaic)
            return scene to SkyChartPaths(scene)
        }
    }

    // Building

    /** Requests a scene for [inputs]; a request that is still waiting is replaced. */
    fun update(catalog: Catalog, inputs: SkyChartInputs) {
        this.catalog = catalog
        requests.trySend(BuildRequest(catalog, inputs))
    }

    /** Builds the requested scenes until cancelled; run it for as long as the chart is on screen. */
    suspend fun runBuilds() {
        for (first in requests) {
            lastBuildStart?.let { start ->
                val wait = MINIMUM_REBUILD_INTERVAL - start.elapsedNow()
                if (wait.isPositive()) delay(wait)
            }
            // A newer request may have arrived while waiting.
            val request = requests.tryReceive().getOrNull() ?: first
            lastBuildStart = TimeSource.Monotonic.markNow()
            val (scene, paths) = withContext(Dispatchers.Default) { request.build() }
            apply(scene, paths)
        }
    }

    private fun apply(scene: SkyChartScene, paths: SkyChartPaths) {
        content = SkyChartContent(scene, paths)
        selection?.let { selection = selectionFor(it.obj, scene) }
    }

    // Selection

    /**
     * Selects what a tap at ([x], [y]) in the chart as drawn in [viewport] hit, or clears the selection when it hit
     * nothing.
     */
    fun handleTap(x: Float, y: Float, viewport: PlanisphereViewport) {
        val hit = content?.scene?.pickable(x, y, viewport)
        val obj = hit?.obj ?: hit?.let { catalog?.objectWithID(it.id) }
        if (obj == null) {
            selection = null
            return
        }
        select(obj)
    }

    fun select(obj: CelestialObject) {
        val scene = content?.scene ?: return
        selection = selectionFor(obj, scene)
    }

    fun clearSelection() {
        selection = null
    }

    private fun selectionFor(obj: CelestialObject, scene: SkyChartScene): SkyChartSelection {
        val frame = SkyFrame(scene.date, scene.observer)
        return SkyChartSelection(obj, frame.horizontal(obj, true), frame.currentMagnitude(obj))
    }

    // Zoom and pan

    /**
     * How the chart is placed in a view of [layout] now, turned by [rotation] radians. The pan is held within its limit
     * here too, so a view that was resized (split screen, a folded or rotated tablet) never shows the chart off centre.
     */
    fun viewport(layout: PlanisphereLayout, rotation: Double = 0.0): PlanisphereViewport =
        PlanisphereViewport(layout, zoom, PlanisphereViewport.clampedPan(pan, zoom, layout), rotation)

    /** Zooms by [ratio] keeping the chart point under [anchor] (a position in the view) in place. */
    fun zoomBy(ratio: Float, anchor: Offset, layout: PlanisphereLayout) {
        if (!(ratio > 0f && ratio.isFinite())) return
        val range = PlanisphereViewport.zoomRange
        val newZoom = (zoom * ratio).coerceIn(range.start, range.endInclusive)
        val anchored = PlanisphereViewport.anchoredPan(pan, anchor, layout, zoom, newZoom)
        zoom = newZoom
        pan = PlanisphereViewport.clampedPan(anchored, newZoom, layout)
    }

    /** Moves the chart by [translation] pixels. */
    fun panBy(translation: Offset, layout: PlanisphereLayout) {
        pan = PlanisphereViewport.clampedPan(pan + translation, zoom, layout)
    }

    /** Puts the chart back at zoom 1 and centred. */
    fun resetViewport() {
        zoom = 1f
        pan = Offset.Zero
    }

    companion object {
        /** The instants scenes are built for are multiples of this many seconds. */
        const val TIME_BUCKET_SECONDS = 30L

        /** The shortest time between the starts of two scene builds. */
        private val MINIMUM_REBUILD_INTERVAL = 250.milliseconds

        /** Rounds [date] down to a multiple of [TIME_BUCKET_SECONDS]. */
        fun bucketed(date: Instant): Instant {
            val bucket = TIME_BUCKET_SECONDS * 1000
            return Instant.ofEpochMilli(Math.floorDiv(date.toEpochMilli(), bucket) * bucket)
        }
    }
}
