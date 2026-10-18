package net.kuecker.lodestar.features.smallbodies

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.WbTwilight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import net.kuecker.lodestar.features.aim.CardNote
import net.kuecker.lodestar.kit.catalog.CelestialObject
import net.kuecker.lodestar.shared.LocalAppModel
import net.kuecker.lodestar.theme.LocalPalette

/** What the app knows about a comet or asteroid at the moment: [info], or `null` when the orbit cannot be evaluated. */
internal class SmallBodyLive(val isComputed: Boolean, val info: SmallBodyInfo?) {
    /** The one line under the name: kind, magnitude estimate and elongation, without the warnings (the notes say them). */
    fun subtitle(obj: CelestialObject): String = SmallBodyText.subtitle(obj, info, isComputed, withWarnings = false)

    companion object {
        /** Before the first computation: nothing is claimed yet. */
        val pending = SmallBodyLive(isComputed = false, info = null)

        /** How often the numbers are worked out again (they change slowly). */
        const val REFRESH_MILLIS = 10_000L
    }
}

/**
 * The live info of [obj], or `null` for an object that is neither a comet nor an asteroid. Worked out on the first
 * composition and then every ten seconds.
 */
@Composable
internal fun rememberSmallBodyLive(obj: CelestialObject): SmallBodyLive? {
    val app = LocalAppModel.current
    val body = obj.smallBody ?: return null
    val live: State<SmallBodyLive> = produceState(SmallBodyLive.pending, body) {
        while (isActive) {
            val info = SmallBodyInfo.compute(body, app.clock.now)
            value = SmallBodyLive(isComputed = true, info = info)
            delay(SmallBodyLive.REFRESH_MILLIS)
        }
    }
    return live.value
}

/** The warnings of a comet or asteroid as notes inside the Aim target card. */
@Composable
internal fun SmallBodyAimNotes(live: SmallBodyLive?) {
    val palette = LocalPalette.current
    val info = live?.info ?: return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (warning in info.warnings) {
            when (warning) {
                SmallBodyWarning.TWILIGHT_GLOW ->
                    CardNote(SmallBodyText.sentence(warning), Icons.Outlined.WbTwilight, palette.warning)
                SmallBodyWarning.POSITION_APPROXIMATE ->
                    CardNote(SmallBodyText.sentence(warning), Icons.Outlined.Info, palette.warning)
                // The subtitle already says it.
                SmallBodyWarning.NO_BRIGHTNESS_DATA -> Unit
            }
        }
    }
}
