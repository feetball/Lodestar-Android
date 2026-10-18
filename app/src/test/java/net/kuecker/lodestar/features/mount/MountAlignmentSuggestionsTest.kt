package net.kuecker.lodestar.features.mount

import net.kuecker.lodestar.kit.catalog.Catalog
import net.kuecker.lodestar.kit.mount.SkyWatcherMountMode
import net.kuecker.lodestar.services.MountTestSky
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MountAlignmentSuggestionsTest {
    private val catalog: Catalog by lazy { Catalog.loadBundled() }
    private val candidates by lazy { MountAlignmentSuggestions.candidates(catalog, MountTestSky.boulder, MountTestSky.start) }

    @Test
    fun theCandidatesAreBrightStarsInAGoodPartOfTheSky() {
        assertTrue(candidates.size >= 5, "${candidates.size}")
        for (candidate in candidates) {
            assertTrue(candidate.magnitude <= MountAlignmentSuggestions.STAR_MAGNITUDE_LIMIT, candidate.obj.name)
            assertTrue(candidate.horizontal.altitude in MountAlignmentSuggestions.MINIMUM_ALTITUDE..MountAlignmentSuggestions.MAXIMUM_ALTITUDE)
            assertTrue(kotlin.math.abs(candidate.j2000.declination) <= MountAlignmentSuggestions.MAXIMUM_DECLINATION)
        }
        assertEquals(candidates.size, candidates.map { it.obj.id }.toSet().size)
    }

    @Test
    fun theFirstStarIsABrightOneHighInTheSky() {
        val first = MountAlignmentSuggestions.suggest(candidates, emptyList())
        assertEquals(MountAlignmentSuggestions.COUNT, first.size)
        assertTrue(first.all { it.separationDegrees == null })
        val brightest = candidates.minOf { it.magnitude }
        assertTrue(first.first().candidate.magnitude <= brightest + 1.5, "${first.first().candidate.obj.name}")
    }

    @Test
    fun laterStarsAreFarFromTheOnesAlreadyUsed() {
        val first = MountAlignmentSuggestions.suggest(candidates, emptyList()).first().candidate
        val second = MountAlignmentSuggestions.suggest(candidates, listOf(first.j2000))
        assertTrue(second.isNotEmpty())
        for (suggestion in second) {
            assertTrue(suggestion.separationDegrees!! >= MountAlignmentSuggestions.MINIMUM_SEPARATION, suggestion.candidate.obj.name)
            assertTrue(suggestion.candidate.obj.id != first.obj.id)
        }
        // With two stars used, the next ones are far from both.
        val third = MountAlignmentSuggestions.suggest(candidates, listOf(first.j2000, second.first().candidate.j2000))
        for (suggestion in third) {
            val separations = listOf(first, second.first().candidate).map { it.j2000.angularSeparation(suggestion.candidate.j2000) }
            assertTrue(separations.all { it >= MountAlignmentSuggestions.MINIMUM_SEPARATION })
        }
    }

    @Test
    fun theListIsEmptyWhenNothingIsFarEnough() {
        assertEquals(emptyList(), MountAlignmentSuggestions.suggest(emptyList(), emptyList()))
        val one = candidates.take(1)
        assertEquals(emptyList(), MountAlignmentSuggestions.suggest(one, listOf(one.single().j2000)))
    }

    @Test
    fun theHomePositionTextsNameBothArrangements() {
        for (mode in SkyWatcherMountMode.entries) {
            assertTrue(mountHomeSteps(mode).length > 60)
            assertTrue(mountArrangementTitle(mode).isNotBlank())
            assertTrue(mountHomeDescription(mode).startsWith("Picture of the"))
        }
        assertTrue(mountHomeSteps(SkyWatcherMountMode.ALT_AZIMUTH).contains("north"))
        assertTrue(mountHomeSteps(SkyWatcherMountMode.EQUATORIAL).contains("counterweight"))
    }
}
