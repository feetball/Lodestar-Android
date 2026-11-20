package net.kuecker.lodestar.features.skyquality

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import net.kuecker.lodestar.kit.foundation.GeographicLocation
import net.kuecker.lodestar.kit.skyquality.BortleEstimate
import net.kuecker.lodestar.theme.LocalPalette

/**
 * One line about the sky of a place, "Sky: Bortle ~4 (rural/suburban transition)", for site rows and the site editor. A tap
 * shows or hides the caveat that the class is an estimate from satellite imagery. Nothing for no place, while the estimate
 * is being worked out and when the map is unavailable.
 */
@Composable
internal fun SkyQualityLine(location: GeographicLocation?, modifier: Modifier = Modifier) {
    val reading by rememberSkyReading(location)
    val estimate = reading?.estimate ?: return
    val palette = LocalPalette.current
    var isExplained by remember { mutableStateOf(false) }
    Column(
        modifier.clickable(
            onClickLabel = if (isExplained) "Hide how the sky quality is estimated" else "Show how the sky quality is estimated",
            role = Role.Button,
        ) { isExplained = !isExplained },
    ) {
        Text("Sky: ${estimate.summary}", color = palette.secondaryText, style = MaterialTheme.typography.bodySmall)
        if (isExplained) {
            Text(BortleEstimate.caveat, color = palette.tertiaryText, style = MaterialTheme.typography.bodySmall)
        }
    }
}
