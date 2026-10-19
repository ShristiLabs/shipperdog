package com.shipperdog.ota

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The transport seam of the OTA module (issue #174, S16-12): the
 * telemetry SDK's own injectable-seam doctrine. Three verbs only —
 * the authenticated GET the check presents, the range-capable
 * streaming GET the downloader stages bytes with, and the batch POST
 * the lifecycle reporter flushes through. The production
 * implementation is [java.net.HttpURLConnection] (JDK API Android
 * ships — the zero-dependency doctrine again); the JVM test suite
 * proves every policy against fakes.
 */
internal interface OtaHttp {

    /** One authenticated GET: answers the status with the body, or throws on transport failure. */
    fun get(url: String, bearerToken: String): Response

    /**
     * Streams one GET into [target], optionally from a byte offset
     * (the resume leg's `Range: bytes=<from>-`). The STATUS decides
     * the file mode — the graceful-degrade contract: a 206 answer
     * APPENDS the requested tail to what is staged; a plain 200
     * answer means the endpoint ignored the range and carries the
     * WHOLE object, so the file is restaged from zero; any other
     * answer leaves the file untouched. Answers the status.
     */
    fun download(url: String, rangeFromBytes: Long?, target: File): Int

    /** POSTs the JSON body with the bearer header; answers the status or throws on transport failure. */
    fun post(url: String, bearerToken: String, jsonBody: String): Int

    /** One GET answer: the status code and, on 2xx, the decoded body. */
    data class Response(val status: Int, val body: String?)
}

/** The production transport: HttpURLConnection with update-sized timeouts. */
internal class UrlConnectionOtaHttp : OtaHttp {

    override fun get(url: String, bearerToken: String): OtaHttp.Response {
        val connection = open(url)
        connection.requestMethod = "GET"
        connection.setRequestProperty("Authorization", "Bearer $bearerToken")
        try {
            val status = connection.responseCode
            val body = if (status in 200..299) connection.inputStream.readBytes().toString(Charsets.UTF_8) else null
            return OtaHttp.Response(status, body)
        } finally {
            connection.disconnect()
        }
    }

    override fun download(url: String, rangeFromBytes: Long?, target: File): Int {
        val connection = open(url)
        connection.requestMethod = "GET"
        if (rangeFromBytes != null && rangeFromBytes > 0) {
            connection.setRequestProperty("Range", "bytes=$rangeFromBytes-")
        }
        try {
            val status = connection.responseCode
            if (status in 200..299) {
                target.parentFile?.mkdirs()
                val append = status == 206
                FileOutputStream(target, append).use { sink ->
                    connection.inputStream.use { stream ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        while (true) {
                            val read = stream.read(buffer)
                            if (read < 0) {
                                break
                            }
                            sink.write(buffer, 0, read)
                        }
                    }
                }
            }
            return status
        } finally {
            connection.disconnect()
        }
    }

    override fun post(url: String, bearerToken: String, jsonBody: String): Int {
        val connection = open(url)
        connection.requestMethod = "POST"
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

    private fun open(url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = true
        return connection
    }

    companion object {
        const val CONNECT_TIMEOUT_MS: Int = 10_000

        /** Bundle-sized, not telemetry-sized: a staged download legitimately runs long. */
        const val READ_TIMEOUT_MS: Int = 120_000
        const val BUFFER_BYTES: Int = 16_384
    }
}
