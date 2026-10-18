package net.kuecker.lodestar.features.objects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.kuecker.lodestar.kit.catalog.CelestialObject
import net.kuecker.lodestar.shared.CardTitle
import net.kuecker.lodestar.shared.InfoRow
import net.kuecker.lodestar.theme.LocalPalette
import net.kuecker.lodestar.theme.card

/** Whether the object has anything to show in the About card. */
internal fun hasAboutContent(obj: CelestialObject): Boolean =
    obj.notes != null || obj.alternateNames.isNotEmpty() || obj.spectralType != null

/** Catalog notes and the facts that do not fit anywhere else. */
@Composable
internal fun ObjectDetailAboutCard(obj: CelestialObject, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    Column(modifier.fillMaxWidth().card(palette), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CardTitle("About", icon = Icons.Outlined.Info)
        obj.notes?.let {
            Text(it, color = palette.primaryText, style = MaterialTheme.typography.bodyMedium)
        }
        if (obj.alternateNames.isNotEmpty()) {
            InfoRow("Also known as", obj.alternateNames.joinToString(", "))
        }
        obj.spectralType?.let { InfoRow("Spectral type", it) }
    }
}
