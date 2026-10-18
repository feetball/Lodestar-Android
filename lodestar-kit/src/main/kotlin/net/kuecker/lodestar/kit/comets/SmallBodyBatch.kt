package net.kuecker.lodestar.kit.comets

/** Line-by-line leniency shared by the batch parsers. */
internal object SmallBodyBatch {
    /** The longest text a batch parser accepts, 64 Mi characters (a full `NEA.txt` is under 9 Mi). */
    const val maximumInputLength: Int = 64 * 1024 * 1024

    /** How many leading lines are searched for the dashed line that ends an MPCORB header. */
    const val maximumHeaderLines: Int = 200

    /** The shortest run of dashes that counts as the end of a header. */
    const val minimumHeaderRule: Int = 20

    /**
     * Parses every line of [text] with [parseLine] and collects the results. Blank lines are ignored; a line that
     * [parseLine] rejects is counted and the first few are reported; a second occurrence of an identifier is
     * rejected as a duplicate. With [skipHeader], everything up to a line of at least [minimumHeaderRule] dashes in
     * the first [maximumHeaderLines] lines is ignored.
     *
     * @throws SmallBodyParseError.InputTooLarge if [text] is longer than [maximumInputLength].
     */
    fun parse(text: String, skipHeader: Boolean, parseLine: (String) -> SmallBody): SmallBodyParseResult {
        if (text.length > maximumInputLength) {
            throw SmallBodyParseError.InputTooLarge(text.length, maximumInputLength)
        }
        val firstDataLine = if (skipHeader) lineAfterHeader(text) else 1
        val bodies = ArrayList<SmallBody>()
        val seen = HashSet<String>()
        val rejections = ArrayList<SmallBodyLineRejection>()
        var rejected = 0
        var lineNumber = 0
        var start = 0
        while (start <= text.length) {
            var end = text.indexOf('\n', start)
            if (end < 0) end = text.length
            lineNumber += 1
            if (lineNumber >= firstDataLine) {
                var lineEnd = end
                if (lineEnd > start && text[lineEnd - 1] == '\r') lineEnd -= 1
                val line = text.substring(start, lineEnd)
                if (!isBlank(line)) {
                    var failure: SmallBodyParseError? = null
                    try {
                        val body = parseLine(line)
                        if (seen.add(body.id)) bodies.add(body) else failure = SmallBodyParseError.DuplicateObject(body.id)
                    } catch (error: SmallBodyParseError) {
                        failure = error
                    }
                    if (failure != null) {
                        rejected += 1
                        if (rejections.size < SmallBodyParseResult.maximumReportedRejections) {
                            rejections.add(SmallBodyLineRejection(lineNumber, failure))
                        }
                    }
                }
            }
            start = end + 1
        }
        return SmallBodyParseResult(bodies, rejected, rejections)
    }

    private fun isBlank(line: String): Boolean {
        for (character in line) {
            if (character != ' ' && character != '\t') return false
        }
        return true
    }

    /** The 1-based number of the first line after an MPCORB header, or 1 if the text has none. */
    private fun lineAfterHeader(text: String): Int {
        var lineNumber = 0
        var start = 0
        while (start <= text.length && lineNumber < maximumHeaderLines) {
            var end = text.indexOf('\n', start)
            if (end < 0) end = text.length
            lineNumber += 1
            var lineEnd = end
            if (lineEnd > start && text[lineEnd - 1] == '\r') lineEnd -= 1
            if (lineEnd - start >= minimumHeaderRule) {
                var onlyDashes = true
                for (index in start until lineEnd) {
                    if (text[index] != '-') {
                        onlyDashes = false
                        break
                    }
                }
                if (onlyDashes) return lineNumber + 1
            }
            start = end + 1
        }
        return 1
    }
}
