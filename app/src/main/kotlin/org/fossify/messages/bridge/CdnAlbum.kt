/*
 * SPDX-FileCopyrightText: 2026 The Fossify Messages bridge contributors
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.fossify.messages.bridge

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import java.io.ByteArrayOutputStream

/** One manifest entry of an album: display text and the plaintext size. */
internal data class CdnAlbumItem(
    val name: String,
    val type: String,
    val size: Long,
)

/**
 * The album container of the bridge file service.
 *
 * An album is one link carrying one encrypted manifest plus up to
 * [HARD_MAX_ITEMS] encrypted items; the bridge stores ciphertext only and
 * never parses the manifest ([manifestJson] / [parseManifest]). Everything
 * here is free of Android APIs, so the framing and the manifest round trip
 * are JVM tests.
 */
internal object CdnAlbum {
    /** Magic of every album upload container; the trailing 01 is the version. */
    const val MAGIC = "CDNALB01"

    /** Container version of the manifest; readers reject anything else. */
    const val MANIFEST_VERSION = 1

    /** Bridge default for `cdn.max_album_items`; the app pre-checks against it. */
    const val MAX_ITEMS = 20

    /** Wire-format maximum of the container's one-byte item count. */
    const val HARD_MAX_ITEMS = 255

    /** Bridge default for `cdn.max_album_bytes` (500 MiB, framing excluded). */
    const val MAX_ALBUM_BYTES = 500L * 1024 * 1024

    /** Bridge hard cap of one manifest ciphertext (64 KiB). */
    const val MAX_MANIFEST_BYTES = 64 * 1024

    /** Size of the fixed part: the 8 magic bytes and the 1 count byte. */
    const val HEADER_BYTES = MAGIC.length + 1

    private const val ITEM_SIZE_BYTES = 8
    private const val BITS_PER_BYTE = 8
    private const val BYTE_MASK = 0xFF

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * The container header of [itemCiphertextLengths] and the manifest: magic,
     * count, then every item length and the manifest length as uint64
     * big-endian. The payloads follow in the same order; callers must have
     * validated the caps before framing.
     */
    fun frameHeader(itemCiphertextLengths: List<Long>, manifestCiphertextLength: Long): ByteArray {
        require(itemCiphertextLengths.isNotEmpty())
        require(itemCiphertextLengths.size <= HARD_MAX_ITEMS)
        require(itemCiphertextLengths.all { it > 0 } && manifestCiphertextLength > 0)
        val capacity = HEADER_BYTES + (itemCiphertextLengths.size + 1) * ITEM_SIZE_BYTES
        val header = ByteArrayOutputStream(capacity)
        header.write(MAGIC.toByteArray(Charsets.US_ASCII))
        header.write(itemCiphertextLengths.size)
        itemCiphertextLengths.forEach { writeUint64(header, it) }
        writeUint64(header, manifestCiphertextLength)
        return header.toByteArray()
    }

    /** The manifest JSON (`version` plus names, types and plaintext sizes). */
    fun manifestJson(items: List<CdnAlbumItem>): ByteArray {
        val manifest = AlbumManifest(
            version = MANIFEST_VERSION,
            items = items.map { AlbumManifestItem(name = it.name, type = it.type, size = it.size) },
        )
        return json.encodeToString(serializer<AlbumManifest>(), manifest).toByteArray(Charsets.UTF_8)
    }

    /**
     * Parses a decrypted manifest; null when it is not the JSON version 1 the
     * app writes. Unknown fields are ignored, names and types are display text
     * only, and a hostile item count is rejected.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    fun parseManifest(bytes: ByteArray): List<CdnAlbumItem>? {
        val manifest = try {
            json.decodeFromString(serializer<AlbumManifest>(), bytes.decodeToString())
        } catch (expected: Exception) {
            null
        } ?: return null
        if (manifest.version != MANIFEST_VERSION || manifest.items.isEmpty() || manifest.items.size > HARD_MAX_ITEMS) {
            return null
        }
        return manifest.items.map { CdnAlbumItem(name = it.name, type = it.type, size = it.size) }
    }

    private fun writeUint64(output: ByteArrayOutputStream, value: Long) {
        for (byteIndex in 0 until ITEM_SIZE_BYTES) {
            val shift = (ITEM_SIZE_BYTES - 1 - byteIndex) * BITS_PER_BYTE
            output.write((value ushr shift).toInt() and BYTE_MASK)
        }
    }

    @Serializable
    private data class AlbumManifest(val version: Int, val items: List<AlbumManifestItem>)

    @Serializable
    private data class AlbumManifestItem(val name: String, val type: String, val size: Long)
}
