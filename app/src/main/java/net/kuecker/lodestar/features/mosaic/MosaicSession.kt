package net.kuecker.lodestar.features.mosaic

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import net.kuecker.lodestar.kit.mosaic.MosaicOrientation
import net.kuecker.lodestar.kit.mosaic.MosaicPanel
import net.kuecker.lodestar.kit.mosaic.MosaicPlan
import net.kuecker.lodestar.kit.mosaic.MosaicPlanner
import net.kuecker.lodestar.kit.mosaic.MosaicRegion

/** What a mosaic covers. */
enum class MosaicSource {
    /** The current target, with a margin around its angular size. */
    TARGET,

    /** The visible arch of the Milky Way, as a horizontal panorama. */
    MILKY_WAY,

    /** A rectangle of a given width and height, around the target or where the camera points. */
    CUSTOM,
}

/** The choices of the mosaic sheet. They are kept with the session, so the sheet opens the way it was left. */
data class MosaicSetup(
    val source: MosaicSource = MosaicSource.TARGET,
    /** Margin around the target as a fraction of its size (0…1). */
    val margin: Double = MosaicRegion.defaultMargin,
    /** Overlap of neighbouring panels as a fraction of the frame (0.10…0.50). */
    val overlap: Double = MosaicPlanner.defaultOverlap,
    /** The camera's orientation, or `null` to follow the planner's recommendation (the one with fewer panels). */
    val orientation: MosaicOrientation? = null,
    /** Width and height in degrees of the custom rectangle. */
    val customWidth: Double = 20.0,
    val customHeight: Double = 15.0,
)

/**
 * The mosaic being shot, shared by the Aim and Sky chart screens (kept with `rememberRetained(MosaicSession.KEY)` so it
 * survives tab switches). Without a [plan] there is no mosaic and the screens behave as usual.
 *
 * The panels are shot in the order the planner gives ([orderedPanels]); [step] is the place of the panel being shot in
 * that order, and equals the number of panels when all of them are done. Main thread only.
 */
@Stable
class MosaicSession {
    /** The choices of the planning sheet. */
    var setup: MosaicSetup by mutableStateOf(MosaicSetup())

    /** The plan being shot, or `null` when no mosaic runs. */
    var plan: MosaicPlan? by mutableStateOf(null)
        private set

    /** What the mosaic covers in words, for example "Andromeda Galaxy" or "Milky Way arch". */
    var title: String by mutableStateOf("")
        private set

    /** The place in the shooting order of the panel being shot (0-based); [panelCount] when all are done. */
    var step: Int by mutableIntStateOf(0)
        private set

    /** The panels of [plan] in shooting order. */
    var orderedPanels: List<MosaicPanel> = emptyList()
        private set

    val isActive: Boolean get() = plan != null
    val panelCount: Int get() = orderedPanels.size

    /** All panels are shot. */
    val isComplete: Boolean get() = plan != null && step >= orderedPanels.size

    /** The panel being shot, or `null` without a mosaic and when all are done. */
    val currentPanel: MosaicPanel? get() = orderedPanels.getOrNull(step)

    /** Starts shooting [plan] with its first panel; whatever ran before is replaced. */
    fun start(plan: MosaicPlan, title: String) {
        orderedPanels = plan.orderedPanels
        this.title = title
        step = 0
        this.plan = plan
    }

    /** The current panel is shot: on to the next one (after the last the mosaic is complete). */
    fun next() {
        if (plan != null && step < orderedPanels.size) step += 1
    }

    /** Back to the previous panel. */
    fun previous() {
        if (plan != null && step > 0) step -= 1
    }

    /** Ends the mosaic; the screens return to normal guidance. */
    fun finish() {
        plan = null
        orderedPanels = emptyList()
        title = ""
        step = 0
    }

    /** "Panel 3 of 12", or "All 12 panels shot". */
    val progressText: String
        get() = if (isComplete) "All $panelCount panels shot" else "Panel ${step + 1} of $panelCount"

    /** The spoken status for accessibility summaries, or `null` without a mosaic. */
    val spokenStatus: String?
        get() {
            val plan = plan ?: return null
            return if (isComplete) {
                "Mosaic of $title complete: all $panelCount panels shot."
            } else {
                "Mosaic of $title, $panelCount panels in ${plan.rows} ${if (plan.rows == 1) "row" else "rows"}. " +
                    "Panel ${step + 1} of $panelCount, $step shot."
            }
        }

    companion object {
        /** The key under which the session is retained; the Aim and Sky chart screens share it. */
        const val KEY = "mosaic.session"
    }
}
