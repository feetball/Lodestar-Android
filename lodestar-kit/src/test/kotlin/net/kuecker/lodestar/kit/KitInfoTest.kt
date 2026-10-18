package net.kuecker.lodestar.kit

import kotlin.test.Test
import kotlin.test.assertEquals

class KitInfoTest {
    @Test
    fun name() = assertEquals("LodestarKit", KitInfo.NAME)
}
