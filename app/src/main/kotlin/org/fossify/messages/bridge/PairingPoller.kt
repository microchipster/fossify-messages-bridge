/*
 * SPDX-FileCopyrightText: 2026 microchipster
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.fossify.messages.bridge

import android.content.Context
import android.util.Log
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "PairingPoller"

private const val QUEUE_WAIT_MS = 30_000
private const val QUEUE_READ_TIMEOUT_MS = QUEUE_WAIT_MS + 15_000
private const val EVENT_WAIT_MS = 25_000L
private const val BACKOFF_INITIAL_MS = 5_000L
private const val BACKOFF_MAX_MS = 5 * 60 * 1000L
private const val MS_PER_SECOND = 1_000

/**
 * Executes the bridge queue and pushes app events back, see
 * `docs/PAIRING_PROTOCOL.md` in the Matrix bridge repository.
 *
 * Two daemon threads run inside the [BridgeService] foreground service:
 *
 * - the queue thread long-polls `GET /_smsmms/v1/queue`, hands the commands to
 *   [BridgeCommandExecutor] and answers them with `send.result` / `ack`
 *   events, plus a periodic heartbeat;
 * - the event thread follows the in-memory [BridgeEvents] feed and pushes
 *   `POST /_smsmms/v1/events` batches, so received messages reach the bridge
 *   while it is connected.
 *
 * Both directions keep their cursor in [PairingStore] and survive restarts;
 * undelivered events (including send results) are persisted by [PairingOutbox].
 */
class PairingPoller(private val context: Context) {

    companion object {
        // Serializes queue-response handling between the long-poll loop and control.SYNC.
        private val QUEUE_APPLY_LOCK = Any()
    }

    private val store = PairingStore.newInstance(context)
    private val outbox = PairingOutbox(context, store)
    private val executor = BridgeCommandExecutor(context, outbox)
    private val running = AtomicBoolean(false)

    @Volatile
    private var queueThread: Thread? = null

    @Volatile
    private var eventThread: Thread? = null

    /** Starts the poller when a device token is stored; safe to call repeatedly. */
    fun start() {
        if (!running.compareAndSet(false, true)) {
            return
        }
        if (!store.isPaired) {
            running.set(false)
            return
        }

        Log.i(TAG, "starting bridge pairing poller")
        eventThread = Thread({ runEventLoop() }, "BridgePairingEvents").apply {
            isDaemon = true
            start()
        }
        queueThread = Thread({ runQueueLoop() }, "BridgePairingQueue").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) {
            return
        }
        Log.i(TAG, "stopping bridge pairing poller")
        eventThread?.interrupt()
        queueThread?.interrupt()
        eventThread = null
        queueThread = null
    }

    /**
     * Runs one queue poll (without waiting for commands) and one heartbeat
     * immediately; used by `control.SYNC`.
     *
     * Response handling is serialized with the long-poll loops and commands
     * below the stored cursor are skipped, so calling this while the service
     * is running is safe. Throws [PairingException] when the poll fails.
     */
    fun syncOnce() {
        val token = store.token
        if (token.isEmpty() || store.bridgeBaseUrl.isBlank()) {
            store.clearPairing()
            throw PairingException("not_paired", 0, "pairing state is incomplete")
        }
        try {
            applyQueueResponse(fetchQueue(token, waitMs = 0))
            outbox.flush()
            BridgeHeartbeat.sendIfDue(context, store, token, store.lastHeartbeatMs, force = true)
        } catch (e: PairingException) {
            if (e.status == PairingHttp.HTTP_STATUS_UNAUTHORIZED || e.status == PairingHttp.HTTP_STATUS_FORBIDDEN) {
                store.clearPairing()
            }
            throw e
        }
    }

    // The loop must survive any bridge or provider failure and simply retry with backoff.
    @Suppress("TooGenericExceptionCaught")
    private fun runQueueLoop() {
        var backoff = BACKOFF_INITIAL_MS
        var lastHeartbeatMs = 0L
        while (running.get() && store.isPaired) {
            try {
                val token = store.token
                if (token.isEmpty() || store.bridgeBaseUrl.isBlank()) {
                    Log.w(TAG, "pairing state is incomplete, clearing the pairing")
                    store.clearPairing()
                    return
                }

                applyQueueResponse(fetchQueue(token))
                backoff = BACKOFF_INITIAL_MS
                outbox.flush()
                lastHeartbeatMs = BridgeHeartbeat.sendIfDue(context, store, token, lastHeartbeatMs)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            } catch (e: PairingException) {
                if (e.status == PairingHttp.HTTP_STATUS_UNAUTHORIZED || e.status == PairingHttp.HTTP_STATUS_FORBIDDEN) {
                    Log.w(TAG, "bridge rejected the device token (${e.code}), clearing the pairing")
                    store.clearPairing()
                    return
                }
                Log.w(TAG, "queue request failed: ${e.code}: ${e.message}")
                backoff = sleepBackoff(backoff)
            } catch (e: Exception) {
                Log.w(TAG, "queue request failed", e)
                backoff = sleepBackoff(backoff)
            }
        }
    }

    // A stopped event loop must never take the foreground service down with it.
    @Suppress("TooGenericExceptionCaught")
    private fun runEventLoop() {
        try {
            pushGapSnapshot()
            var bufferCursor = BridgeEvents.cursor()
            while (running.get() && store.isPaired) {
                val page = BridgeEvents.wait(bufferCursor, EVENT_WAIT_MS)
                bufferCursor = page.cursor
                if (page.reset) {
                    // The in-memory ring buffer dropped events, re-send provider state.
                    pushGapSnapshot()
                    continue
                }
                for (event in page.events) {
                    outbox.enqueue(event)
                }
                if (page.events.isNotEmpty() && !outbox.flush()) {
                    // The queue loop retries with a proper backoff; just do not spin here.
                    Thread.sleep(BACKOFF_INITIAL_MS)
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            Log.e(TAG, "event push loop stopped", e)
        }
    }

    /** Applies a queue response under the shared lock; see [syncOnce] for why. */
    private fun applyQueueResponse(response: QueueResponse) {
        synchronized(QUEUE_APPLY_LOCK) {
            handleQueueResponse(response)
        }
    }

    private fun handleQueueResponse(response: QueueResponse) {
        if (response.reset) {
            Log.i(TAG, "queue cursor is too old (reset), re-sending recent state")
            pushGapSnapshot()
        } else {
            // control.SYNC can fetch in parallel with the long-poll loop; skip
            // commands the stored cursor already covers.
            val cursor = store.queueCursor
            val commands = response.commands.filter { it.seq <= 0 || it.seq > cursor }
            if (commands.isNotEmpty()) {
                Log.i(TAG, "got ${commands.size} queue commands up to ${response.cursor}")
            }
            for (command in commands) {
                executor.execute(command)
            }
        }
        if (response.reset || response.commands.isNotEmpty()) {
            // A reset is authoritative (the bridge dropped its queue and may
            // report a lower cursor); otherwise never move backwards, even
            // when a stale response arrives late.
            store.queueCursor = nextQueueCursor(store.queueCursor, response.cursor, response.reset)
        }
    }

    // A response that cannot be parsed is reported as a protocol error, whatever the parser throws.
    @Suppress("TooGenericExceptionCaught")
    private fun fetchQueue(token: String, waitMs: Int = QUEUE_WAIT_MS): QueueResponse {
        val url = PairingHttp.endpoint(store.bridgeBaseUrl, "/_smsmms/v1/queue") +
            "?since=${store.queueCursor}&wait_ms=$waitMs"
        val body = PairingHttp.requestJson(
            url = url,
            method = "GET",
            token = token,
            readTimeoutMs = QUEUE_READ_TIMEOUT_MS,
        )
        return try {
            outbox.json.decodeFromString(QueueResponse.serializer(), body)
        } catch (e: Exception) {
            Log.e(TAG, "failed to parse the queue response", e)
            throw PairingException("internal", 0, "bridge returned an unexpected queue response")
        }
    }

    /**
     * Re-sends provider state that the bridge may have missed: recent
     * `thread.updated` and `message.new` events. Triggered on startup (gap
     * since the last successful push), on a queue `reset` and when the local
     * event ring buffer overflows.
     */
    private fun pushGapSnapshot() {
        if (!running.get() || !store.isPaired) {
            return
        }
        val sinceMs = maxOf(store.lastEventPushedMs, BridgeStateSnapshot.initialSinceMs(System.currentTimeMillis()))
        val events = BridgeStateSnapshot.recentEvents(context, sinceMs)
        if (events.isEmpty()) {
            return
        }
        Log.i(TAG, "re-sending ${events.size} recent events (since $sinceMs)")
        for (event in events) {
            outbox.enqueue(event)
        }
        outbox.flush()
    }

    private fun sleepBackoff(backoff: Long): Long {
        val sleepMs = backoff.coerceAtMost(BACKOFF_MAX_MS)
        Log.i(TAG, "retrying in ${sleepMs / MS_PER_SECOND}s")
        try {
            Thread.sleep(sleepMs)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return (sleepMs * 2).coerceAtMost(BACKOFF_MAX_MS)
    }
}

/** One `GET /_smsmms/v1/queue` command. */
@Serializable
internal data class QueueCommand(
    val seq: Long = 0,
    val type: String = "",
    @SerialName("send_id") val sendId: String = "",
    @SerialName("sub_id") val subId: Int = 0,
    val recipients: List<String> = emptyList(),
    val text: String = "",
    val attachments: List<BridgeSendAttachment> = emptyList(),
    @SerialName("thread_id") val threadId: Long = 0,
    @SerialName("up_to_ms") val upToMs: Long = 0,
) {
    companion object {
        const val TYPE_SEND_SMS = "send_sms"
        const val TYPE_SEND_MMS = "send_mms"
        const val TYPE_MARK_READ = "mark_read"
        const val TYPE_PING = "ping"
    }
}

/** `GET /_smsmms/v1/queue` response. */
@Serializable
private data class QueueResponse(
    val cursor: Long = 0,
    val reset: Boolean = false,
    val commands: List<QueueCommand> = emptyList(),
)
