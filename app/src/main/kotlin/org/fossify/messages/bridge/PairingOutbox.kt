/*
 * SPDX-FileCopyrightText: 2026 The Fossify Messages bridge contributors
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.fossify.messages.bridge

import android.content.Context
import android.util.Log
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private const val TAG = "PairingOutbox"
private const val MAX_PENDING_EVENTS = 200
private const val MAX_RECENT_RESULTS = 100

// The bridge caps pairing API request bodies at 4 MiB (pairingMaxBodyBytes).
// Keep a margin for the envelope and for JSON escaping of body text.
internal const val MAX_BATCH_BYTES = 3 * 1024 * 1024
private const val BATCH_ENVELOPE_BYTES = 512

/** Encoded size of one event inside an `events` request body (UTF-8 JSON). */
internal fun eventBytes(json: Json, event: BridgeEvent): Int =
    json.encodeToString(BridgeEvent.serializer(), event).toByteArray(Charsets.UTF_8).size

/**
 * Longest prefix of [events] whose request body stays within [maxBytes].
 *
 * A batch that exceeds the bridge's body cap is rejected as a whole and would
 * block every later event, so bursts of inline MMS payloads (replayed
 * messages fetched from the phone) must be split across requests. A single
 * event larger than [maxBytes] is returned alone; the caller decides what to do
 * with it.
 */
internal fun fitEventBatch(
    json: Json,
    events: List<BridgeEvent>,
    maxBytes: Int,
    envelopeBytes: Int,
): List<BridgeEvent> {
    var bytes = envelopeBytes
    for ((index, event) in events.withIndex()) {
        val size = eventBytes(json, event)
        if (index > 0 && bytes + size > maxBytes) {
            return events.subList(0, index)
        }
        bytes += size
    }
    return events
}

/** `POST /_smsmms/v1/events` body. */
@Serializable
internal data class EventsRequest(
    @SerialName("device_id") val deviceId: String,
    val cursor: Long,
    val events: List<BridgeEvent>,
)

/** `POST /_smsmms/v1/events` response; -1 means the field was not returned. */
@Serializable
private data class EventsResponse(
    val cursor: Long = 0,
    val accepted: Int = -1,
)

/** An event that was persisted but not acknowledged by the bridge yet. */
@Serializable
internal data class PendingEvent(
    val id: Long,
    val event: BridgeEvent,
)

/**
 * At-least-once event queue of the pairing poller.
 *
 * Pending events and the results of already executed queue commands are
 * persisted, so a crash between "the SMS was sent" and "the result reached the
 * bridge" does not resend the message: the bridge re-delivers the command and
 * the cached `send.result` is pushed again instead.
 */
internal class PairingOutbox(private val context: Context, private val store: PairingStore) {
    val json = Json {
        encodeDefaults = false
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    private val lock = Any()

    fun enqueue(event: BridgeEvent) {
        synchronized(lock) {
            val pending = loadPending()
            pending.add(PendingEvent(id = (pending.maxOfOrNull { it.id } ?: 0L) + 1, event = event))
            while (pending.size > MAX_PENDING_EVENTS) {
                pending.removeAt(0)
            }
            store.pendingEvents = json.encodeToString(ListSerializer(PendingEvent.serializer()), pending)
        }
    }

    /**
     * Counts of queued message-facing events by kind (undelivered `message.new`
     * / `send.result` events), reported as the outbox sizes of `control.STATUS`.
     * Other event types (acks, thread updates) are ignored.
     */
    fun pendingMessageCounts(): Pair<Int, Int> {
        synchronized(lock) {
            val pending = loadPending().filter { it.event.messageId.isNotEmpty() || it.event.sendId.isNotEmpty() }
            return pending.count { !it.event.isMms } to pending.count { it.event.isMms }
        }
    }

    fun findRecentResult(sendId: String): BridgeEvent? {
        if (sendId.isEmpty()) {
            return null
        }
        synchronized(lock) {
            return loadRecentResults().lastOrNull { it.sendId == sendId }
        }
    }

    fun rememberResult(event: BridgeEvent) {
        synchronized(lock) {
            val previous = loadRecentResults()
            val results = if (event.sendId.isEmpty()) {
                previous.toMutableList()
            } else {
                previous.filter { it.sendId != event.sendId }.toMutableList()
            }
            results.add(event.copy(seq = 0))
            while (results.size > MAX_RECENT_RESULTS) {
                results.removeAt(0)
            }
            store.recentSendResults = json.encodeToString(ListSerializer(BridgeEvent.serializer()), results)
        }
    }

    /**
     * Pushes all pending events with fresh sequence numbers.
     * Returns false when the bridge could not be reached.
     */
    // Any push failure keeps the events queued; the poller loop decides when to retry.
    @Suppress("TooGenericExceptionCaught")
    fun flush(): Boolean {
        synchronized(lock) {
            val pending = loadPending()
            if (pending.isEmpty()) {
                return true
            }
            if (!store.isPaired) {
                return false
            }

            val token = store.token
            if (token.isEmpty()) {
                Log.w(TAG, "cannot push events without a device token")
                return false
            }

            val baseSeq = store.eventsCursor
            val all = pending.mapIndexed { index, item -> item.event.copy(seq = baseSeq + index + 1) }
            // Split bursts of inline payloads (fetched replayed MMS) into
            // several requests: one oversized batch would be rejected as a
            // whole and block every later event.
            val events = fitEventBatch(json, all, MAX_BATCH_BYTES, BATCH_ENVELOPE_BYTES)
            val maxSeq = events.last().seq
            val body = json.encodeToString(
                EventsRequest.serializer(),
                EventsRequest(deviceId = PairingClient.deviceId(context), cursor = maxSeq, events = events),
            )

            return try {
                val response = PairingHttp.requestJson(
                    url = PairingHttp.endpoint(store.bridgeBaseUrl, "/_smsmms/v1/events"),
                    method = "POST",
                    token = token,
                    body = body,
                )
                val acknowledged = acknowledgedCount(response, events.size)
                if (acknowledged > 0) {
                    store.eventsCursor = events[acknowledged - 1].seq
                    val newest = events.take(acknowledged).maxOfOrNull { it.timestampMs } ?: 0
                    if (newest > store.lastEventPushedMs) {
                        store.lastEventPushedMs = newest
                    }
                }
                val sentIds = pending.take(acknowledged).map { it.id }.toSet()
                savePending(loadPending().filter { it.id !in sentIds })
                true
            } catch (e: PairingException) {
                if (e.status == PairingHttp.HTTP_STATUS_UNAUTHORIZED || e.status == PairingHttp.HTTP_STATUS_FORBIDDEN) {
                    Log.w(TAG, "bridge rejected the device token (${e.code}), clearing the pairing")
                    store.clearPairing()
                } else {
                    Log.w(TAG, "failed to push ${events.size} events: ${e.code}: ${e.message}")
                }
                false
            } catch (e: Exception) {
                Log.w(TAG, "failed to push ${events.size} events", e)
                false
            }
        }
    }

    // An unreadable acknowledgement is not fatal: the whole batch is assumed accepted.
    @Suppress("TooGenericExceptionCaught")
    private fun acknowledgedCount(body: String, batchSize: Int): Int {
        return try {
            val response = json.decodeFromString(EventsResponse.serializer(), body)
            if (response.accepted < 0) batchSize else response.accepted.coerceIn(0, batchSize)
        } catch (e: Exception) {
            Log.w(TAG, "could not read the event acknowledgement, assuming the whole batch was accepted", e)
            batchSize
        }
    }

    private fun loadPending(): MutableList<PendingEvent> {
        val pending = decodeList(store.pendingEvents, PendingEvent.serializer())
        return pending.toMutableList()
    }

    private fun savePending(pending: List<PendingEvent>) {
        store.pendingEvents = if (pending.isEmpty()) {
            ""
        } else {
            json.encodeToString(ListSerializer(PendingEvent.serializer()), pending)
        }
    }

    private fun loadRecentResults(): List<BridgeEvent> {
        return decodeList(store.recentSendResults, BridgeEvent.serializer())
    }

    // Persisted state that cannot be parsed is dropped instead of breaking the poller.
    @Suppress("TooGenericExceptionCaught")
    private fun <T> decodeList(raw: String, format: kotlinx.serialization.KSerializer<T>): List<T> {
        if (raw.isEmpty()) {
            return emptyList()
        }
        return try {
            json.decodeFromString(ListSerializer(format), raw)
        } catch (e: Exception) {
            Log.e(TAG, "failed to parse persisted events", e)
            emptyList()
        }
    }
}
