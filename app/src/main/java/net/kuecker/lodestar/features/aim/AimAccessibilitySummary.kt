package net.kuecker.lodestar.features.aim

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * An invisible element for TalkBack that says where the camera points relative to the target, refreshed once a
 * second. The canvas and the live numbers are not accessible, and speaking them ten times a second would be noise.
 * It is a point-sized node (it takes no touches and hides nothing under it); the description is what a screen reader
 * reads when it reaches the node.
 */
@Composable
internal fun AimAccessibilitySummary(model: AimViewModel, isActive: Boolean, modifier: Modifier = Modifier) {
    val frame = rememberAimFrame(model, 1_000, isActive)
    val description = summary(model, frame)
    Box(modifier.size(1.dp).semantics { contentDescription = description })
}

private fun summary(model: AimViewModel, frame: AimFrame?): String {
    if (frame == null) return "Waiting for the motion sensors."
    val target = model.guidanceTarget ?: return "No target. Use the Target button to choose one."
    val guidance = frame.guidance ?: return "Locating ${target.name}."
    val summary = AimGuidanceSummary(guidance)
    val note = if (summary.isBelowHorizon) " Below the horizon." else ""
    return "${target.name}: ${summary.spokenText}.$note"
}
