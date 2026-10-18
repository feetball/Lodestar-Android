package net.kuecker.lodestar.features.horizon

import net.kuecker.lodestar.kit.horizon.AzimuthRange
import net.kuecker.lodestar.kit.horizon.HorizonProfile
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class HorizonFormatTest {
    init {
        Locale.setDefault(Locale.US)
    }

    /** A skyline of [base] degrees with a [peak] at azimuths [from]…[to] (whole degrees, no wrap). */
    private fun skyline(base: Double, peak: Double, from: Int, to: Int): HorizonProfile =
        assertNotNull(HorizonProfile.create(List(360) { if (it in from..to) peak else base }))

    @Test
    fun anOpenHorizonHasNoSkylineToDescribe() {
        assertEquals("Open horizon", HorizonFormat.status(null, null))
        assertEquals("Open horizon", HorizonFormat.status(HorizonProfile.flat, 0.9))
    }

    @Test
    fun theStatusNamesTheCoverageAndTheHighestPlace() {
        val profile = skyline(3.0, 14.0, 80, 100)
        assertEquals("Recorded · 86% covered · highest 14° in the east", HorizonFormat.status(profile, 0.86))
        assertEquals("Recorded · highest 14° in the east", HorizonFormat.status(profile, null))
    }

    @Test
    fun theHighestPlaceIsTheMiddleOfTheHighestStretchEvenAcrossNorth() {
        assertEquals("in the north", HorizonFormat.highestPlace(skyline(0.0, 20.0, 350, 359).let { first ->
            first.maximum(skyline(0.0, 20.0, 0, 10))
        }))
        assertEquals("in the southwest", HorizonFormat.highestPlace(skyline(2.0, 9.0, 215, 235)))
    }

    @Test
    fun aSkylineAsHighEverywhereIsHighAllRound() {
        assertEquals("all round", HorizonFormat.highestPlace(HorizonProfile.constant(12.0)))
        assertEquals("Recorded · highest 12° all round", HorizonFormat.status(HorizonProfile.constant(12.0), null))
    }

    @Test
    fun directionsAreTheEightMainOnes() {
        assertEquals("north", HorizonFormat.direction(359.0))
        assertEquals("northeast", HorizonFormat.direction(40.0))
        assertEquals("east", HorizonFormat.direction(100.0))
        assertEquals("southeast", HorizonFormat.direction(150.0))
        assertEquals("west", HorizonFormat.direction(-80.0))
    }

    @Test
    fun anUnrecordedStretchIsNamedByTheDegreesItCovers() {
        // The recorder starts a range at the lower edge of its first bin: bins 80…95 are 79.5° for 16°.
        assertEquals("80°–95°", HorizonFormat.gapText(AzimuthRange(79.5, 16.0)))
        assertEquals("350°–5°", HorizonFormat.gapText(AzimuthRange(349.5, 16.0)))
        assertEquals("10°", HorizonFormat.gapText(AzimuthRange(9.5, 1.0)))
    }

    @Test
    fun onlyTheFirstGapsAreListed() {
        val gaps = (0 until 5).map { AzimuthRange(it * 70 + 9.5, 10.0) }
        assertEquals("10°–19°, 80°–89°, 150°–159° and 2 more", HorizonFormat.gapsText(gaps))
        assertEquals("10°–19°, 80°–89°", HorizonFormat.gapsText(gaps.take(2)))
    }
}
