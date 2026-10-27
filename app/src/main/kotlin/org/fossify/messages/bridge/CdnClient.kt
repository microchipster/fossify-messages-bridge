/*
 * SPDX-FileCopyrightText: 2026 microchipster
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.fossify.messages.bridge

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import org.fossify.messages.extensions.getFileSizeFromUri
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import javax.crypto.CipherOutputStream

/** One encrypted upload, as returned by `POST <cdn_base_url>/f`. */
internal data class CdnUpload(
    val id: String,
    val size: Long,
    val expiresAtMs: Long,
    val keyFragment: String,
) {
    /** The shareable link; the key fragment never leaves the device otherwise. */
    fun link(cdnBaseUrl: String): String = CdnLink.build(cdnBaseUrl, id, keyFragment)
}

/** One encrypted album, as returned by `POST <cdn_base_url>/f/albums`. */
internal data class CdnAlbumUpload(
    val id: String,
    val size: Long,
    val itemCount: Int,
    val expiresAtMs: Long,
    val keyFragment: String,
) {
    /** The shareable album link; it has the shape of a single-file link. */
    fun link(cdnBaseUrl: String): String = CdnLink.build(cdnBaseUrl, id, keyFragment)
}

/** One selected attachment of an album upload, in display (and index) order. */
internal data class CdnAlbumAttachment(
    val uri: Uri,
    val mimeType: String,
    val name: String,
)

/** A file-service failure: a bridge error code or one of the local [CdnClient] codes. */
internal class CdnException(
    val code: String,
    val status: Int = 0,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Upload and download paths of the bridge-hosted file service.
 *
 * The attachment is encrypted with AES-256-GCM while it is streamed to the
 * bridge (nonce first, the tag is appended by [CipherOutputStream] on close),
 * so a large file is never loaded into memory. The path is inert unless the
 * user allowed it and the bridge advertised a `cdn_base_url` over a paired
 * device token.
 */
@Suppress("TooManyFunctions")
internal object CdnClient {
    /** Local: no advertised base URL or no paired token. */
    const val CODE_NOT_CONFIGURED = "not_configured"

    /** Local: the app-side upload limit rejected the attachment. */
    const val CODE_TOO_LARGE = "too_large"

    /** Local: the request never reached the file service. */
    const val CODE_NETWORK = "network"

    /** Local: a 201 without a usable id. */
    const val CODE_INVALID_RESPONSE = "invalid_response"

    /** Local: the downloaded ciphertext failed authentication or was corrupt. */
    const val CODE_DECRYPT = "decrypt"

    /** Remote: the file is gone (expired or revoked) or the link is wrong. */
    const val CODE_NOT_FOUND = "not_found"

    /** Bridge: the ciphertext exceeds `cdn.max_file_bytes`. */
    const val ERROR_FILE_TOO_LARGE = "file_too_large"

    /** Bridge: the upload exceeds the per-user storage quota. */
    const val ERROR_QUOTA_EXCEEDED = "quota_exceeded"

    /** Bridge: the paired-device token is not valid anymore. */
    const val ERROR_UNAUTHORIZED = "unauthorized"

    /** Bridge: the album exceeds `cdn.max_album_items`. */
    const val ERROR_ALBUM_TOO_MANY_ITEMS = "album_too_many_items"

    /** Bridge: the album exceeds `cdn.max_album_bytes`. */
    const val ERROR_ALBUM_TOO_LARGE = "album_too_large"

    /** Bridge: the album manifest exceeds its 64 KiB cap. */
    const val ERROR_ALBUM_MANIFEST_TOO_LARGE = "album_manifest_too_large"

    private const val TAG = "CdnClient"
    private const val CONNECT_TIMEOUT_MS = 30_000
    private const val UPLOAD_TIMEOUT_MS = 10 * 60 * 1000
    private const val DOWNLOAD_TIMEOUT_MS = 60 * 1000
    private const val COPY_BUFFER_BYTES = 64 * 1024
    private const val CHUNK_BYTES = 64 * 1024
    private const val HTTP_CREATED = 201
    private const val HTTP_NOT_FOUND = 404
    private const val HTTP_OK_MIN = 200
    private const val HTTP_OK_MAX = 299

    private val json = Json {
        encodeDefaults = false
        explicitNulls = false
        ignoreUnknownKeys = true
    }
    private val secureRandom = SecureRandom()

    @Serializable
    private data class UploadResponse(
        val id: String = "",
        val size: Long = 0,
        @SerialName("expires_at_ms") val expiresAtMs: Long = 0,
    )

    @Serializable
    private data class AlbumUploadResponse(
        val id: String = "",
        val size: Long = 0,
        val items: Int = 0,
        @SerialName("expires_at_ms") val expiresAtMs: Long = 0,
    )

    @Serializable
    private data class ErrorResponse(val error: String = "", val message: String = "")

    /**
     * Encrypts one attachment and uploads it to the bridge file service.
     *
     * Blocking; call it from a background thread. [onProgress] reports the
     * plaintext bytes sealed so far and the known total (or a non-positive
     * value when the size is unknown).
     */
    @Suppress("TooGenericExceptionCaught")
    fun upload(
        context: Context,
        uri: Uri,
        expiryDays: Int,
        maxPlaintextBytes: Long,
        onProgress: ((written: Long, total: Long) -> Unit)? = null,
    ): CdnUpload {
        val store = PairingStore.newInstance(context)
        val baseUrl = store.cdnBaseUrl
        val token = store.token
        requireService(baseUrl, token)

        val total = context.getFileSizeFromUri(uri)
        requireFitsUploadLimit(total, maxPlaintextBytes)
        val url = "${baseUrl.trimEnd('/')}${CdnLink.PATH_PREFIX}?expires=${CdnExpiry.durationSpec(expiryDays)}"
        val key = CdnCrypto.generateKey(secureRandom)
        val connection = openConnection(url, token)
        try {
            val written = openAttachment(context, uri).use {
                writeEncryptedBody(connection.outputStream, it, key, maxPlaintextBytes, total, onProgress)
            }
            return completeUpload(connection, key, written)
        } catch (e: CdnException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "file service upload failed", e)
            throw CdnException(CODE_NETWORK, message = e.message ?: "file service upload failed", cause = e)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Encrypts a multi-selection as one album and uploads it in a single
     * request: every item is sealed under its own HKDF subkey (derived from
     * one fresh fragment key), then the encrypted manifest follows, framed in
     * the `CDNALB01` container. Blocking; items are streamed, never buffered.
     *
     * The item count, the per-item upload limit and the album caps are
     * pre-checked against the bridge defaults; a deployment that lowered them
     * answers with the matching bridge error instead.
     */
    @Suppress("TooGenericExceptionCaught", "ThrowsCount")
    fun uploadAlbum(
        context: Context,
        attachments: List<CdnAlbumAttachment>,
        expiryDays: Int,
        maxPlaintextBytes: Long,
        onProgress: ((written: Long, total: Long) -> Unit)? = null,
    ): CdnAlbumUpload {
        val store = PairingStore.newInstance(context)
        val baseUrl = store.cdnBaseUrl
        val token = store.token
        requireService(baseUrl, token)

        val sizes = attachments.map { context.getFileSizeFromUri(it.uri) }
        val ciphertextLengths = requireAlbumItemsFit(sizes, maxPlaintextBytes)
        val key = CdnCrypto.generateKey(secureRandom)
        val manifest = attachments.mapIndexed { index, item ->
            CdnAlbumItem(name = item.name, type = item.mimeType, size = sizes[index])
        }
        val manifestBody = CdnCrypto.encrypt(CdnCrypto.albumManifestKey(key), CdnAlbum.manifestJson(manifest))
        requireAlbumFits(ciphertextLengths, manifestBody.size)
        val url = "${baseUrl.trimEnd('/')}${CdnLink.PATH_PREFIX}/albums?expires=${CdnExpiry.durationSpec(expiryDays)}"
        val totalPlaintext = sizes.sum()
        val connection = openConnection(url, token)
        try {
            connection.outputStream.use { output ->
                output.write(CdnAlbum.frameHeader(ciphertextLengths, manifestBody.size.toLong()))
                var sealedPlaintext = 0L
                attachments.forEachIndexed { index, attachment ->
                    val written = openAttachment(context, attachment.uri).use { source ->
                        encryptInto(output, source, CdnCrypto.albumItemKey(key, index), maxPlaintextBytes) { part ->
                            onProgress?.invoke(sealedPlaintext + part, totalPlaintext)
                        }
                    }
                    if (written != sizes[index]) {
                        throw CdnException(CODE_NETWORK, message = "the attachment changed while it was uploaded")
                    }
                    sealedPlaintext += written
                }
                output.write(manifestBody)
            }
            return completeAlbumUpload(connection, key, ciphertextLengths.sum() + manifestBody.size)
        } catch (e: CdnException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "file service album upload failed", e)
            throw CdnException(CODE_NETWORK, message = e.message ?: "file service album upload failed", cause = e)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Fetches and decrypts the manifest of an album link; null when the id is
     * a single file (or gone), so the caller can fall back to `/raw`. The
     * manifest is small and capped; its names and types are display text only.
     */
    @Suppress("TooGenericExceptionCaught", "ThrowsCount")
    fun downloadAlbumManifest(baseUrl: String, id: String, keyFragment: String): List<CdnAlbumItem>? {
        val key = CdnCrypto.fragmentToKey(keyFragment)
            ?: throw CdnException(CODE_INVALID_RESPONSE, message = "the link carries no valid file key")
        val url = "${baseUrl.trimEnd('/')}${CdnLink.PATH_PREFIX}/$id/manifest"
        val connection = openDownloadConnection(url)
        try {
            val status = connection.responseCode
            if (status == HTTP_NOT_FOUND) {
                return null
            }
            requireDownloadable(status)
            val body = readBounded(connection, CdnAlbum.MAX_MANIFEST_BYTES.toLong())
            val plaintext = CdnCrypto.decrypt(CdnCrypto.albumManifestKey(key), body)
                ?: throw CdnException(CODE_DECRYPT, status, "the album manifest failed authentication")
            return CdnAlbum.parseManifest(plaintext)
                ?: throw CdnException(CODE_INVALID_RESPONSE, status, "the album manifest is not readable")
        } catch (e: Exception) {
            throw asDownloadFailure(e)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Downloads and decrypts one album item by its 0-based [index], under the
     * HKDF subkey of that position; blocking and bounded by [maxBytes], like
     * [download]. The key never reaches the URL or a log line.
     */
    @Suppress("TooGenericExceptionCaught", "ThrowsCount")
    fun downloadAlbumItem(baseUrl: String, id: String, index: Int, keyFragment: String, maxBytes: Long): ByteArray {
        val key = CdnCrypto.fragmentToKey(keyFragment)
            ?: throw CdnException(CODE_INVALID_RESPONSE, message = "the link carries no valid file key")
        if (index < 0 || index >= CdnAlbum.HARD_MAX_ITEMS) {
            throw CdnException(CODE_INVALID_RESPONSE, message = "the album item index is out of range")
        }
        val url = "${baseUrl.trimEnd('/')}${CdnLink.PATH_PREFIX}/$id/items/$index"
        val connection = openDownloadConnection(url)
        try {
            val status = connection.responseCode
            requireDownloadable(status)
            val body = readBounded(connection, maxBytes)
            return CdnCrypto.decrypt(CdnCrypto.albumItemKey(key, index), body)
                ?: throw CdnException(CODE_DECRYPT, status, "the downloaded album item failed authentication")
        } catch (e: Exception) {
            throw asDownloadFailure(e)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Downloads and decrypts one uploaded file; blocking, call it from a
     * background thread and keep [maxBytes] in memory reach of the device.
     *
     * The fragment key is only used locally: the request goes to
     * `/f/<id>/raw` without it (that endpoint needs no auth) and it is never
     * logged. [maxBytes] bounds the ciphertext that is buffered, so a link to
     * a huge payload fails with [CODE_TOO_LARGE] instead of an OOM.
     */
    @Suppress("TooGenericExceptionCaught")
    fun download(baseUrl: String, id: String, keyFragment: String, maxBytes: Long): ByteArray {
        val key = CdnCrypto.fragmentToKey(keyFragment)
            ?: throw CdnException(CODE_INVALID_RESPONSE, message = "the link carries no valid file key")
        val url = "${baseUrl.trimEnd('/')}${CdnLink.PATH_PREFIX}/$id/raw"
        val connection = openDownloadConnection(url)
        try {
            return completeDownload(connection, key, maxBytes)
        } catch (e: Exception) {
            throw asDownloadFailure(e)
        } finally {
            connection.disconnect()
        }
    }

    private fun completeDownload(connection: HttpURLConnection, key: ByteArray, maxBytes: Long): ByteArray {
        val status = connection.responseCode
        requireDownloadable(status)
        val body = readBounded(connection, maxBytes)
        return CdnCrypto.decrypt(key, body)
            ?: throw CdnException(CODE_DECRYPT, status, "the downloaded file failed authentication")
    }

    /** Fails a download that did not return a success status. */
    private fun requireDownloadable(status: Int) {
        if (status == HTTP_NOT_FOUND) {
            throw CdnException(CODE_NOT_FOUND, status, "the file is gone or the link is wrong")
        }
        if (status !in HTTP_OK_MIN..HTTP_OK_MAX) {
            throw CdnException(CODE_NETWORK, status, "the file service rejected the download (HTTP $status)")
        }
    }

    /** Keeps a [CdnException] and wraps everything else; the message has no key. */
    private fun asDownloadFailure(e: Exception): CdnException {
        if (e is CdnException) {
            return e
        }
        Log.w(TAG, "file service download failed", e)
        return CdnException(CODE_NETWORK, message = e.message ?: "file service download failed", cause = e)
    }

    private fun openDownloadConnection(url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = DOWNLOAD_TIMEOUT_MS
        connection.setRequestProperty("Accept", "application/octet-stream")
        return connection
    }

    private fun readBounded(connection: HttpURLConnection, maxBytes: Long): ByteArray {
        if (connection.contentLengthLong > maxBytes) {
            throw CdnException(CODE_TOO_LARGE, message = "the file is too large to render inline")
        }
        val announced = connection.contentLengthLong
        val output = if (announced in 1..maxBytes) ByteArrayOutputStream(announced.toInt()) else ByteArrayOutputStream()
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        connection.inputStream.use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read == -1) {
                    break
                }
                if (output.size().toLong() + read > maxBytes) {
                    throw CdnException(CODE_TOO_LARGE, message = "the file is too large to render inline")
                }
                output.write(buffer, 0, read)
            }
        }
        return output.toByteArray()
    }

    private fun requireService(baseUrl: String, token: String) {
        if (baseUrl.isEmpty() || token.isEmpty()) {
            throw CdnException(CODE_NOT_CONFIGURED, message = "the bridge does not advertise a file service")
        }
    }

    private fun requireFitsUploadLimit(total: Long, maxPlaintextBytes: Long) {
        if (total > maxPlaintextBytes) {
            throw CdnException(CODE_TOO_LARGE, message = "the attachment exceeds the configured upload limit")
        }
    }

    private fun openAttachment(context: Context, uri: Uri): InputStream {
        return context.contentResolver.openInputStream(uri)
            ?: throw CdnException(CODE_NETWORK, message = "cannot open the attachment")
    }

    private fun openConnection(url: String, token: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = UPLOAD_TIMEOUT_MS
        connection.doOutput = true
        connection.setChunkedStreamingMode(CHUNK_BYTES)
        connection.setRequestProperty("Authorization", "Bearer $token")
        connection.setRequestProperty("Content-Type", "application/octet-stream")
        connection.setRequestProperty("Accept", "application/json")
        return connection
    }

    /** Streams `nonce || ciphertext || tag` into [output], never buffering the file. */
    private fun writeEncryptedBody(
        output: OutputStream,
        source: InputStream,
        key: ByteArray,
        maxPlaintextBytes: Long,
        total: Long,
        onProgress: ((written: Long, total: Long) -> Unit)?,
    ): Long {
        val nonce = ByteArray(CdnCrypto.NONCE_BYTES)
        secureRandom.nextBytes(nonce)
        output.write(nonce)
        val sealed = CipherOutputStream(output, CdnCrypto.encryptCipher(key, nonce))

        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var written = 0L
        while (true) {
            val read = source.read(buffer)
            if (read == -1) {
                break
            }
            written += read
            if (written > maxPlaintextBytes) {
                throw CdnException(CODE_TOO_LARGE, message = "the attachment exceeds the configured upload limit")
            }
            sealed.write(buffer, 0, read)
            onProgress?.invoke(written, total)
        }
        sealed.close()
        return written
    }

    /** Validates the album caps the app can know; returns each item's ciphertext length. */
    @Suppress("ThrowsCount")
    private fun requireAlbumItemsFit(sizes: List<Long>, maxPlaintextBytes: Long): List<Long> {
        if (sizes.isEmpty()) {
            throw CdnException(CODE_INVALID_RESPONSE, message = "an album needs at least one item")
        }
        if (sizes.size > CdnAlbum.MAX_ITEMS) {
            throw CdnException(ERROR_ALBUM_TOO_MANY_ITEMS, message = "the album has more items than the bridge accepts")
        }
        return sizes.map { size ->
            if (size <= 0) {
                throw CdnException(CODE_TOO_LARGE, message = "cannot size an album attachment")
            }
            requireFitsUploadLimit(size, maxPlaintextBytes)
            size + CdnCrypto.OVERHEAD_BYTES
        }
    }

    /** Pre-checks the album size caps the bridge defaults document. */
    private fun requireAlbumFits(ciphertextLengths: List<Long>, manifestCiphertextBytes: Int) {
        if (manifestCiphertextBytes > CdnAlbum.MAX_MANIFEST_BYTES) {
            throw CdnException(ERROR_ALBUM_MANIFEST_TOO_LARGE, message = "the album manifest is too large")
        }
        if (ciphertextLengths.sum() + manifestCiphertextBytes > CdnAlbum.MAX_ALBUM_BYTES) {
            throw CdnException(ERROR_ALBUM_TOO_LARGE, message = "the album exceeds the bridge's album size limit")
        }
    }

    /** Streams one plaintext into [output] as `nonce || ciphertext || tag`, without closing it. */
    private fun encryptInto(
        output: OutputStream,
        source: InputStream,
        key: ByteArray,
        maxPlaintextBytes: Long,
        onProgress: ((written: Long) -> Unit)?,
    ): Long {
        val nonce = ByteArray(CdnCrypto.NONCE_BYTES)
        secureRandom.nextBytes(nonce)
        output.write(nonce)
        val cipher = CdnCrypto.encryptCipher(key, nonce)

        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var written = 0L
        while (true) {
            val read = source.read(buffer)
            if (read == -1) {
                break
            }
            written += read
            if (written > maxPlaintextBytes) {
                throw CdnException(CODE_TOO_LARGE, message = "the attachment exceeds the configured upload limit")
            }
            val sealed = cipher.update(buffer, 0, read)
            if (sealed != null && sealed.isNotEmpty()) {
                output.write(sealed)
            }
            onProgress?.invoke(written)
        }
        val tail = cipher.doFinal()
        if (tail.isNotEmpty()) {
            output.write(tail)
        }
        return written
    }

    private fun completeAlbumUpload(connection: HttpURLConnection, key: ByteArray, written: Long): CdnAlbumUpload {
        val status = connection.responseCode
        val stream = if (status in HTTP_OK_MIN..HTTP_OK_MAX) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        if (status != HTTP_CREATED) {
            failUpload(status, body)
        }

        val parsed = decodeAlbumUpload(body, status)
        if (parsed.id.isEmpty() || parsed.items <= 0) {
            throw CdnException(CODE_INVALID_RESPONSE, status, "the bridge returned no album id")
        }
        return CdnAlbumUpload(
            id = parsed.id,
            size = if (parsed.size > 0) parsed.size else written,
            itemCount = parsed.items,
            expiresAtMs = parsed.expiresAtMs,
            keyFragment = CdnCrypto.keyToFragment(key),
        )
    }

    @Suppress("TooGenericExceptionCaught")
    private fun decodeAlbumUpload(body: String, status: Int): AlbumUploadResponse {
        return try {
            json.decodeFromString(serializer<AlbumUploadResponse>(), body)
        } catch (e: Exception) {
            Log.e(TAG, "unexpected file service album response", e)
            throw CdnException(CODE_INVALID_RESPONSE, status, "the bridge returned an unexpected album response", e)
        }
    }

    private fun completeUpload(connection: HttpURLConnection, key: ByteArray, written: Long): CdnUpload {
        val status = connection.responseCode
        val stream = if (status in HTTP_OK_MIN..HTTP_OK_MAX) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        if (status != HTTP_CREATED) {
            failUpload(status, body)
        }

        val parsed = decodeUpload(body, status)
        if (parsed.id.isEmpty()) {
            throw CdnException(CODE_INVALID_RESPONSE, status, "the bridge returned no file id")
        }
        return CdnUpload(
            id = parsed.id,
            size = if (parsed.size > 0) parsed.size else written,
            expiresAtMs = parsed.expiresAtMs,
            keyFragment = CdnCrypto.keyToFragment(key),
        )
    }

    @Suppress("TooGenericExceptionCaught")
    private fun decodeUpload(body: String, status: Int): UploadResponse {
        return try {
            json.decodeFromString(serializer<UploadResponse>(), body)
        } catch (e: Exception) {
            Log.e(TAG, "unexpected file service response", e)
            throw CdnException(CODE_INVALID_RESPONSE, status, "the bridge returned an unexpected upload response", e)
        }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun failUpload(status: Int, body: String): Nothing {
        val parsed = try {
            json.decodeFromString(serializer<ErrorResponse>(), body)
        } catch (expected: Exception) {
            null
        }
        val code = parsed?.error?.takeIf { it.isNotBlank() } ?: "http_$status"
        val message = parsed?.message?.takeIf { it.isNotBlank() }
            ?: "the file service rejected the upload (HTTP $status)"
        Log.w(TAG, "file service upload rejected: HTTP $status: $code: $message")
        throw CdnException(code, status, message)
    }
}
