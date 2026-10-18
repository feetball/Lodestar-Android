package net.kuecker.lodestar.features.widget

import net.kuecker.lodestar.model.NightModeSetting
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class WidgetSnapshotCacheTest {
    private val inputs = WidgetInputs(WidgetPlace("Cache test", 12.5, 45.5, 10.0, timeZoneID = "Europe/Rome"))
    private val now = Instant.parse("2026-10-18T10:00:00Z")

    @Test
    fun aRecentSnapshotIsSharedByTheRefreshAndTheRedraw() {
        val first = WidgetSnapshotCache.recompute(inputs, now)
        assertSame(first, WidgetSnapshotCache.snapshot(inputs, now.plusSeconds(5)))
    }

    @Test
    fun otherInputsGetTheirOwnSnapshot() {
        val first = WidgetSnapshotCache.recompute(inputs, now)
        val other = inputs.copy(place = inputs.place?.copy(name = "Elsewhere", latitude = 13.0))
        assertNotSame(first, WidgetSnapshotCache.snapshot(other, now))
    }

    @Test
    fun aSnapshotIsNotUsedPastItsNextRefresh() {
        val first = WidgetSnapshotCache.recompute(inputs, now)
        val later = first.nextRefresh.plusSeconds(1)
        assertNotSame(first, WidgetSnapshotCache.snapshot(inputs, later))
    }

    @Test
    fun aSnapshotIsNotUsedForAnEarlierTime() {
        val first = WidgetSnapshotCache.recompute(inputs, now)
        assertNotSame(first, WidgetSnapshotCache.snapshot(inputs, now.minusSeconds(60)))
    }

    @Test
    fun aSnapshotIsNotKeptForever() {
        val ownInputs = inputs.copy(nightMode = NightModeSetting.OFF)
        val first = WidgetSnapshotCache.recompute(ownInputs, now)
        assertNotSame(first, WidgetSnapshotCache.snapshot(ownInputs, now.plusSeconds(601)))
    }
}
