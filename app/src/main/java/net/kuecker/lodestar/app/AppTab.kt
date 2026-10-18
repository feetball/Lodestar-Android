package net.kuecker.lodestar.app

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BlurCircular
import androidx.compose.material.icons.outlined.GpsFixed
import androidx.compose.material.icons.outlined.NightsStay
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector

/** The app's tabs. */
enum class AppTab(val title: String, val icon: ImageVector) {
    AIM("Aim", Icons.Outlined.GpsFixed),
    OBJECTS("Objects", Icons.Outlined.AutoAwesome),
    SKY("Sky", Icons.Outlined.BlurCircular),
    TONIGHT("Tonight", Icons.Outlined.NightsStay),
    SETTINGS("Settings", Icons.Outlined.Settings),
}
