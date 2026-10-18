package net.kuecker.lodestar.kit.events

import net.kuecker.lodestar.kit.foundation.*
import java.time.Instant
import kotlin.math.abs
import kotlin.test.assertTrue

/** Sites, instants and assertions shared by the Events tests. */
internal object EventsTestSupport {
    /** An observing site: name, latitude, east longitude and elevation in metres. */
    class Site(val name: String, val latitude: Double, val longitude: Double, val elevation: Double = 0.0) {
        val observer: Observer
            get() = Observer(GeographicLocation(latitude, longitude, elevation))
    }

    val dallas = Site("Dallas", 32.7767, -96.7970, 150.0)
    val newYork = Site("New York", 40.7128, -74.0060, 10.0)
    val burgos = Site("Burgos", 42.3439, -3.6969, 860.0)
    val luxor = Site("Luxor", 25.6872, 32.6396, 75.0)
    val albuquerque = Site("Albuquerque", 35.0844, -106.6504, 1600.0)
    val london = Site("London", 51.5074, -0.1278, 10.0)
    val madrid = Site("Madrid", 40.4168, -3.7038, 650.0)
    val canaryIslands = Site("Canary Islands", 28.0, -15.0, 10.0)
    val antarctica = Site("Antarctica", -66.5, 110.5, 0.0)
    val tokyo = Site("Tokyo", 35.0, 139.7, 10.0)
    val buenosAires = Site("Buenos Aires", -34.6, -58.4, 25.0)
    val losAngeles = Site("Los Angeles", 34.0522, -118.2437, 90.0)
    val moscow = Site("Moscow", 55.7558, 37.6173, 150.0)
    val boulder = Site("Boulder", 40.0, -105.27, 1650.0)
    val sydney = Site("Sydney", -33.87, 151.21, 0.0)

    /** The instant of an ISO-8601 UTC string such as `2025-03-14T06:58:42Z`. */
    fun utc(text: String): Instant = Instant.parse(text)

    /** Seconds from [b] to [a]. */
    fun seconds(a: Instant, b: Instant): Double = a.timeIntervalSince(b)

    /** Asserts that [actual] is within [tolerance] seconds of the instant [expected]. */
    fun assertInstant(expected: String, actual: Instant?, tolerance: Double, label: String) {
        assertTrue(actual != null, "$label: no instant")
        val difference = seconds(actual, utc(expected))
        assertTrue(abs(difference) <= tolerance, "$label: $actual is $difference s from $expected (tolerance $tolerance)")
    }

    /** Asserts that [actual] is within [tolerance] of [expected]. */
    fun assertNear(expected: Double, actual: Double, tolerance: Double, label: String) {
        assertTrue(abs(expected - actual) <= tolerance, "$label: $actual differs from $expected by more than $tolerance")
    }

    /** The meteor shower peaks among [events], each with its event. */
    fun peaks(events: List<AstronomicalEvent>): List<Pair<AstronomicalEvent, MeteorShowerPeak>> =
        events.mapNotNull { event -> (event.details as? EventDetails.MeteorShower)?.let { Pair(event, it.peak) } }

    /** The conjunctions among [events], each with its event. */
    fun conjunctions(events: List<AstronomicalEvent>): List<Pair<AstronomicalEvent, Conjunction>> =
        events.mapNotNull { event -> (event.details as? EventDetails.Conjunction)?.let { Pair(event, it.conjunction) } }

    /** The oppositions among [events], each with its event. */
    fun oppositions(events: List<AstronomicalEvent>): List<Pair<AstronomicalEvent, Opposition>> =
        events.mapNotNull { event -> (event.details as? EventDetails.Opposition)?.let { Pair(event, it.opposition) } }

    /** The greatest elongations among [events], each with its event. */
    fun elongations(events: List<AstronomicalEvent>): List<Pair<AstronomicalEvent, GreatestElongation>> =
        events.mapNotNull { event -> (event.details as? EventDetails.GreatestElongation)?.let { Pair(event, it.elongation) } }

    /** The apsides among [events], each with its event. */
    fun apsides(events: List<AstronomicalEvent>): List<Pair<AstronomicalEvent, EarthApsis>> =
        events.mapNotNull { event -> (event.details as? EventDetails.EarthApsis)?.let { Pair(event, it.apsis) } }

    /** The equinoxes and solstices among [events], each with its event. */
    fun seasons(events: List<AstronomicalEvent>): List<Pair<AstronomicalEvent, Season>> =
        events.mapNotNull { event -> (event.details as? EventDetails.Season)?.let { Pair(event, it.season) } }

    /** The special full Moons among [events], each with its event. */
    fun specials(events: List<AstronomicalEvent>): List<Pair<AstronomicalEvent, FullMoonSpecial>> =
        events.mapNotNull { event -> (event.details as? EventDetails.FullMoonSpecial)?.let { Pair(event, it.special) } }

    /** The lunar eclipses among [events], each with its event. */
    fun lunarEclipses(events: List<AstronomicalEvent>): List<Pair<AstronomicalEvent, LunarEclipse>> =
        events.mapNotNull { event -> (event.details as? EventDetails.LunarEclipse)?.let { Pair(event, it.eclipse) } }

    /** The solar eclipses among [events], each with its event. */
    fun solarEclipses(events: List<AstronomicalEvent>): List<Pair<AstronomicalEvent, SolarEclipse>> =
        events.mapNotNull { event -> (event.details as? EventDetails.SolarEclipse)?.let { Pair(event, it.eclipse) } }
}
