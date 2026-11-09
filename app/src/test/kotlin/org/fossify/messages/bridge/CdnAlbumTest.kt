/*
 * SPDX-FileCopyrightText: 2026 The Fossify Messages bridge contributors
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

@file:Suppress("MagicNumber")

package org.fossify.messages.bridge

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer

/**
 * The album container the app uploads: the `CDNALB01` framing (magic, count,
 * uint64 big-endian lengths, payload order) and the encrypted manifest JSON
 * (`version`, names, types, plaintext sizes). The framing and the manifest are
 * pure, so the wire shape is pinned here.
 */
class CdnAlbumTest {

    @Test
    fun headerIsMagicCountAndBigEndianLengths() {
        val header = CdnAlbum.frameHeader(itemCiphertextLengths = listOf(5L, 770L), manifestCiphertextLength = 3L)

        val lengths = ByteBuffer.allocate(3 * 8).putLong(5).putLong(770).putLong(3).array()
        val expected = "CDNALB01".toByteArray() + byteArrayOf(2) + lengths
        assertArrayEquals(expected, header)
        assertEquals(CdnAlbum.HEADER_BYTES + 3 * 8, header.size)
    }

    @Test
    fun headerRejectsAnEmptyOrBrokenContainer() {
        assertThrows(IllegalArgumentException::class.java) {
            CdnAlbum.frameHeader(itemCiphertextLengths = emptyList(), manifestCiphertextLength = 1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CdnAlbum.frameHeader(itemCiphertextLengths = listOf(0L), manifestCiphertextLength = 1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CdnAlbum.frameHeader(itemCiphertextLengths = listOf(1L), manifestCiphertextLength = 0L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CdnAlbum.frameHeader(
                itemCiphertextLengths = List(CdnAlbum.HARD_MAX_ITEMS + 1) { 1L },
                manifestCiphertextLength = 1L,
            )
        }
    }

    /**
     * Builds the container the upload path streams and parses it back the way
     * the bridge does: declared lengths must line up exactly, items decrypt
     * under their position's subkey and the manifest under its own.
     */
    @Test
    fun containerFramingCarriesItemsAndManifestInOrder() {
        val fragmentKey = CdnCrypto.generateKey()
        val plaintexts = listOf("first item".toByteArray(), "second, longer item".toByteArray())
        val manifestItems = listOf(
            CdnAlbumItem(name = "one.jpg", type = "image/jpeg", size = plaintexts[0].size.toLong()),
            CdnAlbumItem(name = "two.mp4", type = "video/mp4", size = plaintexts[1].size.toLong()),
        )
        val itemBodies = plaintexts.mapIndexed { index, plaintext ->
            CdnCrypto.encrypt(CdnCrypto.albumItemKey(fragmentKey, index), plaintext)
        }
        val manifestBody = CdnCrypto.encrypt(
            CdnCrypto.albumManifestKey(fragmentKey),
            CdnAlbum.manifestJson(manifestItems),
        )

        val header = CdnAlbum.frameHeader(itemBodies.map { it.size.toLong() }, manifestBody.size.toLong())
        val container = header + itemBodies[0] + itemBodies[1] + manifestBody

        val buffer = ByteBuffer.wrap(container)
        val magic = ByteArray(CdnAlbum.MAGIC.length).also { buffer.get(it) }
        assertEquals(CdnAlbum.MAGIC, String(magic, Charsets.US_ASCII))
        val count = buffer.get().toInt()
        assertEquals(2, count)
        val lengths = LongArray(count + 1) { buffer.long }
        assertEquals(itemBodies[0].size.toLong(), lengths[0])
        assertEquals(itemBodies[1].size.toLong(), lengths[1])
        assertEquals(manifestBody.size.toLong(), lengths[2])
        assertEquals(itemBodies[0].size + itemBodies[1].size + manifestBody.size, buffer.remaining())

        val first = ByteArray(lengths[0].toInt()).also { buffer.get(it) }
        val second = ByteArray(lengths[1].toInt()).also { buffer.get(it) }
        val manifest = ByteArray(lengths[2].toInt()).also { buffer.get(it) }
        assertArrayEquals(plaintexts[0], CdnCrypto.decrypt(CdnCrypto.albumItemKey(fragmentKey, 0), first))
        assertArrayEquals(plaintexts[1], CdnCrypto.decrypt(CdnCrypto.albumItemKey(fragmentKey, 1), second))
        assertEquals(
            manifestItems,
            CdnAlbum.parseManifest(CdnCrypto.decrypt(CdnCrypto.albumManifestKey(fragmentKey), manifest)!!),
        )
    }

    @Test
    fun manifestJsonIsTheSpecShape() {
        val json = CdnAlbum.manifestJson(
            listOf(
                CdnAlbumItem(name = "IMG_0001.jpg", type = "image/jpeg", size = 184320),
                CdnAlbumItem(name = "clip.mp4", type = "video/mp4", size = 5242880),
            )
        ).decodeToString()

        assertEquals(
            "{\"version\":1,\"items\":[{\"name\":\"IMG_0001.jpg\",\"type\":\"image/jpeg\",\"size\":184320}," +
                "{\"name\":\"clip.mp4\",\"type\":\"video/mp4\",\"size\":5242880}]}",
            json,
        )
    }

    @Test
    fun manifestRoundTripsAndIgnoresUnknownFields() {
        val items = listOf(CdnAlbumItem(name = "a.mp3", type = "audio/mpeg", size = 42))

        assertEquals(items, CdnAlbum.parseManifest(CdnAlbum.manifestJson(items)))

        val withExtra = "{\"version\":1,\"future\":true,\"items\":[{\"name\":\"a.mp3\",\"type\":\"audio/mpeg\"," +
            "\"size\":42,\"extra\":\"ignored\"}]}"
        assertEquals(items, CdnAlbum.parseManifest(withExtra.toByteArray()))
    }

    @Test
    fun manifestRejectsWrongVersionsShapesAndCounts() {
        assertNull(CdnAlbum.parseManifest("not json".toByteArray()))
        assertNull(CdnAlbum.parseManifest("{\"version\":2,\"items\":[]}".toByteArray()))
        assertNull(CdnAlbum.parseManifest("{\"version\":1,\"items\":[]}".toByteArray()))
        assertNull(CdnAlbum.parseManifest("{\"version\":1}".toByteArray()))
        assertNull(CdnAlbum.parseManifest("{\"version\":1,\"items\":[{\"name\":1}]}".toByteArray()))

        val oversized = (0..CdnAlbum.HARD_MAX_ITEMS)
            .joinToString(",") { "{\"name\":\"i$it\",\"type\":\"image/png\",\"size\":1}" }
        assertNull(CdnAlbum.parseManifest("{\"version\":1,\"items\":[$oversized]}".toByteArray()))
        assertNotNull(
            CdnAlbum.parseManifest("{\"version\":1,\"items\":[{\"name\":\"\",\"type\":\"\",\"size\":0}]}".toByteArray())
        )
    }
}
