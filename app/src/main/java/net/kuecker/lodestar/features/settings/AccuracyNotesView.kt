package net.kuecker.lodestar.features.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import net.kuecker.lodestar.shared.SettingsRow
import net.kuecker.lodestar.shared.SettingsSection
import net.kuecker.lodestar.theme.LocalPalette

private data class Note(val title: String, val text: String)

private val notes = listOf(
    Note(
        "Where things are",
        "The Sun, Moon and planets come from published solar-system theories (VSOP87 for the planets, a " +
            "Meeus-based lunar theory) and are good to well under an arcminute. Stars and deep-sky objects use " +
            "catalog positions with proper motion, precession, nutation, aberration and atmospheric refraction " +
            "applied. The star catalog reaches magnitude 6.5; the deep-sky catalog holds the Messier objects, " +
            "named objects and the brighter NGC and IC objects.",
    ),
    Note(
        "Where the camera points",
        "The phone's compass and gyroscope tell Lodestar where the camera points. A compass is typically good to " +
            "a few degrees and is disturbed by metal and electronics: tripod heads, camera bodies, lens motors, " +
            "even a magnetic case. Aligning on a bright star or the Moon (Aim › Align) corrects the heading and " +
            "the tilt of your bracket and shows the error that remains. Gyro Lock ignores the compass after the " +
            "first heading and drifts slowly.",
    ),
    Note(
        "True north",
        "True north comes from the World Magnetic Model 2025, valid until the end of 2029. Its declination is " +
            "typically within about half a degree. Near the magnetic poles a compass cannot be trusted at all.",
    ),
    Note(
        "Your location and the clock",
        "Sky positions depend on where you are and what time it is. An approximate location is accurate enough: " +
            "a few kilometers change a position by well under a tenth of a degree. Keep the date, time and time " +
            "zone of your phone on automatic.",
    ),
    Note(
        "Refraction",
        "The atmosphere lifts objects near the horizon. Lodestar corrects for it with the temperature and " +
            "pressure in Settings. Right at the horizon the real refraction varies with the weather by several " +
            "arcminutes.",
    ),
    Note(
        "Exposure advice",
        "Exposure limits are rules of thumb. The NPF rule takes aperture and pixel size into account; the 500 " +
            "rule is optimistic for high-resolution sensors. Check the stars in your first test frame.",
    ),
)

/** What Lodestar's numbers can and cannot be trusted for. */
@Composable
internal fun AccuracyNotesView(onBack: () -> Unit) {
    val palette = LocalPalette.current
    SettingsSubScreen("Accuracy Notes", onBack) {
        for (note in notes) {
            SettingsSection(title = note.title) {
                SettingsRow { Text(note.text, color = palette.primaryText, style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}
