package net.kuecker.lodestar.kit.planning

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import net.kuecker.lodestar.kit.catalog.CatalogFixture
import net.kuecker.lodestar.kit.catalog.CelestialObject
import net.kuecker.lodestar.kit.catalog.DeepSkyCatalogue
import net.kuecker.lodestar.kit.catalog.ObjectCategory
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Moon's effect on a target: the rule the apps used, and how each imaging filter relaxes it. */
class PlanningMoonImpactTests {
    private val allKinds = ObjectCategory.entries
    private val emissionKinds = listOf(
        ObjectCategory.EMISSION_NEBULA, ObjectCategory.PLANETARY_NEBULA, ObjectCategory.SUPERNOVA_REMNANT,
        ObjectCategory.CLUSTER_WITH_NEBULA,
    )
    private val otherKinds = allKinds.filter { it !in emissionKinds }

    /** The rule of thumb as the apps had it before it moved into the engine, kept here as the reference. */
    private fun legacyLevel(moonAltitude: Double, separation: Double, illuminatedFraction: Double): MoonImpact.Level {
        if (!(moonAltitude > 0)) return MoonImpact.Level.MOON_DOWN
        val proximity = min(max(1 - separation / 120, 0.25), 1.0)
        val effect = illuminatedFraction * proximity
        return when {
            effect >= 0.5 -> MoonImpact.Level.SEVERE
            effect >= 0.2 -> MoonImpact.Level.MODERATE
            else -> MoonImpact.Level.MINOR
        }
    }

    private fun assess(
        separation: Double,
        illuminatedFraction: Double,
        filter: ImagingFilter,
        kind: ObjectCategory = ObjectCategory.EMISSION_NEBULA,
        moonAltitude: Double = 30.0,
    ): MoonImpact.Level = MoonImpact.assess(moonAltitude, separation, illuminatedFraction, filter, kind)

    private val separations = (0..72).map { it * 2.5 }
    private val fractions = (0..40).map { it * 0.025 }

    // MARK: Without a filter

    @Test
    fun testNoFilterEqualsTheOldAppRuleOnAGrid() {
        var checked = 0
        for (altitude in listOf(-30.0, -0.5, 0.0, 1e-9, 0.0001, 10.0, 60.0, 90.0)) {
            for (separation in separations) {
                for (fraction in fractions) {
                    val expected = legacyLevel(altitude, separation, fraction)
                    for (kind in allKinds) {
                        assertEquals(
                            expected, MoonImpact.assess(altitude, separation, fraction, ImagingFilter.NONE, kind),
                            "altitude $altitude separation $separation fraction $fraction ${kind.rawValue}",
                        )
                        checked += 1
                    }
                }
            }
        }
        assertEquals(8 * 73 * 41 * allKinds.size, checked)
    }

    @Test
    fun testBroadbandThresholdsSitExactlyOnTheOldCutOffs() {
        // 1 × (1 − 60/120) = 0.5 exactly: severe. Just under it: moderate.
        assertEquals(MoonImpact.Level.SEVERE, assess(60.0, 1.0, ImagingFilter.NONE))
        assertEquals(MoonImpact.Level.MODERATE, assess(60.0, 0.9999, ImagingFilter.NONE))
        // 0.2 × 1 = 0.2 exactly: moderate. Just under it: minor.
        assertEquals(MoonImpact.Level.MODERATE, assess(0.0, 0.2, ImagingFilter.NONE))
        assertEquals(MoonImpact.Level.MINOR, assess(0.0, 0.1999, ImagingFilter.NONE))
        // The proximity stops falling at 0.25 from 90° on: a full Moon is moderate anywhere in the sky.
        assertEquals(MoonImpact.Level.MODERATE, assess(90.0, 1.0, ImagingFilter.NONE))
        assertEquals(MoonImpact.Level.MODERATE, assess(180.0, 1.0, ImagingFilter.NONE))
        assertEquals(MoonImpact.Level.MINOR, assess(180.0, 0.7999, ImagingFilter.NONE))
    }

    @Test
    fun testTheMoonOnTheHorizonOrBelowIsDownWhateverTheFilter() {
        for (altitude in listOf(0.0, -0.0, -1.0, -90.0, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)) {
            for (filter in ImagingFilter.entries) {
                for (kind in allKinds) {
                    assertEquals(
                        MoonImpact.Level.MOON_DOWN, MoonImpact.assess(altitude, 10.0, 1.0, filter, kind),
                        "altitude $altitude ${filter.rawValue} ${kind.rawValue}",
                    )
                }
            }
        }
        assertEquals(MoonImpact.Level.SEVERE, assess(10.0, 1.0, ImagingFilter.NONE, moonAltitude = 1e-9))
    }

    @Test
    fun testGeometryThatIsNotFiniteCannotBeJudgedAndIsMinor() {
        for (filter in ImagingFilter.entries) {
            for (kind in allKinds) {
                assertEquals(
                    MoonImpact.Level.MINOR, MoonImpact.assess(30.0, Double.NaN, 1.0, filter, kind),
                    "${filter.rawValue} ${kind.rawValue}",
                )
                assertEquals(MoonImpact.Level.MINOR, MoonImpact.assess(30.0, 10.0, Double.NaN, filter, kind))
                assertEquals(MoonImpact.Level.MINOR, MoonImpact.assess(30.0, Double.POSITIVE_INFINITY, 1.0, filter, kind))
                assertEquals(MoonImpact.Level.MINOR, MoonImpact.assess(30.0, 10.0, Double.NEGATIVE_INFINITY, filter, kind))
            }
        }
    }

    @Test
    fun testValuesOutOfRangeAreLimitedToTheirRange() {
        for (filter in ImagingFilter.entries) {
            assertEquals(assess(0.0, 1.0, filter), assess(-25.0, 1.0, filter))
            assertEquals(assess(180.0, 1.0, filter), assess(400.0, 1.0, filter))
            assertEquals(assess(20.0, 1.0, filter), assess(20.0, 1.7, filter))
            assertEquals(assess(20.0, 0.0, filter), assess(20.0, -0.4, filter))
        }
    }

    // MARK: Which targets benefit

    @Test
    fun testOnlyEmissionLineTargetsBenefit() {
        for (kind in allKinds) {
            assertEquals(kind in emissionKinds, MoonImpact.isEmissionLineTarget(kind), kind.rawValue)
        }
        // The same from the catalog's OpenNGC type codes.
        for (code in listOf("HII", "EmN", "PN", "SNR", "Cl+N")) {
            assertTrue(MoonImpact.isEmissionLineTarget(ObjectCategory.fromOpenNGCType(code)), code)
        }
        for (code in listOf("G", "GPair", "GTrpl", "GGroup", "OCl", "GCl", "RfN", "DrkN", "Neb", "*Ass", "**", "*", "Other", "Nova")) {
            assertFalse(MoonImpact.isEmissionLineTarget(ObjectCategory.fromOpenNGCType(code)), code)
        }
    }

    @Test
    fun testGenericNebulaeWithASharplessNumberAreEmissionLineTargets() {
        val catalog = CatalogFixture.catalog()
        val generic = catalog.deepSkyObjects.filter { it.category == ObjectCategory.NEBULA }
        val sharpless = generic.filter { DeepSkyCatalogue.SHARPLESS in it.catalogues }
        // The data decides: a generic nebula counts exactly when it has a Sharpless number.
        assertTrue(sharpless.size >= 5)
        assertTrue(sharpless.size < generic.size)
        for (nebula in generic) {
            assertEquals(nebula in sharpless, MoonImpact.isEmissionLineTarget(nebula), nebula.id)
            assertFalse(MoonImpact.isEmissionLineTarget(nebula.category), nebula.id)
        }
        // The Lagoon (Sh2-25) and Trifid (Sh2-30) nebulae are generic nebulae in the catalog and H II regions in the sky;
        // the Iris Nebula (Caldwell 4, no Sharpless number) is a reflection nebula.
        val lagoon = checkNotNull(catalog.objectWithID("dso.M8"))
        val trifid = checkNotNull(catalog.objectWithID("dso.M20"))
        val iris = checkNotNull(catalog.objectWithID("dso.NGC7023"))
        assertEquals(ObjectCategory.NEBULA, lagoon.category)
        assertTrue(MoonImpact.isEmissionLineTarget(lagoon))
        assertTrue(MoonImpact.isEmissionLineTarget(trifid))
        assertFalse(MoonImpact.isEmissionLineTarget(iris))
        // A full-ish Moon 50° away is severe for all three; the narrowband filter relaxes the two H II regions only.
        fun level(target: CelestialObject) =
            assess(50.0, 0.9, ImagingFilter.NARROWBAND, MoonImpact.assessmentKind(target))
        assertEquals(MoonImpact.Level.MINOR, level(lagoon))
        assertEquals(MoonImpact.Level.MINOR, level(trifid))
        assertEquals(MoonImpact.Level.SEVERE, level(iris))
    }

    @Test
    fun testTheAssessmentKindIsTheCategoryExceptForSharplessNebulae() {
        val catalog = CatalogFixture.catalog()
        for (obj in catalog.deepSkyObjects) {
            val isSharplessNebula = obj.category == ObjectCategory.NEBULA && DeepSkyCatalogue.SHARPLESS in obj.catalogues
            assertEquals(
                if (isSharplessNebula) ObjectCategory.EMISSION_NEBULA else obj.category,
                MoonImpact.assessmentKind(obj), obj.id,
            )
            // Whatever the kind, the object overload and the category overload agree on it.
            assertEquals(
                MoonImpact.isEmissionLineTarget(MoonImpact.assessmentKind(obj)), MoonImpact.isEmissionLineTarget(obj), obj.id,
            )
        }
        // Sharpless numbers on other categories change nothing: a reflection nebula stays one.
        val reflection = catalog.deepSkyObjects.filter {
            it.category == ObjectCategory.REFLECTION_NEBULA && DeepSkyCatalogue.SHARPLESS in it.catalogues
        }
        assertTrue(reflection.isNotEmpty())
        for (obj in reflection) assertFalse(MoonImpact.isEmissionLineTarget(obj), obj.id)
    }

    @Test
    fun testTargetsWithoutEmissionLinesAreNeverRelaxed() {
        for (kind in otherKinds) {
            for (filter in ImagingFilter.entries) {
                assertEquals(0, MoonImpact.relaxationSteps(10.0, 1.0, filter, kind), "${filter.rawValue} ${kind.rawValue}")
                assertEquals(0, MoonImpact.relaxationSteps(120.0, 1.0, filter, kind), "${filter.rawValue} ${kind.rawValue}")
                for (separation in separations) {
                    for (fraction in fractions) {
                        assertEquals(
                            assess(separation, fraction, ImagingFilter.NONE, kind),
                            assess(separation, fraction, filter, kind),
                            "${filter.rawValue} ${kind.rawValue} separation $separation fraction $fraction",
                        )
                    }
                }
            }
        }
    }

    // MARK: Each filter

    @Test
    fun testNoFilterTakesNoStepsOffAnyTarget() {
        for (kind in allKinds) {
            assertEquals(0, MoonImpact.relaxationSteps(10.0, 1.0, ImagingFilter.NONE, kind))
            assertEquals(0, MoonImpact.relaxationSteps(150.0, 0.2, ImagingFilter.NONE, kind))
        }
    }

    @Test
    fun testLightPollutionFilterTakesOneStepOffFromFortyDegrees() {
        val filter = ImagingFilter.LIGHT_POLLUTION
        assertEquals(0, MoonImpact.relaxationSteps(0.0, 1.0, filter, ObjectCategory.EMISSION_NEBULA))
        assertEquals(0, MoonImpact.relaxationSteps(39.999, 1.0, filter, ObjectCategory.EMISSION_NEBULA))
        assertEquals(1, MoonImpact.relaxationSteps(40.0, 1.0, filter, ObjectCategory.EMISSION_NEBULA))
        assertEquals(1, MoonImpact.relaxationSteps(180.0, 1.0, filter, ObjectCategory.EMISSION_NEBULA))
        // A full Moon 39.999° away is severe either way; at 40° the filter makes it moderate.
        assertEquals(MoonImpact.Level.SEVERE, assess(39.999, 1.0, filter))
        assertEquals(MoonImpact.Level.MODERATE, assess(40.0, 1.0, filter))
        assertEquals(MoonImpact.Level.SEVERE, assess(40.0, 1.0, ImagingFilter.NONE))
        // Moderate becomes minor.
        assertEquals(MoonImpact.Level.MODERATE, assess(50.0, 0.5, ImagingFilter.NONE))
        assertEquals(MoonImpact.Level.MINOR, assess(50.0, 0.5, filter))
        assertEquals(MoonImpact.Level.MODERATE, assess(30.0, 0.5, filter))
    }

    @Test
    fun testDualNarrowbandTakesOneStepOffAlwaysAndTwoFromSixtyDegrees() {
        val filter = ImagingFilter.DUAL_NARROWBAND
        assertEquals(1, MoonImpact.relaxationSteps(0.0, 1.0, filter, ObjectCategory.PLANETARY_NEBULA))
        assertEquals(1, MoonImpact.relaxationSteps(59.999, 1.0, filter, ObjectCategory.PLANETARY_NEBULA))
        assertEquals(2, MoonImpact.relaxationSteps(60.0, 1.0, filter, ObjectCategory.PLANETARY_NEBULA))
        assertEquals(2, MoonImpact.relaxationSteps(180.0, 0.1, filter, ObjectCategory.PLANETARY_NEBULA))
        // A full Moon is severe up to and including 60°: one step less before 60°, two steps from it.
        assertEquals(MoonImpact.Level.MODERATE, assess(5.0, 1.0, filter))
        assertEquals(MoonImpact.Level.MODERATE, assess(59.999, 1.0, filter))
        assertEquals(MoonImpact.Level.MINOR, assess(60.0, 1.0, filter))
        assertEquals(MoonImpact.Level.SEVERE, assess(59.999, 1.0, ImagingFilter.NONE))
        // Moderate and minor cannot drop below minor.
        assertEquals(MoonImpact.Level.MINOR, assess(70.0, 0.9, filter))
        assertEquals(MoonImpact.Level.MINOR, assess(70.0, 0.1, filter))
    }

    @Test
    fun testNarrowbandTakesTwoStepsOffUnlessTheMoonIsCloseAndBright() {
        val filter = ImagingFilter.NARROWBAND
        val kind = ObjectCategory.SUPERNOVA_REMNANT
        assertEquals(2, MoonImpact.relaxationSteps(31.0, 1.0, filter, kind))
        assertEquals(2, MoonImpact.relaxationSteps(30.001, 1.0, filter, kind))
        assertEquals(1, MoonImpact.relaxationSteps(30.0, 1.0, filter, kind))
        assertEquals(1, MoonImpact.relaxationSteps(0.0, 0.5, filter, kind))
        assertEquals(2, MoonImpact.relaxationSteps(30.0, 0.4999, filter, kind))
        assertEquals(2, MoonImpact.relaxationSteps(0.0, 0.0, filter, kind))
        assertEquals(2, MoonImpact.relaxationSteps(180.0, 1.0, filter, kind))
        // A full Moon is severe at 30° and 31°: within 30° it goes down to moderate, beyond it to minor.
        assertEquals(MoonImpact.Level.SEVERE, assess(30.0, 1.0, ImagingFilter.NONE, kind))
        assertEquals(MoonImpact.Level.MODERATE, assess(30.0, 1.0, filter, kind))
        assertEquals(MoonImpact.Level.MINOR, assess(30.001, 1.0, filter, kind))
        assertEquals(MoonImpact.Level.MINOR, assess(31.0, 1.0, filter, kind))
        // A 60 % lit Moon at 5° is severe, and the filter makes it moderate; half lit at 30° it is moderate, then minor.
        assertEquals(MoonImpact.Level.SEVERE, assess(5.0, 0.6, ImagingFilter.NONE, kind))
        assertEquals(MoonImpact.Level.MODERATE, assess(5.0, 0.6, filter, kind))
        assertEquals(MoonImpact.Level.MINOR, assess(30.0, 0.5, filter, kind))
    }

    @Test
    fun testAStrongerFilterNeverTakesFewerSteps() {
        val order = listOf(
            ImagingFilter.NONE, ImagingFilter.LIGHT_POLLUTION, ImagingFilter.DUAL_NARROWBAND, ImagingFilter.NARROWBAND,
        )
        for (kind in emissionKinds) {
            for (separation in separations) {
                for (fraction in fractions) {
                    val steps = order.map { MoonImpact.relaxationSteps(separation, fraction, it, kind) }
                    assertEquals(steps.sorted(), steps, "${kind.rawValue} separation $separation fraction $fraction")
                    assertTrue(steps.all { it in 0..2 })
                }
            }
        }
    }

    @Test
    fun testFiltersOnlyEverLowerTheLevelAndLeaveTheMoonBeingDown() {
        val rank = MoonImpact.Level.entries.withIndex().associate { it.value to it.index }
        for (kind in emissionKinds) {
            for (separation in separations) {
                for (fraction in fractions) {
                    val broadband = assess(separation, fraction, ImagingFilter.NONE, kind)
                    for (filter in ImagingFilter.entries) {
                        val level = assess(separation, fraction, filter, kind)
                        assertTrue(rank.getValue(level) <= rank.getValue(broadband), "${filter.rawValue} $separation $fraction")
                        assertTrue(level != MoonImpact.Level.MOON_DOWN)
                        // Relaxing never goes below minor.
                        assertTrue(rank.getValue(level) >= rank.getValue(MoonImpact.Level.MINOR))
                    }
                }
            }
        }
    }

    // MARK: Levels and weights

    @Test
    fun testLoweringStopsAtMinorAndNeverTouchesMoonDown() {
        assertEquals(MoonImpact.Level.MODERATE, MoonImpact.Level.SEVERE.lowered(1))
        assertEquals(MoonImpact.Level.MINOR, MoonImpact.Level.SEVERE.lowered(2))
        assertEquals(MoonImpact.Level.MINOR, MoonImpact.Level.SEVERE.lowered(9))
        assertEquals(MoonImpact.Level.MINOR, MoonImpact.Level.MODERATE.lowered(1))
        assertEquals(MoonImpact.Level.MINOR, MoonImpact.Level.MINOR.lowered(1))
        assertEquals(MoonImpact.Level.MOON_DOWN, MoonImpact.Level.MOON_DOWN.lowered(2))
        assertEquals(MoonImpact.Level.SEVERE, MoonImpact.Level.SEVERE.lowered(0))
        assertEquals(MoonImpact.Level.MODERATE, MoonImpact.Level.MODERATE.lowered(-3))
    }

    @Test
    fun testTheMoonFreeWeightFallsWithTheLevel() {
        val weights = listOf(
            MoonImpact.Level.MOON_DOWN, MoonImpact.Level.MINOR, MoonImpact.Level.MODERATE, MoonImpact.Level.SEVERE,
        ).map { MoonImpact.moonFreeWeight(it) }
        assertEquals(listOf(1.0, 0.9, 0.7, 0.4), weights)
        assertEquals(weights.sortedDescending(), weights)
        assertTrue(weights.all { it > 0 && it <= 1 })
    }

    @Test
    fun testAFilterRaisesTheWeightOfAnEmissionTargetUnderABrightMoonAndNobodyElses() {
        // A bright Moon 20° away: severe for everything without a filter.
        val none = MoonImpact.moonFreeWeight(assess(20.0, 1.0, ImagingFilter.NONE))
        assertEquals(0.4, none)
        assertEquals(0.7, MoonImpact.moonFreeWeight(assess(20.0, 1.0, ImagingFilter.NARROWBAND)))
        assertEquals(0.4, MoonImpact.moonFreeWeight(assess(20.0, 1.0, ImagingFilter.LIGHT_POLLUTION)))
        for (kind in otherKinds) {
            for (filter in ImagingFilter.entries) {
                assertEquals(0.4, MoonImpact.moonFreeWeight(assess(20.0, 1.0, filter, kind)), "${filter.rawValue} ${kind.rawValue}")
            }
        }
        // 80° away the narrowband filter takes the target to minor.
        assertEquals(0.9, MoonImpact.moonFreeWeight(assess(80.0, 1.0, ImagingFilter.NARROWBAND)))
        assertEquals(0.7, MoonImpact.moonFreeWeight(assess(80.0, 1.0, ImagingFilter.NONE)))
    }

    // MARK: Names and storage

    @Test
    fun testTheFiltersHaveStableRawValuesAndReadableText() {
        assertEquals(
            listOf("none", "lightPollution", "dualNarrowband", "narrowband"),
            ImagingFilter.entries.map { it.rawValue },
        )
        assertEquals(ImagingFilter.entries.map { it.rawValue }, ImagingFilter.entries.map { it.id })
        for (filter in ImagingFilter.entries) {
            assertEquals(filter, ImagingFilter.fromRawValue(filter.rawValue))
            assertTrue(filter.displayName.isNotBlank())
            assertTrue(filter.summary.isNotBlank())
            assertFalse(filter.summary.contains("\n"))
        }
        assertEquals(ImagingFilter.entries.size, ImagingFilter.entries.map { it.displayName }.toSet().size)
        assertEquals(ImagingFilter.entries.size, ImagingFilter.entries.map { it.summary }.toSet().size)
        assertEquals("None", ImagingFilter.NONE.displayName)
        assertEquals("Dual narrowband", ImagingFilter.DUAL_NARROWBAND.displayName)
        assertNull(ImagingFilter.fromRawValue("Narrowband"))
        assertNull(ImagingFilter.fromRawValue(""))
    }

    @Test
    fun testFiltersAndLevelsStoreAsTheirRawValues() {
        val filters = Json.encodeToString(ListSerializer(ImagingFilter.serializer()), ImagingFilter.entries)
        assertEquals("[\"none\",\"lightPollution\",\"dualNarrowband\",\"narrowband\"]", filters)
        assertEquals(
            listOf(ImagingFilter.DUAL_NARROWBAND, ImagingFilter.NONE),
            Json.decodeFromString(ListSerializer(ImagingFilter.serializer()), "[\"dualNarrowband\",\"none\"]"),
        )
        val levels = Json.encodeToString(ListSerializer(MoonImpact.Level.serializer()), MoonImpact.Level.entries)
        assertEquals("[\"moonDown\",\"minor\",\"moderate\",\"severe\"]", levels)
        for (level in MoonImpact.Level.entries) {
            assertEquals(level, MoonImpact.Level.fromRawValue(level.rawValue))
        }
        assertNull(MoonImpact.Level.fromRawValue("extreme"))
    }
}
