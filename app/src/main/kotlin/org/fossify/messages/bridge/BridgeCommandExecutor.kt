/*
 * SPDX-FileCopyrightText: 2026 microchipster
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.fossify.messages.bridge

import android.content.Context
import android.util.Log

private const val TAG = "BridgeCommandExecutor"
private const val TYPE_SEND_RESULT = "send.result"
private const val TYPE_ACK = "ack"

/**
 * Runs one queue command and turns it into the event the bridge expects:
 * `send.result` for `send_sms` / `send_mms` and `ack` for `mark_read` / `ping`
 * (see `docs/PAIRING_PROTOCOL.md`).
 *
 * Commands are executed through the same code the local HTTP API and the UI
 * use ([BridgeSender] and [BridgeQueries]), so both transports behave
 * identically.
 */
internal class BridgeCommandExecutor(private val context: Context, private val outbox: PairingOutbox) {

    fun execute(command: QueueCommand) {
        when (command.type) {
            QueueCommand.TYPE_SEND_SMS, QueueCommand.TYPE_SEND_MMS -> executeSend(command)
            QueueCommand.TYPE_MARK_READ -> executeMarkRead(command)
            QueueCommand.TYPE_PING -> outbox.enqueue(ackEvent(command, null))
            QueueCommand.TYPE_FETCH_MESSAGE -> executeFetchMessage(command)
            else -> {
                Log.w(TAG, "unknown queue command type '${command.type}'")
                val error = BridgeEventError("unsupported", "unknown command type '${command.type}'")
                outbox.enqueue(ackEvent(command, error))
            }
        }
    }

    /**
     * Answers `fetch_message`: pushes the message again with its full payload
     * (inline MMS part bytes) and confirms with an `ack`.
     *
     * The bridge sends it when an event arrived without its payload — replayed
     * state snapshots drop the bytes to keep batches small — because the bridge
     * cannot fetch MMS parts from a NATed phone itself. The `ack` reports the
     * error and the message id when the payload cannot be provided (the message
     * is gone, or too large for one request); the bridge then mirrors the event
     * without media instead of losing it.
     */
    private fun executeFetchMessage(command: QueueCommand) {
        val messageId = command.messageId.trim()
        val message = messageId.takeIf { it.isNotEmpty() }?.let { BridgeQueries.messageForEvent(context, it) }
        if (message == null) {
            Log.w(TAG, "fetch_message: message '$messageId' is not in the provider")
            outbox.enqueue(ackEvent(command, BridgeEventError("not_found", "message $messageId is not available")))
            return
        }
        val event = messageNewEvent(message)
        if (eventBytes(outbox.json, event) > MAX_BATCH_BYTES) {
            // Pushing it would wedge the whole event pipeline behind a batch the
            // bridge rejects; the bridge mirrors the message without media.
            Log.w(TAG, "fetch_message: payload of '$messageId' is too large to push")
            val error = BridgeEventError("too_large", "the payload of $messageId exceeds the pairing batch size")
            outbox.enqueue(ackEvent(command, error))
            return
        }
        outbox.enqueue(event)
        outbox.enqueue(ackEvent(command, null))
    }

    private fun executeMarkRead(command: QueueCommand) {
        val updated = BridgeQueries.markThreadRead(context, command.threadId, command.upToMs)
        Log.i(TAG, "mark_read thread ${command.threadId} updated $updated rows")
        outbox.enqueue(
            BridgeEvent(
                type = BridgeEvents.TYPE_THREAD_UPDATED,
                threadId = command.threadId,
                subId = normalizedSubId(command.subId),
                timestampMs = if (command.upToMs > 0) command.upToMs else System.currentTimeMillis(),
                isMms = false,
                hasAttachment = false,
                recipients = BridgeQueries.threadRecipients(context, command.threadId) ?: emptyList(),
            )
        )
        outbox.enqueue(ackEvent(command, null))
    }

    // An unexpected send failure is reported back to the bridge as a send.result error.
    @Suppress("TooGenericExceptionCaught")
    private fun executeSend(command: QueueCommand) {
        val sendId = command.sendId.ifBlank { "queue-${command.seq}" }
        val cached = outbox.findRecentResult(sendId)
        if (cached != null) {
            Log.i(TAG, "queue command $sendId was already executed, re-sending its result")
            outbox.enqueue(cached)
            return
        }

        val subId = normalizedSubId(command.subId)
        val isMms = command.type == QueueCommand.TYPE_SEND_MMS || command.attachments.isNotEmpty()
        val hasAttachment = command.attachments.isNotEmpty()
        val result = try {
            val response = BridgeSender.send(
                context,
                BridgeSendRequest(
                    sendId = sendId,
                    threadId = command.threadId,
                    subId = command.subId,
                    recipients = command.recipients,
                    text = command.text,
                    attachments = command.attachments,
                ),
            )
            BridgeEvent(
                type = TYPE_SEND_RESULT,
                messageId = response.messageId,
                threadId = response.threadId,
                subId = subId,
                timestampMs = System.currentTimeMillis(),
                isMms = isMms,
                hasAttachment = hasAttachment,
                sendId = sendId,
                partsSent = response.partsSent,
                pending = response.pending,
            )
        } catch (e: BridgeSendException) {
            Log.w(TAG, "send $sendId failed: ${e.code}: ${e.message}")
            errorResult(command, sendId, subId, isMms, hasAttachment, e.code, e.message)
        } catch (e: Exception) {
            Log.e(TAG, "send $sendId failed", e)
            errorResult(command, sendId, subId, isMms, hasAttachment, "send_failed", e.message ?: "sending failed")
        }

        outbox.rememberResult(result)
        outbox.enqueue(result)
    }

    private fun errorResult(
        command: QueueCommand,
        sendId: String,
        subId: Int,
        isMms: Boolean,
        hasAttachment: Boolean,
        code: String,
        message: String,
    ): BridgeEvent {
        return BridgeEvent(
            type = TYPE_SEND_RESULT,
            threadId = command.threadId,
            subId = subId,
            timestampMs = System.currentTimeMillis(),
            isMms = isMms,
            hasAttachment = hasAttachment,
            sendId = sendId,
            error = BridgeEventError(code, message),
        )
    }

    private fun ackEvent(command: QueueCommand, error: BridgeEventError?): BridgeEvent {
        return BridgeEvent(
            type = TYPE_ACK,
            messageId = command.messageId,
            threadId = command.threadId,
            subId = normalizedSubId(command.subId),
            timestampMs = System.currentTimeMillis(),
            isMms = false,
            hasAttachment = false,
            sendId = command.sendId.ifBlank { "ack-${command.seq}" },
            error = error,
            commandSeq = command.seq,
        )
    }

    private fun normalizedSubId(subId: Int): Int {
        return if (subId > 0) subId else -1
    }
}

/**
 * The `message.new` event the bridge receives for a `fetch_message` command:
 * the full message payload, including inline base64 MMS part bytes.
 */
internal fun messageNewEvent(message: BridgeMessage): BridgeEvent {
    return BridgeEvent(
        type = BridgeEvents.TYPE_MESSAGE_NEW,
        messageId = message.id,
        threadId = message.threadId,
        subId = message.subId,
        timestampMs = message.timestampMs,
        sender = message.sender,
        isMms = message.isMms,
        hasAttachment = message.attachments.isNotEmpty(),
        preview = BridgeEvents.preview(message.body),
        message = message,
    )
}
