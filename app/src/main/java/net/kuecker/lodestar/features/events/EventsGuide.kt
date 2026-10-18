package net.kuecker.lodestar.features.events

import net.kuecker.lodestar.kit.events.AstronomicalEvent
import net.kuecker.lodestar.kit.events.ElongationDirection
import net.kuecker.lodestar.kit.events.EventDetails
import net.kuecker.lodestar.kit.events.EventKind
import net.kuecker.lodestar.kit.events.FullMoonCategory
import net.kuecker.lodestar.kit.events.LocalSolarEclipseKind
import net.kuecker.lodestar.kit.events.LunarEclipseKind
import net.kuecker.lodestar.kit.events.SeasonKind
import net.kuecker.lodestar.kit.events.SolarEclipseKind
import net.kuecker.lodestar.kit.ephemeris.SolarSystemBody
import net.kuecker.lodestar.kit.formatting.AngleFormatter
import net.kuecker.lodestar.kit.formatting.formatDuration
import net.kuecker.lodestar.kit.phototools.LensSetup
import net.kuecker.lodestar.kit.phototools.SensorFormat
import net.kuecker.lodestar.model.ObserverContext
import net.kuecker.lodestar.shared.AppFormat
import java.text.NumberFormat
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.tan

/** What an event is, why it is worth photographing, and how to do it. */
internal class EventGuide(
    /** One or two short paragraphs. */
    val what: List<String>,
    val why: String,
    val tips: List<String>,
)

/** The explanations of the event detail screen. Plain text built from the event, so it is the same on both platforms. */
internal object EventsGuide {
    /** Focal lengths a lens is usually sold at; suggestions are rounded down to one of these. */
    private val commonFocalLengths = intArrayOf(14, 16, 20, 24, 28, 35, 50, 70, 85, 100, 135, 200, 300, 400, 500, 600, 800, 1000)

    fun guide(event: AstronomicalEvent, context: ObserverContext, plan: EventPlan?, lens: LensSetup): EventGuide =
        when (val details = event.details) {
            is EventDetails.MeteorShower -> meteorShower(event, details, context, plan, lens)
            is EventDetails.Conjunction -> conjunction(details, lens)
            is EventDetails.Opposition -> opposition(details)
            is EventDetails.GreatestElongation -> elongation(details)
            is EventDetails.Season -> season(details.season.kind, context.location.latitude < 0)
            is EventDetails.EarthApsis -> apsis(details)
            is EventDetails.FullMoonSpecial -> fullMoon(details)
            is EventDetails.LunarEclipse -> lunarEclipse(details)
            is EventDetails.SolarEclipse -> solarEclipse(details, context)
        }

    // Meteor showers

    private fun meteorShower(
        event: AstronomicalEvent,
        details: EventDetails.MeteorShower,
        context: ObserverContext,
        plan: EventPlan?,
        lens: LensSetup,
    ): EventGuide {
        val zone = context.timeZone
        val peak = details.peak
        val shower = peak.shower
        val what = listOf(
            "The ${shower.name} are a meteor shower: Earth crosses a stream of dust left by a comet or asteroid, and the " +
                "grains burn up in our atmosphere at about ${shower.velocity.roundToInt()} km/s. The shower is active from " +
                "${EventsFormat.monthDay(peak.activityStart, zone)} to ${EventsFormat.monthDay(peak.activityEnd, zone)} and " +
                "peaks on the night shown.",
            "Its zenithal hourly rate is ${shower.zhr}: that many meteors an hour under a perfectly dark sky with the " +
                "radiant overhead. Real counts are lower, and the meteors seem to spread out from the radiant, a point in " +
                "the sky that gives the shower its name.",
        )
        val why = "Meteors are brief and unpredictable, so a long run of wide-field frames catches the best ones. A " +
            "bright fireball over a landscape or the Milky Way is one of the most striking night photographs, and the " +
            "frames also make a fine time-lapse."
        val tips = ArrayList<String>()
        tips.add(
            "Use a wide, fast lens (${focalRange(14, 24, lens)}) at its widest aperture, ISO 1600–3200 and 15–25 second " +
                "exposures on a tripod. Shoot continuously with an intervalometer and keep going for the whole window.",
        )
        val frame = plan?.meteorFrame
        tips.add(
            "Aim about ${EventsPlanner.METEOR_OFFSET_DEGREES.roundToInt()}° from the radiant rather than at it: meteors " +
                "close to the radiant are short head-on streaks, farther away they are longer." +
                if (frame != null && plan.radiant != null) {
                    " At ${AppFormat.time(plan.time, zone)} the radiant is ${direction(plan.radiant.azimuth)} at " +
                        "${AngleFormatter.formatDegrees(plan.radiant.altitude, 0)}, so frame ${direction(frame.azimuth)}, " +
                        "about ${AngleFormatter.formatDegrees(frame.altitude, 0)} up."
                } else {
                    ""
                },
        )
        tips.add(
            "Showers are usually best after midnight, when your side of the Earth turns into the stream and the radiant " +
                "climbs higher.",
        )
        tips.add(moonTip(peak.moonIlluminatedFraction, plan?.moon))
        tips.add("Wipe the lens, use a dew shield or heater strap, and let your eyes adapt: the red night mode keeps them dark-adapted.")
        return EventGuide(what, why, tips)
    }

    private fun moonTip(fraction: Double, moon: EventMoon?): String {
        val percent = AppFormat.percent(fraction)
        val down = moon != null && !moon.isUp
        return when (MoonInterference(fraction)) {
            MoonInterference.LITTLE ->
                "The Moon is only $percent lit at the peak, so the sky stays dark and fainter meteors show."
            MoonInterference.MODERATE ->
                "The Moon is $percent lit at the peak and will brighten the sky. " +
                    if (down) "It is below the horizon at the best time, which helps." else "Keep it out of the frame and expect to lose the fainter meteors."
            MoonInterference.STRONG ->
                "The Moon is $percent lit at the peak and will wash out the sky. " +
                    if (down) {
                        "It is below the horizon at the best time, which helps a lot."
                    } else {
                        "Shoot before moonrise or after moonset if you can, and aim away from it; only the brighter " +
                            "meteors will show."
                    }
        }
    }

    // Conjunctions

    private fun conjunction(details: EventDetails.Conjunction, lens: LensSetup): EventGuide {
        val conjunction = details.conjunction
        val first = conjunction.first.displayName
        val second = conjunction.second.displayName
        val separation = AppFormat.separation(conjunction.separation)
        val what = ArrayList<String>()
        if (conjunction.involvesMoon) {
            what.add(
                "The Moon passes $separation from $second. The Moon moves about its own width every hour against the " +
                    "stars, so the pair is closest for only a short time.",
            )
            conjunction.topocentric?.let {
                what.add(
                    "The Moon is close enough to Earth for your position to matter: from here the closest approach is " +
                        "${AppFormat.separation(it.separation)}, not $separation.",
                )
            }
            if (conjunction.topocentric?.isOccultation == true) {
                what.add("From here the Moon passes in front of $second and hides it for a while: an occultation.")
            } else if (conjunction.occultationPossible) {
                what.add("The Moon covers $second from some places on Earth, but not from here.")
            }
        } else {
            what.add(
                "$first and $second come within $separation of each other. They are nowhere near each other in space; " +
                    "they only line up as seen from Earth.",
            )
        }
        val why = "Two bright objects in one frame make a simple, striking picture, especially low over a landscape in " +
            "twilight, and a pair this close fits a long lens."
        val tips = ArrayList<String>()
        tips.add(framingTip(conjunction.separation, lens))
        tips.add(
            "Twilight is the best light: the sky is deep blue, the pair is already bright, and the foreground still " +
                "shows. Start about half an hour after sunset or before sunrise and bracket your exposures.",
        )
        if (conjunction.involvesMoon) {
            tips.add(
                "Expose for the Moon (ISO 100–400, about 1/125–1/500 s). A crescent Moon also shows earthshine in a " +
                    "longer exposure, which you can blend with the short one.",
            )
        } else {
            tips.add(
                "Planets are tiny: at 200 mm or more they show as discs, and Jupiter's moons appear as dots beside it. " +
                    "Use a tripod and a remote release.",
            )
        }
        if (conjunction.topocentric?.isOccultation == true) {
            tips.add("An occultation is over in a blink: record video or a continuous burst from a few minutes before the time shown.")
        }
        return EventGuide(what, why, tips)
    }

    /** "To frame both with room around them…" for a pair [separation] degrees apart on the sensor of [lens]. */
    fun framingTip(separation: Double, lens: LensSetup): String {
        val sensor = lens.sensor
        val longest = framingFocalLength(separation, sensor)
        val crop = sensor.cropFactor
        val equivalent = if (crop in 0.95..1.05) "" else " (${number(longest * crop)} mm full-frame equivalent)"
        val wide = if (longest < 28) "" else " A wider lens, 24–35 mm, fits both with a landscape around them."
        return "To frame both with room around them, use a lens up to about ${number(longest)} mm on your " +
            "${sensor.name} camera$equivalent.$wide"
    }

    /**
     * The longest common focal length, in millimetres, that still frames a pair [separation] degrees apart with the
     * pair covering at most 40 % of the sensor's long side (and a field of at least 1°).
     */
    fun framingFocalLength(separation: Double, sensor: SensorFormat): Double {
        val field = max(separation * 2.5, 1.0).coerceAtMost(170.0)
        val longSide = max(sensor.widthMillimeters, sensor.heightMillimeters)
        val exact = longSide / (2 * tan(field / 2 * PI / 180))
        val equivalent = exact * sensor.cropFactor
        val common = commonFocalLengths.lastOrNull { it <= equivalent } ?: commonFocalLengths.first()
        return common / sensor.cropFactor
    }

    private fun focalRange(low: Int, high: Int, lens: LensSetup): String {
        val crop = lens.sensor.cropFactor
        val range = "$low–$high mm"
        if (crop in 0.95..1.05) return "$range, full-frame equivalent"
        return "$range full-frame equivalent, about ${number(low / crop)}–${number(high / crop)} mm on your ${lens.sensor.name} camera"
    }

    // Planets

    private fun opposition(details: EventDetails.Opposition): EventGuide {
        val opposition = details.opposition
        val planet = opposition.planet
        val size = number(opposition.angularDiameterArcseconds)
        val what = listOf(
            "At opposition ${planet.displayName} lies exactly opposite the Sun in our sky: it rises at sunset, is highest " +
                "at midnight and sets at sunrise. It is also at its closest to Earth for the year, " +
                "${EventsFormat.number(opposition.distanceAU, 2)} AU away, so it looks biggest and brightest" +
                (opposition.apparentMagnitude?.let { " (${AppFormat.magnitude(it)})" } ?: "") +
                ", about $size″ across.",
        )
        val why = "The best time of the year to photograph ${planet.displayName}: it is up all night, at its largest and " +
            "brightest, and its surface features are easiest to see."
        val tips = ArrayList<String>()
        when (planet) {
            SolarSystemBody.MARS, SolarSystemBody.JUPITER, SolarSystemBody.SATURN ->
                tips.add(
                    "Planetary detail needs a long focal length, 1,000–3,000 mm effective, and a video of thousands of " +
                        "frames that you stack. At $size″ the disc is a few pixels wide on a normal lens.",
                )
            else ->
                tips.add(
                    "${planet.displayName} is faint and needs a telescope or a long lens and a finder chart; it shows as a " +
                        "small blue-green dot among the stars.",
                )
        }
        tips.add(
            "On a wide lens the planet is a bright point that does not twinkle: a fine addition to a constellation or " +
                "landscape shot, so use it as an anchor in the frame.",
        )
        tips.add("Wait until it is high, above 30°: the air is steadier and the colours are cleaner than near the horizon.")
        return EventGuide(what, why, tips)
    }

    private fun elongation(details: EventDetails.GreatestElongation): EventGuide {
        val elongation = details.elongation
        val planet = elongation.planet.displayName
        val isEvening = elongation.direction == ElongationDirection.EAST
        val sky = if (isEvening) "evening" else "morning"
        val side = if (isEvening) "east" else "west"
        val what = listOf(
            "$planet is at its greatest apparent distance from the Sun, ${AngleFormatter.formatDegrees(elongation.angle, 0)} " +
                "$side of it. That is as far from the Sun's glare as it ever gets, so it is as easy to see as it " +
                "gets in the $sky sky. Its disc is about half lit now, like a first or last quarter Moon.",
        )
        val why = "$planet is always close to the Sun, so this is the best chance to photograph it against a darker sky " +
            "and to catch its phase."
        val tips = listOf(
            if (isEvening) {
                "Look low in the west after sunset. Start about 20–40 minutes after the Sun has set and keep shooting as the sky darkens."
            } else {
                "Look low in the east before sunrise. Be ready about 40–20 minutes before the Sun comes up."
            },
            "Choose a place with a flat, clear horizon in that direction; the planet is never far above it.",
            "A 300–600 mm lens shows the half-lit disc; a wide lens shows $planet as a bright point over the landscape.",
        )
        return EventGuide(what, why, tips)
    }

    // Seasons and apsides

    private fun season(kind: SeasonKind, isSouthern: Boolean): EventGuide {
        val isEquinox = kind == SeasonKind.MARCH_EQUINOX || kind == SeasonKind.SEPTEMBER_EQUINOX
        val what = if (isEquinox) {
            listOf(
                "At an equinox the Sun crosses the celestial equator. Day and night are nearly equal everywhere on Earth, " +
                    "and the Sun rises almost due east and sets almost due west.",
            )
        } else {
            val north = kind == SeasonKind.JUNE_SOLSTICE
            listOf(
                "At a solstice the Sun reaches its farthest ${if (north) "north" else "south"} of the celestial equator. " +
                    "It is the ${longestOrShortest(north, isSouthern)} day of the year where you are, and the Sun's " +
                    "path across the sky is at its ${if (north == !isSouthern) "highest" else "lowest"}.",
            )
        }
        val why = "The solstices and equinoxes mark where the Sun rises and sets, a natural anchor for sun-path " +
            "composites, time-lapses and alignments with landmarks."
        val tips = listOf(
            "Photograph sunrise or sunset from the same spot at each season and combine them into one sun-path image.",
            "Photograph the Sun only when it is low and dim, or through a certified solar filter.",
        )
        return EventGuide(what, why, tips)
    }

    private fun longestOrShortest(isJune: Boolean, isSouthern: Boolean): String =
        if (isJune == !isSouthern) "longest" else "shortest"

    private fun apsis(details: EventDetails.EarthApsis): EventGuide {
        val kind = details.apsis.kind.displayName.lowercase()
        return EventGuide(
            what = listOf(
                "Earth is at $kind: ${EventsFormat.number(details.apsis.distanceAU, 4)} AU from the Sun. The Sun looks very " +
                    "slightly larger at perihelion and smaller at aphelion.",
            ),
            why = "It does not change the sky much, but it is a quiet date to compare solar images through the year.",
            tips = listOf("Photograph the Sun only through a certified solar filter."),
        )
    }

    // The Moon

    private fun fullMoon(details: EventDetails.FullMoonSpecial): EventGuide {
        val special = details.special
        val distance = number(special.distanceKilometers)
        val size = EventsFormat.arcminutes(special.angularDiameterArcseconds)
        val what = ArrayList<String>()
        for (category in special.categories) {
            what.add(
                when (category) {
                    FullMoonCategory.SUPERMOON ->
                        "A supermoon is a full Moon near its closest point to Earth. This one is $distance km away and " +
                            "$size across, up to about 14 % wider and 30 % brighter than a full Moon at its farthest."
                    FullMoonCategory.MICROMOON ->
                        "A micromoon is a full Moon near its farthest point from Earth. This one is $distance km away and " +
                            "only $size across."
                    FullMoonCategory.BLUE_MOON ->
                        "A blue moon is the second of two full Moons in one calendar month. It is not blue; it is " +
                            "$size across at $distance km."
                },
            )
        }
        val why = "A full Moon rising over a landmark is a classic picture, and the Moon looks largest next to the " +
            "horizon. Differences between full Moons are subtle, so a landmark for scale helps."
        val tips = listOf(
            "Use a telephoto (200–600 mm) from a spot far from the landmark so the Moon looks big behind it. Plan " +
                "around moonrise, when it sits low over the horizon.",
            "The full Moon is bright: for the Moon alone try ISO 100, f/8 and about 1/125–1/250 s. At moonrise in twilight " +
                "bracket exposures and blend the landscape with the Moon.",
            "Use a tripod, a remote release and manual focus on the Moon's edge.",
        )
        return EventGuide(what, why, tips)
    }

    // Eclipses

    private fun lunarEclipse(details: EventDetails.LunarEclipse): EventGuide {
        val eclipse = details.eclipse
        val what = ArrayList<String>()
        when (eclipse.kind) {
            LunarEclipseKind.PENUMBRAL ->
                what.add(
                    "The Moon passes through the pale outer part of Earth's shadow, the penumbra. The dimming is subtle, " +
                        "and easiest to see near the greatest eclipse.",
                )
            LunarEclipseKind.PARTIAL ->
                what.add(
                    "Part of the Moon enters the dark core of Earth's shadow, the umbra, and looks bitten away. At its " +
                        "greatest ${AppFormat.percent(eclipse.umbralMagnitude)} of the Moon's width is in the umbra.",
                )
            LunarEclipseKind.TOTAL ->
                what.add(
                    "The Moon moves fully into the umbra and turns copper red, lit by sunlight bent through Earth's " +
                        "atmosphere. Totality lasts ${AngleFormatter.formatDuration(eclipse.totalDuration)}, the partial " +
                        "phases about ${AngleFormatter.formatDuration(eclipse.partialDuration)} in all.",
                )
        }
        what.add("A lunar eclipse is visible from the whole night side of the Earth, wherever the Moon is above the horizon.")
        val why = "It is perfectly safe to photograph, with no filter, and the Moon changes from a bright disc to a dim " +
            "red one and back over hours: ideal for a sequence or a composite."
        val tips = ArrayList<String>()
        tips.add(
            "The brightness changes by a factor of thousands. Partial phases need about 1/500 s at ISO 100; totality needs " +
                "1–4 s at ISO 800–1600, so use a sturdy tripod, a remote release and a 200–600 mm lens.",
        )
        tips.add("For a composite, shoot every few minutes from the start to the end and keep the framing wide enough for the Moon's path.")
        tips.add("Check the Moon's altitude at each contact in the times above: a low Moon over a landscape makes a good frame.")
        return EventGuide(what, why, tips)
    }

    private fun solarEclipse(details: EventDetails.SolarEclipse, context: ObserverContext): EventGuide {
        val eclipse = details.eclipse
        val what = ArrayList<String>()
        what.add(
            when (eclipse.kind) {
                SolarEclipseKind.TOTAL ->
                    "In a total solar eclipse the Moon covers the Sun completely along a narrow path across the Earth. " +
                        "Outside the path a partial eclipse is seen."
                SolarEclipseKind.ANNULAR ->
                    "In an annular eclipse the Moon is too far away to cover the Sun, leaving a bright ring around it, " +
                        "along a narrow path across the Earth. Outside the path a partial eclipse is seen."
                SolarEclipseKind.HYBRID ->
                    "A hybrid eclipse is total in some places and annular in others along its narrow path. Outside the " +
                        "path a partial eclipse is seen."
                SolarEclipseKind.PARTIAL ->
                    "In a partial solar eclipse the Moon covers only part of the Sun everywhere on Earth."
            },
        )
        val zone = context.timeZone
        val local = eclipse.local
        if (local != null) {
            what.add(
                when (local.kind) {
                    LocalSolarEclipseKind.NONE ->
                        "From here no eclipse can be seen: the Moon's shadow misses your location, or the Sun is below the horizon."
                    LocalSolarEclipseKind.PARTIAL ->
                        "From here it is a partial eclipse: ${AppFormat.percent(local.obscuration)} of the Sun's area is covered at the maximum, " +
                            "at ${local.maximum?.let { AppFormat.time(it, zone) } ?: AppFormat.MISSING}."
                    LocalSolarEclipseKind.TOTAL ->
                        "From here it is total: the Sun is fully covered for ${AngleFormatter.formatDuration(local.centralDuration)}."
                    LocalSolarEclipseKind.ANNULAR ->
                        "From here it is annular: a ring of Sun stays visible for ${AngleFormatter.formatDuration(local.centralDuration)}."
                },
            )
        }
        val why = "A total eclipse shows the Sun's corona and a twilight in the middle of the day, the most spectacular " +
            "sight in the sky. Even a partial eclipse makes memorable pictures through a solar filter."
        val tips = ArrayList<String>()
        tips.add("$SOLAR_FILTER_RULE A certified filter over the front of the lens is essential; eclipse glasses protect only your eyes.")
        tips.add(
            "Put the filter on before you aim and keep it on for every partial phase and for the whole of a partial or " +
                "annular eclipse." +
                if (local?.kind == LocalSolarEclipseKind.TOTAL || (local == null && eclipse.isCentral)) {
                    " Only during the few minutes of totality may it come off, and it must be back on before the Sun " +
                        "reappears: use the times above."
                } else {
                    ""
                },
        )
        tips.add(
            "A 400–800 mm lens shows the Sun at a good size. Use a tripod, a remote release and manual focus, and " +
                "rehearse on the Sun a day before.",
        )
        if (local?.kind == LocalSolarEclipseKind.TOTAL || (local == null && eclipse.isCentral)) {
            tips.add("In totality bracket from 1/1000 s to a second at ISO 100–400 and f/8 to catch the corona's inner and outer parts.")
        }
        return EventGuide(what, why, tips)
    }

    // Helpers

    /** The compass name of [azimuth], such as "south-southeast". */
    private fun direction(azimuth: Double): String =
        AngleFormatter.compassPoint(azimuth).fullName.lowercase()

    private fun number(value: Double): String =
        NumberFormat.getNumberInstance().apply { maximumFractionDigits = 0 }.format(value)

    /** Which kinds of event have visibility rules that the detail explains. */
    fun windowRule(kind: EventKind): String? = when (kind) {
        EventKind.METEOR_SHOWER -> "A viewing window needs the Sun below −18° (full darkness) and the radiant above 10°."
        EventKind.CONJUNCTION, EventKind.OPPOSITION, EventKind.GREATEST_ELONGATION ->
            "A viewing window needs the Sun below −6° (at least dusk) and the target above 10°."
        EventKind.FULL_MOON_SPECIAL -> "A viewing window starts at sunset and needs the Moon above 10°."
        EventKind.LUNAR_ECLIPSE -> "A viewing window is the part of the eclipse with the Moon above the horizon."
        EventKind.SOLAR_ECLIPSE -> "A viewing window is the part of the eclipse with the Sun above the horizon."
        EventKind.SEASON, EventKind.EARTH_APSIS -> null
    }
}
