package net.kuecker.lodestar.features.alignmentfinder

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BlurCircular
import androidx.compose.material.icons.outlined.GpsFixed
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.kuecker.lodestar.app.AppTab
import net.kuecker.lodestar.features.tools.FormRows
import net.kuecker.lodestar.features.tools.FormSection
import net.kuecker.lodestar.features.tools.ToolsScreenFrame
import net.kuecker.lodestar.features.tools.ToolsSegmentedRow
import net.kuecker.lodestar.features.tools.rememberWhilePushed
import net.kuecker.lodestar.kit.catalog.CelestialObject
import net.kuecker.lodestar.kit.horizon.HorizonProfile
import net.kuecker.lodestar.kit.planning.SkyWindowMatch
import net.kuecker.lodestar.shared.EmptyStateView
import net.kuecker.lodestar.shared.LocalAppModel
import net.kuecker.lodestar.shared.LodestarButton
import net.kuecker.lodestar.shared.LodestarButtonKind
import net.kuecker.lodestar.shared.NeedsLocationView
import net.kuecker.lodestar.shared.SettingsCaption
import net.kuecker.lodestar.shared.SettingsMenuOption
import net.kuecker.lodestar.shared.SettingsMenuPicker
import net.kuecker.lodestar.shared.SettingsNumberField
import net.kuecker.lodestar.shared.SettingsRow
import net.kuecker.lodestar.shared.monospacedDigits
import net.kuecker.lodestar.theme.LocalPalette
import net.kuecker.lodestar.theme.card
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

/** Pause before searching after an input changes, so holding a stepper does not search for every step. */
private const val SEARCH_DELAY_MS = 300L

/** The lowest altitude a window may start at, in degrees: a little below the horizon, for a skyline that dips. */
private const val LOWEST_ALTITUDE = -10

/** How long a stepper button is held before it repeats, and the gap between repeats, in milliseconds. */
private const val REPEAT_DELAY_MS = 400L
private const val REPEAT_INTERVAL_MS = 80L

/**
 * Tools ▸ Alignment finder: when does the Milky Way core, the Moon, the Sun or an object stand over a ridge or in a
 * gap between trees? The user picks the target, where it should stand (a point of the recorded skyline or a window
 * of azimuth and altitude) and the period, and gets the matching stretches of each night, with the sky chart at that
 * time and, for tonight, Aim.
 *
 * Push it onto a navigation stack (Settings ▸ Tools). The inputs are kept while another tab is shown.
 */
@Composable
fun AlignmentFinderScreen(onBack: () -> Unit) {
    val app = LocalAppModel.current
    val inputs = rememberWhilePushed("tools.alignmentFinder.inputs") { AlignmentFinderInputs() }
    val list = rememberWhilePushed("tools.alignmentFinder.list") { LazyListState() }
    val context = app.observerContext
    val now = remember { Snapshot.withoutReadObservation { app.clock.now } }
    val request = context?.let { inputs.request(it, now) }
    val aimObject = inputs.aimObject { app.catalog.objectWithID(it) }

    // A site without a recorded skyline starts on the window of numbers; the skyline choice stays, with its explanation.
    val hasSkyline = context?.let { AlignmentFinderRequest.skyline(it) } != null
    LaunchedEffect(hasSkyline) {
        if (!hasSkyline && inputs.place == AlignmentFinderPlace.SKYLINE) inputs.place = AlignmentFinderPlace.WINDOW
    }

    // The search runs on a background thread once the inputs have settled; null while it is under way.
    var outcome by remember { mutableStateOf<AlignmentFinderOutcome?>(null) }
    LaunchedEffect(request) {
        outcome = null
        if (request != null) {
            delay(SEARCH_DELAY_MS)
            outcome = withContext(Dispatchers.Default) { AlignmentFinderSearch.run(request) }
        }
    }

    ToolsScreenFrame(title = AlignmentFinderText.TITLE, onBack = onBack) {
        if (context == null) {
            NeedsLocationView()
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list) {
                item(key = "inputs") { AlignmentFinderForm(inputs, AlignmentFinderRequest.skyline(context)) }
                item(key = "results-title") { ResultsHeader() }
                resultItems(inputs, request, outcome, now, context.timeZone, aimObject)
                item(key = "end") { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

// MARK: Inputs

@Composable
private fun AlignmentFinderForm(inputs: AlignmentFinderInputs, skyline: HorizonProfile?) {
    Column {
        Text(
            AlignmentFinderText.INTRO,
            color = LocalPalette.current.secondaryText,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
        )
        WhatSection(inputs)
        WhereSection(inputs, skyline)
        WhenSection(inputs)
    }
}

@Composable
private fun WhatSection(inputs: AlignmentFinderInputs) {
    var choosing by remember { mutableStateOf(false) }
    val options = AlignmentFinderWhat.entries.map { what ->
        val title = if (what == AlignmentFinderWhat.OBJECT) inputs.chosenObject?.name ?: what.title else what.title
        SettingsMenuOption(title, what)
    }
    FormSection(title = "What") {
        control {
            SettingsMenuPicker(
                title = "Target",
                selection = inputs.what,
                options = options,
                onSelect = { what ->
                    if (what == AlignmentFinderWhat.OBJECT && inputs.chosenObject == null) choosing = true else inputs.choose(what)
                },
            )
        }
        if (inputs.what == AlignmentFinderWhat.OBJECT) {
            action(if (inputs.chosenObject == null) "Choose object…" else "Change object…", Icons.Outlined.Search) { choosing = true }
        }
    }
    if (choosing) {
        AlignmentFinderObjectSheet(
            chosen = inputs.chosenObject,
            onChoose = { inputs.choose(it) },
            onDismiss = { choosing = false },
        )
    }
}

@Composable
private fun WhereSection(inputs: AlignmentFinderInputs, skyline: HorizonProfile?) {
    FormSection(
        title = "Where",
        footer = if (inputs.place == AlignmentFinderPlace.SKYLINE) {
            if (skyline == null) null else AlignmentFinderText.SKYLINE_FOOTER
        } else {
            AlignmentFinderText.WINDOW_FOOTER
        },
    ) {
        row {
            ToolsSegmentedRow(AlignmentFinderPlace.entries, inputs.place, { it.title }, "Where") { inputs.place = it }
        }
        when (inputs.place) {
            AlignmentFinderPlace.SKYLINE -> skylineRows(inputs, skyline)
            AlignmentFinderPlace.WINDOW -> windowRows(inputs)
        }
    }
}

private fun FormRows.skylineRows(inputs: AlignmentFinderInputs, skyline: HorizonProfile?) {
    if (skyline == null) {
        row {
            Text(
                AlignmentFinderText.NO_SKYLINE_TITLE,
                color = LocalPalette.current.primaryText,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
            )
            SettingsCaption(AlignmentFinderText.NO_SKYLINE)
        }
        return
    }
    row {
        AlignmentFinderSkylinePicker(skyline, inputs.skylineAzimuth, inputs.skylineTolerance, onPick = { inputs.skylineAzimuth = it })
    }
    control {
        SettingsNumberField(
            title = "Azimuth",
            value = inputs.skylineAzimuth.toDouble(),
            onValueChange = { inputs.skylineAzimuth = it.roundToInt().mod(AZIMUTH_STEPS) },
            range = 0.0..(AZIMUTH_STEPS - 1).toDouble(),
            unit = "°",
            fractionDigits = 0,
        )
    }
    info("Skyline there", "${AlignmentFinderText.altitude(skyline.altitude(inputs.skylineAzimuth.toDouble()))} · ${AlignmentFinderText.direction(inputs.skylineAzimuth)}")
    control {
        StepperRow(
            title = "Tolerance",
            valueText = "± ${inputs.skylineTolerance}°",
            value = inputs.skylineTolerance,
            range = 1..AlignmentFinderInputs.SKYLINE_TOLERANCE_MAXIMUM,
            step = 1,
            onChange = { inputs.skylineTolerance = it },
        )
    }
}

private fun FormRows.windowRows(inputs: AlignmentFinderInputs) {
    control {
        SettingsNumberField(
            title = "Azimuth centre",
            value = inputs.windowAzimuth.toDouble(),
            onValueChange = { inputs.windowAzimuth = it.roundToInt().mod(AZIMUTH_STEPS) },
            range = 0.0..(AZIMUTH_STEPS - 1).toDouble(),
            unit = "°",
            fractionDigits = 0,
        )
    }
    info("Direction", AlignmentFinderText.direction(inputs.windowAzimuth))
    control {
        StepperRow(
            title = "Width",
            valueText = "± ${inputs.windowTolerance}°",
            value = inputs.windowTolerance,
            range = 1..AlignmentFinderInputs.WINDOW_TOLERANCE_MAXIMUM,
            step = 1,
            onChange = { inputs.windowTolerance = it },
        )
    }
    control {
        StepperRow(
            title = "Lowest altitude",
            valueText = "${inputs.minimumAltitude}°",
            value = inputs.minimumAltitude,
            range = LOWEST_ALTITUDE..inputs.maximumAltitude,
            step = 1,
            onChange = { inputs.minimumAltitude = it },
        )
    }
    control {
        StepperRow(
            title = "Highest altitude",
            valueText = "${inputs.maximumAltitude}°",
            value = inputs.maximumAltitude,
            range = inputs.minimumAltitude..MAXIMUM_ALTITUDE,
            step = 1,
            onChange = { inputs.maximumAltitude = it },
        )
    }
}

@Composable
private fun WhenSection(inputs: AlignmentFinderInputs) {
    FormSection(
        title = "When",
        footer = if (inputs.moon == AlignmentFinderMoon.DIM) AlignmentFinderText.DIM_FOOTER else null,
    ) {
        row {
            ToolsSegmentedRow(AlignmentFinderRange.entries, inputs.range, { it.title }, "Period") { inputs.range = it }
        }
        control {
            SettingsMenuPicker(
                title = "Darkness",
                selection = inputs.darkness,
                options = AlignmentFinderDarkness.entries.map { SettingsMenuOption(it.title, it) },
                onSelect = { inputs.darkness = it },
            )
        }
        control {
            SettingsMenuPicker(
                title = "Moon",
                selection = inputs.moon,
                options = AlignmentFinderMoon.entries.map { SettingsMenuOption(it.title, it) },
                onSelect = { inputs.moon = it },
            )
        }
        if (inputs.moon == AlignmentFinderMoon.DIM) {
            control {
                StepperRow(
                    title = "At most lit",
                    valueText = "${inputs.dimPercent}%",
                    value = inputs.dimPercent,
                    range = DIM_PERCENT_MINIMUM..DIM_PERCENT_MAXIMUM,
                    step = DIM_PERCENT_STEP,
                    onChange = { inputs.dimPercent = it },
                )
            }
        }
    }
}

private const val AZIMUTH_STEPS = 360
private const val MAXIMUM_ALTITUDE = 90
private const val DIM_PERCENT_MINIMUM = 5
private const val DIM_PERCENT_MAXIMUM = 95
private const val DIM_PERCENT_STEP = 5

/** A row with a title, the value and a minus and a plus button; holding a button repeats it. */
@Composable
private fun StepperRow(title: String, valueText: String, value: Int, range: IntRange, step: Int, onChange: (Int) -> Unit) {
    val palette = LocalPalette.current
    SettingsRow {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = palette.primaryText, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            StepButton("$title: less", Icons.Outlined.Remove, enabled = value - step >= range.first) {
                onChange((value - step).coerceAtLeast(range.first))
            }
            Text(
                valueText,
                color = palette.secondaryText,
                style = MaterialTheme.typography.bodyLarge.monospacedDigits(),
                modifier = Modifier.width(72.dp).semantics { contentDescription = "$title $valueText" },
                textAlign = TextAlign.Center,
            )
            StepButton("$title: more", Icons.Outlined.Add, enabled = value + step <= range.last) {
                onChange((value + step).coerceAtMost(range.last))
            }
        }
    }
}

/** A round 44 dp button that steps once on a tap and repeats while it is held. */
@Composable
private fun StepButton(description: String, icon: ImageVector, enabled: Boolean, onStep: () -> Unit) {
    val palette = LocalPalette.current
    val latestStep = rememberUpdatedState(onStep)
    Box(
        Modifier
            .size(44.dp)
            .pointerInput(enabled) {
                detectTapGestures(
                    onPress = {
                        if (!enabled) return@detectTapGestures
                        latestStep.value()
                        coroutineScope {
                            val repeat = launch {
                                delay(REPEAT_DELAY_MS)
                                while (true) {
                                    latestStep.value()
                                    delay(REPEAT_INTERVAL_MS)
                                }
                            }
                            tryAwaitRelease()
                            repeat.cancel()
                        }
                    },
                )
            }
            .semantics {
                role = Role.Button
                contentDescription = description
                if (enabled) {
                    onClick(label = description) {
                        onStep()
                        true
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (enabled) palette.accent else palette.tertiaryText, modifier = Modifier.size(22.dp))
    }
}

// MARK: Results

@Composable
private fun ResultsHeader() {
    Text(
        "RESULTS",
        color = LocalPalette.current.secondaryText,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(start = 32.dp, end = 16.dp, top = 16.dp, bottom = 6.dp).semantics { heading() },
    )
}

private fun LazyListScope.resultItems(
    inputs: AlignmentFinderInputs,
    request: AlignmentFinderRequest?,
    outcome: AlignmentFinderOutcome?,
    now: Instant,
    zone: ZoneId,
    aimObject: CelestialObject?,
) {
    when {
        request == null -> item(key = "needs") { Message(needsText(inputs)) }
        outcome == null -> item(key = "busy") { Searching() }
        outcome is AlignmentFinderOutcome.Failed -> item(key = "failed") { Message(outcome.message) }
        outcome is AlignmentFinderOutcome.Empty -> item(key = "empty") {
            val days = inputs.range.days
            EmptyStateView(
                title = AlignmentFinderText.emptyTitle(outcome.reason, request),
                icon = Icons.Outlined.Search,
                message = AlignmentFinderText.emptyMessage(outcome.reason, request, days),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        outcome is AlignmentFinderOutcome.Found -> {
            val upcoming = outcome.matches.filter { it.interval.end >= now }
            val nights = upcoming.groupBy { it.night.start }
            for ((start, matches) in nights) {
                item(key = "night.$start") { NightHeader(AlignmentFinderText.nightHeader(matches.first().night, zone)) }
                items(matches, key = { "match.${it.interval.start}" }) { match ->
                    MatchCard(match, zone, now, aimObject)
                }
            }
            item(key = "notes") {
                Column(Modifier.padding(horizontal = 32.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (outcome.isCutShort) SettingsCaption(AlignmentFinderText.CUT_SHORT)
                    SettingsCaption(AlignmentFinderText.PAST_NOTE)
                    if (inputs.followsSun) SettingsCaption(AlignmentFinderText.SUN_NOTE)
                }
            }
        }
    }
}

private fun needsText(inputs: AlignmentFinderInputs): String = when {
    inputs.target() == null -> AlignmentFinderText.NEEDS_OBJECT
    inputs.place == AlignmentFinderPlace.SKYLINE -> AlignmentFinderText.NO_SKYLINE
    else -> AlignmentFinderText.NEEDS_LOCATION
}

@Composable
private fun Message(text: String) {
    Text(
        text,
        color = LocalPalette.current.secondaryText,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
    )
}

@Composable
private fun Searching() {
    val palette = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 16.dp).semantics(mergeDescendants = true) {
            contentDescription = AlignmentFinderText.SEARCHING
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(color = palette.accent, trackColor = palette.stroke, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
        Text(AlignmentFinderText.SEARCHING, color = palette.secondaryText, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun NightHeader(text: String) {
    Text(
        text,
        color = LocalPalette.current.primaryText,
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        modifier = Modifier.padding(start = 32.dp, end = 16.dp, top = 12.dp, bottom = 4.dp).semantics { heading() },
    )
}

/** One match: when, where in the sky and what the Moon does, with the sky chart at that time and, tonight, Aim. */
@Composable
private fun MatchCard(match: SkyWindowMatch, zone: ZoneId, now: Instant, aimObject: CelestialObject?) {
    val app = LocalAppModel.current
    val palette = LocalPalette.current
    val isTonight = match.night.start <= now && now < match.night.end && match.interval.end > now
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().card(palette),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(
            Modifier.semantics(mergeDescendants = true) { contentDescription = AlignmentFinderText.rowDescription(match, zone) },
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                AlignmentFinderText.rowTitle(match, zone),
                color = palette.primaryText,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold).monospacedDigits(),
            )
            Text(AlignmentFinderText.rowDetail(match), color = palette.secondaryText, style = MaterialTheme.typography.bodyMedium)
            if (!match.isAboveSkyline) {
                Text(AlignmentFinderText.BEHIND_SKYLINE, color = palette.warning, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            LodestarButton(
                "Sky chart at that time",
                onClick = {
                    app.clock.travel(match.midpoint)
                    app.selectedTab = AppTab.SKY
                },
                modifier = Modifier.weight(1f),
                kind = LodestarButtonKind.SECONDARY,
                icon = Icons.Outlined.BlurCircular,
            )
            if (isTonight && aimObject != null) {
                LodestarButton(
                    "Aim",
                    onClick = { app.aim(aimObject) },
                    kind = LodestarButtonKind.PRIMARY,
                    icon = Icons.Outlined.GpsFixed,
                )
            }
        }
    }
}
