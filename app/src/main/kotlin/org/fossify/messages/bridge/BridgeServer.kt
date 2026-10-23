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
class BridgeServer(
    private val context: Context,
    private val token: String,
    private val port: Int,
) {
    companion object {
        private const val TAG = "BridgeServer"
        private const val MAX_REQUEST_LINE = 8192
        private const val MAX_BODY_BYTES = 12 * 1024 * 1024L

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
                socket.soTimeout = 30_000
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
            if (!isAuthorized(request)) {
                return errorResponse(401, "unauthorized", "missing or invalid bearer token")
            }
            when {
                request.method == "GET" && request.path == "/v1/health" -> jsonResponse(200, BridgeQueries.health(context))
                request.method == "GET" && request.path == "/v1/subscriptions" -> jsonResponse(200, BridgeQueries.subscriptions(context))
                request.method == "GET" && request.path == "/v1/threads" -> jsonResponse(200, BridgeQueries.threads(context))
                request.method == "GET" && request.path == "/v1/events" -> handleEvents(request)
                request.method == "GET" && request.path == "/v1/contacts" ->
                    errorResponse(501, "unsupported", "contact lookup is not implemented yet")

                request.method == "POST" && request.path == "/v1/read" -> handleRead(request)
                request.method == "POST" && request.path == "/v1/send" -> handleSend(request)
                request.method == "GET" -> handleGetByPath(request) ?: throw notFound(request)
                else -> throw notFound(request)
            }
        } catch (e: HttpError) {
            errorResponse(e.status, e.code, e.message)
        } catch (e: Exception) {
            Log.e(TAG, "request ${request.method} ${request.path} failed", e)
            errorResponse(500, "agent_degraded", e.message ?: "internal error")
        }
    }

    private fun handleGetByPath(request: HttpRequest): HttpResponse? {
        THREAD_MESSAGES_PATH.matchEntire(request.path)?.let {
            return handleThreadMessages(it.groupValues[1].toLong(), request)
        }
        MESSAGE_PATH.matchEntire(request.path)?.let {
            return handleMessage(it.groupValues[1])
        }
        MMS_PART_PATH.matchEntire(request.path)?.let {
            return handlePart(it.groupValues[1].toLong(), it.groupValues[2].toLong())
        }
        THREAD_PATH.matchEntire(request.path)?.let {
            return handleThread(it.groupValues[1].toLong())
        }
        return null
    }

    private fun handleThread(threadId: Long): HttpResponse {
        val recipients = BridgeQueries.threadRecipients(context, threadId)
            ?: throw HttpError(404, "not_found", "thread $threadId not found")
        return jsonResponse(
            200,
            BridgeThread(
                id = threadId,
                subId = -1,
                dateMs = 0,
                messageCount = 0,
                recipients = recipients,
                read = false,
                hasAttachment = false,
            ),
        )
    }

    private fun handleThreadMessages(threadId: Long, request: HttpRequest): HttpResponse {
        val limit = request.query["limit"]?.toIntOrNull() ?: BridgeQueries.DEFAULT_HISTORY_LIMIT
        val beforeMs = request.query["before_ms"]?.toLongOrNull() ?: 0
        val afterMs = request.query["after_ms"]?.toLongOrNull() ?: 0
        return jsonResponse(200, BridgeQueries.threadMessages(context, threadId, beforeMs, afterMs, limit))
    }

    private fun handleMessage(messageId: String): HttpResponse {
        val message = BridgeQueries.message(context, messageId)
            ?: throw HttpError(404, "not_found", "message $messageId not found")
        return jsonResponse(200, message)
    }

    private fun handlePart(mmsId: Long, partId: Long): HttpResponse {
        val (bytes, contentType) = BridgeQueries.partBytes(context, mmsId, partId)
            ?: throw HttpError(404, "not_found", "part $partId of mms $mmsId not found")
        return HttpResponse(200, contentType.ifEmpty { "application/octet-stream" }, bytes)
    }

    private fun handleRead(request: HttpRequest): HttpResponse {
        val body = decodeRequest<BridgeReadRequest>(request.body)
        var updated = 0
        if (body.threadId != 0L) {
            updated = BridgeQueries.markThreadRead(context, body.threadId, body.upToMs)
        } else if (body.messageIds.isNotEmpty()) {
            updated = BridgeQueries.markMessagesRead(context, body.messageIds)
        }
        return jsonResponse(200, BridgeReadResponse(updated = updated, supported = true))
    }

    private fun handleSend(request: HttpRequest): HttpResponse {
        val body = decodeRequest<BridgeSendRequest>(request.body)
        return try {
            jsonResponse(200, BridgeSender.send(context, body))
        } catch (e: BridgeSendException) {
            errorResponse(e.status, e.code, e.message)
        }
    }

    private fun handleEvents(request: HttpRequest): HttpResponse {
        val since = request.query["since"]?.toLongOrNull() ?: 0
        var timeoutMs = request.query["timeout_ms"]?.toLongOrNull() ?: 25000
        if (timeoutMs <= 0 || timeoutMs > 30000) {
            timeoutMs = 25000
        }
        return jsonResponse(200, BridgeEvents.wait(since, timeoutMs))
    }

    private fun notFound(request: HttpRequest) = HttpError(404, "not_found", "unknown route ${request.method} ${request.path}")

    private fun isAuthorized(request: HttpRequest): Boolean {
        if (request.path == "/v1/health" || token.isEmpty()) {
            return true
        }
        val header = request.headers["authorization"].orEmpty()
        val presented = if (header.startsWith("Bearer ")) header.removePrefix("Bearer ") else ""
        if (presented.isEmpty()) {
            return false
        }
        return MessageDigest.isEqual(presented.toByteArray(StandardCharsets.UTF_8), token.toByteArray(StandardCharsets.UTF_8))
    }

    private inline fun <reified T> decodeRequest(body: ByteArray): T {
        if (body.isEmpty()) {
            throw HttpError(400, "not_found", "missing JSON body")
        }
        return try {
            json.decodeFromString(serializer<T>(), String(body, StandardCharsets.UTF_8))
        } catch (e: Exception) {
            throw HttpError(400, "not_found", "invalid JSON body: ${e.message}")
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
        if (parts.size < 2) {
            throw HttpError(400, "not_found", "malformed request line")
        }
        val method = parts[0].uppercase()
        val target = parts[1]
        val queryIndex = target.indexOf('?')
        val rawPath = if (queryIndex >= 0) target.substring(0, queryIndex) else target
        val query = parseQuery(if (queryIndex >= 0) target.substring(queryIndex + 1) else "")
        val path = URLDecoder.decode(rawPath, StandardCharsets.UTF_8.name())

        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) {
                break
            }
            val separator = line.indexOf(':')
            if (separator > 0) {
                headers[line.substring(0, separator).trim().lowercase()] = line.substring(separator + 1).trim()
            }
        }

        val contentLength = headers["content-length"]?.toLongOrNull() ?: 0
        if (contentLength > MAX_BODY_BYTES) {
            throw HttpError(413, "too_large", "request body is too large")
        }
        return HttpRequest(method, path, query, headers, readBody(input, contentLength))
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
                throw HttpError(400, "not_found", "request line too long")
            }
        }
        return buffer.toString(StandardCharsets.ISO_8859_1.name())
    }

    private fun readBody(input: InputStream, length: Long): ByteArray {
        if (length <= 0) {
            return ByteArray(0)
        }
        val body = ByteArray(length.toInt())
        var offset = 0
        while (offset < body.size) {
            val read = input.read(body, offset, body.size - offset)
            if (read == -1) {
                throw HttpError(400, "not_found", "unexpected end of request body")
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
            append("HTTP/1.1 ${response.status} ${statusText(response.status)}\r\n")
            append("Content-Type: ${response.contentType}\r\n")
            append("Content-Length: ${response.body.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        out.write(header.toByteArray(StandardCharsets.ISO_8859_1))
        out.write(response.body)
        out.flush()
    }

    private fun statusText(status: Int): String = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        413 -> "Payload Too Large"
        500 -> "Internal Server Error"
        501 -> "Not Implemented"
        502 -> "Bad Gateway"
        503 -> "Service Unavailable"
        else -> "Error"
    }

    private class HttpRequest(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val body: ByteArray,
    )

    private class HttpResponse(val status: Int, val contentType: String, val body: ByteArray)

    private class HttpError(val status: Int, val code: String, override val message: String) : Exception(message)

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
