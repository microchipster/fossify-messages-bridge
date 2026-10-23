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
            else -> {
                Log.w(TAG, "unknown queue command type '${command.type}'")
                val error = BridgeEventError("unsupported", "unknown command type '${command.type}'")
                outbox.enqueue(ackEvent(command, error))
            }
        }
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
            )
        )
        outbox.enqueue(ackEvent(command, null))
    }

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
            threadId = command.threadId,
            subId = normalizedSubId(command.subId),
            timestampMs = System.currentTimeMillis(),
            isMms = false,
            hasAttachment = false,
            sendId = command.sendId.ifBlank { "ack-${command.seq}" },
            error = error,
        )
    }

    private fun normalizedSubId(subId: Int): Int {
        return if (subId > 0) subId else -1
    }
}
