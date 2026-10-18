package net.kuecker.lodestar.features.smallbodies

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.kuecker.lodestar.kit.catalog.Catalog
import net.kuecker.lodestar.kit.catalog.ObjectCategory
import net.kuecker.lodestar.kit.comets.SmallBodyKind
import net.kuecker.lodestar.shared.ObjectIcon
import net.kuecker.lodestar.theme.LocalPalette

/**
 * Favourite comets and asteroids that the catalog does not have (a comet drops out of the orbit file after a few
 * years): they stay in the favourites and show "no current orbit" instead of vanishing.
 */
internal object SmallBodyFavorites {
    /** The favourites that are comets or asteroids the [catalog] has no orbit for, in favourites order, without repeats. */
    fun withoutOrbit(favoriteIDs: List<String>, catalog: Catalog): List<String> {
        val seen = HashSet<String>()
        return favoriteIDs.filter { id ->
            SmallBodyText.kindOfID(id) != null && catalog.objectWithID(id) == null && seen.add(id)
        }
    }

    /** The name of a favourite whose orbit is gone: its designation, such as "C/2023 A3" or "12P". */
    fun name(id: String): String = SmallBodyText.designationOfID(id)

    /** "Comet" or "Asteroid" for the identifier of a favourite whose orbit is gone. */
    fun category(id: String): ObjectCategory =
        if (SmallBodyText.kindOfID(id) == SmallBodyKind.ASTEROID) ObjectCategory.ASTEROID else ObjectCategory.COMET

    /** The words under the name: "Comet · no current orbit". */
    fun subtitle(id: String): String = "${category(id).displayName} · ${SmallBodyText.NO_CURRENT_ORBIT}"

    /** What a screen reader says for the row. */
    fun spoken(id: String): String = "${name(id)}, ${category(id).displayName}, ${SmallBodyText.NO_CURRENT_ORBIT}, Favorite"
}

/**
 * A favourite comet or asteroid without a current orbit, as a row of the Favorites section: its designation, "no
 * current orbit", and the star that removes it from the favourites.
 */
@Composable
internal fun SmallBodyNoOrbitRow(
    id: String,
    shape: Shape,
    showsDivider: Boolean,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val spoken = SmallBodyFavorites.spoken(id)
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(palette.surface)
            .drawBehind {
                if (showsDivider) {
                    val stroke = 0.5.dp.toPx()
                    drawLine(palette.stroke, Offset(16.dp.toPx(), size.height - stroke / 2), Offset(size.width, size.height - stroke / 2), stroke)
                }
            }
            .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp)
            .heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            Modifier.weight(1f).clearAndSetSemantics { contentDescription = spoken },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ObjectIcon(SmallBodyFavorites.category(id))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    SmallBodyFavorites.name(id),
                    color = palette.secondaryText,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                )
                Text(
                    SmallBodyFavorites.subtitle(id),
                    color = palette.tertiaryText,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Row(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .clickable(onClickLabel = "Remove from favorites", role = Role.Button, onClick = onRemove),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                Icons.Filled.Star,
                contentDescription = "Remove ${SmallBodyFavorites.name(id)} from favorites",
                tint = palette.accent,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
