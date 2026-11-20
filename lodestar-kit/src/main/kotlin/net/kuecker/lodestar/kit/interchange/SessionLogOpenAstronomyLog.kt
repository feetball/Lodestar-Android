package net.kuecker.lodestar.kit.interchange

import net.kuecker.lodestar.kit.foundation.roundedHalfAwayFromZero

/** Collects the parts of an OpenAstronomyLog document and writes them in the order of the schema. */
internal class OpenAstronomyLogDocument {
    /** Parts that are written once and referenced by their identifier, in the order they were first used. */
    private class Registry(private val prefix: String) {
        private val identifiers = HashMap<String, String>()
        val elements = ArrayList<String>()

        /** The identifier of the part with [key]; [makeElement] writes it with its new identifier the first time. */
        fun identifier(key: String, makeElement: (String) -> String): String {
            val existing = identifiers[key]
            if (existing != null) return existing
            val identifier = "${prefix}_${elements.size + 1}"
            identifiers[key] = identifier
            elements.add(makeElement(identifier))
            return identifier
        }
    }

    private class Session(val identifier: String, val siteIdentifier: String, var begin: Long, var end: Long)

    private var observerElement = ""
    private val sites = Registry("site")
    private val targets = Registry("target")
    private val scopes = Registry("scope")
    private val filters = Registry("filter")
    private val imagers = Registry("imager")
    private val sessionIndex = HashMap<String, Int>()
    private val sessions = ArrayList<Session>()
    private val observations = ArrayList<String>()

    private fun element(name: String, text: String, indent: Int, attributes: String = ""): String =
        "  ".repeat(indent) + "<$name$attributes>${InterchangeText.xml(text)}</$name>\n"

    fun setObserver(fullName: String) {
        val words = fullName.split(' ').filter { it.isNotEmpty() }
        val surname = words.lastOrNull() ?: ""
        val name = words.dropLast(1).joinToString(" ")
        observerElement = "    <observer id=\"observer_1\">\n" +
            element("name", name, 3) +
            element("surname", surname, 3) +
            "    </observer>\n"
    }

    fun add(entry: SessionLogEntry) {
        val begin = InterchangeText.wholeSeconds(entry.date)
        val isTimed = entry.frameCount > 0 && entry.exposureSeconds > 0
        val end = if (isTimed) begin + entry.totalExposureSeconds.roundedHalfAwayFromZero().toLong() else begin

        var siteIdentifier: String? = null
        val latitude = entry.siteLatitude
        val longitude = entry.siteLongitude
        if (latitude != null && longitude != null) {
            val offset = entry.siteUTCOffsetMinutes ?: 0
            val key = "${entry.siteName}|${InterchangeText.fixed(latitude, 6)}|${InterchangeText.fixed(longitude, 6)}|$offset"
            siteIdentifier = sites.identifier(key) { identifier ->
                "    <site id=\"$identifier\">\n" +
                    element("name", entry.siteName.ifEmpty { "Site" }, 3) +
                    element("longitude", InterchangeText.fixed(longitude, 6), 3, " unit=\"deg\"") +
                    element("latitude", InterchangeText.fixed(latitude, 6), 3, " unit=\"deg\"") +
                    element("timezone", offset.toString(), 3) +
                    "    </site>\n"
            }
        }

        var sessionIdentifier: String? = null
        if (siteIdentifier != null) {
            val key = "${entry.night}|$siteIdentifier"
            val index = sessionIndex[key]
            if (index != null) {
                val session = sessions[index]
                session.begin = minOf(session.begin, begin)
                session.end = maxOf(session.end, end)
                sessionIdentifier = session.identifier
            } else {
                val identifier = "session_${sessions.size + 1}"
                sessionIndex[key] = sessions.size
                sessions.add(Session(identifier, siteIdentifier, begin, end))
                sessionIdentifier = identifier
            }
        }

        val targetIdentifier = targets.identifier(entry.targetID.ifEmpty { entry.targetName }) { identifier ->
            var text = "    <target id=\"$identifier\">\n" +
                element("datasource", "Lodestar", 3) +
                element("name", entry.targetName, 3)
            val rightAscension = entry.rightAscension
            val declination = entry.declination
            if (rightAscension != null && declination != null) {
                text += "      <position>\n" +
                    element("ra", InterchangeText.fixed(rightAscension, 5), 4, " unit=\"deg\"") +
                    element("dec", InterchangeText.fixed(declination, 5), 4, " unit=\"deg\"") +
                    "        <frame>\n" +
                    element("origin", "geo", 5) +
                    element("equinox", "J2000", 5) +
                    "        </frame>\n" +
                    "      </position>\n"
            }
            text + "    </target>\n"
        }

        var scopeIdentifier: String? = null
        var lensText = ""
        val focalLength = entry.focalLength
        val aperture = entry.lensAperture
        if (focalLength != null && aperture != null) {
            val lens = "${InterchangeText.decimal(focalLength, 1)} mm f/${InterchangeText.decimal(aperture, 1)}"
            lensText = lens
            scopeIdentifier = scopes.identifier(lens) { identifier ->
                "    <scope id=\"$identifier\" xsi:type=\"oal:scopeType\">\n" +
                    element("model", "Camera lens $lens", 3) +
                    element("aperture", InterchangeText.decimal(focalLength / aperture, 2), 3) +
                    element("focalLength", InterchangeText.decimal(focalLength, 1), 3) +
                    "    </scope>\n"
            }
        }

        var filterIdentifier: String? = null
        if (entry.filter.isNotEmpty()) {
            filterIdentifier = filters.identifier(entry.filter) { identifier ->
                "    <filter id=\"$identifier\">\n" +
                    element("model", entry.filter, 3) +
                    element("type", "other", 3) +
                    "    </filter>\n"
            }
        }

        var imagerIdentifier: String? = null
        val pixelsX = entry.sensorPixelsX
        val pixelsY = entry.sensorPixelsY
        if (pixelsX != null && pixelsY != null) {
            val model = entry.camera.ifEmpty { "Camera" }
            imagerIdentifier = imagers.identifier("$model|$pixelsX|$pixelsY") { identifier ->
                "    <imager id=\"$identifier\" xsi:type=\"oal:ccdCameraType\">\n" +
                    element("model", model, 3) +
                    element("pixelsX", pixelsX.toString(), 3) +
                    element("pixelsY", pixelsY.toString(), 3) +
                    element("binning", "1", 3) +
                    "    </imager>\n"
            }
        }

        val observation = StringBuilder()
        observation.append("  <observation id=\"observation_${observations.size + 1}\">\n")
        observation.append(element("observer", "observer_1", 2))
        if (siteIdentifier != null) observation.append(element("site", siteIdentifier, 2))
        if (sessionIdentifier != null) observation.append(element("session", sessionIdentifier, 2))
        observation.append(element("target", targetIdentifier, 2))
        observation.append(element("begin", InterchangeText.dateTimeText(begin), 2))
        if (isTimed) observation.append(element("end", InterchangeText.dateTimeText(end), 2))
        if (scopeIdentifier != null) observation.append(element("scope", scopeIdentifier, 2))
        if (filterIdentifier != null) observation.append(element("filter", filterIdentifier, 2))
        if (imagerIdentifier != null) observation.append(element("imager", imagerIdentifier, 2))
        observation.append("    <result lang=\"en\">\n")
        observation.append(element("description", resultText(entry, lensText), 3))
        observation.append("    </result>\n")
        observation.append("  </observation>\n")
        observations.add(observation.toString())
    }

    /** What the result of an observation says: whether it was imaged, the frames, ISO, filter, lens and camera, then the notes. */
    private fun resultText(entry: SessionLogEntry, lens: String): String {
        val parts = ArrayList<String>()
        if (entry.frameCount > 0 && entry.exposureSeconds > 0) {
            parts.add("${entry.frameCount} × ${InterchangeText.decimal(entry.exposureSeconds, 3)} s")
        } else if (entry.frameCount > 0) {
            parts.add("${entry.frameCount} frames")
        } else if (entry.exposureSeconds > 0) {
            parts.add("${InterchangeText.decimal(entry.exposureSeconds, 3)} s")
        }
        if (entry.iso > 0) parts.add("ISO ${entry.iso}")
        if (entry.filter.isNotEmpty()) parts.add("filter ${entry.filter}")
        if (lens.isNotEmpty()) parts.add("lens $lens")
        if (entry.camera.isNotEmpty()) parts.add("camera ${entry.camera}")
        var text = if (entry.isImaged) "Imaged" else "Not imaged"
        if (parts.isNotEmpty()) text += ": " + parts.joinToString(", ")
        text += "."
        if (entry.notes.isNotEmpty()) text += "\n" + entry.notes
        return text
    }

    /** The document text. */
    fun text(): String {
        val result = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        result.append("<oal:observations version=\"2.1\" xmlns:oal=\"${SessionLogCodec.openAstronomyLogNamespace}\"")
        result.append(" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"")
        result.append(" xsi:schemaLocation=\"${SessionLogCodec.openAstronomyLogNamespace} oal21.xsd\">\n")
        result.append("  <observers>\n").append(observerElement).append("  </observers>\n")
        result.append(container("sites", sites.elements))
        result.append("  <sessions>\n")
        for (session in sessions) {
            result.append("    <session id=\"${session.identifier}\">\n")
            result.append(element("begin", InterchangeText.dateTimeText(session.begin), 3))
            result.append(element("end", InterchangeText.dateTimeText(session.end), 3))
            result.append(element("site", session.siteIdentifier, 3))
            result.append("    </session>\n")
        }
        result.append("  </sessions>\n")
        result.append(container("targets", targets.elements))
        result.append(container("scopes", scopes.elements))
        result.append(container("eyepieces", emptyList()))
        result.append(container("lenses", emptyList()))
        result.append(container("filters", filters.elements))
        result.append(container("imagers", imagers.elements))
        for (observation in observations) result.append(observation)
        result.append("</oal:observations>\n")
        return result.toString()
    }

    /** A container element; the schema needs every container to be present, empty ones as `<name/>`. */
    private fun container(name: String, elements: List<String>): String =
        if (elements.isEmpty()) "  <$name/>\n" else "  <$name>\n" + elements.joinToString("") + "  </$name>\n"
}
