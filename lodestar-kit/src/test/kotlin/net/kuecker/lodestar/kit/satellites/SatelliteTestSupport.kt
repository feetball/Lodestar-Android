package net.kuecker.lodestar.kit.satellites

import net.kuecker.lodestar.kit.foundation.*
import java.time.Instant
import kotlin.test.assertNotNull

/** Shared fixtures and helpers of the satellite tests (Swift: `SatelliteTestSupport.swift`). */
internal object SatelliteTestSupport {
    /** The ISS element set of the CelesTrak snapshot of 2026-10-18 (`satellite_stations.tle`). */
    const val issLine1: String = "1 25544U 98067A   26291.14461894  .00007503  00000+0  14456-3 0  9994"
    const val issLine2: String = "2 25544  51.6328  51.6259 0006780 277.7856  82.2363 15.48918166590814"

    /** The epoch of those elements, 2026-10-18T03:28:15.076416Z, in seconds since 1970. */
    const val issEpochUnixSeconds: Double = 1792294095.076416

    fun fixtureBytes(name: String): ByteArray {
        val stream = checkNotNull(SatelliteTestSupport::class.java.getResourceAsStream("/fixtures/$name")) {
            "The fixture $name is missing from the test resources."
        }
        return stream.use { it.readBytes() }
    }

    fun fixtureText(name: String): String = String(fixtureBytes(name), Charsets.UTF_8)

    fun date(unixSeconds: Double): Instant = instantFromTimeIntervalSince1970(unixSeconds)

    fun observer(latitude: Double, longitude: Double, elevation: Double = 0.0): Observer =
        Observer(assertNotNull(GeographicLocation.validated(latitude, longitude, elevation)))

    val boulder: Observer = observer(40.015, -105.2705, 1655.0)
    val sydney: Observer = observer(-33.8688, 151.2093, 20.0)

    /** `line` with its last character replaced by the correct checksum digit. */
    fun withChecksum(line: String): String = line.substring(0, 68) + ElementSetParser.checksum(line)

    fun iss(): OrbitalElements = ElementSetParser.parseTwoLine(issLine1, issLine2, "ISS (ZARYA)")

    /** The elements of the reference satellite sample, by identifier (`iss`, `css`, `hst`, `starlink`, `gps`, `molniya`, `geo`, `polar`). */
    fun referenceSatellites(): Map<String, Satellite> {
        val ids = listOf("iss", "css", "hst", "starlink", "gps", "molniya", "geo", "polar")
        val batch = ElementSetParser.parseJson(fixtureBytes("satellite_omm_sample.json"))
        check(batch.skippedCount == 0 && batch.elements.size == ids.size)
        return ids.zip(batch.elements.map { Satellite(it) }).toMap()
    }

    /** A geostationary-like element set that sits over longitude [longitude] on the equator at [epoch]. */
    fun geostationary(longitude: Double, epoch: Instant): OrbitalElements {
        val gmstDegrees = Math.toDegrees(SatelliteFrames.greenwichMeanSiderealTime(epoch))
        val anomaly = AstroMath.normalizeDegrees(gmstDegrees + longitude)
        return OrbitalElements.create(
            name = "GEOTEST",
            catalogNumber = 99_990,
            epoch = epoch,
            meanMotion = 1.00273790935,
            eccentricity = 0.0001,
            inclination = 0.05,
            rightAscensionOfAscendingNode = 0.0,
            argumentOfPericenter = 0.0,
            meanAnomaly = anomaly,
        )
    }
}
