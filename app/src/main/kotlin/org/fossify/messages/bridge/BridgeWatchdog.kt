/*
 * SPDX-FileCopyrightText: 2026 The Fossify Messages bridge contributors
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.fossify.messages.bridge

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log

private const val TAG = "BridgeWatchdog"
private const val WATCHDOG_INTERVAL_MS = 15 * 60 * 1000L

/**
 * Revives the foreground service after the process was frozen or killed by
 * the system (doze, OEM battery managers, low memory). The alarm is armed
 * while the bridge is enabled or paired and survives service destruction;
 * a device reboot re-arms it through RescheduleAlarmsReceiver.
 */
class BridgeWatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        BridgeWatchdog.schedule(context)
        if (BridgeConfig.newInstance(context).isActive() && !BridgeService.isRunning()) {
            Log.i(TAG, "bridge service is not running, restarting it")
            BridgeService.start(context)
        }
    }
}

/** Arms and cancels the [BridgeWatchdogReceiver] alarm. */
// Alarm bookkeeping must never crash the service or the app.
@Suppress("TooGenericExceptionCaught")
internal object BridgeWatchdog {
    private const val ACTION_WATCHDOG = "org.fossify.messages.bridge.WATCHDOG"

    /** (Re-)arms the periodic watchdog while the bridge is active. */
    fun schedule(context: Context) {
        if (!BridgeConfig.newInstance(context).isActive()) {
            return
        }
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        try {
            manager.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + WATCHDOG_INTERVAL_MS,
                WATCHDOG_INTERVAL_MS,
                pendingIntent(context),
            )
        } catch (e: Exception) {
            Log.w(TAG, "failed to arm the watchdog", e)
        }
    }

    /** Disarms the watchdog (the bridge is fully disabled). */
    fun cancel(context: Context) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        try {
            manager.cancel(pendingIntent(context))
        } catch (e: Exception) {
            Log.w(TAG, "failed to disarm the watchdog", e)
        }
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, BridgeWatchdogReceiver::class.java).setAction(ACTION_WATCHDOG)
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
