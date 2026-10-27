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
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Error detail of a `send.result` event, mirroring the agent API error object. */
@Serializable
data class BridgeEventError(
    val code: String = "",
    val message: String = "",
)

/** One incremental change notification, mirroring the agent API event object. */
@Serializable
data class BridgeEvent(
    val seq: Long = 0,
    val type: String,
    @SerialName("message_id") val messageId: String = "",
    @SerialName("thread_id") val threadId: Long,
    @SerialName("sub_id") val subId: Int,
    @SerialName("timestamp_ms") val timestampMs: Long,
    val sender: String = "",
    @SerialName("is_mms") val isMms: Boolean,
    @SerialName("has_attachment") val hasAttachment: Boolean,
    val preview: String = "",
    @SerialName("send_id") val sendId: String = "",
    @SerialName("parts_sent") val partsSent: Int = 0,
    val pending: Boolean = false,
    val error: BridgeEventError? = null,
    // Full message payload. The pairing transport must carry it because the
    // bridge cannot fetch messages (or MMS parts) from a NATed phone.
    val message: BridgeMessage? = null,
    // Current participant list of the thread (thread.updated only). The bridge
    // has no thread list endpoint for paired phones, so this is how it
    // refreshes a portal's stored participants.
    val recipients: List<String> = emptyList(),
    // Command sequence this event acknowledges (acks only).
    @SerialName("command_seq") val commandSeq: Long = 0,
)

/** GET /v1/events response envelope. */
@Serializable
data class BridgeEventsPage(
    val reset: Boolean,
    val cursor: Long,
    val events: List<BridgeEvent>,
)

/**
 * In-memory ring buffer of receive/send events with long-poll support.
 *
 * The bridge polls this feed and falls back to a full history catch-up
 * whenever it receives `reset = true` (cursor fell out of the retained
 * window).
 */
// Event emission is best-effort: receivers must keep working even if a bridge event cannot be built.
@Suppress("TooGenericExceptionCaught")
object BridgeEvents {
    const val TYPE_MESSAGE_NEW = "message.new"
    const val TYPE_MESSAGE_UPDATED = "message.updated"
    const val TYPE_THREAD_UPDATED = "thread.updated"

    private const val TAG = "BridgeEvents"
    private const val MAX_EVENTS = 10000
    private const val MAX_PREVIEW_LENGTH = 120

    private val lock = ReentrantLock()
    private val hasEvents = lock.newCondition()
    private val buffer = ArrayDeque<BridgeEvent>()
    private var sequence = 0L

    fun cursor(): Long = lock.withLock { sequence }

    fun append(event: BridgeEvent) {
        lock.withLock {
            sequence += 1
            buffer.addLast(event.copy(seq = sequence))
            while (buffer.size > MAX_EVENTS) {
                buffer.removeFirst()
            }
            hasEvents.signalAll()
        }
    }

    /** Long-polls until an event newer than [since] exists or [timeoutMs] elapses. */
    fun wait(since: Long, timeoutMs: Long): BridgeEventsPage {
        val deadline = System.currentTimeMillis() + timeoutMs
        lock.withLock {
            while (true) {
                val page = sinceLocked(since)
                if (page.events.isNotEmpty() || page.reset) {
                    return page
                }
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) {
                    return page
                }
                try {
                    hasEvents.await(remaining, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    return BridgeEventsPage(reset = false, cursor = sequence, events = emptyList())
                }
            }
        }
    }

    private fun sinceLocked(since: Long): BridgeEventsPage {
        val cursor = sequence
        if (buffer.isNotEmpty() && since < buffer.first().seq - 1) {
            return BridgeEventsPage(reset = true, cursor = cursor, events = emptyList())
        }
        val events = buffer.filter { it.seq > since }
        return BridgeEventsPage(reset = false, cursor = cursor, events = events)
    }

    /**
     * Called by [org.fossify.messages.receivers.SmsReceiver] after a new SMS
     * has been persisted. Never throws.
     */
    fun onSmsReceived(
        context: Context,
        rowId: Long,
        threadId: Long,
        subId: Int,
        sender: String,
        body: String,
        timestampMs: Long,
    ) {
        try {
            if (rowId <= 0 || !BridgeConfig.newInstance(context).isActive()) {
                return
            }
            val messageId = BridgeMessageIds.format("sms", normalizeSubId(subId), rowId)
            append(
                BridgeEvent(
                    type = TYPE_MESSAGE_NEW,
                    messageId = messageId,
                    threadId = threadId,
                    subId = normalizeSubId(subId),
                    timestampMs = timestampMs,
                    sender = sender,
                    isMms = false,
                    hasAttachment = false,
                    preview = preview(body),
                    message = BridgeQueries.messageForEvent(context, messageId),
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "failed to emit SMS event", e)
        }
    }

    /**
     * Called by [org.fossify.messages.receivers.MmsReceiver] after a downloaded
     * MMS has been persisted. Never throws.
     */
    fun onMmsReceived(
        context: Context,
        rowId: Long,
        threadId: Long,
        subId: Int,
        sender: String,
        body: String,
        hasAttachment: Boolean,
        timestampMs: Long,
    ) {
        try {
            if (rowId <= 0 || !BridgeConfig.newInstance(context).isActive()) {
                return
            }
            val messageId = BridgeMessageIds.format("mms", normalizeSubId(subId), rowId)
            append(
                BridgeEvent(
                    type = TYPE_MESSAGE_NEW,
                    messageId = messageId,
                    threadId = threadId,
                    subId = normalizeSubId(subId),
                    timestampMs = timestampMs,
                    sender = sender,
                    isMms = true,
                    hasAttachment = hasAttachment,
                    preview = preview(body),
                    message = BridgeQueries.messageForEvent(context, messageId),
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "failed to emit MMS event", e)
        }
    }

    /** Emits an event for a message sent through POST /v1/send (carries the client [sendId]). */
    fun onMessageSent(
        context: Context,
        kind: String,
        rowId: Long,
        threadId: Long,
        subId: Int,
        body: String,
        isMms: Boolean,
        hasAttachment: Boolean,
        sendId: String,
        timestampMs: Long = System.currentTimeMillis(),
    ) {
        try {
            if (!BridgeConfig.newInstance(context).isActive()) {
                return
            }
            append(
                BridgeEvent(
                    type = TYPE_MESSAGE_NEW,
                    messageId = if (rowId > 0) BridgeMessageIds.format(kind, normalizeSubId(subId), rowId) else "",
                    threadId = threadId,
                    subId = normalizeSubId(subId),
                    timestampMs = timestampMs,
                    sender = "",
                    isMms = isMms,
                    hasAttachment = hasAttachment,
                    preview = preview(body),
                    sendId = sendId,
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "failed to emit sent event", e)
        }
    }

    fun normalizeSubId(subId: Int) = if (subId < 0) -1 else subId

    /** Single-line preview of a body, as carried by every `message.new` event. */
    internal fun preview(body: String): String {
        val singleLine = body.replace('\n', ' ').trim()
        return if (singleLine.length > MAX_PREVIEW_LENGTH) {
            singleLine.take(MAX_PREVIEW_LENGTH)
        } else {
            singleLine
        }
    }
}
