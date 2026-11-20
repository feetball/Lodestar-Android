package net.kuecker.lodestar.features.sessionlog

import net.kuecker.lodestar.kit.foundation.instantFromTimeIntervalSince1970
import net.kuecker.lodestar.kit.interchange.SessionLogEntry
import net.kuecker.lodestar.services.SessionLogFile
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionLogTest {
    /** 2026-11-19T03:15:30Z. */
    private val date = instantFromTimeIntervalSince1970(1_795_058_130.0)

    private fun entry(id: String, offset: Long = 0, imaged: Boolean = true) = SessionLogEntry(
        id = id, date = date.plusSeconds(offset), targetID = "dso.M31", targetName = "Andromeda Galaxy",
        rightAscension = 10.6847, declination = 41.2687, siteName = "Dark Site", siteLatitude = 40.015,
        siteLongitude = -105.27, siteUTCOffsetMinutes = -420, camera = "Full Frame 36 × 24 mm", focalLength = 135.0,
        lensAperture = 2.8, sensorPixelsX = 6000, sensorPixelsY = 4000, exposureSeconds = 120.0, iso = 800,
        frameCount = 30, filter = "L-eXtreme", notes = "line 1\nline 2 \"quoted\"", isImaged = imaged,
    )

    @Test
    fun testTheFileRoundTripsEveryField() {
        val entries = listOf(entry("b", 60), entry("a"))
        val back = SessionLogFile.decode(SessionLogFile.encode(entries), limit = 10)
        assertEquals(listOf("b", "a"), back.map { it.id })
        assertEquals(entries.first(), back.first())
        assertEquals(entries.last(), back.last())
    }

    @Test
    fun testBadEntriesAreSkippedAndRepeatsDropped() {
        val text = """{"version":1,"entries":[
            {"id":"x","time":1795058130,"targetID":"dso.M1","targetName":"Crab"},
            {"id":"x","time":1795058131,"targetID":"dso.M1","targetName":"Crab again"},
            {"id":"y","targetID":"dso.M2"},
            {"id":"z","time":1e30,"targetID":"dso.M3"},
            "text",
            {"id":"w","time":1795058132,"targetID":"dso.M4","iso":-5,"exposureSeconds":"fast"}
        ]}"""
        val read = SessionLogFile.decode(text, limit = 10)
        assertEquals(listOf("w", "x"), read.map { it.id })
        assertEquals(0, read.first().iso)
        assertEquals("Crab", read.last().targetName)
        assertTrue(SessionLogFile.decode("not json", limit = 10).isEmpty())
        assertTrue(SessionLogFile.decode("{}", limit = 10).isEmpty())
    }

    @Test
    fun testTheLimitCapsWhatIsRead() {
        val entries = (0 until 5).map { entry("e$it", it.toLong()) }
        assertEquals(3, SessionLogFile.decode(SessionLogFile.encode(entries), limit = 3).size)
    }

    @Test
    fun testWritingAndReadingAFile() {
        val directory = File.createTempFile("sessionlog", "").let { it.delete(); it.mkdirs(); it }
        try {
            val file = File(directory, "SessionLog.json")
            SessionLogFile.write(listOf(entry("a")), file)
            assertEquals(listOf("a"), SessionLogFile.read(file, 10).map { it.id })
            assertTrue(SessionLogFile.read(File(directory, "missing.json"), 10).isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun testTheDraftParsesItsFieldsAndReportsInvalidOnes() {
        val draft = SessionLogDraft.editing(entry("a"))
        assertTrue(draft.isValid)
        assertEquals(120.0, draft.exposureSeconds)
        assertEquals(800, draft.iso)
        assertEquals(30, draft.frameCount)
        assertEquals(date, draft.parsedDate)

        val empty = draft.copy(exposureText = "", isoText = " ", framesText = "")
        assertEquals(0.0, empty.exposureSeconds)
        assertEquals(0, empty.iso)
        assertEquals(0, empty.frameCount)
        assertTrue(empty.isValid)

        assertFalse(draft.copy(exposureText = "fast").isValid)
        assertFalse(draft.copy(exposureText = "100000").isValid)
        assertFalse(draft.copy(isoText = "1e3").isValid)
        assertFalse(draft.copy(framesText = "-1").isValid)
        assertNull(draft.copy(dateText = "yesterday").parsedDate)
        assertEquals(2.5, draft.copy(exposureText = "2,5").exposureSeconds)
    }

    @Test
    fun testTheDraftTextOfADateReadsBack() {
        val text = SessionLogDraft.formatDate(date)
        val draft = SessionLogDraft.editing(entry("a")).copy(date = date.plusSeconds(7), dateText = text)
        // The minute that was typed is read in the time zone of the phone and drops the seconds.
        assertEquals(text, SessionLogDraft.formatDate(draft.parsedDate ?: date))
    }

    @Test
    fun testSummaryAndTitles() {
        assertEquals("30 × 2.0 min · ISO 800 · L-eXtreme · Imaged".replace("2.0 min", "2m"), SessionLogFormat.summary(entry("a")).replace("2m 0s", "2m"))
        assertEquals("Not imaged", SessionLogFormat.summary(SessionLogEntry(date = date, targetID = "x", targetName = "x")))
        assertEquals("Night of 2026-11-18", SessionLogFormat.nightTitle(20_775))
    }
}
