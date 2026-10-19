package net.kuecker.lodestar.features.mount

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ControlCamera
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.kuecker.lodestar.features.settings.SettingsActionRow
import net.kuecker.lodestar.features.settings.SettingsSegmentedPicker
import net.kuecker.lodestar.features.settings.SettingsSubScreen
import net.kuecker.lodestar.kit.mount.MountPhase
import net.kuecker.lodestar.kit.mount.MountProtocol
import net.kuecker.lodestar.services.MountConnectionState
import net.kuecker.lodestar.services.MountDemoKind
import net.kuecker.lodestar.services.MountDiscoveryKind
import net.kuecker.lodestar.services.MountFoundTelescope
import net.kuecker.lodestar.services.MountProtocols
import net.kuecker.lodestar.services.MountService
import net.kuecker.lodestar.services.MountSettingsValues
import net.kuecker.lodestar.services.MountSnapshot
import net.kuecker.lodestar.shared.ChipTone
import net.kuecker.lodestar.shared.InfoRow
import net.kuecker.lodestar.shared.LocalAppModel
import net.kuecker.lodestar.shared.LodestarButton
import net.kuecker.lodestar.shared.SettingsCaption
import net.kuecker.lodestar.shared.SettingsDivider
import net.kuecker.lodestar.shared.SettingsMenuOption
import net.kuecker.lodestar.shared.SettingsMenuPicker
import net.kuecker.lodestar.shared.SettingsNumberField
import net.kuecker.lodestar.shared.SettingsRow
import net.kuecker.lodestar.shared.SettingsSection
import net.kuecker.lodestar.shared.SettingsToggleRow
import net.kuecker.lodestar.shared.StatusChip
import net.kuecker.lodestar.theme.LocalPalette

/** Settings ▸ Mount Control: connect to a GoTo mount, see what it can do, nudge it, and set the safety limits. */
@Composable
fun MountControlScreen(onBack: () -> Unit) {
    MountControlScreenshotEffect()
    SettingsSubScreen("Mount Control", onBack) { MountControlContent() }
}

/**
 * Everything about the mount in one scrolling column, for the Settings screen and the Aim sheet: the state of the
 * connection, the form to connect (or the controls once connected), the limits, the safety rules and what each kind
 * of mount works with.
 */
@Composable
internal fun MountControlContent(onSync: (() -> Unit)? = null) {
    val service = rememberMountService()
    val draft = rememberMountDraft(service)
    val snapshot = service.snapshot
    MountStatusSection(service, snapshot)
    when (snapshot.state) {
        MountConnectionState.DISCONNECTED -> MountConnectionSection(service, draft, snapshot)
        MountConnectionState.CONNECTING -> Unit
        MountConnectionState.CONNECTED -> MountOperateSection(service, snapshot, onSync)
    }
    MountLimitsSection(service, draft)
    MountSafetySection()
    MountProtocolsSection()
}

// Status

@Composable
private fun MountStatusSection(service: MountService, snapshot: MountSnapshot) {
    val app = LocalAppModel.current
    val palette = LocalPalette.current
    SettingsSection(title = "Status") {
        SettingsRow {
            when (snapshot.state) {
                MountConnectionState.DISCONNECTED -> {
                    if (snapshot.lostConnection) {
                        StatusChip(Icons.Outlined.LinkOff, "Connection lost", tone = ChipTone.CRITICAL)
                    } else {
                        StatusChip(Icons.Outlined.ControlCamera, "Not connected")
                    }
                }
                MountConnectionState.CONNECTING -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    MountSpinner()
                    Text(
                        "Connecting to ${snapshot.connection?.host.orEmpty()}…",
                        color = palette.primaryText,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                }
                MountConnectionState.CONNECTED -> StatusChip(
                    Icons.Outlined.Sensors,
                    if (snapshot.linkProblem != null) "Connected · link problem" else "Connected",
                    tone = if (snapshot.linkProblem != null) ChipTone.WARNING else ChipTone.POSITIVE,
                )
            }
            if (snapshot.state == MountConnectionState.DISCONNECTED && !snapshot.lostConnection && snapshot.connectProblem == null) {
                SettingsCaption(
                    "Connect a GoTo mount to send it to the Aim target, sync it on a centred star and nudge it. " +
                        "Without one, Lodestar guides with the phone's sensors as usual.",
                )
            }
        }
        val capabilities = snapshot.capabilities
        if (snapshot.state == MountConnectionState.CONNECTED && capabilities != null) {
            SettingsDivider()
            SettingsRow {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    InfoRow("Mount", capabilities.name + if (snapshot.isDemo) " (simulated)" else "")
                    capabilities.firmware?.let { InfoRow("Firmware", it) }
                    InfoRow("Protocol", MountProtocols.option(capabilities.kind).title)
                    snapshot.connection?.takeUnless { snapshot.isDemo }?.let { InfoRow("Address", "${it.host}:${it.port} (${it.link.title})") }
                    snapshot.status.position?.let { InfoRow("Points at", MountText.position(it, app.settings.coordinateStyle)) }
                    InfoRow("State", MountText.phaseLine(snapshot))
                    InfoRow(
                        "Alignment",
                        snapshot.skyWatcher?.let(MountText::alignmentLine)
                            ?: when (snapshot.isAligned ?: capabilities.isAligned) {
                                true -> "Aligned"
                                false -> "Not aligned"
                                null -> "Not reported"
                            },
                    )
                    snapshot.skyWatcher?.let { info ->
                        info.pierSide?.let { InfoRow("Pier side", MountText.pierSideLine(it)) }
                        MountText.trackingLine(snapshot)?.let { InfoRow("Tracking", it) }
                    }
                    InfoRow("Can", MountText.abilities(capabilities).joinToString(", "))
                }
            }
            MountText.notAlignedBanner(snapshot)?.let {
                SettingsDivider()
                SettingsRow { MountMessage(it, isError = true) }
            }
            MountText.trackingStopLine(snapshot)?.let {
                SettingsDivider()
                SettingsRow { MountMessage(it, isError = true) }
            }
        }
        snapshot.linkProblem?.let {
            SettingsDivider()
            SettingsRow { MountMessage(it, isError = true) }
        }
        snapshot.notice?.let {
            SettingsDivider()
            SettingsRow { MountMessage(it.text, isError = it.isError) }
        }
        if (snapshot.state == MountConnectionState.CONNECTING) {
            SettingsDivider()
            SettingsActionRow("Cancel", onClick = service::cancelConnect)
        }
        if (snapshot.state == MountConnectionState.CONNECTED) {
            SettingsDivider()
            SettingsActionRow("Disconnect", onClick = service::disconnect, isDestructive = true)
        }
        if (snapshot.state == MountConnectionState.DISCONNECTED && snapshot.lostConnection) {
            SettingsDivider()
            SettingsActionRow("Reconnect", onClick = service::reconnect)
        }
    }
}

/** A line of text with a warning mark (errors) or without (information). */
@Composable
internal fun MountMessage(text: String, isError: Boolean) {
    val palette = LocalPalette.current
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (isError) Icon(Icons.Filled.Warning, contentDescription = null, tint = palette.warning, modifier = Modifier.size(18.dp))
        Text(
            text,
            color = if (isError) palette.warning else palette.positive,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
        )
    }
}

// Connecting

@Composable
private fun MountConnectionSection(service: MountService, draft: MountDraft, snapshot: MountSnapshot) {
    val palette = LocalPalette.current
    val option = draft.option
    val protocols = MountProtocols.all.map { SettingsMenuOption(it.title, it.protocol) }
    SettingsSection(title = "Connect") {
        SettingsMenuPicker(
            title = "Mount type",
            selection = draft.protocol,
            options = protocols,
            onSelect = draft::selectProtocol,
        )
        SettingsDivider()
        SettingsRow {
            Text(option.summary, color = palette.primaryText, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
            SettingsCaption(option.explanation)
        }
        if (option.links.size > 1) {
            SettingsDivider()
            SettingsRow {
                SettingsSegmentedPicker(
                    title = "Connection",
                    selection = draft.link,
                    options = option.links.map { SettingsMenuOption(it.title, it) },
                    onSelect = draft::selectLink,
                )
                SettingsCaption("The SynScan Wi-Fi adapter uses UDP; Celestron Wi-Fi bridges use TCP.")
            }
        }
        SettingsDivider()
        MountTextRow(
            title = "Address",
            value = draft.host,
            onValueChange = draft::editHost,
            placeholder = "192.168.4.1",
        )
        SettingsDivider()
        SettingsNumberField(
            title = "Port",
            value = draft.port.toDouble(),
            onValueChange = { draft.editPort(it.toInt()) },
            range = 1.0..65_535.0,
            fractionDigits = 0,
        )
        if (option.supportsDiscovery) MountDiscoveryRows(draft)
        if (option.protocol == MountProtocol.SKYWATCHER) {
            MountSkyWatcherHomeRows(service.preferences.settings.advanced.skyWatcherMode)
        }
        SettingsDivider()
        SettingsRow {
            LodestarButton(
                "Connect",
                onClick = { service.connect(draft.connection()) },
                modifier = Modifier.fillMaxWidth(),
                enabled = draft.host.isNotBlank(),
                icon = Icons.Outlined.ControlCamera,
            )
            snapshot.connectProblem?.let { MountMessage(it, isError = true) }
            SettingsCaption("Join the mount's Wi-Fi network first. Connecting never moves the mount.")
        }
        for (kind in MountDemoKind.entries) {
            SettingsDivider()
            SettingsActionRow(kind.title, onClick = { service.connect(demoConnection(kind), demo = kind) }, icon = Icons.Outlined.PlayCircle)
        }
        SettingsRow {
            SettingsCaption(
                "A mount without hardware, to see how Go to, Sync and the nudge arrows work. The Sky-Watcher one also " +
                    "shows the set-up and the alignment on stars.",
            )
        }
    }
}

@Composable
private fun MountDiscoveryRows(draft: MountDraft) {
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    val kind = draft.option.discovery ?: return
    val isAlpaca = kind == MountDiscoveryKind.ALPACA
    SettingsDivider()
    SettingsActionRow(
        if (draft.isSearching) "Searching…" else kind.buttonTitle,
        onClick = { draft.search(scope) },
        icon = Icons.Outlined.Search,
        enabled = !draft.isSearching,
    )
    if (draft.isSearching) {
        SettingsRow {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MountSpinner(18.dp)
                SettingsCaption("Looking on this network for about two seconds…")
            }
        }
    }
    val found = draft.found
    if (found != null && found.isEmpty() && !draft.isSearching) {
        SettingsRow {
            SettingsCaption(kind.emptyText)
        }
    }
    for (telescope in found.orEmpty()) {
        SettingsDivider()
        MountFoundRow(
            telescope,
            showsDevice = isAlpaca,
            isChosen = telescope.host == draft.host && telescope.port == draft.port && (!isAlpaca || telescope.deviceNumber == draft.deviceNumber),
        ) {
            draft.choose(telescope)
        }
    }
    val name = draft.deviceName
    if (isAlpaca && name != null) {
        SettingsDivider()
        SettingsRow { InfoRow("Telescope", "$name (device ${draft.deviceNumber})") }
    }
    if (isAlpaca && found == null && name == null) {
        SettingsRow { Text("Device 0 is used unless you pick a telescope.", color = palette.tertiaryText, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun MountFoundRow(telescope: MountFoundTelescope, showsDevice: Boolean, isChosen: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    SettingsRow(onClick = onClick, modifier = Modifier.semantics(mergeDescendants = true) { role = Role.Button }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(telescope.deviceName, color = palette.primaryText, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "${telescope.host}:${telescope.port}" + if (showsDevice) " · device ${telescope.deviceNumber}" else "",
                    color = palette.secondaryText,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (isChosen) Icon(Icons.Outlined.Check, contentDescription = "Chosen", tint = palette.accent)
        }
    }
}

// Connected

@Composable
private fun MountOperateSection(service: MountService, snapshot: MountSnapshot, onSync: (() -> Unit)?) {
    val capabilities = snapshot.capabilities ?: return
    val scope = rememberCoroutineScope()
    val settings = service.preferences.settings
    snapshot.skyWatcher?.let { MountSkyWatcherSetupSection(service, snapshot, it, onSync) }
    SettingsSection(title = "Controls") {
        if (capabilities.canSetTracking) {
            SettingsToggleRow(
                title = "Tracking",
                checked = snapshot.status.isTracking == true,
                onCheckedChange = { enabled -> scope.launch { service.setTracking(enabled) } },
                caption = "Keeps the sky still in the camera. Turn it on before a long exposure.",
            )
            SettingsDivider()
        }
        if (capabilities.canSetSiteAndTime && snapshot.skyWatcher == null) {
            SettingsActionRow("Send location and time now", onClick = { scope.launch { service.sendSiteAndTime() } })
            SettingsRow { SettingsCaption("Sends your observing location and the phone's time (UTC) to the mount.") }
            SettingsDivider()
        }
        if (capabilities.canNudge) {
            SettingsRow {
                MountRatePicker(rate = settings.nudgeRate, onSelect = { rate ->
                    service.preferences.update { it.copy(nudgeRate = rate) }
                })
            }
            SettingsRow {
                MountNudgePad(service, settings.nudgeRate, isEnabled = snapshot.status.phase != MountPhase.SLEWING)
                SettingsCaption(
                    "Hold an arrow to keep moving. N and S move along declination, E and W along right ascension " +
                        "(north up, east left, as on a star chart). The red button stops the mount.",
                )
            }
        } else {
            SettingsRow { SettingsCaption("This mount cannot be nudged from the app.") }
        }
    }
}

// Limits and options

@Composable
private fun MountLimitsSection(service: MountService, draft: MountDraft) {
    val preferences = service.preferences
    val settings = preferences.settings
    val option = draft.option
    val syncOptions = MountSettingsValues.syncLimits
        .map { SettingsMenuOption(if (it >= MountSettingsValues.NO_SYNC_LIMIT) "No limit" else "${it.toInt()}°", it) }
    SettingsSection(
        title = "Limits",
        footer = "Limits apply to the next move; the altitude limit also applies to a move already planned.",
    ) {
        SettingsNumberField(
            title = "Altitude limit",
            value = settings.altitudeLimit,
            onValueChange = { value ->
                preferences.update { it.copy(altitudeLimit = value) }
                service.settingsChanged()
            },
            range = -10.0..60.0,
            unit = "°",
            fractionDigits = 0,
        )
        SettingsRow {
            SettingsCaption(
                "Lodestar will not slew to anything lower than this, nor to a target that sinks below it within a " +
                    "minute. Set it above trees, roofs and anything the mount could hit.",
            )
        }
        SettingsDivider()
        if (option.protocol == MountProtocol.SKYWATCHER) {
            SettingsRow {
                Text(
                    "Location and time",
                    color = LocalPalette.current.primaryText,
                    style = MaterialTheme.typography.bodyLarge,
                )
                SettingsCaption(SITE_AND_TIME_TEXT)
            }
        } else {
            SettingsToggleRow(
                title = "Send location and time when connecting",
                checked = settings.sendsSiteAndTime,
                onCheckedChange = { enabled ->
                    preferences.update { it.copy(sendsSiteAndTime = enabled) }
                    service.settingsChanged()
                },
                caption = "Mounts that work out their own sky need the right place and UTC time. Turn this off if the mount's " +
                    "hand controller or another app already sets them.",
            )
        }
        SettingsDivider()
        SettingsMenuPicker(
            title = "Largest sync correction",
            selection = settings.maximumSyncOffset,
            options = syncOptions,
            onSelect = { value ->
                preferences.update { it.copy(maximumSyncOffset = value) }
                service.settingsChanged()
            },
        )
        SettingsRow {
            SettingsCaption(
                "A sync that would move the mount's idea of its position by more than this is refused, which catches a wrong " +
                    "star. Sky-Watcher Wi-Fi mounts ignore this: they judge a star by how well it fits the others.",
            )
        }
        if (option.advancedFields.isNotEmpty()) {
            SettingsDivider()
            SettingsActionRow(
                if (draft.showsAdvanced) "Hide advanced options" else "Advanced options",
                onClick = { draft.showsAdvanced = !draft.showsAdvanced },
            )
            if (draft.showsAdvanced) MountAdvancedRows(service, draft)
        }
    }
}

/** The connection a simulated mount of [kind] shows in the status (it never touches the network). */
private fun demoConnection(kind: MountDemoKind) = MountProtocols.option(
    when (kind) {
        MountDemoKind.LX200 -> MountProtocol.LX200
        MountDemoKind.SKYWATCHER -> MountProtocol.SKYWATCHER
    },
).defaultConnection()
