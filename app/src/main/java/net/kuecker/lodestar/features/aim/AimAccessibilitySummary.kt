package net.kuecker.lodestar.features.aim

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import net.kuecker.lodestar.features.mosaic.MosaicSession
import net.kuecker.lodestar.shared.SkylineSummary

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
    val skyline = model.skyContent?.snapshot?.horizon?.takeUnless { it.isFlat }
        ?.let { " ${SkylineSummary.describe(it)} It is drawn as a line, with the ground below it shaded." }.orEmpty()
    if (model.mosaic.isActive) return mosaicSummary(model.mosaic, frame) + skyline
    val target = model.guidanceTarget ?: return "No target. Use the Target button to choose one.$skyline"
    val guidance = frame.guidance ?: return "Locating ${target.name}.$skyline"
    val summary = AimGuidanceSummary(guidance)
    val note = if (summary.isBelowHorizon) " Below the horizon." else ""
    return "${target.name}: ${summary.spokenText}.$note$skyline"
}

/** Which panel of the mosaic is being shot, how to reach it and how far to turn the camera about its axis. */
private fun mosaicSummary(session: MosaicSession, frame: AimFrame): String {
    val status = session.spokenStatus.orEmpty()
    if (session.isComplete) return status
    val guidance = frame.guidance ?: return "$status Locating the panel."
    val summary = AimGuidanceSummary(guidance)
    val below = if (summary.isBelowHorizon) " The panel is below the horizon." else ""
    val roll = frame.rollDifference?.let { AimMosaicPointings.spokenRollText(it) ?: "The camera roll matches." }
    return "$status ${summary.spokenText}.${if (roll != null) " $roll" else ""}$below"
}
