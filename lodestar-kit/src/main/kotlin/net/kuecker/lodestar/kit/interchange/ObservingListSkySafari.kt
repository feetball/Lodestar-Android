package net.kuecker.lodestar.kit.interchange

import net.kuecker.lodestar.kit.catalog.CatalogText

/** Reads SkySafari observing lists (Swift `ObservingListCodec+SkySafari`). */
internal object ObservingListSkySafari {
    private const val header = "skysafariobservinglistversion"

    /** One `SkyObject` block being read. */
    private class SkySafariObject(val line: Int) {
        val catalogNumbers = ArrayList<String>()
        val commonNames = ArrayList<String>()
    }

    /** Whether the first line that is not blank starts with `SkySafariObservingListVersion`, in any case. */
    fun hasHeader(text: String): Boolean {
        val first = StringBuilder()
        var started = false
        var count = 0
        var index = 0
        while (index < text.length) {
            val scalar = text.codePointAt(index)
            index += Character.charCount(scalar)
            if (scalar == 0x0A || scalar == 0x0D) {
                if (started) break
                continue
            }
            if (!started && CatalogText.isWhiteSpace(scalar)) continue
            started = true
            first.appendCodePoint(scalar)
            count += 1
            if (count >= header.length) break
        }
        return first.toString().lowercase() == header
    }

    /** The lines of [text], split at LF, CRLF and CR. */
    private fun lines(text: String): List<String> {
        val result = ArrayList<String>()
        val current = StringBuilder()
        var index = 0
        while (index < text.length) {
            val character = text[index]
            if (character == '\n' || character == '\r') {
                if (character == '\r' && index + 1 < text.length && text[index + 1] == '\n') index += 1
                result.add(current.toString())
                current.setLength(0)
            } else {
                current.append(character)
            }
            index += 1
        }
        if (current.isNotEmpty()) result.add(current.toString())
        return result
    }

    /**
     * Reads a SkySafari observing list. Keys other than `CatalogNumber` and `CommonName` are ignored, and so is everything
     * outside the `SkyObject` blocks.
     */
    fun decode(text: String): ObservingListImport {
        val targets = ArrayList<ImportedTarget>()
        val errors = ArrayList<ObservingListRowError>()
        var isTruncated = false
        var openObject: SkySafariObject? = null
        var objectCount = 0

        fun close(obj: SkySafariObject, isTerminated: Boolean) {
            if (!isTerminated) errors.add(ObservingListRowError(obj.line, ObservingListRowError.Reason.UNTERMINATED_OBJECT))
            val designation = obj.catalogNumbers.firstOrNull()
            val name = obj.commonNames.firstOrNull()
            if (designation == null && name == null) {
                if (isTerminated) errors.add(ObservingListRowError(obj.line, ObservingListRowError.Reason.MISSING_NAME))
                return
            }
            val others = obj.catalogNumbers.drop(1) + obj.commonNames.drop(1)
            targets.add(
                ImportedTarget(
                    name = name,
                    designation = designation,
                    alternates = others.take(ObservingListCodec.maximumAlternates),
                    line = obj.line,
                ),
            )
        }

        for ((offset, rawLine) in lines(text).withIndex()) {
            val lineNumber = offset + 1
            val line = InterchangeText.singleLine(rawLine, 2_000)
            val separator = line.indexOf('=')
            if (separator < 0) continue
            val key = InterchangeText.singleLine(line.substring(0, separator), 200).lowercase()
            val value = InterchangeText.singleLine(line.substring(separator + 1), 2_000)
            val current = openObject
            if (key == "skyobject" && value.lowercase() == "beginobject") {
                if (current != null) close(current, false)
                if (objectCount >= ObservingListCodec.maximumTargets) {
                    isTruncated = true
                    openObject = null
                    break
                }
                objectCount += 1
                openObject = SkySafariObject(lineNumber)
            } else if (key == "endobject" && value.lowercase() == "skyobject") {
                if (current != null) close(current, true)
                openObject = null
            } else if (current != null && value.isNotEmpty()) {
                val name = InterchangeText.singleLine(value, ObservingListCodec.maximumNameScalars)
                if (key == "catalognumber") {
                    current.catalogNumbers.add(name)
                } else if (key == "commonname") {
                    current.commonNames.add(name)
                }
            }
        }
        val last = openObject
        if (last != null) close(last, false)
        return ObservingListImport(ObservingListFormat.SKY_SAFARI, targets, errors, isTruncated)
    }
}
