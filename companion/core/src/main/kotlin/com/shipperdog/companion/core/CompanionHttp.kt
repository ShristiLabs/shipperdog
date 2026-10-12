package com.shipperdog.companion.core

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The transport seam of the companion core (issue #73, S15-02): the
 * sdk/android telemetry core's `HttpTransport` doctrine applied to the
 * companion's own verbs — authenticated GET/POST of JSON plus the
 * binary fetch an APK download is. Injectable so the JVM test suite
 * proves the catalog/ledger/refresh logic against a fake; the
 * production implementation is [java.net.HttpURLConnection] — JDK API
 * Android ships, the zero-dependency doctrine again (no OkHttp, no
 * client framework). NOT a reuse of the telemetry module's seam: that
 * one is module-internal and POST-only, and coupling the installer to
 * the beacon would weld two different lifecycles together — see
 * docs/companion-spike.md's substrate decisions.
 */
internal fun interface CompanionHttp {

    /** Executes one request; answers status + body (null when none). Throws on transport failure. */
    fun exchange(request: Request): Response

    /** One API request: method, absolute URL, optional bearer, optional JSON body. */
    data class Request(
        val method: String,
        val url: String,
        val bearerToken: String? = null,
        val jsonBody: String? = null,
    )

    /** One API answer: the HTTP status and the body bytes (empty when none). */
    data class Response(val status: Int, val body: ByteArray) {
        fun bodyText(): String = String(body, Charsets.UTF_8)
    }
}

/** A non-2xx answer the callers translate (401 -> refresh, 404 -> the opaque problem, ...). */
internal class HttpError(val response: CompanionHttp.Response) :
    RuntimeException("HTTP " + response.status)

/** The production transport: HttpURLConnection with companion-sized timeouts. */
internal class UrlConnectionHttp : CompanionHttp {

    override fun exchange(request: CompanionHttp.Request): CompanionHttp.Response {
        val connection = URL(request.url).openConnection() as HttpURLConnection
        connection.requestMethod = request.method
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = true
        if (request.bearerToken != null) {
            connection.setRequestProperty("Authorization", "Bearer " + request.bearerToken)
        }
        if (request.jsonBody != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(request.jsonBody.toByteArray(Charsets.UTF_8)) }
        }
        try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { it.readBytes() } ?: ByteArray(0)
            return CompanionHttp.Response(status, body)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val CONNECT_TIMEOUT_MS: Int = 10_000
        const val READ_TIMEOUT_MS: Int = 60_000
    }
}

/**
 * Downloads one issued URL's bytes to [target] (the APK fetch). The
 * issuance URL is the binary's only egress — a short-lived signed URL
 * the portal's own download flow uses too (S8-04: no portal code sits
 * on the fetch path; neither does companion policy — the URL IS the
 * authority). Returns the bytes written; throws [IOException] on any
 * transport failure or non-2xx answer (the caller keeps nothing on a
 * failed download). The read timeout is the APK-sized one, not the
 * JSON seam's.
 */
fun downloadTo(url: String, target: File, progress: (Long, Long?) -> Unit = { _, _ -> }): Long {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.connectTimeout = UrlConnectionHttp.CONNECT_TIMEOUT_MS
    connection.readTimeout = APK_READ_TIMEOUT_MS
    connection.instanceFollowRedirects = true
    try {
        val status = connection.responseCode
        if (status !in 200..299) {
            throw IOException("download answered HTTP $status")
        }
        val total = connection.contentLengthLong.let { if (it > 0) it else null }
        target.parentFile?.mkdirs()
        var written: Long = 0
        connection.inputStream.use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) {
                        break
                    }
                    output.write(buffer, 0, read)
                    written += read
                    progress(written, total)
                }
            }
        }
        return written
    } finally {
        connection.disconnect()
    }
}

/** The APK fetch's read budget: the binary is the big read, not the JSON. */
internal const val APK_READ_TIMEOUT_MS: Int = 120_000
