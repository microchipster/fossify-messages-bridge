/*
 * SPDX-FileCopyrightText: 2026 The Fossify Messages bridge contributors
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.fossify.messages.bridge

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Resolves the bridge base URL advertised by a homeserver's
 * `/.well-known/matrix/client` document. Discovery is optional: any network
 * or payload failure simply means "no bridge advertised".
 */
object PairingDiscovery {
    private const val TAG = "PairingDiscovery"

    // Bridge discovery keys look like `chat.<namespace>.bridge.sms_mms`; any
    // namespace is accepted so no single deployment name is hard-coded.
    private const val WELL_KNOWN_KEY_PREFIX = "chat."
    private const val WELL_KNOWN_KEY_SUFFIX = ".bridge.sms_mms"
    private const val DISCOVERY_TIMEOUT_MS = 15_000
    private const val PROTOCOL_VERSION = PairingClient.PROTOCOL_VERSION

    private val json = Json {
        encodeDefaults = false
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    /**
     * Resolves the bridge base URL from the `{hs}/.well-known/matrix/client`
     * entries advertised as `chat.<namespace>.bridge.sms_mms`. Returns null
     * when discovery fails; a manual URL always takes precedence over this.
     */
    fun discoverBridgeBaseUrl(homeserverUrl: String): String? {
        val hsUrl = PairingHttp.normalizeUrl(homeserverUrl) ?: return null
        val url = decodeWellKnown(fetchWellKnown(hsUrl)) ?: return null
        return PairingHttp.normalizeUrl(url)
    }

    // Discovery is optional: any network or payload failure simply hides the auto-detection.
    @Suppress("TooGenericExceptionCaught")
    private fun fetchWellKnown(hsUrl: String): String? {
        return try {
            PairingHttp.requestJson(
                url = PairingHttp.endpoint(hsUrl, "/.well-known/matrix/client"),
                method = "GET",
                readTimeoutMs = DISCOVERY_TIMEOUT_MS,
            )
        } catch (e: Exception) {
            Log.w(TAG, "bridge URL discovery failed", e)
            null
        }
    }

    // A malformed well-known document just means "no bridge advertised"; never throw.
    @Suppress("TooGenericExceptionCaught")
    private fun decodeWellKnown(body: String?): String? {
        if (body == null) {
            return null
        }

        val root = try {
            json.parseToJsonElement(body) as? JsonObject
        } catch (e: Exception) {
            Log.w(TAG, "well-known payload is not valid JSON", e)
            null
        } ?: return null

        // Deployments advertise themselves under a `chat.<namespace>.bridge.sms_mms`
        // key; accept any namespace instead of hard-coding a single deployment.
        for ((key, value) in root) {
            if (!key.startsWith(WELL_KNOWN_KEY_PREFIX) || !key.endsWith(WELL_KNOWN_KEY_SUFFIX)) {
                continue
            }
            val entry = value as? JsonObject
            val url = ((entry?.get("url") as? JsonPrimitive)?.contentOrNull).orEmpty().trim()
            val version = (entry?.get("protocol_version") as? JsonPrimitive)?.intOrNull
            if (url.isNotEmpty() && (version == null || version == PROTOCOL_VERSION)) {
                return url
            }
        }
        Log.w(TAG, "well-known does not advertise a usable v$PROTOCOL_VERSION bridge URL")
        return null
    }
}
