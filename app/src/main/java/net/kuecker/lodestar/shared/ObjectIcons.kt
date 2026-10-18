package net.kuecker.lodestar.shared

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.Adjust
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BlurCircular
import androidx.compose.material.icons.outlined.BlurOn
import androidx.compose.material.icons.outlined.BubbleChart
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Cyclone
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Dehaze
import androidx.compose.material.icons.outlined.FilterDrama
import androidx.compose.material.icons.outlined.Flare
import androidx.compose.material.icons.outlined.GpsFixed
import androidx.compose.material.icons.outlined.Grain
import androidx.compose.material.icons.outlined.NightsStay
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.automirrored.outlined.StarHalf
import androidx.compose.material.icons.outlined.Stars
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material.icons.outlined.WbTwilight
import androidx.compose.ui.graphics.vector.ImageVector
import net.kuecker.lodestar.kit.catalog.ObjectCategory
import net.kuecker.lodestar.kit.catalog.ObjectGroup

/** The Material icon for an object category (the iOS app uses the SF Symbol in `systemImageName`). */
val ObjectCategory.icon: ImageVector
    get() = when (this) {
        ObjectCategory.SUN -> Icons.Outlined.WbSunny
        ObjectCategory.MOON -> Icons.Outlined.DarkMode
        ObjectCategory.PLANET -> Icons.Outlined.Adjust
        ObjectCategory.STAR -> Icons.Outlined.StarBorder
        ObjectCategory.DOUBLE_STAR -> Icons.AutoMirrored.Outlined.StarHalf
        ObjectCategory.GALAXY -> Icons.Outlined.Cyclone
        ObjectCategory.GALAXY_GROUP -> Icons.Outlined.BubbleChart
        ObjectCategory.EMISSION_NEBULA -> Icons.Filled.Cloud
        ObjectCategory.REFLECTION_NEBULA -> Icons.Outlined.FilterDrama
        ObjectCategory.DARK_NEBULA -> Icons.Outlined.Dehaze
        ObjectCategory.NEBULA -> Icons.Outlined.Cloud
        ObjectCategory.PLANETARY_NEBULA -> Icons.Outlined.BlurCircular
        ObjectCategory.SUPERNOVA_REMNANT -> Icons.Outlined.Flare
        ObjectCategory.OPEN_CLUSTER -> Icons.Outlined.AutoAwesome
        ObjectCategory.GLOBULAR_CLUSTER -> Icons.Outlined.BlurOn
        ObjectCategory.CLUSTER_WITH_NEBULA -> Icons.Outlined.WbTwilight
        ObjectCategory.STAR_ASSOCIATION -> Icons.Outlined.Grain
        ObjectCategory.ASTERISM -> Icons.Outlined.Stars
        ObjectCategory.MILKY_WAY -> Icons.Outlined.NightsStay
        ObjectCategory.CELESTIAL_POLE -> Icons.Outlined.GpsFixed
        ObjectCategory.CUSTOM -> Icons.Outlined.AddCircleOutline
        ObjectCategory.OTHER -> Icons.Outlined.AutoAwesome
    }

/** The Material icon for an object group. */
val ObjectGroup.icon: ImageVector
    get() = when (this) {
        ObjectGroup.SOLAR_SYSTEM -> Icons.Outlined.WbSunny
        ObjectGroup.STARS -> Icons.Outlined.StarBorder
        ObjectGroup.GALAXIES -> Icons.Outlined.Cyclone
        ObjectGroup.NEBULAE -> Icons.Outlined.Cloud
        ObjectGroup.CLUSTERS -> Icons.Outlined.AutoAwesome
        ObjectGroup.SPECIAL -> Icons.Outlined.GpsFixed
    }
