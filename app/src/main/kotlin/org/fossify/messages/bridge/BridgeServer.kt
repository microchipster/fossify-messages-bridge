/*
 * SPDX-FileCopyrightText: 2026 microchipster
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.fossify.messages.bridge

import android.content.Context
import android.os.Looper
import android.util.Log
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Minimal hand-rolled HTTP/1.1 server exposing the agent API (see
 * `docs/AGENT_API.md`) on top of the telephony provider.
 *
 * No external HTTP dependency is used: the server accepts connections on a
 * [ServerSocket] and handles each request on a cached thread pool. Every route
 * except `/v1/health` requires `Authorization: Bearer <token>`.
 */
// One cohesive HTTP server: routing/codec/lifecycle together; no connection failure may escape its threads.
@Suppress("TooManyFunctions", "TooGenericExceptionCaught")
class BridgeServer(
    private val context: Context,
    private val token: String,
    private val port: Int,
) {
    companion object {
        private const val TAG = "BridgeServer"
        private const val MAX_REQUEST_LINE = 8192
        private const val MAX_BODY_BYTES = 12 * 1024 * 1024L
        private const val SOCKET_TIMEOUT_MS = 30_000
        private const val REQUEST_LINE_MIN_PARTS = 2
        private const val EVENTS_TIMEOUT_DEFAULT_MS = 25_000L
        private const val EVENTS_TIMEOUT_MAX_MS = 30_000L

        private val THREAD_MESSAGES_PATH = Regex("^/v1/threads/(\\d+)/messages$")
        private val THREAD_PATH = Regex("^/v1/threads/(\\d+)$")
        private val MESSAGE_PATH = Regex("^/v1/messages/(.+)$")
        private val MMS_PART_PATH = Regex("^/v1/mms/(\\d+)/parts/(\\d+)$")
    }

    private val json = Json {
        encodeDefaults = false
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    private val pool = Executors.newCachedThreadPool()
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var running = false

    fun start() {
        if (running) {
            return
        }
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(port))
        serverSocket = socket
        running = true
        Thread({ acceptLoop(socket) }, "BridgeServer-accept").start()
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        pool.shutdownNow()
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running) {
            val client = try {
                socket.accept()
            } catch (e: Exception) {
                if (running) {
                    Log.e(TAG, "accept failed", e)
                }
                null
            } ?: continue

            try {
                pool.execute { handle(client) }
            } catch (_: RejectedExecutionException) {
                try {
                    client.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun handle(client: Socket) {
        try {
            client.use { socket ->
                socket.soTimeout = SOCKET_TIMEOUT_MS
                try {
                    Looper.prepare()
                } catch (_: Exception) {
                    // already prepared on this pooled thread
                }

                val request = try {
                    readRequest(socket)
                } catch (e: HttpError) {
                    writeResponse(socket, errorResponse(e.status, e.code, e.message))
                    return
                } catch (_: EOFException) {
                    return
                }

                val response = route(request)
                writeResponse(socket, response)
            }
        } catch (e: Exception) {
            Log.e(TAG, "connection failed", e)
        }
    }

    private fun route(request: HttpRequest): HttpResponse {
        return try {
            if (request.method == "OPTIONS") {
                // CORS-style preflight: no auth needed, no body.
                return HttpResponse(BridgeHttpStatus.NO_CONTENT, "text/plain", ByteArray(0))
            }
            if (!isAuthorized(request)) {
                return errorResponse(BridgeHttpStatus.UNAUTHORIZED, "unauthorized", "missing or invalid bearer token")
            }
            when (request.method) {
                "GET" -> handleGet(request)
                "POST" -> handlePost(request)
                else -> throw notFound(request)
            }
        } catch (e: HttpError) {
            errorResponse(e.status, e.code, e.message)
        } catch (e: Exception) {
            Log.e(TAG, "request ${request.method} ${request.path} failed", e)
            errorResponse(BridgeHttpStatus.INTERNAL_ERROR, "agent_degraded", e.message ?: "internal error")
        }
    }

    private fun handleGet(request: HttpRequest): HttpResponse {
        return when (request.path) {
            "/v1/health" -> jsonResponse(BridgeHttpStatus.OK, BridgeQueries.health(context))
            "/v1/subscriptions" -> jsonResponse(BridgeHttpStatus.OK, BridgeQueries.subscriptions(context))
            "/v1/threads" -> jsonResponse(BridgeHttpStatus.OK, BridgeQueries.threads(context))
            "/v1/events" -> handleEvents(request)
            "/v1/contacts" ->
                errorResponse(BridgeHttpStatus.NOT_IMPLEMENTED, "unsupported", "contact lookup is not implemented yet")

            else -> handleGetByPath(request) ?: throw notFound(request)
        }
    }

    private fun handlePost(request: HttpRequest): HttpResponse {
        return when (request.path) {
            "/v1/read" -> handleRead(request)
            "/v1/send" -> handleSend(request)
            else -> throw notFound(request)
        }
    }

    private fun handleGetByPath(request: HttpRequest): HttpResponse? {
        val path = request.path
        return THREAD_MESSAGES_PATH.matchEntire(path)?.let { handleThreadMessages(it.groupValues[1].toLong(), request) }
            ?: MESSAGE_PATH.matchEntire(path)?.let { handleMessage(it.groupValues[1]) }
            ?: MMS_PART_PATH.matchEntire(path)?.let { handleMmsPartPath(it) }
            ?: THREAD_PATH.matchEntire(path)?.let { handleThread(it.groupValues[1].toLong()) }
    }

    private fun handleMmsPartPath(match: MatchResult): HttpResponse {
        return handlePart(match.groupValues[1].toLong(), match.groupValues[2].toLong())
    }

    private fun handleThread(threadId: Long): HttpResponse {
        val thread = BridgeQueries.thread(context, threadId)
            ?: throw HttpError(BridgeHttpStatus.NOT_FOUND, "not_found", "thread $threadId not found")
        return jsonResponse(BridgeHttpStatus.OK, thread)
    }

    private fun handleThreadMessages(threadId: Long, request: HttpRequest): HttpResponse {
        val limit = request.query["limit"]?.toIntOrNull() ?: BridgeQueries.DEFAULT_HISTORY_LIMIT
        val beforeMs = request.query["before_ms"]?.toLongOrNull() ?: 0
        val afterMs = request.query["after_ms"]?.toLongOrNull() ?: 0
        return jsonResponse(
            BridgeHttpStatus.OK,
            BridgeQueries.threadMessages(context, threadId, beforeMs, afterMs, limit),
        )
    }

    private fun handleMessage(messageId: String): HttpResponse {
        val message = BridgeQueries.message(context, messageId)
            ?: throw HttpError(BridgeHttpStatus.NOT_FOUND, "not_found", "message $messageId not found")
        return jsonResponse(BridgeHttpStatus.OK, message)
    }

    private fun handlePart(mmsId: Long, partId: Long): HttpResponse {
        val (bytes, contentType) = BridgeQueries.partBytes(context, mmsId, partId)
            ?: throw HttpError(BridgeHttpStatus.NOT_FOUND, "not_found", "part $partId of mms $mmsId not found")
        return HttpResponse(BridgeHttpStatus.OK, contentType.ifEmpty { "application/octet-stream" }, bytes)
    }

    private fun handleRead(request: HttpRequest): HttpResponse {
        val body = decodeRequest<BridgeReadRequest>(request.body)
        var updated = 0
        if (body.threadId != 0L) {
            updated = BridgeQueries.markThreadRead(context, body.threadId, body.upToMs)
        } else if (body.messageIds.isNotEmpty()) {
            updated = BridgeQueries.markMessagesRead(context, body.messageIds)
        }
        return jsonResponse(BridgeHttpStatus.OK, BridgeReadResponse(updated = updated, supported = true))
    }

    private fun handleSend(request: HttpRequest): HttpResponse {
        val body = decodeRequest<BridgeSendRequest>(request.body)
        return try {
            jsonResponse(BridgeHttpStatus.OK, BridgeSender.send(context, body))
        } catch (e: BridgeSendException) {
            errorResponse(e.status, e.code, e.message)
        }
    }

    private fun handleEvents(request: HttpRequest): HttpResponse {
        val since = request.query["since"]?.toLongOrNull() ?: 0
        var timeoutMs = request.query["timeout_ms"]?.toLongOrNull() ?: EVENTS_TIMEOUT_DEFAULT_MS
        if (timeoutMs <= 0 || timeoutMs > EVENTS_TIMEOUT_MAX_MS) {
            timeoutMs = EVENTS_TIMEOUT_DEFAULT_MS
        }
        return jsonResponse(BridgeHttpStatus.OK, BridgeEvents.wait(since, timeoutMs))
    }

    private fun notFound(request: HttpRequest): HttpError {
        return HttpError(BridgeHttpStatus.NOT_FOUND, "not_found", "unknown route ${request.method} ${request.path}")
    }

    private fun isAuthorized(request: HttpRequest): Boolean {
        if (request.path == "/v1/health" || token.isEmpty()) {
            return true
        }
        val header = request.headers["authorization"].orEmpty()
        val presented = if (header.startsWith("Bearer ")) header.removePrefix("Bearer ") else ""
        if (presented.isEmpty()) {
            return false
        }
        val presentedBytes = presented.toByteArray(StandardCharsets.UTF_8)
        val tokenBytes = token.toByteArray(StandardCharsets.UTF_8)
        return MessageDigest.isEqual(presentedBytes, tokenBytes)
    }

    private inline fun <reified T> decodeRequest(body: ByteArray): T {
        if (body.isEmpty()) {
            throw HttpError(BridgeHttpStatus.BAD_REQUEST, "not_found", "missing JSON body")
        }
        return try {
            json.decodeFromString(serializer<T>(), String(body, StandardCharsets.UTF_8))
        } catch (e: Exception) {
            throw HttpError(BridgeHttpStatus.BAD_REQUEST, "not_found", "invalid JSON body: ${e.message}", e)
        }
    }

    private inline fun <reified T> jsonResponse(status: Int, value: T): HttpResponse {
        val body = json.encodeToString(serializer<T>(), value).toByteArray(StandardCharsets.UTF_8)
        return HttpResponse(status, "application/json", body)
    }

    private fun errorResponse(status: Int, code: String, message: String): HttpResponse {
        val body = json.encodeToString(
            serializer<BridgeErrorBody>(),
            BridgeErrorBody(error = code, message = message),
        ).toByteArray(StandardCharsets.UTF_8)
        return HttpResponse(status, "application/json", body)
    }

    private fun readRequest(socket: Socket): HttpRequest {
        val input = BufferedInputStream(socket.getInputStream())
        val requestLine = readLine(input) ?: throw EOFException()
        val parts = requestLine.split(" ")
        if (parts.size < REQUEST_LINE_MIN_PARTS) {
            throw HttpError(BridgeHttpStatus.BAD_REQUEST, "not_found", "malformed request line")
        }
        val method = parts[0].uppercase()
        val (path, query) = parseTarget(parts[1])
        val headers = readHeaders(input)
        val contentLength = headers["content-length"]?.toLongOrNull() ?: 0
        return HttpRequest(method, path, query, headers, readBody(input, contentLength))
    }

    /** Splits a request target into its decoded path and its query parameters. */
    private fun parseTarget(target: String): Pair<String, Map<String, String>> {
        val queryIndex = target.indexOf('?')
        val rawPath = if (queryIndex >= 0) target.substring(0, queryIndex) else target
        val query = parseQuery(if (queryIndex >= 0) target.substring(queryIndex + 1) else "")
        return URLDecoder.decode(rawPath, StandardCharsets.UTF_8.name()) to query
    }

    private fun readHeaders(input: InputStream): Map<String, String> {
        val headers = HashMap<String, String>()
        var line = readLine(input)
        while (!line.isNullOrEmpty()) {
            val separator = line.indexOf(':')
            if (separator > 0) {
                val name = line.substring(0, separator).trim().lowercase()
                headers[name] = line.substring(separator + 1).trim()
            }
            line = readLine(input)
        }
        return headers
    }

    private fun readLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val byte = input.read()
            if (byte == -1) {
                return if (buffer.size() == 0) null else buffer.toString(StandardCharsets.ISO_8859_1.name())
            }
            if (byte == '\n'.code) {
                break
            }
            if (byte != '\r'.code) {
                buffer.write(byte)
            }
            if (buffer.size() > MAX_REQUEST_LINE) {
                throw HttpError(BridgeHttpStatus.BAD_REQUEST, "not_found", "request line too long")
            }
        }
        return buffer.toString(StandardCharsets.ISO_8859_1.name())
    }

    private fun readBody(input: InputStream, length: Long): ByteArray {
        if (length > MAX_BODY_BYTES) {
            throw HttpError(BridgeHttpStatus.TOO_LARGE, "too_large", "request body is too large")
        }
        if (length <= 0) {
            return ByteArray(0)
        }
        val body = ByteArray(length.toInt())
        var offset = 0
        while (offset < body.size) {
            val read = input.read(body, offset, body.size - offset)
            if (read == -1) {
                throw HttpError(BridgeHttpStatus.BAD_REQUEST, "not_found", "unexpected end of request body")
            }
            offset += read
        }
        return body
    }

    private fun parseQuery(raw: String): Map<String, String> {
        if (raw.isEmpty()) {
            return emptyMap()
        }
        val result = HashMap<String, String>()
        raw.split('&').forEach { pair ->
            if (pair.isEmpty()) {
                return@forEach
            }
            val separator = pair.indexOf('=')
            val key = if (separator >= 0) pair.substring(0, separator) else pair
            val value = if (separator >= 0) pair.substring(separator + 1) else ""
            result[URLDecoder.decode(key, StandardCharsets.UTF_8.name())] =
                URLDecoder.decode(value, StandardCharsets.UTF_8.name())
        }
        return result
    }

    private fun writeResponse(socket: Socket, response: HttpResponse) {
        val out = BufferedOutputStream(socket.getOutputStream())
        val header = buildString {
            append("HTTP/1.1 ${response.status} ${BridgeHttpStatus.text(response.status)}\r\n")
            append("Content-Type: ${response.contentType}\r\n")
            if (response.status != BridgeHttpStatus.NO_CONTENT) {
                append("Content-Length: ${response.body.size}\r\n")
            }
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        out.write(header.toByteArray(StandardCharsets.ISO_8859_1))
        if (response.status != BridgeHttpStatus.NO_CONTENT && response.body.isNotEmpty()) {
            out.write(response.body)
        }
        out.flush()
    }

    private class HttpRequest(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val body: ByteArray,
    )

    private class HttpResponse(val status: Int, val contentType: String, val body: ByteArray)

    private class HttpError(
        val status: Int,
        val code: String,
        override val message: String,
        cause: Throwable? = null,
    ) : Exception(message, cause)

    @Serializable
    private data class BridgeErrorBody(val error: String, val message: String)

    @Serializable
    private data class BridgeReadRequest(
        @SerialName("thread_id") val threadId: Long = 0,
        @SerialName("up_to_ms") val upToMs: Long = 0,
        @SerialName("message_ids") val messageIds: List<String> = emptyList(),
    )

    @Serializable
    private data class BridgeReadResponse(val updated: Int, val supported: Boolean)
}
