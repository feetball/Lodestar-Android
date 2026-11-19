package net.kuecker.lodestar.features.horizon

import net.kuecker.lodestar.kit.horizon.AzimuthRange
import net.kuecker.lodestar.kit.horizon.HorizonProfile
import net.kuecker.lodestar.model.HorizonSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Which skyline applies at a site, and what the screens call it. */
class SiteHorizonTest {
    private val recorded = HorizonProfile.constant(5.0)
    private val terrain = assertNotNull(HorizonProfile.create(List(360) { 2.0 + it / 100.0 }))
    private val gap = listOf(AzimuthRange(79.5, 16.0))

    @Test
    fun withoutAnySkylineTheHorizonIsOpen() {
        assertEquals(ResolvedHorizon.OPEN, SiteHorizon.resolve(null, null, emptyList()))
        assertNull(ResolvedHorizon.OPEN.profile)
        assertNull(ResolvedHorizon.OPEN.source)
    }

    @Test
    fun aRecordedSkylineAloneIsTheRecordedSkyline() {
        val resolved = SiteHorizon.resolve(recorded, null, emptyList())
        assertEquals(recorded, resolved.profile)
        assertEquals(HorizonSource.RECORDED, resolved.source)
        assertEquals(HorizonSource.RECORDED, SiteHorizon.resolve(recorded, null, gap).source)
    }

    @Test
    fun aTerrainHorizonAloneIsTheTerrainHorizon() {
        val resolved = SiteHorizon.resolve(null, terrain, emptyList())
        assertEquals(terrain, resolved.profile)
        assertEquals(HorizonSource.TERRAIN, resolved.source)
        // What was recorded is not in it: the gaps only matter next to a recorded skyline.
        assertEquals(HorizonSource.TERRAIN, SiteHorizon.resolve(null, terrain, gap).source)
    }

    @Test
    fun aFlatTerrainHorizonIsAnOpenHorizon() {
        assertEquals(ResolvedHorizon.OPEN, SiteHorizon.resolve(null, HorizonProfile.flat, emptyList()))
        assertEquals(ResolvedHorizon.OPEN, SiteHorizon.resolve(HorizonProfile.flat, HorizonProfile.flat, gap))
        // A recorded skyline over flat terrain is still the recorded skyline with the terrain in its gaps.
        assertEquals(HorizonSource.RECORDED_AND_TERRAIN, SiteHorizon.resolve(recorded, HorizonProfile.flat, gap).source)
    }

    @Test
    fun theTerrainFillsTheStretchesTheSweepLeftOpen() {
        val resolved = SiteHorizon.resolve(recorded, terrain, gap)
        assertEquals(HorizonSource.RECORDED_AND_TERRAIN, resolved.source)
        val profile = assertNotNull(resolved.profile)
        for (bin in 0 until 360) {
            assertEquals(if (bin in 80..95) terrain.altitudes[bin] else 5.0, profile.altitudes[bin], "bin $bin")
        }
    }

    @Test
    fun aCompleteSweepStaysTheRecordedSkylineWhateverTerrainIsStored() {
        val resolved = SiteHorizon.resolve(recorded, terrain, emptyList())
        assertEquals(recorded, resolved.profile)
        assertEquals(HorizonSource.RECORDED, resolved.source)
    }

    @Test
    fun theSourcesAreNamedForTheScreens() {
        assertEquals("Recorded skyline", HorizonSource.RECORDED.label)
        assertEquals("Terrain horizon", HorizonSource.TERRAIN.label)
        assertEquals("Recorded + terrain", HorizonSource.RECORDED_AND_TERRAIN.label)
        assertEquals("Terrain horizon applied", HorizonFormat.applied(HorizonSource.TERRAIN))
        assertEquals("Recorded + terrain applied", HorizonFormat.applied(HorizonSource.RECORDED_AND_TERRAIN))
        assertEquals("Skyline applied", HorizonFormat.applied(null))
        assertEquals("terrain horizon", HorizonFormat.spokenName(HorizonSource.TERRAIN))
        assertEquals("recorded and terrain skyline", HorizonFormat.spokenName(HorizonSource.RECORDED_AND_TERRAIN))
        assertEquals("recorded skyline", HorizonFormat.spokenName(null))
    }

    @Test
    fun theOneLineSummaryNamesTheSourceAndTheHighestPlace() {
        val east = assertNotNull(HorizonProfile.create(List(360) { if (it in 80..100) 14.0 else 3.0 }))
        assertEquals("Open horizon", HorizonFormat.summary(ResolvedHorizon.OPEN, 0.9))
        assertEquals(
            "Recorded · 86% covered · highest 14° in the east",
            HorizonFormat.summary(ResolvedHorizon(east, HorizonSource.RECORDED), 0.86),
        )
        assertEquals(
            "Terrain horizon · highest 14° in the east",
            HorizonFormat.summary(ResolvedHorizon(east, HorizonSource.TERRAIN), 0.86),
        )
        assertEquals(
            "Recorded + terrain · 60% covered · highest 14° in the east",
            HorizonFormat.summary(ResolvedHorizon(east, HorizonSource.RECORDED_AND_TERRAIN), 0.6),
        )
        assertEquals(
            "Recorded + terrain · highest 14° in the east",
            HorizonFormat.summary(ResolvedHorizon(east, HorizonSource.RECORDED_AND_TERRAIN), null),
        )
    }

    @Test
    fun theSpokenSummaryNamesTheSkyline() {
        assertTrue(
            net.kuecker.lodestar.shared.SkylineSummary.describe(terrain, HorizonSource.TERRAIN)
                .startsWith("The terrain horizon hides the sky up to"),
        )
        assertTrue(
            net.kuecker.lodestar.shared.SkylineSummary.describe(recorded).startsWith("The recorded skyline hides the sky up to"),
        )
    }

    // MARK: What a sweep remembers

    private fun mask(unswept: List<IntRange>): List<Boolean> = List(360) { bin -> unswept.none { bin in it } }

    @Test
    fun aSweepOverAnOldSkylineAddsUpWhatBothReached() {
        val old = HorizonCoverageMemory.Entry(0.5, mask(listOf(180..359)))
        val merged = assertNotNull(HorizonCoverageMemory.merged(mask(listOf(0..269, 340..359)), old))
        // Swept now: 270…339; before: 0…179.
        assertEquals(250.0 / 360, merged.coverage, 1e-12)
        assertEquals(mask(listOf(180..269, 340..359)), merged.mask)
    }

    @Test
    fun aSweepOverAnOldSkylineWithoutItsDegreesKnowsAtLeastTheLargerShare() {
        val merged = assertNotNull(HorizonCoverageMemory.merged(mask(listOf(100..359)), HorizonCoverageMemory.Entry(0.9, null)))
        assertEquals(0.9, merged.coverage)
        assertNull(merged.mask)
        // Nothing remembered about the old skyline: nothing can be said about the total.
        assertNull(HorizonCoverageMemory.merged(mask(emptyList()), null))
    }
}
