package net.kuecker.lodestar.services

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

/** An [OnlineStore] in memory. */
class MemoryOnlineStore : OnlineStore {
    private val values = HashMap<String, Any>()

    override fun getBoolean(key: String): Boolean = values[key] as? Boolean ?: false

    override fun putBoolean(key: String, value: Boolean) {
        values[key] = value
    }

    override fun getLong(key: String): Long? = values[key] as? Long

    override fun putLong(key: String, value: Long) {
        values[key] = value
    }
}

/** What a request to the [LoopbackServer] looked like. */
class RecordedRequest(val path: String, val headers: Map<String, String>)

/** A plain-HTTP server on the loopback address, answering every request with [handler]. */
class LoopbackServer(private val handler: (HttpExchange) -> Unit) : AutoCloseable {
    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    val requests = CopyOnWriteArrayList<RecordedRequest>()

    init {
        server.createContext("/") { exchange ->
            requests.add(
                RecordedRequest(
                    exchange.requestURI.toString(),
                    exchange.requestHeaders.entries.associate { it.key.lowercase() to it.value.joinToString(",") },
                ),
            )
            try {
                handler(exchange)
            } finally {
                exchange.close()
            }
        }
        server.start()
    }

    val url: String get() = "http://127.0.0.1:${server.address.port}/data"

    override fun close() = server.stop(0)

    companion object {
        /** Answers with [status], [body] and the given response headers. */
        fun respond(exchange: HttpExchange, status: Int, body: ByteArray = ByteArray(0), headers: Map<String, String> = emptyMap()) {
            for ((name, value) in headers) exchange.responseHeaders.add(name, value)
            if (status == 304) {
                exchange.sendResponseHeaders(status, -1)
            } else {
                exchange.sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
                if (body.isNotEmpty()) exchange.responseBody.write(body)
            }
        }
    }
}

/** A fresh temporary directory for one test. */
fun temporaryDirectory(): java.io.File = Files.createTempDirectory("lodestar-online").toFile().also { it.deleteOnExit() }

/** An ISS element set as CelesTrak delivers it (OMM JSON, 2026-10-18). */
val issOmmJson = """
{"OBJECT_NAME":"ISS (ZARYA)","OBJECT_ID":"1998-067A","EPOCH":"2026-10-18T11:12:47.041632","MEAN_MOTION":15.48923373,
"ECCENTRICITY":0.00067835,"INCLINATION":51.6327,"RA_OF_ASC_NODE":50.0298,"ARG_OF_PERICENTER":279.2532,
"MEAN_ANOMALY":80.7689,"EPHEMERIS_TYPE":0,"CLASSIFICATION_TYPE":"U","NORAD_CAT_ID":25544,"ELEMENT_SET_NO":999,
"REV_AT_EPOCH":59086,"BSTAR":0.00014361462,"MEAN_MOTION_DOT":7.452e-5,"MEAN_MOTION_DDOT":0}
""".trimIndent().replace("\n", "")

/** Tiangong's core module, same date. */
val tianheOmmJson = """
{"OBJECT_NAME":"CSS (TIANHE)","OBJECT_ID":"2021-035A","EPOCH":"2026-10-18T10:38:26.492352","MEAN_MOTION":15.60900987,
"ECCENTRICITY":0.00013567,"INCLINATION":41.4692,"RA_OF_ASC_NODE":293.3297,"ARG_OF_PERICENTER":173.9168,
"MEAN_ANOMALY":186.1688,"EPHEMERIS_TYPE":0,"CLASSIFICATION_TYPE":"U","NORAD_CAT_ID":48274,"ELEMENT_SET_NO":999,
"REV_AT_EPOCH":31241,"BSTAR":0.0002706558,"MEAN_MOTION_DOT":0.00023032,"MEAN_MOTION_DDOT":0}
""".trimIndent().replace("\n", "")

/** A module that shares the ISS orbit (the stations group lists all of them). */
val poiskOmmJson = issOmmJson.replace("ISS (ZARYA)", "POISK").replace("25544", "36086").replace("1998-067A", "2009-060A")

/** A JSON array of the given OMM records. */
fun ommArray(vararg records: String): ByteArray = records.joinToString(",", "[", "]").toByteArray()
