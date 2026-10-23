/*
 * SPDX-FileCopyrightText: 2026 microchipster
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.fossify.messages.bridge

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.fossify.messages.R
import org.fossify.messages.activities.MainActivity
import org.fossify.messages.helpers.NOTIFICATION_CHANNEL_ID

/**
 * Foreground service hosting the bridge HTTP API.
 *
 * It is started while the `bridge_enabled` preference is true, on app start
 * and after boot/package updates. The bearer token is generated on first use,
 * stored in the `bridge_token` preference and written to logcat so it can be
 * retrieved with `adb logcat`.
 */
// The foreground service must never crash the app or the settings screen, so every lifecycle failure is contained.
@Suppress("TooGenericExceptionCaught")
class BridgeService : Service() {
    companion object {
        private const val TAG = "BridgeService"
        private const val NOTIFICATION_ID = 8391
        private const val MIN_PORT = 1024
        private const val MAX_PORT = 65535

        /**
         * Starts or stops the service according to the current preferences.
         *
         * It runs when the static-token agent mode is enabled (`bridge_enabled`)
         * and/or when this phone is paired with a Matrix bridge (a stored device
         * token), because the pairing poller needs the same foreground service.
         */
        fun maybeStart(context: Context) {
            try {
                if (BridgeConfig.newInstance(context).isActive()) {
                    start(context)
                } else {
                    stop(context)
                }
            } catch (e: Exception) {
                Log.e(TAG, "failed to toggle bridge service", e)
            }
        }

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, BridgeService::class.java))
            } catch (e: Exception) {
                Log.e(TAG, "failed to start bridge service", e)
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, BridgeService::class.java))
            } catch (e: Exception) {
                Log.e(TAG, "failed to stop bridge service", e)
            }
        }
    }

    private var server: BridgeServer? = null
    private var poller: PairingPoller? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val config = BridgeConfig.newInstance(this)
        val bridgeEnabled = config.bridgeEnabled
        val paired = PairingStore.newInstance(this).isPaired
        if (!bridgeEnabled && !paired) {
            stopSelf()
            return START_NOT_STICKY
        }

        val port = config.bridgePort.coerceIn(MIN_PORT, MAX_PORT)
        val token = config.getOrCreateToken()
        startAsForeground(port, bridgeEnabled)

        if (bridgeEnabled && server == null) {
            val newServer = BridgeServer(this, token, port)
            try {
                newServer.start()
                server = newServer
                Log.i(TAG, "bridge HTTP API listening on 0.0.0.0:$port, bearer token: $token")
            } catch (e: Exception) {
                Log.e(TAG, "failed to start bridge HTTP API on port $port", e)
            }
        }

        if (paired && poller == null) {
            poller = PairingPoller(this).also { it.start() }
        } else if (!paired && poller != null) {
            // Unpaired while the service was running: the poller loops exit on
            // their own, but drop the instance so a later pairing starts fresh.
            poller?.stop()
            poller = null
        }

        return START_STICKY
    }

    override fun onDestroy() {
        poller?.stop()
        poller = null
        server?.stop()
        server = null
        super.onDestroy()
    }

    private fun startAsForeground(port: Int, bridgeEnabled: Boolean) {
        val notificationManager = getSystemService(NotificationManager::class.java) ?: return
        ensureNotificationChannel(notificationManager)

        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = if (bridgeEnabled) {
            getString(R.string.bridge_service_text, port)
        } else {
            val user = PairingStore.newInstance(this).pairedUser
                .ifEmpty { getString(R.string.bridge_service_paired_text_unknown) }
            getString(R.string.bridge_service_paired_text, user)
        }
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_messenger)
            .setContentTitle(getString(R.string.bridge_service_title))
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING,
            )
        } else {
            // FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING only exists on API 34+;
            // the manifest attribute is ignored below that.
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /**
     * The bridge reuses the message channel `fossify_messages`; when it does not
     * exist yet (bridge started before any message arrived), create it exactly
     * like [org.fossify.messages.helpers.NotificationHelper] would, so message
     * notifications keep their upstream importance, sound and vibration.
     */
    private fun ensureNotificationChannel(notificationManager: NotificationManager) {
        if (notificationManager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) != null) {
            return
        }

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setLegacyStreamType(AudioManager.STREAM_NOTIFICATION)
            .build()

        NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.channel_received_sms),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            setBypassDnd(false)
            enableLights(true)
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), audioAttributes)
            enableVibration(true)
            notificationManager.createNotificationChannel(this)
        }
    }
}
