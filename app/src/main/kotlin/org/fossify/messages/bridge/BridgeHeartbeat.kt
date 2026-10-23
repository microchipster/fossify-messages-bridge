/*
 * SPDX-FileCopyrightText: 2026 microchipster
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.fossify.messages.bridge

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.fossify.messages.BuildConfig

private const val TAG = "BridgeHeartbeat"
private const val HEARTBEAT_INTERVAL_MS = 5 * 60 * 1000L
private const val BATTERY_PERCENT_MAX = 100
private const val BATTERY_UNKNOWN = -1

/** `POST /_smsmms/v1/heartbeat` body. */
@Serializable
internal data class HeartbeatRequest(
    @SerialName("device_id") val deviceId: String,
    @SerialName("battery_pct") val batteryPct: Int = BATTERY_UNKNOWN,
    @SerialName("default_sms_app") val defaultSmsApp: Boolean = false,
    val permissions: Map<String, Boolean> = emptyMap(),
    @SerialName("app_version") val appVersion: String = "",
)

/**
 * Periodic bridge health report: device state, permissions and app version,
 * used by the bridge to gate MMS and read-state features.
 */
internal object BridgeHeartbeat {
    private val json = Json {
        encodeDefaults = false
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    /**
     * Sends a heartbeat when the interval elapsed and returns the new
     * timestamp. A rejected device token is rethrown, so the caller can clear
     * the pairing.
     */
    @Suppress("TooGenericExceptionCaught")
    fun sendIfDue(context: Context, store: PairingStore, token: String, lastHeartbeatMs: Long): Long {
        val now = System.currentTimeMillis()
        if (lastHeartbeatMs != 0L && now - lastHeartbeatMs < HEARTBEAT_INTERVAL_MS) {
            return lastHeartbeatMs
        }

        val payload = HeartbeatRequest(
            deviceId = PairingClient.deviceId(context),
            batteryPct = batteryPct(context),
            defaultSmsApp = BridgeQueries.isDefaultSmsApp(context),
            permissions = permissionsSnapshot(context),
            appVersion = BuildConfig.VERSION_NAME,
        )
        try {
            PairingHttp.requestJson(
                url = PairingHttp.endpoint(store.bridgeBaseUrl, "/_smsmms/v1/heartbeat"),
                method = "POST",
                token = token,
                body = json.encodeToString(HeartbeatRequest.serializer(), payload),
            )
        } catch (e: PairingException) {
            if (e.status == PairingHttp.HTTP_STATUS_UNAUTHORIZED || e.status == PairingHttp.HTTP_STATUS_FORBIDDEN) {
                throw e
            }
            Log.w(TAG, "heartbeat failed: ${e.code}: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "heartbeat failed", e)
        }
        return now
    }

    @Suppress("TooGenericExceptionCaught")
    private fun batteryPct(context: Context): Int {
        return try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, BATTERY_UNKNOWN) ?: BATTERY_UNKNOWN
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, BATTERY_UNKNOWN) ?: BATTERY_UNKNOWN
            if (level >= 0 && scale > 0) {
                (level * BATTERY_PERCENT_MAX / scale).coerceIn(0, BATTERY_PERCENT_MAX)
            } else {
                BATTERY_UNKNOWN
            }
        } catch (e: Exception) {
            Log.w(TAG, "failed to read the battery level", e)
            BATTERY_UNKNOWN
        }
    }

    private fun permissionsSnapshot(context: Context): Map<String, Boolean> {
        val defaultSmsApp = BridgeQueries.isDefaultSmsApp(context)
        return mapOf(
            "read_sms" to hasPermission(context, Manifest.permission.READ_SMS),
            "send_sms" to hasPermission(context, Manifest.permission.SEND_SMS),
            "receive_sms" to hasPermission(context, Manifest.permission.RECEIVE_SMS),
            "receive_mms" to hasPermission(context, Manifest.permission.RECEIVE_MMS),
            "send_mms" to defaultSmsApp,
            "mark_read" to defaultSmsApp,
        )
    }

    private fun hasPermission(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
}
