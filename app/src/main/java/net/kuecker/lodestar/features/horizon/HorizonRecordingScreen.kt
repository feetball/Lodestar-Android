package net.kuecker.lodestar.features.horizon

import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.kuecker.lodestar.app.AppModel
import net.kuecker.lodestar.kit.foundation.GeographicLocation
import net.kuecker.lodestar.kit.horizon.AzimuthRange
import net.kuecker.lodestar.kit.horizon.HorizonRecorder
import net.kuecker.lodestar.model.HeadingSource
import net.kuecker.lodestar.model.SavedSite
import net.kuecker.lodestar.services.CameraAuthorization
import net.kuecker.lodestar.services.CameraPreview
import net.kuecker.lodestar.services.MotionStatus
import net.kuecker.lodestar.shared.AppFormat
import net.kuecker.lodestar.shared.HideSystemBarsInWindow
import net.kuecker.lodestar.shared.LocalAppModel
import net.kuecker.lodestar.shared.LodestarButton
import net.kuecker.lodestar.shared.LodestarButtonKind
import net.kuecker.lodestar.theme.LocalPalette
import net.kuecker.lodestar.theme.LodestarTheme

/** Further from the site than this, the compass is corrected for the declination of the wrong place. */
private const val NEAR_SITE_METRES = 100_000.0

/** The share of the screen height the top panel may take before it scrolls. */
private const val PANEL_HEIGHT_SHARE = 0.45f

/** The stretches still to sweep are listed once this much of the circle is recorded. */
private const val SHOW_GAPS_FROM = 0.5

/** Where the recording is: sweeping, or answering a question about it. */
private sealed interface HorizonStep {
    data object Sweeping : HorizonStep

    /** Done was tapped before the circle was complete. */
    data class Incomplete(val coverage: Double, val gaps: List<AzimuthRange>) : HorizonStep

    /** Cancel was tapped with something recorded. */
    data object ConfirmDiscard : HorizonStep
}

/**
 * The screen that records a site's skyline: a full-screen camera picture with a crosshair, the direction of the
 * phone's back camera, the skyline recorded so far as a strip round the whole circle, and Cancel and Done. The user
 * holds the phone with the camera on the skyline and turns slowly all the way round.
 *
 * It keeps the motion sensors (and the camera, when the settings want its picture) running while it is shown and
 * saves the result into [site] when the user taps Done. [onClose] is called after saving or cancelling.
 */
@Composable
internal fun HorizonRecordingScreen(site: SavedSite, onClose: () -> Unit) {
    val app = LocalAppModel.current
    val context = LocalContext.current
    val palette = LocalPalette.current
    val sweep = remember(site.id) { HorizonSweep(site.horizon) }
    var step by remember { mutableStateOf<HorizonStep>(HorizonStep.Sweeping) }

    DisposableEffect(app) {
        app.keepsMotionRunning = true
        app.keepsCameraRunning = true
        onDispose {
            app.keepsMotionRunning = false
            app.keepsCameraRunning = false
        }
    }
    // The newest sensor sample goes to the recorder on every frame; the screen is refreshed ten times a second.
    LaunchedEffect(sweep) {
        while (isActive) {
            withFrameNanos { nanos ->
                feed(app, sweep)
                sweep.publishIfDue(nanos)
            }
        }
    }

    fun save(recording: HorizonRecorder.Recording) {
        // The stored site may have changed while recording (the editor only keeps a draft), so build on that one.
        val current = app.settings.savedSites.firstOrNull { it.id == site.id } ?: site
        val updated = current.edited(horizon = recording.profile)
        if (updated != null) {
            app.settingsStore.updateSite(updated)
            HorizonCoverageMemory.store(context, site.id, updated.horizon, recording.coverage)
        }
        onClose()
    }

    fun requestDone() {
        val recording = sweep.finish(HorizonRecorder.UnrecordedFill.KEEP_BASE)
        if (recording.isComplete) {
            save(recording)
        } else {
            step = HorizonStep.Incomplete(recording.coverage, recording.gaps)
        }
    }

    fun requestCancel() {
        if (sweep.hasRecording) step = HorizonStep.ConfirmDiscard else onClose()
    }

    Dialog(
        onDismissRequest = ::requestCancel,
        properties = DialogProperties(
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        LodestarTheme(palette, redFilter = app.settings.redFilterOverlay) {
            HorizonWindowEffect()
            Box(Modifier.fillMaxSize().background(palette.background)) {
                // While a question is asked, what is behind it is out of reach for touch and for screen readers.
                val layers = if (step == HorizonStep.Sweeping) Modifier else Modifier.clearAndSetSemantics { }
                Box(layers.fillMaxSize()) {
                    HorizonCameraLayer()
                    HorizonCrosshair(Modifier.fillMaxSize())
                    HorizonTopPanel(
                        site = site,
                        sweep = sweep,
                        onCancel = ::requestCancel,
                        onDone = ::requestDone,
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                    HorizonBottomPanel(sweep, Modifier.align(Alignment.BottomCenter))
                }
                when (val current = step) {
                    HorizonStep.Sweeping -> Unit
                    is HorizonStep.Incomplete -> HorizonIncompleteDecision(
                        step = current,
                        hasStoredSkyline = sweep.storedSkyline != null,
                        onKeepSweeping = { step = HorizonStep.Sweeping },
                        onSave = { fill -> save(sweep.finish(fill)) },
                    )
                    HorizonStep.ConfirmDiscard -> HorizonDecision(
                        title = "Discard This Recording?",
                        message = "What you have recorded so far is not saved.",
                    ) {
                        LodestarButton(
                            "Keep Recording",
                            onClick = { step = HorizonStep.Sweeping },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        LodestarButton(
                            "Discard",
                            onClick = onClose,
                            modifier = Modifier.fillMaxWidth(),
                            kind = LodestarButtonKind.SECONDARY,
                        )
                    }
                }
            }
        }
    }
}

/** Reads the newest sample and the heading conditions from the app and gives them to [sweep]. */
private fun feed(app: AppModel, sweep: HorizonSweep) {
    val motion = app.motion
    val sample = motion.latestSample() ?: return
    val mount = app.mountProfile
    val alignment = app.alignment
    val isGyroLock = motion.headingSource == HeadingSource.GYRO_LOCK
    // In Gyro Lock the heading is only meaningful once it was transferred from the compass or set by a star.
    val isAligned = alignment.points.isNotEmpty() && alignment.isApplicable(mount)
    val isHeadingSet = !isGyroLock || motion.isGyroHeadingReferenced || isAligned
    val quality = HorizonSweep.quality(motion.headingSource, isHeadingSet, sample.compassQuality)
    val hasInterference = !isGyroLock && (motion.magneticDisturbance ?: 0.0) > HorizonSweep.INTERFERENCE_THRESHOLD
    sweep.feed(sample, alignment.headingOffset(mount), quality, isHeadingMissing = !isHeadingSet, hasInterference)
}

/**
 * Configures the window of the dialog: no system bars (a swipe from the edge shows them), the screen stays on while
 * the user turns round, and at night it is as dim as the rest of the app.
 */
@Composable
private fun HorizonWindowEffect() {
    val app = LocalAppModel.current
    val view = LocalView.current
    val window = (view.parent as? DialogWindowProvider)?.window
    val brightness = if (app.isNightModeActive && app.settings.dimScreenAtNight) app.settings.nightBrightness else null
    HideSystemBarsInWindow()
    DisposableEffect(window, brightness) {
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (window != null && brightness != null) {
            val attributes = window.attributes
            attributes.screenBrightness = brightness.toFloat().coerceIn(0.01f, 1f)
            window.attributes = attributes
        }
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}

/** Whether the camera picture is wanted behind the crosshair: the Aim screen's preferences, night mode included. */
@Composable
private fun isCameraWanted(): Boolean {
    val settings = LocalAppModel.current.settings
    return settings.cameraPreviewEnabled && (!LocalPalette.current.isNight || settings.cameraPreviewAtNight)
}

/** The camera picture, aspect-filled and dimmed at night, when the settings want it. */
@Composable
private fun HorizonCameraLayer() {
    val app = LocalAppModel.current
    val palette = LocalPalette.current
    if (!isCameraWanted()) return
    Box(Modifier.fillMaxSize().clearAndSetSemantics { }) {
        CameraPreview(app.camera, Modifier.fillMaxSize())
        if (palette.isNight) {
            // The same dimming as behind the Aim screen: the picture is red already, this lowers its brightness.
            val opacity = 0.35 + 0.45 * app.settings.nightIntensity
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = (1 - opacity).toFloat())))
        }
    }
}

// MARK: Panels

/** Cancel, the title and Done; under them the feedback, the instruction and, if needed, the camera access offer. */
@Composable
private fun HorizonTopPanel(
    site: SavedSite,
    sweep: HorizonSweep,
    onCancel: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val state = sweep.state
    val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    val maximumHeight = windowHeight * PANEL_HEIGHT_SHARE
    val colors = ButtonDefaults.textButtonColors(
        contentColor = palette.accent,
        disabledContentColor = palette.tertiaryText,
    )
    val shape = RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp)
    Column(
        modifier
            .fillMaxWidth()
            .background(palette.background.copy(alpha = 0.86f), shape)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .heightIn(max = maximumHeight)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onCancel, colors = colors) { Text("Cancel") }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "Record Skyline",
                    color = palette.primaryText,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    site.name,
                    color = palette.secondaryText,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = onDone, enabled = state.recordedBins > 0, colors = colors) {
                Text("Done", fontWeight = FontWeight.SemiBold)
            }
        }
        Column(
            Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            HorizonMessages(site, state)
            HorizonCameraAccess()
        }
    }
}

/** The live direction, the strip, how much is recorded, and Start Over. */
@Composable
private fun HorizonBottomPanel(sweep: HorizonSweep, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val state = sweep.state
    Column(
        modifier
            .fillMaxWidth()
            .background(palette.background.copy(alpha = 0.86f), RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HorizonReadout(state)
        HorizonStrip(state, sweep.storedSkyline)
        HorizonCoverageRow(state, onStartOver = sweep::restart)
    }
}

/** Feedback (spoken by screen readers when it changes), warnings and the instruction. */
@Composable
private fun HorizonMessages(site: SavedSite, state: HorizonSweepState) {
    val app = LocalAppModel.current
    val palette = LocalPalette.current
    val isUnavailable = app.motion.status == MotionStatus.Unavailable
    val feedback: Pair<String, Boolean>? = when {
        isUnavailable -> "This phone has no motion sensors, so it cannot record a skyline." to true
        state.hint.text != null -> state.hint.text to true
        state.azimuth == null -> "Waiting for the motion sensors…" to false
        state.isComplete -> "All the way round. Tap Done to save the skyline." to false
        else -> null
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
            if (feedback != null) {
                val (text, isWarning) = feedback
                val color = if (isWarning) palette.warning else palette.positive
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                    Icon(
                        if (isWarning) Icons.Outlined.Warning else Icons.Outlined.CheckCircle,
                        contentDescription = null,
                        tint = color,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text,
                        color = color,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    )
                }
            }
        }
        if (!state.isComplete) {
            Text(
                "Aim at the skyline and turn slowly all the way round; sweep back over a stretch to redo it.",
                color = palette.primaryText,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        locationWarning(app.observerContext?.location, site)?.let {
            Text(it, color = palette.warning, style = MaterialTheme.typography.bodySmall)
        }
        if (!isCameraWanted()) {
            Text(
                "The camera picture is off in Settings (Camera & Lens, and Night Mode at night). You can still " +
                    "record by pointing the back of the phone along the skyline.",
                color = palette.secondaryText,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** Why the directions might be off for this site, or `null`. */
private fun locationWarning(observer: GeographicLocation?, site: SavedSite): String? {
    if (observer == null) {
        return "Lodestar does not know where you are yet, so it cannot turn compass directions into true north. " +
            "Choose this site or Current Location as your observing location first."
    }
    if (HorizonFormat.distanceMetres(observer, site.location) > NEAR_SITE_METRES) {
        return "You are observing from a place far from this site, so the compass is corrected for the wrong " +
            "location. Choose this site or Current Location as your observing location first."
    }
    return null
}

/** Explains the camera picture and asks for access, as the Aim screen does. */
@Composable
private fun HorizonCameraAccess() {
    val app = LocalAppModel.current
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    if (!isCameraWanted()) return
    val authorization = app.camera.authorization
    if (authorization != CameraAuthorization.NOT_DETERMINED && authorization != CameraAuthorization.DENIED) return
    val isDenied = authorization == CameraAuthorization.DENIED
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "Show the skyline through the camera?",
            color = palette.primaryText,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
        )
        Text(
            if (isDenied) {
                "Camera access is off for Lodestar. Allow it in the phone's permission settings to keep the " +
                    "skyline on the crosshair. Without it you can still record, but you have to aim by eye."
            } else {
                "The live picture helps you keep the skyline on the crosshair. Nothing is recorded or saved. " +
                    "Without it you can still record, but you have to aim by eye."
            },
            color = palette.secondaryText,
            style = MaterialTheme.typography.bodySmall,
        )
        LodestarButton(
            if (isDenied) "Allow Camera Access" else "Enable Camera",
            onClick = { scope.launch { app.camera.requestAccess() } },
            icon = Icons.Filled.CameraAlt,
        )
    }
}

/** The share recorded, what is still to sweep, and Start Over. */
@Composable
private fun HorizonCoverageRow(state: HorizonSweepState, onStartOver: () -> Unit) {
    val palette = LocalPalette.current
    val showsGaps = state.coverage >= SHOW_GAPS_FROM && state.gaps.isNotEmpty()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
            Text(
                "${AppFormat.percent(state.coverage)} covered",
                color = palette.primaryText,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
            )
            if (showsGaps) {
                Text(
                    "Still to sweep: ${HorizonFormat.gapsText(state.gaps)}",
                    color = palette.secondaryText,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        TextButton(
            onClick = onStartOver,
            enabled = state.recordedBins > 0,
            colors = ButtonDefaults.textButtonColors(
                contentColor = palette.accent,
                disabledContentColor = palette.tertiaryText,
            ),
        ) { Text("Start Over") }
    }
}

// MARK: Questions

/** Done was tapped with part of the circle missing: sweep on, keep the old skyline or leave it, or join the ends. */
@Composable
private fun HorizonIncompleteDecision(
    step: HorizonStep.Incomplete,
    hasStoredSkyline: Boolean,
    onKeepSweeping: () -> Unit,
    onSave: (HorizonRecorder.UnrecordedFill) -> Unit,
) {
    val recorded = "${AppFormat.percent(step.coverage)} of the circle is recorded. " +
        "Not yet swept: ${HorizonFormat.gapsText(step.gaps)}."
    HorizonDecision(
        title = "Not All the Way Round",
        message = if (hasStoredSkyline) {
            "$recorded The old skyline can stay in those stretches."
        } else {
            "$recorded Those stretches can stay open, as a clear horizon, or be joined by straight lines."
        },
    ) {
        LodestarButton("Keep Sweeping", onClick = onKeepSweeping, modifier = Modifier.fillMaxWidth())
        LodestarButton(
            if (hasStoredSkyline) "Keep the Old Skyline for the Rest" else "Leave the Rest Open",
            onClick = { onSave(HorizonRecorder.UnrecordedFill.KEEP_BASE) },
            modifier = Modifier.fillMaxWidth(),
            kind = LodestarButtonKind.SECONDARY,
        )
        LodestarButton(
            "Join the Ends With Straight Lines",
            onClick = { onSave(HorizonRecorder.UnrecordedFill.INTERPOLATE) },
            modifier = Modifier.fillMaxWidth(),
            kind = LodestarButtonKind.SECONDARY,
        )
    }
}

/** A question over the screen: a scrim that swallows touches and a card with [actions]. */
@Composable
private fun HorizonDecision(title: String, message: String, actions: @Composable () -> Unit) {
    val palette = LocalPalette.current
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .pointerInput(Unit) { detectTapGestures { } }
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.fillMaxWidth().background(palette.surfaceRaised, RoundedCornerShape(16.dp)).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                title,
                color = palette.primaryText,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.semantics { heading() },
            )
            Text(message, color = palette.secondaryText, style = MaterialTheme.typography.bodyMedium)
            actions()
        }
    }
}
