package net.kuecker.lodestar.wear

import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import net.kuecker.lodestar.kit.wrist.AimWristMessage

/**
 * Lodestar on the watch: one page that mirrors the phone's Aim screen while the phone aims (see [AimScreen]).
 *
 * The phone's messages are listened to only while the page is in front: the phone looks for watches with
 * [AimWristMessage.CAPABILITY] and sends to those it finds, and the screen stays on meanwhile, as both hands are on
 * the tripod head.
 */
class MainActivity : ComponentActivity() {
    private val tracker = WearAimTracker()
    private var received by mutableStateOf<WearAimTracker.Received?>(null)
    private val vibrator: Vibrator? by lazy { getSystemService(VibratorManager::class.java)?.defaultVibrator }

    private val listener = MessageClient.OnMessageReceivedListener { event ->
        if (event.path != AimWristMessage.PATH) return@OnMessageReceivedListener
        val message = AimWristMessage.decode(event.data) ?: return@OnMessageReceivedListener
        runOnUiThread { onMessage(message) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AimScreen(received) }
    }

    override fun onResume() {
        super.onResume()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Wearable.getMessageClient(this).addListener(listener)
    }

    override fun onPause() {
        Wearable.getMessageClient(this).removeListener(listener)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onPause()
    }

    private fun onMessage(message: AimWristMessage) {
        val reachedTarget = tracker.receive(message, SystemClock.elapsedRealtime())
        received = tracker.latest
        if (reachedTarget) vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))
    }
}
