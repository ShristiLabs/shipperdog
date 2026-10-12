package com.shipperdog.telemetry

import java.net.HttpURLConnection
import java.net.URL

/**
 * The transport seam of the telemetry SDK (issue #72, S15-01): one
 * POST of one batch body. Injectable so the JVM test suite proves
 * the flush policy against a fake; the production implementation is
 * [java.net.HttpURLConnection] — JDK API Android ships, the
 * zero-dependency doctrine again (no OkHttp, no client framework,
 * the < 200 KB budget).
 */
internal fun interface HttpTransport {

    /**
     * POSTs the body as JSON with the telemetry token's bearer
     * header. Answers the HTTP status, or throws on transport
     * failure (no connection, timeout) — the flush loop's retry
     * signal.
     */
    fun post(url: String, bearerToken: String, jsonBody: String): Int
}

/** The production transport: HttpURLConnection with telemetry-sized timeouts. */
internal class UrlConnectionTransport : HttpTransport {

    override fun post(url: String, bearerToken: String, jsonBody: String): Int {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Authorization", "Bearer $bearerToken")
        try {
            connection.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
            return connection.responseCode
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val CONNECT_TIMEOUT_MS: Int = 10_000
        const val READ_TIMEOUT_MS: Int = 30_000
    }
}
