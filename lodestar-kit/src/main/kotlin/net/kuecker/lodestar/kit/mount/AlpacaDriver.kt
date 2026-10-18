package net.kuecker.lodestar.kit.mount

import net.kuecker.lodestar.kit.foundation.EquatorialCoordinate
import net.kuecker.lodestar.kit.foundation.GeographicLocation
import java.time.Instant
import kotlin.math.ceil

/**
 * Settings of an [AlpacaDriver].
 *
 * Values are clamped to their ranges when the configuration is created.
 */
@ConsistentCopyVisibility
data class AlpacaConfiguration private constructor(
    /** The telescope's device number from the management API (0 or more). */
    val deviceNumber: Int,
    /** The `ClientID` sent with every request (1 or more). */
    val clientID: Int,
    /** How long one HTTP exchange may take (1 to 60 s). */
    val exchangeTimeoutSeconds: Double,
    /**
     * Whether a positive `moveaxis` rate on the primary axis moves east (increasing right ascension or azimuth).
     * ASCOM leaves the sign of `moveaxis` to the driver; set false for a mount that turns the other way.
     */
    val primaryPositiveIsEast: Boolean,
    /** Whether a positive `moveaxis` rate on the secondary axis moves north (increasing declination or altitude). */
    val secondaryPositiveIsNorth: Boolean,
) {
    companion object {
        /** Device 0, client 1, 10 s timeout, positive rates move east and north. */
        val standard: AlpacaConfiguration = invoke()

        /** Creates a configuration; out-of-range values are clamped. */
        operator fun invoke(
            deviceNumber: Int = 0,
            clientID: Int = 1,
            exchangeTimeoutSeconds: Double = 10.0,
            primaryPositiveIsEast: Boolean = true,
            secondaryPositiveIsNorth: Boolean = true,
        ): AlpacaConfiguration = AlpacaConfiguration(
            deviceNumber = deviceNumber.coerceAtLeast(0),
            clientID = clientID.coerceAtLeast(1),
            exchangeTimeoutSeconds = if (exchangeTimeoutSeconds.isNaN()) 10.0 else exchangeTimeoutSeconds.coerceIn(1.0, 60.0),
            primaryPositiveIsEast = primaryPositiveIsEast,
            secondaryPositiveIsNorth = secondaryPositiveIsNorth,
        )
    }
}

/**
 * Drives an ASCOM Alpaca telescope over an [AlpacaTransport]. See [AlpacaRequestBuilder] for the requests used.
 *
 * Coordinates follow the mount's `equatorialsystem`: topocentric (JNow of the local sky, [MountEpoch.APPARENT_OF_DATE])
 * or J2000; other systems are refused at [connect]. Slews are asynchronous (`slewtocoordinatesasync`), completion is
 * read from `slewing`. Short nudges at the guide rate use `pulseguide`, whose direction is standardised and whose
 * duration the mount enforces; faster nudges use `moveaxis`, whose sign the mount defines ([AlpacaConfiguration]).
 */
class AlpacaDriver(
    private val transport: AlpacaTransport,
    /** The settings in use. */
    val configuration: AlpacaConfiguration = AlpacaConfiguration.standard,
) : MountDriver {
    private val requests = AlpacaRequestBuilder(configuration.deviceNumber, configuration.clientID)
    private var resolvedEpoch: MountEpoch = MountEpoch.APPARENT_OF_DATE
    private var canSlewAsync = true
    private var canSync = true
    private var canPulseGuide = false
    private var canSetTracking = true
    private var canMovePrimary = false
    private var canMoveSecondary = false
    private val movingAxes = LinkedHashSet<AlpacaAxis>()

    override val kind: MountProtocol get() = MountProtocol.ALPACA

    override val epoch: MountEpoch get() = resolvedEpoch

    @Throws(MountError::class)
    override fun connect(): MountCapabilities {
        perform(requests.setConnected(true))
        if (!perform(requests.connected()).booleanValue()) throw MountError.NotConnected
        val system = try {
            perform(requests.equatorialSystem()).integerValue()
        } catch (error: MountError.NotSupported) {
            topocentricSystem
        }
        resolvedEpoch = when (system) {
            topocentricSystem -> MountEpoch.APPARENT_OF_DATE
            j2000System -> MountEpoch.J2000
            else -> throw MountError.UnsupportedCoordinateSystem(system)
        }
        canSlewAsync = probe(requests.canSlewAsync())
        canSync = probe(requests.canSync())
        canPulseGuide = probe(requests.canPulseGuide())
        canSetTracking = probe(requests.canSetTracking())
        canMovePrimary = probe(requests.canMoveAxis(AlpacaAxis.PRIMARY))
        canMoveSecondary = probe(requests.canMoveAxis(AlpacaAxis.SECONDARY))
        val name = try {
            MountText.sanitized(perform(requests.name()).textValue(), 60)
        } catch (error: MountError.NotSupported) {
            ""
        }
        readPosition()
        return MountCapabilities(
            kind = MountProtocol.ALPACA,
            name = if (name.isEmpty()) "Alpaca telescope" else name,
            firmware = null,
            canSlew = canSlewAsync,
            canSync = canSync,
            canAbort = true,
            canSetTracking = canSetTracking,
            canNudge = canPulseGuide || (canMovePrimary && canMoveSecondary),
            canSetSiteAndTime = true,
            gotoNeedsAlignment = false,
            isAligned = null,
        )
    }

    override fun disconnect() {
        movingAxes.clear()
        try {
            perform(requests.setConnected(false))
        } catch (error: MountError) {
            // The session ends either way.
        }
    }

    @Throws(MountError::class)
    override fun readPosition(): EquatorialCoordinate {
        val hours = perform(requests.rightAscension()).doubleValue()
        val declination = perform(requests.declination()).doubleValue()
        if (!(hours >= 0 && hours <= 24) || !(declination >= -90 && declination <= 90)) {
            throw MountError.MalformedReply("position out of range")
        }
        return EquatorialCoordinate.fromRightAscensionHours(hours, declination)
    }

    @Throws(MountError::class)
    override fun readSlewing(): Boolean = perform(requests.slewing()).booleanValue()

    @Throws(MountError::class)
    override fun readTracking(): Boolean? = perform(requests.tracking()).booleanValue()

    @Throws(MountError::class)
    override fun readAligned(): Boolean? = null

    @Throws(MountError::class)
    override fun slew(target: EquatorialCoordinate) {
        if (!canSlewAsync) throw MountError.NotSupported(MountFeature.SLEW)
        val rightAscensionHours = hours(target)
        if (isParked()) throw MountError.Parked
        perform(requests.slewToCoordinatesAsync(rightAscensionHours, target.declination))
    }

    @Throws(MountError::class)
    override fun sync(target: EquatorialCoordinate) {
        if (!canSync) throw MountError.NotSupported(MountFeature.SYNC)
        perform(requests.syncToCoordinates(hours(target), target.declination))
    }

    @Throws(MountError::class)
    override fun abort() {
        perform(requests.abortSlew())
    }

    @Throws(MountError::class)
    override fun setTracking(enabled: Boolean) {
        if (!canSetTracking) throw MountError.NotSupported(MountFeature.TRACKING)
        perform(requests.setTracking(enabled))
    }

    @Throws(MountError::class)
    override fun startNudge(direction: MountNudgeDirection, rate: MountNudgeRate, durationSeconds: Double) {
        val axis = axis(direction)
        val canMove = if (axis == AlpacaAxis.PRIMARY) canMovePrimary else canMoveSecondary
        if (canPulseGuide && (rate == MountNudgeRate.GUIDE || !canMove)) {
            val milliseconds = ceil(durationSeconds * 1000).toInt().coerceIn(1, 30_000)
            perform(requests.pulseGuide(guideDirection(direction), milliseconds))
            return
        }
        if (!canMove) throw MountError.NotSupported(MountFeature.NUDGE)
        val magnitude = rate.degreesPerSecond
        val positive = when (direction) {
            MountNudgeDirection.EAST -> configuration.primaryPositiveIsEast
            MountNudgeDirection.WEST -> !configuration.primaryPositiveIsEast
            MountNudgeDirection.NORTH -> configuration.secondaryPositiveIsNorth
            MountNudgeDirection.SOUTH -> !configuration.secondaryPositiveIsNorth
        }
        perform(requests.moveAxis(axis, if (positive) magnitude else -magnitude))
        movingAxes.add(axis)
    }

    @Throws(MountError::class)
    override fun stopNudge(direction: MountNudgeDirection) {
        val axis = axis(direction)
        if (!movingAxes.contains(axis)) return
        perform(requests.moveAxis(axis, 0.0))
        movingAxes.remove(axis)
    }

    @Throws(MountError::class)
    override fun setSiteAndTime(location: GeographicLocation, date: Instant) {
        val dateRequest = requests.setUTCDate(date)
        perform(requests.setSiteLatitude(location.latitude))
        perform(requests.setSiteLongitude(location.longitude))
        perform(requests.setSiteElevation(location.elevation))
        perform(dateRequest)
    }

    // MARK: Exchanges

    private fun perform(request: AlpacaHTTPRequest): AlpacaReply {
        val response = try {
            transport.send(request, configuration.exchangeTimeoutSeconds)
        } catch (error: MountError) {
            throw error
        } catch (error: Exception) {
            throw MountError.Transport(MountText.sanitized(error.message ?: "I/O error", 120))
        }
        return AlpacaResponseParser.parse(response, request)
    }

    /** A capability flag; a device that does not implement the property counts as "no". */
    private fun probe(request: AlpacaHTTPRequest): Boolean = try {
        perform(request).booleanValue()
    } catch (error: MountError.NotSupported) {
        false
    }

    private fun isParked(): Boolean = try {
        perform(requests.atPark()).booleanValue()
    } catch (error: MountError.NotSupported) {
        false
    }

    private fun hours(target: EquatorialCoordinate): Double {
        if (!target.rightAscension.isFinite() || !target.declination.isFinite()) {
            throw MountError.InvalidInput("the target coordinates are not numbers")
        }
        return target.rightAscension / 15.0
    }

    private fun axis(direction: MountNudgeDirection): AlpacaAxis = when (direction) {
        MountNudgeDirection.EAST, MountNudgeDirection.WEST -> AlpacaAxis.PRIMARY
        MountNudgeDirection.NORTH, MountNudgeDirection.SOUTH -> AlpacaAxis.SECONDARY
    }

    private fun guideDirection(direction: MountNudgeDirection): AlpacaGuideDirection = when (direction) {
        MountNudgeDirection.NORTH -> AlpacaGuideDirection.NORTH
        MountNudgeDirection.SOUTH -> AlpacaGuideDirection.SOUTH
        MountNudgeDirection.EAST -> AlpacaGuideDirection.EAST
        MountNudgeDirection.WEST -> AlpacaGuideDirection.WEST
    }

    private companion object {
        const val topocentricSystem: Int = 1
        const val j2000System: Int = 2
    }
}
