package net.kuecker.lodestar.features.system

import android.app.PendingIntent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import net.kuecker.lodestar.R
import net.kuecker.lodestar.app.AppModel
import net.kuecker.lodestar.app.LodestarApplication
import net.kuecker.lodestar.model.NightModeSetting

/**
 * The quick settings tile that switches Lodestar's red night mode. It lights up while the setting is "On"; a tap
 * switches it on, or off again (see [NightModeChange.resultingIsOn]). The tile runs in the app's own process, so it reads and
 * changes the app's settings directly.
 */
class NightModeTile : TileService() {
    private val app: AppModel get() = (application as LodestarApplication).appModel

    override fun onStartListening() {
        refresh()
    }

    override fun onClick() {
        SystemLaunch.setNightMode(NightModeChange.TOGGLE, app)
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val isOn = app.settings.nightMode == NightModeSetting.ON
        tile.state = if (isOn) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_night_mode)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_system_night)
        tile.updateTile()
    }
}

/** The quick settings tile that opens the red torch. */
class RedTorchTile : TileService() {
    override fun onStartListening() {
        val tile = qsTile ?: return
        tile.state = Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_red_torch)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_system_torch)
        tile.updateTile()
    }

    override fun onClick() {
        val intent = SystemLaunch.intent(this, SystemLaunch.ACTION_TORCH)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
