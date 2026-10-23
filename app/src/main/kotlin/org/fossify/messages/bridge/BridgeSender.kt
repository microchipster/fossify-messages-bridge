/*
 * SPDX-FileCopyrightText: 2026 microchipster
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.fossify.messages.bridge

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Telephony.Mms
import android.provider.Telephony.Sms
import android.telephony.SmsMessage
import android.util.Base64
import android.util.Log
import com.klinker.android.send_message.Message
import com.klinker.android.send_message.Settings
import com.klinker.android.send_message.Transaction
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.fossify.messages.extensions.getThreadId
import org.fossify.messages.extensions.messagingUtils
import org.fossify.messages.messaging.getSendMessageSettings
import org.fossify.messages.messaging.isLongMmsMessage
import org.fossify.messages.receivers.MmsSentReceiver

/** One outgoing MMS attachment of a POST /v1/send request. */
@Serializable
data class BridgeSendAttachment(
    @SerialName("content_type") val contentType: String = "",
    @SerialName("file_name") val fileName: String = "",
    val base64: String = "",
)

/** POST /v1/send body. */
@Serializable
data class BridgeSendRequest(
    @SerialName("send_id") val sendId: String = "",
    @SerialName("thread_id") val threadId: Long = 0,
    @SerialName("sub_id") val subId: Int = 0,
    val recipients: List<String> = emptyList(),
    val text: String = "",
    val attachments: List<BridgeSendAttachment> = emptyList(),
)

/** POST /v1/send response, mirroring the agent API send result. */
@Serializable
data class BridgeSendResponse(
    @SerialName("send_id") val sendId: String,
    @SerialName("message_id") val messageId: String,
    @SerialName("thread_id") val threadId: Long,
    @SerialName("parts_sent") val partsSent: Int,
    val pending: Boolean,
)

/** A send failure that maps directly onto an agent API error response. */
class BridgeSendException(val status: Int, val code: String, override val message: String) : Exception(message)

/**
 * Send path of the bridge API.
 *
 * SMS goes through the app's [org.fossify.messages.messaging.MessagingUtils] exactly like
 * the UI does. MMS is built as a single klinker [Message] carrying every attachment and
 * one text part, which fixes the upstream "N attachments become N separate MMS" bug; for
 * multi-recipient sends `Settings.group` is forced regardless of the UI preference.
 */
object BridgeSender {
    private const val TAG = "BridgeSender"
    private const val RESOLVE_TIMEOUT_MS = 30_000L
    private const val RESOLVE_POLL_INTERVAL_MS = 200L
    private const val MAX_ATTACHMENT_BYTES = 5 * 1024 * 1024

    fun send(context: Context, request: BridgeSendRequest): BridgeSendResponse {
        val sendId = request.sendId.ifBlank { "app-${System.currentTimeMillis()}" }

        var recipients = request.recipients.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (recipients.isEmpty() && request.threadId > 0) {
            recipients = BridgeQueries.threadRecipients(context, request.threadId).orEmpty()
        }
        if (recipients.isEmpty()) {
            throw BridgeSendException(400, "send_failed", "no recipients given")
        }
        if (request.text.isEmpty() && request.attachments.isEmpty()) {
            throw BridgeSendException(400, "send_failed", "message has no text and no attachments")
        }

        val subId = if (request.subId > 0) request.subId else Settings.DEFAULT_SUBSCRIPTION_ID
        val attachments = request.attachments.map { decodeAttachment(it) }
        val isMms = attachments.isNotEmpty() || recipients.size > 1 || context.isLongMmsMessage(request.text)

        return if (isMms) {
            sendMms(context, sendId, request.text, recipients, attachments, subId)
        } else {
            sendSms(context, sendId, recipients.first(), request.text, subId)
        }
    }

    private class OutgoingAttachment(val bytes: ByteArray, val contentType: String, val fileName: String)

    private fun decodeAttachment(attachment: BridgeSendAttachment): OutgoingAttachment {
        val bytes = try {
            Base64.decode(attachment.base64, Base64.DEFAULT)
        } catch (_: IllegalArgumentException) {
            throw BridgeSendException(400, "send_failed", "attachment '${attachment.fileName}' is not valid base64")
        }
        if (bytes.isEmpty()) {
            throw BridgeSendException(400, "send_failed", "attachment '${attachment.fileName}' is empty")
        }
        if (bytes.size > MAX_ATTACHMENT_BYTES) {
            throw BridgeSendException(413, "too_large", "attachment '${attachment.fileName}' is too large")
        }
        return OutgoingAttachment(
            bytes = bytes,
            contentType = attachment.contentType.ifBlank { "application/octet-stream" },
            fileName = attachment.fileName.ifBlank { "attachment" },
        )
    }

    private fun sendSms(
        context: Context,
        sendId: String,
        address: String,
        text: String,
        subId: Int,
    ): BridgeSendResponse {
        val settings = context.getSendMessageSettings()
        val effectiveSubId = if (subId > 0) subId else settings.subscriptionId
        val threadId = context.getThreadId(address)
        val markId = maxRowId(context, Sms.CONTENT_URI)

        try {
            context.messagingUtils.sendSmsMessage(
                text = text,
                addresses = setOf(address),
                subId = effectiveSubId,
                requireDeliveryReport = settings.deliveryReports,
            )
        } catch (e: Exception) {
            Log.e(TAG, "sending SMS failed", e)
            throw BridgeSendException(502, "send_failed", e.message ?: "sending SMS failed")
        }

        val row = resolveNewRow(context, Sms.CONTENT_URI, markId, threadId)
        val partsSent = SmsMessage.calculateLength(text, false).first().coerceAtLeast(1)
        val response = if (row != null) {
            BridgeSendResponse(
                sendId = sendId,
                messageId = BridgeEvents.messageId("sms", BridgeEvents.normalizeSubId(row.subId), row.id),
                threadId = row.threadId,
                partsSent = partsSent,
                pending = false,
            )
        } else {
            BridgeSendResponse(sendId = sendId, messageId = "", threadId = threadId, partsSent = partsSent, pending = true)
        }

        BridgeEvents.onMessageSent(
            context = context,
            kind = "sms",
            rowId = row?.id ?: 0,
            threadId = response.threadId,
            subId = row?.subId ?: effectiveSubId,
            body = text,
            isMms = false,
            hasAttachment = false,
            sendId = sendId,
        )
        return response
    }

    private fun sendMms(
        context: Context,
        sendId: String,
        text: String,
        recipients: List<String>,
        attachments: List<OutgoingAttachment>,
        subId: Int,
    ): BridgeSendResponse {
        val settings = context.getSendMessageSettings()
        // Group MMS must be forced for multi-recipient sends regardless of the UI preference.
        settings.group = recipients.size > 1 || settings.group
        if (subId > 0) {
            settings.subscriptionId = subId
        }
        val threadId = context.getThreadId(recipients.toSet())
        val markId = maxRowId(context, Mms.CONTENT_URI)

        val transaction = Transaction(context, settings)
        val message = Message(text, recipients.toTypedArray())
        attachments.forEach { attachment ->
            message.addMedia(attachment.bytes, attachment.contentType, attachment.fileName, attachment.fileName)
        }
        val mmsSentIntent = Intent(context, MmsSentReceiver::class.java)
        transaction.setExplicitBroadcastForSentMms(mmsSentIntent)

        try {
            transaction.sendNewMessage(message)
        } catch (e: Exception) {
            Log.e(TAG, "sending MMS failed", e)
            throw BridgeSendException(502, "send_failed", e.message ?: "sending MMS failed")
        }

        val row = resolveNewRow(context, Mms.CONTENT_URI, markId, threadId)
        val partsSent = attachments.size + if (text.isNotEmpty()) 1 else 0
        val response = if (row != null) {
            BridgeSendResponse(
                sendId = sendId,
                messageId = BridgeEvents.messageId("mms", BridgeEvents.normalizeSubId(row.subId), row.id),
                threadId = row.threadId,
                partsSent = partsSent,
                pending = false,
            )
        } else {
            BridgeSendResponse(sendId = sendId, messageId = "", threadId = threadId, partsSent = partsSent, pending = true)
        }

        BridgeEvents.onMessageSent(
            context = context,
            kind = "mms",
            rowId = row?.id ?: 0,
            threadId = response.threadId,
            subId = row?.subId ?: subId,
            body = text,
            isMms = true,
            hasAttachment = attachments.isNotEmpty(),
            sendId = sendId,
        )
        return response
    }

    private class ResolvedRow(val id: Long, val threadId: Long, val subId: Int)

    private fun maxRowId(context: Context, uri: Uri): Long {
        var max = 0L
        try {
            context.contentResolver.query(uri, arrayOf("_id"), null, null, "_id DESC LIMIT 1")?.use { cursor ->
                if (cursor.moveToFirst()) {
                    max = cursor.getLong(0)
                }
            }
        } catch (_: Exception) {
        }
        return max
    }

    /** Waits until a row newer than [minId] appears (the provider row created by the send). */
    private fun resolveNewRow(context: Context, uri: Uri, minId: Long, threadId: Long): ResolvedRow? {
        val deadline = System.currentTimeMillis() + RESOLVE_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val row = queryNewRow(context, uri, minId, threadId)
            if (row != null) {
                return row
            }
            try {
                Thread.sleep(RESOLVE_POLL_INTERVAL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        }
        return null
    }

    private fun queryNewRow(context: Context, uri: Uri, minId: Long, threadId: Long): ResolvedRow? {
        try {
            context.contentResolver.query(
                uri,
                arrayOf("_id", "thread_id", "sub_id"),
                "_id > ?",
                arrayOf(minId.toString()),
                "_id DESC LIMIT 1",
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val row = ResolvedRow(cursor.getLong(0), cursor.getLong(1), cursor.getInt(2))
                    if (threadId <= 0 || row.threadId == threadId) {
                        return row
                    }
                }
            }
        } catch (_: Exception) {
        }
        return null
    }
}
