package net.kuecker.lodestar.services

import net.kuecker.lodestar.kit.foundation.DateInterval
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The weather values of the forecast: reading them, the night's dew, wind, fog and temperature, and their words. The
 * answer in `met-locationforecast-oslo.json` is a real Locationforecast 2.0 `complete` answer for 59.9, 10.7 (issued
 * 2026-11-19T18:29:06Z), cut to 31 forecast times and to the details this app reads (data: MET Norway, CC BY 4.0). Its
 * hourly part runs from 2026-11-21T18:00Z to 2026-11-22T00:00Z, then it has one time every six hours up to
 * 2026-11-28T00:00Z; the six-hourly times have no gusts and no fog. The Swift tests of the iOS app mirror these.
 */
class ForecastWeatherTest {
    private val clock = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneOffset.UTC)
    private val timeText: (Instant) -> String = { clock.format(it) }
    private val minus = 0x2212.toChar()
    private val nbsp = 0xA0.toChar()

    private fun fixture(): CloudForecast {
        val stream = assertNotNull(javaClass.getResourceAsStream("met-locationforecast-oslo.json"))
        return ForecastParser.parseForecast(stream.use { it.readBytes() })
    }

    private fun at(text: String): Instant = Instant.parse(text)

    private fun slotsOf(forecast: CloudForecast, from: String, to: String): List<CloudSlot> =
        ForecastNight.slots(forecast, DateInterval(at(from), at(to)))

    /** A forecast of hourly times from 2026-11-20T18:00Z, one [CloudHour] per entry of [hours]. */
    private fun hourly(vararg hours: (Instant) -> CloudHour): CloudForecast {
        val start = at("2026-11-20T18:00:00Z")
        return CloudForecast(start, 59.9, 10.7, hours.mapIndexed { index, make -> make(start.plusSeconds(index * 3600L)) })
    }

    private fun hour(
        temperature: Double? = null,
        dewPoint: Double? = null,
        wind: Double? = null,
        gust: Double? = null,
        fog: Double? = null,
        direction: Double? = null,
    ): (Instant) -> CloudHour = { time ->
        CloudHour(
            time, 20.0, 5.0, 10.0, 15.0, temperature = temperature, dewPoint = dewPoint, humidity = 70.0,
            windSpeed = wind, windGust = gust, windFromDirection = direction, fog = fog,
        )
    }

    private fun nightOf(forecast: CloudForecast, from: String = "2026-11-20T18:00:00Z", to: String = "2026-11-21T06:00:00Z") =
        ForecastNight.slots(forecast, DateInterval(at(from), at(to)))

    /** A forecast answer whose only forecast times all have the weather [details]. */
    private fun answer(details: String, count: Int = 6): ByteArray {
        val entries = (0 until count).map { index ->
            """{"time":"${at("2026-11-20T18:00:00Z").plusSeconds(index * 3600L)}","data":{"instant":{"details":{$details}}}}"""
        }
        return ("""{"geometry":{"coordinates":[10.7,59.9,0]},"properties":{"meta":{"updated_at":"2026-11-20T17:30:00Z"},""" +
            """"timeseries":[${entries.joinToString(",")}]}}""").toByteArray()
    }

    // Reading

    @Test
    fun theWeatherOfARealAnswerIsRead() {
        val forecast = fixture()
        assertEquals(31, forecast.hours.size)
        assertEquals("59.9,10.7", forecast.key)
        val first = forecast.hours.first()
        assertEquals(at("2026-11-21T18:00:00Z"), first.time)
        assertEquals(37.2, first.total)
        assertEquals(1.9, assertNotNull(first.temperature))
        assertEquals(-5.0, assertNotNull(first.dewPoint))
        assertEquals(60.4, assertNotNull(first.humidity))
        assertEquals(5.3, assertNotNull(first.windSpeed))
        assertEquals(8.1, assertNotNull(first.windGust))
        assertEquals(349.0, assertNotNull(first.windFromDirection))
        assertEquals(0.0, assertNotNull(first.fog))
        assertEquals(6.9, assertNotNull(first.dewPointSpread), 1e-9)
        assertTrue(forecast.hours.all { it.temperature != null && it.dewPoint != null && it.windSpeed != null })
    }

    @Test
    fun theSixHourlyTimesHaveNoGustsAndNoFog() {
        val sixHourly = fixture().hours[7]
        assertEquals(at("2026-11-22T06:00:00Z"), sixHourly.time)
        assertEquals(1.7, assertNotNull(sixHourly.temperature))
        assertEquals(6.5, assertNotNull(sixHourly.windSpeed))
        assertEquals(350.7, assertNotNull(sixHourly.windFromDirection))
        assertNull(sixHourly.windGust)
        assertNull(sixHourly.fog)
    }

    @Test
    fun anAnswerWithoutWeatherValuesReadsWithNulls() {
        val forecast = ForecastParser.parseForecast(
            answer(""""cloud_area_fraction":40.0,"cloud_area_fraction_high":10.0,"cloud_area_fraction_low":20.0,"cloud_area_fraction_medium":30.0"""),
        )
        val hour = forecast.hours.first()
        assertEquals(40.0, hour.total)
        assertNull(hour.temperature)
        assertNull(hour.dewPoint)
        assertNull(hour.humidity)
        assertNull(hour.windSpeed)
        assertNull(hour.windGust)
        assertNull(hour.windFromDirection)
        assertNull(hour.fog)
        assertNull(hour.dewPointSpread)
    }

    @Test
    fun valuesOutsideTheirRangeAreDropped() {
        val bad = ForecastParser.parseForecast(
            answer(
                """"cloud_area_fraction":10,"air_temperature":60.5,"dew_point_temperature":-90.5,"wind_speed":100.5,""" +
                    """"wind_speed_of_gust":-0.5,"wind_from_direction":360.5,"relative_humidity":-3,"fog_area_fraction":140""",
            ),
        ).hours.first()
        assertNull(bad.temperature)
        assertNull(bad.dewPoint)
        assertNull(bad.windSpeed)
        assertNull(bad.windGust)
        assertNull(bad.windFromDirection)
        // The percentages are clamped as the cloud values are.
        assertEquals(0.0, bad.humidity)
        assertEquals(100.0, bad.fog)
        val edges = ForecastParser.parseForecast(
            answer(""""cloud_area_fraction":10,"air_temperature":-90,"dew_point_temperature":60,"wind_speed":100,"wind_speed_of_gust":0,"wind_from_direction":360"""),
        ).hours.first()
        assertEquals(-90.0, edges.temperature)
        assertEquals(60.0, edges.dewPoint)
        assertEquals(100.0, edges.windSpeed)
        assertEquals(0.0, edges.windGust)
        assertEquals(0.0, edges.windFromDirection)
    }

    @Test
    fun valuesThatAreNotNumbersAreDropped() {
        val hour = ForecastParser.parseForecast(
            answer(""""cloud_area_fraction":10,"air_temperature":"warm","dew_point_temperature":null,"wind_speed":"fast","fog_area_fraction":"none""""),
        ).hours.first()
        assertNull(hour.temperature)
        assertNull(hour.dewPoint)
        assertNull(hour.windSpeed)
        assertNull(hour.fog)
    }

    @Test
    fun aDewPointAboveTheAirIsASpreadOfZero() {
        val hour = CloudHour(at("2026-11-20T18:00:00Z"), 0.0, 0.0, 0.0, 0.0, temperature = 1.0, dewPoint = 1.2)
        assertEquals(0.0, assertNotNull(hour.dewPointSpread))
    }

    // Dew

    @Test
    fun dewStartsAtTheFirstSlotWithASpreadUnderTwoAndKeepsTheSmallestSpread() {
        val slots = slotsOf(fixture(), "2026-11-26T18:00:00Z", "2026-11-27T12:00:00Z")
        val dew = assertNotNull(ForecastNight.dew(slots))
        assertEquals(at("2026-11-27T00:00:00Z"), dew.from)
        assertEquals(1.8, dew.minimumSpread, 1e-9)
        assertFalse(dew.isVeryLikely)
        assertEquals("Lens fogging likely from 00:00", ForecastWords.dewHint(dew, timeText))
    }

    @Test
    fun aSpreadOfTwoOrMoreAllNightGivesNoDew() {
        val forecast = hourly(hour(0.0, -2.0), hour(0.0, -2.0), hour(1.0, -3.0))
        assertNull(ForecastNight.dew(nightOf(forecast)))
        val real = slotsOf(fixture(), "2026-11-21T18:40:00Z", "2026-11-22T00:30:00Z")
        assertNull(ForecastNight.dew(real))
    }

    @Test
    fun dewIsJustUnderTwoAndVeryLikelyUnderOne() {
        val under = assertNotNull(ForecastNight.dew(nightOf(hourly(hour(0.0, -2.1), hour(0.0, -1.99)))))
        assertEquals(at("2026-11-20T19:00:00Z"), under.from)
        assertFalse(under.isVeryLikely)
        val one = assertNotNull(ForecastNight.dew(nightOf(hourly(hour(0.0, -3.0), hour(0.0, -1.0)))))
        assertFalse(one.isVeryLikely)
        val veryLikely = assertNotNull(ForecastNight.dew(nightOf(hourly(hour(0.0, -3.0), hour(0.0, -1.5), hour(0.0, -0.9)))))
        assertTrue(veryLikely.isVeryLikely)
        assertEquals(0.9, veryLikely.minimumSpread, 1e-9)
        assertEquals(at("2026-11-20T19:00:00Z"), veryLikely.from)
        assertEquals("Lens fogging very likely from 19:00", ForecastWords.dewHint(veryLikely, timeText))
    }

    @Test
    fun dewFromTheStartOfANightThatBeginsMidHourNamesThatTime() {
        val forecast = hourly(hour(0.0, -1.5), hour(0.0, -1.5), hour(0.0, -1.5))
        val slots = nightOf(forecast, from = "2026-11-20T18:40:00Z", to = "2026-11-20T21:00:00Z")
        val dew = assertNotNull(ForecastNight.dew(slots))
        assertEquals("Lens fogging likely from 18:40", ForecastWords.dewHint(dew, timeText))
        assertEquals("Dew from 18:40", ForecastWords.chips(ForecastNight.conditions(slots), ForecastUnitSystem.METRIC, timeText).first().text)
    }

    @Test
    fun slotsWithoutADewPointAreIgnoredForTheDew() {
        val forecast = hourly(hour(0.0, null), hour(null, -1.0), hour(0.0, -0.5))
        val dew = assertNotNull(ForecastNight.dew(nightOf(forecast)))
        assertEquals(at("2026-11-20T20:00:00Z"), dew.from)
        assertNull(ForecastNight.dew(nightOf(hourly(hour(0.0, null), hour(null, -1.0)))))
    }

    // Wind

    @Test
    fun theWindOfTheNightIsItsStrongestSlot() {
        val wind = assertNotNull(ForecastNight.wind(slotsOf(fixture(), "2026-11-21T18:40:00Z", "2026-11-22T00:30:00Z")))
        assertEquals(6.6, wind.maximum)
        assertEquals(10.2, assertNotNull(wind.gust))
        assertEquals(354.0, assertNotNull(wind.fromDirection))
        assertEquals(WindLevel.BREEZY, wind.level)
    }

    @Test
    fun theWindLevelsStartAtFiveEightAndTwelve() {
        fun level(speed: Double) = assertNotNull(ForecastNight.wind(nightOf(hourly(hour(wind = speed))))).level
        assertEquals(WindLevel.CALM, level(0.0))
        assertEquals(WindLevel.CALM, level(4.9))
        assertEquals(WindLevel.BREEZY, level(5.0))
        assertEquals(WindLevel.BREEZY, level(7.9))
        assertEquals(WindLevel.WINDY, level(8.0))
        assertEquals(WindLevel.WINDY, level(11.9))
        assertEquals(WindLevel.STRONG, level(12.0))
        assertEquals(WindLevel.STRONG, level(30.0))
    }

    @Test
    fun theWindLevelFollowsTheSustainedWindNotTheGust() {
        val wind = assertNotNull(ForecastNight.wind(nightOf(hourly(hour(wind = 3.0, gust = 15.0), hour(wind = 4.0, gust = 6.0)))))
        assertEquals(4.0, wind.maximum)
        assertEquals(15.0, assertNotNull(wind.gust))
        assertEquals(WindLevel.CALM, wind.level)
    }

    @Test
    fun noWindSpeedGivesNoWindSummary() {
        assertNull(ForecastNight.wind(nightOf(hourly(hour(gust = 9.0), hour()))))
        val calm = assertNotNull(ForecastNight.wind(slotsOf(fixture(), "2026-11-26T18:00:00Z", "2026-11-27T12:00:00Z")))
        assertEquals(3.7, calm.maximum)
        assertNull(calm.gust)
        assertEquals(WindLevel.CALM, calm.level)
    }

    // Fog and temperature

    @Test
    fun fogCountsTheSlotsWithAtLeastFiftyPercent() {
        val forecast = hourly(hour(fog = 10.0), hour(fog = 49.9), hour(fog = 50.0), hour(fog = 80.0), hour(fog = 20.0), hour(fog = 60.0), hour())
        val fog = assertNotNull(ForecastNight.fog(nightOf(forecast)))
        assertEquals(at("2026-11-20T20:00:00Z"), fog.from)
        assertEquals(3 * 3600.0, fog.seconds)
        assertNull(ForecastNight.fog(nightOf(hourly(hour(fog = 49.9), hour(), hour(fog = 0.0)))))
        assertNull(ForecastNight.fog(slotsOf(fixture(), "2026-11-21T18:00:00Z", "2026-11-22T06:00:00Z")))
    }

    @Test
    fun theTemperatureRangeIsTheLowestAndHighestOfTheNight() {
        val range = assertNotNull(ForecastNight.temperature(nightOf(hourly(hour(2.0), hour(-3.5), hour(), hour(4.2), hour(1.0)))))
        assertEquals(-3.5, range.minimum)
        assertEquals(4.2, range.maximum)
        assertNull(ForecastNight.temperature(nightOf(hourly(hour(), hour()))))
        val real = assertNotNull(ForecastNight.temperature(slotsOf(fixture(), "2026-11-21T18:40:00Z", "2026-11-22T00:30:00Z")))
        assertEquals(1.9, real.minimum)
        assertEquals(3.4, real.maximum)
    }

    // The hourly table

    @Test
    fun theHourlyTableHasOneRowPerSlotOfTheNight() {
        val rows = ForecastNight.conditions(slotsOf(fixture(), "2026-11-21T18:40:00Z", "2026-11-22T00:30:00Z")).hourly
        assertEquals(7, rows.size)
        assertEquals(at("2026-11-21T18:40:00Z"), rows.first().time)
        assertEquals(at("2026-11-21T19:00:00Z"), rows[1].time)
        assertEquals(at("2026-11-22T00:00:00Z"), rows.last().time)
        assertEquals(37.2, rows.first().cloud)
        assertEquals(1.9, assertNotNull(rows.first().temperature))
        assertEquals(6.9, assertNotNull(rows.first().dewPointSpread), 1e-9)
        assertEquals(5.3, assertNotNull(rows.first().windSpeed))
        assertEquals(60.4, assertNotNull(rows.first().humidity))
    }

    @Test
    fun theSixHourlyPartGivesSixHourRows() {
        val rows = ForecastNight.conditions(slotsOf(fixture(), "2026-11-26T18:00:00Z", "2026-11-27T12:00:00Z")).hourly
        assertEquals(listOf("18:00", "00:00", "06:00"), rows.map { timeText(it.time) })
    }

    // Words and units

    @Test
    fun theChipsOfARealNightInMetricAndUsUnits() {
        val conditions = ForecastNight.conditions(slotsOf(fixture(), "2026-11-21T18:40:00Z", "2026-11-22T00:30:00Z"))
        val metric = ForecastWords.chips(conditions, ForecastUnitSystem.METRIC, timeText)
        assertEquals(listOf(ForecastChipKind.WIND, ForecastChipKind.TEMPERATURE), metric.map { it.kind })
        assertEquals("Wind 7${nbsp}m/s, gusts 10", metric[0].text)
        assertTrue(metric[0].isWarning)
        assertEquals("2…3${nbsp}°C", metric[1].text)
        assertFalse(metric[1].isWarning)
        assertEquals("Wind up to 7 metres per second from N, gusts up to 10. Breezy: long exposures may shake.", metric[0].spoken)
        assertEquals("Air temperature from 2 to 3 degrees Celsius.", metric[1].spoken)
        val us = ForecastWords.chips(conditions, ForecastUnitSystem.US, timeText)
        assertEquals("Wind 15${nbsp}mph, gusts 23", us[0].text)
        assertEquals("35…38${nbsp}°F", us[1].text)
        assertEquals("Wind up to 15 miles per hour from N, gusts up to 23. Breezy: long exposures may shake.", us[0].spoken)
    }

    @Test
    fun theChipsAreDewWindFogTemperatureAndOnlyThoseThereIsDataFor() {
        val forecast = hourly(
            hour(-1.0, -2.0, wind = 9.0, fog = 70.0, direction = 90.0), hour(-2.0, -2.5, wind = 8.5, fog = 60.0, direction = 100.0),
            hour(-3.0, -3.2, wind = 3.0, fog = 10.0, direction = 100.0),
        )
        val conditions = ForecastNight.conditions(nightOf(forecast))
        val chips = ForecastWords.chips(conditions, ForecastUnitSystem.METRIC, timeText)
        assertEquals(
            listOf(ForecastChipKind.DEW, ForecastChipKind.WIND, ForecastChipKind.FOG, ForecastChipKind.TEMPERATURE),
            chips.map { it.kind },
        )
        assertEquals("Dew from 18:00", chips[0].text)
        assertTrue(chips[0].isWarning)
        assertEquals("Lens fogging very likely from 18:00. The air gets within 0.2 degrees Celsius of the dew point.", chips[0].spoken)
        assertEquals("Wind 9${nbsp}m/s", chips[1].text)
        assertEquals("Wind up to 9 metres per second from E. Windy: weigh the tripod down.", chips[1].spoken)
        assertEquals("Fog from 18:00", chips[2].text)
        assertEquals("Fog expected for about 2 hours from 18:00.", chips[2].spoken)
        assertTrue(chips[2].isWarning)
        assertEquals("${minus}3…${minus}1${nbsp}°C", chips[3].text)
        assertEquals("Air temperature from minus 3 to minus 1 degrees Celsius.", chips[3].spoken)
        assertTrue(ForecastWords.chips(ForecastNight.conditions(nightOf(hourly(hour(), hour()))), ForecastUnitSystem.METRIC, timeText).isEmpty())
    }

    @Test
    fun theHintNamesTheDewFirstThenTheWind() {
        val both = ForecastNight.conditions(nightOf(hourly(hour(0.0, -0.5, wind = 13.0))))
        assertEquals("Lens fogging very likely from 18:00", ForecastWords.hint(both, timeText))
        val windy = ForecastNight.conditions(nightOf(hourly(hour(0.0, -5.0, wind = 8.0))))
        assertEquals("Windy: weigh the tripod down", ForecastWords.hint(windy, timeText))
        val breezy = ForecastNight.conditions(nightOf(hourly(hour(0.0, -5.0, wind = 5.0))))
        assertEquals("Breezy: long exposures may shake", ForecastWords.hint(breezy, timeText))
        val strong = ForecastNight.conditions(nightOf(hourly(hour(0.0, -5.0, wind = 12.0))))
        assertEquals("Strong wind: expect shaky frames", ForecastWords.hint(strong, timeText))
        val calm = ForecastNight.conditions(nightOf(hourly(hour(0.0, -5.0, wind = 4.0, fog = 90.0))))
        assertNull(ForecastWords.hint(calm, timeText))
        assertNull(ForecastWords.hint(ForecastNight.conditions(emptyList()), timeText))
    }

    @Test
    fun aGustThatRoundsToTheWindIsNotNamed() {
        val wind = ForecastNight.conditions(nightOf(hourly(hour(wind = 6.0, gust = 6.4))))
        assertEquals("Wind 6${nbsp}m/s", ForecastWords.chips(wind, ForecastUnitSystem.METRIC, timeText).first().text)
    }

    @Test
    fun theHourlyCellsAndHeaders() {
        val rows = ForecastNight.conditions(slotsOf(fixture(), "2026-11-21T18:40:00Z", "2026-11-22T00:30:00Z")).hourly
        assertEquals(listOf("Time", "Cloud", "Temp (°C)", "Dew gap (°C)", "Wind (m/s)"), ForecastWords.hourlyHeaders(ForecastUnitSystem.METRIC))
        assertEquals(listOf("Time", "Cloud", "Temp (°F)", "Dew gap (°F)", "Wind (mph)"), ForecastWords.hourlyHeaders(ForecastUnitSystem.US))
        assertEquals(listOf("18:40", "37%", "2", "6.9", "5"), ForecastWords.hourlyCells(rows.first(), ForecastUnitSystem.METRIC, timeText))
        assertEquals(listOf("18:40", "37%", "35", "12.4", "12"), ForecastWords.hourlyCells(rows.first(), ForecastUnitSystem.US, timeText))
        val missing = NightHour(at("2026-11-20T18:00:00Z"), 99.5, null, null, null, null, null)
        assertEquals(listOf("18:00", "100%", "—", "—", "—"), ForecastWords.hourlyCells(missing, ForecastUnitSystem.METRIC, timeText))
        assertEquals("18:00. Cloud 100 percent.", ForecastWords.hourlySpoken(missing, ForecastUnitSystem.METRIC, timeText))
        assertEquals(
            "18:40. Cloud 37 percent. Temperature 2 degrees Celsius. Dew gap 6.9 degrees Celsius. Wind 5 metres per second. " +
                "Humidity 60 percent.",
            ForecastWords.hourlySpoken(rows.first(), ForecastUnitSystem.METRIC, timeText),
        )
    }

    @Test
    fun temperaturesAreRoundedHalfAwayFromZeroWithARealMinusSign() {
        val metric = ForecastUnitSystem.METRIC
        assertEquals("3${nbsp}°C", ForecastUnits.temperature(2.5, metric))
        assertEquals("${minus}3${nbsp}°C", ForecastUnits.temperature(-2.5, metric))
        assertEquals("0${nbsp}°C", ForecastUnits.temperature(-0.4, metric))
        assertEquals("${minus}3…4${nbsp}°C", ForecastUnits.temperatureRange(-3.2, 3.6, metric))
        assertEquals("3${nbsp}°C", ForecastUnits.temperatureRange(2.6, 3.4, metric))
        assertEquals("32${nbsp}°F", ForecastUnits.temperature(0.0, ForecastUnitSystem.US))
        assertEquals("27…39${nbsp}°F", ForecastUnits.temperatureRange(-3.0, 4.0, ForecastUnitSystem.US))
        assertEquals("${minus}4${nbsp}°F", ForecastUnits.temperature(-20.0, ForecastUnitSystem.US))
    }

    @Test
    fun speedsAndDifferencesInBothSystems() {
        assertEquals("6${nbsp}m/s", ForecastUnits.speed(5.5, ForecastUnitSystem.METRIC))
        assertEquals("13${nbsp}mph", ForecastUnits.speed(6.0, ForecastUnitSystem.US))
        assertEquals(11, ForecastUnits.speedValue(5.0, ForecastUnitSystem.US))
        assertEquals("1 metre per second", ForecastUnits.spokenSpeed(1.2, ForecastUnitSystem.METRIC))
        assertEquals("2 miles per hour", ForecastUnits.spokenSpeed(1.0, ForecastUnitSystem.US))
        assertEquals("1 mile per hour", ForecastUnits.spokenSpeed(0.5, ForecastUnitSystem.US))
        assertEquals("1.4", ForecastUnits.differenceValue(1.4, ForecastUnitSystem.METRIC))
        assertEquals("2.5${nbsp}°F", ForecastUnits.difference(1.4, ForecastUnitSystem.US))
        assertEquals("0.0${nbsp}°C", ForecastUnits.difference(0.0, ForecastUnitSystem.METRIC))
        assertEquals("minus 2 degrees Fahrenheit", ForecastUnits.spokenTemperature(-19.0, ForecastUnitSystem.US))
        assertEquals("1 degree Celsius", ForecastUnits.spokenTemperature(0.8, ForecastUnitSystem.METRIC))
        assertEquals("minus 1 degree Celsius", ForecastUnits.spokenTemperature(-1.2, ForecastUnitSystem.METRIC))
    }

    @Test
    fun theUnitSystemFollowsTheRegionOrTheLocalesOwnChoice() {
        assertEquals(ForecastUnitSystem.US, ForecastUnits.systemOf(Locale.forLanguageTag("en-US")))
        assertEquals(ForecastUnitSystem.METRIC, ForecastUnits.systemOf(Locale.forLanguageTag("de-DE")))
        assertEquals(ForecastUnitSystem.METRIC, ForecastUnits.systemOf(Locale.forLanguageTag("nb-NO")))
        assertEquals(ForecastUnitSystem.METRIC, ForecastUnits.systemOf(Locale.forLanguageTag("en-GB")))
        assertEquals(ForecastUnitSystem.METRIC, ForecastUnits.systemOf(Locale.forLanguageTag("en-US-u-ms-metric")))
        assertEquals(ForecastUnitSystem.US, ForecastUnits.systemOf(Locale.forLanguageTag("de-DE-u-ms-ussystem")))
    }
}
