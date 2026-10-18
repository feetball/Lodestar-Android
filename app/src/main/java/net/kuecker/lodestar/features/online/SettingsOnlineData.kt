package net.kuecker.lodestar.features.online

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.kuecker.lodestar.features.settings.SettingsActionRow
import net.kuecker.lodestar.services.OnlineFeed
import net.kuecker.lodestar.services.OnlineFeedStatus
import net.kuecker.lodestar.shared.AppFormat
import net.kuecker.lodestar.shared.InfoRow
import net.kuecker.lodestar.shared.LocalAppModel
import net.kuecker.lodestar.shared.LodestarButton
import net.kuecker.lodestar.shared.LodestarButtonKind
import net.kuecker.lodestar.shared.LodestarSheet
import net.kuecker.lodestar.shared.LocalSheetDismiss
import net.kuecker.lodestar.shared.SettingsCaption
import net.kuecker.lodestar.shared.SettingsDivider
import net.kuecker.lodestar.shared.SettingsRow
import net.kuecker.lodestar.shared.SettingsSection
import net.kuecker.lodestar.theme.LocalPalette
import java.time.Instant
import java.time.ZoneId

/**
 * Settings ▸ Online Data: one switch per optional download with what it is for, what leaves the phone, when it was last
 * updated, an "Update now" action and the last error. The first switch asks for confirmation once.
 */
@Composable
fun SettingsOnlineDataSection() {
    val app = LocalAppModel.current
    val online = app.online
    var pending by remember { mutableStateOf<OnlineFeed?>(null) }
    SettingsSection(title = OnlineText.SECTION_TITLE, footer = OnlineText.FOOTER) {
        SettingsRow { SettingsCaption(OnlineText.INTRO) }
        for (feed in OnlineFeed.entries) {
            SettingsDivider()
            OnlineFeedRows(
                feed = feed,
                status = online.statuses.getValue(feed),
                onToggle = { enabled ->
                    if (enabled && !online.hasExplained) pending = feed else online.setEnabled(feed, enabled)
                },
                onUpdateNow = { online.updateNow(feed) },
                timeZone = app.observerContext?.timeZone ?: ZoneId.systemDefault(),
            )
        }
    }
    pending?.let { feed ->
        OnlineFirstTimeSheet(
            onConfirm = {
                online.markExplained()
                online.setEnabled(feed, true)
                pending = null
            },
            onDismiss = { pending = null },
        )
    }
}

@Composable
private fun OnlineFeedRows(
    feed: OnlineFeed,
    status: OnlineFeedStatus,
    onToggle: (Boolean) -> Unit,
    onUpdateNow: () -> Unit,
    timeZone: ZoneId,
) {
    val palette = LocalPalette.current
    val now = rememberWallNow()
    SettingsRow(onClick = { onToggle(!status.isEnabled) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(feed.title, color = palette.primaryText, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(
                checked = status.isEnabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = palette.onAccent,
                    checkedTrackColor = palette.accent,
                    uncheckedThumbColor = palette.secondaryText,
                    uncheckedTrackColor = palette.surfaceRaised,
                    uncheckedBorderColor = palette.stroke,
                ),
            )
        }
        SettingsCaption(OnlineText.purpose(feed))
        Text(
            OnlineText.leavesPhone(feed),
            color = palette.tertiaryText,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        )
    }
    if (!status.isEnabled) return
    SettingsDivider()
    SettingsRow {
        InfoRow("Updated", if (status.isUpdating) OnlineText.UPDATING else OnlineText.since(status.lastUpdateMillis, now.toEpochMilli()))
        status.error?.let { error ->
            Text(error, color = palette.warning, style = MaterialTheme.typography.bodySmall)
        }
    }
    SettingsDivider()
    val next = status.nextRequestMillis
    val canUpdate = !status.isUpdating && next == null
    SettingsActionRow(OnlineText.UPDATE_NOW, onClick = onUpdateNow, icon = Icons.Outlined.Refresh, enabled = canUpdate)
    if (next != null && !status.isUpdating) {
        SettingsRow { SettingsCaption(OnlineText.nextUpdate(AppFormat.time(Instant.ofEpochMilli(next), timeZone))) }
    }
}

/** The explanation before the first feed is switched on. */
@Composable
private fun OnlineFirstTimeSheet(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val palette = LocalPalette.current
    LodestarSheet(onDismiss = onDismiss) {
        val dismiss = LocalSheetDismiss.current
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(OnlineText.FIRST_TIME_TITLE, color = palette.primaryText, style = MaterialTheme.typography.titleLarge)
            Text(OnlineText.FIRST_TIME_BODY, color = palette.secondaryText, style = MaterialTheme.typography.bodyMedium)
            LodestarButton(OnlineText.FIRST_TIME_CONFIRM, onClick = onConfirm, modifier = Modifier.fillMaxWidth())
            LodestarButton(
                OnlineText.FIRST_TIME_CANCEL, onClick = dismiss, modifier = Modifier.fillMaxWidth(),
                kind = LodestarButtonKind.SECONDARY,
            )
        }
    }
}
