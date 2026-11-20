package net.kuecker.lodestar.features.tonight

import net.kuecker.lodestar.kit.catalog.Catalog
import net.kuecker.lodestar.kit.foundation.GeographicLocation
import net.kuecker.lodestar.kit.foundation.Observer
import net.kuecker.lodestar.kit.horizon.HorizonProfile
import net.kuecker.lodestar.model.LocationSource
import net.kuecker.lodestar.model.ObserverContext
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TonightClearHoursTest {
    private val zone = ZoneId.of("America/Denver")
    private val observer = Observer(checkNotNull(GeographicLocation.validated(40.0, -105.3, 1650.0)))
    private val now = Instant.parse("2026-10-18T04:00:00Z")
    private val catalog: Catalog by lazy { Catalog.loadBundled() }

    private fun ranked(horizon: HorizonProfile?): List<TonightTarget> {
        val context = ObserverContext(observer, zone, "Ridge", LocationSource.Device, horizon = horizon)
        val report = TonightCalculator.report(TonightInput(TonightNightKey(context, TonightNightChoice.Tonight), now, catalog))
        return (report.targets as TonightTargetsOutcome.Ranked).targets
    }

    @Test
    fun withASkylineEveryTargetSaysHowLongItIsClearOfIt() {
        val targets = ranked(HorizonProfile.constant(12.0))
        assertTrue(targets.isNotEmpty())
        for (target in targets) {
            assertEquals(target.hoursAboveMinimum, assertNotNull(target.unobstructedHours), target.obj.name)
        }
    }

    @Test
    fun withoutASkylineThereIsNoSuchNumberAndTheRankingIsTheOldOne() {
        val targets = ranked(null)
        assertTrue(targets.isNotEmpty())
        assertTrue(targets.all { it.unobstructedHours == null })
        assertEquals(targets, ranked(HorizonProfile.flat))
    }

    @Test
    fun theClearHoursBonusIsProportionalUpToItsCap() {
        val m31 = assertNotNull(catalog.objectWithID("dso.M31"))
        val base = TonightCalculator.score(m31, 60.0)
        assertEquals(base, TonightCalculator.score(m31, 60.0, null))
        assertEquals(base, TonightCalculator.score(m31, 60.0, 0.0), 1e-12)
        assertEquals(base + 0.2, TonightCalculator.score(m31, 60.0, 2.0), 1e-12)
        assertEquals(base + 0.1 * TonightCalculator.clearHoursCap, TonightCalculator.score(m31, 60.0, 6.0), 1e-12)
        assertEquals(TonightCalculator.score(m31, 60.0, 6.0), TonightCalculator.score(m31, 60.0, 11.0), 1e-12)
        assertNull(ranked(null).firstOrNull()?.unobstructedHours)
    }
}
