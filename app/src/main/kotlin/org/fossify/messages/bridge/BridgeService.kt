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
import android.net.ConnectivityManager
import android.net.Network
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
 * and after boot/package updates. The bearer token is generated on first use
 * and stored in the `bridge_token` preference; it is never written to logs.
 */
// The foreground service must never crash the app or the settings screen, so every lifecycle failure is contained.
@Suppress("TooGenericExceptionCaught")
class BridgeService : Service() {
    companion object {
        private const val TAG = "BridgeService"
        private const val NOTIFICATION_ID = 8391
        private const val MIN_PORT = 1024
        private const val MAX_PORT = 65535

        @Volatile
        private var running = false

        /** True while the foreground service is started; read by `control.STATUS`. */
        fun isRunning(): Boolean = running

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
                    BridgeWatchdog.schedule(context)
                } else {
                    BridgeWatchdog.cancel(context)
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
    private var serverPort = 0
    private var serverToken = ""
    private var poller: PairingPoller? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val config = BridgeConfig.newInstance(this)
        val bridgeEnabled = config.bridgeEnabled
        val paired = PairingStore.newInstance(this).isPaired
        if (!bridgeEnabled && !paired) {
            running = false
            BridgeWatchdog.cancel(this)
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        BridgeWatchdog.schedule(this)

        val port = config.bridgePort.coerceIn(MIN_PORT, MAX_PORT)
        // Only the static-token agent mode uses this token; a phone that is
        // merely paired through the QR flow does not need one.
        val token = config.getOrCreateToken()
        startAsForeground(port, bridgeEnabled)
        BatteryOptimizations.maybeNotify(this)

        updateServer(bridgeEnabled, token, port)

        if (paired && poller == null) {
            poller = PairingPoller(this).also { it.start() }
        } else if (!paired && poller != null) {
            // Unpaired while the service was running: the poller loops exit on
            // their own, but drop the instance so a later pairing starts fresh.
            poller?.stop()
            poller = null
        }

        if (paired) {
            registerNetworkWatcher()
        } else {
            unregisterNetworkWatcher()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        unregisterNetworkWatcher()
        poller?.stop()
        poller = null
        server?.stop()
        server = null
        // When the process dies while the bridge is still active, the alarm
        // brings the service back (a no-op once the bridge is disabled).
        BridgeWatchdog.schedule(this)
        super.onDestroy()
    }

    /**
     * Reconnects the pairing poller as soon as a network is available again
     * (wifi <-> mobile handovers and after airplane mode).
     */
    private fun registerNetworkWatcher() {
        if (networkCallback != null) {
            return
        }
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "network became available, poking the pairing poller")
                poller?.poke()
            }
        }
        try {
            manager.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        } catch (e: Exception) {
            Log.w(TAG, "failed to register the network watcher", e)
        }
    }

    private fun unregisterNetworkWatcher() {
        val callback = networkCallback ?: return
        networkCallback = null
        try {
            getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback)
        } catch (e: Exception) {
            Log.w(TAG, "failed to unregister the network watcher", e)
        }
    }

    /**
     * Starts, restarts or stops the HTTP API to match the current preferences
     * (a port or token change requires a rebind).
     */
    private fun updateServer(bridgeEnabled: Boolean, token: String, port: Int) {
        if (!bridgeEnabled) {
            server?.stop()
            server = null
            return
        }
        if (server != null && serverPort == port && serverToken == token) {
            return
        }

        server?.stop()
        server = null
        val newServer = BridgeServer(this, token, port)
        try {
            newServer.start()
            server = newServer
            serverPort = port
            serverToken = token
            Log.i(TAG, "bridge HTTP API listening on 127.0.0.1:$port")
        } catch (e: Exception) {
            Log.e(TAG, "failed to start bridge HTTP API on port $port", e)
        }
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
