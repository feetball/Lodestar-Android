package net.kuecker.lodestar.services

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.MalformedURLException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.net.UnknownHostException

/** What Lodestar says about itself in every request: the app and its version, and where to read about it. */
object OnlineUserAgent {
    /** The support page the User-Agent points to. Never an email address. */
    const val supportUrl = "https://feetball.github.io/Lodestar-Android/support.html"

    /** `Lodestar/1.0 (+https://feetball.github.io/Lodestar-Android/support.html)`. */
    fun make(versionName: String): String = "Lodestar/$versionName (+$supportUrl)"
}

/** One request: the address (HTTPS only), the size limit and the validators of the copy we already have. */
class OnlineRequest(
    val url: String,
    val maximumBytes: Int,
    val etag: String? = null,
    val lastModified: String? = null,
)

/** Why a download did not deliver data. */
enum class OnlineFailure(val message: String) {
    /** The address (or a redirect) is not HTTPS. */
    INSECURE("The address is not secure."),

    /** HTTP 403: CelesTrak answers a repeated request for unchanged data this way. */
    FORBIDDEN("The provider declined the request (HTTP 403)."),

    /** HTTP 429. */
    RATE_LIMITED("The provider asked for fewer requests (HTTP 429)."),

    /** Any other HTTP error status. */
    HTTP_ERROR("The provider answered with an error."),

    /** More bytes than the feed's limit. */
    TOO_LARGE("The download was larger than expected."),

    /** The connection or the server did not answer in time. */
    TIMEOUT("The provider did not answer in time."),

    /** No route, DNS failure, reset connection. */
    NETWORK("No connection to the provider."),

    /** The answer had no content. */
    EMPTY("The provider sent no data."),

    /** The bytes could not be read as the expected data. */
    UNREADABLE("The downloaded data could not be read."),

    /** The phone could not store the data. */
    STORAGE("The data could not be saved on this phone."),
}

/** The outcome of [OnlineDownloader.fetch]. */
sealed interface OnlineResult {
    /** A complete answer. The validators are the server's, for the next conditional request. */
    class Downloaded(
        val bytes: ByteArray,
        val etag: String?,
        val lastModified: String?,
        val expiresMillis: Long?,
    ) : OnlineResult

    /** HTTP 304: the copy we have is current. */
    class NotModified(val expiresMillis: Long?) : OnlineResult

    /** Nothing arrived. [statusCode] is the HTTP status when there was one. */
    class Failed(val failure: OnlineFailure, val statusCode: Int? = null) : OnlineResult {
        /** The sentence for the error line. */
        val message: String
            get() = if (statusCode != null && failure == OnlineFailure.HTTP_ERROR) {
                "The provider answered with an error (HTTP $statusCode)."
            } else {
                failure.message
            }
    }
}

/**
 * Downloads one file over HTTPS with `HttpURLConnection`: connect and read time-outs, an overall deadline, a size
 * limit, conditional requests (`ETag`, `Last-Modified`) and a descriptive User-Agent. Blocking: call it from a
 * background dispatcher. It follows at most [MAXIMUM_REDIRECTS] redirects, each to another HTTPS address.
 *
 * [allowLoopbackHttp] exists for the tests: it lets a request to `127.0.0.1` or `localhost` use plain HTTP, and
 * nothing else.
 */
class OnlineDownloader(
    private val userAgent: String,
    private val connectTimeoutMillis: Int = 15_000,
    private val readTimeoutMillis: Int = 30_000,
    private val totalTimeoutMillis: Long = 120_000,
    internal val allowLoopbackHttp: Boolean = false,
) {
    fun fetch(request: OnlineRequest): OnlineResult {
        var url = parse(request.url) ?: return OnlineResult.Failed(OnlineFailure.INSECURE)
        val deadline = System.nanoTime() + totalTimeoutMillis * 1_000_000L
        for (hop in 0..MAXIMUM_REDIRECTS) {
            if (!isAllowed(url)) return OnlineResult.Failed(OnlineFailure.INSECURE)
            val connection = try {
                url.openConnection() as? HttpURLConnection ?: return OnlineResult.Failed(OnlineFailure.INSECURE)
            } catch (_: IOException) {
                return OnlineResult.Failed(OnlineFailure.NETWORK)
            }
            try {
                connection.connectTimeout = connectTimeoutMillis
                connection.readTimeout = readTimeoutMillis
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", userAgent)
                request.etag?.let { connection.setRequestProperty("If-None-Match", it) }
                request.lastModified?.let { connection.setRequestProperty("If-Modified-Since", it) }
                val status = connection.responseCode
                when {
                    status == HttpURLConnection.HTTP_NOT_MODIFIED ->
                        return OnlineResult.NotModified(expiresOf(connection))
                    status in REDIRECTS -> {
                        val location = connection.getHeaderField("Location")
                            ?: return OnlineResult.Failed(OnlineFailure.HTTP_ERROR, status)
                        url = try {
                            URL(url, location)
                        } catch (_: MalformedURLException) {
                            return OnlineResult.Failed(OnlineFailure.HTTP_ERROR, status)
                        }
                    }
                    status == HttpURLConnection.HTTP_FORBIDDEN -> return OnlineResult.Failed(OnlineFailure.FORBIDDEN, status)
                    status == TOO_MANY_REQUESTS -> return OnlineResult.Failed(OnlineFailure.RATE_LIMITED, status)
                    status !in 200..299 -> return OnlineResult.Failed(OnlineFailure.HTTP_ERROR, status)
                    else -> return read(connection, request, deadline)
                }
            } catch (_: SocketTimeoutException) {
                return OnlineResult.Failed(OnlineFailure.TIMEOUT)
            } catch (_: UnknownHostException) {
                return OnlineResult.Failed(OnlineFailure.NETWORK)
            } catch (_: IOException) {
                return OnlineResult.Failed(OnlineFailure.NETWORK)
            } finally {
                connection.disconnect()
            }
        }
        return OnlineResult.Failed(OnlineFailure.HTTP_ERROR)
    }

    private fun read(connection: HttpURLConnection, request: OnlineRequest, deadline: Long): OnlineResult {
        val declared = connection.contentLengthLong
        if (declared > request.maximumBytes) return OnlineResult.Failed(OnlineFailure.TOO_LARGE)
        val buffer = ByteArray(BUFFER_SIZE)
        val bytes = ByteArrayOutputStream(if (declared in 1..request.maximumBytes.toLong()) declared.toInt() else BUFFER_SIZE)
        connection.inputStream.use { input: InputStream ->
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (bytes.size() + count > request.maximumBytes) return OnlineResult.Failed(OnlineFailure.TOO_LARGE)
                bytes.write(buffer, 0, count)
                if (System.nanoTime() > deadline) return OnlineResult.Failed(OnlineFailure.TIMEOUT)
            }
        }
        if (bytes.size() == 0) return OnlineResult.Failed(OnlineFailure.EMPTY)
        return OnlineResult.Downloaded(
            bytes.toByteArray(), connection.getHeaderField("ETag"), connection.getHeaderField("Last-Modified"),
            expiresOf(connection),
        )
    }

    private fun parse(text: String): URL? = try {
        URI(text).toURL()
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: MalformedURLException) {
        null
    } catch (_: java.net.URISyntaxException) {
        null
    }

    private fun isAllowed(url: URL): Boolean {
        if (url.protocol.equals("https", ignoreCase = true)) return true
        return allowLoopbackHttp && url.protocol.equals("http", ignoreCase = true) && isLoopback(url.host)
    }

    private fun isLoopback(host: String): Boolean {
        if (host.equals("localhost", ignoreCase = true)) return true
        // Only literal addresses: no name lookup decides what counts as local.
        if (!host.matches(Regex("[0-9.]+|\\[?[0-9a-fA-F:]+]?"))) return false
        return try {
            InetAddress.getByName(host).isLoopbackAddress
        } catch (_: UnknownHostException) {
            false
        }
    }

    private fun expiresOf(connection: HttpURLConnection): Long? =
        connection.getHeaderFieldDate("Expires", 0L).takeIf { it > 0 }

    private companion object {
        const val MAXIMUM_REDIRECTS = 4
        const val BUFFER_SIZE = 16 * 1024
        const val TOO_MANY_REQUESTS = 429
        val REDIRECTS = setOf(301, 302, 303, 307, 308)
    }
}
