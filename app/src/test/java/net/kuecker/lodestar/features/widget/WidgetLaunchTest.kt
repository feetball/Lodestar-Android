package net.kuecker.lodestar.features.widget

import net.kuecker.lodestar.app.AppTab
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WidgetLaunchTest {
    @Test
    fun theWidgetsOpenTheTonightTab() {
        assertEquals(AppTab.TONIGHT, WidgetLaunch.tabNamed(AppTab.TONIGHT.name))
    }

    @Test
    fun everyTabCanBeNamedAndNothingElse() {
        for (tab in AppTab.entries) assertEquals(tab, WidgetLaunch.tabNamed(tab.name))
        assertNull(WidgetLaunch.tabNamed(null))
        assertNull(WidgetLaunch.tabNamed(""))
        assertNull(WidgetLaunch.tabNamed("tonight"))
        assertNull(WidgetLaunch.tabNamed("MOUNT"))
    }
}
