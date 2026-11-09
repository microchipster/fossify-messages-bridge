/*
 * SPDX-FileCopyrightText: 2026 The Fossify Messages bridge contributors
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.fossify.messages.bridge

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM and HKDF helpers of the bridge-hosted file service.
 *
 * The upload body is `nonce(12) || ciphertext || tag`, the key is 32 random
 * bytes and only ever travels in the link fragment as unpadded base64url.
 * An album derives one subkey per item and one for its manifest with
 * HKDF-SHA256 (see [albumItemKey] / [albumManifestKey]), so the single
 * fragment key is never used for payloads directly. Nothing here depends on
 * Android APIs, so the round trip is covered by JVM tests.
 */
internal object CdnCrypto {
    const val KEY_BYTES = 32
    const val NONCE_BYTES = 12
    const val TAG_BITS = 128
    const val TAG_BYTES = TAG_BITS / 8
    const val OVERHEAD_BYTES = NONCE_BYTES + TAG_BYTES

    /** HKDF salt of the album subkey derivation (ASCII, no terminator). */
    const val ALBUM_SALT = "cdn-album-item"

    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val KEY_ALGORITHM = "AES"
    private const val HKDF_MAC = "HmacSHA256"
    private const val HKDF_HASH_BYTES = 32
    private const val HKDF_MAX_BLOCKS = 255

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

    /**
     * The subkey of the album item at 0-based [index]:
     * `HKDF-SHA256(ikm = fragmentKey, salt = "cdn-album-item", info = "item:<index>")`.
     * The same derivation runs in the gallery wrapper, so items stay bound to
     * their display position and not to a (repeatable) file name.
     */
    fun albumItemKey(fragmentKey: ByteArray, index: Int): ByteArray {
        return hkdfSha256(fragmentKey, salt(), "item:$index".toByteArray(Charsets.US_ASCII), KEY_BYTES)
    }

    /** The subkey of an album manifest: same salt, `info = "manifest"`. */
    fun albumManifestKey(fragmentKey: ByteArray): ByteArray {
        return hkdfSha256(fragmentKey, salt(), MANIFEST_INFO, KEY_BYTES)
    }

    /**
     * HKDF-SHA256 (RFC 5869) with [length] output bytes; the primitive behind
     * the album subkeys. Kept public to the module so the derivation can be
     * pinned against the RFC test vector in JVM tests.
     */
    fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length > 0 && length <= HKDF_MAX_BLOCKS * HKDF_HASH_BYTES)
        val mac = Mac.getInstance(HKDF_MAC)
        // RFC 5869: a missing salt means HashLen zero bytes.
        val effectiveSalt = if (salt.isEmpty()) ByteArray(HKDF_HASH_BYTES) else salt
        mac.init(SecretKeySpec(effectiveSalt, HKDF_MAC))
        val pseudoRandomKey = mac.doFinal(ikm)
        mac.init(SecretKeySpec(pseudoRandomKey, HKDF_MAC))

        val blocks = (length + HKDF_HASH_BYTES - 1) / HKDF_HASH_BYTES
        val expanded = ByteArray(blocks * HKDF_HASH_BYTES)
        var previous = ByteArray(0)
        for (block in 0 until blocks) {
            mac.update(previous)
            mac.update(info)
            mac.update((block + 1).toByte())
            previous = mac.doFinal()
            previous.copyInto(expanded, block * HKDF_HASH_BYTES)
        }
        return expanded.copyOf(length)
    }

    private val MANIFEST_INFO = "manifest".toByteArray(Charsets.US_ASCII)

    private fun salt(): ByteArray = ALBUM_SALT.toByteArray(Charsets.US_ASCII)

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
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, KEY_ALGORITHM), GCMParameterSpec(TAG_BITS, nonce))
                // Sealed data starts at NONCE_BYTES; decrypting in place avoids a
                // full payload copy on the large downloads the render path makes.
                cipher.doFinal(payload, NONCE_BYTES, payload.size - NONCE_BYTES)
            }
        } catch (expected: Exception) {
            null
        }
    }
}
