package net.kuecker.lodestar.features.aim

import android.content.SharedPreferences
import androidx.compose.runtime.MutableDoubleState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Rect
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import net.kuecker.lodestar.app.AppModel
import net.kuecker.lodestar.features.mosaic.MosaicSession
import net.kuecker.lodestar.features.satellites.SatelliteAimSession
import net.kuecker.lodestar.kit.catalog.CelestialObject
import net.kuecker.lodestar.kit.foundation.AstroMath
import net.kuecker.lodestar.kit.foundation.Observer
import net.kuecker.lodestar.kit.foundation.SkyFrame
import net.kuecker.lodestar.kit.foundation.Vector3
import net.kuecker.lodestar.kit.mosaic.MosaicPlan
import net.kuecker.lodestar.kit.pointing.GuidanceState
import net.kuecker.lodestar.model.NightModeSetting
import net.kuecker.lodestar.services.CameraGeometry
import net.kuecker.lodestar.services.CatalogService
import net.kuecker.lodestar.shared.ScreenAxes
import java.time.Instant
import kotlin.math.abs

/**
 * The state and background work behind the Aim screen. A screen makes one with `remember { AimViewModel(app) }`
 * (pass the device-local `SharedPreferences` as [preferences] to remember the zoom across launches; without them
 * it is remembered for the process) and then, on every composition: builds `AimConfiguration(app)` and hands it to
 * [apply] (idempotent, so calling it from a `SideEffect` is fine); calls [syncTarget] whenever
 * `guidanceTarget?.id` changes; and runs the three suspending loops from `LaunchedEffect`s while the tab is showing
 * and the app is in front: `LaunchedEffect(skyInputs, isActive) { model.keepSkyCurrent(skyInputs) }` and
 * `keepRiseInfoCurrent(skyInputs)` with `skyInputs = model.skyInputs(configuration)`, and
 * `LaunchedEffect(isActive) { model.runLoop() }` (call [stopFeedback] when inactive and on disposal). While a mosaic
 * runs ([mosaic] has a plan) a fourth loop, `LaunchedEffect(plan, observer, isActive) { model.keepMosaicCurrent(plan,
 * observer) }`, refreshes where the panels stand, and `LaunchedEffect(plan, step) { model.syncMosaic() }` leads
 * guidance to the panel being shot at once. Only low-rate
 * values are Compose state ([configuration], [skyContent], [riseInfo], [hudRotation], [virtualFieldOfView],
 * [alignmentTarget], [mosaicPointings], [previewOffset]); what changes with every sensor sample is not published: the overlay and the readouts call
 * `engine.frame()` when they draw (or in a per-frame loop of their own), so the composition is not invalidated 60
 * times a second. [SkyOverlayView] publishes the display rotation in [screenAxes]. Pinch gestures call
 * [updatePinch]/[endPinch] while [cameraGeometry] is `null`. With a [previewOffset] the sky, the target and the guidance
 * are worked out for a later (or earlier) moment while the phone's pointing stays live; the screen ends the preview
 * ([endPreview]) when the tab or the app goes away. Main thread only.
 */
class AimViewModel(val app: AppModel, private val preferences: SharedPreferences? = null) {
    private companion object {
        /** Milliseconds between sky rebuilds. The sky moves 15″ per second, far below what the screen can show. */
        const val SKY_REFRESH_INTERVAL_MS = 2_000L

        /** Milliseconds between searches for the target's next rise. */
        const val RISE_REFRESH_INTERVAL_MS = 60_000L

        /** Milliseconds between the recalculations of where the panels of a mosaic stand in the sky. */
        const val MOSAIC_REFRESH_INTERVAL_MS = 1_000L

        /** A sky built for an instant this many milliseconds away from the preview's counts as built for it. */
        const val SKY_FRESH_MS = 30_000L
        const val LOOP_INTERVAL_MS = 33L

        /**
         * Where the zoom of the virtual sky is remembered, so it survives the screen being rebuilt (night mode
         * starting at dusk rebuilds the tabs) and, with preferences, relaunches.
         */
        const val FIELD_OF_VIEW_KEY = "lodestar.aim.virtualFieldOfView"
    }

    /** Preferences and calibration in effect; the screen refreshes them whenever the app model changes. */
    var configuration: AimConfiguration by mutableStateOf(AimConfiguration(app))
        private set

    val engine = AimFrameEngine(app.motion, configuration)

    var skyContent: AimSkyContent? by mutableStateOf(null)
        private set
    var riseInfo: AimRiseInfo? by mutableStateOf(null)
        private set
    var hudRotation: AimHUDRotation by mutableStateOf(AimHUDRotation.UPRIGHT)
        private set

    /**
     * The mosaic being shot (shared with the Sky chart). While it has a plan, guidance leads to the current panel
     * instead of the target; see [keepMosaicCurrent] and [syncMosaic].
     */
    val mosaic: MosaicSession = app.retained.getOrPut(MosaicSession.KEY) { MosaicSession() }

    /**
     * The satellite (or the peak of a pass) Aim follows, chosen on the satellite pass screens (shared with them). While it
     * is set, guidance leads to it instead of the target; see [syncSatellite].
     */
    val satellite: SatelliteAimSession = app.retained.getOrPut(SatelliteAimSession.KEY) { SatelliteAimSession() }

    /** Where every panel of the running mosaic stands in the sky, as of about a second ago; `null` without a mosaic. */
    var mosaicPointings: AimMosaicPointings? by mutableStateOf(null)
        private set

    /** An object the alignment sheet asks to be guided to; it replaces the app's target while it is set. */
    var alignmentTarget: CelestialObject? by mutableStateOf(null)

    /**
     * How far from now the sky is previewed, in seconds: 0 is live, positive looks ahead, negative back, within
     * ±48 hours ([AimPreview.LIMIT_SECONDS]). Change it with [stepPreview], [previewAt] and [endPreview].
     */
    var previewOffset: Double by mutableDoubleStateOf(0.0)
        private set

    /** Whether the time bar above the controls is showing. */
    var isTimeBarOpen: Boolean by mutableStateOf(false)
        private set

    /**
     * The screen's right and up in device coordinates. Phones stay in portrait (the default); on large screens the
     * display can rotate. [SkyOverlayView] keeps it current; [runLoop] needs it for the HUD rotation, and anything
     * that expresses a direction on the screen from device or mount axes (the guidance arrow) can use it too.
     */
    var screenAxes: ScreenAxes = ScreenAxes.natural

    /**
     * The readout at the top of the stack of panels at the bottom of the HUD, in window pixels, or `null` while the
     * HUD has no such stack (its wide layout). [GuidanceHUD] publishes it; the arrow around the reticle keeps its
     * distance readout clear of the stack with it.
     */
    var hudPanels: Rect? by mutableStateOf(null)

    private val fieldOfViewState: MutableDoubleState = app.retained.getOrPut(FIELD_OF_VIEW_KEY) {
        mutableDoubleStateOf(storedFieldOfView())
    }

    /** The field of view along the screen's long side without a camera picture, in degrees. */
    var virtualFieldOfView: Double
        get() = fieldOfViewState.doubleValue
        private set(value) {
            fieldOfViewState.doubleValue = value
        }

    private var hasHUDRotation = false
    private var pinchStartFieldOfView: Double? = null
    private var wasOnTarget: Boolean? = null
    private var panelKey: Int? = null

    // Derived state

    /** Whether the sky is shown for another moment than now. */
    val isPreviewing: Boolean get() = previewOffset != 0.0

    /**
     * The instant the sky, the target, the planets and the guidance are worked out for: the app clock moved by the
     * [previewOffset]. Reads the app clock, so a composition that asks for it follows the clock's seconds.
     */
    val date: Instant get() = AimPreview.date(app.clock.now, previewOffset)

    /**
     * Whether the Sun is up at the previewed moment, or `null` without a preview and while the sky is still being rebuilt
     * for the moment that was just chosen.
     */
    val isDaylightAtPreview: Boolean?
        get() {
            if (!isSkyBuiltForPreview) return null
            return skyContent?.sun?.let { AimPreview.isDaylight(it.horizontal.altitude) }
        }

    /** Whether there is a preview and [skyContent] has been rebuilt for the moment it shows. */
    val isSkyBuiltForPreview: Boolean
        get() {
            if (!isPreviewing) return false
            val built = skyContent?.snapshot?.date ?: return false
            return abs(built.toEpochMilli() - date.toEpochMilli()) <= SKY_FRESH_MS
        }

    /** The object guidance leads to: the alignment sheet's choice, else the app's target. */
    val guidanceTarget: CelestialObject? get() = alignmentTarget ?: app.target

    /**
     * The optics of the camera picture behind the sky, or `null` when the virtual lens is in use: whenever the
     * picture is not wanted or not running.
     */
    val cameraGeometry: CameraGeometry?
        get() = if (configuration.wantsCameraPreview && app.camera.isRunning) app.camera.geometry else null

    /** What the sky snapshot depends on, or `null` while it cannot be built (no location, catalog not loaded). */
    fun skyInputs(configuration: AimConfiguration): AimSkyInputs? {
        val context = app.observerContext ?: return null
        if (app.catalog.loadState != CatalogService.LoadState.Loaded) return null
        return AimSkyInputs(context.observer, configuration.skyOptions, guidanceTarget, context.horizon, previewOffset)
    }

    // Configuration

    /** Takes over new preferences and calibration; nothing happens while they are unchanged. */
    fun apply(configuration: AimConfiguration) {
        if (configuration == this.configuration) return
        this.configuration = configuration
        engine.configure(configuration)
    }

    /** Points the engine at the current guidance target. */
    fun syncTarget() {
        engine.setTarget(guidanceTarget?.id)
        // Choosing another target ends the satellite guidance.
        satellite.targetChanged(guidanceTarget?.id)
        wasOnTarget = null
    }

    /**
     * Points the engine at the chosen satellite, or back at the target when there is none. A satellite chosen for another
     * observing place is dropped. Call whenever [SatelliteAimSession.source] or the observer changes.
     */
    fun syncSatellite() {
        val source = satellite.source
        if (source != null && source.observer != app.observerContext?.observer) satellite.stop()
        // A satellite moves too fast to look ahead at: choosing one ends the preview.
        if (satellite.source != null) endPreview()
        engine.setSatellite(satellite.source, ::satelliteNow)
        wasOnTarget = null
    }

    /**
     * The instant to look a satellite up at: the wall clock, since the app clock ticks once a second and a satellite
     * moves up to a degree in that time. A stopped app clock (screenshot mode) wins.
     */
    private fun satelliteNow(): Instant {
        val tick = Snapshot.withoutReadObservation { app.clock.now }
        val wall = Instant.now()
        return if (abs(wall.toEpochMilli() - tick.toEpochMilli()) < 2_000) wall else tick
    }

    // Background work

    /**
     * Rebuilds the sky for [inputs] now and then every few seconds until cancelled. Restart it (`LaunchedEffect`
     * keyed on the inputs) whenever they change; a build still running for older inputs is discarded.
     */
    suspend fun keepSkyCurrent(inputs: AimSkyInputs?) {
        if (inputs == null) return
        val catalog = app.catalog.catalog ?: return
        while (currentCoroutineContext().isActive) {
            val date = AimPreview.date(app.clock.now, inputs.previewOffset)
            val content = withContext(Dispatchers.Default) { AimSkyContent.build(catalog, inputs, date) }
            skyContent = content
            engine.update(content.snapshot.target)
            delay(SKY_REFRESH_INTERVAL_MS)
        }
    }

    /** Keeps [riseInfo] (when the target next rises, counted from the previewed moment) current while there is a target. */
    suspend fun keepRiseInfoCurrent(inputs: AimSkyInputs?) {
        val target = inputs?.target
        if (inputs == null || target == null) {
            riseInfo = null
            return
        }
        val observer = inputs.observer
        while (currentCoroutineContext().isActive) {
            val date = AimPreview.date(app.clock.now, inputs.previewOffset)
            val info = withContext(Dispatchers.Default) { AimRiseInfo.compute(target, observer, date) }
            riseInfo = info
            delay(RISE_REFRESH_INTERVAL_MS)
        }
    }

    /**
     * Works out where the panels of [plan] stand in the sky now and then once a second until cancelled (equatorial
     * panels drift with the sky), and leads guidance to the current one, at the previewed moment while there is a
     * preview. Restart it (`LaunchedEffect` keyed on the plan, the observer and the [previewOffset]) whenever they
     * change; without a plan or an observer it ends the mosaic guidance and returns.
     */
    suspend fun keepMosaicCurrent(plan: MosaicPlan?, observer: Observer?) {
        if (plan == null || observer == null) {
            mosaicPointings = null
            syncMosaic()
            return
        }
        while (currentCoroutineContext().isActive) {
            val date = this.date
            mosaicPointings = withContext(Dispatchers.Default) { AimMosaicPointings.make(plan, SkyFrame(date, observer)) }
            syncMosaic()
            delay(MOSAIC_REFRESH_INTERVAL_MS)
        }
    }

    /**
     * Points guidance at the mosaic's current panel, or back at the target when no mosaic runs or all its panels are
     * shot. Call it whenever the panel changes ([MosaicSession.step], [MosaicSession.plan]); [keepMosaicCurrent] does
     * after every refresh.
     */
    fun syncMosaic() {
        val plan = mosaic.plan
        val pointings = mosaicPointings
        val aim = if (plan != null && pointings != null && pointings.plan === plan) pointings.aim(mosaic.step) else null
        if (aim?.key != panelKey) wasOnTarget = null
        panelKey = aim?.key
        engine.setPanel(aim)
    }

    /**
     * Runs the aiming logic about 30 times a second until cancelled: the HUD rotation, the audio ticks and the
     * haptics for reaching and leaving the target.
     */
    suspend fun runLoop() {
        while (currentCoroutineContext().isActive) {
            step()
            delay(LOOP_INTERVAL_MS)
        }
    }

    private fun step() {
        val frame = engine.frame() ?: return
        updateHUDRotation(frame.sample.gravity)
        val guidance = frame.guidance
        if (guidance == null) {
            if (wasOnTarget != null) stopFeedback()
            return
        }
        app.feedback.guidanceTick(guidance.separation)
        val isOnTarget = guidance.state == GuidanceState.ON_TARGET
        val previously = wasOnTarget
        if (previously != null && previously != isOnTarget) {
            if (isOnTarget) app.feedback.targetAcquired() else app.feedback.targetLost()
        }
        wasOnTarget = isOnTarget
    }

    /** [gravity] arrives in device coordinates; the HUD turns with the gravity seen on the screen. */
    private fun updateHUDRotation(gravity: Vector3) {
        val axes = screenAxes
        val onScreen = Vector3(gravity.dot(axes.right), gravity.dot(axes.up), gravity.dot(axes.right.cross(axes.up)))
        val rotation = AimHUDRotation.updated(
            if (hasHUDRotation) hudRotation else null, onScreen, configuration.mount,
        )
        hasHUDRotation = true
        if (rotation != hudRotation) hudRotation = rotation
    }

    /** Silences the guidance audio; call when the screen goes away. */
    fun stopFeedback() {
        app.feedback.stopGuidanceAudio()
        wasOnTarget = null
    }

    // Time preview

    /** Shows or hides the time bar. Hiding it does not end a preview; the chip at the top does that. */
    fun toggleTimeBar() {
        isTimeBarOpen = !isTimeBarOpen
    }

    /** Moves the previewed moment by [step], from now when the sky is live; a step back to now ends the preview. */
    internal fun stepPreview(step: AimPreview.Step) {
        changePreviewOffset(previewOffset + step.seconds)
    }

    /** Shows the sky at [date] (held within 48 hours of now). */
    fun previewAt(date: Instant) {
        changePreviewOffset(AimPreview.offset(date, app.clock.now))
    }

    /** Back to the live sky. */
    fun endPreview() {
        changePreviewOffset(0.0)
    }

    /** Back to the live sky and out of the time bar: the Aim tab went away, so nothing stale is left on return. */
    fun leave() {
        endPreview()
        isTimeBarOpen = false
    }

    private fun changePreviewOffset(seconds: Double) {
        val offset = AimPreview.normalized(seconds)
        if (offset == previewOffset) return
        // Looking ahead needs a sky that stands still for the moment: a followed satellite is dropped.
        if (offset != 0.0 && satellite.source != null) satellite.stop()
        previewOffset = offset
        // The target jumps with the clock: reaching or losing it because of that is not worth a buzz.
        wasOnTarget = null
    }

    // Zoom

    /**
     * Zooms the virtual sky by a pinch's [magnification], counted from the field of view when the pinch began (a zoom
     * factor above 1 narrows the view).
     */
    fun updatePinch(magnification: Double) {
        if (!magnification.isFinite() || magnification <= 0) return
        val start = pinchStartFieldOfView ?: virtualFieldOfView
        pinchStartFieldOfView = start
        zoomTo(start / magnification)
    }

    fun endPinch() {
        pinchStartFieldOfView = null
        val degrees = virtualFieldOfView
        preferences?.edit { putFloat(FIELD_OF_VIEW_KEY, degrees.toFloat()) }
    }

    /** Returns the virtual sky to its default field of view. */
    fun resetFieldOfView() {
        zoomTo(AimProjectionFactory.defaultVirtualFieldOfView)
        preferences?.edit { remove(FIELD_OF_VIEW_KEY) }
    }

    private fun zoomTo(degrees: Double) {
        val range = AimProjectionFactory.virtualFieldOfViewRange
        virtualFieldOfView = AstroMath.clamp(degrees, range.start, range.endInclusive)
    }

    private fun storedFieldOfView(): Double {
        val stored = preferences?.getFloat(FIELD_OF_VIEW_KEY, 0f)?.toDouble() ?: 0.0
        return if (stored in AimProjectionFactory.virtualFieldOfViewRange) {
            stored
        } else {
            AimProjectionFactory.defaultVirtualFieldOfView
        }
    }

    // Controls

    /**
     * Turns the camera picture on or off. At night the separate "camera at night" preference decides, so
     * switching it off does not change the daytime choice.
     */
    fun toggleCameraPreview() {
        val wanted = configuration.wantsCameraPreview
        app.settingsStore.update { settings ->
            when {
                !app.isNightModeActive -> settings.copy(cameraPreviewEnabled = !wanted)
                wanted -> settings.copy(cameraPreviewAtNight = false)
                else -> settings.copy(cameraPreviewEnabled = true, cameraPreviewAtNight = true)
            }
        }
    }

    /** Cycles night mode: off, on, after sunset. */
    fun cycleNightMode() {
        val next = when (app.settings.nightMode) {
            NightModeSetting.OFF -> NightModeSetting.ON
            NightModeSetting.ON -> NightModeSetting.AUTOMATIC
            NightModeSetting.AUTOMATIC -> NightModeSetting.OFF
        }
        app.settingsStore.update { it.copy(nightMode = next) }
    }
}
