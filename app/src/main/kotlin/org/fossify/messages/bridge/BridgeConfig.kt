/*
 * SPDX-FileCopyrightText: 2026 microchipster
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.fossify.messages.bridge

import android.content.Context
import org.fossify.commons.helpers.BaseConfig
import java.security.SecureRandom
import java.util.UUID

/**
 * Preferences of the bridge mode.
 *
 * They intentionally live in the same SharedPreferences file as the rest of
 * the app config, so a single write (pairing screen, control intent) can
 * toggle the whole feature.
 */
class BridgeConfig(context: Context) : BaseConfig(context) {

    companion object {
        const val DEFAULT_PORT = 8391

        private const val BRIDGE_ENABLED = "bridge_enabled"
        private const val BRIDGE_TOKEN = "bridge_token"
        private const val BRIDGE_PORT = "bridge_port"
        private const val BRIDGE_DEVICE_ID = "bridge_device_id"
        private const val LEGACY_CDN_ALLOW_LINKS = "cdn_allow_links"
        private const val CDN_SEND_MODE = "cdn_send_mode"
        private const val CDN_THRESHOLD_MB = "cdn_threshold_mb"
        private const val CDN_ASK_COMPRESS = "cdn_ask_compress"
        private const val CDN_UPLOAD_LIMIT_MB = "cdn_upload_limit_mb"
        private const val CDN_EXPIRY_DAYS = "cdn_expiry_days"
        private const val CDN_ASK_EXPIRY = "cdn_ask_expiry"
        private const val CONTROL_ENABLED = "control_enabled"
        private const val CONTROL_TOKEN = "control_token"
        private const val PROVISIONED_AT = "provisioned_at"
        private const val TOKEN_RANDOM_BYTES = 16

        fun newInstance(context: Context) = BridgeConfig(context)
    }

    var bridgeEnabled: Boolean
        get() = prefs.getBoolean(BRIDGE_ENABLED, false)
        set(enabled) = prefs.edit().putBoolean(BRIDGE_ENABLED, enabled).apply()

    var bridgePort: Int
        get() = prefs.getInt(BRIDGE_PORT, DEFAULT_PORT)
        set(port) = prefs.edit().putInt(BRIDGE_PORT, port).apply()

    var bridgeToken: String
        get() = prefs.getString(BRIDGE_TOKEN, "").orEmpty()
        set(token) = prefs.edit().putString(BRIDGE_TOKEN, token).apply()

    /**
     * Stable device identifier reported to the bridge.
     *
     * The bridge derives portal IDs from this value, so it must not change
     * after pairing. When nothing is stored yet, a random id is generated once
     * and persisted; it is never derived from a hardware identifier (no adb
     * serial, no `ANDROID_ID`). A value that was already stored - including one
     * written by hand in an older deployment - is returned unchanged.
     */
    var bridgeDeviceId: String
        get() {
            val stored = prefs.getString(BRIDGE_DEVICE_ID, "").orEmpty().trim()
            if (stored.isNotEmpty()) {
                return stored
            }
            return getOrCreateDeviceId()
        }
        set(id) = prefs.edit().putString(BRIDGE_DEVICE_ID, id).apply()

    /**
     * Enables the token-gated control intents. Off by default, so a stock
     * install is not remotely drivable.
     */
    var controlEnabled: Boolean
        get() = prefs.getBoolean(CONTROL_ENABLED, false)
        set(enabled) = prefs.edit().putBoolean(CONTROL_ENABLED, enabled).apply()

    /**
     * Token that authorizes control intents; an empty value means "no token
     * configured" (holders of `android.permission.DUMP` still pass).
     */
    var controlToken: String
        get() = prefs.getString(CONTROL_TOKEN, "").orEmpty()
        set(token) = prefs.edit().putString(CONTROL_TOKEN, token).apply()

    /**
     * Timestamp of the last successful `control.PROVISION` (0 = never
     * provisioned). Zero-touch installs set it so a device can be told apart
     * from a hand-configured one; see `docs/bridge-mode.md`.
     */
    var provisionedAt: Long
        get() = prefs.getLong(PROVISIONED_AT, 0L)
        set(timestamp) = prefs.edit().putLong(PROVISIONED_AT, timestamp).apply()

    /** True once a `control.PROVISION` applied a config. */
    val isProvisioned: Boolean
        get() = provisionedAt > 0L

    /**
     * True while some bridge transport is active: the static-token agent mode
     * (`bridge_enabled`) or a paired device (a stored pairing token).
     */
    fun isActive(): Boolean {
        return bridgeEnabled || PairingStore.newInstance(context).isPaired
    }

    /**
     * What happens to attachments above [cdnThresholdMb]: Encrypted link
     * (the default for fresh installs), ask first, or never offer a link.
     *
     * Migrates the legacy `cdn_allow_links` boolean once: a checked switch
     * kept the ask dialog ([CdnSendMode.ASK]), an unchecked one never offered
     * links ([CdnSendMode.NEVER]), so an existing install behaves as before.
     */
    internal var cdnSendMode: CdnSendMode
        get() {
            val stored = prefs.getString(CDN_SEND_MODE, null)
            val legacy = if (prefs.contains(LEGACY_CDN_ALLOW_LINKS)) {
                prefs.getBoolean(LEGACY_CDN_ALLOW_LINKS, false)
            } else {
                null
            }
            val mode = CdnSettings.resolveMode(stored, legacy)
            if (stored == null && legacy != null) {
                prefs.edit()
                    .putString(CDN_SEND_MODE, mode.preferenceValue)
                    .remove(LEGACY_CDN_ALLOW_LINKS)
                    .apply()
            }
            return mode
        }
        set(mode) = prefs.edit().putString(CDN_SEND_MODE, mode.preferenceValue).apply()

    /**
     * Files above this size in MB take [cdnSendMode]; [CdnThreshold.DEFAULT_MB]
     * (0) applies the mode to any attachment, [CdnThreshold.AUTO_MB] follows
     * the carrier MMS limit, clamped on read. Inert until the bridge advertises
     * a `cdn_base_url` over a paired device token.
     */
    internal var cdnThresholdMb: Int
        get() = CdnThreshold.coerceMb(prefs.getInt(CDN_THRESHOLD_MB, CdnThreshold.DEFAULT_MB))
        set(mb) = prefs.edit().putInt(CDN_THRESHOLD_MB, CdnThreshold.coerceMb(mb)).apply()

    /** Asks before downscaling an oversized attachment instead of doing it silently. */
    var cdnAskCompress: Boolean
        get() = prefs.getBoolean(CDN_ASK_COMPRESS, CdnSettings.DEFAULT_ASK_COMPRESS)
        set(enabled) = prefs.edit().putBoolean(CDN_ASK_COMPRESS, enabled).apply()

    /**
     * App-side pre-check against the bridge's `max_file_bytes`, clamped on
     * read. A stored value survives whatever the option list becomes; only
     * installs without one get [CdnSettings.DEFAULT_UPLOAD_LIMIT_MB].
     */
    var cdnUploadLimitMb: Int
        get() = CdnSettings.coerceUploadLimitMb(
            prefs.getInt(CDN_UPLOAD_LIMIT_MB, CdnSettings.DEFAULT_UPLOAD_LIMIT_MB)
        )
        set(limit) = prefs.edit().putInt(CDN_UPLOAD_LIMIT_MB, CdnSettings.coerceUploadLimitMb(limit)).apply()

    /** Default link lifetime sent as `?expires=`, clamped to the bridge's 365d cap. */
    var cdnExpiryDays: Int
        get() = CdnExpiry.coerceDays(prefs.getInt(CDN_EXPIRY_DAYS, CdnExpiry.DEFAULT_DAYS))
        set(days) = prefs.edit().putInt(CDN_EXPIRY_DAYS, CdnExpiry.coerceDays(days)).apply()

    /** Asks for the link lifetime on every upload instead of using [cdnExpiryDays]. */
    var cdnAskExpiry: Boolean
        get() = prefs.getBoolean(CDN_ASK_EXPIRY, CdnSettings.DEFAULT_ASK_EXPIRY)
        set(enabled) = prefs.edit().putBoolean(CDN_ASK_EXPIRY, enabled).apply()

    /** Snapshot of the file-service settings used by the send path. */
    internal fun cdnSettings(): CdnSettings = CdnSettings(
        mode = cdnSendMode,
        thresholdMb = cdnThresholdMb,
        askCompress = cdnAskCompress,
        uploadLimitMb = cdnUploadLimitMb,
        expiryDays = cdnExpiryDays,
        askExpiry = cdnAskExpiry,
    ).coerce()

    /** Returns the stored bearer token, generating and persisting a 32 hex char token on first use. */
    @Synchronized
    fun getOrCreateToken(): String {
        val existing = bridgeToken
        if (existing.isNotEmpty()) {
            return existing
        }

        val bytes = ByteArray(TOKEN_RANDOM_BYTES)
        SecureRandom().nextBytes(bytes)
        val token = bytes.joinToString("") { "%02x".format(it) }
        bridgeToken = token
        return token
    }

    /**
     * Generates and persists the random device id on first use.
     *
     * Ids generated by older app versions live in [PairingStore.pairedDeviceId];
     * they are adopted as-is so an existing deployment keeps its bridge-side
     * identity.
     */
    @Synchronized
    private fun getOrCreateDeviceId(): String {
        val stored = prefs.getString(BRIDGE_DEVICE_ID, "").orEmpty().trim()
        if (stored.isNotEmpty()) {
            return stored
        }

        val paired = PairingStore.newInstance(context).pairedDeviceId.trim()
        if (paired.isNotEmpty()) {
            prefs.edit().putString(BRIDGE_DEVICE_ID, paired).apply()
            return paired
        }

        val generated = "android-" + UUID.randomUUID().toString().replace("-", "")
        prefs.edit().putString(BRIDGE_DEVICE_ID, generated).apply()
        return generated
    }
}
