/*
 * SPDX-FileCopyrightText: 2026 microchipster
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.fossify.messages.control

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.serializer
import java.security.MessageDigest

/** One control command, identified by the broadcast action it is sent with. */
enum class ControlCommand(val commandName: String) {
    STATUS("STATUS"),
    CONFIG("CONFIG"),
    PAIR("PAIR"),
    UNPAIR("UNPAIR"),
    SEND("SEND"),
    SYNC("SYNC"),
    PROVISION("PROVISION");

    val action: String
        get() = ControlContract.ACTION_PREFIX + commandName

    companion object {
        fun fromAction(action: String?): ControlCommand? {
            return entries.firstOrNull { it.action == action }
        }
    }
}

/**
 * Wire contract of the token-gated control intents, see `docs/bridge-mode.md`.
 *
 * Everything here is pure Kotlin: the broadcast plumbing and the Android
 * state it reads live in [ControlReceiver], so the JSON shapes, the
 * authorization rule and the payload parsing stay unit-testable on the JVM.
 */
// One cohesive wire contract (constants, parsing, encoding), split only by what the tests need.
@Suppress("TooManyFunctions")
object ControlContract {
    const val ACTION_PREFIX = "org.fossify.messages.bridge.control."

    const val EXTRA_CONTROL_TOKEN = "control_token"
    const val EXTRA_CONFIG = "config"
    const val EXTRA_PAIR = "pair"
    const val EXTRA_TO = "to"
    const val EXTRA_TEXT = "text"
    const val EXTRA_FILE = "file"

    const val ERROR_INVALID_TOKEN = "invalid_token"
    const val ERROR_DISABLED = "disabled"
    const val ERROR_INVALID_REQUEST = "invalid_request"
    const val ERROR_NOT_PAIRED = "not_paired"
    const val ERROR_UNAVAILABLE = "unavailable"

    const val MIN_PORT = 1024
    const val MAX_PORT = 65535

    const val FIELD_ERROR = "error"
    const val FIELD_CODE = "code"
    const val FIELD_MESSAGE = "message"
    const val FIELD_ENABLED = "enabled"
    const val FIELD_SERVICE_RUNNING = "service_running"
    const val FIELD_PAIRED = "paired"
    const val FIELD_DEVICE_ID = "device_id"
    const val FIELD_USER_ID = "user_id"
    const val FIELD_HS_URL = "hs_url"
    const val FIELD_BRIDGE_BASE_URL = "bridge_base_url"
    const val FIELD_BRIDGE_PORT = "bridge_port"
    const val FIELD_DEFAULT_SMS_ROLE = "default_sms_role"
    const val FIELD_PERMISSIONS = "permissions"
    const val FIELD_LAST_HEARTBEAT_MS = "last_heartbeat_ms"
    const val FIELD_QUEUE_CURSOR = "queue_cursor"
    const val FIELD_EVENT_CURSOR = "event_cursor"
    const val FIELD_OUTBOX_SMS = "outbox_sms"
    const val FIELD_OUTBOX_MMS = "outbox_mms"
    const val FIELD_VERSION_NAME = "version_name"
    const val FIELD_VERSION_CODE = "version_code"
    const val FIELD_BRIDGE_ENABLED = "bridge_enabled"
    const val FIELD_BRIDGE_DEVICE_ID = "bridge_device_id"
    const val FIELD_BRIDGE_TOKEN = "bridge_token"
    const val FIELD_BRIDGE_TOKEN_SET = "bridge_token_set"
    const val FIELD_BRIDGE_HS_URL = "bridge_hs_url"
    const val FIELD_CONTROL_ENABLED = "control_enabled"
    const val FIELD_CONTROL_TOKEN = "control_token"
    const val FIELD_CONTROL_TOKEN_SET = "control_token_set"
    const val FIELD_MESSAGE_ID = "message_id"
    const val FIELD_THREAD_ID = "thread_id"
    const val FIELD_PENDING = "pending"
    const val FIELD_MODE = "mode"
    const val FIELD_PAIR_HS = "hs"
    const val FIELD_PAIR_BRIDGE = "b"
    const val FIELD_PROVISIONED = "provisioned"
    const val FIELD_PROVISIONED_AT_MS = "provisioned_at_ms"
    const val FIELD_APPLIED = "applied"
    const val FIELD_SEND_LONG_MESSAGE_MMS = "send_long_message_mms"
    const val FIELD_SEND_GROUP_MESSAGE_MMS = "send_group_message_mms"

    /** The configuration keys `control.CONFIG` accepts; anything else is rejected. */
    val CONFIG_KEYS = setOf(
        FIELD_BRIDGE_ENABLED,
        FIELD_BRIDGE_PORT,
        FIELD_BRIDGE_DEVICE_ID,
        FIELD_BRIDGE_TOKEN,
        FIELD_BRIDGE_HS_URL,
        FIELD_BRIDGE_BASE_URL,
        FIELD_CONTROL_ENABLED,
        FIELD_CONTROL_TOKEN,
    )

    /**
     * The bootstrap keys `control.PROVISION` accepts; anything else is
     * rejected. The static bridge token is deliberately absent - provisioning
     * sets the control surface, and a bridge token can be applied later with
     * `control.CONFIG`. The two message-format preferences are included so a
     * scripted install can decide between concatenated SMS (default) and MMS
     * without a UI round-trip (see `docs/mms-interop.md`).
     */
    val PROVISION_KEYS = setOf(
        FIELD_BRIDGE_ENABLED,
        FIELD_BRIDGE_PORT,
        FIELD_BRIDGE_DEVICE_ID,
        FIELD_BRIDGE_HS_URL,
        FIELD_BRIDGE_BASE_URL,
        FIELD_CONTROL_ENABLED,
        FIELD_CONTROL_TOKEN,
        FIELD_SEND_LONG_MESSAGE_MMS,
        FIELD_SEND_GROUP_MESSAGE_MMS,
    )

    private val BOOLEAN_PAYLOAD_KEYS = setOf(
        FIELD_BRIDGE_ENABLED,
        FIELD_CONTROL_ENABLED,
        FIELD_SEND_LONG_MESSAGE_MMS,
        FIELD_SEND_GROUP_MESSAGE_MMS,
    )
    private val STRING_PAYLOAD_KEYS = setOf(
        FIELD_BRIDGE_DEVICE_ID,
        FIELD_BRIDGE_TOKEN,
        FIELD_BRIDGE_HS_URL,
        FIELD_BRIDGE_BASE_URL,
        FIELD_CONTROL_TOKEN,
    )

    private val json = Json {
        encodeDefaults = false
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    /**
     * True when the caller presented the stored control token, or holds
     * `android.permission.DUMP` (which `adb shell` does).
     */
    fun isAuthorized(presentedToken: String, storedToken: String, hasDumpPermission: Boolean): Boolean {
        if (hasDumpPermission) {
            return true
        }
        if (presentedToken.isEmpty() || storedToken.isEmpty()) {
            return false
        }
        return MessageDigest.isEqual(
            presentedToken.toByteArray(Charsets.UTF_8),
            storedToken.toByteArray(Charsets.UTF_8),
        )
    }

    /** Splits a comma-separated recipient list, trimming and dropping empty entries. */
    fun parseRecipients(raw: String?): List<String> {
        return raw.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }

    /** Parses a control JSON payload; null when it is absent or not a JSON object. */
    @Suppress("SwallowedException")
    fun objectOrNull(raw: String?): JsonObject? {
        if (raw.isNullOrBlank()) {
            return null
        }
        return try {
            json.parseToJsonElement(raw) as? JsonObject
        } catch (_: Exception) {
            null
        }
    }

    /** Strict string reader: a JSON string only, numbers or booleans are rejected. */
    fun stringValue(payload: JsonObject, key: String): String? {
        val primitive = payload[key] as? JsonPrimitive ?: return null
        if (!primitive.isString) {
            return null
        }
        return primitive.content
    }

    /** Strict boolean reader: a JSON boolean only. */
    fun booleanValue(payload: JsonObject, key: String): Boolean? {
        val primitive = payload[key] as? JsonPrimitive ?: return null
        if (primitive.isString) {
            return null
        }
        return primitive.booleanOrNull
    }

    /** Strict integer reader: a JSON number only. */
    fun intValue(payload: JsonObject, key: String): Int? {
        val primitive = payload[key] as? JsonPrimitive ?: return null
        if (primitive.isString) {
            return null
        }
        return primitive.intOrNull
    }

    /** Returns the first validation problem of a `control.CONFIG` payload, or null when it is valid. */
    fun validateConfig(payload: JsonObject): String? = validatePayload(payload, CONFIG_KEYS)

    /** Returns the first validation problem of a `control.PROVISION` payload, or null when it is valid. */
    fun validateProvision(payload: JsonObject): String? = validatePayload(payload, PROVISION_KEYS)

    @Suppress("ReturnCount")
    private fun validatePayload(payload: JsonObject, allowedKeys: Set<String>): String? {
        val unknown = payload.keys.firstOrNull { it !in allowedKeys }
        if (unknown != null) {
            return "unknown config key '$unknown'"
        }
        for (key in BOOLEAN_PAYLOAD_KEYS) {
            if (payload.containsKey(key) && booleanValue(payload, key) == null) {
                return "config key '$key' must be a boolean"
            }
        }
        for (key in STRING_PAYLOAD_KEYS) {
            if (payload.containsKey(key) && stringValue(payload, key) == null) {
                return "config key '$key' must be a string"
            }
        }
        if (payload.containsKey(FIELD_BRIDGE_PORT)) {
            val port = intValue(payload, FIELD_BRIDGE_PORT)
                ?: return "config key '$FIELD_BRIDGE_PORT' must be a number"
            if (port < MIN_PORT || port > MAX_PORT) {
                return "config key '$FIELD_BRIDGE_PORT' must be between $MIN_PORT and $MAX_PORT"
            }
        }
        return null
    }

    /** Maps a `BridgeSendException` code onto the control error code space. */
    fun errorCodeForSendFailure(code: String): String {
        return when (code) {
            "unsupported", "too_large", "too_long" -> ERROR_INVALID_REQUEST
            else -> ERROR_UNAVAILABLE
        }
    }

    /** Maps a `PairingException` code onto the control error code space. */
    fun errorCodeForPairingFailure(code: String): String {
        return if (code == "bad_request") ERROR_INVALID_REQUEST else ERROR_UNAVAILABLE
    }

    fun errorJson(code: String, message: String): String {
        return encode(ControlErrorPayload(error = ControlErrorDetail(code = code, message = message)))
    }

    fun statusJson(status: ControlStatusPayload): String = encode(status)

    fun configJson(config: ControlConfigPayload): String = encode(config)

    fun provisionJson(result: ControlProvisionPayload): String = encode(result)

    fun pairJson(result: ControlPairPayload): String = encode(result)

    fun unpairJson(): String = encode(ControlUnpairPayload(paired = false))

    fun sendJson(result: ControlSendPayload): String = encode(result)

    fun syncJson(result: ControlSyncPayload): String = encode(result)

    private inline fun <reified T> encode(value: T): String {
        return json.encodeToString(serializer<T>(), value)
    }
}

@Serializable
private data class ControlErrorPayload(
    @SerialName(ControlContract.FIELD_ERROR) val error: ControlErrorDetail,
)

@Serializable
private data class ControlErrorDetail(
    @SerialName(ControlContract.FIELD_CODE) val code: String,
    @SerialName(ControlContract.FIELD_MESSAGE) val message: String,
)

/** `control.STATUS` result; never contains the bridge token or any message content. */
@Serializable
data class ControlStatusPayload(
    @SerialName(ControlContract.FIELD_ENABLED) val enabled: Boolean,
    @SerialName(ControlContract.FIELD_SERVICE_RUNNING) val serviceRunning: Boolean,
    @SerialName(ControlContract.FIELD_PAIRED) val paired: Boolean,
    @SerialName(ControlContract.FIELD_DEVICE_ID) val deviceId: String,
    @SerialName(ControlContract.FIELD_USER_ID) val userId: String,
    @SerialName(ControlContract.FIELD_HS_URL) val hsUrl: String,
    @SerialName(ControlContract.FIELD_BRIDGE_BASE_URL) val bridgeBaseUrl: String,
    @SerialName(ControlContract.FIELD_BRIDGE_PORT) val bridgePort: Int,
    @SerialName(ControlContract.FIELD_DEFAULT_SMS_ROLE) val defaultSmsRole: Boolean,
    @SerialName(ControlContract.FIELD_PERMISSIONS) val permissions: Map<String, Boolean>,
    @SerialName(ControlContract.FIELD_LAST_HEARTBEAT_MS) val lastHeartbeatMs: Long,
    @SerialName(ControlContract.FIELD_QUEUE_CURSOR) val queueCursor: Long,
    @SerialName(ControlContract.FIELD_EVENT_CURSOR) val eventCursor: Long,
    @SerialName(ControlContract.FIELD_OUTBOX_SMS) val outboxSms: Int,
    @SerialName(ControlContract.FIELD_OUTBOX_MMS) val outboxMms: Int,
    @SerialName(ControlContract.FIELD_VERSION_NAME) val versionName: String,
    @SerialName(ControlContract.FIELD_VERSION_CODE) val versionCode: Int,
)

/** `control.CONFIG` result: only the preferences that were part of the payload; nulls are omitted. */
@Serializable
data class ControlConfigPayload(
    @SerialName(ControlContract.FIELD_BRIDGE_ENABLED) val bridgeEnabled: Boolean? = null,
    @SerialName(ControlContract.FIELD_BRIDGE_PORT) val bridgePort: Int? = null,
    @SerialName(ControlContract.FIELD_BRIDGE_DEVICE_ID) val bridgeDeviceId: String? = null,
    @SerialName(ControlContract.FIELD_BRIDGE_TOKEN_SET) val bridgeTokenSet: Boolean? = null,
    @SerialName(ControlContract.FIELD_BRIDGE_HS_URL) val bridgeHsUrl: String? = null,
    @SerialName(ControlContract.FIELD_BRIDGE_BASE_URL) val bridgeBaseUrl: String? = null,
    @SerialName(ControlContract.FIELD_CONTROL_ENABLED) val controlEnabled: Boolean? = null,
    @SerialName(ControlContract.FIELD_CONTROL_TOKEN_SET) val controlTokenSet: Boolean? = null,
)

/** `control.PROVISION` result: the bootstrap applied its config and marked the device provisioned. */
@Serializable
data class ControlProvisionPayload(
    @SerialName(ControlContract.FIELD_PROVISIONED) val provisioned: Boolean,
    @SerialName(ControlContract.FIELD_PROVISIONED_AT_MS) val provisionedAtMs: Long,
    @SerialName(ControlContract.FIELD_APPLIED) val applied: List<String>,
)

/** `control.PAIR` result. */
@Serializable
data class ControlPairPayload(
    @SerialName(ControlContract.FIELD_PAIRED) val paired: Boolean,
    @SerialName(ControlContract.FIELD_DEVICE_ID) val deviceId: String,
    @SerialName(ControlContract.FIELD_USER_ID) val userId: String,
    @SerialName(ControlContract.FIELD_HS_URL) val hsUrl: String,
)

@Serializable
private data class ControlUnpairPayload(
    @SerialName(ControlContract.FIELD_PAIRED) val paired: Boolean,
)

/** `control.SEND` result; `messageId` is the raw provider row id (0 while it is still unknown). */
@Serializable
data class ControlSendPayload(
    @SerialName(ControlContract.FIELD_MESSAGE_ID) val messageId: Long,
    @SerialName(ControlContract.FIELD_THREAD_ID) val threadId: Long,
    @SerialName(ControlContract.FIELD_PENDING) val pending: Boolean,
    @SerialName(ControlContract.FIELD_MODE) val mode: String,
)

/** `control.SYNC` result: the cursor subset of `control.STATUS`. */
@Serializable
data class ControlSyncPayload(
    @SerialName(ControlContract.FIELD_QUEUE_CURSOR) val queueCursor: Long,
    @SerialName(ControlContract.FIELD_EVENT_CURSOR) val eventCursor: Long,
)
