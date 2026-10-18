package net.kuecker.lodestar.services

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.hardware.camera2.CameraCharacteristics
import android.util.Range
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import net.kuecker.lodestar.kit.foundation.AstroMath
import kotlin.math.atan

/** Camera permission and hardware as the app sees it. */
enum class CameraAuthorization { NOT_DETERMINED, AUTHORIZED, DENIED, UNAVAILABLE }

/**
 * The field of view of the preview frames as displayed upright on the current screen (pinhole model, square
 * pixels). The preview view aspect-fills its bounds with these frames, so the overlay must apply the same crop.
 */
data class CameraGeometry(
    /** Degrees across the upright frame. */
    val horizontalFOV: Double,
    /** Degrees along the upright frame. */
    val verticalFOV: Double,
    val imageWidth: Int,
    val imageHeight: Int,
)

/**
 * The live camera picture behind the sky overlay (back camera, 4:3 to keep the full sensor field, zoom 1,
 * stabilisation off so the field of view matches the optics).
 *
 * [start] is meant to be called often with the current wish; it only acts when something changed. The picture is
 * shown by [CameraPreview]; in night mode it is filtered to red monochrome there ([nightFilter]).
 */
class CameraService(private val context: Context) {
    var authorization by mutableStateOf(CameraAuthorization.NOT_DETERMINED)
        private set
    var geometry by mutableStateOf<CameraGeometry?>(null)
        private set
    var isRunning by mutableStateOf(false)
        private set
    var errorDescription by mutableStateOf<String?>(null)
        private set

    /** Red-monochrome intensity (0…1) applied to the preview, or `null` for natural colour. */
    var nightFilter by mutableStateOf<Double?>(null)
        private set

    /** The activity's lifecycle, which the camera binds to; installed by the activity. */
    var lifecycleOwner: LifecycleOwner? = null

    /** Shows the system permission dialog and reports whether access was granted; installed by the activity. */
    var permissionPrompt: (suspend () -> Boolean)? = null

    private var wantsRunning = false
    private var provider: ProcessCameraProvider? = null
    private var isLoadingProvider = false
    private var preview: Preview? = null
    private var camera: Camera? = null
    private var previewView: PreviewView? = null
    private val streamObserver = Observer<PreviewView.StreamState> { state ->
        if (state == PreviewView.StreamState.STREAMING) refreshGeometry()
    }

    init {
        refreshAuthorization()
    }

    /** Asks for camera access if it was never decided. Returns whether access is granted. */
    suspend fun requestAccess(): Boolean {
        refreshAuthorization()
        if (authorization == CameraAuthorization.AUTHORIZED) return true
        if (authorization == CameraAuthorization.UNAVAILABLE) return false
        val granted = permissionPrompt?.invoke() ?: false
        authorization = if (granted) CameraAuthorization.AUTHORIZED else CameraAuthorization.DENIED
        if (granted && wantsRunning) bindIfPossible()
        return granted
    }

    /**
     * Runs the preview with [nightFilter] (`null` = natural colour, 0…1 = red-mono intensity). Idempotent; updates
     * the filter live. Does not ask for permission (the Aim screen does that when the user turns the preview on).
     */
    fun start(nightFilter: Double?) {
        if (this.nightFilter != nightFilter) this.nightFilter = nightFilter
        wantsRunning = true
        refreshAuthorization()
        if (authorization == CameraAuthorization.AUTHORIZED) bindIfPossible()
    }

    fun stop() {
        wantsRunning = false
        unbind()
    }

    /** Called by [CameraPreview] when its view appears. */
    fun attach(view: PreviewView) {
        previewView = view
        preview?.setSurfaceProvider(view.surfaceProvider)
        lifecycleOwner?.let { view.previewStreamState.observe(it, streamObserver) }
    }

    /** Called by [CameraPreview] when its view goes away. */
    fun detach(view: PreviewView) {
        if (previewView !== view) return
        view.previewStreamState.removeObserver(streamObserver)
        preview?.setSurfaceProvider(null)
        previewView = null
    }

    // Private

    private fun refreshAuthorization() {
        val status = when {
            !context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) -> CameraAuthorization.UNAVAILABLE
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED ->
                CameraAuthorization.AUTHORIZED
            authorization == CameraAuthorization.DENIED -> CameraAuthorization.DENIED
            else -> CameraAuthorization.NOT_DETERMINED
        }
        if (authorization != status) authorization = status
    }

    private fun bindIfPossible() {
        if (camera != null) return
        val owner = lifecycleOwner ?: return
        val cameraProvider = provider
        if (cameraProvider == null) {
            if (isLoadingProvider) return
            isLoadingProvider = true
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                isLoadingProvider = false
                provider = runCatching { future.get() }.getOrElse {
                    fail("The camera could not be opened.")
                    return@addListener
                }
                if (wantsRunning) bindIfPossible()
            }, ContextCompat.getMainExecutor(context))
            return
        }
        try {
            if (!cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                authorization = CameraAuthorization.UNAVAILABLE
                fail("This device has no back camera.")
                return
            }
            val selector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .build()
            val useCase = Preview.Builder()
                .setResolutionSelector(selector)
                .setPreviewStabilizationEnabled(false)
                .apply { lowLightFrameRate(cameraProvider)?.let(::setTargetFrameRate) }
                .build()
            useCase.setSurfaceProvider(previewView?.surfaceProvider)
            camera = cameraProvider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, useCase)
            preview = useCase
            isRunning = true
            errorDescription = null
            previewView?.let { view -> view.previewStreamState.observe(owner, streamObserver) }
        } catch (error: IllegalStateException) {
            fail("The camera is in use by another app.")
        } catch (error: IllegalArgumentException) {
            fail("The camera could not be configured.")
        }
    }

    /**
     * A frame-rate range with the lowest available minimum (≥ 5 fps) and a maximum of at least 24 fps: auto
     * exposure may then integrate longer in the dark (the frame rate drops) and stays smooth in daylight.
     */
    private fun lowLightFrameRate(cameraProvider: ProcessCameraProvider): Range<Int>? {
        val info = runCatching { cameraProvider.getCameraInfo(CameraSelector.DEFAULT_BACK_CAMERA) }.getOrNull()
            ?: return null
        return info.supportedFrameRateRanges
            .filter { it.lower >= 5 && it.upper >= 24 }
            .minWithOrNull(compareBy<Range<Int>> { it.lower }.thenByDescending { it.upper })
    }

    private fun unbind() {
        val useCase = preview
        if (useCase != null) provider?.unbind(useCase)
        preview = null
        camera = null
        if (isRunning) isRunning = false
        if (geometry != null) geometry = null
    }

    private fun fail(message: String) {
        errorDescription = message
        unbind()
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun refreshGeometry() {
        val camera = camera ?: return
        val resolution = preview?.resolutionInfo ?: return
        val info = Camera2CameraInfo.from(camera.cameraInfo)
        val focal = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?.firstOrNull()?.toDouble() ?: return
        val physical = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return
        val pixels = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE) ?: return
        val active = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
        val crop = resolution.cropRect
        val next = geometry(
            focalLength = focal,
            activeWidthMillimeters = physical.width.toDouble() * active.width() / pixels.width,
            activeHeightMillimeters = physical.height.toDouble() * active.height() / pixels.height,
            frameWidth = crop.width(), frameHeight = crop.height(),
            rotationDegrees = resolution.rotationDegrees,
        )
        if (next != null && next != geometry) geometry = next
    }

    companion object {
        /**
         * The upright field of view of a [frameWidth] × [frameHeight] stream (sensor orientation) cut from the
         * centre of the active pixel array at full width or full height, whichever fits the stream's aspect ratio,
         * then rotated by [rotationDegrees] for display. `null` for unusable input.
         */
        fun geometry(
            focalLength: Double,
            activeWidthMillimeters: Double,
            activeHeightMillimeters: Double,
            frameWidth: Int,
            frameHeight: Int,
            rotationDegrees: Int,
        ): CameraGeometry? {
            if (focalLength <= 0 || activeWidthMillimeters <= 0 || activeHeightMillimeters <= 0 ||
                frameWidth <= 0 || frameHeight <= 0
            ) {
                return null
            }
            val frameAspect = frameWidth.toDouble() / frameHeight
            val sensorAspect = activeWidthMillimeters / activeHeightMillimeters
            val halfWidth: Double
            val halfHeight: Double
            if (frameAspect >= sensorAspect) {
                halfWidth = activeWidthMillimeters / 2
                halfHeight = activeWidthMillimeters / frameAspect / 2
            } else {
                halfWidth = activeHeightMillimeters * frameAspect / 2
                halfHeight = activeHeightMillimeters / 2
            }
            val sensorHorizontal = AstroMath.degrees(2 * atan(halfWidth / focalLength))
            val sensorVertical = AstroMath.degrees(2 * atan(halfHeight / focalLength))
            val rotated = Math.floorMod(rotationDegrees, 180) == 90
            return if (rotated) {
                CameraGeometry(sensorVertical, sensorHorizontal, frameHeight, frameWidth)
            } else {
                CameraGeometry(sensorHorizontal, sensorVertical, frameWidth, frameHeight)
            }
        }

        /** Red monochrome: R' = intensity × Rec. 709 luma, G' = B' = 0 (as in the iOS app). */
        fun nightColorMatrix(intensity: Double): ColorMatrix {
            val k = intensity.coerceIn(0.0, 1.0).toFloat()
            return ColorMatrix(
                floatArrayOf(
                    0.2126f * k, 0.7152f * k, 0.0722f * k, 0f, 0f,
                    0f, 0f, 0f, 0f, 0f,
                    0f, 0f, 0f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f,
                ),
            )
        }
    }
}

/**
 * The live preview of [camera], aspect-filling its bounds, red-filtered while [CameraService.nightFilter] is set.
 * Shown only while the camera runs; draws nothing otherwise.
 */
@Composable
fun CameraPreview(camera: CameraService, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val view = remember {
        PreviewView(context).apply {
            // TextureView-backed, so the night colour filter applies to the picture.
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    DisposableEffect(view) {
        camera.attach(view)
        onDispose { camera.detach(view) }
    }
    val filter = camera.nightFilter
    AndroidView(factory = { view }, modifier = modifier, update = { applyNightFilter(it, filter) })
}

private fun applyNightFilter(view: PreviewView, intensity: Double?) {
    val paint = intensity?.let { Paint().apply { colorFilter = ColorMatrixColorFilter(CameraService.nightColorMatrix(it)) } }
    fun apply(target: View) {
        if (target is TextureView) target.setLayerPaint(paint)
    }
    for (index in 0 until view.childCount) apply(view.getChildAt(index))
    // The texture view is created when the stream starts; filter it as soon as it appears.
    view.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
        override fun onChildViewAdded(parent: View, child: View) = apply(child)
        override fun onChildViewRemoved(parent: View, child: View) = Unit
    })
}
