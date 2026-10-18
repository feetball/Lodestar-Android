package net.kuecker.lodestar.features.mount

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GpsFixed
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.kuecker.lodestar.app.AppTab
import net.kuecker.lodestar.features.settings.SettingsActionRow
import net.kuecker.lodestar.kit.formatting.AngleFormatter
import net.kuecker.lodestar.kit.mount.MountPhase
import net.kuecker.lodestar.kit.mount.SkyWatcherMountMode
import net.kuecker.lodestar.services.MountService
import net.kuecker.lodestar.services.MountSkyWatcherInfo
import net.kuecker.lodestar.services.MountSnapshot
import net.kuecker.lodestar.services.MountTargeting
import net.kuecker.lodestar.shared.AppFormat
import net.kuecker.lodestar.shared.InfoRow
import net.kuecker.lodestar.shared.LocalAppModel
import net.kuecker.lodestar.shared.LodestarButton
import net.kuecker.lodestar.shared.LodestarButtonKind
import net.kuecker.lodestar.shared.SettingsCaption
import net.kuecker.lodestar.shared.SettingsDivider
import net.kuecker.lodestar.shared.SettingsRow
import net.kuecker.lodestar.shared.SettingsSection
import net.kuecker.lodestar.theme.LocalPalette
import java.time.Instant

/** Milliseconds between the refreshes of the list of suggested stars (the sky turns slowly). */
private const val SUGGESTION_REFRESH_MS = 30_000L

/** The name of an arrangement in the texts and the pickers. */
internal fun mountArrangementTitle(mode: SkyWatcherMountMode): String = when (mode) {
    SkyWatcherMountMode.ALT_AZIMUTH -> "Alt-azimuth (AZ-GTi on its tripod)"
    SkyWatcherMountMode.EQUATORIAL -> "Equatorial (Star Adventurer GTi, EQ mounts, AZ-GTi on a wedge)"
}

/** What to do with the mount before it is switched on, for each arrangement. */
internal fun mountHomeSteps(mode: SkyWatcherMountMode): String = when (mode) {
    SkyWatcherMountMode.ALT_AZIMUTH ->
        "Level the tripod and the mount base. Point the telescope or camera level, not tilted, and towards north. A " +
            "compass is enough: 5° off is fine, the alignment finds the rest."
    SkyWatcherMountMode.EQUATORIAL ->
        "Point the polar axis at the celestial pole as well as you can. Turn the counterweight bar so that it points " +
            "down at the ground. Point the telescope or camera parallel to the polar axis, at the pole."
}

/** Shown before connecting: put the mount at its home position first, with a picture for each arrangement. */
@Composable
internal fun ColumnScope.MountSkyWatcherHomeRows(arrangement: SkyWatcherMountMode?) {
    val palette = LocalPalette.current
    SettingsDivider()
    SettingsRow {
        Text(
            "Before you switch the mount on",
            color = palette.primaryText,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.semantics { heading() },
        )
        SettingsCaption(
            "Put the mount at its home position, and only then switch it on. The mount remembers where it stood when " +
                "it was switched on and Lodestar counts every move from there, so do not turn the mount by hand " +
                "afterwards.",
        )
        if (arrangement == null) {
            SettingsCaption(
                "Lodestar takes the arrangement from the mount model when it connects. For an AZ-GTi on a wedge " +
                    "choose Equatorial under Advanced options.",
            )
        }
    }
    val modes = arrangement?.let { listOf(it) } ?: SkyWatcherMountMode.entries
    for (mode in modes) {
        SettingsDivider()
        SettingsRow {
            Text(
                mountArrangementTitle(mode),
                color = palette.primaryText,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            )
            MountHomeDrawing(mode)
            SettingsCaption(mountHomeSteps(mode))
        }
    }
}

/**
 * The set-up of a connected Sky-Watcher mount in three steps: the start position, the site and time, and the alignment on one
 * to three stars (up to eight), with the stars so far, how well they fit, and the next stars to try. [onSync] opens the
 * confirmation to sync on the Aim target; without it (Settings) the section points to the Aim screen.
 */
@Composable
internal fun MountSkyWatcherSetupSection(
    service: MountService,
    snapshot: MountSnapshot,
    info: MountSkyWatcherInfo,
    onSync: (() -> Unit)?,
) {
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    SettingsSection(
        title = "Set up this mount",
        footer = "The mount has no sky of its own: Lodestar learns from the stars you centre where the mount really points.",
    ) {
        // 1. Start position
        SettingsRow {
            StepTitle("1. Start position")
            val away = info.startsAwayFromHome
            MountMessage(MountText.startPositionLine(info), isError = away)
            if (away) InfoRow("Axis angles at start", MountText.axisAngles(info))
        }
        SettingsActionRow(
            "This is home",
            onClick = { scope.launch { service.declareHome() } },
            icon = Icons.Outlined.MyLocation,
        )
        SettingsRow {
            SettingsCaption(
                "Tap this when the mount stands at its home position now, for example after you moved it there with the " +
                    "arrows. It clears the star alignment.",
            )
        }
        SettingsDivider()
        // 2. Site and time
        SettingsRow {
            StepTitle("2. Location and time")
            Text(SITE_AND_TIME_TEXT, color = palette.secondaryText, style = MaterialTheme.typography.bodyMedium)
            SettingsCaption("Check that the phone's clock is set automatically.")
        }
        SettingsDivider()
        // 3. Alignment
        SettingsRow {
            StepTitle("3. Align on one to three stars")
            Text(ALIGNMENT_GUIDE, color = palette.secondaryText, style = MaterialTheme.typography.bodyMedium)
            if (snapshot.isDemo) SettingsCaption(DEMO_GUIDE)
        }
        MountAlignmentSummary(service, info)
        MountAlignmentSuggestionRows(service, snapshot, info)
        MountSyncRow(onSync)
    }
}

/** Shown in place of the usual "send location and time" explanation: this mount is sent nothing. */
internal const val SITE_AND_TIME_TEXT = "Uses your location and the phone's clock for pointing; nothing is sent to the mount."

private const val ALIGNMENT_GUIDE =
    "Pick a bright star that is high in the sky and make it the target. Switch tracking on under Controls so it stays " +
        "put, then centre it in the camera with the nudge arrows (Guide, Center and Find rates) and tap Sync. Go to " +
        "stays off until the first star is synced. A second and third star far from the first make the mount much more " +
        "accurate."

private const val DEMO_GUIDE =
    "Simulated mount: the Sync screen has a button that puts the simulated telescope on the target for you, instead of " +
        "the nudge arrows."

@Composable
private fun StepTitle(text: String) {
    Text(
        text,
        color = LocalPalette.current.primaryText,
        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
        modifier = Modifier.semantics { heading() },
    )
}

/** The stars so far with their residuals and a button to drop each, the fit's accuracy, and Start over. */
@Composable
private fun MountAlignmentSummary(service: MountService, info: MountSkyWatcherInfo) {
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    SettingsDivider()
    SettingsRow {
        Text(
            MountText.alignmentLine(info),
            color = if (info.isAligned) palette.positive else palette.warning,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
        )
        info.homeErrorDegrees?.let { InfoRow("Home position error", AngleFormatter.formatDegrees(it, 1)) }
        info.predictedErrorDegrees?.let { InfoRow("Accuracy", "≈ ${MountText.accuracyText(it)} away from these stars") }
        if (info.isAligned && info.stars.size < MAXIMUM_STARS && (info.predictedErrorDegrees ?: 0.0) > ADD_STARS_ABOVE) {
            SettingsCaption("Add stars while the accuracy is poor, up to $MAXIMUM_STARS.")
        }
    }
    info.stars.forEachIndexed { index, star ->
        SettingsDivider()
        SettingsRow {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(star.name, color = palette.primaryText, style = MaterialTheme.typography.bodyLarge)
                    if (info.stars.size > 1) {
                        Text(
                            "${AngleFormatter.formatDegrees(star.residualDegrees, 2)} residual",
                            color = palette.secondaryText,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                LodestarButton(
                    "Remove",
                    onClick = { scope.launch { service.removeAlignmentStar(index) } },
                    kind = LodestarButtonKind.SECONDARY,
                    modifier = Modifier.semantics { contentDescription = "Remove ${star.name} from the alignment" },
                )
            }
        }
    }
    if (info.isAligned) {
        SettingsDivider()
        SettingsActionRow("Start over", onClick = { scope.launch { service.clearAlignment() } }, isDestructive = true)
    }
}

private const val MAXIMUM_STARS = 8
private const val ADD_STARS_ABOVE = 0.3

/** The stars to try next, from the bright stars that are well placed now; tap one to make it the target. */
@Composable
private fun MountAlignmentSuggestionRows(service: MountService, snapshot: MountSnapshot, info: MountSkyWatcherInfo) {
    val app = LocalAppModel.current
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    val observer = app.observerContext?.observer
    val catalog = app.catalog.catalog
    var candidates by remember { mutableStateOf<List<MountStarCandidate>>(emptyList()) }
    LaunchedEffect(observer, catalog) {
        if (observer == null || catalog == null) return@LaunchedEffect
        while (true) {
            candidates = withContext(Dispatchers.Default) { MountAlignmentSuggestions.candidates(catalog, observer, Instant.now()) }
            delay(SUGGESTION_REFRESH_MS)
        }
    }
    val suggestions = remember(candidates, info.stars) { MountAlignmentSuggestions.suggest(candidates, info.stars.mapNotNull { it.j2000 }) }
    SettingsDivider()
    SettingsRow {
        Text(
            if (info.isAligned) "Next star" else "First star",
            color = palette.primaryText,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.semantics { heading() },
        )
        if (suggestions.isEmpty()) {
            SettingsCaption("No bright star is well placed right now. Try again when the sky has darkened.")
        } else {
            SettingsCaption(
                if (info.isAligned) "Bright and high, and at least 30° from the stars you used. Tap one to make it the target."
                else "Bright and high in the sky. Tap one to make it the target.",
            )
        }
    }
    val canGoto = info.isAligned && snapshot.status.phase == MountPhase.IDLE
    for (suggestion in suggestions) {
        val candidate = suggestion.candidate
        SettingsDivider()
        SettingsRow(
            onClick = { app.select(candidate.obj) },
            modifier = Modifier.semantics(mergeDescendants = true) { role = Role.Button },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        candidate.obj.name + if (app.target?.id == candidate.obj.id) " · target" else "",
                        color = palette.primaryText,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    val away = suggestion.separationDegrees?.let { " · ${AppFormat.separation(it)} from the nearest used" }.orEmpty()
                    Text(
                        "${AppFormat.magnitude(candidate.magnitude)} · ${AppFormat.altitude(candidate.horizontal.altitude)} · " +
                            "${AppFormat.azimuth(candidate.horizontal.azimuth)}$away",
                        color = palette.secondaryText,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (canGoto && observer != null) {
                    LodestarButton(
                        "Go to",
                        onClick = {
                            app.select(candidate.obj)
                            scope.launch { service.goto(MountTargeting.target(candidate.obj, observer, Instant.now())) }
                        },
                        kind = LodestarButtonKind.SECONDARY,
                        modifier = Modifier.semantics { contentDescription = "Slew the mount to ${candidate.obj.name}" },
                    )
                }
            }
        }
    }
}

/** The step that follows the centring: tap Sync on the target (or open the Aim screen, where the nudge pad and sky are). */
@Composable
private fun MountSyncRow(onSync: (() -> Unit)?) {
    val app = LocalAppModel.current
    val target = app.target
    SettingsDivider()
    SettingsRow {
        if (onSync != null && target != null) {
            LodestarButton(
                "Sync on ${target.name}",
                onClick = onSync,
                modifier = Modifier.fillMaxWidth(),
                icon = Icons.Outlined.GpsFixed,
            )
            SettingsCaption("Only when the star is in the middle of the camera's frame.")
        } else if (target == null) {
            SettingsCaption("Choose a star above, or on the Aim screen, to make it the target.")
        } else {
            LodestarButton(
                "Open the Aim screen",
                onClick = { app.selectedTab = AppTab.AIM },
                modifier = Modifier.fillMaxWidth(),
                kind = LodestarButtonKind.SECONDARY,
            )
            SettingsCaption("Centre ${target.name} with the nudge arrows there, then tap Sync.")
        }
    }
}
