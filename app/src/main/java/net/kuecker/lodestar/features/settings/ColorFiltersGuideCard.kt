package net.kuecker.lodestar.features.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.kuecker.lodestar.shared.CardTitle
import net.kuecker.lodestar.shared.monospacedDigits
import net.kuecker.lodestar.theme.LocalPalette

private val steps = listOf(
    "Open the phone's Settings and search for Extra dim (usually under Accessibility › Color and motion, or " +
        "Display › Brightness). Turn it on to go below the lowest normal brightness.",
    "Add the Extra dim tile to Quick Settings, or set it as the Accessibility button, so you can switch it on and " +
        "off in the dark.",
    "In Settings › Display, turn on Night Light (some phones call it Eye comfort shield or Blue light filter), " +
        "schedule it from sunset to sunrise and choose the warmest, strongest tint. That removes most of the blue " +
        "light and some of the green.",
    "Turn the system brightness down as far as it goes. Lodestar dims its own screen in night mode as well.",
)

/**
 * How to soften the whole phone for dark adaptation with Android's Extra dim and Night Light (or the Eye comfort
 * shield): the only way to tame system-drawn content (the keyboard, dialogs, other apps) that Lodestar cannot colour
 * red. Android has no pure-red screen filter. [showsTitle] repeats the title (leave it off when a section header
 * already says it).
 */
@Composable
internal fun ColorFiltersGuideCard(showsTitle: Boolean = true) {
    val palette = LocalPalette.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (showsTitle) CardTitle("Night vision for the whole phone", icon = Icons.Outlined.PhoneAndroid)
        Text(
            "Lodestar draws everything it controls in red. The keyboard, system dialogs and other apps stay bright, " +
                "so soften the whole screen too:",
            color = palette.secondaryText,
            style = MaterialTheme.typography.bodyMedium,
        )
        steps.forEachIndexed { index, step ->
            Row(
                Modifier.semantics(mergeDescendants = true) { contentDescription = "Step ${index + 1}. $step" },
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    Modifier.size(22.dp).background(palette.accent, CircleShape).clearAndSetSemantics {},
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "${index + 1}",
                        color = palette.onAccent,
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold).monospacedDigits(),
                    )
                }
                Text(step, color = palette.primaryText, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
        }
        Text(
            "These settings change everything on the phone, including photos and videos you view. They do not make " +
                "the screen pure red, so keep Lodestar's night mode on.",
            color = palette.secondaryText,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
