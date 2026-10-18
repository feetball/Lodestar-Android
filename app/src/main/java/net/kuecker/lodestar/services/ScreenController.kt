package net.kuecker.lodestar.services

import android.view.Window
import android.view.WindowManager

/**
 * Screen brightness and keep-awake for the activity window. The overrides belong to the window, so Android
 * restores the user's brightness by itself whenever the app is not in front.
 */
class ScreenController {
    private var window: Window? = null
    private var brightness: Double? = null
    private var keepOn = false

    fun attach(window: Window) {
        this.window = window
        apply()
    }

    fun detach(window: Window) {
        if (this.window === window) this.window = null
    }

    /** `null` restores the user's brightness; otherwise 0.01…1. */
    fun setBrightnessOverride(value: Double?) {
        if (brightness == value) return
        brightness = value
        apply()
    }

    fun setKeepScreenOn(on: Boolean) {
        if (keepOn == on) return
        keepOn = on
        apply()
    }

    /** Restores brightness and lets the screen sleep (background/teardown). */
    fun restore() {
        brightness = null
        keepOn = false
        apply()
    }

    private fun apply() {
        val window = window ?: return
        val attributes = window.attributes
        val target = brightness?.toFloat()?.coerceIn(0.01f, 1f) ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        if (attributes.screenBrightness != target) {
            attributes.screenBrightness = target
            window.attributes = attributes
        }
        if (keepOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}
