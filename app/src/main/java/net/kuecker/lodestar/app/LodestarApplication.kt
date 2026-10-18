package net.kuecker.lodestar.app

import android.app.Application
import kotlinx.coroutines.MainScope

/** Owns the process-wide [AppModel]; it outlives activities, so rotation and recreation keep all state. */
class LodestarApplication : Application() {
    val appModel: AppModel by lazy { AppModel(this, MainScope()) }
}
