package net.kuecker.lodestar.services

import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OnlineDownloaderTest {
    private val userAgent = OnlineUserAgent.make("1.0")

    private fun downloader(read: Int = 2_000, total: Long = 10_000, loopback: Boolean = true) =
        OnlineDownloader(userAgent, connectTimeoutMillis = 1_000, readTimeoutMillis = read, totalTimeoutMillis = total, allowLoopbackHttp = loopback)

    @Test
    fun theUserAgentNamesTheAppAndPointsToTheSupportPageWithoutAnEmailAddress() {
        assertEquals("Lodestar/1.4 (+https://feetball.github.io/Lodestar-Android/support.html)", OnlineUserAgent.make("1.4"))
        assertFalse(OnlineUserAgent.make("1.4").contains("@"))
    }

    @Test
    fun aSuccessfulDownloadDeliversTheBytesAndTheServersValidatorsAndSendsTheUserAgent() {
        val body = "hello orbit".toByteArray()
        LoopbackServer { LoopbackServer.respond(it, 200, body, mapOf("ETag" to "\"abc\"", "Last-Modified" to "Sun, 18 Oct 2026 16:15:16 GMT", "Expires" to "Sun, 18 Oct 2026 19:50:18 GMT")) }.use { server ->
            val result = downloader().fetch(OnlineRequest(server.url, 1_000))
            val downloaded = assertIs<OnlineResult.Downloaded>(result)
            assertContentEquals(body, downloaded.bytes)
            assertEquals("\"abc\"", downloaded.etag)
            assertEquals("Sun, 18 Oct 2026 16:15:16 GMT", downloaded.lastModified)
            assertEquals(1_792_353_018_000L, downloaded.expiresMillis)
            assertEquals(userAgent, server.requests.single().headers["user-agent"])
        }
    }

    @Test
    fun theValidatorsOfTheCachedCopyAreSentAndANotModifiedAnswerIsHandled() {
        LoopbackServer { LoopbackServer.respond(it, 304, headers = mapOf("Expires" to "Sun, 18 Oct 2026 19:50:18 GMT")) }.use { server ->
            val result = downloader().fetch(OnlineRequest(server.url, 1_000, etag = "\"abc\"", lastModified = "Sun, 18 Oct 2026 16:15:16 GMT"))
            val notModified = assertIs<OnlineResult.NotModified>(result)
            assertEquals(1_792_353_018_000L, notModified.expiresMillis)
            val headers = server.requests.single().headers
            assertEquals("\"abc\"", headers["if-none-match"])
            assertEquals("Sun, 18 Oct 2026 16:15:16 GMT", headers["if-modified-since"])
        }
    }

    @Test
    fun noValidatorsAreSentWithoutACachedCopy() {
        LoopbackServer { LoopbackServer.respond(it, 200, byteArrayOf(1)) }.use { server ->
            downloader().fetch(OnlineRequest(server.url, 1_000))
            assertNull(server.requests.single().headers["if-none-match"])
            assertNull(server.requests.single().headers["if-modified-since"])
        }
    }

    @Test
    fun errorStatusesBecomeFailures() {
        for ((status, expected) in listOf(403 to OnlineFailure.FORBIDDEN, 429 to OnlineFailure.RATE_LIMITED, 404 to OnlineFailure.HTTP_ERROR, 503 to OnlineFailure.HTTP_ERROR)) {
            LoopbackServer { LoopbackServer.respond(it, status, "no".toByteArray()) }.use { server ->
                val failed = assertIs<OnlineResult.Failed>(downloader().fetch(OnlineRequest(server.url, 1_000)))
                assertEquals(expected, failed.failure)
                assertEquals(status, failed.statusCode)
            }
        }
    }

    @Test
    fun anEmptyAnswerIsAFailure() {
        LoopbackServer { LoopbackServer.respond(it, 200) }.use { server ->
            assertEquals(OnlineFailure.EMPTY, assertIs<OnlineResult.Failed>(downloader().fetch(OnlineRequest(server.url, 1_000))).failure)
        }
    }

    @Test
    fun aDeclaredSizeBeyondTheLimitIsRefusedBeforeReading() {
        LoopbackServer { LoopbackServer.respond(it, 200, ByteArray(5_000)) }.use { server ->
            val failed = assertIs<OnlineResult.Failed>(downloader().fetch(OnlineRequest(server.url, 4_000)))
            assertEquals(OnlineFailure.TOO_LARGE, failed.failure)
        }
    }

    @Test
    fun aStreamedAnswerBeyondTheLimitIsCutOff() {
        // Chunked: no Content-Length, so only counting the bytes can enforce the limit.
        LoopbackServer { exchange ->
            exchange.sendResponseHeaders(200, 0)
            repeat(20) { exchange.responseBody.write(ByteArray(1_000)) }
        }.use { server ->
            val failed = assertIs<OnlineResult.Failed>(downloader().fetch(OnlineRequest(server.url, 4_000)))
            assertEquals(OnlineFailure.TOO_LARGE, failed.failure)
        }
    }

    @Test
    fun anAnswerExactlyAtTheLimitIsAccepted() {
        LoopbackServer { LoopbackServer.respond(it, 200, ByteArray(4_000) { 7 }) }.use { server ->
            assertEquals(4_000, assertIs<OnlineResult.Downloaded>(downloader().fetch(OnlineRequest(server.url, 4_000))).bytes.size)
        }
    }

    @Test
    fun aServerThatDoesNotAnswerTimesOut() {
        val release = CountDownLatch(1)
        LoopbackServer { release.await() }.use { server ->
            val started = System.nanoTime()
            val failed = assertIs<OnlineResult.Failed>(downloader(read = 300).fetch(OnlineRequest(server.url, 1_000)))
            release.countDown()
            assertEquals(OnlineFailure.TIMEOUT, failed.failure)
            assertTrue((System.nanoTime() - started) / 1_000_000 < 3_000)
        }
    }

    @Test
    fun aTrickleThatNeverEndsHitsTheOverallDeadline() {
        LoopbackServer { exchange ->
            exchange.sendResponseHeaders(200, 0)
            repeat(200) {
                try {
                    exchange.responseBody.write(1)
                    exchange.responseBody.flush()
                    Thread.sleep(20)
                } catch (_: java.io.IOException) {
                    return@repeat
                }
            }
        }.use { server ->
            val failed = assertIs<OnlineResult.Failed>(downloader(read = 2_000, total = 300).fetch(OnlineRequest(server.url, 1_000_000)))
            assertEquals(OnlineFailure.TIMEOUT, failed.failure)
        }
    }

    @Test
    fun aRefusedConnectionIsANetworkFailure() {
        val port = ServerSocket(0).use { it.localPort }
        val failed = assertIs<OnlineResult.Failed>(downloader().fetch(OnlineRequest("http://127.0.0.1:$port/data", 1_000)))
        assertEquals(OnlineFailure.NETWORK, failed.failure)
    }

    @Test
    fun onlyHttpsIsAllowedInProduction() {
        val production = downloader(loopback = false)
        assertEquals(OnlineFailure.INSECURE, assertIs<OnlineResult.Failed>(production.fetch(OnlineRequest("http://celestrak.org/NORAD/elements/gp.php", 1_000))).failure)
        // Not even the loopback address may use plain HTTP unless the tests ask for it.
        LoopbackServer { LoopbackServer.respond(it, 200, byteArrayOf(1)) }.use { server ->
            assertEquals(OnlineFailure.INSECURE, assertIs<OnlineResult.Failed>(production.fetch(OnlineRequest(server.url, 1_000))).failure)
            assertTrue(server.requests.isEmpty())
        }
        assertEquals(OnlineFailure.INSECURE, assertIs<OnlineResult.Failed>(production.fetch(OnlineRequest("ftp://celestrak.org/x", 1_000))).failure)
        assertEquals(OnlineFailure.INSECURE, assertIs<OnlineResult.Failed>(production.fetch(OnlineRequest("not a url", 1_000))).failure)
    }

    @Test
    fun theLoopbackAllowanceIsOnlyForTheLoopbackAddress() {
        val testing = downloader(loopback = true)
        assertEquals(OnlineFailure.INSECURE, assertIs<OnlineResult.Failed>(testing.fetch(OnlineRequest("http://example.com/data", 1_000))).failure)
        assertEquals(OnlineFailure.INSECURE, assertIs<OnlineResult.Failed>(testing.fetch(OnlineRequest("http://192.0.2.1/data", 1_000))).failure)
    }

    @Test
    fun aRedirectIsFollowedWhenItStaysSecureAndRefusedWhenItDoesNot() {
        // The target of the first server's redirect is the second server.
        LoopbackServer { LoopbackServer.respond(it, 200, "moved here".toByteArray()) }.use { target ->
            LoopbackServer { LoopbackServer.respond(it, 302, headers = mapOf("Location" to target.url)) }.use { origin ->
                val downloaded = assertIs<OnlineResult.Downloaded>(downloader().fetch(OnlineRequest(origin.url, 1_000)))
                assertEquals("moved here", String(downloaded.bytes))
            }
        }
        LoopbackServer { LoopbackServer.respond(it, 301, headers = mapOf("Location" to "http://example.com/data")) }.use { origin ->
            val failed = assertIs<OnlineResult.Failed>(downloader().fetch(OnlineRequest(origin.url, 1_000)))
            assertEquals(OnlineFailure.INSECURE, failed.failure)
        }
    }

    @Test
    fun aRedirectLoopGivesUp() {
        lateinit var self: LoopbackServer
        self = LoopbackServer { LoopbackServer.respond(it, 302, headers = mapOf("Location" to self.url)) }
        self.use {
            val failed = assertIs<OnlineResult.Failed>(downloader().fetch(OnlineRequest(self.url, 1_000)))
            assertEquals(OnlineFailure.HTTP_ERROR, failed.failure)
            assertNotNull(failed.message)
        }
    }
}
