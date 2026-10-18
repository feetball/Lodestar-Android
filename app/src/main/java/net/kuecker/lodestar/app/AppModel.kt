package net.kuecker.lodestar.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.kuecker.lodestar.kit.catalog.CelestialObject
import net.kuecker.lodestar.kit.ephemeris.SolarSystemBody
import net.kuecker.lodestar.kit.ephemeris.horizontal
import net.kuecker.lodestar.kit.foundation.Observer
import net.kuecker.lodestar.kit.foundation.SkyFrame
import net.kuecker.lodestar.kit.geomagnetism.WorldMagneticModel
import net.kuecker.lodestar.kit.phototools.LensSetup
import net.kuecker.lodestar.kit.phototools.SensorFormat
import net.kuecker.lodestar.kit.pointing.AlignmentModel
import net.kuecker.lodestar.kit.pointing.DeviceAttitude
import net.kuecker.lodestar.kit.pointing.MountProfile
import net.kuecker.lodestar.model.AppSettings
import net.kuecker.lodestar.model.HeadingSource
import net.kuecker.lodestar.model.LocationSource
import net.kuecker.lodestar.model.NightModeSetting
import net.kuecker.lodestar.model.ObserverContext
import net.kuecker.lodestar.model.SettingsStore
import net.kuecker.lodestar.model.SkyClock
import net.kuecker.lodestar.services.CameraService
import net.kuecker.lodestar.services.CatalogService
import net.kuecker.lodestar.services.FeedbackService
import net.kuecker.lodestar.services.LocationService
import net.kuecker.lodestar.services.MagneticReference
import net.kuecker.lodestar.services.MotionService
import net.kuecker.lodestar.services.ScreenController
import net.kuecker.lodestar.shared.RetainedState
import net.kuecker.lodestar.theme.Palette
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Root application state: owns the services, the current target and alignment, and derives the observing context
 * and display mode. Lives as long as the process (created by [LodestarApplication]); screens read it through
 * [LocalAppModel]. All members are main-thread only.
 */
class AppModel(context: Context, private val scope: CoroutineScope) {
    /** Backed up with the user's Android backup: preferences, saved sites, favourites. */
    private val preferences = context.getSharedPreferences("lodestar", Context.MODE_PRIVATE)

    /** Device-local state that must not move to another phone (permission bookkeeping). */
    private val localPreferences = context.getSharedPreferences("lodestar.local", Context.MODE_PRIVATE)

    val settingsStore = SettingsStore(preferences, scope)
    val clock = SkyClock(scope)
    val location = LocationService(context, localPreferences)
    val motion = MotionService(context, scope)
    val camera = CameraService(context)
    val catalog = CatalogService(scope)
    val feedback = FeedbackService(context)
    val screen = ScreenController()

    /** UI state that must survive tab switches and configuration changes (navigation stacks, scroll positions). */
    val retained = RetainedState()

    private var selectedTabState by mutableStateOf(AppTab.AIM)

    /** The selected tab. Changing it starts/stops the sensors that tab needs. */
    var selectedTab: AppTab
        get() = selectedTabState
        set(value) {
            if (value == selectedTabState) return
            selectedTabState = value
            syncServices()
        }

    /** The object the Aim screen guides to. */
    var target by mutableStateOf<CelestialObject?>(null)
        private set

    /** Star-alignment corrections for the current mount and heading source. */
    var alignment by mutableStateOf(AlignmentModel.identity)

    private var keepsMotionRunningState by mutableStateOf(false)

    /** Keeps the motion sensors running outside the Aim and Sky tabs (e.g. Sensor Diagnostics). */
    var keepsMotionRunning: Boolean
        get() = keepsMotionRunningState
        set(value) {
            if (value == keepsMotionRunningState) return
            keepsMotionRunningState = value
            syncServices()
        }

    /** Whether the Sun is below −6° at the observer (drives automatic night mode). */
    var isSunBelowCivilTwilight by mutableStateOf(false)
        private set

    /** Whether the activity is started (visible). */
    var isForeground by mutableStateOf(false)
        private set

    private var maintenanceJob: Job? = null
    private var hasLaunched = false
    private var hasRequestedLocationThisLaunch = false
    private var lastAlignmentContext: AlignmentContext? = null
    private var lastSunEvaluation: Pair<Instant, Observer>? = null

    // Derived state

    val settings: AppSettings get() = settingsStore.settings

    /** Night mode as currently in effect (manual setting or automatic after sunset). */
    val isNightModeActive: Boolean
        get() = when (settings.nightMode) {
            NightModeSetting.ON -> true
            NightModeSetting.OFF -> false
            NightModeSetting.AUTOMATIC -> isSunBelowCivilTwilight
        }

    val palette: Palette get() = if (isNightModeActive) Palette.night(settings.nightIntensity) else Palette.normal

    /** The resolved observer, or `null` when no location is known yet. */
    val observerContext: ObserverContext?
        get() {
            val atmosphere = settings.atmosphere
            return when (val source = settings.locationSource) {
                LocationSource.Device -> {
                    val current = location.stableLocation ?: return null
                    val fix = location.fix
                    ObserverContext(
                        observer = Observer(current, atmosphere), timeZone = ZoneId.systemDefault(),
                        name = "Current Location", source = source,
                        horizontalAccuracy = fix?.horizontalAccuracy, timestamp = fix?.timestamp,
                    )
                }
                is LocationSource.Site -> {
                    val site = settings.savedSites.firstOrNull { it.id == source.id } ?: return null
                    ObserverContext(
                        observer = Observer(site.location, atmosphere),
                        timeZone = site.timeZone ?: ZoneId.systemDefault(), name = site.name, source = source,
                    )
                }
            }
        }

    /** The mount geometry selected in Settings. */
    val mountProfile: MountProfile
        get() = MountProfile.presets.firstOrNull { it.id == settings.mountPresetID } ?: MountProfile.backCameraPortrait

    /** The camera body + lens used for the framing rectangle and exposure tools. */
    val lensSetup: LensSetup
        get() {
            val sensor = SensorFormat.presets.firstOrNull { it.id == settings.sensorID }
                ?: SensorFormat.custom(settings.customSensorWidth, settings.customSensorHeight)
            return LensSetup(sensor, settings.focalLength, settings.aperture, settings.pixelPitch)
        }

    // Lifecycle

    /** Starts the clock and periodic maintenance and loads the bundled catalog. Runs once per process. */
    fun launch() {
        if (hasLaunched) return
        hasLaunched = true
        ScreenshotMode.current?.let(::applyScreenshotMode)
        clock.start()
        startMaintenance()
        syncServices()
        scope.launch {
            catalog.load()
            val screenshotTarget = ScreenshotMode.current?.targetID?.let(catalog::objectWithID)
            if (screenshotTarget != null) {
                target = screenshotTarget
            } else if (target == null) {
                settings.recentObjectIDs.firstOrNull()?.let(catalog::objectWithID)?.let { target = it }
            }
            evaluateSun(force = true)
        }
    }

    /** The activity became visible ([started] = true) or went to the background. */
    fun handleLifecycle(started: Boolean) {
        isForeground = started
        if (started) {
            clock.start()
            startMaintenance()
        } else {
            settingsStore.saveNow()
            clock.stop()
            maintenanceJob?.cancel()
            maintenanceJob = null
        }
        syncServices()
    }

    /** Finishes onboarding and requests location access if the device location is used. */
    fun completeOnboarding() {
        settingsStore.update { it.copy(hasCompletedOnboarding = true) }
        if (settings.locationSource == LocationSource.Device) location.requestAuthorization()
        syncServices()
    }

    // Targeting

    /** Makes [obj] the aiming target and switches to the Aim tab. */
    fun aim(obj: CelestialObject) {
        select(obj)
        selectedTab = AppTab.AIM
    }

    /** Makes [obj] the aiming target without changing tabs. */
    fun select(obj: CelestialObject) {
        target = obj
        if (!obj.id.startsWith("custom.")) settingsStore.noteRecent(obj.id)
    }

    fun clearTarget() {
        target = null
    }

    // Alignment

    fun resetAlignment() {
        alignment = AlignmentModel.identity
    }

    // Service orchestration

    /** Starts exactly the services the visible screen needs and applies display rules. Idempotent; call often. */
    fun syncServices() {
        val active = isForeground
        val onboarded = settings.hasCompletedOnboarding

        // Location: only for the device source, only while visible. A one-time grant expires with the session, so
        // ask again (once per launch) when the permission is undetermined.
        if (active && onboarded && settings.locationSource == LocationSource.Device) {
            if (!location.isAuthorized && !location.isDenied && !hasRequestedLocationThisLaunch) {
                hasRequestedLocationThisLaunch = true
                location.requestAuthorization()
            }
            location.start()
        } else {
            location.stop()
        }

        // Motion: the Aim and Sky screens show the camera's pointing direction. Gyro lock keeps running on every
        // tab: stopping the gyro-only frame would lose its heading and with it the star alignment.
        val needsMotion = active && onboarded && (
            selectedTab == AppTab.AIM || selectedTab == AppTab.SKY || keepsMotionRunning ||
                settings.headingSource == HeadingSource.GYRO_LOCK
            )
        if (needsMotion) {
            motion.configure(settings.headingSource, settings.smoothingTimeConstant, magneticReference())
            motion.start()
        } else {
            motion.stop()
        }

        // Camera preview: Aim tab only, if enabled for the current display mode, and only when the phone's back
        // camera looks along the lens (flat-on-top mounts would show the ground).
        val backCameraAlongLens = mountProfile.cameraAxis.dot(DeviceAttitude.backCameraAxis) > 0.99
        val wantsPreview = settings.cameraPreviewEnabled && backCameraAlongLens &&
            (!isNightModeActive || settings.cameraPreviewAtNight)
        if (active && onboarded && selectedTab == AppTab.AIM && wantsPreview && observerContext != null) {
            camera.start(if (isNightModeActive) settings.nightIntensity else null)
        } else {
            camera.stop()
        }

        // Screen: dim at night, keep awake while aiming.
        if (active) {
            screen.setBrightnessOverride(
                if (isNightModeActive && settings.dimScreenAtNight) settings.nightBrightness else null,
            )
            screen.setKeepScreenOn(selectedTab == AppTab.AIM && settings.keepScreenAwake)
        } else {
            screen.restore()
        }

        feedback.configure(settings.hapticsEnabled, settings.audioCuesEnabled)
        invalidateAlignmentIfNeeded()
    }

    // Private

    private data class AlignmentContext(val mountPresetID: String, val headingSource: HeadingSource, val frameEpoch: Int)

    /** Alignment is only valid for the mount and attitude reference it was measured with. */
    private fun invalidateAlignmentIfNeeded() {
        val context = AlignmentContext(settings.mountPresetID, settings.headingSource, motion.frameEpoch)
        val last = lastAlignmentContext
        if (last != null && last != context) alignment = AlignmentModel.identity
        lastAlignmentContext = context
    }

    /**
     * Magnetic declination and expected field strength at the observer from WMM2025: the rotation vector is
     * referenced to magnetic north, and the field strength reveals magnetic interference.
     */
    private fun magneticReference(): MagneticReference? {
        val context = observerContext ?: return null
        val where = context.location
        val field = WorldMagneticModel.wmm2025.field(
            where.latitude, where.longitude, where.elevation, WorldMagneticModel.decimalYear(clock.now),
        )
        return MagneticReference(field.declination, field.totalIntensity / 1000)
    }

    private fun startMaintenance() {
        if (maintenanceJob != null) return
        maintenanceJob = scope.launch {
            while (isActive) {
                evaluateSun(force = false)
                syncServices()
                delay(2_000)
            }
        }
    }

    /** Recomputes the Sun's altitude at most every 30 s (or when the observer changes). */
    private fun evaluateSun(force: Boolean) {
        val observer = observerContext?.observer
        val now = clock.now
        if (observer == null) {
            // No location yet: approximate darkness by the local clock so automatic night mode still protects
            // dark-adapted eyes.
            val hour = ZonedDateTime.ofInstant(now, ZoneId.systemDefault()).hour
            isSunBelowCivilTwilight = hour >= 20 || hour < 6
            return
        }
        val last = lastSunEvaluation
        if (!force && last != null && last.second == observer && now.toEpochMilli() - last.first.toEpochMilli() < 30_000) {
            return
        }
        lastSunEvaluation = now to observer
        val sun = SkyFrame(now, observer).horizontal(SolarSystemBody.SUN, false)
        isSunBelowCivilTwilight = sun.altitude < -6
    }

    /** Debug builds: demo site, fixed time, display mode and camera pointing for store screenshots. */
    private fun applyScreenshotMode(demo: ScreenshotMode) {
        val site = net.kuecker.lodestar.model.SavedSite.create(
            demo.siteName, demo.latitude, demo.longitude, demo.elevation, demo.timeZoneIdentifier,
        )
        settingsStore.update { s ->
            s.copy(
                hasCompletedOnboarding = true,
                cameraPreviewEnabled = false,
                nightMode = if (demo.nightMode) NightModeSetting.ON else NightModeSetting.OFF,
                savedSites = site?.let(::listOf) ?: s.savedSites,
                locationSource = site?.let { LocationSource.Site(it.id) } ?: s.locationSource,
            )
        }
        selectedTabState = demo.tab
        clock.freeze(demo.date)
        demo.pointing?.let(motion::useScreenshotPointing)
    }
}
