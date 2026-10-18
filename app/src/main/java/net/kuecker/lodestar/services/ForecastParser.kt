package net.kuecker.lodestar.services

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Cloud cover at one forecast time, in percent of the sky (0…100): the whole sky and the low, medium and high layers.
 * MET Norway gives hourly values for about the first two and a half days, then every six hours.
 */
class CloudHour(val time: Instant, val total: Double, val low: Double, val medium: Double, val high: Double)

/**
 * MET Norway's cloud forecast for one grid point.
 *
 * @property issuedAt When the forecast was produced (`updated_at`).
 * @property latitude The grid point's latitude, as MET Norway reports it (the request's rounded value).
 * @property longitude The grid point's longitude.
 * @property hours The forecast times in ascending order.
 */
class CloudForecast(
    val issuedAt: Instant,
    val latitude: Double?,
    val longitude: Double?,
    val hours: List<CloudHour>,
) {
    /** The last time the forecast covers. */
    val end: Instant? get() = hours.lastOrNull()?.time

    /** Tells which request this forecast answers, as [OnlineCoordinates.key] does; `null` when the answer names no place. */
    val key: String? get() = if (latitude != null && longitude != null) OnlineCoordinates.key(latitude, longitude) else null
}

/** Reads the answer of MET Norway's Locationforecast 2.0 (`complete` or `compact`). */
object ForecastParser {
    /** The fewest forecast times a usable answer has. */
    const val minimumHours = 6

    private val json = Json

    /** Throws [OnlineDataException] when [bytes] are not a forecast with cloud values. */
    fun parse(bytes: ByteArray): OnlineParsed<CloudForecast> = OnlineParsed(parseForecast(bytes))

    /** The forecast in [bytes]. Throws [OnlineDataException] when there is none. */
    fun parseForecast(bytes: ByteArray): CloudForecast {
        val element = try {
            json.parseToJsonElement(String(bytes, Charsets.UTF_8))
        } catch (e: IllegalArgumentException) {
            throw OnlineDataException("Not JSON: ${e.message}")
        }
        val root = element as? JsonObject ?: throw OnlineDataException("Not a forecast.")
        val properties = root["properties"] as? JsonObject ?: throw OnlineDataException("No properties.")
        val series = properties["timeseries"] as? JsonArray ?: throw OnlineDataException("No time series.")
        val issued = ((properties["meta"] as? JsonObject)?.get("updated_at") as? JsonPrimitive)?.contentOrNull
            ?.let(::instantOrNull)
            ?: throw OnlineDataException("No issue time.")
        val hours = ArrayList<CloudHour>(series.size)
        for (entry in series) {
            val item = entry as? JsonObject ?: continue
            val time = (item["time"] as? JsonPrimitive)?.contentOrNull?.let(::instantOrNull) ?: continue
            val details = ((item["data"] as? JsonObject)?.get("instant") as? JsonObject)?.get("details") as? JsonObject
                ?: continue
            val total = percent(details, "cloud_area_fraction") ?: continue
            hours.add(
                CloudHour(
                    time, total,
                    percent(details, "cloud_area_fraction_low") ?: 0.0,
                    percent(details, "cloud_area_fraction_medium") ?: 0.0,
                    percent(details, "cloud_area_fraction_high") ?: 0.0,
                ),
            )
        }
        if (hours.size < minimumHours) throw OnlineDataException("Too few forecast times.")
        for (index in 1 until hours.size) {
            if (hours[index].time <= hours[index - 1].time) throw OnlineDataException("Forecast times out of order.")
        }
        val coordinates = ((root["geometry"] as? JsonObject)?.get("coordinates") as? JsonArray)?.takeIf { it.size >= 2 }
        val longitude = coordinates?.let { (it[0] as? JsonPrimitive)?.doubleOrNull }
        val latitude = coordinates?.let { (it[1] as? JsonPrimitive)?.doubleOrNull }
        return CloudForecast(issued, latitude, longitude, hours)
    }

    private fun percent(details: JsonObject, name: String): Double? {
        val value = (details[name] as? JsonPrimitive)?.doubleOrNull ?: return null
        return if (value.isFinite()) value.coerceIn(0.0, 100.0) else null
    }

    private fun instantOrNull(text: String): Instant? = try {
        Instant.parse(text)
    } catch (_: DateTimeParseException) {
        null
    }
}
