package net.kuecker.lodestar.kit.interchange

import net.kuecker.lodestar.kit.catalog.CatalogFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reading and writing observing lists. The fixtures are in `fixtures/interchange/` (see the README there): the header and
 * the first row of `telescopius_export.csv` are quoted from a real Telescopius export, `skysafari_list.skylist` has the
 * shape of the lists SkySafari 6/7 and RASC publish.
 */
class ObservingListCodecTests {
    private val tolerance = 1e-6

    private fun decode(fixture: String): ObservingListImport =
        ObservingListCodec.decode(InterchangeTestSupport.fixture(fixture))

    private fun error(line: Int, reason: ObservingListRowError.Reason) = ObservingListRowError(line, reason)

    // MARK: Telescopius

    @Test
    fun testTelescopiusExportIsRecognisedAndReadByColumnName() {
        val list = decode("telescopius_export.csv")
        assertEquals(ObservingListFormat.TELESCOPIUS_CSV, list.format)
        assertTrue(list.errors.isEmpty())
        assertFalse(list.isTruncated)
        assertEquals(4, list.targets.size)

        val pleiades = list.targets[0]
        assertEquals("M 45", pleiades.designation)
        assertEquals("Pleiades", pleiades.name)
        assertEquals(listOf("CL MELOTTE 22", "H 0346+24", "OCL 421.0"), pleiades.alternates)
        assertEquals(2, pleiades.line)
        // 03h 46' 24" and 24º 06' 50" (the J2000 columns, not the jNow ones), arcseconds written as two quotation marks.
        assertEquals((3 + 46.0 / 60 + 24.0 / 3600) * 15, assertNotNull(pleiades.rightAscension), tolerance)
        assertEquals(24 + 6.0 / 60 + 50.0 / 3600, assertNotNull(pleiades.declination), tolerance)

        val andromeda = list.targets[1]
        assertEquals("M 31", andromeda.designation)
        assertEquals((42.0 / 60 + 44.0 / 3600) * 15, assertNotNull(andromeda.rightAscension), tolerance)
        assertEquals(41 + 16.0 / 60 + 9.0 / 3600, assertNotNull(andromeda.declination), tolerance)
        assertEquals(listOf("NGC 224", "UGC 454"), andromeda.alternates)

        // No familiar name: the label of the list entry names it; a negative declination reads with its sign.
        val veil = list.targets[2]
        assertEquals("NGC 7000", veil.designation)
        assertEquals("North America Nebula", veil.name)
        val barnard = list.targets[3]
        assertEquals("IC 434", barnard.designation)
        assertNull(barnard.name)
        assertEquals(-(2 + 28.0 / 60), assertNotNull(barnard.declination), tolerance)
    }

    @Test
    fun testTelescopiusLabelNamesAnEntryWithoutFamiliarName() {
        val text = "Catalogue Entry,Familiar Name,Right Ascension (j2000),Declination (j2000),Label\nNGC 6960,,20h 45' 42\",30º 43' 00\",Veil West\n"
        val target = assertNotNull(ObservingListCodec.decode(text).targets.firstOrNull())
        assertEquals("Veil West", target.name)
        assertEquals("NGC 6960", target.designation)
    }

    @Test
    fun testOlderTelescopiusExportsWithoutEpochColumnsAreRead() {
        val list = decode("telescopius_legacy.csv")
        assertEquals(ObservingListFormat.TELESCOPIUS_CSV, list.format)
        assertEquals(listOf<String?>("M 45", "M 42"), list.targets.map { it.designation })
        assertEquals((5 + 35.0 / 60 + 17.0 / 3600) * 15, assertNotNull(list.targets[1].rightAscension), tolerance)
        assertEquals(-(5 + 23.0 / 60 + 28.0 / 3600), assertNotNull(list.targets[1].declination), tolerance)
    }

    @Test
    fun testJNowColumnsAreNeverUsed() {
        val text = "Catalogue Entry,Right Ascension (jNow),Declination (jNow)\nM 31,00h 43' 46\",41º 22' 11\"\n"
        val target = assertNotNull(ObservingListCodec.decode(text).targets.firstOrNull())
        assertEquals("M 31", target.designation)
        assertFalse(target.hasCoordinates)
    }

    // MARK: Generic CSV

    @Test
    fun testGenericCSVWithDegrees() {
        val list = decode("generic_degrees.csv")
        assertEquals(ObservingListFormat.GENERIC_CSV, list.format)
        assertEquals(5, list.targets.size)
        assertTrue(list.errors.isEmpty())
        assertEquals("Andromeda Galaxy", list.targets[0].name)
        assertEquals("M 31", list.targets[0].designation)
        assertEquals(10.6847, assertNotNull(list.targets[0].rightAscension), tolerance)
        assertEquals(-5.3911, assertNotNull(list.targets[1].declination), tolerance)
        assertEquals("Flame, Horsehead region", list.targets[2].name)
        assertNull(list.targets[2].designation)
        assertEquals(12.25, assertNotNull(list.targets[3].declination), tolerance)
        assertEquals("Moon", list.targets[4].name)
        assertFalse(list.targets[4].hasCoordinates)
        assertEquals(listOf(2, 3, 4, 5, 6), list.targets.map { it.line })
    }

    @Test
    fun testGenericCSVWithSexagesimalAndSemicolons() {
        val list = decode("generic_sexagesimal.csv")
        assertEquals(ObservingListFormat.GENERIC_CSV, list.format)
        assertEquals(listOf<String?>("M 13", "NGC 6992", "M 27", "Bad Dec", "Bad RA", "Half"), list.targets.map { it.designation })
        assertEquals((16 + 41.0 / 60 + 41.6 / 3600) * 15, assertNotNull(list.targets[0].rightAscension), tolerance)
        assertEquals(36 + 27.0 / 60 + 37.0 / 3600, assertNotNull(list.targets[0].declination), tolerance)
        assertEquals((20 + 56.0 / 60 + 20.0 / 3600) * 15, assertNotNull(list.targets[1].rightAscension), tolerance)
        assertEquals(22 + 43.0 / 60 + 16.0 / 3600, assertNotNull(list.targets[2].declination), tolerance)
        // Rows with a bad or half coordinate stay in the list without coordinates, and are reported with their line.
        assertEquals(
            listOf(
                error(5, ObservingListRowError.Reason.INVALID_DECLINATION),
                error(6, ObservingListRowError.Reason.INVALID_RIGHT_ASCENSION),
                error(7, ObservingListRowError.Reason.INCOMPLETE_COORDINATES),
            ),
            list.errors,
        )
        assertTrue(list.targets.drop(3).all { !it.hasCoordinates })
    }

    @Test
    fun testAPlainRightAscensionIsDegreesUnlessTheHeaderSaysHours() {
        val degrees = ObservingListCodec.decode("name,ra,dec\nA,83.8,-5.4\nB,360,0\n")
        assertEquals(83.8, assertNotNull(degrees.targets[0].rightAscension), tolerance)
        assertEquals(listOf(error(3, ObservingListRowError.Reason.INVALID_RIGHT_ASCENSION)), degrees.errors)
        val hours = ObservingListCodec.decode("name,RA (hours),Dec\nA,5.588,-5.4\nB,24,0\n")
        assertEquals(5.588 * 15, assertNotNull(hours.targets[0].rightAscension), tolerance)
        assertEquals(listOf(error(3, ObservingListRowError.Reason.INVALID_RIGHT_ASCENSION)), hours.errors)
        val marked = ObservingListCodec.decode("name,Right Ascension,Declination\nA,83.8°,-5.4\nB,5h 35m,-5d 23m\n")
        assertEquals(83.8, assertNotNull(marked.targets[0].rightAscension), tolerance)
        assertEquals((5 + 35.0 / 60) * 15, assertNotNull(marked.targets[1].rightAscension), tolerance)
        assertEquals(-(5 + 23.0 / 60), assertNotNull(marked.targets[1].declination), tolerance)
    }

    @Test
    fun testHeaderNamesAreMatchedWithoutCaseAndPunctuation() {
        val list = ObservingListCodec.decode("TARGET NAME,RA_J2000,DEC-J2000\nM 1,83.63,22.01\n")
        assertEquals("M 1", list.targets.firstOrNull()?.name)
        assertEquals(83.63, assertNotNull(list.targets.firstOrNull()?.rightAscension), tolerance)
        val onlyCoordinates = ObservingListCodec.decode("ra,dec\n10.5,20.5\n")
        assertNull(onlyCoordinates.targets.firstOrNull()?.name)
        assertTrue(onlyCoordinates.targets.firstOrNull()?.hasCoordinates == true)
    }

    @Test
    fun testGuardedSpreadsheetTextIsReadBack() {
        val list = ObservingListCodec.decode("name\n'=SUM(A1)\n'plain\n")
        assertEquals(listOf<String?>("=SUM(A1)", "'plain"), list.targets.map { it.name })
    }

    @Test
    fun testRowsWithoutAnythingToIdentifyThemAreReported() {
        val list = ObservingListCodec.decode("name,type\nM 31,Galaxy\n,Galaxy\n,\n")
        assertEquals(listOf<String?>("M 31"), list.targets.map { it.name })
        assertEquals(listOf(error(3, ObservingListRowError.Reason.MISSING_NAME)), list.errors)
    }

    @Test
    fun testTheRowLimitIsEnforced() {
        val text = StringBuilder("name\n")
        for (index in 0 until ObservingListCodec.maximumTargets + 3) text.append("T$index\n")
        val list = ObservingListCodec.decode(text.toString())
        assertEquals(ObservingListCodec.maximumTargets, list.targets.size)
        assertTrue(list.isTruncated)
        assertEquals("T${ObservingListCodec.maximumTargets - 1}", list.targets.lastOrNull()?.name)
    }

    @Test
    fun testLongNamesAreCut() {
        val list = ObservingListCodec.decode("name\n" + "x".repeat(500) + "\n")
        assertEquals(ObservingListCodec.maximumNameScalars, list.targets.firstOrNull()?.name?.length)
    }

    // MARK: SkySafari

    @Test
    fun testSkySafariListIsRead() {
        val list = decode("skysafari_list.skylist")
        assertEquals(ObservingListFormat.SKY_SAFARI, list.format)
        assertEquals(listOf("M 35", "IC 443", "M 44", "NGC 6946", null, "NGC 7000"), list.targets.map { it.designation })
        assertEquals(listOf(null, null, "Beehive Cluster", "Fireworks Galaxy", "Jupiter", null), list.targets.map { it.name })
        assertEquals(listOf("NGC 2168"), list.targets[0].alternates)
        assertEquals(listOf("NGC 2632", "Praesepe"), list.targets[2].alternates)
        assertEquals(listOf("C 12", "UGC 11597"), list.targets[3].alternates)
        assertEquals(listOf(3, 9, 15, 23, 31, 40), list.targets.map { it.line })
        assertTrue(list.targets.all { !it.hasCoordinates })
        // An object without a name, and an object that is never closed.
        assertEquals(
            listOf(
                error(36, ObservingListRowError.Reason.MISSING_NAME),
                error(40, ObservingListRowError.Reason.UNTERMINATED_OBJECT),
            ),
            list.errors,
        )
    }

    @Test
    fun testSkySafariHeaderIsFoundWithBlankLinesByteOrderMarkAndOtherLineBreaks() {
        val text = "﻿\r\n  skysafariobservinglistversion=3.0\r\nSkyObject=BeginObject\rCatalogNumber = M 1\rEndObject=SkyObject\r"
        val list = ObservingListCodec.decode(text)
        assertEquals(ObservingListFormat.SKY_SAFARI, list.format)
        assertEquals(listOf<String?>("M 1"), list.targets.map { it.designation })
    }

    // MARK: Writing

    private val entries = listOf(
        ObservingListEntry(name = "Andromeda Galaxy", designation = "M 31", rightAscension = 10.6847, declination = 41.2687, typeName = "Galaxy"),
        ObservingListEntry(name = "Jupiter", typeName = "Planet"),
        ObservingListEntry(name = "=HYPERLINK(\"x\")", rightAscension = 400.0, declination = 5.0),
        ObservingListEntry(name = "Flame, Horsehead", rightAscension = 85.25, declination = -2.45),
    )

    @Test
    fun testCSVExport() {
        val expected = listOf(
            "Name,Designation,RA J2000 (deg),Dec J2000 (deg),Type",
            "Andromeda Galaxy,M 31,10.68470,41.26870,Galaxy",
            "Jupiter,,,,Planet",
            "\"'=HYPERLINK(\"\"x\"\")\",,,,",
            "\"Flame, Horsehead\",,85.25000,-2.45000,",
        )
        assertEquals(expected.joinToString("\r\n") + "\r\n", ObservingListCodec.encodeCSV(entries))
    }

    @Test
    fun testCSVExportReadsBackThroughTheImporter() {
        val list = ObservingListCodec.decode(ObservingListCodec.encodeCSV(entries))
        assertEquals(ObservingListFormat.GENERIC_CSV, list.format)
        assertTrue(list.errors.isEmpty())
        assertEquals(listOf<String?>("Andromeda Galaxy", "Jupiter", "=HYPERLINK(\"x\")", "Flame, Horsehead"), list.targets.map { it.name })
        assertEquals("M 31", list.targets[0].designation)
        assertEquals(10.6847, assertNotNull(list.targets[0].rightAscension), tolerance)
        assertEquals(-2.45, assertNotNull(list.targets[3].declination), tolerance)
        assertFalse(list.targets[1].hasCoordinates)
        assertFalse(list.targets[2].hasCoordinates)
    }

    @Test
    fun testSkySafariExport() {
        val expected = listOf(
            "SkySafariObservingListVersion=3.0",
            "SortedBy=Default Order",
            "SkyObject=BeginObject",
            "\tObjectID=2,-1,-1",
            "\tCatalogNumber=M 31",
            "\tDefaultIndex=0",
            "EndObject=SkyObject",
            "SkyObject=BeginObject",
            "\tObjectID=2,-1,-1",
            "\tCommonName=Jupiter",
            "\tDefaultIndex=1",
            "EndObject=SkyObject",
        )
        assertEquals(expected.joinToString("\n") + "\n", ObservingListCodec.encodeSkySafari(entries.take(2)))
    }

    @Test
    fun testSkySafariExportReadsBackThroughTheImporter() {
        val list = ObservingListCodec.decode(ObservingListCodec.encodeSkySafari(entries))
        assertEquals(ObservingListFormat.SKY_SAFARI, list.format)
        assertEquals(listOf("M 31", null, null, null), list.targets.map { it.designation })
        assertEquals(listOf(null, "Jupiter", "=HYPERLINK(\"x\")", "Flame, Horsehead"), list.targets.map { it.name })
        assertTrue(list.errors.isEmpty())
    }

    @Test
    fun testExportsAreCappedAndKeepLinesApart() {
        val many = (0 until ObservingListCodec.maximumTargets + 5).map { ObservingListEntry(name = "T$it") }
        assertEquals(ObservingListCodec.maximumTargets + 1, ObservingListCodec.encodeCSV(many).split("\r\n").filter { it.isNotEmpty() }.size)
        val multiline = ObservingListEntry(name = "Two\nlines\r\nhere", designation = "A\tB")
        val text = ObservingListCodec.encodeSkySafari(listOf(multiline))
        assertTrue(text.contains("\tCatalogNumber=A B\n"))
        assertEquals(7, text.split("\n").filter { it.isNotEmpty() }.size)
    }

    // MARK: Malformed input

    @Test
    fun testMalformedInputGivesErrorsNotCrashes() {
        assertFailsWith<InterchangeError.Empty> { ObservingListCodec.decode("") }
        assertFailsWith<InterchangeError.Empty> { ObservingListCodec.decode(" \r\n\t\n") }
        assertFailsWith<InterchangeError.MissingColumns> { ObservingListCodec.decode("foo,bar\n1,2\n") }
        assertFailsWith<InterchangeError.MissingColumns> { ObservingListCodec.decode("{\"json\": true}") }
        assertEquals(InterchangeError.UnterminatedQuote(2), assertFailsWith<InterchangeError.UnterminatedQuote> { ObservingListCodec.decode("name\n\"open") })
        val huge = "a".repeat(ObservingListCodec.maximumTextScalars + 1)
        assertEquals(
            InterchangeError.TooLarge(ObservingListCodec.maximumTextScalars),
            assertFailsWith<InterchangeError.TooLarge> { ObservingListCodec.decode(huge) },
        )
    }

    @Test
    fun testBinaryAndOddInputNeverTraps() {
        val odd = listOf(
            "\u0000\u0001\u0002", "name\n\u0000￿\n", "name,ra,dec\nx,￾,🌟\n", "SkySafariObservingListVersion",
            "=,=,=\n=,=,=", "ra,dec\n1e400,nan\n", "name,ra,dec\nA,+,-\n", "name\n" + "\"".repeat(1001),
        )
        for (text in odd) {
            try {
                ObservingListCodec.decode(text)
            } catch (_: InterchangeError) {
                // A typed error is the expected answer for such input.
            }
        }
        val list = ObservingListCodec.decode("name,ra,dec\nA,1e400,nan\nB,+,-\n")
        assertEquals(2, list.targets.size)
        assertEquals(4, list.errors.size)
    }

    // MARK: Matching

    @Test
    fun testRowsAreMatchedToCatalogObjectsByDesignationAndName() {
        val catalog = CatalogFixture.catalog()
        val targets = listOf(
            ImportedTarget(name = "Andromeda Galaxy", designation = "m031", line = 2),
            ImportedTarget(name = null, designation = "NGC 7000", line = 3),
            ImportedTarget(name = "Orion Nebula", designation = "Not a catalogue number", line = 4),
            ImportedTarget(name = "zzz unknown", designation = null, alternates = listOf("qqq nothing"), line = 5),
            ImportedTarget(name = "Pleiades", designation = "M 45", alternates = listOf("OCL 421.0"), line = 6),
        )
        val matches = ObservingListMatcher.match(targets, catalog)
        assertEquals(targets, matches.map { it.target })
        assertEquals("dso.M31", matches[0].obj?.id)
        assertEquals("dso.NGC7000", matches[1].obj?.id)
        assertEquals("dso.M42", matches[2].obj?.id)
        assertNull(matches[3].obj)
        assertEquals("dso.M45", matches[4].obj?.id)
    }

    @Test
    fun testPartialNamesDoNotMatch() {
        val catalog = CatalogFixture.catalog()
        assertNull(ObservingListMatcher.match(ImportedTarget(name = "Androm", designation = null, line = 1), catalog))
        assertNotEquals("dso.M31", ObservingListMatcher.match(ImportedTarget(name = "M 3", designation = null, line = 1), catalog)?.id)
    }

    @Test
    fun testTelescopiusFixtureMatchesTheBundledCatalog() {
        val catalog = CatalogFixture.catalog()
        val list = decode("telescopius_export.csv")
        val matches = ObservingListMatcher.match(list.targets, catalog)
        assertEquals("dso.M45", matches[0].obj?.id)
        assertEquals("dso.M31", matches[1].obj?.id)
        assertEquals("dso.NGC7000", matches[2].obj?.id)
    }
}
