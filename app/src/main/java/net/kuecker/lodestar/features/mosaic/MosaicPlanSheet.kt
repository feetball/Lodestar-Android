package net.kuecker.lodestar.features.mosaic

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.kuecker.lodestar.kit.catalog.CelestialObject
import net.kuecker.lodestar.kit.formatting.AngleFormatter
import net.kuecker.lodestar.kit.foundation.HorizontalCoordinate
import net.kuecker.lodestar.kit.mosaic.MosaicOrientation
import net.kuecker.lodestar.kit.mosaic.MosaicPlan
import net.kuecker.lodestar.kit.mosaic.MosaicPlanner
import net.kuecker.lodestar.kit.phototools.LensSetup
import net.kuecker.lodestar.shared.InfoRow
import net.kuecker.lodestar.shared.LocalAppModel
import net.kuecker.lodestar.shared.LocalSheetDismiss
import net.kuecker.lodestar.shared.LodestarButton
import net.kuecker.lodestar.shared.LodestarButtonKind
import net.kuecker.lodestar.shared.LodestarSheet
import net.kuecker.lodestar.shared.SettingsCaption
import net.kuecker.lodestar.shared.SettingsDivider
import net.kuecker.lodestar.shared.SettingsMenuOption
import net.kuecker.lodestar.shared.SettingsMenuPicker
import net.kuecker.lodestar.shared.SettingsNumberField
import net.kuecker.lodestar.shared.SettingsNumberText
import net.kuecker.lodestar.shared.SettingsRow
import net.kuecker.lodestar.shared.SettingsSection
import net.kuecker.lodestar.shared.SettingsSliderRow
import net.kuecker.lodestar.theme.LocalPalette
import kotlin.math.roundToInt

/**
 * The sheet that plans a mosaic: what to cover (the target, the Milky Way arch or a custom size), the overlap, the
 * camera's orientation and the lens, with the resulting panels. "Start" hands the plan to the [session], which makes the
 * Aim screen guide from panel to panel.
 *
 * @param target The object guidance leads to now, for "Fit target" and as the centre of a custom size.
 * @param cameraDirection Where the camera points, read once when the sheet opens (the centre of a custom size without a
 * target).
 */
@Composable
fun MosaicPlanSheet(
    session: MosaicSession,
    target: CelestialObject?,
    cameraDirection: () -> HorizontalCoordinate?,
    onDismiss: () -> Unit,
) {
    LodestarSheet(onDismiss) { MosaicPlanContent(session, target, cameraDirection, LocalSheetDismiss.current) }
}

@Composable
private fun MosaicPlanContent(
    session: MosaicSession,
    target: CelestialObject?,
    cameraDirection: () -> HorizontalCoordinate?,
    onDismiss: () -> Unit,
) {
    val app = LocalAppModel.current
    val palette = LocalPalette.current
    val observer = app.observerContext?.observer
    val lens = app.lensSetup
    val camera = remember { cameraDirection() }
    val opened = remember { Snapshot.withoutReadObservation { app.clock.now } }

    val targetProblem = remember(target) { MosaicPlanning.targetProblem(target) }
    var archProblem by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(observer) {
        archProblem = observer?.let { withContext(Dispatchers.Default) { MosaicPlanning.archProblem(it, opened) } }
    }
    // A source that cannot be used is not kept: the plan falls back to a custom size.
    val stored = session.setup
    val setup = when {
        stored.source == MosaicSource.TARGET && targetProblem != null -> stored.copy(source = MosaicSource.CUSTOM)
        stored.source == MosaicSource.MILKY_WAY && archProblem != null -> stored.copy(source = MosaicSource.CUSTOM)
        else -> stored
    }

    var outcome by remember { mutableStateOf<MosaicPlanOutcome?>(null) }
    LaunchedEffect(setup, target, lens, observer, camera) {
        outcome = if (observer == null) {
            MosaicPlanOutcome.Problem("Choose a location first.")
        } else {
            val inputs = MosaicPlanningInputs(setup, target, lens, observer, camera, app.clock.now)
            withContext(Dispatchers.Default) { MosaicPlanning.plan(inputs) }
        }
    }
    val ready = outcome as? MosaicPlanOutcome.Ready

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 12.dp),
    ) {
        SheetBar(onDone = onDismiss)
        if (session.isActive) RunningCard(session, onFinish = session::finish)

        SettingsSection(title = "Cover") {
            SourceRow(
                title = "Fit target",
                caption = targetProblem ?: fitCaption(target),
                isSelected = setup.source == MosaicSource.TARGET,
                isEnabled = targetProblem == null,
                onSelect = { session.setup = session.setup.copy(source = MosaicSource.TARGET) },
            )
            SettingsDivider()
            SourceRow(
                title = "Milky Way arch panorama",
                caption = archProblem ?: "The visible arch of the Milky Way, from horizon to horizon.",
                isSelected = setup.source == MosaicSource.MILKY_WAY,
                isEnabled = archProblem == null,
                onSelect = { session.setup = session.setup.copy(source = MosaicSource.MILKY_WAY) },
            )
            SettingsDivider()
            SourceRow(
                title = "Custom size",
                caption = customCaption(target, camera),
                isSelected = setup.source == MosaicSource.CUSTOM,
                isEnabled = true,
                onSelect = { session.setup = session.setup.copy(source = MosaicSource.CUSTOM) },
            )
            when (setup.source) {
                MosaicSource.TARGET -> {
                    SettingsDivider()
                    SettingsSliderRow(
                        title = "Margin",
                        valueText = "${(setup.margin * 100).roundToInt()} %",
                        value = setup.margin * 100,
                        onValueChange = { session.setup = session.setup.copy(margin = it.roundToStep(5.0) / 100) },
                        range = 0.0..100.0,
                        step = 5.0,
                    )
                }
                MosaicSource.CUSTOM -> {
                    SettingsDivider()
                    SettingsNumberField(
                        title = "Width",
                        value = setup.customWidth,
                        onValueChange = { session.setup = session.setup.copy(customWidth = it) },
                        range = 0.5..360.0,
                        unit = "°",
                    )
                    SettingsDivider()
                    SettingsNumberField(
                        title = "Height",
                        value = setup.customHeight,
                        onValueChange = { session.setup = session.setup.copy(customHeight = it) },
                        range = 0.5..180.0,
                        unit = "°",
                    )
                }
                MosaicSource.MILKY_WAY -> Unit
            }
        }

        SettingsSection(
            title = "Frames",
            footer = "The overlap is the share of a frame that neighbouring panels have in common. " +
                "More overlap makes stitching easier and needs more panels.",
        ) {
            SettingsSliderRow(
                title = "Overlap",
                valueText = "${(setup.overlap * 100).roundToInt()} %",
                value = setup.overlap * 100,
                onValueChange = { session.setup = session.setup.copy(overlap = it.roundToStep(5.0) / 100) },
                range = MosaicPlanner.overlapRange.start * 100..MosaicPlanner.overlapRange.endInclusive * 100,
                step = 5.0,
            )
            SettingsDivider()
            OrientationPicker(setup.orientation, ready?.recommended) { session.setup = session.setup.copy(orientation = it) }
            SettingsDivider()
            SettingsRow {
                InfoRow("Lens", lensText(app.lensSetup))
                SettingsCaption("Change the camera and lens in Settings.")
            }
        }

        SettingsSection(title = "Plan") { PlanSummary(outcome) }

        if (setup.source == MosaicSource.MILKY_WAY) {
            SettingsCaption(
                "The arch moves about 15° an hour. Plan close to when you shoot, and start soon after.",
                Modifier.padding(horizontal = 32.dp, vertical = 4.dp),
            )
        }
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LodestarButton(
                text = if (session.isActive) "Restart with this plan" else "Start",
                onClick = {
                    val plan = ready ?: return@LodestarButton
                    session.start(plan.plan, plan.title)
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = ready != null,
                icon = Icons.Outlined.GridView,
            )
            if (!session.isActive) {
                Text(
                    "While a mosaic runs, the Aim screen guides to one panel after the other.",
                    color = palette.secondaryText,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    }
}

/** The sheet's title and its "Done" button. */
@Composable
private fun SheetBar(onDone: () -> Unit) {
    val palette = LocalPalette.current
    Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 8.dp)) {
        Text(
            "Mosaic",
            color = palette.primaryText,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.align(Alignment.Center).semantics { heading() },
        )
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                .clickable(role = Role.Button, onClick = onDone)
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("Done", color = palette.accent, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** What is running now, with the way to end it. */
@Composable
private fun RunningCard(session: MosaicSession, onFinish: () -> Unit) {
    val palette = LocalPalette.current
    SettingsSection(title = "Running") {
        SettingsRow {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        session.title,
                        color = palette.primaryText,
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    )
                    Text(session.progressText, color = palette.secondaryText, style = MaterialTheme.typography.bodyMedium)
                }
                LodestarButton("Finish", onClick = onFinish, kind = LodestarButtonKind.SECONDARY)
            }
        }
    }
}

/** One choice of what to cover: a radio button with a title and an explanation. */
@Composable
private fun SourceRow(title: String, caption: String, isSelected: Boolean, isEnabled: Boolean, onSelect: () -> Unit) {
    val palette = LocalPalette.current
    val alpha = if (isEnabled) 1f else 0.5f
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = isSelected, enabled = isEnabled, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(
            selected = isSelected,
            onClick = null,
            enabled = isEnabled,
            colors = RadioButtonDefaults.colors(
                selectedColor = palette.accent,
                unselectedColor = palette.secondaryText,
                disabledSelectedColor = palette.tertiaryText,
                disabledUnselectedColor = palette.tertiaryText,
            ),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                color = palette.primaryText.copy(alpha = alpha),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                caption,
                color = (if (isEnabled) palette.secondaryText else palette.warning).copy(alpha = if (isEnabled) 1f else 0.9f),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** Landscape or portrait, or the planner's recommendation. */
@Composable
private fun OrientationPicker(
    chosen: MosaicOrientation?,
    recommended: MosaicOrientation?,
    onSelect: (MosaicOrientation?) -> Unit,
) {
    val options: List<SettingsMenuOption<MosaicOrientation?>> = listOf(
        SettingsMenuOption(
            if (recommended == null) "Recommended" else "Recommended (${orientationName(recommended)})", null,
        ),
        SettingsMenuOption(orientationName(MosaicOrientation.LANDSCAPE), MosaicOrientation.LANDSCAPE),
        SettingsMenuOption(orientationName(MosaicOrientation.PORTRAIT), MosaicOrientation.PORTRAIT),
    )
    Column {
        SettingsMenuPicker("Camera orientation", chosen, options, onSelect = onSelect)
        SettingsCaption(
            if (chosen == null) {
                "Chosen for the fewest panels. Turn the camera a quarter turn for portrait."
            } else {
                "Turn the camera a quarter turn for portrait."
            },
            Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        )
    }
}

/** The numbers of the plan, its warnings, or why there is no plan. */
@Composable
private fun PlanSummary(outcome: MosaicPlanOutcome?) {
    val palette = LocalPalette.current
    when (outcome) {
        null -> SettingsRow {
            Text("Planning…", color = palette.secondaryText, style = MaterialTheme.typography.bodyMedium)
        }
        is MosaicPlanOutcome.Problem -> SettingsRow {
            Note(outcome.message, Icons.Filled.Warning, palette.warning)
        }
        is MosaicPlanOutcome.Ready -> {
            val plan = outcome.plan
            SettingsRow {
                InfoRow("Panels", plan.panelCount.toString())
                InfoRow(if (plan.isRectangular) "Rows × columns" else "Layout", gridText(plan))
                InfoRow(
                    "Covers",
                    "${AngleFormatter.formatDegrees(plan.coverage.widthDegrees, 1)} × " +
                        AngleFormatter.formatDegrees(plan.coverage.heightDegrees, 1),
                )
                InfoRow("Overlap", plan.achievedOverlap?.let { "${(it * 100).roundToInt()} %" } ?: "Single frame")
                InfoRow("Orientation", orientationName(plan.orientation))
            }
            if (plan.warnings.isEmpty()) {
                SettingsDivider()
                SettingsRow { Note("The region is fully covered.", Icons.Filled.CheckCircle, palette.positive) }
            } else {
                for (warning in plan.warnings) {
                    SettingsDivider()
                    SettingsRow { Note(MosaicPlanning.describe(warning), Icons.Filled.Warning, palette.warning) }
                }
            }
        }
    }
}

@Composable
private fun Note(text: String, icon: ImageVector, color: Color) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Text(text, color = color, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

private fun fitCaption(target: CelestialObject?): String {
    val size = target?.angularSize ?: return "Choose a target first."
    return "${target.name}, ${size.description}, with a margin around it."
}

private fun customCaption(target: CelestialObject?, camera: HorizontalCoordinate?): String = when {
    target?.j2000 != null -> "A rectangle around ${target.name}."
    camera != null -> "A rectangle where the camera points now."
    else -> "Choose a target first, or point the camera where the mosaic should be."
}

private fun gridText(plan: MosaicPlan): String =
    if (plan.isRectangular) "${plan.rows} × ${plan.columns}" else plan.gridDescription

private fun orientationName(orientation: MosaicOrientation): String = when (orientation) {
    MosaicOrientation.LANDSCAPE -> "Landscape"
    MosaicOrientation.PORTRAIT -> "Portrait"
}

private fun lensText(setup: LensSetup): String {
    val frame = setup.fieldOfView
    return "${SettingsNumberText.text(setup.focalLengthMillimeters, 1)} mm · ${setup.sensor.name} · " +
        "${AngleFormatter.formatDegrees(frame.horizontal, 1)} × ${AngleFormatter.formatDegrees(frame.vertical, 1)}"
}

private fun Double.roundToStep(step: Double): Double = (this / step).roundToInt() * step
