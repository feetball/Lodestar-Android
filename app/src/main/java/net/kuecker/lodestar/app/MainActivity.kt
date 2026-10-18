package net.kuecker.lodestar.app

import android.Manifest
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.CompletableDeferred
import net.kuecker.lodestar.features.widget.WidgetLaunch

/** The single activity: hosts [RootScreen] and lends the services what only an activity has. */
class MainActivity : ComponentActivity() {
    private val app: AppModel get() = (application as LodestarApplication).appModel

    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { app.location.onPermissionResult() }

    private var cameraResult: CompletableDeferred<Boolean>? = null
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cameraResult?.complete(granted)
        cameraResult = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        ScreenshotMode.install(intent.getStringExtra(ScreenshotMode.EXTRA))
        val model = app
        model.location.permissionPrompt = ::requestLocation
        model.camera.permissionPrompt = ::requestCamera
        model.camera.lifecycleOwner = this
        model.screen.attach(window)
        model.feedback.view = window.decorView
        model.launch()
        WidgetLaunch.handle(intent, model)
        addOnNewIntentListener { WidgetLaunch.handle(it, model) }
        setContent { RootScreen(model) }
    }

    override fun onStart() {
        super.onStart()
        app.handleLifecycle(started = true)
    }

    override fun onStop() {
        app.handleLifecycle(started = false)
        super.onStop()
    }

    override fun onDestroy() {
        val model = app
        model.screen.detach(window)
        if (model.camera.lifecycleOwner === this) model.camera.lifecycleOwner = null
        if (model.feedback.view === window.decorView) model.feedback.view = null
        super.onDestroy()
    }

    /** Shows the location dialog, or the app's settings once Android no longer shows it after refusals. */
    private fun requestLocation() {
        val blocked = app.location.isDenied &&
            !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) &&
            !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (blocked) {
            openAppSettings()
        } else {
            locationPermission.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            )
        }
    }

    private suspend fun requestCamera(): Boolean {
        val blocked = app.camera.authorization == net.kuecker.lodestar.services.CameraAuthorization.DENIED &&
            !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
        if (blocked) {
            openAppSettings()
            return false
        }
        val result = CompletableDeferred<Boolean>()
        cameraResult?.complete(false)
        cameraResult = result
        cameraPermission.launch(Manifest.permission.CAMERA)
        return result.await()
    }

    private fun openAppSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
