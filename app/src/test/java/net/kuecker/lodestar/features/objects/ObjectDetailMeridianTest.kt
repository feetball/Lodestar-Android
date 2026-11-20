package net.kuecker.lodestar.features.objects

import net.kuecker.lodestar.kit.catalog.Catalog
import net.kuecker.lodestar.kit.catalog.apparentEquatorial
import net.kuecker.lodestar.kit.foundation.EquatorialCoordinate
import net.kuecker.lodestar.kit.foundation.GeographicLocation
import net.kuecker.lodestar.kit.foundation.Observer
import net.kuecker.lodestar.kit.foundation.SkyFrame
import net.kuecker.lodestar.kit.foundation.addingTimeInterval
import net.kuecker.lodestar.kit.foundation.timeIntervalSince
import net.kuecker.lodestar.model.CoordinateStyle
import java.time.Instant
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ObjectDetailMeridianTest {
    private val catalog: Catalog by lazy { Catalog.loadBundled() }
    private val observer = Observer(checkNotNull(GeographicLocation.validated(40.0, -105.27, 1650.0)))
    private val date = Instant.parse("2026-01-16T04:00:00Z")

    private fun text(hours: Double, style: CoordinateStyle = CoordinateStyle.SEXAGESIMAL) =
        ObjectDetailMeridian.hourAngleText(hours, style)

    @Test
    fun theHourAngleReadsInHoursAndMinutesWithTheSignOfTheSide() {
        assertEquals("−1h 23m", text(-(1 + 23.0 / 60)))
        assertEquals("+0h 25m", text(25.0 / 60))
        assertEquals("−11h 59m", text(-11.99))
        assertEquals("+12h 00m", text(11.999))
        // Within half a minute of the meridian it reads zero, without a sign.
        assertEquals("0h 00m", text(0.0))
        assertEquals("0h 00m", text(-0.004))
        assertEquals("0h 00m", text(0.004))
        assertEquals("—", text(Double.NaN))
    }

    @Test
    fun theDecimalStyleReadsInThousandthsOfAnHour() {
        assertEquals("−1.383 h", text(-1.3833, CoordinateStyle.DECIMAL))
        assertEquals("+0.417 h", text(0.4167, CoordinateStyle.DECIMAL))
        assertEquals("0.000 h", text(0.0004, CoordinateStyle.DECIMAL))
        assertEquals("−11.990 h", text(-11.99, CoordinateStyle.DECIMAL))
    }

    @Test
    fun theTimeToTransitReadsInMinutesAndHours() {
        assertEquals("transits in 25 min", ObjectDetailMeridian.transitIn(25 * 60.0))
        assertEquals("transits in 1 h 23 min", ObjectDetailMeridian.transitIn(83 * 60.0))
        assertEquals("transits in 2 h", ObjectDetailMeridian.transitIn(2 * 3_600.0))
        assertEquals("transits in 1 min", ObjectDetailMeridian.transitIn(31.0))
        assertEquals("transits now", ObjectDetailMeridian.transitIn(29.0))
        assertEquals("transited 25 min ago", ObjectDetailMeridian.transitedAgo(25 * 60.0))
        assertEquals("transited 3 h 5 min ago", ObjectDetailMeridian.transitedAgo(185 * 60.0))
        assertEquals("transited just now", ObjectDetailMeridian.transitedAgo(10.0))
        assertNull(ObjectDetailMeridian.transitIn(Double.NaN))
    }

    @Test
    fun theRowNamesTheTransitOnTheSideTheObjectIsOn() {
        val next = date.addingTimeInterval(83 * 60.0)
        val previous = date.addingTimeInterval(-25 * 60.0)
        val east = ObjectDetailMeridian(-(1 + 23.0 / 60), next, previous, date)
        assertEquals("−1h 23m · transits in 1 h 23 min", east.rowText(CoordinateStyle.SEXAGESIMAL))
        val west = ObjectDetailMeridian(25.0 / 60, next, previous, date)
        assertEquals("+0h 25m · transited 25 min ago", west.rowText(CoordinateStyle.SEXAGESIMAL))
        // A transit out of reach leaves the hour angle alone.
        assertEquals("+0.417 h", ObjectDetailMeridian(25.0 / 60, next, null, date).rowText(CoordinateStyle.DECIMAL))
    }

    @Test
    fun aStarsHourAngleAndTransitAgree() {
        val sirius = assertNotNull(catalog.objectWithID("star.hip32349"))
        val frame = SkyFrame(date, observer)
        val meridian = assertNotNull(ObjectDetailCalculator.current(sirius, observer, date).meridian)
        assertEquals(frame.hourAngle(frame.apparentEquatorial(sirius).rightAscension) / 15, meridian.hourAngle, 1e-9)
        val next = assertNotNull(meridian.next)
        val previous = assertNotNull(meridian.previous)
        assertTrue(previous <= date && date < next)
        // The star is on the meridian at sidereal speed: the hour angle of the side it is on counts down to the transit.
        val side = if (meridian.hourAngle < 0) next.timeIntervalSince(date) else date.timeIntervalSince(previous)
        assertEquals(abs(meridian.hourAngle) / 1.0027379093, side / 3_600, 0.001)
        assertEquals(next.timeIntervalSince(previous), 86_164.0905, 1.0)
    }

    @Test
    fun aPoleHasNoHourAngle() {
        val pole = assertNotNull(catalog.objectWithID("special.ncp"))
        assertNull(ObjectDetailCalculator.current(pole, observer, date).meridian)
    }

    @Test
    fun anObjectWhoseOrbitCannotBeEvaluatedHasNoHourAngle() {
        val sirius = assertNotNull(catalog.objectWithID("star.hip32349"))
        val place = EquatorialCoordinate(0.0, 0.0)
        assertNull(ObjectDetailMeridian.of(sirius, observer, date, place, isEvaluable = false))
        assertNotNull(ObjectDetailMeridian.of(sirius, observer, date, place))
    }
}
