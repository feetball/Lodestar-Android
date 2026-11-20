package net.kuecker.lodestar.features.system

import android.content.Context
import android.content.Intent
import net.kuecker.lodestar.app.AppModel
import net.kuecker.lodestar.app.AppTab
import net.kuecker.lodestar.app.MainActivity
import net.kuecker.lodestar.model.NightModeSetting

/** How a request changes night mode. */
enum class NightModeChange {
    TOGGLE, ON, OFF;

    /**
     * Whether night mode is to be on afterwards. [isOn] says whether the setting is "On" now; "Automatic" counts as not on,
     * so a toggle then switches night mode on for good and the next toggle switches it off. The iOS controls follow the
     * same rule.
     */
    fun resultingIsOn(isOn: Boolean): Boolean = when (this) {
        TOGGLE -> !isOn
        ON -> true
        OFF -> false
    }
}

/**
 * What a tap does to the red torch: red to a question about white light, the question to red again (a tap elsewhere is no
 * yes), white back to red at once. Only the confirmation turns it white.
 */
enum class TorchLight {
    RED, CONFIRMING_WHITE, WHITE;

    val afterTap: TorchLight get() = when (this) {
        RED -> CONFIRMING_WHITE
        CONFIRMING_WHITE, WHITE -> RED
    }

    val afterConfirm: TorchLight get() = if (this == CONFIRMING_WHITE) WHITE else this

    val afterCancel: TorchLight get() = if (this == CONFIRMING_WHITE) RED else this
}

/** What a launcher shortcut or a quick settings tile asks the app to do. */
internal sealed interface SystemCommand {
    data object ShowAim : SystemCommand
    data object ShowTorch : SystemCommand
    data class NightMode(val change: NightModeChange) : SystemCommand
    data class AimAt(val objectID: String) : SystemCommand
}

/** The intents of the launcher shortcuts and tiles, and what the app does with them. */
internal object SystemLaunch {
    /** The extra of the launch intent that names the action. */
    const val EXTRA_ACTION = "net.kuecker.lodestar.system.ACTION"

    /** The extra of the launch intent that names the catalog identifier to aim at. */
    const val EXTRA_OBJECT = "net.kuecker.lodestar.system.OBJECT"

    const val ACTION_AIM = "aim"
    const val ACTION_TORCH = "torch"
    const val ACTION_NIGHT_TOGGLE = "night.toggle"
    const val ACTION_NIGHT_ON = "night.on"
    const val ACTION_NIGHT_OFF = "night.off"
    const val ACTION_AIM_AT = "aim.object"

    /** The most characters of an object identifier a launch intent may carry (a custom target has a UUID). */
    const val MAXIMUM_OBJECT_ID_LENGTH = 80

    /** The intent that starts the app with [action] (and the object [objectID] for [ACTION_AIM_AT]). */
    fun intent(context: Context, action: String, objectID: String? = null): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .putExtra(EXTRA_ACTION, action)
            .apply { objectID?.let { putExtra(EXTRA_OBJECT, it) } }
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    /** The command a launch intent asks for, or `null` for no or an unknown action, or for aiming without an object. */
    fun command(action: String?, objectID: String?): SystemCommand? = when (action) {
        ACTION_AIM -> SystemCommand.ShowAim
        ACTION_TORCH -> SystemCommand.ShowTorch
        ACTION_NIGHT_TOGGLE -> SystemCommand.NightMode(NightModeChange.TOGGLE)
        ACTION_NIGHT_ON -> SystemCommand.NightMode(NightModeChange.ON)
        ACTION_NIGHT_OFF -> SystemCommand.NightMode(NightModeChange.OFF)
        ACTION_AIM_AT -> objectID?.takeIf { it.isNotBlank() && it.length <= MAXIMUM_OBJECT_ID_LENGTH }
            ?.let(SystemCommand::AimAt)
        else -> null
    }

    /** Carries out what [intent] asked for. The request is used up, so a recreated activity does not repeat it. */
    fun handle(intent: Intent?, app: AppModel) {
        val action = intent?.getStringExtra(EXTRA_ACTION) ?: return
        val objectID = intent.getStringExtra(EXTRA_OBJECT)
        intent.removeExtra(EXTRA_ACTION)
        intent.removeExtra(EXTRA_OBJECT)
        command(action, objectID)?.let { perform(it, app) }
    }

    fun perform(command: SystemCommand, app: AppModel) {
        when (command) {
            SystemCommand.ShowAim -> app.selectedTab = AppTab.AIM
            SystemCommand.ShowTorch -> app.isTorchShown = true
            is SystemCommand.NightMode -> setNightMode(command.change, app)
            is SystemCommand.AimAt -> app.aim(command.objectID)
        }
    }

    /** Applies [change] to the night mode setting. */
    fun setNightMode(change: NightModeChange, app: AppModel) {
        val isOn = change.resultingIsOn(app.settings.nightMode == NightModeSetting.ON)
        app.settingsStore.update { it.copy(nightMode = if (isOn) NightModeSetting.ON else NightModeSetting.OFF) }
    }
}
