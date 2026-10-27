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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trips the exact upload body the file service expects:
 * `nonce(12) || ciphertext || tag`, key in the link fragment as unpadded
 * base64url.
 */
class CdnCryptoTest {

    @Test
    fun roundTripPreservesPlaintext() {
        val key = CdnCrypto.generateKey()
        val plaintext = "large file contents".toByteArray()

        val payload = CdnCrypto.encrypt(key, plaintext)

        assertArrayEquals(plaintext, CdnCrypto.decrypt(key, payload))
    }

    @Test
    fun emptyPlaintextStillProducesAFullFrame() {
        val key = CdnCrypto.generateKey()

        val payload = CdnCrypto.encrypt(key, ByteArray(0))

        assertEquals(CdnCrypto.OVERHEAD_BYTES, payload.size)
        assertArrayEquals(ByteArray(0), CdnCrypto.decrypt(key, payload))
    }

    @Test
    fun payloadIsNoncePlusSealedPlaintext() {
        val key = CdnCrypto.generateKey()
        val plaintext = ByteArray(1000) { it.toByte() }

        val payload = CdnCrypto.encrypt(key, plaintext)

        assertEquals(plaintext.size + CdnCrypto.OVERHEAD_BYTES, payload.size)
    }

    @Test
    fun eachUploadUsesAFreshNonce() {
        val key = CdnCrypto.generateKey()
        val plaintext = ByteArray(64)

        val first = CdnCrypto.encrypt(key, plaintext)
        val second = CdnCrypto.encrypt(key, plaintext)

        assertFalse(first.contentEquals(second))
    }

    @Test
    fun wrongKeyFailsAuthentication() {
        val payload = CdnCrypto.encrypt(CdnCrypto.generateKey(), "secret".toByteArray())

        assertNull(CdnCrypto.decrypt(CdnCrypto.generateKey(), payload))
    }

    @Test
    fun tamperedCiphertextFailsAuthentication() {
        val key = CdnCrypto.generateKey()
        val payload = CdnCrypto.encrypt(key, "secret".toByteArray())
        payload[payload.size - 1] = (payload[payload.size - 1].toInt() xor 1).toByte()

        assertNull(CdnCrypto.decrypt(key, payload))
    }

    @Test
    fun shortPayloadIsRejected() {
        assertNull(CdnCrypto.decrypt(CdnCrypto.generateKey(), ByteArray(4)))
    }

    @Test
    fun keyFragmentIsUnpaddedBase64Url() {
        val key = CdnCrypto.generateKey()

        val fragment = CdnCrypto.keyToFragment(key)

        assertEquals(43, fragment.length)
        assertFalse(fragment.contains('='))
        assertFalse(fragment.contains('+'))
        assertFalse(fragment.contains('/'))
        assertArrayEquals(key, CdnCrypto.fragmentToKey(fragment))
    }

    @Test
    fun fragmentRejectsWrongLengthAndAlphabet() {
        assertNull(CdnCrypto.fragmentToKey("abc"))
        assertNull(CdnCrypto.fragmentToKey("!!!!"))
        assertNull(CdnCrypto.fragmentToKey(""))
        assertTrue(CdnCrypto.OVERHEAD_BYTES == CdnCrypto.NONCE_BYTES + CdnCrypto.TAG_BYTES)
    }
}
