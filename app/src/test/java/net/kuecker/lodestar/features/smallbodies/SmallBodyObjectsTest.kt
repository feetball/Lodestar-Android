package net.kuecker.lodestar.features.smallbodies

import net.kuecker.lodestar.features.mosaic.MosaicPlanOutcome
import net.kuecker.lodestar.features.mosaic.MosaicPlanning
import net.kuecker.lodestar.features.mosaic.MosaicPlanningInputs
import net.kuecker.lodestar.features.mosaic.MosaicSetup
import net.kuecker.lodestar.features.objects.ObjectDetailCalculator
import net.kuecker.lodestar.features.objects.ObjectDetailDistance
import net.kuecker.lodestar.features.objects.ObjectSkyPosition
import net.kuecker.lodestar.features.objects.ObjectsCatalogIndex
import net.kuecker.lodestar.features.objects.ObjectsFormat
import net.kuecker.lodestar.features.objects.ObjectsGrouping
import net.kuecker.lodestar.features.objects.ObjectsListBuilder
import net.kuecker.lodestar.features.objects.ObjectsListOptions
import net.kuecker.lodestar.features.objects.ObjectsListSection
import net.kuecker.lodestar.features.objects.ObjectsPositionCalculator
import net.kuecker.lodestar.features.objects.ObjectsSortOrder
import net.kuecker.lodestar.features.smallbodies.SmallBodyFixtures as Fixtures
import net.kuecker.lodestar.kit.catalog.CatalogSortOrder
import net.kuecker.lodestar.kit.catalog.ObjectCategory
import net.kuecker.lodestar.kit.catalog.ObjectGroup
import net.kuecker.lodestar.kit.phototools.LensSetup
import net.kuecker.lodestar.kit.phototools.SensorFormat
import net.kuecker.lodestar.model.LocationSource
import net.kuecker.lodestar.model.ObserverContext
import java.time.Instant
import java.time.ZoneId
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SmallBodyObjectsTest {
    private val catalog = Fixtures.catalog()
    private val index: ObjectsCatalogIndex by lazy { ObjectsCatalogIndex(catalog) }
    private val observer = Fixtures.observer
    private val date = Fixtures.date

    @BeforeTest
    fun locale() = Fixtures.useUSLocale()

    private fun sections(
        options: ObjectsListOptions = ObjectsListOptions(),
        favorites: List<String> = emptyList(),
        positions: Map<String, ObjectSkyPosition> = emptyMap(),
    ) = ObjectsListBuilder.sections(options, catalog, index, favorites, emptyList(), positions)

    private fun position(magnitude: Double?, smallBody: SmallBodyInfo? = null) = ObjectSkyPosition(
        altitude = 30.0, azimuth = 120.0, nextRise = null, neverRises = false, magnitude = magnitude, computedAt = date,
        smallBody = smallBody,
    )

    // The Solar System section splits

    @Test
    fun theSolarSystemSplitsIntoPlanetsCometsAndAsteroids() {
        val list = sections()
        val titles = list.map { it.title }
        assertEquals(listOf("Solar System", "Comets", "Asteroids"), titles.take(3))
        val system = list[0]
        assertEquals(ObjectsListSection.Kind.Group(ObjectGroup.SOLAR_SYSTEM), system.kind)
        assertTrue(system.objects.all { it.smallBody == null })
        assertEquals(9, system.objects.size)
        val comets = list[1]
        assertEquals(ObjectsListSection.Kind.SmallBodies(ObjectCategory.COMET), comets.kind)
        assertEquals("category.comet", comets.id)
        assertTrue(comets.objects.isNotEmpty() && comets.objects.all { it.category == ObjectCategory.COMET })
        val asteroids = list[2]
        assertEquals("category.asteroid", asteroids.id)
        assertTrue(asteroids.objects.isNotEmpty() && asteroids.objects.all { it.category == ObjectCategory.ASTEROID })
        // Only the listed ones are in the browse list: the faint Encke and YR4 are found by search only.
        assertTrue(comets.objects.none { it.id == Fixtures.encke.id })
        assertTrue(asteroids.objects.none { it.id == Fixtures.yr4.id })
        assertTrue(comets.objects.any { it.id == Fixtures.tsuchinshanAtlas.id })
        assertTrue(asteroids.objects.any { it.id == Fixtures.vesta.id })
        assertTrue(list.drop(3).none { it.kind is ObjectsListSection.Kind.SmallBodies })
        // Each section is in name order, like every section.
        assertEquals(catalog.sorted(comets.objects, CatalogSortOrder.NAME), comets.objects)
    }

    @Test
    fun otherGroupingsKeepThemTogetherWithTheRest() {
        val none = sections(ObjectsListOptions(grouping = ObjectsGrouping.NONE))
        assertEquals(1, none.size)
        assertTrue(none[0].objects.any { it.id == Fixtures.tsuchinshanAtlas.id })
        val byProminence = sections(ObjectsListOptions(grouping = ObjectsGrouping.PROMINENCE))
        assertTrue(byProminence.none { it.kind is ObjectsListSection.Kind.SmallBodies })
        val showpieces = byProminence.first().objects
        assertTrue(showpieces.any { it.id == Fixtures.tsuchinshanAtlas.id }, "a magnitude 3.7 peak is a showpiece")
    }

    @Test
    fun theSectionsHaveTitlesAndIcons() {
        val comets = ObjectsListSection(ObjectsListSection.Kind.SmallBodies(ObjectCategory.COMET), emptyList())
        assertEquals("Comets", comets.title)
        assertEquals(ObjectsListSection(ObjectsListSection.Kind.SmallBodies(ObjectCategory.ASTEROID), emptyList()).title, "Asteroids")
        assertNotNull(comets.icon)
    }

    // Sorting, filters and search

    @Test
    fun brightnessOrdersCometsByTheirCurrentMagnitude() {
        val comets = catalog.smallBodies.filter { it.category == ObjectCategory.COMET && it.isListed }
        val positions = ObjectsPositionCalculator.positions(comets, observer, date)
        val list = sections(ObjectsListOptions(sortOrder = ObjectsSortOrder.BRIGHTNESS), positions = positions)
        val section = list.first { it.kind == ObjectsListSection.Kind.SmallBodies(ObjectCategory.COMET) }
        val magnitudes = section.objects.map { assertNotNull(positions[it.id]).magnitude ?: Double.POSITIVE_INFINITY }
        assertEquals(magnitudes.sorted(), magnitudes)
        assertTrue(magnitudes.distinct().size > 1)
    }

    @Test
    fun theFaintFilterUsesTheCurrentMagnitude() {
        val comets = catalog.smallBodies.filter { it.category == ObjectCategory.COMET && it.isListed }
        val bright = comets.first { it.id == Fixtures.tsuchinshanAtlas.id }
        val dim = comets.first { it.id == Fixtures.ponsBrooks.id }
        val positions = mapOf(bright.id to position(8.0), dim.id to position(12.5))
        val hidden = sections(ObjectsListOptions(hidesFaintObjects = true), positions = positions)
        val ids = hidden.flatMap { it.objects }.map { it.id }
        assertTrue(bright.id in ids)
        assertFalse(dim.id in ids)
        // Without a magnitude yet, nothing is hidden.
        val unknown = sections(ObjectsListOptions(hidesFaintObjects = true), positions = mapOf(dim.id to position(null)))
        assertTrue(dim.id in unknown.flatMap { it.objects }.map { it.id })
    }

    @Test
    fun searchFindsThemByNameAndDesignation() {
        for ((query, id) in listOf(
            "Tsuchinshan" to Fixtures.tsuchinshanAtlas.id, "C/2023 A3" to Fixtures.tsuchinshanAtlas.id,
            "pons" to Fixtures.ponsBrooks.id, "12P" to Fixtures.ponsBrooks.id, "vesta" to Fixtures.vesta.id,
            "yr4" to Fixtures.yr4.id,
        )) {
            assertEquals(id, catalog.search(query, 5).firstOrNull()?.obj?.id, query)
        }
    }

    // Favourites

    @Test
    fun aFavoriteCometThatDroppedOutStaysAsNoCurrentOrbit() {
        val favorites = listOf("comet.C/1999 X9", "planet.mars", Fixtures.tsuchinshanAtlas.id, "asteroid.99999", "comet.C/1999 X9")
        val list = sections(favorites = favorites)
        val section = list.first()
        assertEquals(ObjectsListSection.Kind.Favorites, section.kind)
        assertEquals(setOf("planet.mars", Fixtures.tsuchinshanAtlas.id), section.objects.map { it.id }.toSet())
        assertEquals(listOf("comet.C/1999 X9", "asteroid.99999"), section.unavailable)
        // A star that is not in the catalog is not a comet: it vanishes as before.
        assertEquals(emptyList(), sections(favorites = listOf("dso.GONE")).filter { it.kind == ObjectsListSection.Kind.Favorites })
    }

    @Test
    fun aFavoritesSectionOfOnlyLostCometsStillShows() {
        val section = sections(favorites = listOf("comet.C/1999 X9")).first()
        assertEquals(ObjectsListSection.Kind.Favorites, section.kind)
        assertTrue(section.objects.isEmpty())
        assertEquals(listOf("comet.C/1999 X9"), section.unavailable)
    }

    @Test
    fun filtersHideTheLostFavoritesAsTheyHideTheRest() {
        val favorites = listOf("comet.C/1999 X9", "planet.mars")
        val positions = mapOf("planet.mars" to position(-1.0))
        assertTrue(
            sections(ObjectsListOptions(showsOnlyAboveHorizon = true), favorites, positions).first().unavailable.isEmpty(),
        )
        assertTrue(sections(ObjectsListOptions(showsOnlyPopular = true), favorites).first().unavailable.isEmpty())
        assertEquals(listOf("comet.C/1999 X9"), sections(ObjectsListOptions(), favorites).first().unavailable)
    }

    @Test
    fun theLostFavoriteHasAnameAKindAndWords() {
        assertEquals("C/1999 X9", SmallBodyFavorites.name("comet.C/1999 X9"))
        assertEquals("12P", SmallBodyFavorites.name("comet.12P"))
        assertEquals(ObjectCategory.COMET, SmallBodyFavorites.category("comet.12P"))
        assertEquals(ObjectCategory.ASTEROID, SmallBodyFavorites.category("asteroid.4"))
        assertEquals("Comet · no current orbit", SmallBodyFavorites.subtitle("comet.12P"))
        assertEquals("Asteroid · no current orbit", SmallBodyFavorites.subtitle("asteroid.4"))
        assertEquals("12P, Comet, no current orbit, Favorite", SmallBodyFavorites.spoken("comet.12P"))
        assertEquals(
            listOf("comet.12P"),
            SmallBodyFavorites.withoutOrbit(listOf("planet.mars", "comet.12P", "comet.12P"), Fixtures.bundled),
        )
        assertEquals(emptyList(), SmallBodyFavorites.withoutOrbit(listOf(Fixtures.ponsBrooks.id), catalog))
    }

    // Rows

    @Test
    fun theRowsGetTheNumbersOfTheMoment() {
        val comet = Fixtures.obj(Fixtures.tsuchinshanAtlas)
        val positions = ObjectsPositionCalculator.positions(listOf(comet, Fixtures.obj(Fixtures.longGone)), observer, date)
        val cometPosition = assertNotNull(positions[comet.id])
        val info = assertNotNull(cometPosition.smallBody)
        assertEquals(info.magnitude, cometPosition.magnitude)
        assertEquals(SmallBodyInfo.compute(Fixtures.tsuchinshanAtlas, date), info)
        val subtitle = ObjectsFormat.subtitle(comet, cometPosition.magnitude, position = cometPosition)
        assertTrue(subtitle.startsWith("Comet · mag ≈ "), subtitle)
        assertTrue(subtitle.contains("° from the Sun"), subtitle)
        // Before the first computation only the kind is shown.
        assertEquals("Comet", ObjectsFormat.subtitle(comet, null))
        // An orbit that cannot be evaluated says so.
        val gone = Fixtures.obj(Fixtures.longGone)
        val gonePosition = assertNotNull(positions[gone.id])
        assertNull(gonePosition.smallBody)
        assertEquals("Comet · no current orbit", ObjectsFormat.subtitle(gone, gonePosition.magnitude, position = gonePosition))
    }

    @Test
    fun otherObjectsKeepTheirRows() {
        val mars = assertNotNull(Fixtures.bundled.objectWithID("planet.mars"))
        assertEquals("Planet · mag −1.5", ObjectsFormat.subtitle(mars, -1.5))
        val sirius = Fixtures.bundled.search("Sirius", 1).first().obj
        assertTrue(ObjectsFormat.subtitle(sirius, null).startsWith("Star"))
    }

    @Test
    fun theRowsReadTheCompactNameAndTheDesignation() {
        val comet = Fixtures.obj(Fixtures.tsuchinshanAtlas)
        assertEquals("C/2023 A3", ObjectsFormat.rowDesignations(comet))
        assertEquals("(4)", ObjectsFormat.rowDesignations(Fixtures.obj(Fixtures.vesta)))
        assertNull(ObjectsFormat.rowDesignations(Fixtures.obj(Fixtures.yr4)))
        val spoken = ObjectsFormat.spokenSummary(comet, position(7.0, assertNotNull(SmallBodyInfo.compute(Fixtures.tsuchinshanAtlas, date))), false, ZoneId.of("UTC"))
        assertTrue(spoken.startsWith("Tsuchinshan-ATLAS, C/2023 A3, Comet, magnitude about "), spoken)
    }

    // The detail screen

    @Test
    fun theDetailGetsDistancesElongationPhaseAndMagnitude() {
        val comet = Fixtures.obj(Fixtures.tsuchinshanAtlas)
        val current = ObjectDetailCalculator.current(comet, observer, date)
        assertTrue(current.isSmallBody)
        val detail = assertNotNull(current.smallBody)
        assertEquals(detail.info.magnitude, current.magnitude)
        val distance = assertIs<ObjectDetailDistance.AstronomicalUnits>(current.distance)
        assertEquals(detail.info.earthDistanceAU, distance.value)
        assertNull(current.illuminatedFraction)
        assertNull(current.moonPhase)
        assertNull(current.angularSize)
        assertNotNull(detail.tail)
        assertTrue(detail.info.sunDistanceAU > 0 && detail.info.elongation in 0.0..180.0 && detail.info.phaseAngle in 0.0..180.0)
    }

    @Test
    fun anAsteroidHasNoTailAndABodyWithoutParametersNoBrightness() {
        val vesta = ObjectDetailCalculator.current(Fixtures.obj(Fixtures.vesta), observer, date)
        assertNull(assertNotNull(vesta.smallBody).tail)
        val unknown = ObjectDetailCalculator.current(Fixtures.obj(Fixtures.withoutMagnitude), observer, date)
        assertNull(unknown.magnitude)
        assertTrue(assertNotNull(unknown.smallBody).info.hasNoBrightnessData)
    }

    @Test
    fun anOrbitThatCannotBeEvaluatedHasNoDetailOrDistance() {
        val current = ObjectDetailCalculator.current(Fixtures.obj(Fixtures.longGone), observer, date)
        assertTrue(current.isSmallBody)
        assertNull(current.smallBody)
        assertNull(current.distance)
        assertNull(current.magnitude)
        assertFalse(current.isUp)
    }

    @Test
    fun aPlanetIsNoSmallBody() {
        val mars = assertNotNull(Fixtures.bundled.objectWithID("planet.mars"))
        val current = ObjectDetailCalculator.current(mars, observer, date)
        assertFalse(current.isSmallBody)
        assertNull(current.smallBody)
        assertIs<ObjectDetailDistance.AstronomicalUnits>(current.distance)
        assertNotNull(current.illuminatedFraction)
    }

    @Test
    fun theNightHasRiseTransitSetAndAnAltitudeCurve() {
        val context = ObserverContext(observer, ZoneId.of("America/Denver"), "Ridge", LocationSource.Device)
        for (body in listOf(Fixtures.oppositionComet(), Fixtures.vesta, Fixtures.tsuchinshanAtlas)) {
            val night = ObjectDetailCalculator.night(Fixtures.obj(body), context, date)
            assertTrue(night.samples.size > 20, body.id)
            assertTrue(night.samples.none { it.altitude.isNaN() }, body.id)
        }
        val comet = ObjectDetailCalculator.night(Fixtures.obj(Fixtures.oppositionComet()), context, date)
        assertEquals(net.kuecker.lodestar.kit.ephemeris.RiseTransitSet.Circumstance.NORMAL, comet.events.circumstance)
        assertNotNull(comet.events.rise)
        assertNotNull(comet.events.transit)
        assertNotNull(comet.events.set)
        val peak = assertNotNull(comet.bestInDarkness)
        assertTrue(peak.altitude > 40.0, "${peak.altitude}")
    }

    // The mosaic

    private fun lens() = LensSetup(SensorFormat.fullFrame, 50.0, 2.8, 4.3)

    @Test
    fun aMosaicIsRefusedForAMovingTarget() {
        val comet = Fixtures.obj(Fixtures.ponsBrooks)
        assertEquals(SmallBodyText.mosaicRefusal(comet), MosaicPlanning.targetProblem(comet))
        assertEquals(SmallBodyText.mosaicRefusal(Fixtures.obj(Fixtures.vesta)), MosaicPlanning.targetProblem(Fixtures.obj(Fixtures.vesta)))
        val outcome = MosaicPlanning.plan(MosaicPlanningInputs(MosaicSetup(), comet, lens(), observer, null, date))
        assertEquals(SmallBodyText.mosaicRefusal(comet), assertIs<MosaicPlanOutcome.Problem>(outcome).message)
        // The planets keep the old words.
        val moon = assertNotNull(Fixtures.bundled.objectWithID("moon"))
        assertTrue(assertNotNull(MosaicPlanning.targetProblem(moon)).contains("moves across the sky"))
    }

    @Test
    fun aCustomMosaicStillWorksWhereTheCameraPoints() {
        val comet = Fixtures.obj(Fixtures.ponsBrooks)
        val camera = net.kuecker.lodestar.kit.foundation.HorizontalCoordinate(180.0, 40.0)
        val setup = MosaicSetup(source = net.kuecker.lodestar.features.mosaic.MosaicSource.CUSTOM)
        val outcome = MosaicPlanning.plan(MosaicPlanningInputs(setup, comet, lens(), observer, camera, date))
        assertIs<MosaicPlanOutcome.Ready>(outcome)
    }
}
