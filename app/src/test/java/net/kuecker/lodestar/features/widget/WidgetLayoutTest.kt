package net.kuecker.lodestar.features.widget

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WidgetLayoutTest {
    private fun kind(width: Int, height: Int) = WidgetSizes.kind(DpSize(width.dp, height.dp))

    @Test
    fun aSquareTwoByTwoShowsTheMoonAndTheDarkness() {
        assertEquals(WidgetLayoutKind.SMALL, kind(110, 110))
        assertEquals(WidgetLayoutKind.SMALL, kind(100, 90))
        assertEquals(WidgetLayoutKind.SMALL, kind(146, 110))
    }

    @Test
    fun threeCellsWideAndTwoHighAddsThePhaseBesideTheMoon() {
        // Too narrow for two tiles side by side, but wide enough to say more than the smallest layout.
        assertEquals(WidgetLayoutKind.SMALL_WIDE, kind(160, 110))
        assertEquals(WidgetLayoutKind.SMALL_WIDE, kind(180, 110))
        assertEquals(WidgetLayoutKind.SMALL_WIDE, kind(200, 110))
        // A square keeps the roomy layout.
        assertEquals(WidgetLayoutKind.SMALL_ROOMY, kind(150, 150))
    }

    @Test
    fun aRoomierTwoByTwoAddsThePhaseOfTheMoon() {
        assertEquals(WidgetLayoutKind.SMALL_ROOMY, kind(120, 120))
        assertEquals(WidgetLayoutKind.SMALL_ROOMY, kind(146, 146))
        assertEquals(WidgetLayoutKind.SMALL, kind(119, 130))
    }

    @Test
    fun aWideTwoCellsHighWidgetShowsAllFourInAGrid() {
        assertEquals(WidgetLayoutKind.GRID, kind(250, 110))
        assertEquals(WidgetLayoutKind.GRID, kind(320, 120))
    }

    @Test
    fun roomToGrowAddsLines() {
        assertEquals(WidgetLayoutKind.GRID_DETAIL, kind(250, 180))
        assertEquals(WidgetLayoutKind.LIST, kind(250, 250))
        assertEquals(WidgetLayoutKind.LIST_TALL, kind(320, 320))
    }

    @Test
    fun aNarrowTallWidgetShowsAllFourOneUnderTheOther() {
        assertEquals(WidgetLayoutKind.LIST_NARROW, kind(110, 180))
        assertEquals(WidgetLayoutKind.LIST_NARROW, kind(180, 180))
    }

    @Test
    fun aNarrowWidgetTallEnoughForTwoLinesEachGivesEveryValueTwo() {
        assertEquals(WidgetLayoutKind.LIST_NARROW_TALL, kind(110, 230))
        assertEquals(WidgetLayoutKind.LIST_NARROW_TALL, kind(146, 230))
        assertEquals(WidgetLayoutKind.LIST_NARROW_TALL, kind(110, 250))
        assertEquals(WidgetLayoutKind.LIST_NARROW, kind(110, 219))
    }

    @Test
    fun everyLayoutHasASizeThatChoosesIt() {
        val chosen = WidgetSizes.all.map { WidgetSizes.kind(it) }
        assertEquals(WidgetLayoutKind.entries.toSet(), chosen.toSet())
        assertEquals(WidgetSizes.all.size, chosen.toSet().size)
    }

    @Test
    fun theSizesOfThePickerPreviewsGetTheLayoutsTheyAreFor() {
        assertEquals(WidgetLayoutKind.SMALL, WidgetSizes.kind(WidgetSizes.previewSmall))
        assertEquals(WidgetLayoutKind.GRID, WidgetSizes.kind(WidgetSizes.previewMedium))
        // Both are the cell sizes of the picker: 2 x 2 and 4 x 2 (about 70 dp a cell less 30 dp).
        assertTrue(abs(WidgetSizes.previewMedium.width.value - (4 * 70 - 30)) <= 0.5f)
        assertTrue(abs(WidgetSizes.previewSmall.width.value - (2 * 70 - 30)) <= 0.5f)
    }
}
