package net.kuecker.lodestar.features.smallbodies

import net.kuecker.lodestar.features.smallbodies.SmallBodyFixtures as Fixtures
import net.kuecker.lodestar.kit.comets.SmallBodyKind
import net.kuecker.lodestar.kit.foundation.SkyFrame
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SmallBodyTextTest {
    private val frame = SkyFrame(Fixtures.date, Fixtures.observer)

    @BeforeTest
    fun locale() = Fixtures.useUSLocale()

    @Test
    fun theCompactNameIsTheCommonNameElseTheDesignation() {
        assertEquals("Tsuchinshan-ATLAS", SmallBodyText.displayName(Fixtures.obj(Fixtures.tsuchinshanAtlas)))
        assertEquals("Pons-Brooks", SmallBodyText.displayName(Fixtures.obj(Fixtures.ponsBrooks)))
        assertEquals("Vesta", SmallBodyText.displayName(Fixtures.obj(Fixtures.vesta)))
        assertEquals("2024 YR4", SmallBodyText.displayName(Fixtures.obj(Fixtures.yr4)))
        // Other objects keep their name.
        val mars = assertNotNull(Fixtures.bundled.objectWithID("planet.mars"))
        assertEquals(mars.name, SmallBodyText.displayName(mars))
        // The full name stays the formal title.
        assertEquals("C/2023 A3 (Tsuchinshan-ATLAS)", Fixtures.obj(Fixtures.tsuchinshanAtlas).name)
    }

    @Test
    fun theMagnitudeIsWrittenAsAnEstimate() {
        assertEquals("mag ≈ 7.4", SmallBodyText.magnitude(7.4))
        assertEquals("mag ≈ 7.4", SmallBodyText.magnitude(7.43))
        assertEquals("mag ≈ −0.3", SmallBodyText.magnitude(-0.3))
        assertEquals("mag ≈ 0.0", SmallBodyText.magnitude(-0.04))
        assertEquals("no brightness data", SmallBodyText.magnitude(null))
        assertEquals("No brightness data", SmallBodyText.magnitude(null, sentence = true))
        assertEquals("no brightness data", SmallBodyText.magnitude(Double.NaN))
    }

    @Test
    fun theWarningsReadAsPhrasesAndAsSentences() {
        assertEquals("in the twilight glow", SmallBodyText.warning(SmallBodyWarning.TWILIGHT_GLOW))
        assertEquals("position approximate", SmallBodyText.warning(SmallBodyWarning.POSITION_APPROXIMATE))
        assertEquals("no brightness data", SmallBodyText.warning(SmallBodyWarning.NO_BRIGHTNESS_DATA))
        assertEquals("In the twilight glow", SmallBodyText.sentence(SmallBodyWarning.TWILIGHT_GLOW))
        assertEquals("Position approximate", SmallBodyText.sentence(SmallBodyWarning.POSITION_APPROXIMATE))
        assertEquals("No brightness data", SmallBodyText.sentence(SmallBodyWarning.NO_BRIGHTNESS_DATA))
    }

    @Test
    fun aRowSaysKindMagnitudeAndElongation() {
        val obj = Fixtures.obj(Fixtures.vesta)
        val info = assertNotNull(SmallBodyInfo.compute(Fixtures.vesta, frame)).copy(magnitude = 7.4, elongation = 62.0)
        assertEquals("Asteroid · mag ≈ 7.4 · 62° from the Sun", SmallBodyText.summary(obj, info))
        assertEquals("Asteroid · mag ≈ 7.4 · 62° from the Sun", SmallBodyText.subtitle(obj, info, isComputed = true))
    }

    @Test
    fun aListRowPutsTheElongationOnItsOwnLineAndKeepsEachValueWhole() {
        val obj = Fixtures.obj(Fixtures.vesta)
        val info = assertNotNull(SmallBodyInfo.compute(Fixtures.vesta, frame)).copy(magnitude = 7.4, elongation = 62.0)
        assertEquals(
            "Asteroid · mag ≈ 7.4\n62° from the Sun",
            SmallBodyText.rowSubtitle(obj, info, isComputed = true),
        )
        assertEquals("Asteroid", SmallBodyText.rowSubtitle(obj, null, isComputed = false))
    }

    @Test
    fun aRowAppendsTheWarnings() {
        val obj = Fixtures.obj(Fixtures.withoutMagnitude)
        val info = assertNotNull(SmallBodyInfo.compute(Fixtures.withoutMagnitude, frame))
            .copy(elongation = 18.0, elementsAgeDays = 400.0)
        assertEquals(
            "Asteroid · no brightness data · 18° from the Sun · in the twilight glow · position approximate",
            SmallBodyText.subtitle(obj, info, isComputed = true),
        )
        assertEquals(
            "Asteroid · no brightness data · 18° from the Sun",
            SmallBodyText.subtitle(obj, info, isComputed = true, withWarnings = false),
        )
    }

    @Test
    fun aRowBeforeThePositionIsKnownSaysOnlyTheKind() {
        val obj = Fixtures.obj(Fixtures.tsuchinshanAtlas)
        assertEquals("Comet", SmallBodyText.subtitle(obj, null, isComputed = false))
        assertEquals("Comet · no current orbit", SmallBodyText.subtitle(obj, null, isComputed = true))
    }

    @Test
    fun aSpokenRowIsWrittenOut() {
        val obj = Fixtures.obj(Fixtures.tsuchinshanAtlas)
        val info = assertNotNull(SmallBodyInfo.compute(Fixtures.tsuchinshanAtlas, frame)).copy(magnitude = 7.4, elongation = 62.0)
        assertEquals(
            "Comet, magnitude about 7.4, 62 degrees from the Sun",
            SmallBodyText.subtitle(obj, info, isComputed = true, spoken = true),
        )
    }

    @Test
    fun theRealNumbersFormatLikeThePattern() {
        val obj = Fixtures.obj(Fixtures.tsuchinshanAtlas)
        // Whatever the comet's elongation on the date, the line has this shape (with the elongation made harmless).
        val info = assertNotNull(SmallBodyInfo.compute(Fixtures.tsuchinshanAtlas, Fixtures.date)).copy(elongation = 90.0)
        val text = SmallBodyText.summary(obj, info)
        assertTrue(Regex("^Comet · mag ≈ −?\\d+\\.\\d · 90° from the Sun$").matches(text), text)
        assertTrue(SmallBodyText.summary(obj, Fixtures.date).startsWith("Comet · mag ≈ "))
        // Anything but a comet or an asteroid is just its kind.
        assertEquals("Planet", SmallBodyText.summary(assertNotNull(Fixtures.bundled.objectWithID("planet.mars")), Fixtures.date))
    }

    @Test
    fun theTailIsDescribed() {
        assertEquals("Straight up", SmallBodyText.tailDirection(0.2))
        assertEquals("Straight down", SmallBodyText.tailDirection(180.0))
        assertEquals("35° to the right of up", SmallBodyText.tailDirection(35.2))
        assertEquals("120° to the left of up", SmallBodyText.tailDirection(-119.6))
        assertEquals("None needed", SmallBodyText.cameraTilt(0.3))
        assertEquals("12° clockwise", SmallBodyText.cameraTilt(12.0))
        assertEquals("12° counter-clockwise", SmallBodyText.cameraTilt(-12.0))
        assertEquals("Ion tail direction (approximate)", SmallBodyText.TAIL_TITLE)
    }

    @Test
    fun identifiersTellComets() {
        assertEquals(SmallBodyKind.COMET, SmallBodyText.kindOfID("comet.12P"))
        assertEquals(SmallBodyKind.ASTEROID, SmallBodyText.kindOfID("asteroid.4"))
        assertNull(SmallBodyText.kindOfID("planet.mars"))
        assertNull(SmallBodyText.kindOfID("dso.M31"))
        assertEquals("C/2023 A3", SmallBodyText.designationOfID("comet.C/2023 A3"))
        assertEquals("12P", SmallBodyText.designationOfID("comet.12P"))
    }

    @Test
    fun theOrbitElementsHaveADate() {
        assertEquals("Oct 17, 2026", SmallBodyText.elementsDate(Fixtures.tsuchinshanAtlas))
        assertEquals("Jun 9, 2026", SmallBodyText.elementsDate(Fixtures.vesta))
    }

    @Test
    fun aMosaicIsRefusedForAMovingTarget() {
        val text = SmallBodyText.mosaicRefusal(Fixtures.obj(Fixtures.ponsBrooks))
        assertEquals("Pons-Brooks moves among the stars, so a mosaic cannot follow it. Plan a custom size instead.", text)
        assertFalse(text.contains("iPhone") || text.contains("iOS"))
    }
}
