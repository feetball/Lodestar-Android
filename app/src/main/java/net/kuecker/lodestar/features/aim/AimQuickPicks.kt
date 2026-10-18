package net.kuecker.lodestar.features.aim

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.kuecker.lodestar.kit.catalog.CelestialObject
import net.kuecker.lodestar.services.CatalogService
import net.kuecker.lodestar.shared.SkySnapshot
import net.kuecker.lodestar.shared.icon
import net.kuecker.lodestar.theme.LocalPalette

/**
 * The objects offered first when choosing a target: the Moon, the planets above the horizon, the Milky Way core and
 * the pole star of the observer's hemisphere (Polaris, or the south celestial pole).
 */
internal object AimQuickPicks {
    class Pick(val obj: CelestialObject, val title: String) {
        val id: String get() = obj.id
    }

    private val planetIDs = listOf("planet.venus", "planet.jupiter", "planet.saturn", "planet.mars", "planet.mercury")
    private const val GALACTIC_CENTER_ID = "special.galacticCenter"
    private const val POLARIS_ID = "star.hip11767"
    private const val SOUTH_CELESTIAL_POLE_ID = "special.scp"

    /**
     * @param snapshot The current sky, when known; planets below the horizon are left out and the pole pick follows
     * the observer's hemisphere.
     * @param planetLimit The most planets offered, the brightest first.
     */
    fun picks(catalog: CatalogService, snapshot: SkySnapshot?, planetLimit: Int): List<Pick> {
        val bodies = snapshot?.bodies
        val picks = mutableListOf<Pick>()
        fun add(id: String, title: String? = null) {
            catalog.objectWithID(id)?.let { picks.add(Pick(it, title ?: it.name)) }
        }
        add("moon")
        val visible = if (bodies != null) {
            bodies.filter { it.obj.id in planetIDs && it.horizontal.altitude > 0 }
                .sortedBy { it.magnitude ?: 99.0 }
                .map { it.obj.id }
        } else {
            planetIDs
        }
        visible.take(planetLimit).forEach { add(it) }
        add(GALACTIC_CENTER_ID, title = "Milky Way core")
        val latitude = snapshot?.observer?.location?.latitude
        if (latitude != null && latitude < 0) add(SOUTH_CELESTIAL_POLE_ID) else add(POLARIS_ID)
        return picks
    }
}

/** A quick pick as a capsule button. */
@Composable
internal fun AimQuickPickButton(pick: AimQuickPicks.Pick, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    Row(
        modifier
            .heightIn(min = 44.dp)
            .clip(CircleShape)
            .background(palette.surfaceRaised, CircleShape)
            .border(1.dp, palette.accent.copy(alpha = 0.5f), CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(pick.obj.category.icon, contentDescription = null, tint = palette.accent, modifier = Modifier.size(18.dp))
        Text(
            pick.title,
            color = palette.accent,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}
