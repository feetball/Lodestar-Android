package net.kuecker.lodestar.kit.catalog

/**
 * One search hit before it is turned into a public [CatalogSearchResult].
 *
 * @property objectIndex The position of the object in `Catalog.objects`.
 * @property keyIndex The index of the best matching key in the search index.
 * @property kind How well the key matched.
 */
internal data class SearchHit(val objectIndex: Int, val keyIndex: Int, val kind: CatalogSearchResult.MatchKind)

/**
 * The object and entry a search key was made from (Swift's `(object:entry:)` tuple).
 *
 * @property obj The position of the object in `Catalog.objects`.
 * @property entry Which text of the object the key was made from: 0 is its name, then its designations,
 * then its alternate names.
 */
internal data class SearchKeySource(val obj: Int, val entry: Int)

/**
 * The precomputed keys of all objects, scanned linearly for each query.
 *
 * Every object contributes one key per searchable text (names, designations and their spelling
 * variants). All keys live in one byte array, and each key has a mask of the letters and digits it
 * contains, so a query first discards the keys that lack one of its characters with a single
 * comparison and only looks closely at the rest. A query allocates two arrays with an entry per object
 * and the result; about 40 000 keys are scanned in a fraction of a millisecond.
 *
 * The keys are stored as parallel arrays (Swift keeps an array of `KeyRecord` structs): a key's fields
 * are at the same index in [keyStarts], [keyOwners], [keyWordStarts], [keyEntries] and [keyLengths],
 * and its character mask in [symbolMasks]. The scan reads only the masks of the keys it rejects.
 *
 * @property text The bytes of all keys, one after the other.
 * @property keyStarts Per key: the offset of its first byte in [text].
 * @property keyOwners Per key: the object it belongs to (the keys are grouped by object, in the order of
 * `Catalog.objects`).
 * @property keyWordStarts Per key: the word boundaries, see [SearchText.Key.wordStarts].
 * @property keyEntries Per key: which text of the object it was made from: 0 is its name, then its
 * designations, then its alternate names.
 * @property keyLengths Per key: its length in bytes (at most [SearchText.maximumKeyLength]).
 * @property symbolMasks Per key: the characters it contains, see [symbolMask].
 * @property objectsByRank The objects from the first to the last place in the order of equally good matches.
 */
internal class SearchIndex(
    val text: ByteArray,
    val keyStarts: IntArray,
    val keyOwners: IntArray,
    val keyWordStarts: LongArray,
    val keyEntries: IntArray,
    val keyLengths: IntArray,
    val symbolMasks: LongArray,
    val objectsByRank: IntArray,
) {
    /** The number of keys. */
    val keyCount: Int get() = keyStarts.size

    /** The object and entry a key was made from. */
    fun source(keyIndex: Int): SearchKeySource = SearchKeySource(keyOwners[keyIndex], keyEntries[keyIndex])

    /**
     * The best [limit] matches for a normalized query, best first.
     *
     * An object is ranked by its best-matching key: exact match, key starting with the query,
     * query starting at a later word of the key, query anywhere inside the key. Only when nothing
     * matches in one of those ways, and the query has at least [minimumFuzzyLength] bytes, are
     * objects suggested whose keys contain the query's letters in order, the first of them opening
     * a word. Equal ranks are ordered by [objectsByRank].
     *
     * The scan runs in stages and stops as soon as the better stages have found [limit] objects,
     * since later stages can only add objects that rank below all of those.
     */
    fun search(query: ByteArray, limit: Int): List<SearchHit> {
        if (query.isEmpty() || query.size > SearchText.maximumKeyLength || limit <= 0) return emptyList()
        val scan = Scan(this, query, symbolMask(query))
        scan.findWordMatches()
        if (scan.matchedObjects < limit) {
            scan.findInnerMatches()
        }
        if (scan.matchedObjects == 0 && query.size >= minimumFuzzyLength) {
            scan.findLooseMatches()
        }
        return scan.hits(limit)
    }

    companion object {
        /** Queries shorter than this (in normalized bytes) are not matched loosely. */
        const val minimumFuzzyLength: Int = 3

        /**
         * One bit per character that can occur in a key: 0 to 25 for "a" to "z", 26 to 35 for "0" to "9".
         * Any other byte, which no key contains, takes bit 63.
         */
        fun symbolBit(byte: Byte): Long = SYMBOL_BITS[byte.toInt() and 0xFF]

        private val SYMBOL_BITS = LongArray(256) { value ->
            when (value) {
                in 0x61..0x7A -> 1L shl (value - 0x61)
                in 0x30..0x39 -> 1L shl (26 + value - 0x30)
                else -> 1L shl 63
            }
        }

        /**
         * The set of characters in [bytes], as an or of [symbolBit].
         *
         * A key can only match a query if its mask contains the query's mask.
         */
        fun symbolMask(bytes: ByteArray): Long {
            var mask = 0L
            for (byte in bytes) {
                mask = mask or symbolBit(byte)
            }
            return mask
        }
    }
}

// MARK: - Scanning

/** The state of one search: the best match found so far for every object. */
private class Scan(index: SearchIndex, private val query: ByteArray, private val queryMask: Long) {
    private val text = index.text
    private val starts = index.keyStarts
    private val owners = index.keyOwners
    private val wordStartsOfKey = index.keyWordStarts
    private val lengths = index.keyLengths
    private val masks = index.symbolMasks
    private val objectsByRank = index.objectsByRank
    private val keyCount = index.keyCount

    /** Per object: the `MatchKind` raw value of its best match, or [NO_MATCH]. */
    private val bestKind = ByteArray(objectsByRank.size).also { it.fill(NO_MATCH) }

    /** Per object: the key of its best match. */
    private val bestKey = IntArray(objectsByRank.size)

    /** Per kind (in `MatchKind` order): how many objects have it as their best match. */
    private val objectsOfKind = IntArray(kinds.size)

    /** How many objects have a match. */
    var matchedObjects = 0
        private set

    /** Finds the keys equal to the query, starting with it, or with a later word that starts with it. */
    fun findWordMatches() {
        val masks = masks
        val lengths = lengths
        val starts = starts
        val owners = owners
        val text = text
        val queryMask = queryMask
        val queryLength = query.size
        val first = query[0]
        for (keyIndex in 0 until keyCount) {
            if (masks[keyIndex] and queryMask != queryMask) continue
            val length = lengths[keyIndex]
            if (length < queryLength) continue
            val start = starts[keyIndex]
            val kind: Int
            if (text[start] == first && matches(start)) {
                kind = if (length == queryLength) EXACT else PREFIX
            } else if (opensWord(keyIndex)) {
                kind = WORD_PREFIX
            } else {
                continue
            }
            note(kind, keyIndex, owners[keyIndex])
        }
    }

    /** Finds the keys that contain the query somewhere, for the objects without a better match. */
    fun findInnerMatches() {
        val masks = masks
        val lengths = lengths
        val starts = starts
        val owners = owners
        val text = text
        val bestKind = bestKind
        val queryMask = queryMask
        val queryLength = query.size
        val first = query[0]
        for (keyIndex in 0 until keyCount) {
            if (masks[keyIndex] and queryMask != queryMask) continue
            val length = lengths[keyIndex]
            val owner = owners[keyIndex]
            if (length < queryLength || bestKind[owner] != NO_MATCH) continue
            val start = starts[keyIndex]
            var offset = 0
            while (offset + queryLength <= length) {
                if (text[start + offset] == first && matches(start + offset)) {
                    note(CONTAINS, keyIndex, owner)
                    break
                }
                offset += 1
            }
        }
    }

    /**
     * Finds the keys with the query's characters in order, the first one opening a word, for the
     * objects without any other match.
     */
    fun findLooseMatches() {
        val masks = masks
        val lengths = lengths
        val starts = starts
        val owners = owners
        val text = text
        val bestKind = bestKind
        val query = query
        val queryMask = queryMask
        val queryLength = query.size
        val first = query[0]
        for (keyIndex in 0 until keyCount) {
            if (masks[keyIndex] and queryMask != queryMask) continue
            val length = lengths[keyIndex]
            val owner = owners[keyIndex]
            if (length < queryLength || bestKind[owner] != NO_MATCH) continue
            val start = starts[keyIndex]
            var wordStarts = wordStartsOfKey[keyIndex]
            while (wordStarts != 0L) {
                val offset = java.lang.Long.numberOfTrailingZeros(wordStarts)
                wordStarts = wordStarts and (wordStarts - 1)
                if (offset >= length || length - offset < queryLength || text[start + offset] != first) continue
                var matched = 1
                var position = offset + 1
                while (matched < queryLength && position < length) {
                    if (text[start + position] == query[matched]) matched += 1
                    position += 1
                }
                if (matched == queryLength) {
                    note(FUZZY, keyIndex, owner)
                    break
                }
            }
        }
    }

    /** The best [limit] matched objects: better kinds first, equal kinds in the order of `objectsByRank`. */
    fun hits(limit: Int): List<SearchHit> {
        // The best kinds fill the result first, so only some objects of the last kind that fits are
        // wanted. One walk through the objects in rank order picks them out without sorting, and it
        // ends as soon as they are all found.
        val wanted = objectsOfKind.copyOf()
        var room = limit
        for (kind in wanted.indices) {
            wanted[kind] = minOf(wanted[kind], room)
            room -= wanted[kind]
        }
        var outstanding = wanted.sum()
        if (outstanding <= 0) return emptyList()
        val byKind = Array(wanted.size) { IntArray(wanted[it]) }
        val taken = IntArray(wanted.size)
        for (obj in objectsByRank) {
            val kind = bestKind[obj].toInt() and 0xFF
            if (kind != NO_MATCH_VALUE && taken[kind] < wanted[kind]) {
                byKind[kind][taken[kind]] = obj
                taken[kind] += 1
                outstanding -= 1
                if (outstanding == 0) break
            }
        }
        val hits = ArrayList<SearchHit>(minOf(limit, matchedObjects))
        for (kind in kinds.indices) {
            for (position in 0 until taken[kind]) {
                val obj = byKind[kind][position]
                hits.add(SearchHit(obj, bestKey[obj], kinds[kind]))
            }
        }
        return hits
    }

    // MARK: Helpers

    /** Records a match for an object unless it already has an equal or better one. */
    private fun note(kind: Int, key: Int, owner: Int) {
        val current = bestKind[owner].toInt() and 0xFF
        if (kind >= current) return
        if (current == NO_MATCH_VALUE) {
            matchedObjects += 1
        } else {
            objectsOfKind[current] -= 1
        }
        objectsOfKind[kind] += 1
        bestKind[owner] = kind.toByte()
        bestKey[owner] = key
    }

    /** Whether the query occurs in the text at [offset], which must leave room for all of it. */
    private fun matches(offset: Int): Boolean {
        val query = query
        val text = text
        var index = 0
        val count = query.size
        while (index < count) {
            if (text[offset + index] != query[index]) return false
            index += 1
        }
        return true
    }

    /** Whether a word after the first one starts with the query. */
    private fun opensWord(keyIndex: Int): Boolean {
        val start = starts[keyIndex]
        val length = lengths[keyIndex]
        val first = query[0]
        var wordStarts = wordStartsOfKey[keyIndex] and 1L.inv()
        while (wordStarts != 0L) {
            val offset = java.lang.Long.numberOfTrailingZeros(wordStarts)
            wordStarts = wordStarts and (wordStarts - 1)
            if (offset + query.size <= length && text[start + offset] == first && matches(start + offset)) {
                return true
            }
        }
        return false
    }

    private companion object {
        const val NO_MATCH: Byte = -1
        const val NO_MATCH_VALUE: Int = 0xFF
        const val EXACT: Int = 0
        const val PREFIX: Int = 1
        const val WORD_PREFIX: Int = 2
        const val CONTAINS: Int = 3
        const val FUZZY: Int = 4
        val kinds: List<CatalogSearchResult.MatchKind> = CatalogSearchResult.MatchKind.entries
    }
}

// MARK: - Building

/** Collects the keys of all objects, in object order. */
internal class SearchIndexBuilder {
    private var text = ByteArray(1 shl 20)
    private var textCount = 0
    private var keyStarts = IntArray(1 shl 16)
    private var keyOwners = IntArray(1 shl 16)
    private var keyWordStarts = LongArray(1 shl 16)
    private var keyEntries = IntArray(1 shl 16)
    private var keyLengths = IntArray(1 shl 16)
    private var symbolMasks = LongArray(1 shl 16)
    private var recordCount = 0
    private var objectCount = 0
    private var firstRecordOfObject = 0
    private val keyBuilder = KeyBuilder()

    /** The number of keys the current object has so far. */
    val keyCountOfCurrentObject: Int get() = recordCount - firstRecordOfObject

    /** Starts the keys of the next object (the one after all previous ones). */
    fun beginObject() {
        objectCount += 1
        firstRecordOfObject = recordCount
    }

    /** Adds the key of [string] for the current object, unless it is empty or the object already has it. */
    fun add(string: String, entry: Int) {
        val builder = keyBuilder
        builder.reset()
        builder.readText(string, Int.MAX_VALUE)
        val length = builder.count
        if (length == 0 || entry < 0 || entry > MAXIMUM_ENTRY) return
        val bytes = builder.bytes
        for (index in firstRecordOfObject until recordCount) {
            if (keyLengths[index] == length && equalsKey(index, bytes, length)) return
        }
        ensureCapacity(length)
        keyStarts[recordCount] = textCount
        keyOwners[recordCount] = objectCount - 1
        keyWordStarts[recordCount] = builder.wordStarts
        keyEntries[recordCount] = entry
        keyLengths[recordCount] = length
        symbolMasks[recordCount] = builder.symbolMask
        recordCount += 1
        System.arraycopy(bytes, 0, text, textCount, length)
        textCount += length
    }

    /** The bytes of the key that [add] stored last (a copy). */
    fun lastKeyBytes(): ByteArray {
        val start = keyStarts[recordCount - 1]
        return text.copyOfRange(start, start + keyLengths[recordCount - 1])
    }

    /**
     * Completes the index with the order of equally good matches: [objectsByRank] lists the
     * objects from the first to the last place.
     */
    fun finish(objectsByRank: IntArray): SearchIndex = SearchIndex(
        text = text.copyOf(textCount),
        keyStarts = keyStarts.copyOf(recordCount),
        keyOwners = keyOwners.copyOf(recordCount),
        keyWordStarts = keyWordStarts.copyOf(recordCount),
        keyEntries = keyEntries.copyOf(recordCount),
        keyLengths = keyLengths.copyOf(recordCount),
        symbolMasks = symbolMasks.copyOf(recordCount),
        objectsByRank = objectsByRank,
    )

    private fun equalsKey(index: Int, bytes: ByteArray, length: Int): Boolean {
        val start = keyStarts[index]
        for (offset in 0 until length) {
            if (text[start + offset] != bytes[offset]) return false
        }
        return true
    }

    private fun ensureCapacity(extraBytes: Int) {
        if (textCount + extraBytes > text.size) {
            text = text.copyOf(maxOf(text.size * 2, textCount + extraBytes))
        }
        if (recordCount == keyStarts.size) {
            val capacity = keyStarts.size * 2
            keyStarts = keyStarts.copyOf(capacity)
            keyOwners = keyOwners.copyOf(capacity)
            keyWordStarts = keyWordStarts.copyOf(capacity)
            keyEntries = keyEntries.copyOf(capacity)
            keyLengths = keyLengths.copyOf(capacity)
            symbolMasks = symbolMasks.copyOf(capacity)
        }
    }

    private companion object {
        /** The largest entry number a key can carry (Swift stores it in a `UInt16`). */
        const val MAXIMUM_ENTRY = 0xFFFF
    }
}
