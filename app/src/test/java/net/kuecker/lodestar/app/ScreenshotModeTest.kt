package net.kuecker.lodestar.app

import net.kuecker.lodestar.features.mosaic.MosaicSource
import net.kuecker.lodestar.kit.events.EventKind
import net.kuecker.lodestar.services.OnlineFeed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScreenshotModeTest {
    private val base = "date=2026-07-17T05:30:00Z lat=36.0544 lon=-112.1401 site=Grand_Canyon"

    @Test
    fun theStoreArgumentsStillParseAndOpenNoScreen() {
        val mode = assertNotNull(ScreenshotMode.parse("$base tab=tonight target=dso.M8 pointing=178,22 night=1"))
        assertEquals(AppTab.TONIGHT, mode.tab)
        assertEquals("Grand Canyon", mode.siteName)
        assertTrue(mode.nightMode)
        assertNull(mode.screen)
        assertFalse(mode.hasSkyline)
        assertFalse(mode.showsCamera)
    }

    @Test
    fun readsTheKeysOfTheReviewScreens() {
        val mode = assertNotNull(
            ScreenshotMode.parse("$base screen=event-detail event=meteorShower mosaic=milkyWay skyline=1 camera=1 focal=600"),
        )
        assertEquals(ScreenshotScreen.EVENT_DETAIL, mode.screen)
        assertEquals(EventKind.METEOR_SHOWER, mode.eventKind)
        assertEquals(MosaicSource.MILKY_WAY, mode.mosaicSource)
        assertTrue(mode.hasSkyline)
        assertTrue(mode.showsCamera)
        assertEquals(600.0, mode.focalLength)
    }

    @Test
    fun readsTheOnlineFeedsAndTheSpacesOfATarget() {
        val mode = assertNotNull(ScreenshotMode.parse("$base online=satellites,comets target=comet.C/2026_T1"))
        assertEquals(setOf(OnlineFeed.SATELLITE_PASSES, OnlineFeed.COMETS), mode.onlineFeeds)
        assertEquals("comet.C/2026 T1", mode.targetID)
        assertTrue(assertNotNull(ScreenshotMode.parse(base)).onlineFeeds.isEmpty())
        assertNull(ScreenshotMode.parse("$base online=satellites,weather"))
    }

    @Test
    fun everyScreenHasItsOwnId() {
        assertEquals(ScreenshotScreen.entries.size, ScreenshotScreen.entries.map { it.id }.toSet().size)
        for (screen in ScreenshotScreen.entries) assertEquals(screen, ScreenshotScreen.named(screen.id))
    }

    @Test
    fun ignoresUnknownKeysAndStrayWords() {
        val mode = assertNotNull(ScreenshotMode.parse("$base colour=blue stray =5 screen=mosaic"))
        assertEquals(ScreenshotScreen.MOSAIC, mode.screen)
    }

    @Test
    fun aValueThatDoesNotExistInvalidatesTheArgument() {
        assertNull(ScreenshotMode.parse("$base screen=no-such-screen"))
        assertNull(ScreenshotMode.parse("$base event=comet"))
        assertNull(ScreenshotMode.parse("$base mosaic=everything"))
        assertNull(ScreenshotMode.parse("$base focal=0"))
        assertNull(ScreenshotMode.parse("date=today lat=36 lon=-112"))
    }

    @Test
    fun theMadeUpSkylineIsAValidProfileWithRelief() {
        val skyline = ScreenshotMode.demoSkyline()
        assertFalse(skyline.isFlat)
        assertTrue(skyline.minimumAltitude >= 0.0)
        assertTrue(skyline.maximumAltitude in 10.0..40.0)
    }
}
