/*
 * SPDX-FileCopyrightText: 2026 microchipster
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

@file:Suppress("MagicNumber")

package org.fossify.messages.bridge

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The album subkey derivation eats exactly the spec recipe: HKDF-SHA256
 * (RFC 5869) with salt `cdn-album-item` and info `item:<index>` / `manifest`.
 * The RFC vector pins the primitive, the pinned hex vectors pin the exact
 * album derivation the browser gallery repeats, and the round trips pin that
 * a raw fragment key never opens an item.
 */
class CdnAlbumCryptoTest {

    /** The RFC 5869 appendix A.1 (test case 1, SHA-256) vector, L = 42. */
    @Test
    fun hkdfMatchesTheRfc5869Vector() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = ByteArray(13) { it.toByte() }
        val info = ByteArray(10) { (0xf0 + it).toByte() }

        val derived = CdnCrypto.hkdfSha256(ikm, salt, info, 42)

        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            derived.toHex(),
        )
    }

    @Test
    fun albumSubkeysMatchTheSpecRecipe() {
        val fragmentKey = ByteArray(CdnCrypto.KEY_BYTES) { it.toByte() }

        assertEquals(
            "1037ab7ca2e2310e6531a3a93d1be32cdb18bf7e493e05b3ba13a1d3fd3136c3",
            CdnCrypto.albumItemKey(fragmentKey, 0).toHex(),
        )
        assertEquals(
            "1d0a13ebdf3a4558abc7bcbb21eeb9fc6a29eb5416f4b556659ed2d0c1304a64",
            CdnCrypto.albumItemKey(fragmentKey, 1).toHex(),
        )
        assertEquals(
            "1192b0e7258980a6bd3b16a9716c3fe44bdc5b823cf12a6b6f6e663bae82b355",
            CdnCrypto.albumManifestKey(fragmentKey).toHex(),
        )
    }

    @Test
    fun subkeysAreDeterministicDistinctAndFullLength() {
        val fragmentKey = CdnCrypto.generateKey()
        val item0 = CdnCrypto.albumItemKey(fragmentKey, 0)
        val item1 = CdnCrypto.albumItemKey(fragmentKey, 1)
        val item2 = CdnCrypto.albumItemKey(fragmentKey, 2)
        val manifest = CdnCrypto.albumManifestKey(fragmentKey)

        assertEquals(CdnCrypto.KEY_BYTES, item0.size)
        assertArrayEquals(item0, CdnCrypto.albumItemKey(fragmentKey, 0))
        assertNotEquals(item0.toHex(), item1.toHex())
        assertNotEquals(item1.toHex(), item2.toHex())
        assertNotEquals(item0.toHex(), manifest.toHex())
        assertNotEquals(manifest.toHex(), CdnCrypto.albumManifestKey(CdnCrypto.generateKey()).toHex())
    }

    @Test
    fun itemAndManifestRoundTripUnderTheirOwnSubkeys() {
        val fragmentKey = CdnCrypto.generateKey()
        val item0 = CdnCrypto.encrypt(CdnCrypto.albumItemKey(fragmentKey, 0), "photo".toByteArray())
        val manifest = CdnCrypto.encrypt(CdnCrypto.albumManifestKey(fragmentKey), "{\"version\":1}".toByteArray())

        assertArrayEquals("photo".toByteArray(), CdnCrypto.decrypt(CdnCrypto.albumItemKey(fragmentKey, 0), item0))
        assertArrayEquals(
            "{\"version\":1}".toByteArray(),
            CdnCrypto.decrypt(CdnCrypto.albumManifestKey(fragmentKey), manifest),
        )

        // Position-bound: another item's subkey never opens item 0, and the
        // raw fragment key is never used on payloads.
        assertNull(CdnCrypto.decrypt(CdnCrypto.albumItemKey(fragmentKey, 1), item0))
        assertNull(CdnCrypto.decrypt(CdnCrypto.albumManifestKey(fragmentKey), item0))
        assertNull(CdnCrypto.decrypt(fragmentKey, item0))
    }

    @Test
    fun hkdfHandlesAnEmptySaltAndRejectsInvalidLengths() {
        val ikm = ByteArray(4)
        assertEquals(32, CdnCrypto.hkdfSha256(ikm, ByteArray(0), ByteArray(0), 32).size)
        assertArrayEquals(
            CdnCrypto.hkdfSha256(ikm, ByteArray(32), ByteArray(0), 32),
            CdnCrypto.hkdfSha256(ikm, ByteArray(0), ByteArray(0), 32),
        )
        assertThrows(IllegalArgumentException::class.java) {
            CdnCrypto.hkdfSha256(ikm, ByteArray(0), ByteArray(0), 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CdnCrypto.hkdfSha256(ikm, ByteArray(0), ByteArray(0), 255 * 32 + 1)
        }
    }

    @Test
    fun albumsUseAFreshFragmentKey() {
        val first = CdnCrypto.generateKey()
        val second = CdnCrypto.generateKey()

        assertFalse(first.contentEquals(second))
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
