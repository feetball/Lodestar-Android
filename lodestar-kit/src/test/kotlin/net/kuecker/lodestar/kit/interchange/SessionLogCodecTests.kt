package net.kuecker.lodestar.kit.interchange

import net.kuecker.lodestar.kit.foundation.instantFromTimeIntervalSince1970
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The session log entry and its CSV and OpenAstronomyLog writers. `fixtures/interchange/session_log_oal.xml` was written by
 * hand and validates against the OpenAstronomyLog 2.1 schema (https://github.com/openastronomylog/openastronomylog,
 * `src/oal21.xsd`, checked with libxml2).
 */
class SessionLogCodecTests {
    /** 2026-11-19T03:15:30Z, 20:15:30 on 2026-11-18 at UTC-7. */
    private val first = instantFromTimeIntervalSince1970(1_795_058_130.0)

    private fun log(): List<SessionLogEntry> = listOf(
        SessionLogEntry(
            id = "id-1", date = first, targetID = "dso.M31", targetName = "Andromeda Galaxy",
            rightAscension = 10.6847, declination = 41.2687,
            siteName = "Dark Site", siteLatitude = 40.015, siteLongitude = -105.27, siteUTCOffsetMinutes = -420,
            camera = "Full Frame 36 × 24 mm", focalLength = 135.0, lensAperture = 2.8, sensorPixelsX = 6000, sensorPixelsY = 4000,
            exposureSeconds = 120.0, iso = 800, frameCount = 30, filter = "L-eXtreme",
            notes = "Good seeing, <clear> & dark \"sky\"", isImaged = true,
        ),
        SessionLogEntry(
            id = "id-2", date = first.plusSeconds(7_200), targetID = "dso.M42", targetName = "Orion Nebula",
            rightAscension = 83.8221, declination = -5.3911,
            siteName = "Dark Site", siteLatitude = 40.015, siteLongitude = -105.27, siteUTCOffsetMinutes = -420,
            camera = "Full Frame 36 × 24 mm", focalLength = 135.0, lensAperture = 2.8, sensorPixelsX = 6000, sensorPixelsY = 4000,
            exposureSeconds = 60.0, iso = 1600, frameCount = 10, isImaged = false,
        ),
        SessionLogEntry(
            id = "id-3", date = first.plusSeconds(86_400), targetID = "dso.M31", targetName = "Andromeda Galaxy",
            rightAscension = 10.6847, declination = 41.2687, siteName = "Backyard", isImaged = true,
        ),
    )

    // MARK: Entries

    @Test
    fun testEntryValuesAreCleanedAndBounded() {
        val entry = SessionLogEntry(
            id = "  ", date = first, targetID = " dso.M31 ", targetName = "  \n ",
            rightAscension = 370.0, declination = 95.0, siteName = "A\tB\nC",
            siteLatitude = 91.0, siteLongitude = 10.0, siteUTCOffsetMinutes = 900,
            camera = "c".repeat(300), focalLength = Double.NaN, lensAperture = 500.0,
            sensorPixelsX = 6000, sensorPixelsY = null,
            exposureSeconds = Double.POSITIVE_INFINITY, iso = -5, frameCount = 10_000_000,
            filter = "x", notes = "n".repeat(5_000), isImaged = true,
        )
        UUID.fromString(entry.id)
        assertEquals("dso.M31", entry.targetID)
        assertEquals(SessionLogEntry.defaultTargetName, entry.targetName)
        assertEquals(10.0, assertNotNull(entry.rightAscension), 1e-9)
        assertEquals(90.0, entry.declination)
        assertEquals("A B C", entry.siteName)
        assertNull(entry.siteLatitude)
        assertNull(entry.siteLongitude)
        assertNull(entry.siteUTCOffsetMinutes)
        assertEquals(SessionLogEntry.maximumLabelLength, entry.camera.length)
        assertNull(entry.focalLength)
        assertNull(entry.lensAperture)
        assertNull(entry.sensorPixelsX)
        assertNull(entry.sensorPixelsY)
        assertEquals(0.0, entry.exposureSeconds)
        assertEquals(0, entry.iso)
        assertEquals(SessionLogEntry.maximumFrameCount, entry.frameCount)
        assertEquals(SessionLogEntry.maximumNotesLength, entry.notes.length)
    }

    @Test
    fun testCoordinatesCountOnlyAsAPair() {
        assertFalse(SessionLogEntry(date = first, targetID = "x", targetName = "x", rightAscension = 10.0).hasCoordinates)
        assertFalse(SessionLogEntry(date = first, targetID = "x", targetName = "x", rightAscension = Double.NaN, declination = 5.0).hasCoordinates)
        assertTrue(SessionLogEntry(date = first, targetID = "x", targetName = "x", rightAscension = 10.0, declination = 5.0).hasCoordinates)
    }

    @Test
    fun testTotalExposureAndNights() {
        val entries = log()
        assertEquals(3_600.0, entries[0].totalExposureSeconds)
        assertEquals(20_775L, entries[0].night)
        assertEquals(20_775L, entries[1].night)
        assertEquals("2026-11-18", SessionLogCodec.nightText(entries[0].night))
        // Without a site offset the night follows UTC: 03:15 UTC belongs to the evening of the 19th, UTC-12h.
        assertEquals("2026-11-19", SessionLogCodec.nightText(entries[2].night))
    }

    @Test
    fun testNightsChangeAtNoonLocalTime() {
        // 2026-11-18T09:59:59Z and 10:00:00Z: 11:59:59 and 12:00:00 at UTC+2.
        val beforeNoon = instantFromTimeIntervalSince1970(1_794_995_999.0)
        val atNoon = beforeNoon.plusSeconds(1)
        val day = SessionLogCodec.night(beforeNoon, 120)
        assertEquals(day + 1, SessionLogCodec.night(atNoon, 120))
        assertEquals(day, SessionLogCodec.night(beforeNoon, 0))
        assertEquals(day, SessionLogCodec.night(atNoon, 0))
        assertEquals(day, SessionLogCodec.night(atNoon, -60))
    }

    // MARK: CSV

    @Test
    fun testCSVExportInDateOrder() {
        val expected = listOf(
            "Date (UTC),Night,Target,Target ID,RA J2000 (deg),Dec J2000 (deg),Site,Latitude,Longitude,Camera,Focal length (mm),Aperture (f/),Exposure (s),ISO,Frames,Total exposure (s),Filter,Imaged,Notes",
            "2026-11-19T03:15:30Z,2026-11-18,Andromeda Galaxy,dso.M31,10.68470,41.26870,Dark Site,40.015,-105.27,Full Frame 36 × 24 mm,135,2.8,120,800,30,3600,L-eXtreme,yes,\"Good seeing, <clear> & dark \"\"sky\"\"\"",
            "2026-11-19T05:15:30Z,2026-11-18,Orion Nebula,dso.M42,83.82210,-5.39110,Dark Site,40.015,-105.27,Full Frame 36 × 24 mm,135,2.8,60,1600,10,600,,no,",
            "2026-11-20T03:15:30Z,2026-11-19,Andromeda Galaxy,dso.M31,10.68470,41.26870,Backyard,,,,,,0,,0,0,,yes,",
        )
        assertEquals(expected.joinToString("\r\n") + "\r\n", SessionLogCodec.encodeCSV(log().reversed()))
    }

    @Test
    fun testCSVGuardsFormulasAndKeepsMultilineNotes() {
        val entry = SessionLogEntry(date = first, targetID = "custom.1", targetName = "=cmd|' /C calc'!A0", notes = "line 1\nline 2", isImaged = true)
        val table = InterchangeCSV.parse(SessionLogCodec.encodeCSV(listOf(entry)), 10)
        assertEquals(2, table.records.size)
        assertEquals("'=cmd|' /C calc'!A0", table.records[1].fields[2])
        assertEquals("line 1\nline 2", table.records[1].fields[18])
        assertEquals(19, table.records[1].fields.size)
    }

    @Test
    fun testExportsAreCapped() {
        val entries = (0 until SessionLogCodec.maximumEntries + 10).map {
            SessionLogEntry(date = instantFromTimeIntervalSince1970(it.toDouble()), targetID = "t", targetName = "T$it")
        }
        assertEquals(SessionLogCodec.maximumEntries + 1, SessionLogCodec.encodeCSV(entries).split("\r\n").filter { it.isNotEmpty() }.size)
    }

    // MARK: OpenAstronomyLog

    @Test
    fun testOpenAstronomyLogDocumentMatchesTheValidatedFixture() {
        val expected = InterchangeTestSupport.fixture("session_log_oal.xml")
        assertEquals(expected, SessionLogCodec.encodeOpenAstronomyLog(log()))
    }

    @Test
    fun testOpenAstronomyLogWithoutEntriesHasEveryContainer() {
        val text = SessionLogCodec.encodeOpenAstronomyLog(emptyList(), observer = "Ada King Lovelace")
        for (name in listOf("sites", "sessions", "targets", "scopes", "eyepieces", "lenses", "filters", "imagers")) {
            assertTrue(text.contains("<$name/>") || text.contains("<$name>"), name)
        }
        assertTrue(text.contains("<name>Ada King</name>"))
        assertTrue(text.contains("<surname>Lovelace</surname>"))
        assertFalse(text.contains("<observation "))
    }

    @Test
    fun testOpenAstronomyLogEscapesTextAndDropsInvalidCharacters() {
        val entry = SessionLogEntry(
            date = first, targetID = "custom.<1>", targetName = "A & B <\"C\">",
            siteName = "Tom's \u0007Site", siteLatitude = 10.0, siteLongitude = 20.0, notes = "x\u0001y\u000Bz",
        )
        val text = SessionLogCodec.encodeOpenAstronomyLog(listOf(entry))
        assertTrue(text.contains("<name>A &amp; B &lt;&quot;C&quot;&gt;</name>"))
        assertTrue(text.contains("<name>Tom&apos;s Site</name>"))
        assertTrue(text.contains("xyz"))
        assertFalse(text.any { it.code < 0x20 && it != '\n' })
    }

    @Test
    fun testSitesWithTheSameNameButOtherPlacesStaySeparate() {
        val a = SessionLogEntry(date = first, targetID = "t", targetName = "T", siteName = "Current Location",
            siteLatitude = 10.0, siteLongitude = 20.0, siteUTCOffsetMinutes = 60)
        val b = SessionLogEntry(date = first.plusSeconds(60), targetID = "t", targetName = "T", siteName = "Current Location",
            siteLatitude = 11.0, siteLongitude = 20.0, siteUTCOffsetMinutes = 60)
        val text = SessionLogCodec.encodeOpenAstronomyLog(listOf(a, b))
        assertTrue(text.contains("id=\"site_2\""))
        assertTrue(text.contains("id=\"session_2\""))
        assertTrue(text.contains("id=\"target_1\""))
        assertFalse(text.contains("id=\"target_2\""))
    }
}
