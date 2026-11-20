package net.kuecker.lodestar.features.objects

import net.kuecker.lodestar.kit.catalog.Catalog
import net.kuecker.lodestar.kit.catalog.ObjectCategory
import net.kuecker.lodestar.kit.ephemeris.SolarSystemBody
import net.kuecker.lodestar.kit.ephemeris.horizontal
import net.kuecker.lodestar.kit.foundation.GeographicLocation
import net.kuecker.lodestar.kit.foundation.Observer
import net.kuecker.lodestar.kit.foundation.SkyFrame
import net.kuecker.lodestar.kit.planning.ImagingFilter
import net.kuecker.lodestar.kit.planning.MoonImpact
import net.kuecker.lodestar.model.LocationSource
import net.kuecker.lodestar.model.ObserverContext
import java.time.Instant
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Moon's effect on an object in the app: the assessment through the user's filter, and its wording. */
class ObjectMoonTest {
    private val catalog: Catalog by lazy { Catalog.loadBundled() }
    private val observer = Observer(checkNotNull(GeographicLocation.validated(40.0, -105.3, 1650.0)))
    private val context = ObserverContext(observer, ZoneId.of("America/Denver"), "Test", LocationSource.Device)

    private fun geometry(altitude: Double, separation: Double, fraction: Double) =
        ObjectMoonGeometry(altitude, separation, fraction)

    /** The rule the app used before the engine took it over. */
    private fun legacy(geometry: ObjectMoonGeometry): MoonImpact.Level {
        if (!(geometry.moonAltitude > 0)) return MoonImpact.Level.MOON_DOWN
        val proximity = min(max(1 - geometry.separation / 120, 0.25), 1.0)
        val effect = geometry.illuminatedFraction * proximity
        return when {
            effect >= 0.5 -> MoonImpact.Level.SEVERE
            effect >= 0.2 -> MoonImpact.Level.MODERATE
            else -> MoonImpact.Level.MINOR
        }
    }

    // MARK: Assessment

    @Test
    fun withoutAFilterTheLevelIsTheOldRuleForEveryKindOfObject() {
        for (altitude in listOf(-20.0, 15.0, 60.0)) {
            for (separation in listOf(0.0, 20.0, 45.0, 60.0, 90.0, 140.0)) {
                for (fraction in listOf(0.0, 0.1, 0.3, 0.6, 1.0)) {
                    val geometry = geometry(altitude, separation, fraction)
                    for (kind in ObjectCategory.entries) {
                        val assessment = geometry.assess(ImagingFilter.NONE, kind)
                        assertEquals(legacy(geometry), assessment.level, "$altitude $separation $fraction ${kind.rawValue}")
                        assertEquals(assessment.level, assessment.broadbandLevel)
                        assertFalse(assessment.isRelaxed)
                    }
                }
            }
        }
    }

    @Test
    fun aNarrowbandFilterRelaxesAnEmissionNebulaButNotAGalaxy() {
        val geometry = geometry(40.0, 50.0, 0.9)
        val nebula = geometry.assess(ImagingFilter.NARROWBAND, ObjectCategory.EMISSION_NEBULA)
        assertEquals(MoonImpact.Level.SEVERE, nebula.broadbandLevel)
        assertEquals(MoonImpact.Level.MINOR, nebula.level)
        assertTrue(nebula.isRelaxed)
        val galaxy = geometry.assess(ImagingFilter.NARROWBAND, ObjectCategory.GALAXY)
        assertEquals(MoonImpact.Level.SEVERE, galaxy.level)
        assertFalse(galaxy.isRelaxed)
    }

    @Test
    fun aMoonBelowTheHorizonIsNeverRelaxed() {
        val assessment = geometry(-5.0, 10.0, 1.0).assess(ImagingFilter.NARROWBAND, ObjectCategory.PLANETARY_NEBULA)
        assertEquals(MoonImpact.Level.MOON_DOWN, assessment.level)
        assertFalse(assessment.isRelaxed)
    }

    @Test
    fun theGeometryMatchesTheSkyAtTheGivenInstant() {
        val m42 = assertNotNull(catalog.objectWithID("dso.M42"))
        val date = Instant.parse("2026-12-20T04:00:00Z")
        val geometry = ObjectMoonGeometry.of(m42, observer, date)
        val moon = SkyFrame(date, observer).horizontal(SolarSystemBody.MOON, false)
        assertEquals(moon.altitude, geometry.moonAltitude, 1e-9)
        assertTrue(geometry.separation in 0.0..180.0)
        assertTrue(geometry.illuminatedFraction in 0.0..1.0)
        // The detail screen's conditions are this geometry, at now while it is dark and else at the best time.
        val night = ObjectDetailCalculator.night(m42, context, date)
        val conditions = assertNotNull(ObjectDetailCalculator.moonConditions(m42, observer, night, date))
        assertEquals(ObjectMoonGeometry.of(m42, observer, conditions.date), conditions.geometry)
        assertEquals(conditions.geometry.separation, conditions.separation)
        assertEquals(conditions.geometry.illuminatedFraction, conditions.illuminatedFraction)
    }

    @Test
    fun theSunAndTheMoonHaveNoMoonConditions() {
        val date = Instant.parse("2026-12-20T04:00:00Z")
        val moon = assertNotNull(catalog.objectWithID("moon"))
        val night = ObjectDetailCalculator.night(moon, context, date)
        assertNull(ObjectDetailCalculator.moonConditions(moon, observer, night, date))
    }

    // MARK: Wording

    @Test
    fun withoutRelaxationTheAdviceIsTheOldSentenceOfTheLevel() {
        fun advice(level: MoonImpact.Level, filter: ImagingFilter = ImagingFilter.NONE) =
            ObjectMoonText.advice(ObjectMoonAssessment(level, level), filter)
        assertEquals("The Moon is down, so the sky stays dark.", advice(MoonImpact.Level.MOON_DOWN))
        assertEquals("The Moon has little effect on this target.", advice(MoonImpact.Level.MINOR))
        assertEquals("Moonlight will brighten the sky around this target.", advice(MoonImpact.Level.MODERATE))
        assertEquals("A bright Moon close by will wash out faint detail.", advice(MoonImpact.Level.SEVERE))
        // A filter that changed nothing is not mentioned.
        assertEquals("A bright Moon close by will wash out faint detail.", advice(MoonImpact.Level.SEVERE, ImagingFilter.NARROWBAND))
    }

    @Test
    fun whenTheFilterRelaxedTheLevelTheAdviceSaysSo() {
        val nebula = ObjectCategory.EMISSION_NEBULA
        assertEquals(
            "Moderate · fine with your narrowband filter",
            ObjectMoonText.advice(geometry(40.0, 50.0, 0.5).assess(ImagingFilter.NARROWBAND, nebula), ImagingFilter.NARROWBAND),
        )
        assertEquals(
            "Severe · fine with your narrowband filter",
            ObjectMoonText.advice(geometry(40.0, 50.0, 0.9).assess(ImagingFilter.NARROWBAND, nebula), ImagingFilter.NARROWBAND),
        )
        assertEquals(
            "Severe · manageable with your dual narrowband filter",
            ObjectMoonText.advice(geometry(40.0, 20.0, 1.0).assess(ImagingFilter.DUAL_NARROWBAND, nebula), ImagingFilter.DUAL_NARROWBAND),
        )
        assertEquals(
            "Moderate · fine with your light-pollution filter",
            ObjectMoonText.advice(geometry(40.0, 50.0, 0.5).assess(ImagingFilter.LIGHT_POLLUTION, nebula), ImagingFilter.LIGHT_POLLUTION),
        )
    }

    @Test
    fun theTonightChipFollowsTheAssessedLevel() {
        fun chip(level: MoonImpact.Level, broadband: MoonImpact.Level = level) =
            ObjectMoonText.chip(ObjectMoonAssessment(level, broadband))
        assertEquals("Moon down", chip(MoonImpact.Level.MOON_DOWN))
        assertEquals("Moon: minor", chip(MoonImpact.Level.MINOR))
        assertEquals("Moon: moderate", chip(MoonImpact.Level.MODERATE))
        assertEquals("Moon: severe", chip(MoonImpact.Level.SEVERE))
        assertEquals("Moon: fine with your filter", chip(MoonImpact.Level.MINOR, MoonImpact.Level.SEVERE))
        assertEquals("Moon: manageable with your filter", chip(MoonImpact.Level.MODERATE, MoonImpact.Level.SEVERE))
    }

    @Test
    fun everyFilterButNoneHasAName() {
        assertEquals("", ObjectMoonText.filterName(ImagingFilter.NONE))
        for (filter in ImagingFilter.entries.filter { it != ImagingFilter.NONE }) {
            assertTrue(ObjectMoonText.filterName(filter).isNotBlank(), filter.rawValue)
        }
    }
}
