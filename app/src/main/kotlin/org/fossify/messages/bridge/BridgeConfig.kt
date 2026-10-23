/*
 * SPDX-FileCopyrightText: 2026 microchipster
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.fossify.messages.bridge

import android.content.Context
import org.fossify.commons.helpers.BaseConfig
import java.security.SecureRandom

/**
 * Preferences of the bridge mode.
 *
 * They intentionally live in the same SharedPreferences file as the rest of
 * the app config, so a single adb (or future in-app settings screen) write can
 * toggle the whole feature.
 */
class BridgeConfig(context: Context) : BaseConfig(context) {

    companion object {
        const val DEFAULT_PORT = 8391

        private const val BRIDGE_ENABLED = "bridge_enabled"
        private const val BRIDGE_TOKEN = "bridge_token"
        private const val BRIDGE_PORT = "bridge_port"
        private const val BRIDGE_DEVICE_ID = "bridge_device_id"

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
     * Stable device identifier reported over the bridge API.
     *
     * The bridge derives portal IDs from this value, so it must stay stable and
     * should match the identifier used by the adb agent (the adb serial) when
     * the same phone is bridged through both transports, otherwise the two
     * modes would create separate Matrix rooms.
     */
    var bridgeDeviceId: String
        get() = prefs.getString(BRIDGE_DEVICE_ID, "").orEmpty()
        set(id) = prefs.edit().putString(BRIDGE_DEVICE_ID, id).apply()

    /**
     * True while some bridge transport is active: the static-token agent mode
     * (`bridge_enabled`) or a paired device (a stored pairing token).
     */
    fun isActive(): Boolean {
        return bridgeEnabled || PairingStore.newInstance(context).isPaired
    }

    /** Returns the stored bearer token, generating and persisting a 32 hex char token on first use. */
    @Synchronized
    fun getOrCreateToken(): String {
        val existing = bridgeToken
        if (existing.isNotEmpty()) {
            return existing
        }

        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        val token = bytes.joinToString("") { "%02x".format(it) }
        bridgeToken = token
        return token
    }
}
