package net.kuecker.lodestar.features.tools

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import net.kuecker.lodestar.kit.formatting.AngleFormatter
import net.kuecker.lodestar.kit.foundation.AstroMath
import net.kuecker.lodestar.kit.foundation.Matrix3
import net.kuecker.lodestar.kit.foundation.Vector3
import net.kuecker.lodestar.kit.foundation.formatFixed
import net.kuecker.lodestar.kit.geomagnetism.CompassReliability
import net.kuecker.lodestar.services.CompassQuality
import net.kuecker.lodestar.services.MotionService
import net.kuecker.lodestar.services.MotionStatus
import net.kuecker.lodestar.shared.AppFormat
import net.kuecker.lodestar.shared.ChipTone
import net.kuecker.lodestar.shared.LocalAppModel
import net.kuecker.lodestar.shared.StatusChip
import net.kuecker.lodestar.theme.LocalPalette
import java.text.NumberFormat
import java.time.Instant

/** The screen refreshes ten times a second. */
private const val REFRESH_MILLISECONDS = 100L

/** Above this fractional deviation from the expected field strength the compass is probably disturbed. */
private const val INTERFERENCE_THRESHOLD = 0.25

/** The smallest font the matrix entries shrink to when large system fonts leave their columns too narrow. */
private val MATRIX_MINIMUM_FONT_SIZE = 8.sp

/**
 * Live self-checks of the motion sensors and the attitude convention (read-only, drawn in the palette so it is safe
 * to use at night): the attitude matrix, the camera boresight, the gravity check, the top-edge heading to compare
 * with a compass app, the compass and the reference frame.
 *
 * Push it onto a navigation stack (Settings ▸ About). It refreshes ten times a second and keeps the motion service
 * running while it is visible.
 */
@Composable
fun SensorDiagnosticsScreen(onBack: () -> Unit) {
    val app = LocalAppModel.current
    DisposableEffect(app) {
        app.keepsMotionRunning = true
        onDispose { app.keepsMotionRunning = false }
    }
    var date by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(app.isForeground) {
        if (!app.isForeground) return@LaunchedEffect
        while (isActive) {
            delay(REFRESH_MILLISECONDS)
            date = Instant.now()
        }
    }
    val scroll = rememberWhilePushed("tools.diagnostics.scroll") { ScrollState(0) }
    ToolsScreenFrame(title = "Sensor Diagnostics", onBack = onBack) {
        SensorDiagnosticsForm(date, Modifier.weight(1f).verticalScroll(scroll))
    }
}

/** The diagnostics for the newest motion sample at [date]. */
@Composable
private fun SensorDiagnosticsForm(date: Instant, modifier: Modifier) {
    val app = LocalAppModel.current
    val motion = app.motion
    val snapshot = motion.latestSample()?.let(::SensorDiagnosticsSnapshot)
    val magnetic = app.observerContext?.let { SensorDiagnosticsMagneticModel.create(it.location, date) }
    Column(modifier) {
        StatusSection(motion, hasSample = snapshot != null)
        if (snapshot != null) {
            MatrixSection(snapshot)
            BoresightSection(snapshot)
            GravitySection(snapshot)
            TopEdgeSection(snapshot, magnetic)
            CompassSection(motion, snapshot, magnetic)
        }
        ReferenceSection(motion, snapshot)
        Spacer(Modifier.height(24.dp))
    }
}

// MARK: Sections

@Composable
private fun StatusSection(motion: MotionService, hasSample: Boolean) {
    val palette = LocalPalette.current
    FormSection(title = "Motion Sensors") {
        when (val status = motion.status) {
            MotionStatus.Unavailable -> caption(
                "This phone has no usable motion sensors, so these checks cannot run. They need a rotation vector " +
                    "sensor, or an accelerometer and a magnetometer.",
            )
            is MotionStatus.Failed -> row {
                Text(status.message, color = palette.critical, style = MaterialTheme.typography.bodyLarge)
            }
            MotionStatus.Idle, MotionStatus.Starting -> caption("Starting the motion sensors…")
            MotionStatus.Running ->
                if (hasSample) {
                    row { StatusChip(Icons.Outlined.Sensors, "Receiving samples", tone = ChipTone.POSITIVE) }
                } else {
                    caption("Waiting for the first sample…")
                }
        }
    }
}

@Composable
private fun MatrixSection(snapshot: SensorDiagnosticsSnapshot) {
    FormSection(
        title = "Attitude Matrix",
        footer = "The attitude matrix M that Lodestar builds from Android's rotation vector (magnetic north plus " +
            "the World Magnetic Model declination) or, in gyro lock, from the game rotation vector, with " +
            "v_device = M · v_reference. Its rows are the device axes (right edge, top edge, screen normal) " +
            "written in the reference frame (north, west, up).",
    ) {
        row { SensorMatrixGrid(snapshot.matrix) }
    }
}

@Composable
private fun BoresightSection(snapshot: SensorDiagnosticsSnapshot) {
    FormSection(
        title = "Camera Boresight",
        footer = "The direction the back camera looks along, from the matrix: azimuth = atan2(m32, −m31), " +
            "altitude = asin(−m33).",
    ) {
        info("Azimuth from true north", AppFormat.azimuth(snapshot.boresight.azimuth))
        info("Altitude", AppFormat.altitude(snapshot.boresight.altitude))
        info("Azimuth within the frame", AngleFormatter.formatAzimuth(snapshot.boresightInFrame.azimuth))
        info(
            "LodestarKit agrees",
            if (snapshot.boresightsAgree) {
                "Yes"
            } else {
                "No, ${AngleFormatter.formatDegrees(snapshot.boresightDisagreement, 2)} apart"
            },
        )
    }
}

@Composable
private fun GravitySection(snapshot: SensorDiagnosticsSnapshot) {
    val tolerance = NumberFormat.getNumberInstance().format(SensorDiagnosticsSnapshot.GRAVITY_TOLERANCE)
    FormSection(
        title = "Gravity Check",
        footer = "If M maps reference vectors to device vectors, gravity in device coordinates equals the third " +
            "column of M, (−m13, −m23, −m33). The residual must stay below $tolerance g. In " +
            "symmetric poses, such as upright with the camera pointing east, both mappings agree and the check " +
            "proves nothing: tilt the phone.",
    ) {
        row { VerdictChip(snapshot.gravityVerdict) }
        info("Gravity (device)", vector(snapshot.gravity))
        info("Residual, reference → device", residual(snapshot.referenceToDeviceResidual))
        info("Residual, device → reference", residual(snapshot.deviceToReferenceResidual))
    }
}

@Composable
private fun TopEdgeSection(snapshot: SensorDiagnosticsSnapshot, magnetic: SensorDiagnosticsMagneticModel?) {
    FormSection(
        title = "Top-Edge Heading",
        footer = "Lay the phone flat, screen up, with its top edge towards a known bearing and compare with a " +
            "compass app (most show magnetic north unless true north is selected in their settings). A constant " +
            "difference of 90° or 270° would mean the north axis of the convention is wrong.",
    ) {
        row {
            if (snapshot.isFlat) {
                StatusChip(Icons.Outlined.PhoneAndroid, "Flat", tone = ChipTone.POSITIVE)
            } else {
                StatusChip(Icons.Outlined.Warning, "Not flat", tone = ChipTone.WARNING)
            }
        }
        info("Tilt from flat", AngleFormatter.formatDegrees(snapshot.tiltFromFlat))
        info("True heading", AppFormat.azimuth(snapshot.topEdgeHeading))
        if (magnetic != null) {
            val magneticHeading = AstroMath.normalizeDegrees(snapshot.topEdgeHeading - magnetic.declination)
            info("Magnetic heading", AppFormat.azimuth(magneticHeading))
        }
    }
}

@Composable
private fun CompassSection(
    motion: MotionService,
    snapshot: SensorDiagnosticsSnapshot,
    magnetic: SensorDiagnosticsMagneticModel?,
) {
    FormSection(title = "Compass") {
        info("Compass quality", motion.compassQuality.title)
        info("Field measured", microtesla(snapshot.magneticFieldMicrotesla))
        if (magnetic != null) {
            info("Field expected (WMM)", microtesla(magnetic.expectedFieldMicrotesla))
            val measured = snapshot.magneticFieldMicrotesla
            if (measured != null) {
                val deviation = magnetic.deviation(measured)
                row {
                    if (deviation > INTERFERENCE_THRESHOLD) {
                        StatusChip(Icons.Outlined.Warning, "Interference suspected", tone = ChipTone.WARNING)
                    } else {
                        StatusChip(Icons.Outlined.Check, "Field as expected", tone = ChipTone.POSITIVE)
                    }
                }
                info("Deviation", AppFormat.percent(deviation))
            }
            info("Averaged deviation", AppFormat.percent(motion.magneticDisturbance))
            info("Declination", AngleFormatter.formatSignedDegrees(magnetic.declination, 2))
            info("Inclination", AngleFormatter.formatSignedDegrees(magnetic.inclination, 1))
            info("Horizontal field", microtesla(magnetic.horizontalFieldMicrotesla))
            info("Compass here", magnetic.reliability.text)
            if (!magnetic.isCurrent) {
                caption(
                    "The World Magnetic Model 2025 is outside its validity (2025–2030); declination and field " +
                        "strength are extrapolated.",
                )
            }
        } else {
            caption("The expected field and the declination come from the World Magnetic Model and need a location.")
        }
    }
}

@Composable
private fun ReferenceSection(motion: MotionService, snapshot: SensorDiagnosticsSnapshot?) {
    FormSection(title = "Reference Frame") {
        info("Reference", motion.referenceDescription)
        info("Heading source", motion.headingSource.title)
        info("Gyro heading referenced", if (motion.isGyroHeadingReferenced) "Yes" else "No")
        info("Frame epoch", "${motion.frameEpoch}")
        if (snapshot != null) {
            info("Reference azimuth", AngleFormatter.formatSignedDegrees(snapshot.referenceAzimuth, 2))
            info("Rotation speed", AngleFormatter.formatDegrees(snapshot.angularSpeed) + "/s")
        }
    }
}

// MARK: Pieces

@Composable
private fun VerdictChip(verdict: SensorDiagnosticsSnapshot.GravityVerdict) {
    when (verdict) {
        SensorDiagnosticsSnapshot.GravityVerdict.PASS ->
            StatusChip(Icons.Outlined.CheckCircle, "PASS", tone = ChipTone.POSITIVE)
        SensorDiagnosticsSnapshot.GravityVerdict.FAIL ->
            StatusChip(Icons.Outlined.Cancel, "FAIL", tone = ChipTone.CRITICAL)
        SensorDiagnosticsSnapshot.GravityVerdict.INCONCLUSIVE ->
            StatusChip(Icons.Outlined.Warning, "Tilt the phone", tone = ChipTone.WARNING)
    }
}

/** The nine entries of the attitude matrix in a monospaced grid. */
@Composable
private fun SensorMatrixGrid(matrix: Matrix3) {
    val palette = LocalPalette.current
    val rows = listOf(matrix.r0, matrix.r1, matrix.r2)
    val style = MaterialTheme.typography.bodyLarge.copy(
        fontFamily = FontFamily.Monospace,
        letterSpacing = TextUnit.Unspecified,
        lineHeight = TextUnit.Unspecified,
    )
    Column(
        Modifier.fillMaxWidth().clearAndSetSemantics {
            contentDescription = "Rotation matrix"
            stateDescription = spokenRows(rows)
        },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (row in rows) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for (entry in doubleArrayOf(row.x, row.y, row.z)) {
                    // Every entry has seven characters, so they all shrink alike when the column is too narrow.
                    Text(
                        signed(entry, 4),
                        modifier = Modifier.weight(1f),
                        color = palette.primaryText,
                        textAlign = TextAlign.End,
                        maxLines = 1,
                        autoSize = TextAutoSize.StepBased(
                            minFontSize = MATRIX_MINIMUM_FONT_SIZE,
                            maxFontSize = style.fontSize,
                        ),
                        style = style,
                    )
                }
            }
        }
    }
}

/** "Row 1: …, Row 2: …": the entries a screen reader reads out. */
private fun spokenRows(rows: List<Vector3>): String =
    rows.mapIndexed { index, row ->
        "Row ${index + 1}: " + listOf(row.x, row.y, row.z).joinToString(", ") { signed(it, 4) }
    }.joinToString(". ")

/** [value] with an explicit sign, like C's `%+.<decimals>f`. */
private fun signed(value: Double, decimals: Int): String {
    val text = formatFixed(value, decimals)
    return if (text.startsWith("-")) text else "+$text"
}

private fun vector(v: Vector3): String = "(${signed(v.x, 3)}, ${signed(v.y, 3)}, ${signed(v.z, 3)})"

private fun residual(value: Double): String = "${formatFixed(value, 3)} g"

private fun microtesla(value: Double?): String =
    if (value == null) AppFormat.MISSING else "${formatFixed(value, 1)} µT"

private val CompassQuality.title: String
    get() = when (this) {
        CompassQuality.UNAVAILABLE -> "Unavailable"
        CompassQuality.UNCALIBRATED -> "Uncalibrated"
        CompassQuality.LOW -> "Low"
        CompassQuality.MEDIUM -> "Medium"
        CompassQuality.HIGH -> "High"
    }

private val CompassReliability.text: String
    get() = when (this) {
        CompassReliability.RELIABLE -> "Reliable"
        CompassReliability.CAUTION -> "Caution zone: degraded"
        CompassReliability.BLACKOUT -> "Blackout zone: do not trust"
    }
