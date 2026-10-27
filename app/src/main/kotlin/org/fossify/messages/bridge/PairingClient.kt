/*
 * SPDX-FileCopyrightText: 2026 microchipster
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.fossify.messages.bridge

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.serializer
import org.fossify.messages.BuildConfig

/** A completed pairing, as reported by `POST /_smsmms/v1/pair/complete`. */
data class PairingResult(
    val deviceId: String,
    val user: String,
    val homeserverUrl: String,
    val bridgeBaseUrl: String,
)

/**
 * An error returned by the pairing API (`{"error": code, "message": text}`) or
 * raised locally before a request could be made.
 */
class PairingException(val code: String, val status: Int = 0, override val message: String) : Exception(message)

/**
 * Client of the bridge pairing protocol v1.
 *
 * The protocol and its exact JSON shapes are specified in
 * `docs/PAIRING_PROTOCOL.md` of the Matrix bridge repository. Transport,
 * URL handling and the Keystore device key live in [PairingHttp] and
 * [PairingKeys].
 */
object PairingClient {
    const val PROTOCOL_VERSION = 1
    const val PAIRING_SCHEME = "smsmms"
    const val PAIRING_HOST = "pair"

    private const val TAG = "PairingClient"

    // Bridge discovery keys look like `chat.<namespace>.bridge.sms_mms`; any
    // namespace is accepted so no single deployment name is hard-coded.
    private const val WELL_KNOWN_KEY_PREFIX = "chat."
    private const val WELL_KNOWN_KEY_SUFFIX = ".bridge.sms_mms"
    private const val DISCOVERY_TIMEOUT_MS = 15_000

    private val json = Json {
        encodeDefaults = false
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    @Serializable
    private data class PairingDevice(
        val id: String,
        val name: String = "",
        val platform: String = "android",
        @SerialName("app_version") val appVersion: String = "",
    )

    @Serializable
    private data class PairingInfoResponse(
        @SerialName("protocol_version") val protocolVersion: Int = 0,
        @SerialName("bridge_name") val bridgeName: String = "",
        @SerialName("homeserver_url") val homeserverUrl: String = "",
        @SerialName("pairing_enabled") val pairingEnabled: Boolean = true,
    )

    @Serializable
    private data class PairingStartRequest(
        val code: String,
        @SerialName("hs_url") val hsUrl: String,
        val device: PairingDevice,
        val pubkey: String,
        @SerialName("client_nonce") val clientNonce: String,
    )

    @Serializable
    private data class PairingStartResponse(
        @SerialName("pairing_id") val pairingId: String = "",
        @SerialName("server_nonce") val serverNonce: String = "",
        @SerialName("expires_at_ms") val expiresAtMs: Long = 0,
    )

    @Serializable
    private data class PairingCompleteRequest(
        @SerialName("pairing_id") val pairingId: String,
        val signature: String,
    )

    @Serializable
    private data class PairingUser(
        @SerialName("mxid") val mxid: String = "",
        @SerialName("displayname") val displayname: String = "",
    )

    @Serializable
    private data class PairingBridge(
        @SerialName("base_url") val baseUrl: String = "",
        @SerialName("protocol_version") val protocolVersion: Int = 0,
    )

    @Serializable
    private data class PairingCompleteResponse(
        @SerialName("device_id") val deviceId: String = "",
        val token: String = "",
        val user: PairingUser? = null,
        @SerialName("homeserver_url") val homeserverUrl: String = "",
        val bridge: PairingBridge? = null,
    )

    /**
     * Runs the whole pairing flow: discovery, `/info` sanity check,
     * `/pair/start`, Keystore signature over the challenge, `/pair/complete`
     * and persistence of the returned token.
     *
     * Blocking; call it from a background thread. Throws [PairingException].
     */
    fun pair(context: Context, code: String, homeserverUrl: String, bridgeUrlOverride: String = ""): PairingResult {
        val pairingCode = code.trim().uppercase()
        if (pairingCode.isEmpty()) {
            fail("bad_request", "missing pairing code")
        }
        val hsUrl = PairingHttp.normalizeUrl(homeserverUrl)
            ?: fail("bad_request", "missing or invalid homeserver URL")
        val bridgeBaseUrl = resolveBridgeBaseUrl(hsUrl, bridgeUrlOverride)

        checkBridgeInfo(bridgeBaseUrl, hsUrl)

        val deviceId = deviceId(context)
        val clientNonce = PairingKeys.randomNonce()
        val startRequest = PairingStartRequest(
            code = pairingCode,
            hsUrl = hsUrl,
            device = PairingDevice(id = deviceId, name = deviceName(), appVersion = BuildConfig.VERSION_NAME),
            pubkey = PairingKeys.publicKeyBase64Url(),
            clientNonce = clientNonce,
        )
        val start = decode(
            PairingHttp.requestJson(
                url = PairingHttp.endpoint(bridgeBaseUrl, "/_smsmms/v1/pair/start"),
                method = "POST",
                body = json.encodeToString(serializer<PairingStartRequest>(), startRequest),
            ),
            serializer<PairingStartResponse>(),
        )
        if (start.pairingId.isEmpty() || start.serverNonce.isEmpty()) {
            fail("internal", "bridge did not return a pairing session")
        }

        val signingInput = "smsmms-pair-v1|${start.pairingId}|${start.serverNonce}|$clientNonce|$deviceId"
        val complete = decode(
            PairingHttp.requestJson(
                url = PairingHttp.endpoint(bridgeBaseUrl, "/_smsmms/v1/pair/complete"),
                method = "POST",
                body = json.encodeToString(
                    serializer<PairingCompleteRequest>(),
                    PairingCompleteRequest(pairingId = start.pairingId, signature = PairingKeys.sign(signingInput)),
                ),
            ),
            serializer<PairingCompleteResponse>(),
        )
        if (complete.token.isEmpty()) {
            fail("internal", "bridge did not return a device token")
        }

        val resolvedBridgeUrl = complete.bridge?.baseUrl?.takeIf { it.isNotBlank() } ?: bridgeBaseUrl
        val resolvedHsUrl = complete.homeserverUrl.ifBlank { hsUrl }
        val resolvedDeviceId = complete.deviceId.ifBlank { deviceId }
        val user = complete.user?.mxid.orEmpty()

        PairingStore.newInstance(context).savePairing(
            homeserverUrl = PairingHttp.normalizeUrl(resolvedHsUrl) ?: resolvedHsUrl,
            bridgeBaseUrl = PairingHttp.normalizeUrl(resolvedBridgeUrl) ?: resolvedBridgeUrl,
            deviceId = resolvedDeviceId,
            user = user,
            token = complete.token,
        )
        Log.i(TAG, "paired as ${user.ifEmpty { "unknown user" }} (device $resolvedDeviceId) via $resolvedBridgeUrl")
        return PairingResult(
            deviceId = resolvedDeviceId,
            user = user,
            homeserverUrl = resolvedHsUrl,
            bridgeBaseUrl = resolvedBridgeUrl,
        )
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

    /**
     * Stable device id: generated once and persisted by [BridgeConfig] as a
     * random value, never derived from a hardware identifier.
     */
    fun deviceId(context: Context): String {
        return BridgeConfig.newInstance(context).bridgeDeviceId
    }

    private fun resolveBridgeBaseUrl(homeserverUrl: String, bridgeUrlOverride: String): String {
        val override = PairingHttp.normalizeUrl(bridgeUrlOverride.trim())
        if (override != null) {
            return override
        }
        val discovered = discoverBridgeBaseUrl(homeserverUrl)
        if (discovered.isNullOrEmpty()) {
            fail("not_found", "cannot discover the bridge URL, enter it manually")
        }
        return discovered
    }

    private fun checkBridgeInfo(bridgeBaseUrl: String, homeserverUrl: String) {
        val info = decode(
            PairingHttp.requestJson(PairingHttp.endpoint(bridgeBaseUrl, "/_smsmms/v1/info"), "GET"),
            serializer<PairingInfoResponse>(),
        )
        if (info.protocolVersion != PROTOCOL_VERSION) {
            fail("unsupported", "bridge speaks protocol ${info.protocolVersion}, app speaks $PROTOCOL_VERSION")
        }
        if (!info.pairingEnabled) {
            fail("unsupported", "pairing is disabled on the bridge")
        }
        val bridgeHomeserver = PairingHttp.normalizeUrl(info.homeserverUrl)
        val payloadHomeserver = PairingHttp.normalizeUrl(homeserverUrl)
        if (info.homeserverUrl.isNotBlank() && bridgeHomeserver != payloadHomeserver) {
            Log.w(TAG, "bridge reports a different homeserver (${info.homeserverUrl}) than the payload")
        }
    }

    // Any decode failure must become a generic PairingException, not leak serializer internals.
    @Suppress("TooGenericExceptionCaught")
    private fun <T> decode(body: String, format: kotlinx.serialization.KSerializer<T>): T {
        if (body.isEmpty()) {
            fail("internal", "bridge returned an empty response")
        }
        return try {
            json.decodeFromString(format, body)
        } catch (e: Exception) {
            Log.e(TAG, "failed to parse a bridge response", e)
            fail("internal", "bridge returned an unexpected response")
        }
    }

    private fun deviceName(): String {
        val model = Build.MODEL.orEmpty().trim()
        return model.ifEmpty { "Android device" }
    }

    private fun fail(code: String, message: String): Nothing {
        throw PairingException(code, 0, message)
    }
}
