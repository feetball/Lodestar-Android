package net.kuecker.lodestar.features.mosaic

import net.kuecker.lodestar.kit.mosaic.MosaicOrientation
import net.kuecker.lodestar.kit.mosaic.MosaicPlan
import net.kuecker.lodestar.kit.mosaic.MosaicPlanner
import net.kuecker.lodestar.kit.mosaic.MosaicRegion
import net.kuecker.lodestar.kit.phototools.LensSetup
import net.kuecker.lodestar.kit.phototools.SensorFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MosaicSessionTest {
    /** A 60° × 30° patch of sky shot with an 85 mm lens: several panels. */
    private fun plan(): MosaicPlan = MosaicPlanner.plan(
        MosaicRegion.centered(180.0, 30.0, 60.0, 30.0),
        LensSetup(SensorFormat.fullFrame, 85.0, 2.8, 4.3),
        MosaicOrientation.LANDSCAPE, 0.25, 40.0,
    )

    @Test
    fun startsAtTheFirstPanelInShootingOrder() {
        val session = MosaicSession()
        assertFalse(session.isActive)
        assertNull(session.spokenStatus)
        val plan = plan()
        session.start(plan, "Test patch")
        assertTrue(session.isActive)
        assertEquals(0, session.step)
        assertEquals(plan.panels.size, session.panelCount)
        assertTrue(session.panelCount > 4)
        assertEquals(plan.orderedPanels.first(), session.currentPanel)
        assertEquals("Panel 1 of ${session.panelCount}", session.progressText)
        assertNotNull(session.spokenStatus)
        assertFalse(session.isComplete)
    }

    @Test
    fun movesThroughThePanelsAndCompletes() {
        val session = MosaicSession()
        val plan = plan()
        session.start(plan, "Test patch")
        for (expected in plan.orderedPanels) {
            assertEquals(expected, session.currentPanel)
            session.next()
        }
        assertTrue(session.isComplete)
        assertNull(session.currentPanel)
        assertEquals("All ${plan.panels.size} panels shot", session.progressText)
        session.next()
        assertEquals(plan.panels.size, session.step)
        // Back from the end returns to the last panel.
        session.previous()
        assertEquals(plan.orderedPanels.last(), session.currentPanel)
        assertFalse(session.isComplete)
    }

    @Test
    fun previousStopsAtTheFirstPanel() {
        val session = MosaicSession()
        session.start(plan(), "Test patch")
        session.previous()
        assertEquals(0, session.step)
        session.next()
        session.previous()
        assertEquals(0, session.step)
    }

    @Test
    fun finishingEndsTheMosaic() {
        val session = MosaicSession()
        session.start(plan(), "Test patch")
        session.next()
        session.finish()
        assertFalse(session.isActive)
        assertNull(session.plan)
        assertNull(session.currentPanel)
        assertEquals(0, session.step)
        assertEquals(0, session.panelCount)
        // Steps without a mosaic do nothing.
        session.next()
        session.previous()
        assertEquals(0, session.step)
    }

    @Test
    fun startingAgainReplacesThePlanAndRestarts() {
        val session = MosaicSession()
        session.start(plan(), "First")
        session.next()
        session.next()
        val second = plan()
        session.start(second, "Second")
        assertEquals(0, session.step)
        assertEquals("Second", session.title)
        assertEquals(second, session.plan)
    }

    @Test
    fun theSetupSurvivesAMosaic() {
        val session = MosaicSession()
        session.setup = session.setup.copy(overlap = 0.4, source = MosaicSource.CUSTOM)
        session.start(plan(), "Test patch")
        session.finish()
        assertEquals(0.4, session.setup.overlap)
        assertEquals(MosaicSource.CUSTOM, session.setup.source)
    }
}
