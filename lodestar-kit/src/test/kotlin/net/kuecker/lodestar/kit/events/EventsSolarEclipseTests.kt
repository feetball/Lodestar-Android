package net.kuecker.lodestar.kit.events

import net.kuecker.lodestar.kit.ephemeris.*
import net.kuecker.lodestar.kit.events.EventsTestSupport.assertInstant
import net.kuecker.lodestar.kit.events.EventsTestSupport.assertNear
import net.kuecker.lodestar.kit.events.EventsTestSupport.utc
import net.kuecker.lodestar.kit.foundation.*
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Solar eclipses: the global circumstances against the NASA catalog and the local circumstances against the U.S. Naval
 * Observatory.
 *
 * - NASA: F. Espenak, *Catalog of Solar Eclipses: 2001 to 2100* (https://eclipse.gsfc.nasa.gov/SEcat5/SE2001-2100.html):
 *   greatest eclipse in TD with the catalog's ΔT (73–77 s, so the UT is TD − ΔT), gamma, magnitude and kind.
 * - USNO: the Solar Eclipse Computer API (https://aa.usno.navy.mil/api/eclipses/solar/date?date=…&coords=lat,lon&height=m),
 *   queried 2026-10-18: contact times in UT, magnitude (fraction of the Sun's diameter covered), obscuration, Sun
 *   altitude and azimuth at maximum. USNO assumes ΔT of 72.5–74 s, the kit about 69 s, so the kit's times differ
 *   by up to about 15 s (observed); tests allow 45 s, the brief ±1 minute.
 * - Luxor 2027-08-02, which the USNO service does not cover: topocentric apparent positions of the Sun and Moon from
 *   JPL Horizons (DE441, observer table at SITE_COORD 32.6396,25.6872,0.075; quantities 2 and 20), contacts from the
 *   separation against the apparent radii (Sun 695 700 km, Moon 1 737.4 km) at 20 s steps. The same method reproduces
 *   the USNO times for Dallas and Burgos to 6 s. Published minute-level times (partial begins 11:40, totality 13:01 to
 *   13:08, ends 14:26 EEST, UTC+3) agree.
 */
class EventsSolarEclipseTests {
    private class Catalog(
        val date: String,
        val kind: SolarEclipseKind,
        val greatest: String,
        val gamma: Double,
        val magnitude: Double,
    )

    private val catalog = listOf(
        Catalog("2023-04-20", SolarEclipseKind.HYBRID, "2023-04-20T04:16:43Z", -0.3952, 1.0132),
        Catalog("2023-10-14", SolarEclipseKind.ANNULAR, "2023-10-14T17:59:27Z", 0.3753, 0.9520),
        Catalog("2024-04-08", SolarEclipseKind.TOTAL, "2024-04-08T18:17:15Z", 0.3431, 1.0566),
        Catalog("2024-10-02", SolarEclipseKind.ANNULAR, "2024-10-02T18:44:59Z", -0.3509, 0.9326),
        Catalog("2025-03-29", SolarEclipseKind.PARTIAL, "2025-03-29T10:47:21Z", 1.0405, 0.9376),
        Catalog("2025-09-21", SolarEclipseKind.PARTIAL, "2025-09-21T19:41:49Z", -1.0651, 0.8550),
        Catalog("2026-02-17", SolarEclipseKind.ANNULAR, "2026-02-17T12:11:51Z", -0.9743, 0.9630),
        Catalog("2026-08-12", SolarEclipseKind.TOTAL, "2026-08-12T17:45:51Z", 0.8977, 1.0386),
        Catalog("2027-02-06", SolarEclipseKind.ANNULAR, "2027-02-06T15:59:32Z", -0.2952, 0.9281),
        Catalog("2027-08-02", SolarEclipseKind.TOTAL, "2027-08-02T10:06:34Z", 0.1421, 1.0790),
        Catalog("2028-01-26", SolarEclipseKind.ANNULAR, "2028-01-26T15:07:42Z", 0.3901, 0.9208),
        Catalog("2028-07-22", SolarEclipseKind.TOTAL, "2028-07-22T02:55:23Z", -0.6056, 1.0560),
    )

    private fun eclipses(from: String, to: String): List<SolarEclipse> =
        EventsTestSupport.solarEclipses(SolarEclipseFinder.events(utc(from), utc(to))).map { it.second }

    @Test
    fun testGlobalCircumstancesAgainstTheNasaCatalog() {
        for (entry in catalog) {
            val found = eclipses("${entry.date}T00:00:00Z", "${entry.date}T23:59:59Z")
            assertEquals(1, found.size, entry.date)
            val eclipse = found[0]
            assertEquals(entry.kind, eclipse.kind, entry.date)
            assertInstant(entry.greatest, eclipse.greatest, 45.0, "${entry.date} greatest eclipse")
            assertNear(entry.gamma, eclipse.gamma, 0.003, "${entry.date} gamma")
            // Central eclipses: ratio of the diameters (1 mm in a thousand: the kit's Moon and Sun radii differ a little
            // from the catalog's). Partial eclipses: the Earth is a sphere here, so allow 0.007.
            val tolerance = if (entry.kind == SolarEclipseKind.PARTIAL) 0.007 else 0.003
            assertNear(entry.magnitude, eclipse.greatestMagnitude, tolerance, "${entry.date} magnitude")
            assertTrue(eclipse.local == null, "no observer, no local circumstances")
        }
    }

    @Test
    fun testKindsAgainstTheNasaCatalog2029To2045() {
        // Columns of the catalog: date, greatest eclipse (TD), ΔT (s), type (P partial, A annular, T total, H hybrid;
        // a trailing + or - marks a non-central total or annular eclipse, "s" an annular eclipse whose antumbra just
        // reaches the Earth), gamma, magnitude.
        val rows = """
            2029 Jan 14 | 17:13:48 | 77 | P | 1.0553 | 0.8714
            2029 Jun 12 | 04:06:13 | 77 | P | 1.2943 | 0.4576
            2029 Jul 11 | 15:37:19 | 77 | P | -1.4191 | 0.2303
            2029 Dec 05 | 15:03:58 | 77 | P | -1.0609 | 0.8911
            2030 Jun 01 | 06:29:13 | 78 | A | 0.5626 | 0.9443
            2030 Nov 25 | 06:51:37 | 78 | T | -0.3867 | 1.0468
            2031 May 21 | 07:16:04 | 78 | A | -0.1970 | 0.9589
            2031 Nov 14 | 21:07:31 | 79 | H | 0.3078 | 1.0106
            2032 May 09 | 13:26:42 | 79 | A | -0.9375 | 0.9957
            2032 Nov 03 | 05:34:13 | 79 | P | 1.0643 | 0.8554
            2033 Mar 30 | 18:02:36 | 80 | T | 0.9778 | 1.0462
            2033 Sep 23 | 13:54:31 | 80 | P | -1.1583 | 0.6890
            2034 Mar 20 | 10:18:45 | 80 | T | 0.2894 | 1.0458
            2034 Sep 12 | 16:19:28 | 81 | A | -0.3936 | 0.9736
            2035 Mar 09 | 23:05:54 | 81 | A | -0.4368 | 0.9919
            2035 Sep 02 | 01:56:46 | 81 | T | 0.3727 | 1.0320
            2036 Feb 27 | 04:46:49 | 82 | P | -1.1942 | 0.6286
            2036 Jul 23 | 10:32:06 | 82 | P | -1.4250 | 0.1991
            2036 Aug 21 | 17:25:45 | 82 | P | 1.0825 | 0.8622
            2037 Jan 16 | 09:48:55 | 82 | P | 1.1477 | 0.7049
            2037 Jul 13 | 02:40:36 | 83 | T | -0.7246 | 1.0413
            2038 Jan 05 | 13:47:11 | 83 | A | 0.4169 | 0.9728
            2038 Jul 02 | 13:32:55 | 84 | A | 0.0398 | 0.9911
            2038 Dec 26 | 01:00:10 | 84 | T | -0.2881 | 1.0268
            2039 Jun 21 | 17:12:54 | 84 | A | 0.8312 | 0.9454
            2039 Dec 15 | 16:23:46 | 85 | T | -0.9458 | 1.0356
            2040 May 11 | 03:43:02 | 85 | P | -1.2529 | 0.5306
            2040 Nov 04 | 19:09:02 | 85 | P | 1.0993 | 0.8074
            2041 Apr 30 | 11:52:21 | 86 | T | -0.4492 | 1.0189
            2041 Oct 25 | 01:36:22 | 86 | A | 0.4133 | 0.9467
            2042 Apr 20 | 02:17:30 | 86 | T | 0.2956 | 1.0614
            2042 Oct 14 | 02:00:42 | 87 | A | -0.3030 | 0.9300
            2043 Apr 09 | 18:57:49 | 87 | T+ | 1.0031 | 1.0095
            2043 Oct 03 | 03:01:49 | 88 | A- | -1.0102 | 0.9497
            2044 Feb 28 | 20:24:39 | 88 | As | -0.9954 | 0.9600
            2044 Aug 23 | 01:17:02 | 88 | T | 0.9613 | 1.0364
            2045 Feb 16 | 23:56:07 | 89 | A | -0.3125 | 0.9285
            2045 Aug 12 | 17:42:39 | 89 | T | 0.2116 | 1.0774
""".trimIndent().lines()
        val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        assertEquals(38, rows.size)
        for (row in rows) {
            val fields = row.split("|").map { it.trim() }
            val date = fields[0].split(" ")
            val iso = "%s-%02d-%sT%sZ".format(date[0], months.indexOf(date[1]) + 1, date[2], fields[1])
            val greatest = utc(iso).minusMillis((fields[2].toDouble() * 1000).toLong())
            val found = SolarEclipseFinder.events(greatest.minusSeconds(3 * 86_400), greatest.plusSeconds(3 * 86_400))
            assertEquals(1, found.size, row)
            val eclipse = EventsTestSupport.solarEclipses(found)[0].second
            val expectedKind = when (fields[3].first()) {
                'P' -> SolarEclipseKind.PARTIAL
                'A' -> SolarEclipseKind.ANNULAR
                'H' -> SolarEclipseKind.HYBRID
                else -> SolarEclipseKind.TOTAL
            }
            assertEquals(expectedKind, eclipse.kind, row)
            assertTrue(abs(EventsTestSupport.seconds(eclipse.greatest, greatest)) < 45, "$row greatest")
            assertNear(fields[4].toDouble(), eclipse.gamma, 0.003, "$row gamma")
            assertNear(fields[5].toDouble(), eclipse.greatestMagnitude, if (expectedKind == SolarEclipseKind.PARTIAL) 0.007 else 0.005, "$row magnitude")
            // The global flag: the axis of the shadow touches the Earth unless the catalog marks the eclipse non-central.
            assertEquals(expectedKind != SolarEclipseKind.PARTIAL && !fields[3].endsWith("+") && !fields[3].endsWith("-"), eclipse.isCentral, row)
        }
    }

    @Test
    fun testCentralFlagMarksEclipsesThatReachTheGround() {
        for (entry in catalog) {
            val eclipse = eclipses("${entry.date}T00:00:00Z", "${entry.date}T23:59:59Z")[0]
            assertEquals(entry.kind != SolarEclipseKind.PARTIAL, eclipse.isCentral, entry.date)
        }
    }

    @Test
    fun testEveryEclipseOf2025To2028IsFound() {
        // NASA catalog: 2025 two partial eclipses, 2026 two, 2027 two, 2028 two (annular January, total July).
        assertEquals(2, eclipses("2025-01-01T00:00:00Z", "2025-12-31T23:59:59Z").size)
        assertEquals(2, eclipses("2026-01-01T00:00:00Z", "2026-12-31T23:59:59Z").size)
        assertEquals(2, eclipses("2027-01-01T00:00:00Z", "2027-12-31T23:59:59Z").size)
        assertEquals(2, eclipses("2028-01-01T00:00:00Z", "2028-12-31T23:59:59Z").size)
    }

    private fun local(date: String, site: EventsTestSupport.Site): LocalSolarCircumstances {
        val day = utc("${date}T12:00:00Z")
        val events = SolarEclipseFinder.events(day.minusSeconds(86_400), day.plusSeconds(86_400), site.observer)
        assertEquals(1, events.size, "$date ${site.name}")
        val eclipse = EventsTestSupport.solarEclipses(events)[0].second
        return assertNotNull(eclipse.local, "$date ${site.name}")
    }

    @Test
    fun testTotalityAtDallasOn2024April8() {
        // USNO: Dallas 32.7767N 96.7970W 150 m: first contact 17:23:14.6 UT (12:23 CDT), totality 18:40:37.7 to
        // 18:44:30.2 (13:40–13:44 CDT; 3m 52.5s), maximum 18:42:33.0, last contact 20:02:35.7; magnitude 1.015,
        // obscuration 100 %, Sun at altitude 64.6° and azimuth 188.0° at maximum.
        val circumstances = local("2024-04-08", EventsTestSupport.dallas)
        assertEquals(LocalSolarEclipseKind.TOTAL, circumstances.kind)
        assertInstant("2024-04-08T17:23:14Z", circumstances.c1, 45.0, "C1")
        assertInstant("2024-04-08T18:40:38Z", circumstances.c2, 45.0, "C2")
        assertInstant("2024-04-08T18:42:33Z", circumstances.maximum, 45.0, "maximum")
        assertInstant("2024-04-08T18:44:30Z", circumstances.c3, 45.0, "C3")
        assertInstant("2024-04-08T20:02:36Z", circumstances.c4, 45.0, "C4")
        assertNear(232.5, circumstances.centralDuration, 15.0, "duration of totality")
        assertNear(1.015, circumstances.magnitude, 0.005, "magnitude")
        assertEquals(1.0, circumstances.obscuration, 0.0)
        assertNear(64.6, assertNotNull(circumstances.sunAltitude), 0.3, "Sun altitude")
        assertNear(188.0, assertNotNull(circumstances.sunAzimuth), 0.3, "Sun azimuth")
        val visible = assertNotNull(circumstances.visibleInterval)
        assertTrue(abs(EventsTestSupport.seconds(visible.start, assertNotNull(circumstances.c1))) < 0.01)
        assertTrue(abs(EventsTestSupport.seconds(visible.end, assertNotNull(circumstances.c4))) < 0.01)
    }

    @Test
    fun testTotalityAtBurgosOn2026August12() {
        // USNO: Burgos 42.3439N 3.6969W 860 m: first contact 17:33:19.3 UT, totality 18:28:20.4 to 18:30:06.7
        // (20:28–20:30 CEST; 1m 46.3s), maximum 18:29:12.7, magnitude 1.014, Sun at altitude 8.2°, azimuth 282.6°;
        // sunset 19:20 UT.
        val circumstances = local("2026-08-12", EventsTestSupport.burgos)
        assertEquals(LocalSolarEclipseKind.TOTAL, circumstances.kind)
        assertInstant("2026-08-12T17:33:19Z", circumstances.c1, 45.0, "C1")
        assertInstant("2026-08-12T18:28:20Z", circumstances.c2, 45.0, "C2")
        assertInstant("2026-08-12T18:29:13Z", circumstances.maximum, 45.0, "maximum")
        assertInstant("2026-08-12T18:30:07Z", circumstances.c3, 45.0, "C3")
        assertNear(106.3, circumstances.centralDuration, 8.0, "duration of totality")
        assertNear(1.014, circumstances.magnitude, 0.005, "magnitude")
        assertNear(8.2, assertNotNull(circumstances.sunAltitude), 0.3, "Sun altitude")
        assertNear(282.6, assertNotNull(circumstances.sunAzimuth), 0.3, "Sun azimuth")
        // The eclipse ends (C4 19:21 UT) a little after the Sun sets (19:20), so the visible part is cut off.
        assertTrue(assertNotNull(circumstances.c4) >= assertNotNull(circumstances.visibleInterval).end)
    }

    @Test
    fun testTotalityAtLuxorOn2027August2() {
        // JPL Horizons (see the class comment): C1 08:40:19.4, C2 10:02:05.7, maximum 10:05:18.5, C3 10:08:30.6 (6m 24.8s),
        // C4 11:26:32.9 UT; Sun altitude about 82°.
        val circumstances = local("2027-08-02", EventsTestSupport.luxor)
        assertEquals(LocalSolarEclipseKind.TOTAL, circumstances.kind)
        assertInstant("2027-08-02T08:40:19Z", circumstances.c1, 45.0, "C1")
        assertInstant("2027-08-02T10:02:06Z", circumstances.c2, 45.0, "C2")
        assertInstant("2027-08-02T10:05:19Z", circumstances.maximum, 45.0, "maximum")
        assertInstant("2027-08-02T10:08:31Z", circumstances.c3, 45.0, "C3")
        assertInstant("2027-08-02T11:26:33Z", circumstances.c4, 45.0, "C4")
        assertNear(384.8, circumstances.centralDuration, 15.0, "duration of totality")
        assertNear(82.0, assertNotNull(circumstances.sunAltitude), 0.5, "Sun altitude")
    }

    @Test
    fun testPartialEclipseAtNewYorkOn2024April8() {
        // USNO: New York 40.7128N 74.0060W 10 m: begins 18:10:32.3 UT, maximum 19:25:30.1, ends 20:36:19.3;
        // magnitude 0.911, obscuration 89.9 %, Sun at altitude 43.4°, azimuth 235.1° at maximum.
        val circumstances = local("2024-04-08", EventsTestSupport.newYork)
        assertEquals(LocalSolarEclipseKind.PARTIAL, circumstances.kind)
        assertInstant("2024-04-08T18:10:32Z", circumstances.c1, 45.0, "C1")
        assertInstant("2024-04-08T19:25:30Z", circumstances.maximum, 45.0, "maximum")
        assertInstant("2024-04-08T20:36:19Z", circumstances.c4, 45.0, "C4")
        assertNull(circumstances.c2)
        assertNull(circumstances.c3)
        assertEquals(0.0, circumstances.centralDuration, 0.0)
        assertNear(0.911, circumstances.magnitude, 0.002, "magnitude")
        assertNear(0.899, circumstances.obscuration, 0.002, "obscuration")
        assertNear(43.4, assertNotNull(circumstances.sunAltitude), 0.3, "Sun altitude")
        assertNear(235.1, assertNotNull(circumstances.sunAzimuth), 0.3, "Sun azimuth")
    }

    @Test
    fun testAnnularEclipseAtAlbuquerqueOn2023October14() {
        // USNO: Albuquerque 35.0844N 106.6504W 1600 m: begins 15:13:11.5 UT, annularity 16:34:29.8 to 16:39:16.9
        // (4m 47.1s), maximum 16:36:52.4, ends 18:09:21.9; magnitude 0.970, obscuration 89.7 %, Sun at 36.1°, 136.8°.
        val circumstances = local("2023-10-14", EventsTestSupport.albuquerque)
        assertEquals(LocalSolarEclipseKind.ANNULAR, circumstances.kind)
        assertInstant("2023-10-14T15:13:12Z", circumstances.c1, 45.0, "C1")
        assertInstant("2023-10-14T16:34:30Z", circumstances.c2, 45.0, "C2")
        assertInstant("2023-10-14T16:36:52Z", circumstances.maximum, 45.0, "maximum")
        assertInstant("2023-10-14T16:39:17Z", circumstances.c3, 45.0, "C3")
        assertInstant("2023-10-14T18:09:22Z", circumstances.c4, 45.0, "C4")
        assertNear(287.1, circumstances.centralDuration, 15.0, "duration of annularity")
        assertNear(0.970, circumstances.magnitude, 0.003, "magnitude")
        assertNear(0.897, circumstances.obscuration, 0.003, "obscuration")
        assertNear(36.1, assertNotNull(circumstances.sunAltitude), 0.3, "Sun altitude")
        assertNear(136.8, assertNotNull(circumstances.sunAzimuth), 0.3, "Sun azimuth")
    }

    @Test
    fun testPartialEclipsesOf2026August12() {
        // USNO, 2026-08-12 (UT): London 51.5074N 0.1278W: begins 17:17:16.5, maximum 18:13:15.6 (magnitude 0.925,
        // obscuration 91.4 %, Sun at 10.3°, 280.8°), ends 19:06:16.4. Canary Islands 28N 15W: begins 17:58:34.1,
        // maximum 18:53:44.9, magnitude 0.755, obscuration 69.9 %. Madrid 40.4168N 3.7038W: begins 17:36:42.4, maximum
        // 18:32:18.5, magnitude 0.999 (just outside the path of totality), the eclipse ends after sunset.
        val london = local("2026-08-12", EventsTestSupport.london)
        assertEquals(LocalSolarEclipseKind.PARTIAL, london.kind)
        assertInstant("2026-08-12T17:17:16Z", london.c1, 45.0, "London C1")
        assertInstant("2026-08-12T18:13:16Z", london.maximum, 45.0, "London maximum")
        assertInstant("2026-08-12T19:06:16Z", london.c4, 45.0, "London C4")
        assertNear(0.925, london.magnitude, 0.002, "London magnitude")
        assertNear(0.914, london.obscuration, 0.002, "London obscuration")
        assertNear(10.3, assertNotNull(london.sunAltitude), 0.3, "London Sun altitude")
        assertNear(280.8, assertNotNull(london.sunAzimuth), 0.3, "London Sun azimuth")

        val canary = local("2026-08-12", EventsTestSupport.canaryIslands)
        assertEquals(LocalSolarEclipseKind.PARTIAL, canary.kind)
        assertInstant("2026-08-12T17:58:34Z", canary.c1, 45.0, "Canary C1")
        assertInstant("2026-08-12T18:53:45Z", canary.maximum, 45.0, "Canary maximum")
        assertNear(0.755, canary.magnitude, 0.002, "Canary magnitude")
        assertNear(0.699, canary.obscuration, 0.002, "Canary obscuration")

        val madrid = local("2026-08-12", EventsTestSupport.madrid)
        assertEquals(LocalSolarEclipseKind.PARTIAL, madrid.kind)
        assertInstant("2026-08-12T17:36:42Z", madrid.c1, 45.0, "Madrid C1")
        assertInstant("2026-08-12T18:32:18Z", madrid.maximum, 45.0, "Madrid maximum")
        assertNear(0.999, madrid.magnitude, 0.001, "Madrid magnitude")
        assertTrue(madrid.obscuration > 0.999)
        assertNull(madrid.c2)
        assertTrue(assertNotNull(madrid.c4) > assertNotNull(madrid.visibleInterval).end, "the Sun sets before the eclipse ends")
    }

    @Test
    fun testEclipseInProgressAtSunsetAndNoEclipseAtAll() {
        // USNO 2026-02-17 at 66.5S 110.5E: begins 11:05:41.4 UT, maximum 12:04:54.0 (magnitude 0.947, obscuration
        // 91.3 %, Sun at 3.8°), sunset 12:57 before the eclipse ends. Buenos Aires (2026-02-17) and Tokyo (2024-04-08):
        // "Eclipse not visible from selected location".
        val antarctic = local("2026-02-17", EventsTestSupport.antarctica)
        assertEquals(LocalSolarEclipseKind.PARTIAL, antarctic.kind)
        assertInstant("2026-02-17T11:05:41Z", antarctic.c1, 45.0, "Antarctic C1")
        assertInstant("2026-02-17T12:04:54Z", antarctic.maximum, 45.0, "Antarctic maximum")
        assertNear(0.947, antarctic.magnitude, 0.003, "Antarctic magnitude")
        assertNear(3.8, assertNotNull(antarctic.sunAltitude), 0.3, "Antarctic Sun altitude")
        val sunset = assertNotNull(antarctic.visibleInterval).end
        assertInstant("2026-02-17T12:57:00Z", sunset, 120.0, "sunset")
        assertTrue(assertNotNull(antarctic.c4) > sunset)

        val buenosAires = local("2026-02-17", EventsTestSupport.buenosAires)
        assertEquals(LocalSolarEclipseKind.NONE, buenosAires.kind)
        assertNull(buenosAires.c1)
        assertNull(buenosAires.maximum)
        assertNull(buenosAires.visibleInterval)
        assertEquals(0.0, buenosAires.magnitude, 0.0)

        // Tokyo: the Moon does cross the Sun's disc a little at 17:50 UT, but the Sun is far below the horizon
        // (it is 02:50 JST), so nothing is seen.
        val tokyo = local("2024-04-08", EventsTestSupport.tokyo)
        assertEquals(LocalSolarEclipseKind.NONE, tokyo.kind)
        assertNull(tokyo.visibleInterval)
        assertTrue(assertNotNull(tokyo.sunAltitude) < -20)
    }

    @Test
    fun testLocalCircumstancesFunctionMatchesTheFinder() {
        val global = eclipses("2024-04-08T00:00:00Z", "2024-04-08T23:59:59Z")[0]
        val direct = SolarEclipseFinder.localCircumstances(global, EventsTestSupport.dallas.observer)
        assertEquals(local("2024-04-08", EventsTestSupport.dallas), direct)
    }

    @Test
    fun testSolarEclipseVisibilityHasContactsAndWindow() {
        val options = EventOptions(kinds = setOf(EventKind.SOLAR_ECLIPSE))
        val events = EventCalculator.events(
            utc("2024-04-08T00:00:00Z"), utc("2024-04-08T23:59:59Z"), EventsTestSupport.dallas.observer, options,
        )
        assertEquals(1, events.size)
        val visibility = assertNotNull(events[0].visibility)
        assertEquals(
            listOf(EclipseContact.C1, EclipseContact.C2, EclipseContact.MAXIMUM, EclipseContact.C3, EclipseContact.C4),
            visibility.contacts.map { it.contact },
        )
        assertTrue(visibility.contacts.all { it.altitude > 50 })
        assertTrue(!visibility.isDark)
        val window = assertNotNull(visibility.bestWindow)
        val circumstances = assertNotNull(EventsTestSupport.solarEclipses(events)[0].second.local)
        assertEquals(circumstances.visibleInterval, window)
        assertEquals(circumstances.maximum, visibility.bestTime)
        assertNear(64.6, visibility.altitude, 0.3, "altitude at maximum")
        // From Tokyo the same eclipse is not observable at all.
        val tokyo = EventCalculator.events(
            utc("2024-04-08T00:00:00Z"), utc("2024-04-08T23:59:59Z"), EventsTestSupport.tokyo.observer, options,
        )
        assertTrue(!assertNotNull(tokyo[0].visibility).isObservable)
    }

    @Test
    fun testObscurationOfOverlappingDiscs() {
        // Limiting cases, then two equal discs a radius apart: the lens has area (2·acos(1/2) - sqrt(3)/2) r² =
        // (2π/3 - √3/2) r², i.e. a fraction 2/3 - √3/(2π) = 0.39100 of the disc.
        assertEquals(0.0, DiscOverlap.obscuration(1.0, 1.0, 2.0), 0.0)
        assertEquals(0.0, DiscOverlap.obscuration(1.0, 0.9, 3.0), 0.0)
        assertEquals(1.0, DiscOverlap.obscuration(1.0, 1.05, 0.0), 0.0)
        assertEquals(1.0, DiscOverlap.obscuration(1.0, 1.05, 0.04), 0.0)
        assertEquals(0.9 * 0.9, DiscOverlap.obscuration(1.0, 0.9, 0.05), 1e-15)
        assertEquals(2.0 / 3 - kotlin.math.sqrt(3.0) / (2 * Math.PI), DiscOverlap.obscuration(1.0, 1.0, 1.0), 1e-12)
        // The magnitude: half the Sun's diameter covered when the Moon's edge reaches the Sun's centre.
        assertEquals(0.5, DiscOverlap.magnitude(1.0, 1.0, 1.0), 1e-15)
        assertEquals(0.0, DiscOverlap.magnitude(1.0, 0.9, 5.0), 0.0)
        assertEquals(1.05, DiscOverlap.magnitude(1.0, 1.1, 0.0), 1e-15)
    }

    @Test
    fun testPartialSiteObscurationIsBelowTheMagnitudeInDiameter() {
        // For a partial eclipse the area covered is less than the share of the diameter covered (0.899 against 0.911).
        val circumstances = local("2024-04-08", EventsTestSupport.newYork)
        assertTrue(circumstances.obscuration < circumstances.magnitude)
        assertTrue(circumstances.obscuration > 0.5)
    }
}
