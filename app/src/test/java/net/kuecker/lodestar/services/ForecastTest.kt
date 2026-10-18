package net.kuecker.lodestar.services

import net.kuecker.lodestar.kit.foundation.DateInterval
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ForecastTest {
    private val start = Instant.parse("2026-10-18T19:00:00Z")
    private val zone: ZoneId = ZoneOffset.UTC
    private val clock = DateTimeFormatter.ofPattern("HH:mm").withZone(zone)
    private val timeText: (Instant) -> String = { clock.format(it) }

    /** A MET Norway answer (the fields of the real Locationforecast 2.0 `complete` answer that matter here). */
    private fun metJson(
        totals: List<Double>,
        step: Long = 3600,
        updatedAt: String = "2026-10-18T19:17:31Z",
        extraEntry: String = "",
    ): ByteArray {
        val entries = totals.mapIndexed { index, total ->
            val time = start.plusSeconds(index * step)
            """{"time":"$time","data":{"instant":{"details":{"air_pressure_at_sea_level":1026.4,"air_temperature":20.1,""" +
                """"cloud_area_fraction":$total,"cloud_area_fraction_high":${total / 2},"cloud_area_fraction_low":0.0,""" +
                """"cloud_area_fraction_medium":${total / 4},"wind_speed":1.4}},"next_1_hours":{"summary":{"symbol_code":"fair_day"}}}}"""
        }
        return ("""{"type":"Feature","geometry":{"type":"Point","coordinates":[-96.8,32.8,153]},"properties":{"meta":""" +
            """{"updated_at":"$updatedAt","units":{"cloud_area_fraction":"%"}},"timeseries":[""" +
            (entries + listOfNotNull(extraEntry.ifEmpty { null })).joinToString(",") + "]}}").toByteArray()
    }

    private fun night(fromHour: Int, toHour: Int) =
        DateInterval(Instant.parse("2026-10-18T00:00:00Z").plusSeconds(fromHour * 3600L), Instant.parse("2026-10-18T00:00:00Z").plusSeconds(toHour * 3600L))

    /** Hourly forecast from 19:00 whose total cloud is [percent] for the clock hour (0 = midnight). */
    private fun forecastOf(vararg percentByHour: Pair<Int, Double>): CloudForecast {
        val byHour = percentByHour.toMap()
        val totals = (0 until 12).map { byHour[(19 + it) % 24] ?: 100.0 }
        return ForecastParser.parseForecast(metJson(totals))
    }

    @Test
    fun theAnswerIsReadWithItsLayersIssueTimeAndPlace() {
        val forecast = ForecastParser.parseForecast(metJson(listOf(10.0, 20.0, 30.0, 40.0, 50.0, 60.0, 70.0)))
        assertEquals(7, forecast.hours.size)
        assertEquals(Instant.parse("2026-10-18T19:17:31Z"), forecast.issuedAt)
        assertEquals(start, forecast.hours.first().time)
        val second = forecast.hours[1]
        assertEquals(20.0, second.total)
        assertEquals(10.0, second.high)
        assertEquals(5.0, second.medium)
        assertEquals(0.0, second.low)
        assertEquals("32.8,-96.8", forecast.key)
        assertEquals(start.plusSeconds(6 * 3600), forecast.end)
    }

    @Test
    fun entriesWithoutCloudValuesAreSkippedAndValuesAreClamped() {
        val noClouds = """{"time":"2026-10-19T09:00:00Z","data":{"instant":{"details":{"air_temperature":3}}}}"""
        val forecast = ForecastParser.parseForecast(metJson(listOf(10.0, 20.0, 30.0, 40.0, 50.0, 140.0), extraEntry = noClouds))
        assertEquals(6, forecast.hours.size)
        assertEquals(100.0, forecast.hours.last().total)
    }

    @Test
    fun answersThatAreNotForecastsAreRejected() {
        assertFailsWith<OnlineDataException> { ForecastParser.parseForecast("not json".toByteArray()) }
        assertFailsWith<OnlineDataException> { ForecastParser.parseForecast("[]".toByteArray()) }
        assertFailsWith<OnlineDataException> { ForecastParser.parseForecast("""{"properties":{}}""".toByteArray()) }
        assertFailsWith<OnlineDataException> { ForecastParser.parseForecast(metJson(listOf(10.0, 20.0))) }
        assertFailsWith<OnlineDataException> { ForecastParser.parseForecast(metJson(List(8) { 10.0 }, updatedAt = "yesterday")) }
        // An HTML error page.
        assertFailsWith<OnlineDataException> { ForecastParser.parse("<html><body>403 Forbidden</body></html>".toByteArray()) }
    }

    @Test
    fun timesOutOfOrderAreRejected() {
        val bytes = String(metJson(List(8) { 10.0 })).replace("2026-10-18T21:00:00Z", "2026-10-18T19:00:00Z").toByteArray()
        assertFailsWith<OnlineDataException> { ForecastParser.parseForecast(bytes) }
    }

    @Test
    fun hourlyEntriesBecomeOneHourSlotsCutToTheNight() {
        val forecast = forecastOf()
        val slots = ForecastNight.slots(forecast, DateInterval(Instant.parse("2026-10-18T19:40:00Z"), Instant.parse("2026-10-19T01:20:00Z")))
        assertEquals(Instant.parse("2026-10-18T19:40:00Z"), slots.first().start)
        assertEquals(Instant.parse("2026-10-19T01:20:00Z"), slots.last().end)
        assertEquals(7, slots.size)
        assertEquals(1200.0, slots.first().seconds)
        assertEquals(3600.0, slots[1].seconds)
    }

    @Test
    fun theSixHourlyPartOfAForecastGivesLongSlots() {
        val forecast = ForecastParser.parseForecast(metJson(List(8) { 10.0 * it }, step = 6 * 3600))
        val slots = ForecastNight.slots(forecast, DateInterval(start.plusSeconds(7 * 3600), start.plusSeconds(21 * 3600)))
        assertEquals(3, slots.size)
        assertEquals(5 * 3600.0, slots[0].seconds)
        assertEquals(6 * 3600.0, slots[1].seconds)
        assertEquals(3 * 3600.0, slots[2].seconds)
        assertEquals(10.0, slots[0].total)
    }

    @Test
    fun aNightOutsideTheForecastHasNoSlots() {
        val forecast = forecastOf()
        assertTrue(ForecastNight.slots(forecast, DateInterval(start.minusSeconds(20 * 3600), start.minusSeconds(10 * 3600))).isEmpty())
        assertTrue(ForecastNight.slots(forecast, DateInterval(start.plusSeconds(40 * 3600), start.plusSeconds(50 * 3600))).isEmpty())
        assertNull(ForecastNight.summary(emptyList(), timeText))
        assertNull(ForecastNight.spokenSummary(emptyList(), timeText))
    }

    private fun summary(forecast: CloudForecast, from: Int = 19, to: Int = 30): String? {
        val slots = ForecastNight.slots(forecast, DateInterval(Instant.parse("2026-10-18T00:00:00Z").plusSeconds(from * 3600L), Instant.parse("2026-10-18T00:00:00Z").plusSeconds(to * 3600L)))
        return ForecastNight.summary(slots, timeText)
    }

    @Test
    fun aNightOfClearSkyIsClearAllNight() {
        assertEquals("Clear all night", summary(forecastOf(*(0..23).map { it to 5.0 }.toTypedArray())))
    }

    @Test
    fun cloudsThatClearAtMidnightGiveMostlyClearAfter() {
        val forecast = forecastOf(*((19..23).map { it to 90.0 } + (0..6).map { it to 10.0 }).toTypedArray())
        assertEquals("Mostly clear after 00:00", summary(forecast))
        val later = forecastOf(*((19..22).map { it to 90.0 } + (23..23).map { it to 10.0 } + (0..6).map { it to 10.0 }).toTypedArray())
        assertEquals("Mostly clear after 23:00", summary(later))
    }

    @Test
    fun skyThatClearsUpEarlyThenClouds_overGivesMostlyClearUntil() {
        val forecast = forecastOf(*((19..23).map { it to 10.0 } + (0..0).map { it to 10.0 } + (1..6).map { it to 90.0 }).toTypedArray())
        assertEquals("Mostly clear until 01:00", summary(forecast))
    }

    @Test
    fun aClearSpellInTheMiddleIsNamedWithItsTimes() {
        val forecast = forecastOf(*((19..21).map { it to 90.0 } + (22..23).map { it to 5.0 } + (0..1).map { it to 5.0 } + (2..6).map { it to 90.0 }).toTypedArray())
        assertEquals("Clear from 22:00 to 02:00", summary(forecast))
    }

    @Test
    fun aShortClearGapIsNotNamed() {
        val forecast = forecastOf(*((19..23).map { it to 95.0 } + listOf(0 to 10.0) + (1..6).map { it to 95.0 }).toTypedArray())
        assertEquals("Cloudy all night", summary(forecast))
        val partly = forecastOf(*(0..23).map { it to if (it % 2 == 0) 60.0 else 40.0 }.toTypedArray())
        assertEquals("No long clear spell", summary(partly))
    }

    @Test
    fun theSpokenSummaryNamesTheSpanTheExtremesAndTheSpells() {
        val forecast = forecastOf(*((19..21).map { it to 80.0 } + (22..23).map { it to 5.0 } + (0..6).map { it to 5.0 }).toTypedArray())
        val slots = ForecastNight.slots(forecast, night(19, 30))
        val spoken = assertNotNull(ForecastNight.spokenSummary(slots, timeText))
        assertTrue(spoken.startsWith("Cloud cover from 19:00 to 06:00."), spoken)
        assertTrue(spoken.contains("Total cloud runs from 5 percent at 22:00 to 80 percent at 19:00."), spoken)
        assertTrue(spoken.endsWith("Mostly clear after 22:00."), spoken)
    }

    @Test
    fun theWeightedMeanCountsLongSlotsMore() {
        val slots = listOf(
            CloudSlot(start, start.plusSeconds(3600), 100.0, 0.0, 0.0, 0.0),
            CloudSlot(start.plusSeconds(3600), start.plusSeconds(3 * 3600), 0.0, 0.0, 0.0, 0.0),
        )
        assertEquals(100.0 / 3, ForecastNight.meanTotal(slots), 1e-9)
    }
}
