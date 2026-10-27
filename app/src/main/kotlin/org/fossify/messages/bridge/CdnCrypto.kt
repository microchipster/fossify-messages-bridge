/*
 * SPDX-FileCopyrightText: 2026 microchipster
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.fossify.messages.bridge

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM helpers of the bridge-hosted file service.
 *
 * The upload body is `nonce(12) || ciphertext || tag`, the key is 32 random
 * bytes and only ever travels in the link fragment as unpadded base64url.
 * Nothing here depends on Android APIs, so the round trip is covered by JVM
 * tests.
 */
internal object CdnCrypto {
    const val KEY_BYTES = 32
    const val NONCE_BYTES = 12
    const val TAG_BITS = 128
    const val TAG_BYTES = TAG_BITS / 8
    const val OVERHEAD_BYTES = NONCE_BYTES + TAG_BYTES

    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val KEY_ALGORITHM = "AES"

    /** A fresh 32 byte file key from the platform CSPRNG. */
    fun generateKey(random: SecureRandom = SecureRandom()): ByteArray {
        val key = ByteArray(KEY_BYTES)
        random.nextBytes(key)
        return key
    }

    /** Encodes a key for the URL fragment: unpadded base64url. */
    fun keyToFragment(key: ByteArray): String {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(key)
    }

    /** Decodes a fragment key, or null when it is not a 32 byte unpadded base64url value. */
    @Suppress("SwallowedException")
    fun fragmentToKey(fragment: String): ByteArray? {
        return try {
            Base64.getUrlDecoder().decode(fragment).takeIf { it.size == KEY_BYTES }
        } catch (expected: IllegalArgumentException) {
            null
        }
    }

    /** An encrypt-mode GCM cipher pinned to [nonce]; used by the streaming upload. */
    fun encryptCipher(key: ByteArray, nonce: ByteArray): Cipher {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, KEY_ALGORITHM), GCMParameterSpec(TAG_BITS, nonce))
        return cipher
    }

    /** Encrypts [plaintext] into the on-the-wire `nonce || ciphertext || tag` body. */
    fun encrypt(key: ByteArray, plaintext: ByteArray, random: SecureRandom = SecureRandom()): ByteArray {
        val nonce = ByteArray(NONCE_BYTES)
        random.nextBytes(nonce)
        return nonce + encryptCipher(key, nonce).doFinal(plaintext)
    }

    /** Decrypts a `nonce || ciphertext || tag` body; null on a wrong key or corrupt payload. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    fun decrypt(key: ByteArray, payload: ByteArray): ByteArray? {
        return try {
            if (payload.size < OVERHEAD_BYTES) {
                null
            } else {
                val nonce = payload.copyOfRange(0, NONCE_BYTES)
                val sealed = payload.copyOfRange(NONCE_BYTES, payload.size)
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, KEY_ALGORITHM), GCMParameterSpec(TAG_BITS, nonce))
                cipher.doFinal(sealed)
            }
        } catch (expected: Exception) {
            null
        }
    }
}
