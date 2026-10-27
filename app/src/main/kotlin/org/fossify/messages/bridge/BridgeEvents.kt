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
    // Selected phone threads of a `merge_threads` request and the thread whose
    // room becomes the merged target (0 = first thread).
    @SerialName("thread_ids") val threadIds: List<Long> = emptyList(),
    @SerialName("target_thread") val targetThread: Long = 0,
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
// One builder per wire event type keeps the payload shapes testable; splitting
// the factory would obscure the feed contract.
@Suppress("TooGenericExceptionCaught", "TooManyFunctions")
object BridgeEvents {
    const val TYPE_MESSAGE_NEW = "message.new"
    const val TYPE_MESSAGE_UPDATED = "message.updated"
    const val TYPE_THREAD_UPDATED = "thread.updated"
    // App -> bridge request that asks the bridge to merge the rooms of several
    // phone threads (see MergeThreads).
    const val TYPE_MERGE_THREADS = "merge_threads"

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

    /**
     * Emits an event for a message sent through POST /v1/send or a queue
     * `send_sms` / `send_mms` command (carries the client [sendId]).
     *
     * The echo deliberately carries the send correlation ([BridgeEvent.sendId]
     * and, when the provider row is already known, [BridgeEvent.messageId]) and
     * no recipients: it is not a participant update. The bridge matches it
     * against the pending outgoing send whether it sees this event or the
     * `send.result` first, suppresses it, and never reconciles room membership
     * from its empty participant list.
     */
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
                sentEvent(
                    kind = kind,
                    rowId = rowId,
                    threadId = threadId,
                    subId = subId,
                    body = body,
                    isMms = isMms,
                    hasAttachment = hasAttachment,
                    sendId = sendId,
                    timestampMs = timestampMs,
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "failed to emit sent event", e)
        }
    }

    /**
     * Builds the outgoing `message.new` echo without touching Android state, so
     * the wire payload (send correlation present, recipients absent) is unit
     * testable.
     */
    internal fun sentEvent(
        kind: String,
        rowId: Long,
        threadId: Long,
        subId: Int,
        body: String,
        isMms: Boolean,
        hasAttachment: Boolean,
        sendId: String,
        timestampMs: Long = System.currentTimeMillis(),
    ): BridgeEvent {
        return BridgeEvent(
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
            recipients = emptyList(),
        )
    }

    /**
     * Emits a `thread.updated` event carrying the thread's current participants.
     *
     * A send can create a thread the bridge has never seen (a new group MMS to
     * several recipients). The `message.new` echo deliberately carries no
     * recipients, so without this update the bridge would create the portal
     * without members, name it "Unknown conversation" and have no recipients
     * to send with. Best effort: a thread whose participants cannot be read
     * emits nothing, so an empty list can never clear known room members.
     */
    fun onThreadUpdated(
        context: Context,
        threadId: Long,
        subId: Int,
        timestampMs: Long = System.currentTimeMillis(),
    ) {
        try {
            if (threadId <= 0 || !BridgeConfig.newInstance(context).isActive()) {
                return
            }
            val recipients = BridgeQueries.threadRecipients(context, threadId) ?: emptyList()
            if (recipients.isEmpty()) {
                return
            }
            append(threadUpdatedEvent(threadId, subId, recipients, timestampMs))
        } catch (e: Exception) {
            Log.e(TAG, "failed to emit thread update event", e)
        }
    }

    /**
     * Builds the `thread.updated` payload without touching Android state, so
     * the wire shape (participants present, no send correlation) is unit
     * testable.
     */
    internal fun threadUpdatedEvent(
        threadId: Long,
        subId: Int,
        recipients: List<String>,
        timestampMs: Long = System.currentTimeMillis(),
    ): BridgeEvent {
        return BridgeEvent(
            type = TYPE_THREAD_UPDATED,
            threadId = threadId,
            subId = normalizeSubId(subId),
            timestampMs = timestampMs,
            isMms = false,
            hasAttachment = false,
            recipients = recipients,
        )
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
