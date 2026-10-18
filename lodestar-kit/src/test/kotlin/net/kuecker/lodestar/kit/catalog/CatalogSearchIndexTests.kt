package net.kuecker.lodestar.kit.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The normalized text of a key, for checking the index against a plain implementation. */
internal fun SearchIndex.key(keyIndex: Int): SearchText.Key {
    val start = keyStarts[keyIndex]
    return SearchText.Key(text.copyOfRange(start, start + keyLengths[keyIndex]), keyWordStarts[keyIndex])
}

/**
 * The search rules written out in the plainest way, to check the optimized scan against.
 *
 * It looks at every key of the index one by one with ordinary array operations, finds the best
 * match of every object and orders the objects by kind and then by the documented tie-break:
 * listed objects first, then brighter ones, then by name.
 */
private class ReferenceSearch(catalog: Catalog) {
    data class Match(val obj: Int, val key: Int, val kind: CatalogSearchResult.MatchKind)

    private val keys: List<SearchText.Key>
    private val owners: List<Int>
    private val rankOfObject: IntArray

    init {
        val index = catalog.searchIndex
        keys = (0 until index.keyCount).map { index.key(it) }
        owners = (0 until index.keyCount).map { index.source(it).obj }
        val objects = catalog.objects

        fun precedes(first: Int, second: Int): Boolean {
            val a = objects[first]
            val b = objects[second]
            if (a.isListed != b.isListed) return a.isListed
            if (a.prominence != b.prominence) return a.prominence < b.prominence
            if (a.rankingMagnitude != b.rankingMagnitude) return a.rankingMagnitude < b.rankingMagnitude
            val nameA = SearchText.key(a.name).bytes
            val nameB = SearchText.key(b.name).bytes
            if (SearchText.naturalPrecedes(nameA, nameB)) return true
            if (SearchText.naturalPrecedes(nameB, nameA)) return false
            return a.id < b.id
        }

        val order = objects.indices.sortedWith { first, second ->
            if (precedes(first, second)) -1 else if (precedes(second, first)) 1 else 0
        }
        val rank = IntArray(objects.size)
        for ((position, obj) in order.withIndex()) rank[obj] = position
        rankOfObject = rank
    }

    /** Every matching object, best first. */
    fun matches(query: ByteArray): List<Match> {
        val best = HashMap<Int, Match>()
        for ((keyIndex, key) in keys.withIndex()) {
            val kind = kind(key, query) ?: continue
            val owner = owners[keyIndex]
            val current = best[owner]
            if (current != null && current.kind <= kind) continue
            best[owner] = Match(owner, keyIndex, kind)
        }
        if (best.isEmpty() && query.size >= 3) {
            for ((keyIndex, key) in keys.withIndex()) {
                if (best[owners[keyIndex]] == null && isLoose(key, query)) {
                    best[owners[keyIndex]] = Match(owners[keyIndex], keyIndex, CatalogSearchResult.MatchKind.FUZZY)
                }
            }
        }
        return best.values.sortedWith { first, second ->
            if (first.kind != second.kind) {
                first.kind.compareTo(second.kind)
            } else {
                rankOfObject[first.obj].compareTo(rankOfObject[second.obj])
            }
        }
    }

    private fun kind(key: SearchText.Key, query: ByteArray): CatalogSearchResult.MatchKind? {
        val bytes = key.bytes
        if (bytes.size < query.size) return null

        /** Whether the query is the text of the key from `offset` on, for as long as the query goes. */
        fun occurs(offset: Int): Boolean {
            for (position in query.indices) {
                if (bytes[offset + position] != query[position]) return false
            }
            return true
        }
        if (occurs(0)) {
            return if (bytes.size == query.size) CatalogSearchResult.MatchKind.EXACT else CatalogSearchResult.MatchKind.PREFIX
        }
        val lastOffset = bytes.size - query.size
        for (offset in 1..lastOffset) {
            if (key.wordStarts and (1L shl offset) != 0L && occurs(offset)) return CatalogSearchResult.MatchKind.WORD_PREFIX
        }
        for (offset in 1..lastOffset) {
            if (occurs(offset)) return CatalogSearchResult.MatchKind.CONTAINS
        }
        return null
    }

    /** Whether the query's bytes occur in order in the key, the first one at the start of a word. */
    private fun isLoose(key: SearchText.Key, query: ByteArray): Boolean {
        val bytes = key.bytes
        for (start in bytes.indices) {
            if (key.wordStarts and (1L shl start) == 0L || bytes[start] != query[0]) continue
            var matched = 0
            for (position in start until bytes.size) {
                if (matched < query.size && bytes[position] == query[matched]) matched += 1
            }
            if (matched == query.size) return true
        }
        return false
    }
}

class CatalogSearchIndexTests {
    // MARK: Symbol masks

    @Test
    fun testSymbolMasksHaveOneBitPerLetterAndDigit() {
        val letters = "abcdefghijklmnopqrstuvwxyz".toByteArray(Charsets.US_ASCII)
        val digits = "0123456789".toByteArray(Charsets.US_ASCII)
        var seen = 0L
        for (byte in letters + digits) {
            val bit = SearchIndex.symbolBit(byte)
            assertEquals(1, java.lang.Long.bitCount(bit), byte.toInt().toChar().toString())
            assertEquals(0L, seen and bit, "bits are distinct")
            seen = seen or bit
        }
        assertEquals(36, java.lang.Long.bitCount(seen))
        assertEquals(1L, SearchIndex.symbolBit('a'.code.toByte()))
        assertEquals(1L shl 25, SearchIndex.symbolBit('z'.code.toByte()))
        assertEquals(1L shl 26, SearchIndex.symbolBit('0'.code.toByte()))
        assertEquals(1L shl 35, SearchIndex.symbolBit('9'.code.toByte()))
        for (byte in intArrayOf(0, 0x2F, 0x3A, 0x40, 0x5B, 0x60, 0x7B, 0x80, 0xFF)) {
            assertEquals(1L shl 63, SearchIndex.symbolBit(byte.toByte()), "$byte is not a character of a key")
        }
        assertEquals(0L, SearchIndex.symbolMask(ByteArray(0)))
        assertEquals(1L or 2L or (1L shl 26) or (1L shl 35), SearchIndex.symbolMask("aab09".toByteArray(Charsets.US_ASCII)))
    }

    @Test
    fun testAKeyMaskContainsTheMaskOfEverythingInsideIt() {
        val index = CatalogFixture.catalog().searchIndex
        for (keyIndex in 0 until index.keyCount step 7) {
            val key = index.key(keyIndex)
            val mask = index.symbolMasks[keyIndex]
            assertEquals(mask, SearchIndex.symbolMask(key.bytes))
            val inner = key.bytes.drop(1).take(3).toByteArray()
            assertEquals(SearchIndex.symbolMask(inner), SearchIndex.symbolMask(inner) and mask)
        }
    }

    // MARK: The index and a plain implementation agree

    /**
     * Queries made from the keys themselves (prefixes, inner pieces, pieces of later words, pieces with a
     * letter left out) and from nothing in particular, so that every kind of match and every stage of the
     * scan is exercised.
     */
    private fun queries(index: SearchIndex): List<ByteArray> {
        var state = 0x2545_F491_4F6C_DD1DL
        fun next(bound: Int): Int {
            state = state * 6_364_136_223_846_793_005L + 1_442_695_040_888_963_407L
            return ((state ushr 33) % bound.toLong()).toInt()
        }

        val queries = ArrayList<ByteArray>()
        for (text in listOf("a", "e", "1", "m", "ngc", "m31", "sirius", "alpha", "orion", "hd 4", "hip 3", "andgal", "zqx", "cen")) {
            queries.add(SearchText.key(text).bytes)
        }
        for (iteration in 0 until 400) {
            val key = index.key(next(index.keyCount))
            val bytes = key.bytes
            when (next(5)) {
                0 -> queries.add(bytes.copyOfRange(0, minOf(bytes.size, 1 + next(minOf(bytes.size, 6)))))
                1 -> {
                    val length = 1 + next(minOf(bytes.size, 5))
                    val start = next(bytes.size - length + 1)
                    queries.add(bytes.copyOfRange(start, start + length))
                }
                2 -> {
                    val words = bytes.indices.filter { key.wordStarts and (1L shl it) != 0L }
                    val start = words[next(words.size)]
                    val rest = bytes.copyOfRange(start, bytes.size)
                    queries.add(rest.copyOfRange(0, minOf(rest.size, 1 + next(4))))
                }
                3 -> if (bytes.size >= 4) {
                    val dropped = bytes.copyOfRange(0, minOf(bytes.size, 3 + next(5))).toMutableList()
                    dropped.removeAt(1 + next(dropped.size - 1))
                    queries.add(dropped.toByteArray())
                } else {
                    queries.add(ByteArray(1 + next(5)) { ('a'.code + next(26)).toByte() })
                }
                else -> queries.add(ByteArray(1 + next(5)) { ('a'.code + next(26)).toByte() })
            }
        }
        return queries.filter { it.isNotEmpty() }
    }

    @Test
    fun testTheScanAgreesWithAPlainImplementationForEveryLimit() {
        val catalog = CatalogFixture.catalog()
        val reference = ReferenceSearch(catalog)
        val index = catalog.searchIndex
        val kinds = HashSet<CatalogSearchResult.MatchKind>()
        for (query in queries(index)) {
            val expected = reference.matches(query)
            kinds.addAll(expected.map { it.kind })
            for (limit in listOf(1, 2, 7, 50, 400, Int.MAX_VALUE)) {
                val actual = index.search(query, limit).map { ReferenceSearch.Match(it.objectIndex, it.keyIndex, it.kind) }
                val wanted = expected.take(limit)
                if (actual != wanted) {
                    val difference = actual.zip(wanted).indexOfFirst { it.first != it.second }
                        .let { if (it >= 0) it else minOf(actual.size, wanted.size) }
                    throw AssertionError(
                        "\"${String(query, Charsets.UTF_8)}\", limit $limit: ${actual.size} results instead of " +
                            "${wanted.size}, the first difference is at position $difference: " +
                            "${actual.drop(difference).firstOrNull()} instead of ${wanted.drop(difference).firstOrNull()}",
                    )
                }
            }
        }
        assertEquals(CatalogSearchResult.MatchKind.entries.toSet(), kinds, "the queries produce every kind of match")
    }

    @Test
    fun testAShorterLimitGivesTheBeginningOfTheLongerResult() {
        val catalog = CatalogFixture.catalog()
        for (query in listOf("a", "m", "ngc", "alpha", "sirius", "cen", "1", "hip 12", "xqz", "andgal")) {
            val everything = catalog.search(query, limit = Int.MAX_VALUE).map { it.obj.id }
            for (limit in listOf(1, 3, 10, 50, 250)) {
                assertEquals(everything.take(limit), catalog.search(query, limit).map { it.obj.id }, "$query, $limit")
            }
        }
    }

    @Test
    fun testSearchesOfAnEmptyOrOversizedQueryFindNothing() {
        val index = CatalogFixture.catalog().searchIndex
        assertTrue(index.search(ByteArray(0), limit = 10).isEmpty())
        assertTrue(index.search(ByteArray(SearchText.maximumKeyLength + 1) { 'a'.code.toByte() }, limit = 10).isEmpty())
        assertTrue(index.search("sirius".toByteArray(Charsets.US_ASCII), limit = 0).isEmpty())
        assertTrue(index.search("sirius".toByteArray(Charsets.US_ASCII), limit = -3).isEmpty())
        assertEquals(1, index.search("sirius".toByteArray(Charsets.US_ASCII), limit = 1).size)
        assertTrue(index.search(byteArrayOf(0xFF.toByte(), 0x00), limit = 10).isEmpty(), "bytes that never occur in a key match nothing")
        assertTrue(index.search("SIRIUS".toByteArray(Charsets.US_ASCII), limit = 10).isEmpty(), "queries are expected to be normalized already")
    }

    @Test
    fun testEveryKeyIsFoundByItself() {
        val catalog = CatalogFixture.catalog()
        val index = catalog.searchIndex
        for (keyIndex in 0 until index.keyCount step 41) {
            val key = index.key(keyIndex)
            val hits = index.search(key.bytes, limit = 5000)
            val owner = index.source(keyIndex).obj
            assertTrue(
                hits.any { it.objectIndex == owner && it.kind == CatalogSearchResult.MatchKind.EXACT },
                "${String(key.bytes, Charsets.UTF_8)} should find object $owner exactly",
            )
        }
    }
}
