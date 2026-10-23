/*
 * SPDX-FileCopyrightText: 2026 microchipster
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.fossify.messages.bridge

import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL

/**
 * Minimal JSON-over-HTTP transport of the pairing protocol.
 *
 * Only [HttpURLConnection] is used (no HTTP dependency is added) and every URL
 * must be HTTPS; plain HTTP is accepted for loopback addresses only, so the
 * flow can be exercised against a local bridge during development.
 */
internal object PairingHttp {
    const val CONNECT_TIMEOUT_MS = 15_000
    const val REQUEST_TIMEOUT_MS = 30_000
    const val HTTP_STATUS_UNAUTHORIZED = 401
    const val HTTP_STATUS_FORBIDDEN = 403

    private const val TAG = "PairingHttp"
    private const val HTTP_OK_MIN = 200
    private const val HTTP_OK_MAX = 299

    private val json = Json {
        encodeDefaults = false
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    @Serializable
    private data class ErrorBody(val error: String = "", val message: String = "")

    /**
     * Performs one JSON request. Throws [PairingException] on non-2xx
     * responses, keeping the error code and message of the API. Blocking.
     */
    fun requestJson(
        url: String,
        method: String,
        token: String = "",
        body: String? = null,
        readTimeoutMs: Int = REQUEST_TIMEOUT_MS,
    ): String {
        requireSecureUrl(url)
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = readTimeoutMs
            connection.instanceFollowRedirects = method == "GET"
            connection.setRequestProperty("Accept", "application/json")
            if (token.isNotEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer $token")
            }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }

            val status = connection.responseCode
            val stream = if (status in HTTP_OK_MIN..HTTP_OK_MAX) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in HTTP_OK_MIN..HTTP_OK_MAX) {
                throw parseHttpError(status, text)
            }
            return text
        } finally {
            connection.disconnect()
        }
    }

    /** Combines a base URL and an absolute path into a full URL. */
    fun endpoint(baseUrl: String, path: String): String {
        return baseUrl.trimEnd('/') + path
    }

    /** Trims a URL, adding `https://` when no scheme was typed; null when unusable. */
    fun normalizeUrl(raw: String): String? {
        var value = raw.trim()
        if (value.isEmpty()) {
            return null
        }
        if (!value.contains("://")) {
            value = "https://$value"
        }

        val parsed = try {
            URL(value)
        } catch (_: MalformedURLException) {
            null
        } ?: return null

        val supportedScheme = parsed.protocol == "https" || parsed.protocol == "http"
        if (!supportedScheme || parsed.host.isNullOrBlank()) {
            return null
        }
        return value.trimEnd('/')
    }

    private fun requireSecureUrl(url: String) {
        val parsed = try {
            URL(url)
        } catch (_: MalformedURLException) {
            throw PairingException("bad_request", 0, "invalid URL: $url")
        }
        if (parsed.protocol == "https") {
            return
        }
        val host = parsed.host.orEmpty().lowercase().trim('[', ']')
        val isLoopback = host == "localhost" || host == "127.0.0.1" || host == "::1"
        if (parsed.protocol != "http" || !isLoopback) {
            throw PairingException("unsupported", 0, "refusing to send pairing data over an insecure URL: $url")
        }
    }

    // Error payloads are best-effort: any parse failure falls back to a synthetic HTTP error.
    @Suppress("TooGenericExceptionCaught")
    private fun parseHttpError(status: Int, text: String): PairingException {
        val parsed = try {
            json.decodeFromString(serializer<ErrorBody>(), text)
        } catch (_: Exception) {
            null
        }
        val code = parsed?.error?.takeIf { it.isNotBlank() } ?: "http_$status"
        val message = parsed?.message?.takeIf { it.isNotBlank() } ?: "request failed with HTTP $status"
        Log.w(TAG, "HTTP $status: $code: $message")
        return PairingException(code, status, message)
    }
}
