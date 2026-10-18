package net.kuecker.lodestar.kit.catalog

import net.kuecker.lodestar.kit.foundation.EquatorialCoordinate
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The deep-sky catalogues beyond Messier and NGC/IC (Caldwell, Sharpless 2, Barnard, Lynds, Collinder, Melotte,
 * Trumpler, Stock), the `cat` and `p` members of the data records, prominence and the catalogue lists.
 *
 * Positions are checked against published values (SIMBAD and Wikipedia are named where they are used) with the
 * tolerance the source catalogue allows: Sharpless and Lynds give the center of a region or cloud to a fraction
 * of its size.
 */
class CatalogCataloguesTests {
    private fun catalog(): Catalog = CatalogFixture.catalog()

    private fun obj(id: String): CelestialObject = assertNotNull(catalog().objectWithID(id), id)

    /** Angular distance in degrees between a J2000 position and an object. */
    private fun distance(ra: Double, dec: Double, id: String): Double {
        val position = assertNotNull(obj(id).j2000, id)
        val dRa = (position.rightAscension - ra) * cos(Math.toRadians((position.declination + dec) / 2))
        val dDec = position.declination - dec
        return sqrt(dRa * dRa + dDec * dDec)
    }

    private fun ids(results: List<CatalogSearchResult>): List<String> = results.map { it.obj.id }

    // MARK: Counts

    @Test
    fun testCatalogueListsHaveTheCountsOfTheirCatalogues() {
        val catalog = catalog()
        assertEquals(110, catalog.objects(DeepSkyCatalogue.MESSIER).size)
        assertEquals(109, catalog.objects(DeepSkyCatalogue.CALDWELL).size)
        assertEquals(313, catalog.objects(DeepSkyCatalogue.SHARPLESS).size)
        assertEquals(349, catalog.objects(DeepSkyCatalogue.BARNARD).size)
        assertEquals(398, catalog.objects(DeepSkyCatalogue.LYNDS).size)
        assertEquals(1225, catalog.objects(DeepSkyCatalogue.NGC).size)
        assertEquals(167, catalog.objects(DeepSkyCatalogue.IC).size)
        assertEquals(17, catalog.objects(DeepSkyCatalogue.COLLINDER).size)
        assertEquals(9, catalog.objects(DeepSkyCatalogue.MELOTTE).size)
        assertEquals(2, catalog.objects(DeepSkyCatalogue.TRUMPLER).size)
        assertEquals(1, catalog.objects(DeepSkyCatalogue.STOCK).size)
        assertEquals(2441 - catalog.loadReport.deepSkyObjects.merged, catalog.deepSkyObjects.size)
        assertEquals(catalog.objects.filter { it.isDeepSky }, catalog.deepSkyObjects)
        assertEquals(0, catalog.loadReport.rejectedCount)
    }

    @Test
    fun testEveryCatalogueListIsOrderedByNumberAndHasNoRepeats() {
        val catalog = catalog()
        for (catalogue in DeepSkyCatalogue.entries) {
            val list = catalog.objects(catalogue)
            assertEquals(list.size, list.map { it.id }.toSet().size, "$catalogue repeats an object")
            var previous: CatalogNumber? = null
            for (obj in list) {
                assertTrue(catalogue in obj.catalogues, "${obj.id} is not in $catalogue")
                val number = assertNotNull(obj.catalogNumbers.first { it.catalogue == catalogue })
                if (previous != null) {
                    assertTrue(
                        previous.number < number.number ||
                            (previous.number == number.number && previous.suffix <= number.suffix),
                        "$catalogue: ${previous.label} before ${number.label}",
                    )
                }
                previous = number
            }
        }
        assertEquals((1..109).toList(), catalog.objects(DeepSkyCatalogue.CALDWELL).map { it.catalogNumbers.first { n -> n.catalogue == DeepSkyCatalogue.CALDWELL }.number })
        assertEquals((1..313).toList(), catalog.objects(DeepSkyCatalogue.SHARPLESS).map { it.catalogNumbers.first { n -> n.catalogue == DeepSkyCatalogue.SHARPLESS }.number })
        assertEquals((1..110).toList(), catalog.objects(DeepSkyCatalogue.MESSIER).map { it.catalogNumbers.first().number })
        assertEquals("dso.M1", catalog.objects(DeepSkyCatalogue.MESSIER).first().id)
    }

    @Test
    fun testCatalogNumbersAgreeWithTheDataFile() {
        val catalog = catalog()
        val prefixes = mapOf(
            "M" to DeepSkyCatalogue.MESSIER, "NGC" to DeepSkyCatalogue.NGC, "IC" to DeepSkyCatalogue.IC,
            "C" to DeepSkyCatalogue.CALDWELL, "B" to DeepSkyCatalogue.BARNARD, "LDN" to DeepSkyCatalogue.LYNDS,
            "Cl" to DeepSkyCatalogue.COLLINDER, "Mel" to DeepSkyCatalogue.MELOTTE, "Tr" to DeepSkyCatalogue.TRUMPLER,
            "St" to DeepSkyCatalogue.STOCK,
        )

        fun number(text: String): CatalogNumber? {
            if (text.startsWith("Sh2-")) return CatalogNumber(DeepSkyCatalogue.SHARPLESS, text.substring(4).toInt())
            val match = Regex("([A-Za-z]+)(\\d+)([A-Za-z]?)").matchEntire(text) ?: return null
            val catalogue = prefixes[match.groupValues[1]] ?: return null
            return CatalogNumber(catalogue, match.groupValues[2].toInt(), match.groupValues[3])
        }

        var checked = 0
        for (record in CatalogFixture.rawDeepSky()) {
            val obj = catalog.objectWithID("dso.${record.id}") ?: continue
            val expected = ArrayList<CatalogNumber>()
            fun add(candidate: CatalogNumber?) {
                if (candidate != null && candidate !in expected) expected.add(candidate)
            }
            record.m?.let { add(CatalogNumber(DeepSkyCatalogue.MESSIER, it)) }
            record.ngc?.forEach { add(CatalogNumber(DeepSkyCatalogue.NGC, it)) }
            record.ic?.forEach { add(CatalogNumber(DeepSkyCatalogue.IC, it)) }
            add(number(record.id))
            record.cat?.forEach { add(number(it)) }
            assertEquals(expected, obj.catalogNumbers, record.id)
            assertEquals(expected.map { it.catalogue }.distinct(), obj.catalogues, record.id)
            checked += 1
        }
        assertEquals(2441 - catalog.loadReport.deepSkyObjects.merged, checked)
    }

    // MARK: Caldwell

    @Test
    fun testCaldwellObjectsAreWhereMooresListPutsThem() {
        val catalog = catalog()
        // Patrick Moore, 1995 (as in the Wikipedia list of Caldwell objects): Caldwell number -> NGC/IC object.
        val expected = mapOf(
            1 to "NGC188", 2 to "NGC40", 4 to "NGC7023", 5 to "IC342", 6 to "NGC6543", 11 to "NGC7635",
            13 to "NGC457", 19 to "IC5146", 20 to "NGC7000", 27 to "NGC6888", 31 to "IC405", 33 to "NGC6992",
            34 to "NGC6960", 38 to "NGC4565", 39 to "NGC2392", 49 to "NGC2238", 55 to "NGC7009", 63 to "NGC7293",
            65 to "NGC253", 77 to "NGC5128", 80 to "NGC5139", 92 to "NGC3372", 94 to "NGC4755", 103 to "NGC2070",
            106 to "NGC104", 109 to "NGC3195",
        )
        val byNumber = catalog.objects(DeepSkyCatalogue.CALDWELL)
        for ((number, id) in expected) {
            val obj = byNumber[number - 1]
            assertEquals("dso.$id", obj.id, "Caldwell $number")
            assertTrue(obj.designations.contains("Caldwell $number"), "Caldwell $number: ${obj.designations}")
        }
        // The four Caldwell objects that have no NGC or IC number are objects of their own.
        assertEquals("dso.C9", byNumber[8].id)
        assertEquals("dso.C14", byNumber[13].id)
        assertEquals("dso.C41", byNumber[40].id)
        assertEquals("dso.C99", byNumber[98].id)
        assertEquals("Cave Nebula", byNumber[8].name)
        assertEquals("Double Cluster", byNumber[13].name)
        assertEquals("Hyades", byNumber[40].name)
        assertEquals("Coalsack Nebula", byNumber[98].name)
        assertEquals("Caldwell 20", obj("dso.NGC7000").designations[1])
        assertTrue(byNumber.all { it.prominence == Prominence.SHOWPIECE && it.isListed })
        assertEquals(listOf("Caldwell 109"), listOf(byNumber.last().designations.last()))
    }

    // MARK: Sharpless

    @Test
    fun testSharplessRegionsAreEmissionNebulaeWithTheirSizes() {
        val catalog = catalog()
        val regions = catalog.objects(DeepSkyCatalogue.SHARPLESS)
        assertEquals(313, regions.size)
        val sharplessOnly = regions.filter { it.id.startsWith("dso.Sh2-") }
        assertEquals(313 - 47, sharplessOnly.size, "47 regions are other objects of the catalog")
        for (region in sharplessOnly) {
            assertTrue(region.angularSize != null, region.id)
            assertNull(region.magnitude, region.id)
            assertNotNull(region.constellation, region.id)
            assertTrue(
                region.category == ObjectCategory.EMISSION_NEBULA || region.category == ObjectCategory.PLANETARY_NEBULA ||
                    region.category == ObjectCategory.SUPERNOVA_REMNANT,
                region.id,
            )
        }
        // The catalogue lists every region as HII; five are known to be something else.
        assertEquals(
            setOf("dso.Sh2-188", "dso.Sh2-216", "dso.Sh2-274", "dso.Sh2-290"),
            sharplessOnly.filter { it.category == ObjectCategory.PLANETARY_NEBULA }.map { it.id }.toSet(),
        )
        assertEquals(listOf("dso.Sh2-240"), sharplessOnly.filter { it.category == ObjectCategory.SUPERNOVA_REMNANT }.map { it.id })
        // Sizes are the largest extent of the region in arcminutes (Sharpless 1959, CDS VII/20).
        assertEquals(150.0, obj("dso.Sh2-1").angularSize?.majorArcminutes)
        assertEquals(140.0, obj("dso.Sh2-129").angularSize?.majorArcminutes)
        assertEquals(1200.0, obj("dso.Sh2-276").angularSize?.majorArcminutes)
        assertEquals(8.0, obj("dso.Sh2-274").angularSize?.majorArcminutes)
        // A circular region has equal axes (Sh2-240 is circular), others only the major axis.
        assertEquals(180.0, obj("dso.Sh2-240").angularSize?.minorArcminutes)
        assertNull(obj("dso.Sh2-129").angularSize?.minorArcminutes)
        assertEquals("Sh2-129", obj("dso.Sh2-129").designations.first())
        assertEquals("Sh2-1", obj("dso.Sh2-1").name)
    }

    @Test
    fun testSharplessRegionsThatAreOtherObjectsAreDesignationsOfThem() {
        // Sh2-25 is the Lagoon Nebula, Sh2-117 the North America Nebula, Sh2-281 the Orion Nebula, Sh2-275 the
        // Rosette Nebula, Sh2-190 the Heart Nebula, Sh2-184 the Pacman Nebula, Sh2-155 the Cave Nebula.
        val pairs = mapOf(
            25 to "dso.M8", 30 to "dso.M20", 45 to "dso.M17", 49 to "dso.M16", 117 to "dso.NGC7000", 281 to "dso.M42",
            275 to "dso.NGC2238", 190 to "dso.IC1805", 199 to "dso.IC1848", 184 to "dso.NGC281", 155 to "dso.C9",
            103 to "dso.NGC6960", 220 to "dso.NGC1499", 248 to "dso.IC443", 277 to "dso.NGC2024", 298 to "dso.NGC2359",
            244 to "dso.M1",
        )
        val catalog = catalog()
        val regions = catalog.objects(DeepSkyCatalogue.SHARPLESS)
        for ((number, id) in pairs) {
            val region = regions[number - 1]
            assertEquals(id, region.id, "Sh2-$number")
            assertTrue(region.designations.contains("Sh2-$number"), region.designations.toString())
            assertTrue(region.catalogNumbers.contains(CatalogNumber(DeepSkyCatalogue.SHARPLESS, number)))
        }
        assertEquals(listOf("M 8", "NGC 6523", "NGC 6533", "Sh2-25"), obj("dso.M8").designations)
        assertEquals(listOf("NGC 7000", "Caldwell 20", "Sh2-117"), obj("dso.NGC7000").designations)
        assertEquals(
            listOf(
                CatalogNumber(DeepSkyCatalogue.NGC, 7000), CatalogNumber(DeepSkyCatalogue.CALDWELL, 20),
                CatalogNumber(DeepSkyCatalogue.SHARPLESS, 117),
            ),
            obj("dso.NGC7000").catalogNumbers,
        )
        assertEquals(
            listOf(DeepSkyCatalogue.NGC, DeepSkyCatalogue.CALDWELL, DeepSkyCatalogue.SHARPLESS),
            obj("dso.NGC7000").catalogues,
        )
        assertEquals("Sh2-117", obj("dso.NGC7000").catalogNumbers.last().label)
        assertEquals("C 20", obj("dso.NGC7000").catalogNumbers[1].label)
    }

    // MARK: Names

    @Test
    fun testPopularNamesOfTheNewCatalogues() {
        val names = mapOf(
            "dso.Sh2-129" to "Flying Bat Nebula", "dso.Sh2-240" to "Spaghetti Nebula", "dso.Sh2-132" to "Lion Nebula",
            "dso.Sh2-101" to "Tulip Nebula", "dso.Sh2-136" to "Ghost Nebula", "dso.Sh2-157" to "Lobster Claw Nebula",
            "dso.Sh2-261" to "Lower's Nebula", "dso.Sh2-274" to "Medusa Nebula", "dso.Sh2-276" to "Barnard's Loop",
            "dso.Sh2-308" to "Dolphin Head Nebula", "dso.Sh2-264" to "Lambda Orionis Ring",
            "dso.Sh2-108" to "Gamma Cygni Nebula", "dso.Sh2-290" to "Abell 31", "dso.C9" to "Cave Nebula",
            "dso.B33" to "Horsehead Nebula", "dso.B72" to "Snake Nebula", "dso.B78" to "Pipe Nebula",
            "dso.B86" to "Ink Spot Nebula", "dso.B150" to "Seahorse Nebula",
            "dso.LDN1622" to "Boogeyman Nebula", "dso.LDN1235" to "Shark Nebula",
            "dso.Mel20" to "Alpha Persei Cluster", "dso.Cl69" to "Lambda Orionis Cluster",
            "dso.Cl70" to "Orion's Belt Cluster", "dso.St2" to "Muscleman Cluster",
        )
        for ((id, name) in names) {
            assertEquals(name, obj(id).name, id)
            assertTrue(obj(id).isListed, "$id: a named object is never obscure")
        }
        assertEquals("Orion", obj("dso.Sh2-276").constellationName?.take(5))
        assertEquals("Cepheus", obj("dso.Sh2-129").constellationName)
    }

    // MARK: Dark nebulae

    @Test
    fun testBarnardAndLyndsDarkNebulae() {
        val catalog = catalog()
        val barnard = catalog.objects(DeepSkyCatalogue.BARNARD)
        assertEquals(349, barnard.size)
        assertTrue(barnard.all { it.category == ObjectCategory.DARK_NEBULA && it.magnitude == null })
        // Barnard numbers: 1 to 175 (three of them omitted by the source) and 201 to 370; some carry a letter.
        val numbers = barnard.map { it.catalogNumbers.first { n -> n.catalogue == DeepSkyCatalogue.BARNARD } }
        assertEquals(DeepSkyCatalogue.BARNARD, numbers.first().catalogue)
        assertEquals("B 1", numbers.first().label)
        assertTrue(numbers.any { it.suffix == "a" })
        assertTrue(numbers.maxOf { it.number } <= 370)
        assertEquals(listOf("Barnard 33"), obj("dso.B33").designations)
        assertEquals(6.0, obj("dso.B33").angularSize?.majorArcminutes)
        assertEquals(4.0, obj("dso.B33").angularSize?.minorArcminutes)
        assertEquals(180.0, obj("dso.B78").angularSize?.majorArcminutes)
        assertTrue(obj("dso.B67a").designations.contains("Barnard 67a"))

        val lynds = catalog.objects(DeepSkyCatalogue.LYNDS)
        assertEquals(398, lynds.size)
        assertTrue(lynds.all { it.category == ObjectCategory.DARK_NEBULA && it.magnitude == null })
        assertEquals(listOf("LDN 1622"), obj("dso.LDN1622").designations)
        assertEquals(listOf("Boogeyman Nebula"), listOf(obj("dso.LDN1622").name))
        // The cut: the darkest clouds (opacity 6) and the large ones (an area of at least a square degree,
        // a circle 67.7' across); 248 clouds are that large.
        assertEquals(248, lynds.count { (it.angularSize?.majorArcminutes ?: 0.0) >= 67.7 })
        assertEquals(150, lynds.count { (it.angularSize?.majorArcminutes ?: 0.0) < 67.7 })
        // Area-equivalent diameter of LDN 1622 (0.122 square degrees): 2 * sqrt(0.122 / pi) degrees.
        assertEquals(2 * sqrt(0.122 / Math.PI) * 60, obj("dso.LDN1622").angularSize?.majorArcminutes ?: 0.0, 0.01)
    }

    @Test
    fun testOpenClustersOfTheDiasList() {
        val catalog = catalog()
        val collinder = catalog.objects(DeepSkyCatalogue.COLLINDER)
        // Collinder 50 (the Hyades, C 41) and 258 (Harvard 5) are the same objects as in OpenNGC.
        assertEquals(
            listOf(50, 65, 69, 70, 89, 121, 132, 140, 173, 258, 302, 316, 359, 399, 408, 464, 471),
            collinder.map { it.catalogNumbers.first { n -> n.catalogue == DeepSkyCatalogue.COLLINDER }.number },
        )
        assertEquals(
            listOf(20, 22, 25, 31, 71, 72, 101, 105, 111),
            catalog.objects(DeepSkyCatalogue.MELOTTE).map { it.catalogNumbers.first { n -> n.catalogue == DeepSkyCatalogue.MELOTTE }.number },
        )
        assertEquals(listOf("dso.Tr24", "dso.IC1396"), catalog.objects(DeepSkyCatalogue.TRUMPLER).map { it.id })
        assertEquals(listOf("dso.St2"), catalog.objects(DeepSkyCatalogue.STOCK).map { it.id })
        val alphaPersei = obj("dso.Mel20")
        assertEquals(ObjectCategory.OPEN_CLUSTER, alphaPersei.category)
        assertEquals(300.0, alphaPersei.angularSize?.majorArcminutes)
        assertEquals("Per", alphaPersei.constellation)
        assertEquals(listOf("Melotte 20"), alphaPersei.designations)
        assertEquals(Prominence.PROMINENT, alphaPersei.prominence)
        // Clusters larger than 10 degrees are moving groups, not clusters: Collinder 285 is left out.
        assertNull(catalog.objectWithID("dso.Cl285"))
        // The Pleiades are Melotte 22, the Hyades Melotte 25 and Collinder 50.
        assertEquals(listOf("M 45", "Melotte 22"), obj("dso.M45").designations)
        assertEquals(listOf("Caldwell 41", "Melotte 25", "Collinder 50"), obj("dso.C41").designations)
        assertEquals(listOf("IC 1396", "Sh2-131", "Trumpler 37"), obj("dso.IC1396").designations)
    }

    // MARK: Published positions

    @Test
    fun testPositionsMatchPublishedValues() {
        // Medusa Nebula = Sh2-274 = Abell 21: SIMBAD 07 29 02.69 +13 14 48.4 (Wikipedia, Medusa Nebula).
        // Sharpless' center is good to a few arcminutes of this 8' nebula.
        assertTrue(distance(112.26121, 13.24678, "dso.Sh2-274") < 0.05)
        // Snake Nebula = Barnard 72: 17 23 30 -23 38 (Wikipedia, Snake Nebula).
        assertTrue(distance(260.875, -23.6333, "dso.B72") < 0.05)
        // Horsehead Nebula = Barnard 33: 05 40 59 -02 27 30 (OpenNGC addendum).
        assertTrue(distance(85.245833, -2.458333, "dso.B33") < 1e-5)
        // Tulip Nebula = Sh2-101: 20 00 29.37 +35 19 13.9 (Wikipedia, Sh 2-101); Sharpless' center is 6' away.
        assertTrue(distance(300.12238, 35.32053, "dso.Sh2-101") < 0.15)
        // Spaghetti Nebula = Simeis 147 = Sh2-240: 05 39 06 +27 59 55 (Wikipedia); the nebula is 3 degrees wide.
        assertTrue(distance(84.775, 27.99861, "dso.Sh2-240") < 0.5)
        // Pipe Nebula bowl = Barnard 78: 17 32 -25 35 (Barnard 1927, J2000 as in CDS VII/220A).
        assertTrue(distance(263.0, -25.5833, "dso.B78") < 0.05)
        // Cave Nebula = Caldwell 9 = Sh2-155 at the position OpenNGC gives.
        assertTrue(distance(344.475, 62.518333, "dso.C9") < 1e-5)
        // Lynds' center of LDN 1622 (CDS VII/7A, 05 52.0 +02 00 at B1950, precessed); the Boogeyman Nebula
        // is at about 05 54 +01 50.
        assertTrue(distance(88.65, 2.008, "dso.LDN1622") < 0.01)
        assertTrue(distance(88.5, 1.83, "dso.LDN1622") < 0.4)
    }

    @Test
    fun testPositionsAgreeWithTheRowsOfTheSourceCatalogues() {
        // Rows quoted from the CDS files: Sharpless VII/20 (B1900.0), Lynds VII/7A (B1950.0), Barnard VII/220A and
        // Dias B/ocl (J2000.0), as hours, minutes, seconds and degrees, arcminutes, arcseconds.
        class Row(val id: String, val jd: Double, val ra: Triple<Double, Double, Double>, val sign: Int, val dec: Triple<Double, Double, Double>)

        val b1900 = 2415020.31352
        val b1950 = 2433282.4235
        val j2000 = 2451545.0
        val rows = listOf(
            Row("dso.Sh2-1", b1900, Triple(15.0, 52.0, 48.0), -1, Triple(25.0, 50.0, 0.0)),
            Row("dso.Sh2-129", b1900, Triple(21.0, 9.0, 12.0), 1, Triple(59.0, 33.0, 0.0)),
            Row("dso.Sh2-240", b1900, Triple(5.0, 34.0, 42.0), 1, Triple(28.0, 3.0, 0.0)),
            Row("dso.Sh2-274", b1900, Triple(7.0, 23.0, 30.0), 1, Triple(13.0, 28.0, 0.0)),
            Row("dso.Sh2-276", b1900, Triple(5.0, 22.0, 30.0), -1, Triple(4.0, 3.0, 0.0)),
            Row("dso.Sh2-308", b1900, Triple(6.0, 50.0, 0.0), -1, Triple(23.0, 49.0, 0.0)),
            Row("dso.LDN673", b1950, Triple(19.0, 18.5, 0.0), 1, Triple(11.0, 10.0, 0.0)),
            Row("dso.LDN1235", b1950, Triple(22.0, 14.0, 0.0), 1, Triple(73.0, 10.0, 0.0)),
            Row("dso.LDN1622", b1950, Triple(5.0, 52.0, 0.0), 1, Triple(2.0, 0.0, 0.0)),
            Row("dso.B72", j2000, Triple(17.0, 23.0, 35.0), -1, Triple(23.0, 37.0, 0.0)),
            Row("dso.B78", j2000, Triple(17.0, 32.0, 0.0), -1, Triple(25.0, 35.0, 0.0)),
            Row("dso.B86", j2000, Triple(18.0, 3.0, 1.0), -1, Triple(27.0, 52.0, 0.0)),
            Row("dso.B150", j2000, Triple(20.0, 50.0, 39.0), 1, Triple(60.0, 18.0, 0.0)),
            Row("dso.Mel20", j2000, Triple(3.0, 24.0, 19.0), 1, Triple(49.0, 51.0, 42.0)),
            Row("dso.Cl70", j2000, Triple(5.0, 35.0, 30.0), -1, Triple(1.0, 6.0, 0.0)),
            Row("dso.St2", j2000, Triple(2.0, 14.0, 43.0), 1, Triple(59.0, 29.0, 6.0)),
            Row("dso.Tr24", j2000, Triple(16.0, 57.0, 0.0), -1, Triple(40.0, 40.0, 0.0)),
        )
        for (row in rows) {
            val ra = (row.ra.first + row.ra.second / 60 + row.ra.third / 3600) * 15
            val dec = row.sign * (row.dec.first + row.dec.second / 60 + row.dec.third / 3600)
            val (expectedRa, expectedDec) = precessToJ2000(ra, dec, row.jd, j2000)
            val position = assertNotNull(obj(row.id).j2000, row.id)
            assertEquals(expectedRa, position.rightAscension, 1e-4, row.id)
            assertEquals(expectedDec, position.declination, 1e-4, row.id)
        }
    }

    /** IAU 1976 precession (Lieske) of a position from the equinox of date [fromJd] to J2000, by rotation matrices. */
    private fun precessToJ2000(ra: Double, dec: Double, fromJd: Double, j2000: Double): Pair<Double, Double> {
        if (fromJd == j2000) return ra to dec
        val t0 = (fromJd - j2000) / 36525.0
        val t = (j2000 - fromJd) / 36525.0
        val arcsecond = Math.PI / 648000.0
        val zeta = ((2306.2181 + 1.39656 * t0 - 0.000139 * t0 * t0) * t + (0.30188 - 0.000344 * t0) * t * t + 0.017998 * t * t * t) * arcsecond
        val z = ((2306.2181 + 1.39656 * t0 - 0.000139 * t0 * t0) * t + (1.09468 + 0.000066 * t0) * t * t + 0.018203 * t * t * t) * arcsecond
        val theta = ((2004.3109 - 0.8533 * t0 - 0.000217 * t0 * t0) * t - (0.42665 + 0.000217 * t0) * t * t - 0.041833 * t * t * t) * arcsecond
        val a = Math.toRadians(ra)
        val d = Math.toRadians(dec)
        var x = cos(d) * cos(a)
        var y = cos(d) * sin(a)
        var w = sin(d)
        // R3(-z) R2(theta) R3(-zeta)
        var nx = x * cos(zeta) - y * sin(zeta)
        var ny = x * sin(zeta) + y * cos(zeta)
        x = nx; y = ny
        nx = x * cos(theta) - w * sin(theta)
        val nw = x * sin(theta) + w * cos(theta)
        x = nx; w = nw
        nx = x * cos(z) - y * sin(z)
        ny = x * sin(z) + y * cos(z)
        var resultRa = Math.toDegrees(atan2(ny, nx))
        if (resultRa < 0) resultRa += 360.0
        return resultRa to Math.toDegrees(asin(w.coerceIn(-1.0, 1.0)))
    }

    // MARK: Search

    @Test
    fun testTheNewDesignationsFindTheirObjectsWhateverTheSpelling() {
        val catalog = catalog()
        val expectations = listOf(
            // Sharpless: the catalogue number with and without the leading 2, spaces, hyphen or none.
            "Sh2-155" to "dso.C9", "Sh 2-155" to "dso.C9", "sh2155" to "dso.C9", "Sharpless 155" to "dso.C9",
            "Sharpless 2-155" to "dso.C9", "Sh2-129" to "dso.Sh2-129", "Sh 2-129" to "dso.Sh2-129",
            // Search keys have no punctuation: below Sh2-100 "Sharpless 2-45" would read as "Sharpless 245".
            "Sharpless 45" to "dso.M17", "Sharpless 245" to "dso.Sh2-245", "Sharpless 25" to "dso.M8",
            "Sharpless 225" to "dso.Sh2-225", "Sh2-45" to "dso.M17", "Sh 2-245" to "dso.Sh2-245",
            "Sharpless 129" to "dso.Sh2-129", "Sh2-240" to "dso.Sh2-240", "Sh2-25" to "dso.M8",
            // Barnard.
            "B33" to "dso.B33", "B 33" to "dso.B33", "Barnard 33" to "dso.B33", "barnard33" to "dso.B33",
            "B72" to "dso.B72", "Barnard 72" to "dso.B72", "B67a" to "dso.B67a", "Barnard 67a" to "dso.B67a",
            // Lynds.
            "LDN 1622" to "dso.LDN1622", "LDN1622" to "dso.LDN1622", "Lynds 1622" to "dso.LDN1622",
            "L 1622" to "dso.LDN1622", "ldn 1235" to "dso.LDN1235",
            // Caldwell.
            "C14" to "dso.C14", "C 14" to "dso.C14", "Caldwell 14" to "dso.C14", "caldwell14" to "dso.C14",
            "C20" to "dso.NGC7000", "Caldwell 20" to "dso.NGC7000", "C 63" to "dso.NGC7293",
            "Caldwell 80" to "dso.NGC5139", "C 106" to "dso.NGC104", "C9" to "dso.C9",
            // Collinder, Melotte, Trumpler, Stock.
            "Cr 399" to "dso.Cl399", "Cr399" to "dso.Cl399", "Cl 399" to "dso.Cl399", "Collinder 399" to "dso.Cl399",
            "Mel 20" to "dso.Mel20", "Melotte 20" to "dso.Mel20", "Mel20" to "dso.Mel20",
            "Mel 22" to "dso.M45", "Mel 25" to "dso.C41", "Cr 50" to "dso.C41", "Cr 70" to "dso.Cl70",
            "Tr 24" to "dso.Tr24", "Trumpler 24" to "dso.Tr24", "Tr 37" to "dso.IC1396", "St 2" to "dso.St2",
            "Stock 2" to "dso.St2",
            // Names.
            "Boogeyman" to "dso.LDN1622", "Flying Bat" to "dso.Sh2-129", "Horsehead" to "dso.B33",
            "Spaghetti Nebula" to "dso.Sh2-240", "Cave Nebula" to "dso.C9", "Snake Nebula" to "dso.B72",
            "Simeis 147" to "dso.Sh2-240", "Abell 21" to "dso.Sh2-274", "Abell 31" to "dso.Sh2-290",
        )
        for ((query, id) in expectations) {
            val results = catalog.search(query)
            val first = assertNotNull(results.firstOrNull(), query)
            assertEquals(id, first.obj.id, query)
            assertTrue(
                first.kind == CatalogSearchResult.MatchKind.EXACT || first.kind == CatalogSearchResult.MatchKind.PREFIX ||
                    first.kind == CatalogSearchResult.MatchKind.WORD_PREFIX,
                "$query: ${first.kind}",
            )
        }
        // The designations are exact matches, not prefix matches of something longer.
        for (query in listOf("Sh2-155", "B33", "LDN 1622", "C14", "Cr 399", "Mel 20", "Caldwell 14", "Barnard 33")) {
            assertEquals(CatalogSearchResult.MatchKind.EXACT, catalog.search(query).first().kind, query)
        }
    }

    @Test
    fun testTheMatchedTextOfAnAliasIsTheDesignationAsShown() {
        val catalog = catalog()
        assertEquals("Sh2-155", catalog.search("Sharpless 155").first().matchedText)
        assertEquals("Caldwell 20", catalog.search("C 20").first().matchedText)
        assertEquals("Barnard 33", catalog.search("B33").first().matchedText)
        assertEquals("LDN 1622", catalog.search("Lynds 1622").first().matchedText)
        assertEquals("Collinder 399", catalog.search("Cr 399").first().matchedText)
        assertEquals("Melotte 22", catalog.search("Mel 22").first().matchedText)
    }

    @Test
    fun testSearchingACataloguePrefixListsItsObjects() {
        val catalog = catalog()
        val caldwell = catalog.search("caldwell", limit = 200)
        assertEquals(109, caldwell.size)
        assertEquals(catalog.objects(DeepSkyCatalogue.CALDWELL).map { it.id }.toSet(), ids(caldwell).toSet())
        assertTrue(caldwell.all { it.kind == CatalogSearchResult.MatchKind.PREFIX })
        val sharpless = catalog.search("sharpless", limit = 400)
        assertEquals(313, sharpless.size)
        val barnard = catalog.search("barnard 3", limit = 400)
        assertTrue(barnard.isNotEmpty() && barnard.all { it.obj.catalogues.contains(DeepSkyCatalogue.BARNARD) })
        val exact = catalog.search("sh2-1", limit = 400)
        assertEquals("dso.Sh2-1", exact.first().obj.id)
        assertEquals(CatalogSearchResult.MatchKind.EXACT, exact.first().kind)
        assertTrue(exact.drop(1).all { it.kind == CatalogSearchResult.MatchKind.PREFIX })
        // Every result of a LDN query is a cloud, a Barnard object is never found by a Lynds number.
        assertTrue(catalog.search("ldn 16", limit = 100).all { it.obj.id.startsWith("dso.LDN16") })
    }

    @Test
    fun testFamousDarkNebulaeOutrankFaintGalaxiesInSearch() {
        val catalog = catalog()
        // "h" matches the names "Horsehead Nebula", "Heart Nebula", ... and many galaxies; the showpieces come
        // first even though they have no magnitude.
        val results = catalog.search("horse", limit = 5)
        assertEquals("dso.B33", results.first().obj.id)
        // Within one kind of match no obscure object comes before a showpiece.
        val hits = catalog.search("nebula", limit = 400)
        for ((first, second) in hits.zip(hits.drop(1))) {
            if (first.kind == second.kind && first.obj.isListed == second.obj.isListed) {
                assertTrue(first.obj.prominence <= second.obj.prominence, "${first.obj.id} before ${second.obj.id}")
            }
        }
    }

    // MARK: Prominence

    @Test
    fun testProminenceCountsAndListingOfTheBundledCatalog() {
        val catalog = catalog()
        val deepSky = catalog.deepSkyObjects
        val counts = Prominence.entries.map { level -> deepSky.count { it.prominence == level } }
        // The bundled data (Tools/README.md) has 253 showpieces, 234 prominent, 508 notable and 1446 obscure
        // deep-sky records; two notable ones, NGC 1990 and IC 1318, are the stars Alnilam and Sadr and not objects.
        assertEquals(2, catalog.loadReport.deepSkyObjects.merged)
        assertEquals(listOf(253, 234, 506, 1446), counts)
        assertEquals(993, deepSky.count { it.isListed })
        for (level in Prominence.entries) {
            val upTo = catalog.deepSkyObjects(level)
            assertEquals(deepSky.filter { it.prominence <= level }, upTo, "$level")
        }
        assertEquals(deepSky, catalog.deepSkyObjects(Prominence.OBSCURE))
        assertEquals(253, catalog.deepSkyObjects(Prominence.SHOWPIECE).size)
        assertEquals(253 + 234, catalog.deepSkyObjects(Prominence.PROMINENT).size)
        assertEquals(253 + 234 + 506, catalog.deepSkyObjects(Prominence.NOTABLE).size)
        assertTrue(catalog.deepSkyObjects(Prominence.SHOWPIECE).all { it.prominence == Prominence.SHOWPIECE })
    }

    @Test
    fun testProminenceFollowsTheDocumentedRules() {
        val catalog = catalog()
        for (obj in catalog.deepSkyObjects) {
            val isPlainStar = obj.category == ObjectCategory.STAR || obj.category == ObjectCategory.DOUBLE_STAR
            // Messier and Caldwell objects are showpieces.
            if (DeepSkyCatalogue.MESSIER in obj.catalogues || DeepSkyCatalogue.CALDWELL in obj.catalogues) {
                assertEquals(Prominence.SHOWPIECE, obj.prominence, obj.id)
            }
            // Anything brighter than magnitude 4 is a showpiece; brighter than 8 at least prominent.
            val magnitude = obj.magnitude
            if (magnitude != null && !isPlainStar) {
                if (magnitude <= 4.0) assertEquals(Prominence.SHOWPIECE, obj.prominence, obj.id)
                if (magnitude <= 8.0) assertTrue(obj.prominence <= Prominence.PROMINENT, obj.id)
                if (magnitude <= 10.0) assertTrue(obj.prominence <= Prominence.NOTABLE, "${obj.id} is bright enough to be listed")
            }
            // An object with a common name is never obscure.
            if (obj.name != obj.designations.first()) assertTrue(obj.prominence <= Prominence.NOTABLE, obj.id)
            // Dark nebulae have no magnitude: unnamed Lynds clouds are notable from 3 degrees across and
            // obscure under 20 arcminutes (in between it depends on their opacity).
            if (obj.id.startsWith("dso.LDN") && obj.name == obj.designations.first()) {
                val size = obj.angularSize?.majorArcminutes ?: 0.0
                if (size >= 180.0) assertEquals(Prominence.NOTABLE, obj.prominence, "${obj.id} ($size')")
                if (size < 20.0) assertEquals(Prominence.OBSCURE, obj.prominence, "${obj.id} ($size')")
            }
            // Listed means notable or better.
            assertEquals(obj.prominence <= Prominence.NOTABLE, obj.isListed, obj.id)
        }
        // Spot checks of the rules for objects without magnitude.
        assertEquals(Prominence.SHOWPIECE, obj("dso.B33").prominence)
        assertEquals(Prominence.SHOWPIECE, obj("dso.LDN1622").prominence)
        assertEquals(Prominence.SHOWPIECE, obj("dso.Sh2-129").prominence)
        assertEquals(Prominence.PROMINENT, obj("dso.B72").prominence)
        assertEquals(Prominence.PROMINENT, obj("dso.LDN1235").prominence)
        assertEquals(Prominence.PROMINENT, obj("dso.Sh2-261").prominence)
        assertEquals(Prominence.NOTABLE, obj("dso.Sh2-274").prominence, "the Medusa Nebula is named but small")
        assertEquals(Prominence.OBSCURE, obj("dso.B1").prominence)
        assertEquals(Prominence.OBSCURE, obj("dso.Sh2-4").prominence)
        assertEquals(Prominence.SHOWPIECE, obj("dso.M31").prominence)
        assertEquals(Prominence.SHOWPIECE, obj("dso.ESO56-115").prominence, "the Large Magellanic Cloud is brighter than 4")
        assertEquals(Prominence.OBSCURE, obj("dso.IC1459").prominence, "a magnitude 10.5 galaxy")
    }

    @Test
    fun testEverythingTheFirstReleaseListedIsStillListed() {
        val catalog = catalog()
        for (obj in catalog.deepSkyObjects) {
            val id = obj.id.removePrefix("dso.")
            val magnitude = obj.magnitude
            val named = obj.name != obj.designations.first()
            val size = obj.angularSize?.majorArcminutes ?: 0.0
            val openNgc = id.startsWith("NGC") || id.startsWith("IC")
            val plainStar = obj.category == ObjectCategory.STAR || obj.category == ObjectCategory.DOUBLE_STAR
            val nebula = obj.category in setOf(
                ObjectCategory.EMISSION_NEBULA, ObjectCategory.REFLECTION_NEBULA, ObjectCategory.NEBULA,
                ObjectCategory.SUPERNOVA_REMNANT, ObjectCategory.CLUSTER_WITH_NEBULA,
            )
            if (DeepSkyCatalogue.MESSIER in obj.catalogues || named) assertTrue(obj.isListed, obj.id)
            if (openNgc && !plainStar && magnitude != null && magnitude <= 10.0) assertTrue(obj.isListed, obj.id)
            if (openNgc && magnitude == null && nebula && size >= 10.0) assertTrue(obj.isListed, "${obj.id} is a large nebula")
        }
    }

    @Test
    fun testStarsAndBuiltInObjectsHaveTheirProminence() {
        val catalog = catalog()
        for (id in listOf("sun", "moon", "planet.mars", "planet.neptune", "special.galacticCenter", "special.ncp", "special.scp")) {
            assertEquals(Prominence.SHOWPIECE, obj(id).prominence, id)
        }
        assertEquals(Prominence.SHOWPIECE, obj("star.hip32349").prominence, "Sirius")
        assertEquals(Prominence.PROMINENT, obj("star.hip11767").prominence, "Polaris, magnitude 2.0")
        for (star in catalog.objects.filter { it.id.startsWith("star.") }) {
            assertEquals(Prominence.forStar(assertNotNull(star.magnitude)), star.prominence, star.id)
        }
        assertEquals(Prominence.SHOWPIECE, Prominence.forStar(-1.4))
        assertEquals(Prominence.SHOWPIECE, Prominence.forStar(1.5))
        assertEquals(Prominence.PROMINENT, Prominence.forStar(1.51))
        assertEquals(Prominence.PROMINENT, Prominence.forStar(3.5))
        assertEquals(Prominence.NOTABLE, Prominence.forStar(3.51))
        assertEquals(Prominence.NOTABLE, Prominence.forStar(5.0))
        assertEquals(Prominence.OBSCURE, Prominence.forStar(5.01))
        assertEquals(Prominence.OBSCURE, Prominence.forStar(Double.POSITIVE_INFINITY))
        val custom = CelestialObject.custom("My target", EquatorialCoordinate(10.0, 20.0))
        assertEquals(Prominence.SHOWPIECE, custom.prominence)
        assertTrue(custom.catalogNumbers.isEmpty() && custom.catalogues.isEmpty() && !custom.isDeepSky)
        assertTrue(obj("dso.M31").isDeepSky && !obj("star.hip32349").isDeepSky)
    }

    @Test
    fun testTheEnumerationsAndLabels() {
        assertEquals(listOf(1, 2, 3, 4), Prominence.entries.map { it.rawValue })
        assertTrue(Prominence.SHOWPIECE < Prominence.PROMINENT && Prominence.NOTABLE < Prominence.OBSCURE)
        assertEquals(Prominence.NOTABLE, Prominence.lastListed)
        for (level in Prominence.entries) {
            assertEquals(level, Prominence.fromRawValue(level.rawValue))
            assertFalse(level.displayName.isEmpty())
        }
        assertNull(Prominence.fromRawValue(0))
        assertNull(Prominence.fromRawValue(5))
        assertEquals(listOf("Showpiece", "Prominent", "Notable", "Obscure"), Prominence.entries.map { it.displayName })
        for (catalogue in DeepSkyCatalogue.entries) {
            assertEquals(catalogue, DeepSkyCatalogue.fromRawValue(catalogue.rawValue))
            assertFalse(catalogue.displayName.isEmpty() || catalogue.abbreviation.isEmpty())
        }
        assertNull(DeepSkyCatalogue.fromRawValue("Messier"))
        assertEquals(
            listOf("messier", "caldwell", "ngc", "ic", "sharpless", "barnard", "lynds", "collinder", "melotte", "trumpler", "stock"),
            DeepSkyCatalogue.entries.map { it.rawValue },
        )
        assertEquals("M 31", CatalogNumber(DeepSkyCatalogue.MESSIER, 31).label)
        assertEquals("NGC 7000", CatalogNumber(DeepSkyCatalogue.NGC, 7000).label)
        assertEquals("C 14", CatalogNumber(DeepSkyCatalogue.CALDWELL, 14).label)
        assertEquals("Sh2-155", CatalogNumber(DeepSkyCatalogue.SHARPLESS, 155).label)
        assertEquals("B 67a", CatalogNumber(DeepSkyCatalogue.BARNARD, 67, "a").label)
        assertEquals("LDN 1622", CatalogNumber(DeepSkyCatalogue.LYNDS, 1622).label)
        assertEquals("Cr 399", CatalogNumber(DeepSkyCatalogue.COLLINDER, 399).label)
        assertEquals("Mel 20", CatalogNumber(DeepSkyCatalogue.MELOTTE, 20).label)
    }

    // MARK: Designations

    @Test
    fun testCompactDesignationsAreSpelledAndParsed() {
        val spelled = listOf(
            "M31" to ("M 31" to listOf("Messier 31")),
            "NGC7000" to ("NGC 7000" to emptyList()),
            "C14" to ("Caldwell 14" to listOf("C 14")),
            "Sh2-155" to ("Sh2-155" to listOf("Sh 2-155", "Sharpless 155", "Sharpless 2-155")),
            "Sh2-45" to ("Sh2-45" to listOf("Sh 2-45", "Sharpless 45")),
            "LDN1622" to ("LDN 1622" to listOf("Lynds 1622", "L 1622")),
            "B67a" to ("Barnard 67a" to listOf("B 67a")),
            "Cl399" to ("Collinder 399" to listOf("Cl 399", "Cr 399")),
            "Mel20" to ("Melotte 20" to listOf("Mel 20")),
            "Tr24" to ("Trumpler 24" to listOf("Tr 24")),
            "St2" to ("Stock 2" to listOf("St 2")),
            "ESO56-115" to ("ESO 56-115" to emptyList()),
            "IC2431_NED02" to ("IC 2431 NED02" to emptyList()),
        )
        for ((id, expected) in spelled) {
            val spelling = CatalogDesignation.spelling(id)
            assertEquals(expected.first, spelling.display, id)
            assertEquals(expected.second, spelling.aliases, id)
        }
        val numbers = listOf(
            "M31" to CatalogNumber(DeepSkyCatalogue.MESSIER, 31), "NGC7000" to CatalogNumber(DeepSkyCatalogue.NGC, 7000),
            "IC434" to CatalogNumber(DeepSkyCatalogue.IC, 434), "C14" to CatalogNumber(DeepSkyCatalogue.CALDWELL, 14),
            "Sh2-155" to CatalogNumber(DeepSkyCatalogue.SHARPLESS, 155),
            "B67a" to CatalogNumber(DeepSkyCatalogue.BARNARD, 67, "a"),
            "LDN1622" to CatalogNumber(DeepSkyCatalogue.LYNDS, 1622), "Cl399" to CatalogNumber(DeepSkyCatalogue.COLLINDER, 399),
            "Mel20" to CatalogNumber(DeepSkyCatalogue.MELOTTE, 20), "Tr24" to CatalogNumber(DeepSkyCatalogue.TRUMPLER, 24),
            "St2" to CatalogNumber(DeepSkyCatalogue.STOCK, 2),
            "NGC2818A" to CatalogNumber(DeepSkyCatalogue.NGC, 2818, "A"),
        )
        for ((text, number) in numbers) {
            assertEquals(number, CatalogDesignation.catalogNumber(text), text)
            assertTrue(CatalogDesignation.isCompact(text), text)
        }
        for (text in listOf("ESO56-115", "H20", "PGC143", "UGC5373", "MWSC3171", "IC2431_NED02", "HCG92", "Sh2", "Sh2-", "")) {
            assertNull(CatalogDesignation.catalogNumber(text), text)
        }
        for (text in listOf("H20", "PGC143", "Sh2")) assertTrue(CatalogDesignation.isCompact(text), text)
        for (text in listOf("", "20", "C", "C0", "c20", "Sh2-", "Sh2-0", "Sh2-12345", "C1234567", "ABCDEFG1", "B67ab", "B67_", "C 20", "C-20", "ESO56-115")) {
            assertFalse(CatalogDesignation.isCompact(text), text)
        }
    }

    // MARK: Decoding the new members

    private fun deepSky(vararg members: Pair<String, String>): String =
        "{" + members.joinToString(",") { "\"${it.first}\":${it.second}" } + "}"

    private val base = arrayOf(
        "t" to "\"HII\"", "ra" to "314.8", "de" to "44.5", "c" to "\"Cyg\"", "maj" to "120", "min" to "30",
    )

    @Test
    fun testCrossDesignationsAndProminenceAreDecoded() {
        val record = deepSky(
            "id" to "\"NGC7000\"", "ngc" to "[7000]", "cat" to "[\"C20\",\"Sh2-117\"]",
            "names" to "[\"North America Nebula\"]", *base, "p" to "1",
        )
        val catalog = CatalogFixture.syntheticCatalog(deepSky = listOf(record))
        assertTrue(catalog.loadReport.isClean, "${catalog.loadReport.issues}")
        val obj = assertNotNull(catalog.objectWithID("dso.NGC7000"))
        assertEquals(listOf("NGC 7000", "Caldwell 20", "Sh2-117"), obj.designations)
        assertEquals("North America Nebula", obj.name)
        assertEquals(Prominence.SHOWPIECE, obj.prominence)
        assertTrue(obj.isListed)
        assertEquals(
            listOf(
                CatalogNumber(DeepSkyCatalogue.NGC, 7000), CatalogNumber(DeepSkyCatalogue.CALDWELL, 20),
                CatalogNumber(DeepSkyCatalogue.SHARPLESS, 117),
            ),
            obj.catalogNumbers,
        )
        assertEquals(listOf("dso.NGC7000"), ids(catalog.search("C 20")))
        assertEquals(listOf("dso.NGC7000"), ids(catalog.search("Sharpless 117")))
        assertEquals(listOf("dso.NGC7000"), ids(catalog.search("Sh 2-117")))
        assertEquals("Caldwell 20", catalog.search("C20").single().matchedText)
        assertEquals(listOf(obj), catalog.objects(DeepSkyCatalogue.CALDWELL))
        assertEquals(listOf(obj), catalog.objects(DeepSkyCatalogue.SHARPLESS))
        assertEquals(listOf(obj), catalog.objects(DeepSkyCatalogue.NGC))
        assertTrue(catalog.objects(DeepSkyCatalogue.MESSIER).isEmpty())
    }

    @Test
    fun testRecordsWithoutTheNewMembersLoadAsBefore() {
        val catalog = CatalogFixture.syntheticCatalog()
        assertTrue(catalog.loadReport.isClean)
        val obj = assertNotNull(catalog.objectWithID("dso.M1"))
        assertEquals(Prominence.NOTABLE, obj.prominence, "no p: notable")
        assertTrue(obj.isListed)
        assertEquals(listOf("M 1"), obj.designations)
        assertEquals(listOf(CatalogNumber(DeepSkyCatalogue.MESSIER, 1)), obj.catalogNumbers)
        assertEquals(listOf(obj), catalog.deepSkyObjects)
        assertEquals(listOf(obj), catalog.deepSkyObjects(Prominence.NOTABLE))
        assertTrue(catalog.deepSkyObjects(Prominence.PROMINENT).isEmpty())
        // null is the same as absent.
        val withNulls = CatalogFixture.syntheticCatalog(
            deepSky = listOf(deepSky("id" to "\"NGC1\"", "cat" to "null", "p" to "null", *base)),
        )
        assertTrue(withNulls.loadReport.isClean)
        assertEquals(Prominence.NOTABLE, withNulls.objectWithID("dso.NGC1")?.prominence)
    }

    @Test
    fun testObscureRecordsAreNotListedButCanBeFoundAndTheyAreInTheirCatalogue() {
        val records = listOf(
            deepSky("id" to "\"B5\"", "cat" to "[\"LDN100\"]", *base, "p" to "4"),
            deepSky("id" to "\"B6\"", *base, "p" to "3"),
            deepSky("id" to "\"B7\"", *base, "p" to "2"),
            deepSky("id" to "\"B7a\"", *base, "p" to "1"),
        )
        val catalog = CatalogFixture.syntheticCatalog(deepSky = records)
        assertTrue(catalog.loadReport.isClean, "${catalog.loadReport.issues}")
        val obscure = assertNotNull(catalog.objectWithID("dso.B5"))
        assertEquals(Prominence.OBSCURE, obscure.prominence)
        assertFalse(obscure.isListed)
        assertFalse(catalog.listedObjects.any { it.id == "dso.B5" })
        assertTrue(catalog.objects(ObjectGroup.NEBULAE).none { it.id == "dso.B5" })
        assertEquals(listOf("dso.B6", "dso.B7", "dso.B7a"), catalog.objects(ObjectGroup.NEBULAE).map { it.id })
        assertEquals(listOf("dso.B5"), ids(catalog.search("B5")).take(1))
        assertEquals(listOf("dso.B5", "dso.B6", "dso.B7", "dso.B7a"), catalog.objects(DeepSkyCatalogue.BARNARD).map { it.id })
        assertEquals(listOf("dso.B5"), catalog.objects(DeepSkyCatalogue.LYNDS).map { it.id })
        assertEquals(listOf("dso.B7a"), catalog.deepSkyObjects(Prominence.SHOWPIECE).map { it.id })
        assertEquals(listOf("dso.B7", "dso.B7a"), catalog.deepSkyObjects(Prominence.PROMINENT).map { it.id })
        assertEquals(listOf("dso.B6", "dso.B7", "dso.B7a"), catalog.deepSkyObjects(Prominence.NOTABLE).map { it.id })
        assertEquals(4, catalog.deepSkyObjects(Prominence.OBSCURE).size)
        assertEquals(listOf("B 5", "B 6", "B 7", "B 7a"), catalog.objects(DeepSkyCatalogue.BARNARD).map { it.catalogNumbers.first().label })
    }

    @Test
    fun testSearchRanksProminenceBeforeMagnitude() {
        val records = listOf(
            deepSky("id" to "\"NGC101\"", "ngc" to "[101]", "t" to "\"G\"", "ra" to "10", "de" to "10", "c" to "\"And\"", "vm" to "11.0", "p" to "4"),
            deepSky("id" to "\"NGC102\"", "ngc" to "[102]", "t" to "\"DrkN\"", "ra" to "10", "de" to "10", "c" to "\"And\"", "p" to "1"),
            deepSky("id" to "\"NGC103\"", "ngc" to "[103]", "t" to "\"G\"", "ra" to "10", "de" to "10", "c" to "\"And\"", "vm" to "9.0", "p" to "3"),
            deepSky("id" to "\"NGC104\"", "ngc" to "[104]", "t" to "\"G\"", "ra" to "10", "de" to "10", "c" to "\"And\"", "vm" to "10.0", "p" to "3"),
            deepSky("id" to "\"NGC105\"", "ngc" to "[105]", "t" to "\"G\"", "ra" to "10", "de" to "10", "c" to "\"And\"", "p" to "3"),
        )
        val catalog = CatalogFixture.syntheticCatalog(deepSky = records)
        assertEquals(
            listOf("dso.NGC102", "dso.NGC103", "dso.NGC104", "dso.NGC105", "dso.NGC101"),
            ids(catalog.search("ngc 10", limit = 10)),
            "the unlisted object comes last; then prominence, magnitude (unknown last), name",
        )
    }

    // MARK: Malformed variants

    private fun rejected(vararg members: Pair<String, String>): Catalog {
        val catalog = CatalogFixture.syntheticCatalog(deepSky = listOf(CatalogFixture.defaultDeepSky, deepSky(*members)))
        assertEquals(1, catalog.loadReport.deepSkyObjects.accepted, "$members")
        assertEquals(1, catalog.loadReport.deepSkyObjects.rejected, "$members")
        assertEquals(listOf("dso.M1"), catalog.objects.filter { it.isDeepSky }.map { it.id })
        return catalog
    }

    private fun accepted(vararg members: Pair<String, String>): Catalog {
        val catalog = CatalogFixture.syntheticCatalog(deepSky = listOf(CatalogFixture.defaultDeepSky, deepSky(*members)))
        assertEquals(2, catalog.loadReport.deepSkyObjects.accepted, "${catalog.loadReport.issues}")
        assertTrue(catalog.loadReport.isClean)
        return catalog
    }

    @Test
    fun testProminenceOutsideOneToFourRejectsTheRecord() {
        for (value in listOf("0", "5", "-1", "100", "9223372036854775807", "-9223372036854775808")) {
            val catalog = rejected("id" to "\"NGC1\"", *base, "p" to value)
            assertEquals("dsos[1]: p $value out of range", catalog.loadReport.issues.single(), value)
        }
        for (value in listOf("1", "2", "3", "4")) accepted("id" to "\"NGC1\"", *base, "p" to value)
    }

    @Test
    fun testProminenceOfTheWrongTypeMakesTheRecordUnreadable() {
        for (value in listOf("\"2\"", "2.5", "true", "[2]", "{}")) {
            val catalog = rejected("id" to "\"NGC1\"", *base, "p" to value)
            assertEquals("dsos[1]: record has the wrong shape", catalog.loadReport.issues.single(), value)
        }
    }

    @Test
    fun testMalformedCrossDesignationsRejectTheRecord() {
        val bad = listOf(
            "ESO56-115", "20", "C", "C0", "Sh2-", "Sh2-0", "Sh2-12345", "C1234567", "B67ab", "C 20", " ", "",
            "Sh2-1a", "ÄB12",
        )
        for (entry in bad) {
            val catalog = rejected("id" to "\"NGC1\"", *base, "cat" to "[\"$entry\"]")
            val issue = catalog.loadReport.issues.single()
            assertTrue(issue.startsWith("dsos[1]: cat"), "$entry: $issue")
        }
        assertEquals(
            "dsos[1]: cat entry ESO56-115 is not a catalogue designation",
            rejected("id" to "\"NGC1\"", *base, "cat" to "[\"ESO56-115\"]").loadReport.issues.single(),
        )
        assertEquals(
            "dsos[1]: cat is too long",
            rejected("id" to "\"NGC1\"", *base, "cat" to "[\"${"A".repeat(17)}1\"]").loadReport.issues.single(),
        )
        assertEquals(
            "dsos[1]: cat contains control characters",
            rejected("id" to "\"NGC1\"", *base, "cat" to "[\"C\\u00002\"]").loadReport.issues.single(),
        )
        val seventeen = (1..17).joinToString(",") { "\"C$it\"" }
        assertEquals(
            "dsos[1]: cat has too many entries",
            rejected("id" to "\"NGC1\"", *base, "cat" to "[$seventeen]").loadReport.issues.single(),
        )
        accepted("id" to "\"NGC1\"", *base, "cat" to "[${(1..16).joinToString(",") { "\"C$it\"" }}]")
    }

    @Test
    fun testCrossDesignationsOfTheWrongShapeMakeTheRecordUnreadable() {
        for (value in listOf("\"C20\"", "[20]", "[null]", "[[\"C20\"]]", "{}", "true", "[\"C20\",5]")) {
            val catalog = rejected("id" to "\"NGC1\"", *base, "cat" to value)
            assertEquals("dsos[1]: record has the wrong shape", catalog.loadReport.issues.single(), value)
        }
    }

    @Test
    fun testCrossDesignationsAreTrimmedAndDeduplicated() {
        val catalog = accepted("id" to "\"NGC1\"", "ngc" to "[1]", *base, "cat" to "[\" C20 \",\"C20\",\"NGC1\",\"Sh2-117\",\"Sh2-117\"]")
        val obj = assertNotNull(catalog.objectWithID("dso.NGC1"))
        // NGC1 repeats the object's own NGC number: the designation is shown once.
        assertEquals(listOf("NGC 1", "Caldwell 20", "Sh2-117"), obj.designations)
        assertEquals(3, obj.catalogNumbers.size)
    }

    @Test
    fun testTheSchemaVersionStaysOne() {
        assertEquals(1, Catalog.supportedSchemaVersion)
        val data = Catalog.bundledData(CatalogFile.DEEP_SKY_OBJECTS)
        assertTrue(String(data, 0, 12, Charsets.UTF_8).startsWith("{\"schema\":1,"))
        assertTrue(data.size < 600 * 1024, "dsos.json is ${data.size} bytes")
    }
}
