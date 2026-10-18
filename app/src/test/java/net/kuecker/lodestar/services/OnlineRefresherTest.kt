package net.kuecker.lodestar.services

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OnlineRefresherTest {
    private val resource = OnlineResource.STATIONS
    private var now = 1_000_000_000_000L

    private class Fixture(val directory: File, val store: MemoryOnlineStore, val cache: OnlineCache, val refresher: OnlineRefresher)

    private fun fixture(): Fixture {
        val directory = temporaryDirectory()
        val store = MemoryOnlineStore()
        val cache = OnlineCache(directory)
        val downloader = OnlineDownloader(
            OnlineUserAgent.make("1.0"), connectTimeoutMillis = 1_000, readTimeoutMillis = 1_000, totalTimeoutMillis = 5_000,
            allowLoopbackHttp = true,
        )
        return Fixture(directory, store, cache, OnlineRefresher(cache, downloader, store) { now })
    }

    private val parse: (ByteArray) -> OnlineParsed<String> = { bytes ->
        val text = String(bytes)
        if (text == "bad") throw OnlineDataException("bad")
        OnlineParsed(text, 42L)
    }

    @Test
    fun aDownloadIsValidatedCachedAndRecorded() {
        val f = fixture()
        LoopbackServer { LoopbackServer.respond(it, 200, "v1".toByteArray(), mapOf("ETag" to "\"1\"")) }.use { server ->
            val outcome = assertIs<OnlineRefreshOutcome.Updated<String>>(f.refresher.refresh(resource, server.url, null, parse))
            assertEquals("v1", outcome.value)
            val state = f.refresher.state(resource)
            assertEquals(now, state.lastAttemptMillis)
            assertFalse(state.lastAttemptFailed)
            assertEquals(now, state.meta?.savedAtMillis)
            assertEquals("\"1\"", state.meta?.etag)
            assertEquals(42L, state.meta?.dataEpochMillis)
            assertEquals("v1", String(assertNotNull(f.cache.read(resource.key)).bytes))
        }
    }

    @Test
    fun aNotModifiedAnswerKeepsTheDataAndRenewsTheTime() {
        val f = fixture()
        LoopbackServer { exchange ->
            if (exchange.requestHeaders.getFirst("If-None-Match") == "\"1\"") {
                LoopbackServer.respond(exchange, 304)
            } else {
                LoopbackServer.respond(exchange, 200, "v1".toByteArray(), mapOf("ETag" to "\"1\""))
            }
        }.use { server ->
            f.refresher.refresh(resource, server.url, null, parse)
            now += 3_600_000
            assertIs<OnlineRefreshOutcome.NotModified>(f.refresher.refresh(resource, server.url, null, parse))
            assertEquals("v1", String(assertNotNull(f.cache.read(resource.key)).bytes))
            assertEquals(now, f.refresher.state(resource).meta?.savedAtMillis)
            assertFalse(f.refresher.state(resource).lastAttemptFailed)
        }
    }

    @Test
    fun aForbiddenAnswerKeepsTheCachedData() {
        val f = fixture()
        f.cache.write(resource.key, "good".toByteArray(), OnlineCacheMeta(savedAtMillis = 5, dataEpochMillis = 1))
        LoopbackServer { LoopbackServer.respond(it, 403, "blocked".toByteArray()) }.use { server ->
            val outcome = assertIs<OnlineRefreshOutcome.Failed>(f.refresher.refresh(resource, server.url, null, parse))
            assertEquals(OnlineFailure.FORBIDDEN, outcome.failure)
            assertEquals("good", String(assertNotNull(f.cache.read(resource.key)).bytes))
            assertEquals(5, f.cache.readMeta(resource.key)?.savedAtMillis)
            // The attempt counts: the request rules hold after a refusal too.
            assertEquals(now, f.refresher.state(resource).lastAttemptMillis)
            assertTrue(f.refresher.state(resource).lastAttemptFailed)
        }
    }

    @Test
    fun aDownloadTheParserRejectsNeverReplacesGoodData() {
        val f = fixture()
        LoopbackServer { LoopbackServer.respond(it, 200, "bad".toByteArray()) }.use { server ->
            f.cache.write(resource.key, "good".toByteArray(), OnlineCacheMeta(savedAtMillis = 5))
            val outcome = assertIs<OnlineRefreshOutcome.Failed>(f.refresher.refresh(resource, server.url, null, parse))
            assertEquals(OnlineFailure.UNREADABLE, outcome.failure)
            assertEquals("good", String(assertNotNull(f.cache.read(resource.key)).bytes))
            assertTrue(f.refresher.state(resource).lastAttemptFailed)
        }
    }

    @Test
    fun aDeadServerKeepsTheDataAndStillCountsAsAnAttempt() {
        val f = fixture()
        f.cache.write(resource.key, "good".toByteArray(), OnlineCacheMeta(savedAtMillis = 5))
        val port = java.net.ServerSocket(0).use { it.localPort }
        val outcome = assertIs<OnlineRefreshOutcome.Failed>(f.refresher.refresh(resource, "http://127.0.0.1:$port/x", null, parse))
        assertEquals(OnlineFailure.NETWORK, outcome.failure)
        assertEquals("good", String(assertNotNull(f.cache.read(resource.key)).bytes))
        assertEquals(now, f.refresher.state(resource).lastAttemptMillis)
    }

    @Test
    fun anOversizedDownloadIsDiscarded() {
        val f = fixture()
        f.cache.write(resource.key, "good".toByteArray(), OnlineCacheMeta(savedAtMillis = 5))
        LoopbackServer { LoopbackServer.respond(it, 200, ByteArray(resource.maximumBytes + 1)) }.use { server ->
            val outcome = assertIs<OnlineRefreshOutcome.Failed>(f.refresher.refresh(resource, server.url, null, parse))
            assertEquals(OnlineFailure.TOO_LARGE, outcome.failure)
            assertEquals("good", String(assertNotNull(f.cache.read(resource.key)).bytes))
        }
    }

    @Test
    fun validatorsAreOnlySentForTheSameCopy() {
        val f = fixture()
        f.cache.write(
            OnlineResource.FORECAST.key, "old".toByteArray(),
            OnlineCacheMeta(savedAtMillis = 5, lastModified = "Sun, 18 Oct 2026 19:19:15 GMT", key = "32.8,-96.8"),
        )
        LoopbackServer { LoopbackServer.respond(it, 200, "new".toByteArray()) }.use { server ->
            f.refresher.refresh(OnlineResource.FORECAST, server.url, "40.0,-90.0", parse)
            assertNull(server.requests.last().headers["if-modified-since"])
            f.refresher.refresh(OnlineResource.FORECAST, server.url, "40.0,-90.0", parse)
            assertEquals("new", String(assertNotNull(f.cache.read(OnlineResource.FORECAST.key)).bytes))
        }
    }

    @Test
    fun savedDataThatNoLongerParsesIsRemoved() {
        val f = fixture()
        f.cache.write(resource.key, "bad".toByteArray(), OnlineCacheMeta(savedAtMillis = 5))
        assertNull(f.refresher.loadCached(resource, parse))
        assertNull(f.cache.read(resource.key))
        f.cache.write(resource.key, "fine".toByteArray(), OnlineCacheMeta(savedAtMillis = 5))
        assertEquals("fine", f.refresher.loadCached(resource, parse)?.first)
    }

    @Test
    fun turningAFeedOffAndOnAgainCannotSkipTheMinimumInterval() {
        val f = fixture()
        LoopbackServer { LoopbackServer.respond(it, 200, "v1".toByteArray()) }.use { server ->
            f.refresher.refresh(resource, server.url, null, parse)
            // Turning the feed off deletes the data, not the record of the request.
            f.cache.delete(resource.key)
            val state = f.refresher.state(resource)
            assertNull(state.meta)
            assertTrue(OnlineSchedule.isDue(resource, state, now))
            assertFalse(OnlineSchedule.mayRequest(resource, state, now + 119 * 60_000L))
            assertTrue(OnlineSchedule.mayRequest(resource, state, now + 121 * 60_000L))
        }
    }

    @Test
    fun theCachedBytesAreExactlyTheDownloadedBytes() {
        val f = fixture()
        val body = ByteArray(10_000) { (it * 7).toByte() }
        LoopbackServer { LoopbackServer.respond(it, 200, body) }.use { server ->
            f.refresher.refresh(resource, server.url, null) { OnlineParsed(it.size) }
            assertContentEquals(body, assertNotNull(f.cache.read(resource.key)).bytes)
        }
    }
}
