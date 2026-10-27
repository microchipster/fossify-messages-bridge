/*
 * SPDX-FileCopyrightText: 2026 microchipster
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.fossify.messages.control

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Base64
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.content.ContextCompat
import kotlinx.serialization.json.JsonObject
import org.fossify.messages.BuildConfig
import org.fossify.messages.bridge.BridgeConfig
import org.fossify.messages.bridge.BridgeMessageIds
import org.fossify.messages.bridge.BridgeQueries
import org.fossify.messages.bridge.BridgeSendAttachment
import org.fossify.messages.bridge.BridgeSendException
import org.fossify.messages.bridge.BridgeSendRequest
import org.fossify.messages.bridge.BridgeSender
import org.fossify.messages.bridge.BridgeService
import org.fossify.messages.bridge.PairingClient
import org.fossify.messages.bridge.PairingException
import org.fossify.messages.bridge.PairingOutbox
import org.fossify.messages.bridge.PairingPoller
import org.fossify.messages.bridge.PairingStore
import org.fossify.messages.messaging.isLongMmsMessage
import java.io.File

/**
 * Ordered broadcast receiver of the token-gated control intents (see `docs/bridge-mode.md`).
 *
 * It answers with a single-line JSON object through `setResultData(...)` and
 * stays inert unless the `control_enabled` preference is true and the sender
 * passed the control token or holds `android.permission.DUMP`. Logs never
 * contain message bodies or tokens.
 */
// One broadcast entry point with per-command handlers; every command failure must become a JSON error.
@Suppress("TooManyFunctions", "TooGenericExceptionCaught")
class ControlReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "ControlReceiver"
        private const val DEFAULT_CONTENT_TYPE = "application/octet-stream"
        private const val THREAD_NAME = "ControlReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        // The sender's identity is only available while the broadcast is being delivered.
        val config = BridgeConfig.newInstance(context)
        val authorized = ControlContract.isAuthorized(
            presentedToken = intent.getStringExtra(ControlContract.EXTRA_CONTROL_TOKEN).orEmpty(),
            storedToken = config.controlToken,
            hasDumpPermission = hasDumpPermission(context),
        )
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        Thread({
            val json = try {
                dispatch(appContext, intent, authorized)
            } catch (e: Exception) {
                Log.e(TAG, "control command failed: ${e.javaClass.simpleName}")
                ControlContract.errorJson(ControlContract.ERROR_UNAVAILABLE, "control command failed")
            }
            pendingResult.setResultData(json)
            pendingResult.finish()
        }, THREAD_NAME).start()
    }

    /** True when the broadcast sender (e.g. `adb shell`) holds `android.permission.DUMP`. */
    private fun hasDumpPermission(context: Context): Boolean {
        return try {
            context.checkCallingPermission(Manifest.permission.DUMP) == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            Log.w(TAG, "failed to check the sender permission: ${e.javaClass.simpleName}")
            false
        }
    }

    private fun dispatch(context: Context, intent: Intent, authorized: Boolean): String {
        // Authorization comes first: unauthorized callers learn nothing, not even
        // whether control is enabled or which action names exist.
        if (!authorized) {
            Log.w(TAG, "control request rejected: invalid token")
            return ControlContract.errorJson(ControlContract.ERROR_INVALID_TOKEN, "missing or invalid control token")
        }
        val command = ControlCommand.fromAction(intent.action)
            ?: return ControlContract.errorJson(ControlContract.ERROR_INVALID_REQUEST, "unknown control action")
        // PROVISION is the bootstrap command: a freshly installed device still
        // has control_enabled off and must accept its first config. The
        // authorization check above still applies (DUMP or the stored token).
        if (command != ControlCommand.PROVISION && !BridgeConfig.newInstance(context).controlEnabled) {
            Log.i(TAG, "control ${command.commandName} rejected: disabled")
            return ControlContract.errorJson(ControlContract.ERROR_DISABLED, "control intents are disabled")
        }
        return try {
            when (command) {
                ControlCommand.STATUS -> status(context)
                ControlCommand.CONFIG -> config(context, intent)
                ControlCommand.PAIR -> pair(context, intent)
                ControlCommand.UNPAIR -> unpair(context)
                ControlCommand.SEND -> send(context, intent)
                ControlCommand.SYNC -> sync(context)
                ControlCommand.PROVISION -> provision(context, intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "control ${command.commandName} failed: ${e.javaClass.simpleName}")
            ControlContract.errorJson(ControlContract.ERROR_UNAVAILABLE, e.message ?: "control command failed")
        }
    }

    private fun status(context: Context): String {
        val config = BridgeConfig.newInstance(context)
        val store = PairingStore.newInstance(context)
        val (outboxSms, outboxMms) = PairingOutbox(context, store).pendingMessageCounts()
        val payload = ControlStatusPayload(
            // "enabled" reports the static bridge mode; the control surface itself
            // is implicitly enabled, otherwise this response would be an error.
            enabled = config.bridgeEnabled,
            serviceRunning = BridgeService.isRunning(),
            paired = store.isPaired,
            deviceId = config.bridgeDeviceId,
            userId = store.pairedUser,
            hsUrl = store.homeserverUrl,
            bridgeBaseUrl = store.bridgeBaseUrl,
            bridgePort = config.bridgePort,
            defaultSmsRole = BridgeQueries.isDefaultSmsApp(context),
            permissions = permissions(context),
            lastHeartbeatMs = store.lastHeartbeatMs,
            queueCursor = store.queueCursor,
            eventCursor = store.eventsCursor,
            outboxSms = outboxSms,
            outboxMms = outboxMms,
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
        )
        return ControlContract.statusJson(payload)
    }

    private fun permissions(context: Context): Map<String, Boolean> {
        val hasNotifications = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)
        return mapOf(
            "sms" to hasPermission(context, Manifest.permission.SEND_SMS),
            "post_notifications" to hasNotifications,
            "read_sms" to hasPermission(context, Manifest.permission.READ_SMS),
        )
    }

    private fun hasPermission(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    @Suppress("ReturnCount")
    private fun config(context: Context, intent: Intent): String {
        val payload = ControlContract.objectOrNull(intent.getStringExtra(ControlContract.EXTRA_CONFIG))
            ?: return invalidRequest("config must be a JSON object")
        val problem = ControlContract.validateConfig(payload)
        if (problem != null) {
            return invalidRequest(problem)
        }

        val config = BridgeConfig.newInstance(context)
        val store = PairingStore.newInstance(context)
        applyBoolean(payload, ControlContract.FIELD_BRIDGE_ENABLED) { config.bridgeEnabled = it }
        applyInt(payload, ControlContract.FIELD_BRIDGE_PORT) { config.bridgePort = it }
        applyString(payload, ControlContract.FIELD_BRIDGE_DEVICE_ID) { config.bridgeDeviceId = it }
        applyString(payload, ControlContract.FIELD_BRIDGE_TOKEN) { config.bridgeToken = it }
        applyString(payload, ControlContract.FIELD_BRIDGE_HS_URL) { store.homeserverUrl = it }
        applyString(payload, ControlContract.FIELD_BRIDGE_BASE_URL) { store.bridgeBaseUrl = it }
        applyBoolean(payload, ControlContract.FIELD_CONTROL_ENABLED) { config.controlEnabled = it }
        applyString(payload, ControlContract.FIELD_CONTROL_TOKEN) { config.controlToken = it }

        val serviceAffecting = payload.containsKey(ControlContract.FIELD_BRIDGE_ENABLED) ||
            payload.containsKey(ControlContract.FIELD_BRIDGE_PORT) ||
            payload.containsKey(ControlContract.FIELD_BRIDGE_TOKEN)
        if (serviceAffecting) {
            BridgeService.maybeStart(context)
        }
        Log.i(TAG, "control CONFIG applied keys: ${payload.keys.sorted().joinToString(",")}")

        val bridgeToken = ControlContract.stringValue(payload, ControlContract.FIELD_BRIDGE_TOKEN)
        val controlToken = ControlContract.stringValue(payload, ControlContract.FIELD_CONTROL_TOKEN)
        return ControlContract.configJson(
            ControlConfigPayload(
                bridgeEnabled = ControlContract.booleanValue(payload, ControlContract.FIELD_BRIDGE_ENABLED),
                bridgePort = ControlContract.intValue(payload, ControlContract.FIELD_BRIDGE_PORT),
                bridgeDeviceId = ControlContract.stringValue(payload, ControlContract.FIELD_BRIDGE_DEVICE_ID),
                bridgeTokenSet = bridgeToken?.isNotEmpty(),
                bridgeHsUrl = ControlContract.stringValue(payload, ControlContract.FIELD_BRIDGE_HS_URL),
                bridgeBaseUrl = ControlContract.stringValue(payload, ControlContract.FIELD_BRIDGE_BASE_URL),
                controlEnabled = ControlContract.booleanValue(payload, ControlContract.FIELD_CONTROL_ENABLED),
                controlTokenSet = controlToken?.isNotEmpty(),
            )
        )
    }

    @Suppress("ReturnCount")
    private fun provision(context: Context, intent: Intent): String {
        val payload = ControlContract.objectOrNull(intent.getStringExtra(ControlContract.EXTRA_CONFIG))
            ?: return invalidRequest("config must be a JSON object")
        val problem = ControlContract.validateProvision(payload)
        if (problem != null) {
            return invalidRequest(problem)
        }

        val config = BridgeConfig.newInstance(context)
        val store = PairingStore.newInstance(context)
        applyBoolean(payload, ControlContract.FIELD_BRIDGE_ENABLED) { config.bridgeEnabled = it }
        applyInt(payload, ControlContract.FIELD_BRIDGE_PORT) { config.bridgePort = it }
        applyString(payload, ControlContract.FIELD_BRIDGE_DEVICE_ID) { config.bridgeDeviceId = it }
        applyString(payload, ControlContract.FIELD_BRIDGE_HS_URL) { store.homeserverUrl = it }
        applyString(payload, ControlContract.FIELD_BRIDGE_BASE_URL) { store.bridgeBaseUrl = it }
        applyBoolean(payload, ControlContract.FIELD_CONTROL_ENABLED) { config.controlEnabled = it }
        applyString(payload, ControlContract.FIELD_CONTROL_TOKEN) { config.controlToken = it }
        config.provisionedAt = System.currentTimeMillis()

        BridgeService.maybeStart(context)
        Log.i(TAG, "control PROVISION applied keys: ${payload.keys.sorted().joinToString(",")}")
        return ControlContract.provisionJson(
            ControlProvisionPayload(
                provisioned = true,
                provisionedAtMs = config.provisionedAt,
                applied = payload.keys.sorted(),
            )
        )
    }

    private fun applyBoolean(payload: JsonObject, key: String, apply: (Boolean) -> Unit) {
        val value = ControlContract.booleanValue(payload, key) ?: return
        apply(value)
    }

    private fun applyInt(payload: JsonObject, key: String, apply: (Int) -> Unit) {
        val value = ControlContract.intValue(payload, key) ?: return
        apply(value)
    }

    private fun applyString(payload: JsonObject, key: String, apply: (String) -> Unit) {
        val value = ControlContract.stringValue(payload, key) ?: return
        apply(value)
    }

    @Suppress("ReturnCount")
    private fun pair(context: Context, intent: Intent): String {
        val payload = ControlContract.objectOrNull(intent.getStringExtra(ControlContract.EXTRA_PAIR))
            ?: return invalidRequest("pair must be a JSON object")
        val code = ControlContract.stringValue(payload, ControlContract.FIELD_CODE)
            ?: return invalidRequest("pair needs a 'code' string")
        val hs = ControlContract.stringValue(payload, ControlContract.FIELD_PAIR_HS)
            ?: return invalidRequest("pair needs an 'hs' string")
        val bridgeUrl = ControlContract.stringValue(payload, ControlContract.FIELD_PAIR_BRIDGE).orEmpty()
        val deviceId = ControlContract.stringValue(payload, ControlContract.FIELD_DEVICE_ID).orEmpty()
        if (deviceId.isNotEmpty()) {
            BridgeConfig.newInstance(context).bridgeDeviceId = deviceId
        }

        val result = try {
            PairingClient.pair(context, code, hs, bridgeUrl)
        } catch (e: PairingException) {
            Log.w(TAG, "control PAIR failed: ${e.code}")
            return ControlContract.errorJson(ControlContract.errorCodeForPairingFailure(e.code), e.message)
        }
        BridgeService.maybeStart(context)
        Log.i(TAG, "control PAIR completed")
        return ControlContract.pairJson(
            ControlPairPayload(
                paired = true,
                deviceId = result.deviceId,
                userId = result.user,
                hsUrl = result.homeserverUrl,
            )
        )
    }

    private fun unpair(context: Context): String {
        PairingStore.newInstance(context).clearPairing()
        BridgeService.maybeStart(context)
        Log.i(TAG, "control UNPAIR cleared the pairing")
        return ControlContract.unpairJson()
    }

    @Suppress("ReturnCount")
    private fun send(context: Context, intent: Intent): String {
        val recipients = ControlContract.parseRecipients(intent.getStringExtra(ControlContract.EXTRA_TO))
        if (recipients.isEmpty()) {
            return invalidRequest("send needs at least one recipient in 'to'")
        }
        val text = intent.getStringExtra(ControlContract.EXTRA_TEXT).orEmpty()
        val filePath = intent.getStringExtra(ControlContract.EXTRA_FILE).orEmpty()
        val attachments = try {
            if (filePath.isNotEmpty()) listOf(readAttachment(filePath)) else emptyList()
        } catch (e: IllegalArgumentException) {
            return invalidRequest(e.message ?: "cannot read the file extra")
        }
        if (text.isEmpty() && attachments.isEmpty()) {
            return invalidRequest("send needs 'text' or a 'file'")
        }

        val response = try {
            BridgeSender.send(
                context,
                BridgeSendRequest(recipients = recipients, text = text, attachments = attachments),
            )
        } catch (e: BridgeSendException) {
            Log.w(TAG, "control SEND failed: ${e.code}")
            return ControlContract.errorJson(ControlContract.errorCodeForSendFailure(e.code), e.message)
        }

        // Same rule as BridgeSender: exactly one SMS for one recipient without a file,
        // one group MMS for several recipients or with an attachment (long texts follow
        // the app's own "long message as MMS" preference).
        val defaultSmsApp = BridgeQueries.isDefaultSmsApp(context)
        val isMms = attachments.isNotEmpty() || recipients.size > 1 ||
            (context.isLongMmsMessage(text) && defaultSmsApp)
        val rowId = BridgeMessageIds.parse(response.messageId)?.third ?: 0
        Log.i(TAG, "control SEND completed (${if (isMms) "mms" else "sms"}, pending=${response.pending})")
        return ControlContract.sendJson(
            ControlSendPayload(
                messageId = rowId,
                threadId = response.threadId,
                pending = response.pending,
                mode = if (isMms) "mms" else "sms",
            )
        )
    }

    private fun readAttachment(path: String): BridgeSendAttachment {
        val file = File(path)
        require(file.isAbsolute) { "file must be an absolute path" }
        require(file.isFile) { "file does not exist or is not a regular file" }
        require(file.length() <= BridgeSender.MAX_ATTACHMENT_BYTES) {
            "file is larger than ${BridgeSender.MAX_ATTACHMENT_BYTES} bytes"
        }
        val bytes = try {
            file.readBytes()
        } catch (e: Exception) {
            throw IllegalArgumentException("cannot read file '${file.name}'", e)
        }
        val contentType = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(file.extension.lowercase())
            ?: DEFAULT_CONTENT_TYPE
        return BridgeSendAttachment(
            contentType = contentType,
            fileName = file.name,
            base64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
        )
    }

    private fun sync(context: Context): String {
        val store = PairingStore.newInstance(context)
        if (!store.isPaired) {
            return ControlContract.errorJson(ControlContract.ERROR_NOT_PAIRED, "nothing is paired")
        }
        try {
            PairingPoller(context).syncOnce()
        } catch (e: PairingException) {
            Log.w(TAG, "control SYNC failed: ${e.code}")
            val code = if (store.isPaired) ControlContract.ERROR_UNAVAILABLE else ControlContract.ERROR_NOT_PAIRED
            return ControlContract.errorJson(code, e.message)
        }
        Log.i(TAG, "control SYNC polled the queue up to ${store.queueCursor}")
        return ControlContract.syncJson(
            ControlSyncPayload(queueCursor = store.queueCursor, eventCursor = store.eventsCursor)
        )
    }

    private fun invalidRequest(message: String): String {
        return ControlContract.errorJson(ControlContract.ERROR_INVALID_REQUEST, message)
    }
}
