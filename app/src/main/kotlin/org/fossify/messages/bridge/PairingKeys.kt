/*
 * SPDX-FileCopyrightText: 2026 The Fossify Messages bridge contributors
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.fossify.messages.bridge

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * Device identity of the pairing protocol: an ECDSA P-256 keypair that lives
 * in the Android Keystore. The private key is non-exportable and never leaves
 * the device; the bridge only sees the SPKI public key and a signature over
 * the pairing challenge.
 */
internal object PairingKeys {
    const val KEY_ALIAS = "smsmms_pair_key"

    private const val TAG = "PairingKeys"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val CURVE = "prime256v1"
    private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
    private const val NONCE_BYTES = 16

    /** SPKI DER of the pairing public key, base64url unpadded. */
    fun publicKeyBase64Url(): String {
        return base64Url(deviceKeyPair().public.encoded)
    }

    /** ECDSA-SHA256 signature over [signingInput], base64url unpadded. */
    fun sign(signingInput: String): String {
        val signature = Signature.getInstance(SIGNATURE_ALGORITHM)
        signature.initSign(deviceKeyPair().private)
        signature.update(signingInput.toByteArray(Charsets.UTF_8))
        return base64Url(signature.sign())
    }

    /** A fresh 16 byte nonce, base64url unpadded. */
    fun randomNonce(): String {
        val nonce = ByteArray(NONCE_BYTES)
        SecureRandom().nextBytes(nonce)
        return base64Url(nonce)
    }

    /** Loads (or creates) the non-exportable P-256 signing key of this installation. */
    @Synchronized
    private fun deviceKeyPair(): KeyPair {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry
        if (existing != null) {
            return KeyPair(existing.certificate.publicKey, existing.privateKey)
        }

        Log.i(TAG, "generating the pairing key $KEY_ALIAS")
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
        generator.initialize(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec(CURVE))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build()
        )
        return generator.generateKeyPair()
    }

    private fun base64Url(bytes: ByteArray): String {
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
}
