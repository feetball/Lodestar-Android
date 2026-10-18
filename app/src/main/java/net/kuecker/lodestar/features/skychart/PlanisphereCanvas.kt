package net.kuecker.lodestar.features.skychart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import net.kuecker.lodestar.features.mosaic.MosaicSession
import net.kuecker.lodestar.theme.Palette

/**
 * The all-sky chart: draws a [SkyChartContent] through a [PlanisphereViewport] built from the model's zoom and pan.
 *
 * Orientation: the zenith is at the centre of the horizon circle, north is at the top and east to the left, as when
 * lying down and looking up. A frame only transforms and fills what the content already holds: line and polygon paths
 * come ready-made, and the text labels are laid out once and looked up in a cache.
 *
 * The draw block reads the zoom, pan and selection from [model] (and, while the chart turns with the camera
 * ([followsHeading]), the heading from [live], and the panel being shot from the [mosaic] session), so a pinch, drag,
 * heading change or finished panel redraws the canvas without recomposing anything.
 */
@Composable
internal fun PlanisphereCanvas(
    content: SkyChartContent,
    model: SkyChartModel,
    live: SkyChartLiveState,
    palette: Palette,
    layers: SkyChartLayerOptions,
    followsHeading: Boolean,
    mosaic: MosaicSession,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val renderer = remember(measurer, density) { PlanisphereRenderer(PlanisphereTextCache(measurer, density)) }
    Canvas(modifier.fillMaxSize()) {
        val layout = PlanisphereLayout(size.width, size.height, this.density)
        val rotation = if (followsHeading) live.rotation else 0.0
        val viewport = model.viewport(layout, rotation)
        // The panels are drawn while the scene holds the plan that is being shot (a new scene follows a new plan).
        val step = if (content.scene.mosaic?.plan === mosaic.plan) mosaic.step else -1
        renderer.draw(this, content, viewport, palette, layers, model.selection?.horizontal?.unitVector, step)
    }
}

/** The kinds of text the chart writes, each with its own font. */
internal enum class PlanisphereTextRole {
    CONSTELLATION,
    STAR,
    DEEP_SKY,
    BODY,
    TARGET,
    CARDINAL,
    CARDINAL_MINOR,
    ALTITUDE,
    MOSAIC,
}

internal val SkyChartLabel.Style.role: PlanisphereTextRole
    get() = when (this) {
        SkyChartLabel.Style.CONSTELLATION -> PlanisphereTextRole.CONSTELLATION
        SkyChartLabel.Style.STAR -> PlanisphereTextRole.STAR
        SkyChartLabel.Style.DEEP_SKY -> PlanisphereTextRole.DEEP_SKY
        SkyChartLabel.Style.BODY -> PlanisphereTextRole.BODY
        SkyChartLabel.Style.TARGET -> PlanisphereTextRole.TARGET
    }

/** The colour a label of this style is written in. */
internal fun SkyChartLabel.Style.color(palette: Palette): Color = when (this) {
    SkyChartLabel.Style.CONSTELLATION -> palette.constellationLabel
    SkyChartLabel.Style.STAR -> palette.secondaryText
    SkyChartLabel.Style.DEEP_SKY -> palette.deepSkyMarker
    SkyChartLabel.Style.BODY -> palette.primaryText
    SkyChartLabel.Style.TARGET -> palette.guidance
}

/**
 * The text labels of the chart, laid out once and kept: measuring text is far too slow to do for every label of every
 * frame. Layouts do not depend on colour (it is passed when drawing), so they survive palette changes and are shared by
 * all scenes; the cache lives as long as the canvas does and is dropped when the density or font settings change.
 *
 * Chart text is drawing, not reading text: its size is fixed in dp and does not follow the system font scale, as on iOS.
 */
internal class PlanisphereTextCache(private val measurer: TextMeasurer, density: Density) {
    private val styles: Array<TextStyle> = with(density) {
        fun style(size: Float, weight: FontWeight, spacing: Float = 0f) =
            TextStyle(fontSize = fixed(size), fontWeight = weight, letterSpacing = fixed(spacing))
        arrayOf(
            style(9.5f, FontWeight.Normal, spacing = 0.8f), // CONSTELLATION
            style(10.5f, FontWeight.Medium), // STAR
            style(10f, FontWeight.Normal), // DEEP_SKY
            style(11.5f, FontWeight.SemiBold), // BODY
            style(12f, FontWeight.Bold), // TARGET
            style(16f, FontWeight.Bold), // CARDINAL
            style(11f, FontWeight.Bold), // CARDINAL_MINOR
            style(9f, FontWeight.Normal), // ALTITUDE
            style(12f, FontWeight.Bold), // MOSAIC
        )
    }

    private val layouts = Array(PlanisphereTextRole.entries.size) { HashMap<String, TextLayoutResult>() }

    /** The laid-out [text] in the font of [role]; constellation names are written in capitals. */
    fun layout(role: PlanisphereTextRole, text: String): TextLayoutResult =
        layouts[role.ordinal].getOrPut(text) {
            measurer.measure(
                text = if (role == PlanisphereTextRole.CONSTELLATION) text.uppercase() else text,
                style = styles[role.ordinal],
                overflow = TextOverflow.Clip,
                softWrap = false,
                maxLines = 1,
                skipCache = true,
            )
        }

    /** [size] dp as a text size that ignores the font scale. */
    private fun Density.fixed(size: Float): TextUnit = size.dp.toSp()
}
