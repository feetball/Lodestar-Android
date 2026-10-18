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
 * Lunar eclipses against the NASA/Espenak catalog.
 *
 * Reference: F. Espenak and J. Meeus, *Five Millennium Catalog of Lunar Eclipses*, NASA GSFC
 * (https://eclipse.gsfc.nasa.gov/LEcat5/LE2001-2100.html: greatest eclipse in TD, ΔT, gamma, magnitudes, durations) and
 * the per-eclipse plots https://eclipse.gsfc.nasa.gov/LEplot/LEplot2001/LE2025Mar14T.pdf (and the three others): contact
 * times in UT, computed with ΔT = 75 s and the Danjon rule. The kit uses its own ΔT (about 69 s in 2025–26), so its
 * times run a few seconds behind; the observed differences are below 17 s and the brief allowed ±2 minutes.
 */
class EventsLunarEclipseTests {
    /** NASA contact times (UT) of one eclipse; `null` where the eclipse has no such contact. */
    private class Reference(
        val date: String,
        val kind: LunarEclipseKind,
        val p1: String,
        val u1: String?,
        val u2: String?,
        val greatest: String,
        val u3: String?,
        val u4: String?,
        val p4: String,
        val gamma: Double,
        val penumbralMagnitude: Double,
        val umbralMagnitude: Double,
    )

    private val references = listOf(
        Reference(
            "2025-03-14", LunarEclipseKind.TOTAL, "2025-03-14T03:57:24Z", "2025-03-14T05:09:33Z", "2025-03-14T06:25:59Z",
            "2025-03-14T06:58:42Z", "2025-03-14T07:31:23Z", "2025-03-14T08:47:48Z", "2025-03-14T10:00:01Z",
            0.3484, 2.2595, 1.1784,
        ),
        Reference(
            "2025-09-07", LunarEclipseKind.TOTAL, "2025-09-07T15:28:21Z", "2025-09-07T16:27:02Z", "2025-09-07T17:30:41Z",
            "2025-09-07T18:11:43Z", "2025-09-07T18:52:47Z", "2025-09-07T19:56:26Z", "2025-09-07T20:55:00Z",
            -0.2752, 2.3440, 1.3619,
        ),
        Reference(
            "2026-03-03", LunarEclipseKind.TOTAL, "2026-03-03T08:44:22Z", "2026-03-03T09:50:00Z", "2026-03-03T11:04:26Z",
            "2026-03-03T11:33:37Z", "2026-03-03T12:02:45Z", "2026-03-03T13:17:10Z", "2026-03-03T14:22:59Z",
            -0.3765, 2.1838, 1.1507,
        ),
        Reference(
            "2026-08-28", LunarEclipseKind.PARTIAL, "2026-08-28T01:23:55Z", "2026-08-28T02:33:48Z", null,
            "2026-08-28T04:12:49Z", null, "2026-08-28T05:51:55Z", "2026-08-28T07:01:41Z",
            0.4964, 1.9645, 0.9299,
        ),
    )

    private fun eclipses(from: String, to: String): List<LunarEclipse> =
        EventsTestSupport.lunarEclipses(LunarEclipseFinder.events(utc(from), utc(to))).map { it.second }

    @Test
    fun testContactTimesAgainstTheNasaCatalog() {
        // Observed differences are at most 17 s; 40 s leaves room for the ΔT assumption and still catches regressions.
        val tolerance = 40.0
        for (reference in references) {
            val found = eclipses("${reference.date}T00:00:00Z", "${reference.date}T23:59:59Z")
            assertEquals(1, found.size, reference.date)
            val eclipse = found[0]
            assertEquals(reference.kind, eclipse.kind, reference.date)
            assertInstant(reference.p1, eclipse.p1, tolerance, "${reference.date} P1")
            assertInstant(reference.greatest, eclipse.greatest, tolerance, "${reference.date} greatest")
            assertInstant(reference.p4, eclipse.p4, tolerance, "${reference.date} P4")
            for ((label, expected, actual) in listOf(
                Triple("U1", reference.u1, eclipse.u1), Triple("U2", reference.u2, eclipse.u2),
                Triple("U3", reference.u3, eclipse.u3), Triple("U4", reference.u4, eclipse.u4),
            )) {
                if (expected == null) {
                    assertNull(actual, "${reference.date} $label")
                } else {
                    assertInstant(expected, actual, tolerance, "${reference.date} $label")
                }
            }
        }
    }

    @Test
    fun testMagnitudesAndGammaAgainstTheNasaCatalog() {
        for (reference in references) {
            val eclipse = eclipses("${reference.date}T00:00:00Z", "${reference.date}T23:59:59Z")[0]
            assertNear(reference.gamma, eclipse.gamma, 0.002, "${reference.date} gamma")
            assertNear(reference.penumbralMagnitude, eclipse.penumbralMagnitude, 0.002, "${reference.date} penumbral magnitude")
            assertNear(reference.umbralMagnitude, eclipse.umbralMagnitude, 0.002, "${reference.date} umbral magnitude")
        }
    }

    @Test
    fun testDurationsAgainstTheNasaCatalog() {
        // Catalog durations (minutes): 2025-03-14 pen 362.6, par 218.3, total 65.4; 2026-08-28 pen 337.8, par 198.1.
        val march = eclipses("2025-03-14T00:00:00Z", "2025-03-14T23:59:59Z")[0]
        assertNear(362.6 * 60, march.penumbralDuration, 40.0, "penumbral duration")
        assertNear(218.3 * 60, march.partialDuration, 40.0, "partial duration")
        assertNear(65.4 * 60, march.totalDuration, 40.0, "total duration")
        val august = eclipses("2026-08-28T00:00:00Z", "2026-08-28T23:59:59Z")[0]
        assertNear(337.8 * 60, august.penumbralDuration, 40.0, "penumbral duration")
        assertNear(198.1 * 60, august.partialDuration, 40.0, "partial duration")
        assertEquals(0.0, august.totalDuration, 0.0)
    }

    @Test
    fun testKindsMagnitudesAndDurationsAgainstTheNasaCatalog2028To2040() {
        // Columns of the catalog: date, greatest eclipse (TD), ΔT (s), type (N penumbral, P partial, T total), gamma,
        // penumbral and umbral magnitude, durations (minutes) of the penumbral, partial and total phases.
        val rows = """
            2028 Jan 12 | 04:14:13 | 77 | P | 0.9817 | 1.0468 | 0.0662 | 250.7 | 56.0 | -
            2028 Jul 06 | 18:20:57 | 77 | P | -0.7903 | 1.4266 | 0.3892 | 310.6 | 141.5 | -
            2028 Dec 31 | 16:53:15 | 77 | T | 0.3258 | 2.2742 | 1.2463 | 336.2 | 208.8 | 71.3
            2029 Jun 26 | 03:23:22 | 77 | T | 0.0124 | 2.8266 | 1.8436 | 335.1 | 219.5 | 101.9
            2029 Dec 20 | 22:43:12 | 78 | T | -0.3811 | 2.2008 | 1.1174 | 358.0 | 213.3 | 53.7
            2030 Jun 15 | 18:34:34 | 78 | P | 0.7534 | 1.4480 | 0.5025 | 278.2 | 144.4 | -
            2030 Dec 09 | 22:28:51 | 78 | N | -1.0731 | 0.9416 | -0.1628 | 279.2 | - | -
            2031 May 07 | 03:52:02 | 78 | N | -1.0694 | 0.8814 | -0.0904 | 237.3 | - | -
            2031 Jun 05 | 11:45:17 | 78 | N | 1.4731 | 0.1292 | -0.8199 | 95.6 | - | -
            2031 Oct 30 | 07:46:45 | 79 | N | 1.1773 | 0.7161 | -0.3204 | 231.8 | - | -
            2032 Apr 25 | 15:14:51 | 79 | T | -0.3558 | 2.2192 | 1.1913 | 342.4 | 211.2 | 65.5
            2032 Oct 18 | 19:03:40 | 79 | T | 0.4169 | 2.0830 | 1.1028 | 315.4 | 195.9 | 47.1
            2033 Apr 14 | 19:13:51 | 80 | T | 0.3954 | 2.1711 | 1.0944 | 361.2 | 215.0 | 49.2
            2033 Oct 08 | 10:56:23 | 80 | T | -0.2889 | 2.3057 | 1.3497 | 312.6 | 202.4 | 78.8
            2034 Apr 03 | 19:06:59 | 80 | N | 1.1144 | 0.8545 | -0.2274 | 265.4 | - | -
            2034 Sep 28 | 02:47:37 | 81 | P | -1.0110 | 0.9911 | 0.0144 | 248.7 | 26.7 | -
            2035 Feb 22 | 09:06:12 | 81 | N | -1.0367 | 0.9652 | -0.0535 | 255.7 | - | -
            2035 Aug 19 | 01:12:15 | 81 | P | 0.9433 | 1.1507 | 0.1037 | 289.8 | 76.5 | -
            2036 Feb 11 | 22:13:06 | 82 | T | -0.3110 | 2.2751 | 1.2995 | 316.1 | 201.9 | 74.5
            2036 Aug 07 | 02:52:32 | 82 | T | 0.2004 | 2.5266 | 1.4544 | 372.1 | 231.3 | 95.3
            2037 Jan 31 | 14:01:38 | 82 | T | 0.3619 | 2.1803 | 1.2074 | 312.1 | 197.5 | 63.7
            2037 Jul 27 | 04:09:53 | 83 | P | -0.5582 | 1.8584 | 0.8095 | 340.8 | 192.4 | -
            2038 Jan 21 | 03:49:52 | 83 | N | 1.0710 | 0.8996 | -0.1140 | 245.8 | - | -
            2038 Jun 17 | 02:45:02 | 83 | N | 1.3082 | 0.4422 | -0.5275 | 176.3 | - | -
            2038 Jul 16 | 11:35:56 | 84 | N | -1.2837 | 0.4999 | -0.4952 | 192.4 | - | -
            2038 Dec 11 | 17:45:00 | 84 | N | -1.1448 | 0.8046 | -0.2892 | 258.5 | - | -
            2039 Jun 06 | 18:54:25 | 84 | P | 0.5460 | 1.8272 | 0.8846 | 296.7 | 179.3 | -
            2039 Nov 30 | 16:56:28 | 85 | P | -0.4721 | 2.0418 | 0.9426 | 360.1 | 206.0 | -
            2040 May 26 | 11:46:22 | 85 | T | -0.1872 | 2.4938 | 1.5348 | 321.4 | 210.7 | 92.2
            2040 Nov 18 | 19:04:40 | 85 | T | 0.2361 | 2.4525 | 1.3974 | 353.6 | 220.4 | 87.8
""".trimIndent().lines()
        val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        assertEquals(30, rows.size)
        for (row in rows) {
            val fields = row.split("|").map { it.trim() }
            val date = fields[0].split(" ")
            val iso = "%s-%02d-%sT%sZ".format(date[0], months.indexOf(date[1]) + 1, date[2], fields[1])
            val greatest = utc(iso).minusMillis((fields[2].toDouble() * 1000).toLong())
            val found = eclipses("${iso.substring(0, 10)}T00:00:00Z", "${iso.substring(0, 10)}T23:59:59Z").filter {
                abs(EventsTestSupport.seconds(it.greatest, greatest)) < 3_600
            }
            assertEquals(1, found.size, row)
            val eclipse = found[0]
            val expectedKind = when (fields[3]) {
                "N" -> LunarEclipseKind.PENUMBRAL
                "P" -> LunarEclipseKind.PARTIAL
                else -> LunarEclipseKind.TOTAL
            }
            assertEquals(expectedKind, eclipse.kind, row)
            assertTrue(abs(EventsTestSupport.seconds(eclipse.greatest, greatest)) < 45, "$row greatest")
            assertNear(fields[4].toDouble(), eclipse.gamma, 0.003, "$row gamma")
            assertNear(fields[5].toDouble(), eclipse.penumbralMagnitude, 0.005, "$row penumbral magnitude")
            assertNear(fields[6].toDouble(), eclipse.umbralMagnitude, 0.005, "$row umbral magnitude")
            assertNear(fields[7].toDouble() * 60, eclipse.penumbralDuration, 60.0, "$row penumbral duration")
            assertNear((fields[8].toDoubleOrNull() ?: 0.0) * 60, eclipse.partialDuration, 60.0, "$row partial duration")
            assertNear((fields[9].toDoubleOrNull() ?: 0.0) * 60, eclipse.totalDuration, 60.0, "$row total duration")
        }
    }

    @Test
    fun testPenumbralEclipsesOf2027() {
        // Catalog: 2027 Feb 20 greatest 23:14:06 TD (ΔT 76 s), gamma -1.0480, pen 0.9266, um -0.0569;
        // 2027 Aug 17 07:14:59 TD, gamma 1.2797, pen 0.5456, um -0.5254; 2027 Jul 18 16:04:09 TD, pen 0.0014.
        val found = eclipses("2027-01-01T00:00:00Z", "2027-12-31T23:59:59Z")
        assertEquals(3, found.size)
        assertTrue(found.all { it.kind == LunarEclipseKind.PENUMBRAL })
        assertTrue(found.all { it.u1 == null && it.u2 == null && it.u3 == null && it.u4 == null })
        assertInstant("2027-02-20T23:12:50Z", found[0].greatest, 40.0, "Feb greatest")
        assertInstant("2027-07-18T16:02:53Z", found[1].greatest, 40.0, "Jul greatest")
        assertInstant("2027-08-17T07:13:43Z", found[2].greatest, 40.0, "Aug greatest")
        assertNear(0.9266, found[0].penumbralMagnitude, 0.004, "Feb penumbral magnitude")
        assertNear(-0.0569, found[0].umbralMagnitude, 0.004, "Feb umbral magnitude")
        assertNear(-1.0480, found[0].gamma, 0.003, "Feb gamma")
        assertNear(0.5456, found[2].penumbralMagnitude, 0.004, "Aug penumbral magnitude")
        assertNear(1.2797, found[2].gamma, 0.003, "Aug gamma")
        assertNear(0.0014, found[1].penumbralMagnitude, 0.002, "Jul penumbral magnitude")
    }

    @Test
    fun testContactsAreInOrderAndComplete() {
        val total = eclipses("2025-03-14T00:00:00Z", "2025-03-14T23:59:59Z")[0]
        assertEquals(
            listOf(
                EclipseContact.P1, EclipseContact.U1, EclipseContact.U2, EclipseContact.GREATEST, EclipseContact.U3,
                EclipseContact.U4, EclipseContact.P4,
            ),
            total.contacts.map { it.contact },
        )
        for (index in 1 until total.contacts.size) {
            assertTrue(total.contacts[index].time > total.contacts[index - 1].time)
        }
        val partial = eclipses("2026-08-28T00:00:00Z", "2026-08-28T23:59:59Z")[0]
        assertEquals(
            listOf(EclipseContact.P1, EclipseContact.U1, EclipseContact.GREATEST, EclipseContact.U4, EclipseContact.P4),
            partial.contacts.map { it.contact },
        )
        val penumbral = eclipses("2027-02-20T00:00:00Z", "2027-02-21T23:59:59Z")[0]
        assertEquals(listOf(EclipseContact.P1, EclipseContact.GREATEST, EclipseContact.P4), penumbral.contacts.map { it.contact })
    }

    @Test
    fun testQuietMonthsHaveNoEclipse() {
        // 2025 has only the two eclipses of March and September (NASA catalog).
        assertEquals(0, eclipses("2025-03-20T00:00:00Z", "2025-08-31T23:59:59Z").size)
        assertEquals(2, eclipses("2025-01-01T00:00:00Z", "2025-12-31T23:59:59Z").size)
        assertEquals(2, eclipses("2026-01-01T00:00:00Z", "2026-12-31T23:59:59Z").size)
    }

    @Test
    fun testSignOfGammaFollowsTheSideOfTheAxis() {
        // The Moon passes north of the shadow axis in March 2025 (declination +2°41′ against the axis at +2°24′).
        val march = eclipses("2025-03-14T00:00:00Z", "2025-03-14T23:59:59Z")[0]
        val september = eclipses("2025-09-07T00:00:00Z", "2025-09-07T23:59:59Z")[0]
        assertTrue(march.gamma > 0 && september.gamma < 0)
    }

    @Test
    fun testVisibilityFromLosAngelesAndMoscow() {
        // 2025-03-14: the whole eclipse happens during the night of 13/14 March in California (PDT, UTC-7: P1 20:57,
        // totality 23:26–00:31) and in the afternoon and evening in Moscow, where the full Moon has not risen.
        val range = Pair(utc("2025-03-14T00:00:00Z"), utc("2025-03-14T23:59:59Z"))
        val onlyEclipses = EventOptions(kinds = setOf(EventKind.LUNAR_ECLIPSE))
        val losAngeles = EventCalculator.events(range.first, range.second, EventsTestSupport.losAngeles.observer, onlyEclipses)
        assertEquals(1, losAngeles.size)
        val visibility = assertNotNull(losAngeles[0].visibility)
        assertEquals(7, visibility.contacts.size)
        for (contact in visibility.contacts) {
            val frame = SkyFrame(contact.time, EventsTestSupport.losAngeles.observer)
            val moon = frame.horizontal(SolarSystemBody.MOON, false)
            assertNear(moon.altitude, contact.altitude, 1e-9, "${contact.contact} altitude")
            assertNear(moon.azimuth, contact.azimuth, 1e-9, "${contact.contact} azimuth")
        }
        // Totality is high in the sky and the sky is dark.
        val totalityStart = visibility.contacts.first { it.contact == EclipseContact.U2 }
        assertTrue(totalityStart.altitude > 30, "Moon at the start of totality: ${totalityStart.altitude}")
        assertTrue(visibility.isDark && visibility.isObservable)
        val window = assertNotNull(visibility.bestWindow)
        val eclipse = EventsTestSupport.lunarEclipses(losAngeles)[0].second
        val umbralStart = assertNotNull(eclipse.u1)
        val umbralEnd = assertNotNull(eclipse.u4)
        assertTrue(abs(EventsTestSupport.seconds(window.start, umbralStart)) < 0.01, "the window starts at U1")
        assertTrue(abs(EventsTestSupport.seconds(window.end, umbralEnd)) < 0.01, "the window ends at U4")
        assertEquals(eclipse.greatest, visibility.bestTime)
        assertEquals(0.0, visibility.darknessLimit, 0.0)

        val moscow = EventCalculator.events(range.first, range.second, EventsTestSupport.moscow.observer, onlyEclipses)[0]
        val moscowVisibility = assertNotNull(moscow.visibility)
        assertNull(moscowVisibility.bestWindow)
        assertNull(moscowVisibility.bestTime)
        assertTrue(moscowVisibility.contacts.all { it.altitude < 0 }, "the Moon is below the horizon all through the eclipse")
        assertTrue(!moscowVisibility.isObservable)
        // visibleOnly drops it from the list.
        val visibleOnly = EventOptions(kinds = setOf(EventKind.LUNAR_ECLIPSE), visibleOnly = true)
        assertEquals(0, EventCalculator.events(range.first, range.second, EventsTestSupport.moscow.observer, visibleOnly).size)
        assertEquals(1, EventCalculator.events(range.first, range.second, EventsTestSupport.losAngeles.observer, visibleOnly).size)
    }

    @Test
    fun testEclipseWithTheMoonRisingDuringIt() {
        // Auckland (36.85S 174.76E, NZDT UTC+13) on 2025-03-14: the full Moon rises at about 06:45 UT (19:45 local),
        // between the start (U2 06:26) and the end (U3 07:31) of totality; the umbral phases U1–U4 are 05:09–08:48 UT.
        // The window starts at moonrise and runs to the end of the partial phase.
        val auckland = Observer(GeographicLocation(-36.85, 174.76, 20.0))
        val onlyEclipses = EventOptions(kinds = setOf(EventKind.LUNAR_ECLIPSE))
        val events = EventCalculator.events(utc("2025-03-14T00:00:00Z"), utc("2025-03-14T23:59:59Z"), auckland, onlyEclipses)
        val visibility = assertNotNull(events[0].visibility)
        val eclipse = EventsTestSupport.lunarEclipses(events)[0].second
        assertTrue(visibility.contacts.any { it.altitude < 0 } && visibility.contacts.any { it.altitude > 0 })
        val window = assertNotNull(visibility.bestWindow)
        val frame = SkyFrame(window.start, auckland)
        assertNear(RiseSetCalculator.standardHorizonAltitude, frame.horizontal(SolarSystemBody.MOON, false).altitude, 0.01, "window starts at moonrise")
        assertTrue(window.start > assertNotNull(eclipse.u2) && window.start < assertNotNull(eclipse.u3), "moonrise during totality")
        assertTrue(abs(EventsTestSupport.seconds(window.end, assertNotNull(eclipse.u4))) < 0.01)
        // The greatest eclipse (06:58 UT) is after moonrise: it is the best moment.
        assertEquals(eclipse.greatest, visibility.bestTime)
        val greatest = visibility.contacts.first { it.contact == EclipseContact.GREATEST }
        assertTrue(greatest.altitude > 0)
        assertTrue(visibility.contacts.first { it.contact == EclipseContact.P1 }.altitude < 0)
    }

    @Test
    fun testInvalidIntervalsGiveNoEclipses() {
        val moment = utc("2025-03-14T00:00:00Z")
        assertTrue(LunarEclipseFinder.events(moment, moment.minusSeconds(1)).isEmpty())
        assertTrue(LunarEclipseFinder.events(moment, moment).isEmpty())
    }

    @Test
    fun testShadowEnlargementFollowsDanjonsRule() {
        // The NASA plot of 2025-03-14 lists penumbral radius 1.1899° and umbral radius 0.6537° at greatest eclipse.
        val shadow = LunarShadow(JulianDate(utc("2025-03-14T06:58:42Z")).ut)
        assertNear(0.6537, shadow.umbra, 0.0005, "umbral radius")
        assertNear(1.1899, shadow.penumbra, 0.0005, "penumbral radius")
        assertNear(0.3171, shadow.separation, 0.001, "axis distance")
        assertTrue(abs(LunarShadow.shadowEnlargement - 1.01) < 1e-12)
    }
}
