package net.kuecker.lodestar.kit.catalog

import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import net.kuecker.lodestar.kit.ephemeris.SolarSystemBody
import net.kuecker.lodestar.kit.foundation.*
import java.lang.reflect.Modifier
import java.text.BreakIterator
import java.time.Instant
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CatalogModelTests {
    // MARK: Categories and groups

    @Test
    fun testCategoryDisplayNames() {
        val names: Map<ObjectCategory, String> = mapOf(
            ObjectCategory.SUN to "Sun", ObjectCategory.MOON to "Moon", ObjectCategory.PLANET to "Planet",
            ObjectCategory.STAR to "Star", ObjectCategory.DOUBLE_STAR to "Double Star",
            ObjectCategory.GALAXY to "Galaxy", ObjectCategory.GALAXY_GROUP to "Galaxy Group",
            ObjectCategory.EMISSION_NEBULA to "Emission Nebula",
            ObjectCategory.REFLECTION_NEBULA to "Reflection Nebula", ObjectCategory.DARK_NEBULA to "Dark Nebula",
            ObjectCategory.NEBULA to "Nebula", ObjectCategory.PLANETARY_NEBULA to "Planetary Nebula",
            ObjectCategory.SUPERNOVA_REMNANT to "Supernova Remnant", ObjectCategory.OPEN_CLUSTER to "Open Cluster",
            ObjectCategory.GLOBULAR_CLUSTER to "Globular Cluster",
            ObjectCategory.CLUSTER_WITH_NEBULA to "Cluster with Nebula",
            ObjectCategory.STAR_ASSOCIATION to "Star Association", ObjectCategory.ASTERISM to "Asterism",
            ObjectCategory.MILKY_WAY to "Milky Way", ObjectCategory.CELESTIAL_POLE to "Celestial Pole",
            ObjectCategory.CUSTOM to "Custom Target", ObjectCategory.OTHER to "Other",
        )
        assertEquals(22, ObjectCategory.entries.size)
        assertEquals(ObjectCategory.entries.toSet(), names.keys)
        for ((category, name) in names) {
            assertEquals(name, category.displayName)
        }
        assertEquals(22, ObjectCategory.entries.map { it.displayName }.toSet().size, "display names are unique")
    }

    @Test
    fun testCategorySymbolNamesLookLikeSFSymbols() {
        for (category in ObjectCategory.entries) {
            val name = category.systemImageName
            assertFalse(name.isEmpty())
            assertTrue(name.all { (it in 'a'..'z') || (it in '0'..'9') || it == '.' }, "$category: $name")
            assertFalse(name.startsWith(".") || name.endsWith(".") || name.contains(".."), name)
        }
        for (group in ObjectGroup.entries) {
            assertFalse(group.systemImageName.isEmpty())
        }
        assertEquals(ObjectGroup.entries.size, ObjectGroup.entries.map { it.systemImageName }.toSet().size)
        // Every symbol was looked up in Apple's symbol availability data (SF Symbols 1.0 to 3.0: iOS 13 to 15), so
        // each is available on iOS 17. A new name has to be verified the same way and added here.
        val verified = setOf(
            "sun.max", "moon", "circle.circle", "star", "star.leadinghalf.filled", "hurricane", "circle.hexagongrid",
            "cloud.fill", "cloud.sun", "cloud.fog.fill", "cloud", "circle.dotted", "burst", "sparkles", "aqi.medium",
            "cloud.moon", "aqi.low", "star.circle", "moon.stars", "scope", "plus.circle", "sparkle",
        )
        val used = ObjectCategory.entries.map { it.systemImageName }.toSet() + ObjectGroup.entries.map { it.systemImageName }
        assertEquals(emptySet(), used - verified)
        assertEquals(emptySet(), verified - used, "the list names only symbols that are used")
    }

    @Test
    fun testCategoryGroups() {
        val groups: Map<ObjectGroup, List<ObjectCategory>> = mapOf(
            ObjectGroup.SOLAR_SYSTEM to listOf(ObjectCategory.SUN, ObjectCategory.MOON, ObjectCategory.PLANET),
            ObjectGroup.STARS to listOf(ObjectCategory.STAR, ObjectCategory.DOUBLE_STAR, ObjectCategory.ASTERISM),
            ObjectGroup.GALAXIES to listOf(ObjectCategory.GALAXY, ObjectCategory.GALAXY_GROUP),
            ObjectGroup.NEBULAE to listOf(
                ObjectCategory.EMISSION_NEBULA, ObjectCategory.REFLECTION_NEBULA, ObjectCategory.DARK_NEBULA,
                ObjectCategory.NEBULA, ObjectCategory.PLANETARY_NEBULA, ObjectCategory.SUPERNOVA_REMNANT,
                ObjectCategory.CLUSTER_WITH_NEBULA,
            ),
            ObjectGroup.CLUSTERS to listOf(
                ObjectCategory.OPEN_CLUSTER, ObjectCategory.GLOBULAR_CLUSTER, ObjectCategory.STAR_ASSOCIATION,
            ),
            ObjectGroup.SPECIAL to listOf(
                ObjectCategory.MILKY_WAY, ObjectCategory.CELESTIAL_POLE, ObjectCategory.CUSTOM, ObjectCategory.OTHER,
            ),
        )
        for ((group, categories) in groups) {
            assertEquals(categories.toSet(), group.categories.toSet(), "$group")
            for (category in categories) {
                assertEquals(group, category.group, "$category")
            }
        }
        assertEquals(ObjectCategory.entries.toSet(), groups.values.flatten().toSet())
        assertEquals(ObjectCategory.entries.size, groups.values.map { it.size }.sum(), "no category in two groups")
    }

    @Test
    fun testGroupDisplayNames() {
        assertEquals(
            listOf(
                ObjectGroup.SOLAR_SYSTEM, ObjectGroup.STARS, ObjectGroup.GALAXIES, ObjectGroup.NEBULAE,
                ObjectGroup.CLUSTERS, ObjectGroup.SPECIAL,
            ),
            ObjectGroup.entries,
        )
        assertEquals(
            listOf("Solar System", "Stars", "Galaxies", "Nebulae", "Star Clusters", "Special"),
            ObjectGroup.entries.map { it.displayName },
        )
    }

    @Test
    fun testCategoryAndGroupCodingUseTheCaseNames() {
        for (category in ObjectCategory.entries) {
            val data = Json.encodeToString(ListSerializer(ObjectCategory.serializer()), listOf(category))
            assertEquals("[\"${category.rawValue}\"]", data)
            assertEquals(listOf(category), Json.decodeFromString(ListSerializer(ObjectCategory.serializer()), data))
        }
        assertEquals(ObjectCategory.DOUBLE_STAR, ObjectCategory.fromRawValue("doubleStar"))
        assertNull(ObjectCategory.fromRawValue("DoubleStar"))
        assertEquals(
            listOf(ObjectGroup.SOLAR_SYSTEM, ObjectGroup.SPECIAL),
            Json.decodeFromString(ListSerializer(ObjectGroup.serializer()), """["solarSystem","special"]"""),
        )
        assertFailsWith<SerializationException> {
            Json.decodeFromString(ListSerializer(ObjectGroup.serializer()), """["planets"]""")
        }
    }

    // MARK: Angular size

    @Test
    fun testAngularSizeRejectsInvalidMajorAxes() {
        for (major in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, AngularSize.maximumArcminutes * 2)) {
            assertNull(AngularSize.create(major), "$major")
        }
        assertNotNull(AngularSize.create(0.001))
        assertNotNull(AngularSize.create(AngularSize.maximumArcminutes))
    }

    @Test
    fun testAngularSizeCleansTheOptionalValues() {
        assertEquals(4.0, AngularSize.create(10.0, 4.0)?.minorArcminutes)
        assertEquals(10.0, AngularSize.create(10.0, 10.0)?.minorArcminutes)
        for (minor in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, 10.5)) {
            assertNull(AngularSize.create(10.0, minor)?.minorArcminutes, "$minor")
        }
        val angles: List<Pair<Double, Double?>> = listOf(
            0.0 to 0.0, 45.0 to 45.0, 179.5 to 179.5, 180.0 to 0.0, 360.0 to 0.0, 190.0 to 10.0, -10.0 to 170.0,
            725.0 to 5.0, Double.NaN to null, Double.POSITIVE_INFINITY to null,
        )
        for ((angle, expected) in angles) {
            assertEquals(expected, AngularSize.create(5.0, positionAngle = angle)?.positionAngle, "$angle")
        }
        assertNull(AngularSize.create(5.0)?.positionAngle)
        assertEquals(AngularSize.create(5.0, 2.0, 30.0), AngularSize.create(5.0, 2.0, 30.0))
    }

    @Test
    fun testAngularSizeDescriptions() {
        fun text(major: Double, minor: Double? = null): String =
            assertNotNull(AngularSize.create(major, minor)).description

        assertEquals("178′ × 70′", text(178.0, 69.7))
        assertEquals("150′", text(150.0, 150.0))
        assertEquals("646′ × 550′", text(646.0, 550.0))
        assertEquals("12′", text(12.0))
        assertEquals("12′", text(12.4))
        assertEquals("8.4′ × 4′", text(8.4, 4.0))
        assertEquals("10′", text(9.96))
        assertEquals("1.2′ × 0.5′", text(1.2, 0.5))
        assertEquals("1′", text(1.0))
        assertEquals("36″", text(0.6))
        assertEquals("30″ × 15″", text(0.5, 0.25))
        assertEquals("6″", text(0.1))
        assertEquals("0.6″", text(0.01))
        assertEquals("59″", text(0.99))
        assertEquals("1′", text(0.9917), "59.5″ would round to 60″, which is written as one arcminute")
        assertEquals("1′ × 0.5′", text(0.9999, 0.5))
        assertEquals("21600′", text(21600.0))
        assertEquals("31′", assertNotNull(AngularSize.create(30.5)).toString())
    }

    @Test
    fun testAngularSizeCoding() {
        val size = assertNotNull(AngularSize.create(178.0, 70.0, 35.0))
        val encoded = Json.encodeToString(AngularSize.serializer(), size)
        assertEquals(size, Json.decodeFromString(AngularSize.serializer(), encoded))
        val minimal = Json.decodeFromString(AngularSize.serializer(), """{"majorArcminutes":5}""")
        assertEquals(AngularSize.create(5.0), minimal)
        for (json in listOf("""{"majorArcminutes":0}""", """{"majorArcminutes":-3}""", """{"minorArcminutes":2}""", """{"majorArcminutes":"big"}""", "{}")) {
            assertFailsWith<SerializationException>(json) { Json.decodeFromString(AngularSize.serializer(), json) }
        }
        // A minor axis that is too long is dropped on decoding, as it is on creation.
        assertNull(
            Json.decodeFromString(AngularSize.serializer(), """{"majorArcminutes":5,"minorArcminutes":9}""").minorArcminutes,
        )
    }

    // MARK: Custom targets

    @Test
    fun testCustomTargetBasics() {
        val position = EquatorialCoordinate(rightAscension = 123.4, declination = -45.6)
        val target = CelestialObject.custom("My Field", position)
        assertTrue(target.id.startsWith("custom."))
        val uuid = target.id.drop("custom.".length)
        assertNotNull(UUID.fromString(uuid), target.id)
        assertEquals(uuid.uppercase(Locale.ROOT), uuid, "the identifier is written like Swift's uuidString")
        assertEquals("My Field", target.name)
        assertEquals(ObjectCategory.CUSTOM, target.category)
        assertEquals(ObjectGroup.SPECIAL, target.group)
        assertEquals(ObjectPosition.Fixed(position, null), target.position)
        assertEquals(position, target.j2000)
        assertTrue(target.isListed)
        assertFalse(target.isSolarSystem)
        assertTrue(target.designations.isEmpty())
        assertTrue(target.alternateNames.isEmpty())
        assertNull(target.magnitude)
        assertNull(target.angularSize)
        assertNull(target.constellation)
        assertEquals("Custom Target", target.subtitle)
        assertNotEquals(target.id, CelestialObject.custom("My Field", position).id, "every call makes a new identifier")
    }

    @Test
    fun testCustomTargetCanBeRestoredWithItsIdentifier() {
        val uuid = UUID.randomUUID()
        val position = EquatorialCoordinate(rightAscension = 10.0, declination = 20.0)
        val first = CelestialObject.custom(uuid, "Saved", position)
        val second = CelestialObject.custom(uuid, "Saved", position)
        assertEquals("custom.${uuid.toString().uppercase(Locale.ROOT)}", first.id)
        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        val renamed = CelestialObject.custom(uuid, "Renamed", position)
        assertNotEquals(first, renamed, "equality compares every property")
        assertEquals(first.hashCode(), renamed.hashCode(), "hashing uses the identifier only")
        assertEquals(2, setOf(first, second, renamed).size)
    }

    @Test
    fun testCustomTargetNamesAreCleanedUp() {
        val position = EquatorialCoordinate(rightAscension = 0.0, declination = 0.0)

        fun name(raw: String): String = CelestialObject.custom(raw, position).name

        assertEquals("My Target", name("  My Target \n"))
        assertEquals("A B C D", name("A\nB\tC  D"))
        assertEquals("AB", name("A​B"))
        assertEquals("tabbell", name("tab\u0007bell"))
        assertEquals("Ångström — Nebula", name("Ångström — Nebula"))
        assertEquals("日本語の名前", name("日本語の名前"))
        assertEquals("é", name("é"))
        for (empty in listOf("", "   ", "\n\t ", "\u0000\u0007", "​")) {
            assertEquals(
                CelestialObject.defaultCustomName, name(empty),
                "${empty.codePoints().toArray().toList()}",
            )
        }
        assertEquals("Custom Target", CelestialObject.defaultCustomName)
    }

    @Test
    fun testCustomTargetNamesAreCappedAtSixtyCharacters() {
        val position = EquatorialCoordinate(rightAscension = 0.0, declination = 0.0)

        fun name(raw: String): String = CelestialObject.custom(raw, position).name

        /** Swift's `String.count`: the number of extended grapheme clusters. */
        fun count(text: String): Int {
            val boundaries = BreakIterator.getCharacterInstance(Locale.ROOT)
            boundaries.setText(text)
            var characters = 0
            while (boundaries.next() != BreakIterator.DONE) characters += 1
            return characters
        }

        assertEquals(60, CelestialObject.maximumCustomNameLength)
        assertEquals(60, count(name("x".repeat(60))))
        assertEquals(60, count(name("x".repeat(61))))
        assertEquals("🔭".repeat(60), name("🔭".repeat(100)))
        assertEquals(60, count(name("é".repeat(70))))
        assertEquals("a".repeat(59), name("a".repeat(59) + " bbb"), "no trailing space after the cut")
        val start = System.nanoTime()
        assertEquals(59, count(name("word ".repeat(400_000))))
        assertEquals("end", name(" ".repeat(2_000_000) + "end"))
        val scalars = name("z" + "́".repeat(1_000_000))
        assertEquals(8 * 60, scalars.codePointCount(0, scalars.length))
        assertTrue((System.nanoTime() - start) / 1e9 < 10)
    }

    @Test
    fun testCustomTargetPositionsAreMadeFinite() {
        val nan = CelestialObject.custom("NaN", EquatorialCoordinate(rightAscension = Double.NaN, declination = Double.NaN))
        assertEquals(EquatorialCoordinate(rightAscension = 0.0, declination = 0.0), nan.j2000)
        // The coordinate type clamps an infinite declination to the pole; an infinite right ascension survives it.
        val infinite = CelestialObject.custom(
            "Inf",
            EquatorialCoordinate(rightAscension = Double.POSITIVE_INFINITY, declination = Double.NEGATIVE_INFINITY),
        )
        assertEquals(EquatorialCoordinate(rightAscension = 0.0, declination = -90.0), infinite.j2000)
        val wrapped = CelestialObject.custom("Wrap", EquatorialCoordinate(rightAscension = 400.0, declination = 95.0))
        assertEquals(40.0, wrapped.j2000?.rightAscension ?: -1.0, 1e-12)
        assertEquals(90.0, wrapped.j2000?.declination)
    }

    @Test
    fun testCustomTargetsAreNotPartOfTheCatalog() {
        val catalog = CatalogFixture.catalog()
        val target = CelestialObject.custom("Elsewhere", EquatorialCoordinate(rightAscension = 1.0, declination = 1.0))
        assertNull(catalog.objectWithID(target.id))
        assertTrue(catalog.search("Elsewhere").isEmpty())
    }

    // MARK: Object values

    @Test
    fun testSubtitles() {
        val catalog = CatalogFixture.catalog()
        assertEquals("Galaxy · Andromeda · mag 3.4", catalog.objectWithID("dso.M31")?.subtitle)
        assertEquals("Star · Canis Major · mag −1.4", catalog.objectWithID("star.hip32349")?.subtitle)
        assertEquals(
            "Star · Centaurus · mag 0.0", catalog.objectWithID("star.hip71683")?.subtitle,
            "magnitude -0.01 rounds to zero without a sign",
        )
        assertEquals("Open Cluster · Taurus · mag 1.2", catalog.objectWithID("dso.M45")?.subtitle)
        assertEquals("Planet", catalog.objectWithID("planet.mars")?.subtitle)
        assertEquals("Sun", catalog.objectWithID("sun")?.subtitle)
        assertEquals("Celestial Pole · Ursa Minor", catalog.objectWithID("special.ncp")?.subtitle)
        assertEquals("Milky Way · Sagittarius", catalog.objectWithID("special.galacticCenter")?.subtitle)
        assertEquals("Dark Nebula · Orion", catalog.objectWithID("dso.B33")?.subtitle)
    }

    @Test
    fun testObjectPositionValueSemantics() {
        val coordinate = EquatorialCoordinate(rightAscension = 10.0, declination = 20.0)
        val motion = ProperMotion(raMilliarcsecondsPerYear = 1.0, decMilliarcsecondsPerYear = 2.0)
        assertEquals(ObjectPosition.Fixed(coordinate, motion), ObjectPosition.Fixed(coordinate, motion))
        assertNotEquals<ObjectPosition>(ObjectPosition.Fixed(coordinate, motion), ObjectPosition.Fixed(coordinate, null))
        assertNotEquals<ObjectPosition>(
            ObjectPosition.SolarSystem(SolarSystemBody.SUN), ObjectPosition.SolarSystem(SolarSystemBody.MOON),
        )
        assertNotEquals<ObjectPosition>(ObjectPosition.CelestialPole(north = true), ObjectPosition.CelestialPole(north = false))
        assertEquals(
            2,
            setOf<ObjectPosition>(
                ObjectPosition.SolarSystem(SolarSystemBody.SUN), ObjectPosition.SolarSystem(SolarSystemBody.SUN),
                ObjectPosition.CelestialPole(north = true),
            ).size,
        )
    }

    @Test
    fun testRankingMagnitudePlacesSolarSystemBodiesByBrightness() {
        val order = listOf(
            SolarSystemBody.SUN, SolarSystemBody.MOON, SolarSystemBody.VENUS, SolarSystemBody.JUPITER,
            SolarSystemBody.MARS, SolarSystemBody.MERCURY, SolarSystemBody.SATURN, SolarSystemBody.URANUS,
            SolarSystemBody.NEPTUNE,
        )
        val ranking = order.map {
            CelestialObject(
                id = it.rawValue, name = it.displayName, category = ObjectCategory.PLANET,
                position = ObjectPosition.SolarSystem(it), isListed = true,
            ).rankingMagnitude
        }
        assertEquals(ranking.sorted(), ranking)
        assertEquals(9, ranking.toSet().size)
        val nebula = CelestialObject(
            id = "x", name = "x", category = ObjectCategory.NEBULA,
            position = ObjectPosition.Fixed(EquatorialCoordinate(rightAscension = 0.0, declination = 0.0), null),
            isListed = true,
        )
        assertEquals(Double.POSITIVE_INFINITY, nebula.rankingMagnitude)
        assertTrue((ranking.lastOrNull() ?: 0.0) < 10)
    }

    // MARK: Sharing between tasks

    // Swift's `Sendable` is a compile-time promise that a type can cross threads. The Kotlin counterpart is
    // immutability: every instance field of these types is final (collections are exposed read-only), so a
    // value built on one thread can be read from any other.
    @Test
    fun testThePublicTypesAreSendable() {
        val types: List<Class<*>> = listOf(
            Catalog::class.java, CelestialObject::class.java, CatalogSearchResult::class.java,
            CatalogSearchResult.MatchKind::class.java, CatalogSortOrder::class.java, RenderStar::class.java,
            Constellation::class.java, MilkyWayOutline::class.java, MilkyWayOutline.Level::class.java,
            ObjectCategory::class.java, ObjectGroup::class.java, ObjectPosition.Fixed::class.java,
            ObjectPosition.SolarSystem::class.java, ObjectPosition.CelestialPole::class.java,
            AngularSize::class.java, CatalogError::class.java, CatalogFile::class.java,
            // CatalogLoadReport is left out on purpose: the loader fills it in through internal setters while the
            // catalog is built, and nothing changes it afterwards (its public properties are read-only).
        )
        for (type in types) {
            for (field in type.declaredFields) {
                if (Modifier.isStatic(field.modifiers) || field.isSynthetic) continue
                assertTrue(Modifier.isFinal(field.modifiers), "${type.simpleName}.${field.name} is not final")
            }
        }
    }

    @Test
    fun testACatalogCanBeUsedFromOtherTasks() {
        val catalog = CatalogFixture.catalog()
        val pool = Executors.newSingleThreadExecutor()
        try {
            val results = pool.submit(Callable { catalog.search("m31", limit = 3).map { it.obj.id } }).get()
            assertEquals("dso.M31", results.firstOrNull())
            val sirius = assertNotNull(catalog.objectWithID("star.hip32349"))
            val observer = Observer(GeographicLocation(latitude = 40.0, longitude = -105.0, elevation = 0.0))
            val position = pool.submit(
                Callable { SkyFrame(Instant.ofEpochSecond(0), observer).horizontal(sirius) },
            ).get()
            assertTrue(position.altitude >= -90.0 && position.altitude <= 90.0)
        } finally {
            pool.shutdown()
        }
    }

    // MARK: Errors and the load report

    @Test
    fun testErrorDescriptionsNameTheFile() {
        val errors: List<CatalogError> = listOf(
            CatalogError.MissingResource(CatalogFile.STARS),
            CatalogError.Unreadable(CatalogFile.DEEP_SKY_OBJECTS, "disk"),
            CatalogError.Malformed(CatalogFile.CONSTELLATIONS, "truncated"),
            CatalogError.UnsupportedSchema(CatalogFile.MILKY_WAY, 7),
            CatalogError.UnsupportedSchema(CatalogFile.STARS, null),
            CatalogError.NoValidRecords(CatalogFile.DEEP_SKY_OBJECTS, 3),
        )
        val files = listOf("stars.json", "dsos.json", "constellations.json", "milkyway.json", "stars.json", "dsos.json")
        for ((error, file) in errors.zip(files)) {
            assertTrue(error.description.contains(file), error.description)
            assertEquals(error.description, error.errorDescription)
            assertEquals(error.description, error.message)
            assertEquals(error.description, error.localizedMessage)
        }
        assertTrue(CatalogError.Malformed(CatalogFile.STARS, "truncated").description.contains("truncated"))
        assertTrue(CatalogError.UnsupportedSchema(CatalogFile.MILKY_WAY, 7).description.contains("7"))
        assertContentEquals(
            listOf("stars.json", "dsos.json", "constellations.json", "milkyway.json"),
            CatalogFile.entries.map { it.fileName },
        )
    }

    @Test
    fun testLoadReportKeepsOnlyTheFirstIssues() {
        val report = CatalogLoadReport()
        assertTrue(report.isClean)
        for (index in 0 until 100) {
            report.reject(CatalogLoadReport::stars) { "problem $index" }
        }
        assertEquals(100, report.stars.rejected)
        assertEquals(100, report.rejectedCount)
        assertFalse(report.isClean)
        assertEquals(CatalogLoadReport.maximumIssueCount, report.issues.size)
        assertEquals("problem 0", report.issues.firstOrNull())
        report.reject(CatalogLoadReport::milkyWayRings) { "ring" }
        assertEquals(101, report.rejectedCount)
        assertEquals(1, report.milkyWayRings.rejected)
        assertEquals(CatalogLoadReport.maximumIssueCount, report.issues.size, "the list is capped")
    }
}
