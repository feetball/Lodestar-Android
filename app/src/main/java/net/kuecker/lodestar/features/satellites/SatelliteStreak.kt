package net.kuecker.lodestar.features.satellites

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SatelliteAlt
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import net.kuecker.lodestar.app.AppTab
import net.kuecker.lodestar.features.aim.AimViewModel
import net.kuecker.lodestar.features.tools.apparentDeclination
import net.kuecker.lodestar.kit.foundation.DateInterval
import net.kuecker.lodestar.kit.foundation.Vector3
import net.kuecker.lodestar.kit.phototools.ExposureCalculator
import net.kuecker.lodestar.kit.phototools.ExposureRule
import net.kuecker.lodestar.kit.phototools.LensSetup
import net.kuecker.lodestar.kit.satellites.CameraFrame
import net.kuecker.lodestar.kit.satellites.SatelliteCrossing
import net.kuecker.lodestar.kit.satellites.StreakAssessment
import net.kuecker.lodestar.kit.satellites.StreakRisk
import net.kuecker.lodestar.services.OnlineFeed
import net.kuecker.lodestar.services.SatelliteGroup
import net.kuecker.lodestar.shared.ChipTone
import net.kuecker.lodestar.shared.LocalAppModel
import net.kuecker.lodestar.shared.LodestarSheet
import net.kuecker.lodestar.shared.StatusChip
import net.kuecker.lodestar.shared.monospacedDigits
import net.kuecker.lodestar.shared.rememberRetained
import net.kuecker.lodestar.theme.LocalPalette
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

/** How long an exposure the warning looks ahead. */
internal object StreakExposure {
    /** The shortest exposure considered, in seconds. */
    const val MINIMUM_SECONDS = 1

    /** The longest, in seconds: long exposures are the tracker's business, not the plain tripod's. */
    const val MAXIMUM_SECONDS = 120

    /**
     * The longest exposure that keeps stars sharp for [lens] (the NPF rule, with the target's [declination] when there is
     * one): the app has no exposure setting of its own, so this is the exposure the warning is for.
     */
    fun seconds(lens: LensSetup, declination: Double?): Int =
        ExposureCalculator.maxExposure(ExposureRule.NPF, lens, declination).seconds.roundToInt()
            .coerceIn(MINIMUM_SECONDS, MAXIMUM_SECONDS)
}

/** What the streak warning knows. */
internal sealed interface StreakState {
    /** Nothing to show: the feed is off, the pointing is not known, or there is no satellite data yet. */
    data object Hidden : StreakState

    /** The Starlink data is older than its freshness limit, so no prediction is made. */
    data object TooOld : StreakState

    /** The newest assessment of the framing. */
    class Ready(
        val assessment: StreakAssessment,
        val exposureSeconds: Int,
        val computedAt: Instant,
        val newestEpoch: Instant?,
    ) : StreakState
}

/**
 * The Starlink streak warning's background work: about once a second it takes the camera's framing from Aim, works out which
 * satellites cross it during the exposure ([StreakRisk.assess], on a background dispatcher) and publishes the result. It
 * does nothing while the Starlink data is older than its limit. When the camera has hardly moved a result under three
 * seconds old is reused.
 */
internal class StreakMonitor {
    var state: StreakState by mutableStateOf(StreakState.Hidden)
        private set

    /** Whether the details sheet is open. */
    var isSheetOpen: Boolean by mutableStateOf(false)

    private var lastBoresight: Vector3? = null
    private var lastExposure = 0

    /** Forgets the result (the warning is off or the screen is away). */
    fun clear() {
        state = StreakState.Hidden
        lastBoresight = null
        isSheetOpen = false
    }

    /** Runs until cancelled: one step per second at most. */
    suspend fun run(model: AimViewModel) {
        while (currentCoroutineContext().isActive) {
            val started = System.nanoTime()
            step(model)
            val spentMillis = (System.nanoTime() - started) / 1_000_000
            delay((INTERVAL_MILLIS - spentMillis).coerceAtLeast(0))
        }
    }

    private suspend fun step(model: AimViewModel) {
        val app = model.app
        val group: SatelliteGroup? = app.online.satellites.starlink
        val observer = app.observerContext?.observer
        val frame = model.engine.frame()
        if (group == null || observer == null || frame == null) {
            state = StreakState.Hidden
            return
        }
        val now = Instant.now()
        if (!group.isFresh(now)) {
            state = StreakState.TooOld
            return
        }
        val configuration = model.configuration
        val target = model.guidanceTarget
        val exposure = StreakExposure.seconds(app.lensSetup, target?.let { apparentDeclination(app, it) })
        val boresight = frame.pointing.boresightVector
        val previous = state
        val moved = lastBoresight?.let { it.angle(boresight) > REUSE_DEGREES } ?: true
        if (previous is StreakState.Ready && !moved && exposure == lastExposure &&
            now.toEpochMilli() - previous.computedAt.toEpochMilli() < REUSE_MILLIS
        ) {
            return
        }
        val cameraFrame = CameraFrame.create(frame.pointing, configuration.lensFrame.horizontal, configuration.lensFrame.vertical)
        if (cameraFrame == null) {
            state = StreakState.Hidden
            return
        }
        val assessment = withContext(Dispatchers.Default) {
            StreakRisk.assess(group.satellites, observer, cameraFrame, DateInterval(now, exposure.toDouble()))
        }
        lastBoresight = boresight
        lastExposure = exposure
        state = StreakState.Ready(assessment, exposure, now, group.newest?.epoch)
    }

    private companion object {
        const val INTERVAL_MILLIS = 1_000L
        const val REUSE_MILLIS = 3_000L
        const val REUSE_DEGREES = 0.5
    }
}

/**
 * The Starlink streak warning in Aim: a chip under the status chips, for example "Starlink: 3 crossings in 30 s · 12%
 * streak risk", that opens the list of crossings. Shown only while the Starlink feed is on and the pointing is known.
 */
@Composable
internal fun SatelliteStreakChip(model: AimViewModel) {
    val app = LocalAppModel.current
    val isOn = app.online.isEnabled(OnlineFeed.STARLINK)
    val isActive = app.selectedTab == AppTab.AIM && app.isForeground
    val monitor = rememberRetained("satellites.streak") { StreakMonitor() }
    val library = app.online.satellites
    LaunchedEffect(isOn, isActive, library.starlink) {
        if (isOn && isActive) monitor.run(model) else monitor.clear()
    }
    when (val state = monitor.state) {
        StreakState.Hidden -> Unit
        StreakState.TooOld -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            StatusChip(Icons.Outlined.SatelliteAlt, SatelliteText.STREAK_STALE_CHIP, tone = ChipTone.NEUTRAL)
        }
        is StreakState.Ready -> {
            val text = SatelliteText.streakChip(
                state.assessment.crossings.size, state.exposureSeconds,
                state.assessment.probabilityOfStreak(state.exposureSeconds.toDouble()),
            )
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                StatusChip(
                    Icons.Outlined.SatelliteAlt, text,
                    tone = if (state.assessment.crossings.isEmpty()) ChipTone.NEUTRAL else ChipTone.WARNING,
                    modifier = Modifier
                        .clickable(onClickLabel = "Show the Starlink crossings", role = Role.Button) { monitor.isSheetOpen = true }
                        .padding(vertical = 9.dp)
                        .semantics { contentDescription = text },
                )
            }
            if (monitor.isSheetOpen) StreakSheet(state, onDismiss = { monitor.isSheetOpen = false })
        }
    }
}

/** The crossings of the framing: entry and exit times and brightness, with the numbers behind the chip. */
@Composable
private fun StreakSheet(state: StreakState.Ready, onDismiss: () -> Unit) {
    val app = LocalAppModel.current
    val palette = LocalPalette.current
    val zone = app.observerContext?.timeZone ?: ZoneId.systemDefault()
    val assessment = state.assessment
    LodestarSheet(onDismiss = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Starlink crossings", color = palette.primaryText, style = MaterialTheme.typography.titleLarge)
            Text(
                SatelliteText.streakSummary(state.exposureSeconds, assessment.sunlitAboveSummaryAltitudeCount,
                    assessment.crossingsPerMinute, assessment.probabilityOfStreak(state.exposureSeconds.toDouble())),
                color = palette.secondaryText,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (assessment.crossings.isEmpty()) {
                Text(SatelliteText.NO_CROSSINGS, color = palette.primaryText, style = MaterialTheme.typography.bodyMedium)
            }
            val shown = assessment.crossings.take(MAXIMUM_ROWS)
            for ((index, crossing) in shown.withIndex()) {
                if (index > 0) HorizontalDivider(thickness = 0.5.dp, color = palette.stroke)
                CrossingRow(crossing, zone)
            }
            if (assessment.crossings.size > shown.size) {
                Text(
                    "and ${assessment.crossings.size - shown.size} more", color = palette.secondaryText,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(SatelliteText.STREAK_NOTE, color = palette.tertiaryText, style = MaterialTheme.typography.bodySmall)
            Text(SatelliteText.ATTRIBUTION, color = palette.tertiaryText, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun CrossingRow(crossing: SatelliteCrossing, zone: ZoneId) {
    val palette = LocalPalette.current
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        Text(crossing.satellite.name, color = palette.primaryText, style = MaterialTheme.typography.bodyLarge)
        Text(
            SatelliteText.crossing(crossing, zone),
            color = palette.secondaryText,
            style = MaterialTheme.typography.bodyMedium.monospacedDigits(),
        )
    }
}

private const val MAXIMUM_ROWS = 25
