package net.kuecker.lodestar.services

import net.kuecker.lodestar.kit.comets.CometElementsParser
import net.kuecker.lodestar.kit.comets.SmallBody
import net.kuecker.lodestar.kit.comets.SmallBodyParseError

/** Reads the Minor Planet Center's `CometEls.txt`. */
object OnlineCometData {
    /** Up to this share of the lines (as a fraction of the parsed comets) may be rejected; beyond it the format has changed. */
    private const val MAXIMUM_REJECTED_SHARE = 0.25

    /**
     * The comets in [bytes]. The file is plain ASCII with fixed columns, so each byte is one character. Throws
     * [OnlineDataException] when the file has no comet or many rejected lines: such a file never replaces good data.
     */
    fun parse(bytes: ByteArray): OnlineParsed<List<SmallBody>> {
        val result = try {
            CometElementsParser.parse(String(bytes, Charsets.ISO_8859_1))
        } catch (e: SmallBodyParseError) {
            throw OnlineDataException("Not a comet file: ${e.description}")
        }
        if (result.bodies.isEmpty()) throw OnlineDataException("No comets in the file.")
        if (result.rejectedLineCount > result.bodies.size * MAXIMUM_REJECTED_SHARE) {
            throw OnlineDataException("${result.rejectedLineCount} lines of the comet file were rejected.")
        }
        return OnlineParsed(result.bodies)
    }
}
