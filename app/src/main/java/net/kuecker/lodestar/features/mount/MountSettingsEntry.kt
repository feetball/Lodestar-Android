package net.kuecker.lodestar.features.mount

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ControlCamera
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import net.kuecker.lodestar.services.MountConnectionState
import net.kuecker.lodestar.shared.SettingsRow
import net.kuecker.lodestar.shared.SettingsSection
import net.kuecker.lodestar.theme.LocalPalette

/**
 * The Settings entry for the mount: one row that shows whether a mount is connected and opens [MountControlScreen].
 * [onOpen] pushes that screen.
 */
@Composable
fun SettingsMountControlSection(onOpen: () -> Unit) {
    val palette = LocalPalette.current
    val snapshot = rememberMountService().snapshot
    val status = when {
        snapshot.state == MountConnectionState.CONNECTED ->
            "Connected · ${snapshot.capabilities?.name ?: "mount"}" + if (snapshot.isDemo) " (simulated)" else ""
        snapshot.state == MountConnectionState.CONNECTING -> "Connecting…"
        snapshot.lostConnection -> "Connection lost"
        else -> "Not connected"
    }
    SettingsSection(
        title = "GoTo Mount",
        footer = "Send a computerised GoTo mount to the Aim target, sync it on a centred star and nudge it. Connects over Wi-Fi. After the first connection, Aim shows a Mount button.",
    ) {
        SettingsRow(onClick = onOpen, modifier = Modifier.semantics(mergeDescendants = true) { role = Role.Button }) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.ControlCamera, contentDescription = null, tint = palette.accent, modifier = Modifier.size(22.dp))
                Column(Modifier.weight(1f)) {
                    Text("Mount Control", color = palette.primaryText, style = MaterialTheme.typography.bodyLarge)
                    Text(status, color = palette.secondaryText, style = MaterialTheme.typography.bodySmall)
                }
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = palette.tertiaryText)
            }
        }
    }
}
