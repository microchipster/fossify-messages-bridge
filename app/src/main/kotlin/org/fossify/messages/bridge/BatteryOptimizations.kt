/*
 * SPDX-FileCopyrightText: 2026 microchipster
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.fossify.messages.bridge

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import org.fossify.messages.R

/**
 * Battery-optimization exemption nudge for the bridge service.
 *
 * When the app is battery-optimized, Android may freeze its process for hours
 * (doze / app standby) even though the foreground service is running, and the
 * paired Matrix bridge then sees the phone as offline. The fix is a user
 * setting, so the bridge asks once a week instead of failing silently.
 */
// The nudge must never take the service down over OEM quirks in the power APIs.
@Suppress("TooGenericExceptionCaught")
internal object BatteryOptimizations {
    private const val TAG = "BatteryOptimizations"
    private const val CHANNEL_ID = "bridge_battery_optimization"
    private const val NOTIFICATION_ID = 8392
    private const val PROMPT_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000
    private const val PREFS_NAME = "bridge_battery_optimization"
    private const val LAST_PROMPT_MS = "last_prompt_ms"

    /** True when the system already keeps the app out of battery optimizations. */
    fun isExempt(context: Context): Boolean {
        val power = context.getSystemService(PowerManager::class.java) ?: return true
        return try {
            power.isIgnoringBatteryOptimizations(context.packageName)
        } catch (e: Exception) {
            Log.w(TAG, "failed to read the battery optimization state", e)
            true
        }
    }

    /** System dialog intent that asks for the exemption. */
    fun requestIntent(context: Context): Intent {
        return Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * Posts the exemption nudge (at most once a week) while the app is still
     * battery-optimized, and clears it again once the exemption is granted.
     */
    fun maybeNotify(context: Context) {
        if (isExempt(context)) {
            cancel(context)
            return
        }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(LAST_PROMPT_MS, 0L) < PROMPT_INTERVAL_MS) {
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(context, manager)
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            requestIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = context.getString(R.string.bridge_battery_text)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_messenger)
            .setContentTitle(context.getString(R.string.bridge_battery_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        try {
            manager.notify(NOTIFICATION_ID, notification)
            prefs.edit().putLong(LAST_PROMPT_MS, now).apply()
        } catch (e: Exception) {
            Log.w(TAG, "failed to post the battery optimization nudge", e)
        }
    }

    /** Removes the nudge when the exemption is no longer missing. */
    fun cancel(context: Context) {
        try {
            context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        } catch (e: Exception) {
            Log.w(TAG, "failed to clear the battery optimization nudge", e)
        }
    }

    private fun ensureChannel(context: Context, manager: NotificationManager) {
        if (manager.getNotificationChannel(CHANNEL_ID) != null) {
            return
        }
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.bridge_battery_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.setShowBadge(false)
        manager.createNotificationChannel(channel)
    }
}
