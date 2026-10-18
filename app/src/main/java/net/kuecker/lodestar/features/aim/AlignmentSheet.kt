package net.kuecker.lodestar.features.aim

import androidx.compose.ui.graphics.Color
import net.kuecker.lodestar.shared.LocalSheetDismiss
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.GpsFixed
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.UnfoldLess
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import net.kuecker.lodestar.kit.catalog.horizontal
import net.kuecker.lodestar.kit.formatting.AngleFormatter
import net.kuecker.lodestar.kit.foundation.SkyFrame
import net.kuecker.lodestar.kit.pointing.AlignmentError
import net.kuecker.lodestar.kit.pointing.AlignmentModel
import net.kuecker.lodestar.kit.pointing.AlignmentPoint
import net.kuecker.lodestar.model.HeadingSource
import net.kuecker.lodestar.shared.CardTitle
import net.kuecker.lodestar.shared.LocalAppModel
import net.kuecker.lodestar.shared.LodestarButton
import net.kuecker.lodestar.shared.LodestarButtonKind
import net.kuecker.lodestar.shared.LodestarSheet
import net.kuecker.lodestar.theme.LocalPalette
import java.time.Instant

/** Milliseconds between the refreshes of the candidates' positions. */
private const val CANDIDATE_REFRESH_INTERVAL_MS = 30_000L

/** Milliseconds between the re-sortings of the candidates by distance from the camera. */
private const val SORT_INTERVAL_MS = 2_500L

/**
 * The share of the screen's height the sheet fills: small by default, so the sky behind it can still be seen and the
 * tripod steered, and large when asked for (iOS drags between a 45 % and a full detent).
 */
private const val COLLAPSED_HEIGHT_SHARE = 0.4f
private const val EXPANDED_HEIGHT_SHARE = 0.85f

/**
 * Star alignment: the person centres a bright reference object in the camera and syncs, which teaches the app the
 * compass error and how the phone sits on the camera. The sheet shows what the alignment has found so far.
 *
 * The chosen object becomes the guidance target while the sheet is open, so the arrows lead to it; the real target is
 * back when the sheet closes.
 */
@Composable
internal fun AlignmentSheet(model: AimViewModel, onDismiss: () -> Unit) {
    // A light scrim: the sky stays readable behind the sheet while you find the reference star.
    LodestarSheet(onDismiss = onDismiss, scrimColor = Color.Black.copy(alpha = 0.15f)) {
        AlignmentContent(model, LocalSheetDismiss.current)
    }
}

@Composable
private fun AlignmentContent(model: AimViewModel, onDismiss: () -> Unit) {
    val app = LocalAppModel.current
    val palette = LocalPalette.current
    var candidates by remember { mutableStateOf<List<AimAlignmentCandidate>>(emptyList()) }
    var rows by remember { mutableStateOf<List<AimAlignmentRow>>(emptyList()) }
    var selected by remember { mutableStateOf<AimAlignmentCandidate?>(null) }
    var notice by remember { mutableStateOf<AimAlignmentNotice?>(null) }
    var isExpanded by remember { mutableStateOf(false) }

    /** Orders the candidates by distance from where the camera points; without sensor data, by brightness. */
    fun sortRows() {
        val boresight = model.engine.frame()?.pointing?.boresightVector
        rows = candidates
            .map { candidate -> AimAlignmentRow(candidate, boresight?.angle(candidate.horizontal.unitVector)) }
            .sortedWith(compareBy({ it.separation == null }, { it.separation }, { it.candidate.magnitude ?: 99.0 }))
    }

    fun deselect() {
        selected = null
        model.alignmentTarget = null
        sortRows()
    }

    fun select(candidate: AimAlignmentCandidate) {
        selected = candidate
        notice = null
        model.alignmentTarget = candidate.obj
    }

    /** Records the camera's attitude together with the object's true apparent position at this instant. */
    fun sync() {
        val chosen = selected
        val sample = app.motion.latestSample()
        val observer = app.observerContext?.observer
        if (chosen == null || sample == null || observer == null) {
            notice = AimAlignmentNotice.Failure(
                "The motion sensors are not delivering data yet. Wait a moment and sync again.",
            )
            return
        }
        val now = Instant.now()
        val apparent = SkyFrame(now, observer).horizontal(chosen.obj, refraction = true)
        val point = AlignmentPoint(sample.rawAttitude, apparent, now, chosen.obj.name)
        try {
            val alignment = app.alignment.adding(point, app.mountProfile)
            app.alignment = alignment
            notice = AimAlignmentNotice.Success(successText(chosen.obj.name, alignment))
            deselect()
        } catch (error: AlignmentError) {
            notice = AimAlignmentNotice.Failure(error.aimMessage(chosen.obj.name))
        }
    }

    fun remove(index: Int) {
        try {
            app.alignment = app.alignment.removingPoint(index, app.mountProfile)
            notice = null
        } catch (error: AlignmentError) {
            notice = AimAlignmentNotice.Failure(error.aimMessage("the object"))
        }
    }

    // Makes the list of reference objects now and again every half minute.
    val observer = app.observerContext?.observer
    val catalog = app.catalog.catalog
    LaunchedEffect(observer, catalog) {
        if (observer == null || catalog == null) return@LaunchedEffect
        while (true) {
            val date = Instant.now()
            candidates = withContext(Dispatchers.Default) { AimAlignmentCandidates.make(catalog, observer, date) }
            sortRows()
            delay(CANDIDATE_REFRESH_INTERVAL_MS)
        }
    }
    // Re-sorts the list now and then; while an object is chosen the list is hidden.
    LaunchedEffect(Unit) {
        while (true) {
            if (selected == null) sortRows()
            delay(SORT_INTERVAL_MS)
        }
    }
    DisposableEffect(model) { onDispose { model.alignmentTarget = null } }

    Column(
        Modifier
            .fillMaxWidth()
            .animateContentSize()
            .fillMaxHeight(if (isExpanded) EXPANDED_HEIGHT_SHARE else COLLAPSED_HEIGHT_SHARE),
    ) {
        Header(isExpanded, onToggleExpanded = { isExpanded = !isExpanded }, onDone = onDismiss)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Explanation()
            notice?.let { Notice(it) }
            val chosen = selected
            if (chosen != null) {
                AimReferenceCard(model, chosen)
            } else {
                CandidateList(rows, onSelect = ::select)
            }
            AimAlignmentSummaryCard(app.alignment, onRemove = ::remove, onReset = {
                app.resetAlignment()
                notice = null
            })
        }
        // The sync button, always in reach.
        Column(
            Modifier.fillMaxWidth().background(palette.surface).padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (selected != null) {
                LodestarButton(
                    "Centered — Sync", onClick = ::sync, modifier = Modifier.fillMaxWidth(), icon = Icons.Outlined.GpsFixed,
                )
                LodestarButton("Choose another object", onClick = ::deselect, kind = LodestarButtonKind.SECONDARY)
            } else {
                Box(Modifier.heightIn(min = 44.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "Choose a bright star or planet from the list.",
                        color = palette.secondaryText,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun Header(isExpanded: Boolean, onToggleExpanded: () -> Unit, onDone: () -> Unit) {
    val palette = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "Align on a star",
            color = palette.primaryText,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        // The sheet has two heights; this switches between them.
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(22.dp))
                .clickable(role = Role.Button, onClick = onToggleExpanded)
                .semantics { contentDescription = if (isExpanded) "Show less" else "Show more" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (isExpanded) Icons.Outlined.UnfoldLess else Icons.Outlined.UnfoldMore,
                contentDescription = null,
                tint = palette.accent,
                modifier = Modifier.size(24.dp),
            )
        }
        LodestarButton("Done", onClick = onDone, kind = LodestarButtonKind.SECONDARY)
    }
}

@Composable
private fun Explanation() {
    val app = LocalAppModel.current
    val palette = LocalPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "Alignment teaches Lodestar which way north really is for your phone and how the phone sits on the " +
                "camera. Pick a bright star or planet, steer the tripod until it is in the middle of your camera’s " +
                "frame, then tap Sync. A second object in another part of the sky makes it far more accurate.",
            color = palette.secondaryText,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (app.motion.headingSource == HeadingSource.GYRO_LOCK && !app.motion.isGyroHeadingReferenced) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = palette.warning, modifier = Modifier.size(16.dp))
                Text(
                    "Gyro lock has no heading yet. One alignment sets it.",
                    color = palette.warning,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                )
            }
        }
    }
}

/** The outcome of the last step, success or failure. */
@Composable
private fun Notice(notice: AimAlignmentNotice) {
    val palette = LocalPalette.current
    val color = if (notice.isSuccess) palette.positive else palette.warning
    Row(
        Modifier.fillMaxWidth().aimPanel(palette, padding = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            if (notice.isSuccess) Icons.Filled.CheckCircle else Icons.Filled.Warning,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(20.dp),
        )
        Text(
            notice.text,
            color = color,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun CandidateList(rows: List<AimAlignmentRow>, onSelect: (AimAlignmentCandidate) -> Unit) {
    val palette = LocalPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CardTitle("Reference objects", icon = Icons.Outlined.StarBorder)
        if (rows.isEmpty()) {
            Text(
                "No bright star or planet is between 15° and 78° right now. Try again later or after the sky has darkened.",
                color = palette.secondaryText,
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            Text(
                "Sorted by distance from where your camera points.",
                color = palette.tertiaryText,
                style = MaterialTheme.typography.bodySmall,
            )
            for (row in rows) {
                key(row.id) { AimCandidateRow(row, onClick = { onSelect(row.candidate) }) }
            }
        }
    }
}

private fun successText(name: String, alignment: AlignmentModel): String {
    if (alignment.points.size < 2) {
        return "Aligned on $name. Add a second object in another part of the sky to tell compass and bracket errors apart."
    }
    val uncertainty = alignment.pointingUncertainty ?: alignment.rmsResidual
    return "Aligned on $name. Expected accuracy ±${AngleFormatter.formatDegrees(uncertainty, if (uncertainty < 10) 1 else 0)}."
}
