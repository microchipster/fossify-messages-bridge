/*
 * SPDX-FileCopyrightText: 2026 The Fossify Messages bridge contributors
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.fossify.messages.bridge

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import org.fossify.commons.helpers.BaseConfig
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Persistent state of a device that was paired with a Matrix bridge through the
 * pairing protocol (`docs/PAIRING_PROTOCOL.md` in `mautrix-android-sms-mms`).
 *
 * Everything lives in the regular app preferences file next to the
 * static-token agent mode preferences ([BridgeConfig]), so a single place
 * tells the whole story of how this phone is bridged.
 *
 * The device token is the only secret that is not stored verbatim: it is
 * AES-GCM wrapped with a non-exportable Android Keystore key, so a preferences
 * dump (backup, `run-as cat`, root file copy) does not hand out a usable
 * bearer token. The wrapping key never leaves the Keystore and is not part of
 * Android backups, which means a restored preferences file cannot be decrypted
 * anywhere else (the device simply has to pair again).
 */
class PairingStore(context: Context) : BaseConfig(context) {

    companion object {
        private const val TAG = "PairingStore"

        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TOKEN_KEY_ALIAS = "smsmms_bridge_token_key"
        private const val GCM_TAG_BITS = 128
        private const val GCM_IV_MAX_BYTES = 16

        const val BRIDGE_HS_URL = "bridge_hs_url"
        const val BRIDGE_BASE_URL = "bridge_base_url"
        const val BRIDGE_CDN_BASE_URL = "bridge_cdn_base_url"
        const val BRIDGE_PAIRED_DEVICE_ID = "bridge_paired_device_id"
        const val BRIDGE_PAIRED_USER = "bridge_paired_user"
        const val BRIDGE_TOKEN_WRAPPED = "bridge_token_wrapped"
        const val BRIDGE_QUEUE_CURSOR = "bridge_queue_cursor"
        const val BRIDGE_EVENTS_CURSOR = "bridge_events_cursor"
        const val BRIDGE_LAST_EVENT_PUSHED_MS = "bridge_last_event_pushed_ms"
        const val BRIDGE_LAST_HEARTBEAT_MS = "bridge_last_heartbeat_ms"
        const val BRIDGE_PENDING_EVENTS = "bridge_pending_events"
        const val BRIDGE_RECENT_SEND_RESULTS = "bridge_recent_send_results"
        const val BRIDGE_MERGE_OUTCOME = "bridge_merge_outcome"

        private val TOKEN_KEY_LOCK = Any()

        fun newInstance(context: Context) = PairingStore(context)
    }

    /** Homeserver URL of the paired account, as reported by the bridge during pairing. */
    var homeserverUrl: String
        get() = prefs.getString(BRIDGE_HS_URL, "").orEmpty()
        set(value) = prefs.edit().putString(BRIDGE_HS_URL, value).apply()

    /** Public base URL of the bridge API (reverse-proxied; NOT the adb agent port). */
    var bridgeBaseUrl: String
        get() = prefs.getString(BRIDGE_BASE_URL, "").orEmpty()
        set(value) = prefs.edit().putString(BRIDGE_BASE_URL, value).apply()

    /**
     * Public base URL of the bridge-hosted encrypted file service, as
     * advertised by the bridge (`cdn_base_url`). Empty means the bridge does
     * not offer the service; the app never guesses a hostname of its own.
     */
    var cdnBaseUrl: String
        get() = prefs.getString(BRIDGE_CDN_BASE_URL, "").orEmpty()
        set(value) = prefs.edit().putString(BRIDGE_CDN_BASE_URL, value.trim()).apply()

    /**
     * Device id the bridge knows this phone by, as acknowledged during pairing.
     *
     * Normally the app-generated random id from [BridgeConfig.bridgeDeviceId];
     * older app versions generated and stored it here, so it is also the
     * adoption source for an already deployed phone. Never a hardware
     * identifier.
     */
    var pairedDeviceId: String
        get() = prefs.getString(BRIDGE_PAIRED_DEVICE_ID, "").orEmpty()
        set(value) = prefs.edit().putString(BRIDGE_PAIRED_DEVICE_ID, value).apply()

    /** MXID of the Matrix account that confirmed the pairing. */
    var pairedUser: String
        get() = prefs.getString(BRIDGE_PAIRED_USER, "").orEmpty()
        set(value) = prefs.edit().putString(BRIDGE_PAIRED_USER, value).apply()

    /** Cursor of the last queue command the bridge handed out (bridge -> app direction). */
    var queueCursor: Long
        get() = prefs.getLong(BRIDGE_QUEUE_CURSOR, 0)
        set(value) = prefs.edit().putLong(BRIDGE_QUEUE_CURSOR, value).apply()

    /** Cursor of the last event batch acknowledged by the bridge (app -> bridge direction). */
    var eventsCursor: Long
        get() = prefs.getLong(BRIDGE_EVENTS_CURSOR, 0)
        set(value) = prefs.edit().putLong(BRIDGE_EVENTS_CURSOR, value).apply()

    /** Timestamp (ms) of the newest event that was successfully pushed; used for gap catch-up. */
    var lastEventPushedMs: Long
        get() = prefs.getLong(BRIDGE_LAST_EVENT_PUSHED_MS, 0)
        set(value) = prefs.edit().putLong(BRIDGE_LAST_EVENT_PUSHED_MS, value).apply()

    /** Timestamp (ms) of the last heartbeat the bridge accepted. */
    var lastHeartbeatMs: Long
        get() = prefs.getLong(BRIDGE_LAST_HEARTBEAT_MS, 0)
        set(value) = prefs.edit().putLong(BRIDGE_LAST_HEARTBEAT_MS, value).apply()

    /** JSON-serialized events that were not acknowledged by the bridge yet. Managed by [PairingPoller]. */
    var pendingEvents: String
        get() = prefs.getString(BRIDGE_PENDING_EVENTS, "").orEmpty()
        set(value) = prefs.edit().putString(BRIDGE_PENDING_EVENTS, value).apply()

    /** JSON-serialized recent `send.result` events, used to answer re-delivered queue commands. */
    var recentSendResults: String
        get() = prefs.getString(BRIDGE_RECENT_SEND_RESULTS, "").orEmpty()
        set(value) = prefs.edit().putString(BRIDGE_RECENT_SEND_RESULTS, value).apply()

    /**
     * JSON-serialized `merge_threads.result` the user has not seen yet, shown
     * by the conversation list as a toast. Managed by [MergeThreads].
     */
    var mergeOutcome: String
        get() = prefs.getString(BRIDGE_MERGE_OUTCOME, "").orEmpty()
        set(value) = prefs.edit().putString(BRIDGE_MERGE_OUTCOME, value).apply()

    /** True when a wrapped device token is stored. */
    val isPaired: Boolean
        get() = prefs.getString(BRIDGE_TOKEN_WRAPPED, "").orEmpty().isNotEmpty()

    /**
     * The bearer token used with the pairing API, decrypted on read.
     *
     * Reading fails closed: when the Keystore entry is gone (app data restored
     * on another device, Keystore reset, ...) an empty string is returned and
     * the caller treats the phone as unpaired.
     */
    var token: String
        get() = unwrapToken(prefs.getString(BRIDGE_TOKEN_WRAPPED, "").orEmpty())
        set(value) {
            val wrapped = wrapToken(value)
            if (value.isEmpty() || wrapped.isNotEmpty()) {
                prefs.edit().putString(BRIDGE_TOKEN_WRAPPED, wrapped).apply()
            } else {
                Log.e(TAG, "refusing to store a token that could not be wrapped")
            }
        }

    /** Persists a completed pairing in one transaction. */
    fun savePairing(
        homeserverUrl: String,
        bridgeBaseUrl: String,
        deviceId: String,
        user: String,
        token: String,
        cdnBaseUrl: String = "",
    ) {
        val wrapped = wrapToken(token)
        if (wrapped.isEmpty()) {
            error("failed to wrap the device token")
        }

        queueCursor = 0
        eventsCursor = 0
        lastEventPushedMs = 0
        pendingEvents = ""
        recentSendResults = ""
        mergeOutcome = ""
        prefs.edit()
            .putString(BRIDGE_HS_URL, homeserverUrl)
            .putString(BRIDGE_BASE_URL, bridgeBaseUrl)
            .putString(BRIDGE_CDN_BASE_URL, cdnBaseUrl.trim())
            .putString(BRIDGE_PAIRED_DEVICE_ID, deviceId)
            .putString(BRIDGE_PAIRED_USER, user)
            .putString(BRIDGE_TOKEN_WRAPPED, wrapped)
            .apply()
    }

    /** Drops the device token but keeps the discovery hints (base URL, device id). */
    fun clearToken() {
        prefs.edit()
            .remove(BRIDGE_TOKEN_WRAPPED)
            .remove(BRIDGE_PENDING_EVENTS)
            .remove(BRIDGE_RECENT_SEND_RESULTS)
            .remove(BRIDGE_MERGE_OUTCOME)
            .apply()
    }

    /** Removes every trace of the pairing, leaving the static-token agent mode untouched. */
    fun clearPairing() {
        // The stable device id is kept on purpose: re-pairing the same phone
        // (also to another bridge) must not look like a new device.
        prefs.edit()
            .remove(BRIDGE_HS_URL)
            .remove(BRIDGE_BASE_URL)
            .remove(BRIDGE_CDN_BASE_URL)
            .remove(BRIDGE_PAIRED_USER)
            .remove(BRIDGE_TOKEN_WRAPPED)
            .remove(BRIDGE_QUEUE_CURSOR)
            .remove(BRIDGE_EVENTS_CURSOR)
            .remove(BRIDGE_LAST_EVENT_PUSHED_MS)
            .remove(BRIDGE_LAST_HEARTBEAT_MS)
            .remove(BRIDGE_PENDING_EVENTS)
            .remove(BRIDGE_RECENT_SEND_RESULTS)
            .remove(BRIDGE_MERGE_OUTCOME)
            .apply()
    }

    // Keystore/cipher failures must not break pairing; callers handle an empty result.
    @Suppress("TooGenericExceptionCaught")
    private fun wrapToken(token: String): String {
        if (token.isEmpty()) {
            return ""
        }
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, tokenKey())
            val iv = cipher.iv
            if (iv.size > GCM_IV_MAX_BYTES) {
                return ""
            }
            val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(byteArrayOf(iv.size.toByte()) + iv + encrypted, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "failed to wrap the device token", e)
            ""
        }
    }

    // A missing Keystore entry or a corrupt payload means "not paired"; this must never throw.
    @Suppress("TooGenericExceptionCaught")
    private fun unwrapToken(wrapped: String): String {
        if (wrapped.isEmpty()) {
            return ""
        }
        return try {
            val payload = Base64.decode(wrapped, Base64.DEFAULT)
            if (payload.size <= 2) {
                return ""
            }
            val ivSize = payload[0].toInt()
            if (ivSize <= 0 || ivSize > GCM_IV_MAX_BYTES || payload.size <= 1 + ivSize) {
                return ""
            }
            val iv = payload.copyOfRange(1, 1 + ivSize)
            val encrypted = payload.copyOfRange(1 + ivSize, payload.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, tokenKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "failed to unwrap the device token", e)
            ""
        }
    }

    private fun tokenKey(): SecretKey {
        synchronized(TOKEN_KEY_LOCK) {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val existing = keyStore.getEntry(TOKEN_KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
            if (existing != null) {
                return existing.secretKey
            }

            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            generator.init(
                KeyGenParameterSpec.Builder(
                    TOKEN_KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setUserAuthenticationRequired(false)
                    .build()
            )
            return generator.generateKey()
        }
    }
}
