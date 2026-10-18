package net.kuecker.lodestar.features.aim

import android.util.SparseArray
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** How a label on the sky looks; [size] is in dp and does not follow the user's font scale. */
internal class AimLabelStyle(val size: Float, val weight: FontWeight, val color: Color)

/**
 * Measured labels, kept so that the handful of names on the screen is not laid out again every frame. The cache is
 * bounded, least recently used first out, which keeps the labels whose text changes all the time (distances) from
 * piling up. Valid for one density.
 */
internal class AimTextCache(private val measurer: TextMeasurer, private val density: Density) {
    private class Layouts : LinkedHashMap<String, TextLayoutResult>(INITIAL_CAPACITY, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TextLayoutResult>?): Boolean =
            size > LIMIT_PER_LOOK
    }

    private val byLook = SparseArray<Layouts>()

    /** The laid-out [text] in the look of [size] (dp) and [weight]; the colour is applied when drawing. */
    fun layout(text: String, size: Float, weight: FontWeight): TextLayoutResult {
        val look = weight.weight * LOOK_SIZE_STEPS + size.roundToInt()
        val layouts = byLook[look] ?: Layouts().also { byLook.put(look, it) }
        layouts[text]?.let { return it }
        val measured = measurer.measure(
            text = text,
            style = TextStyle(fontSize = with(density) { size.dp.toSp() }, fontWeight = weight),
            overflow = TextOverflow.Clip,
            softWrap = false,
            maxLines = 1,
            skipCache = true,
        )
        layouts[text] = measured
        return measured
    }

    private companion object {
        const val LIMIT_PER_LOOK = 64
        const val INITIAL_CAPACITY = 32
        const val LOAD_FACTOR = 0.75f
        const val LOOK_SIZE_STEPS = 100
    }
}

/**
 * Collects the labels of a frame and draws as many as the budget allows, most important first, skipping any that
 * would overlap one already drawn. Labels are turned with the HUD so they read upright for a sideways phone. Reusable:
 * [reset] it at the start of every frame.
 */
internal class AimLabelQueue(private val texts: AimTextCache) {
    private class Request {
        var text = ""
        var x = 0f
        var y = 0f
        var size = 0f
        var weight = FontWeight.Normal
        var color = Color.Unspecified
        var priority = 0
    }

    private val requests = ArrayList<Request>()
    private var requestCount = 0
    private val occupied = FloatArray(4 * BUDGET)

    fun reset() {
        requestCount = 0
    }

    /** Asks for [text] to be centred on `(x, y)` pixels. Lower [priority] values are drawn first. */
    fun add(text: String, x: Float, y: Float, style: AimLabelStyle, priority: Int) {
        if (requestCount == requests.size) requests.add(Request())
        val request = requests[requestCount]
        requestCount += 1
        request.text = text
        request.x = x
        request.y = y
        request.size = style.size
        request.weight = style.weight
        request.color = style.color
        request.priority = priority
    }

    /** Draws the queued labels inside `[0, bounds]` (pixels); [density] converts the dp sizes. */
    fun draw(scope: DrawScope, bounds: Size, rotation: AimHUDRotation, density: Float) {
        if (requestCount == 0) return
        var lowest = Int.MAX_VALUE
        var highest = Int.MIN_VALUE
        for (index in 0 until requestCount) {
            lowest = minOf(lowest, requests[index].priority)
            highest = maxOf(highest, requests[index].priority)
        }
        val margin = EDGE_MARGIN * density
        var drawn = 0
        // Priority order, and within a priority the order in which they were added.
        for (priority in lowest..highest) {
            for (index in 0 until requestCount) {
                val request = requests[index]
                if (request.priority != priority) continue
                if (drawn >= BUDGET) return
                if (request.text.isEmpty() || !(request.x >= 0 && request.x < bounds.width &&
                        request.y >= 0 && request.y < bounds.height)
                ) {
                    continue
                }
                val layout = texts.layout(request.text, request.size, request.weight)
                val layoutWidth = layout.size.width.toFloat()
                val layoutHeight = layout.size.height.toFloat()
                val width = if (rotation.swapsAxes) layoutHeight else layoutWidth
                val height = if (rotation.swapsAxes) layoutWidth else layoutHeight
                var left = request.x - width / 2 - 3 * density
                var top = request.y - height / 2 - 2 * density
                val boxWidth = width + 6 * density
                val boxHeight = height + 4 * density
                // Slide labels near an edge inward instead of letting the edge clip them.
                val innerRight = bounds.width - margin
                val innerBottom = bounds.height - margin
                if (left + boxWidth > innerRight) left -= left + boxWidth - innerRight
                if (left < margin) left += margin - left
                if (top + boxHeight > innerBottom) top -= top + boxHeight - innerBottom
                if (top < margin) top += margin - top
                if (overlapsDrawn(drawn, left, top, left + boxWidth, top + boxHeight)) continue
                occupied[4 * drawn] = left
                occupied[4 * drawn + 1] = top
                occupied[4 * drawn + 2] = left + boxWidth
                occupied[4 * drawn + 3] = top + boxHeight
                drawn += 1
                scope.withTransform({
                    translate(left + boxWidth / 2, top + boxHeight / 2)
                    if (rotation != AimHUDRotation.UPRIGHT) rotate(rotation.degrees.toFloat(), Offset.Zero)
                }) {
                    drawText(layout, color = request.color, topLeft = Offset(-layoutWidth / 2, -layoutHeight / 2))
                }
            }
        }
    }

    private fun overlapsDrawn(count: Int, left: Float, top: Float, right: Float, bottom: Float): Boolean {
        for (index in 0 until count) {
            if (occupied[4 * index] < right && left < occupied[4 * index + 2] &&
                occupied[4 * index + 1] < bottom && top < occupied[4 * index + 3]
            ) {
                return true
            }
        }
        return false
    }

    private companion object {
        /** The most labels drawn in one frame. */
        const val BUDGET = 40

        /** Distance in dp that labels keep from the edges of the overlay. */
        const val EDGE_MARGIN = 4f
    }
}
