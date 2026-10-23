/*
 * SPDX-FileCopyrightText: 2026 microchipster
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.fossify.messages.bridge

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.provider.Telephony.Mms
import android.provider.Telephony.Sms
import android.provider.Telephony.Threads
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.google.android.mms.pdu_alt.PduHeaders
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.fossify.commons.models.SimpleContact
import org.fossify.messages.extensions.conversationsDB
import org.fossify.messages.extensions.getConversations
import org.fossify.messages.extensions.getThreadParticipants
import org.fossify.messages.extensions.messagesDB
import org.fossify.messages.messaging.MessagingUtils.Companion.ADDRESS_SEPARATOR

/** A non-text MMS part, mirroring the agent API attachment object. */
@Serializable
data class BridgeAttachment(
    @SerialName("part_id") val partId: Long,
    @SerialName("content_type") val contentType: String,
    @SerialName("file_name") val fileName: String = "",
    val size: Long = 0,
    val url: String = "",
)

/** Normalized SMS/MMS message, mirroring the agent API message object. */
@Serializable
data class BridgeMessage(
    val id: String,
    @SerialName("thread_id") val threadId: Long,
    @SerialName("sub_id") val subId: Int,
    @SerialName("timestamp_ms") val timestampMs: Long,
    @SerialName("timestamp_sent_ms") val timestampSentMs: Long = 0,
    val sender: String = "",
    val recipients: List<String>,
    val body: String = "",
    @SerialName("is_mms") val isMms: Boolean,
    val type: Int,
    val read: Boolean,
    val seen: Boolean,
    val status: Int,
    @SerialName("text_only") val textOnly: Boolean = false,
    val attachments: List<BridgeAttachment> = emptyList(),
)

/** One telephony conversation, mirroring the agent API thread object. */
@Serializable
data class BridgeThread(
    val id: Long,
    @SerialName("sub_id") val subId: Int,
    @SerialName("date_ms") val dateMs: Long,
    @SerialName("message_count") val messageCount: Int,
    val recipients: List<String>,
    val snippet: String = "",
    val read: Boolean,
    @SerialName("has_attachment") val hasAttachment: Boolean,
    @SerialName("unread_count") val unreadCount: Int = 0,
)

/** One SIM subscription, mirroring the agent API subscription object. */
@Serializable
data class BridgeSubscription(
    @SerialName("sub_id") val subId: Int,
    @SerialName("sim_slot") val simSlot: Int = 0,
    @SerialName("display_name") val displayName: String = "",
    val carrier: String = "",
    val number: String = "",
    @SerialName("default_sms") val defaultSms: Boolean = false,
)

@Serializable
data class BridgeDeviceInfo(
    val serial: String,
    val model: String = "",
    @SerialName("android_sdk") val androidSdk: Int = 0,
    @SerialName("android_release") val androidRelease: String = "",
)

@Serializable
data class BridgeBackendInfo(
    val read: String,
    @SerialName("send_sms") val sendSms: String = "",
    @SerialName("send_mms") val sendMms: Boolean,
    @SerialName("can_mark_read") val canMarkRead: Boolean,
    val root: Boolean,
    @SerialName("default_sms_app") val defaultSmsApp: String = "",
)

@Serializable
data class BridgeHealth(
    val ok: Boolean,
    @SerialName("protocol_version") val protocolVersion: Int,
    val device: BridgeDeviceInfo,
    val root: Boolean,
    val backend: BridgeBackendInfo,
    @SerialName("default_sms_app") val defaultSmsApp: String = "",
    val capabilities: List<String>,
)

/**
 * Provider-backed data access for the bridge API.
 *
 * The telephony content provider is the source of truth; the Room database is
 * only an overlay used by the app UI, so all history is read from and all read
 * state is written to the provider.
 */
// Cohesive provider data-access layer: many small query helpers over one cursor model.
@Suppress("TooManyFunctions", "LargeClass")
object BridgeQueries {
    const val PROTOCOL_VERSION = 1
    const val DEFAULT_HISTORY_LIMIT = 200
    const val MAX_HISTORY_LIMIT = 1000

    private const val MMS_TYPE_NOTIFICATION_IND = 130
    private const val MMS_TYPE_NOTIFY_RESP_IND = 131
    private const val MMS_TYPE_DELIVERY_IND = 134

    // Column positions of `smsProjection`, in projection order.
    private const val SMS_COL_ID = 0
    private const val SMS_COL_THREAD_ID = 1
    private const val SMS_COL_ADDRESS = 2
    private const val SMS_COL_DATE = 3
    private const val SMS_COL_DATE_SENT = 4
    private const val SMS_COL_TYPE = 5
    private const val SMS_COL_READ = 6
    private const val SMS_COL_SEEN = 7
    private const val SMS_COL_STATUS = 8
    private const val SMS_COL_SUBSCRIPTION_ID = 9
    private const val SMS_COL_BODY = 10

    // Column positions of `mmsProjection`, in projection order.
    private const val MMS_COL_ID = 0
    private const val MMS_COL_THREAD_ID = 1
    private const val MMS_COL_DATE = 2
    private const val MMS_COL_MESSAGE_BOX = 3
    private const val MMS_COL_MESSAGE_TYPE = 4
    private const val MMS_COL_READ = 5
    private const val MMS_COL_SEEN = 6
    private const val MMS_COL_STATUS = 7
    private const val MMS_COL_SUBSCRIPTION_ID = 8
    private const val MMS_COL_TEXT_ONLY = 9

    // Column positions of `partProjection`, in projection order.
    private const val PART_COL_ID = 0
    private const val PART_COL_CONTENT_TYPE = 1
    private const val PART_COL_TEXT = 2
    private const val PART_COL_NAME = 3
    private const val PART_COL_FILE_NAME = 4
    private const val PART_COL_CONTENT_LOCATION = 5
    private const val PART_COL_CONTENT_ID = 6

    // Column positions of the raw `content://telephony/siminfo` fallback query.
    private const val SIMINFO_COL_SUB_ID = 0
    private const val SIMINFO_COL_SIM_SLOT = 1
    private const val SIMINFO_COL_DISPLAY_NAME = 2
    private const val SIMINFO_COL_CARRIER_NAME = 3
    private const val SIMINFO_COL_NUMBER = 4

    // Notification/ack rows are not user visible messages. SEND_REQ (128) is
    // kept so a just-persisted outgoing MMS can be resolved right after send.
    private const val MMS_NOT_USER_VISIBLE = "${Mms.MESSAGE_TYPE} NOT IN ($MMS_TYPE_NOTIFICATION_IND, " +
        "$MMS_TYPE_NOTIFY_RESP_IND, $MMS_TYPE_DELIVERY_IND)"

    private val smsProjection = arrayOf(
        Sms._ID,
        Sms.THREAD_ID,
        Sms.ADDRESS,
        Sms.DATE,
        Sms.DATE_SENT,
        Sms.TYPE,
        Sms.READ,
        Sms.SEEN,
        Sms.STATUS,
        Sms.SUBSCRIPTION_ID,
        Sms.BODY,
    )

    private val mmsProjection = arrayOf(
        Mms._ID,
        Mms.THREAD_ID,
        Mms.DATE,
        Mms.MESSAGE_BOX,
        Mms.MESSAGE_TYPE,
        Mms.READ,
        Mms.SEEN,
        Mms.STATUS,
        Mms.SUBSCRIPTION_ID,
        Mms.TEXT_ONLY,
    )

    private val partProjection = arrayOf(
        Mms.Part._ID,
        Mms.Part.CONTENT_TYPE,
        Mms.Part.TEXT,
        Mms.Part.NAME,
        Mms.Part.FILENAME,
        Mms.Part.CONTENT_LOCATION,
        Mms.Part.CONTENT_ID,
    )

    fun health(context: Context): BridgeHealth {
        val defaultSmsApp = try {
            Sms.getDefaultSmsPackage(context).orEmpty()
        } catch (_: Exception) {
            ""
        }
        val isDefaultSmsApp = defaultSmsApp == context.packageName
        val capabilities = mutableListOf("threads", "messages", "mms-parts", "events", "subscriptions", "send-sms")
        if (isDefaultSmsApp) {
            capabilities.add("mark-read")
            capabilities.add("send-mms")
        }

        return BridgeHealth(
            ok = true,
            protocolVersion = PROTOCOL_VERSION,
            device = BridgeDeviceInfo(
                serial = deviceSerial(context),
                model = Build.MODEL.orEmpty(),
                androidSdk = Build.VERSION.SDK_INT,
                androidRelease = Build.VERSION.RELEASE.orEmpty(),
            ),
            root = false,
            backend = BridgeBackendInfo(
                read = "content-provider",
                sendSms = "app",
                sendMms = isDefaultSmsApp,
                canMarkRead = isDefaultSmsApp,
                root = false,
                defaultSmsApp = defaultSmsApp,
            ),
            defaultSmsApp = defaultSmsApp,
            capabilities = capabilities,
        )
    }

    /** True while this fork holds the default SMS role, required for MMS send and read-state writeback. */
    fun isDefaultSmsApp(context: Context): Boolean {
        return try {
            Sms.getDefaultSmsPackage(context) == context.packageName
        } catch (_: Exception) {
            false
        }
    }

    fun subscriptions(context: Context): List<BridgeSubscription> {
        val infos = activeSubscriptionInfos(context)

        if (infos != null) {
            val defaultSubId = try {
                SubscriptionManager.getDefaultSmsSubscriptionId()
            } catch (_: Exception) {
                SubscriptionManager.INVALID_SUBSCRIPTION_ID
            }
            return infos.map { info ->
                BridgeSubscription(
                    subId = info.subscriptionId,
                    simSlot = info.simSlotIndex,
                    displayName = info.displayName?.toString().orEmpty(),
                    carrier = info.carrierName?.toString().orEmpty(),
                    number = info.number.orEmpty(),
                    defaultSms = info.subscriptionId == defaultSubId,
                )
            }.sortedBy { it.subId }
        }

        // Fallback for devices where the SubscriptionManager list is not accessible.
        val subscriptions = ArrayList<BridgeSubscription>()
        try {
            context.contentResolver.query(
                "content://telephony/siminfo".toUri(),
                arrayOf("_id", "sim_id", "display_name", "carrier_name", "number"),
                null,
                null,
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    subscriptions.add(
                        BridgeSubscription(
                            subId = cursor.getInt(SIMINFO_COL_SUB_ID),
                            simSlot = cursor.getInt(SIMINFO_COL_SIM_SLOT),
                            displayName = cursor.getStringOrEmpty(SIMINFO_COL_DISPLAY_NAME),
                            carrier = cursor.getStringOrEmpty(SIMINFO_COL_CARRIER_NAME),
                            number = cursor.getStringOrEmpty(SIMINFO_COL_NUMBER),
                        )
                    )
                }
            }
        } catch (_: Exception) {
        }
        return subscriptions.sortedBy { it.subId }
    }

    /**
     * The active subscriptions, or null when the list is not accessible (missing READ_PHONE_STATE
     * or a provider failure), in which case callers fall back to the raw siminfo table.
     */
    private fun activeSubscriptionInfos(context: Context): List<SubscriptionInfo>? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        return try {
            context.getSystemService(SubscriptionManager::class.java)?.activeSubscriptionInfoList
        } catch (_: Exception) {
            null
        }
    }

    fun threads(context: Context): List<BridgeThread> {
        val conversations = try {
            context.getConversations()
        } catch (_: Exception) {
            emptyList()
        }

        val extras = threadExtras(context)
        return conversations.map { conversation ->
            val participants = threadRecipients(context, conversation.threadId)
                ?: listOfNotNull(conversation.phoneNumber.takeIf { it.isNotBlank() })
            val extra = extras[conversation.threadId]
            BridgeThread(
                id = conversation.threadId,
                subId = -1,
                dateMs = conversation.date * 1000L,
                messageCount = extra?.first ?: 0,
                recipients = participants,
                snippet = conversation.snippet,
                read = conversation.read,
                hasAttachment = extra?.second ?: false,
                unreadCount = conversation.unreadCount,
            )
        }
    }

    /** Returns one full thread object (participants, counts, read state), or null when unknown. */
    fun thread(context: Context, threadId: Long): BridgeThread? {
        val conversation = try {
            context.getConversations(threadId).firstOrNull()
        } catch (_: Exception) {
            null
        } ?: return null

        val participants = threadRecipients(context, threadId)
            ?: listOfNotNull(conversation.phoneNumber.takeIf { it.isNotBlank() })
        val extra = threadExtras(context)[threadId]
        return BridgeThread(
            id = conversation.threadId,
            subId = -1,
            dateMs = conversation.date * 1000L,
            messageCount = extra?.first ?: 0,
            recipients = participants,
            snippet = conversation.snippet,
            read = conversation.read,
            hasAttachment = extra?.second ?: false,
            unreadCount = conversation.unreadCount,
        )
    }

    /** Returns the canonical participant set of a thread, or null when the thread is unknown. */
    fun threadRecipients(context: Context, threadId: Long): List<String>? {
        val participants = try {
            context.getThreadParticipants(threadId, null)
        } catch (_: Exception) {
            return null
        }
        if (participants.isEmpty()) {
            return null
        }
        return participantsToAddresses(participants)
    }

    fun threadMessages(
        context: Context,
        threadId: Long,
        beforeMs: Long,
        afterMs: Long,
        limit: Int,
    ): List<BridgeMessage> {
        val effectiveLimit = if (limit <= 0) DEFAULT_HISTORY_LIMIT else limit.coerceAtMost(MAX_HISTORY_LIMIT)
        val threadParticipants = threadRecipients(context, threadId).orEmpty()

        val messages = ArrayList<BridgeMessage>()
        queryThreadSms(context, threadId, beforeMs, afterMs, effectiveLimit).forEach { row ->
            messages.add(buildSmsMessage(row, threadParticipants))
        }
        queryThreadMms(context, threadId, beforeMs, afterMs, effectiveLimit).forEach { row ->
            buildMmsMessage(context, row, threadParticipants)?.let { messages.add(it) }
        }

        messages.sortWith(compareBy({ it.timestampMs }, { it.id }))
        return if (messages.size > effectiveLimit) {
            messages.subList(messages.size - effectiveLimit, messages.size).toList()
        } else {
            messages
        }
    }

    /** Loads a single message by its wire id (`sms:<sub>:<row>` / `mms:<sub>:<row>`), or null. */
    fun message(context: Context, messageId: String): BridgeMessage? {
        val (kind, _, rowId) = BridgeMessageIds.parse(messageId) ?: return null
        return when (kind) {
            "sms" -> querySmsById(context, rowId)?.let { row ->
                val threadParticipants = threadRecipients(context, row.threadId).orEmpty()
                buildSmsMessage(row, threadParticipants)
            }

            "mms" -> queryMmsById(context, rowId)?.let { row ->
                val threadParticipants = threadRecipients(context, row.threadId).orEmpty()
                buildMmsMessage(context, row, threadParticipants)
            }

            else -> null
        }
    }

    /** Returns the raw bytes plus content type of one MMS part, or null when it does not exist. */
    fun partBytes(context: Context, mmsId: Long, partId: Long): Pair<ByteArray, String>? {
        val contentType = partContentType(context, mmsId, partId) ?: return null
        val bytes = partData(context, partId) ?: return null
        return bytes to contentType
    }

    private fun partContentType(context: Context, mmsId: Long, partId: Long): String? {
        return try {
            var contentType: String? = null
            context.contentResolver.query(
                "content://mms/$mmsId/part".toUri(),
                arrayOf(Mms.Part._ID, Mms.Part.CONTENT_TYPE),
                "${Mms.Part._ID} = ?",
                arrayOf(partId.toString()),
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    contentType = cursor.getStringOrEmpty(PART_COL_CONTENT_TYPE)
                }
            }
            contentType
        } catch (_: Exception) {
            null
        }
    }

    private fun partData(context: Context, partId: Long): ByteArray? {
        return try {
            context.contentResolver.openInputStream("content://mms/part/$partId".toUri())?.use { it.readBytes() }
        } catch (_: Exception) {
            null
        }
    }

    /** Marks all messages of a thread as read+seen, optionally only up to [upToMs]. Returns the updated row count. */
    fun markThreadRead(context: Context, threadId: Long, upToMs: Long): Int {
        var updated = 0
        val id = threadId.toString()

        try {
            val smsValues = ContentValues().apply {
                put(Sms.READ, 1)
                put(Sms.SEEN, 1)
            }
            val smsSelection = if (upToMs > 0) {
                "${Sms.THREAD_ID} = ? AND ${Sms.DATE} <= ?"
            } else {
                "${Sms.THREAD_ID} = ?"
            }
            val smsArgs = if (upToMs > 0) arrayOf(id, upToMs.toString()) else arrayOf(id)
            updated += context.contentResolver.update(Sms.CONTENT_URI, smsValues, smsSelection, smsArgs)
        } catch (_: Exception) {
        }

        try {
            val mmsValues = ContentValues().apply {
                put(Mms.READ, 1)
                put(Mms.SEEN, 1)
            }
            val mmsSelection = if (upToMs > 0) {
                "${Mms.THREAD_ID} = ? AND ${Mms.DATE} <= ?"
            } else {
                "${Mms.THREAD_ID} = ?"
            }
            val mmsArgs = if (upToMs > 0) arrayOf(id, (upToMs / 1000).toString()) else arrayOf(id)
            updated += context.contentResolver.update(Mms.CONTENT_URI, mmsValues, mmsSelection, mmsArgs)
        } catch (_: Exception) {
        }

        try {
            context.messagesDB.markThreadRead(threadId)
            context.conversationsDB.markRead(threadId)
        } catch (_: Exception) {
        }

        return updated
    }

    /** Marks the given wire message ids as read+seen. Returns the updated row count. */
    fun markMessagesRead(context: Context, messageIds: List<String>): Int {
        var updated = 0
        for (messageId in messageIds) {
            val (kind, _, rowId) = BridgeMessageIds.parse(messageId) ?: continue
            val uri = if (kind == "mms") Mms.CONTENT_URI else Sms.CONTENT_URI
            val values = ContentValues().apply {
                put(Sms.READ, 1)
                put(Sms.SEEN, 1)
            }
            try {
                updated += context.contentResolver.update(uri, values, "${Sms._ID} = ?", arrayOf(rowId.toString()))
                context.messagesDB.markRead(rowId)
            } catch (_: Exception) {
            }
        }
        return updated
    }

    // Flat mirror of the `smsProjection` columns; grouping fields would only add indirection.
    @Suppress("LongParameterList")
    private class SmsRow(
        val id: Long,
        val threadId: Long,
        val subId: Int,
        val address: String,
        val dateMs: Long,
        val dateSentMs: Long,
        val type: Int,
        val read: Boolean,
        val seen: Boolean,
        val status: Int,
        val body: String,
    )

    // Flat mirror of the `mmsProjection` columns; grouping fields would only add indirection.
    @Suppress("LongParameterList")
    private class MmsRow(
        val id: Long,
        val threadId: Long,
        val subId: Int,
        val dateMs: Long,
        val messageBox: Int,
        val messageType: Int,
        val read: Boolean,
        val seen: Boolean,
        val status: Int,
        val textOnly: Boolean,
    )

    private fun queryThreadSms(
        context: Context,
        threadId: Long,
        beforeMs: Long,
        afterMs: Long,
        limit: Int,
    ): List<SmsRow> {
        val conditions = StringBuilder("${Sms.THREAD_ID} = ? AND ${Sms.TYPE} != ${Sms.MESSAGE_TYPE_DRAFT}")
        val args = arrayListOf(threadId.toString())
        if (beforeMs > 0) {
            conditions.append(" AND ${Sms.DATE} < ?")
            args.add(beforeMs.toString())
        }
        if (afterMs > 0) {
            conditions.append(" AND ${Sms.DATE} >= ?")
            args.add(afterMs.toString())
        }
        val rows = ArrayList<SmsRow>()
        queryRows(
            context,
            Sms.CONTENT_URI,
            smsProjection,
            conditions.toString(),
            args.toTypedArray(),
            "${Sms.DATE} DESC LIMIT $limit",
        ) { cursor ->
            toSmsRow(cursor)?.let { row ->
                // The UI inserts a merged "a|b" dummy row for group SMS; it is not a real message.
                if (!row.address.contains(ADDRESS_SEPARATOR)) {
                    rows.add(row)
                }
            }
        }
        return rows
    }

    private fun querySmsById(context: Context, id: Long): SmsRow? {
        var row: SmsRow? = null
        queryRows(context, Sms.CONTENT_URI, smsProjection, "${Sms._ID} = ?", arrayOf(id.toString()), null) { cursor ->
            row = toSmsRow(cursor)
        }
        return row
    }

    private fun queryThreadMms(
        context: Context,
        threadId: Long,
        beforeMs: Long,
        afterMs: Long,
        limit: Int,
    ): List<MmsRow> {
        val conditions = StringBuilder(
            "${Mms.THREAD_ID} = ? AND ${Mms.MESSAGE_BOX} != ${Mms.MESSAGE_BOX_DRAFTS} AND $MMS_NOT_USER_VISIBLE"
        )
        val args = arrayListOf(threadId.toString())
        if (beforeMs > 0) {
            conditions.append(" AND ${Mms.DATE} < ?")
            args.add((beforeMs / 1000).toString())
        }
        if (afterMs > 0) {
            conditions.append(" AND ${Mms.DATE} >= ?")
            args.add((afterMs / 1000).toString())
        }
        val rows = ArrayList<MmsRow>()
        queryRows(
            context,
            Mms.CONTENT_URI,
            mmsProjection,
            conditions.toString(),
            args.toTypedArray(),
            "${Mms.DATE} DESC LIMIT $limit",
        ) { cursor ->
            toMmsRow(cursor)?.let { rows.add(it) }
        }
        return rows
    }

    private fun queryMmsById(context: Context, id: Long): MmsRow? {
        var row: MmsRow? = null
        queryRows(
            context,
            Mms.CONTENT_URI,
            mmsProjection,
            "${Mms._ID} = ? AND $MMS_NOT_USER_VISIBLE",
            arrayOf(id.toString()),
            null,
        ) { cursor ->
            row = toMmsRow(cursor)
        }
        return row
    }

    private fun toSmsRow(cursor: Cursor): SmsRow? {
        val address = cursor.getStringOrEmpty(SMS_COL_ADDRESS)
        return SmsRow(
            id = cursor.getLong(SMS_COL_ID),
            threadId = cursor.getLong(SMS_COL_THREAD_ID),
            subId = cursor.getInt(SMS_COL_SUBSCRIPTION_ID),
            address = address,
            dateMs = cursor.getLong(SMS_COL_DATE),
            dateSentMs = cursor.getLong(SMS_COL_DATE_SENT),
            type = cursor.getInt(SMS_COL_TYPE),
            read = cursor.getInt(SMS_COL_READ) == 1,
            seen = cursor.getInt(SMS_COL_SEEN) == 1,
            status = cursor.getInt(SMS_COL_STATUS),
            body = cursor.getStringOrEmpty(SMS_COL_BODY),
        )
    }

    private fun toMmsRow(cursor: Cursor): MmsRow? {
        if (cursor.isNull(MMS_COL_ID)) {
            return null
        }
        return MmsRow(
            id = cursor.getLong(MMS_COL_ID),
            threadId = cursor.getLong(MMS_COL_THREAD_ID),
            subId = cursor.getInt(MMS_COL_SUBSCRIPTION_ID),
            dateMs = cursor.getLong(MMS_COL_DATE) * 1000L,
            messageBox = cursor.getInt(MMS_COL_MESSAGE_BOX),
            messageType = cursor.getInt(MMS_COL_MESSAGE_TYPE),
            read = cursor.getInt(MMS_COL_READ) == 1,
            seen = cursor.getInt(MMS_COL_SEEN) == 1,
            status = cursor.getInt(MMS_COL_STATUS),
            textOnly = cursor.getInt(MMS_COL_TEXT_ONLY) == 1,
        )
    }

    private fun buildSmsMessage(row: SmsRow, threadParticipants: List<String>): BridgeMessage {
        val sender = if (row.type == Sms.MESSAGE_TYPE_INBOX && row.address.isNotBlank()) row.address else ""
        val recipients = if (sender.isNotEmpty()) {
            removeAddress(threadParticipants, sender)
        } else {
            threadParticipants
        }
        return BridgeMessage(
            id = BridgeMessageIds.format("sms", BridgeEvents.normalizeSubId(row.subId), row.id),
            threadId = row.threadId,
            subId = BridgeEvents.normalizeSubId(row.subId),
            timestampMs = row.dateMs,
            timestampSentMs = row.dateSentMs,
            sender = sender,
            recipients = recipients,
            body = row.body,
            isMms = false,
            type = row.type,
            read = row.read,
            seen = row.seen,
            status = row.status,
        )
    }

    private fun buildMmsMessage(
        context: Context,
        row: MmsRow,
        threadParticipants: List<String>,
    ): BridgeMessage? {
        val parts = queryParts(context, row.id)
        var body = ""
        val attachments = ArrayList<BridgeAttachment>()
        var textOnly = row.textOnly
        for (part in parts) {
            when {
                part.contentType == "text/plain" -> if (body.isEmpty()) {
                    body = part.text
                }

                part.contentType == "application/smil" -> {
                }

                else -> attachments.add(
                    BridgeAttachment(
                        partId = part.id,
                        contentType = part.contentType,
                        fileName = firstNonEmpty(part.name, part.fileName, part.contentLocation, part.contentId),
                        url = "/v1/mms/${row.id}/parts/${part.id}",
                    )
                )
            }
        }
        if (attachments.isEmpty()) {
            textOnly = true
        }

        val sender = if (row.messageBox == Mms.MESSAGE_BOX_INBOX) {
            mmsSender(context, row.id)
        } else {
            ""
        }

        var recipients = mmsRecipients(context, row.id)
        if (sender.isNotEmpty()) {
            recipients = removeAddress(recipients, sender)
        }
        if (recipients.isEmpty()) {
            recipients = if (sender.isNotEmpty()) {
                removeAddress(threadParticipants, sender)
            } else {
                threadParticipants
            }
        }

        return BridgeMessage(
            id = BridgeMessageIds.format("mms", BridgeEvents.normalizeSubId(row.subId), row.id),
            threadId = row.threadId,
            subId = BridgeEvents.normalizeSubId(row.subId),
            timestampMs = row.dateMs,
            sender = sender,
            recipients = recipients.distinct().sorted(),
            body = body,
            isMms = true,
            type = row.messageBox,
            read = row.read,
            seen = row.seen,
            status = row.status,
            textOnly = textOnly,
            attachments = attachments,
        )
    }

    private class PartRow(
        val id: Long,
        val contentType: String,
        val text: String,
        val name: String,
        val fileName: String,
        val contentLocation: String,
        val contentId: String,
    )

    private fun queryParts(context: Context, mmsId: Long): List<PartRow> {
        val parts = ArrayList<PartRow>()
        queryRows(
            context,
            "content://mms/$mmsId/part".toUri(),
            partProjection,
            null,
            null,
            "${Mms.Part.SEQ} ASC",
        ) { cursor ->
            val contentType = cursor.getStringOrEmpty(PART_COL_CONTENT_TYPE).lowercase()
            if (contentType.isNotEmpty()) {
                parts.add(toPartRow(cursor, contentType))
            }
        }
        return parts
    }

    private fun toPartRow(cursor: Cursor, contentType: String): PartRow {
        return PartRow(
            id = cursor.getLong(PART_COL_ID),
            contentType = contentType,
            text = cursor.getStringOrEmpty(PART_COL_TEXT),
            name = cursor.getStringOrEmpty(PART_COL_NAME),
            fileName = cursor.getStringOrEmpty(PART_COL_FILE_NAME),
            contentLocation = cursor.getStringOrEmpty(PART_COL_CONTENT_LOCATION),
            contentId = cursor.getStringOrEmpty(PART_COL_CONTENT_ID),
        )
    }

    private fun mmsSender(context: Context, mmsId: Long): String {
        return mmsAddresses(context, mmsId, PduHeaders.FROM).firstOrNull().orEmpty()
    }

    private fun mmsRecipients(context: Context, mmsId: Long): List<String> {
        return mmsAddresses(context, mmsId, PduHeaders.TO, PduHeaders.CC)
    }

    private fun mmsAddresses(context: Context, mmsId: Long, vararg types: Int): List<String> {
        val addresses = ArrayList<String>()
        queryRows(
            context,
            "content://mms/$mmsId/addr".toUri(),
            arrayOf(Mms.Addr.TYPE, Mms.Addr.ADDRESS),
            null,
            null,
            null,
        ) { cursor ->
            val type = cursor.getInt(0)
            if (types.contains(type)) {
                val address = cursor.getStringOrEmpty(1)
                if (address.isNotBlank() && !addresses.contains(address)) {
                    addresses.add(address)
                }
            }
        }
        return addresses
    }

    private fun threadExtras(context: Context): Map<Long, Pair<Int, Boolean>> {
        val extras = HashMap<Long, Pair<Int, Boolean>>()
        try {
            context.contentResolver.query(
                "${Threads.CONTENT_URI}?simple=true".toUri(),
                arrayOf(Threads._ID, Threads.MESSAGE_COUNT, Threads.HAS_ATTACHMENT),
                null,
                null,
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val count = cursor.getInt(1)
                    extras[cursor.getLong(0)] = count to (cursor.getInt(2) == 1)
                }
            }
        } catch (_: Exception) {
        }
        return extras
    }

    private fun participantsToAddresses(participants: List<SimpleContact>): List<String> {
        return participants.mapNotNull { participant ->
            participant.phoneNumbers.firstOrNull()?.normalizedNumber?.takeIf { it.isNotBlank() }
        }
    }

    private fun queryRows(
        context: Context,
        uri: Uri,
        projection: Array<String>,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
        callback: (Cursor) -> Unit,
    ) {
        try {
            context.contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
                while (cursor.moveToNext()) {
                    callback(cursor)
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun Cursor.getStringOrEmpty(index: Int): String {
        return if (isNull(index)) "" else getString(index).orEmpty()
    }

    private fun removeAddress(addresses: List<String>, address: String): List<String> {
        return addresses.filter { it != address }
    }

    private fun firstNonEmpty(vararg values: String): String {
        return values.firstOrNull { it.isNotBlank() }.orEmpty()
    }

    private fun deviceSerial(context: Context): String {
        val configured = BridgeConfig.newInstance(context).bridgeDeviceId
        if (configured.isNotBlank()) {
            return configured
        }

        val serial = try {
            hardwareSerial(context)
        } catch (_: Exception) {
            Build.SERIAL
        }
        if (!serial.isNullOrBlank() && serial != Build.UNKNOWN) {
            return serial
        }

        return try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * Best-effort hardware serial. [Build.getSerial] is annotated with the signature-level
     * READ_PRIVILEGED_PHONE_STATE from API 30 on, which a normal app can never hold; on the API
     * levels where it is readable (26-29) READ_PHONE_STATE is checked first.
     */
    // Lint demands the signature-level READ_PRIVILEGED_PHONE_STATE; the API 26-29 path is guarded below.
    @SuppressLint("MissingPermission")
    private fun hardwareSerial(context: Context): String {
        val canReadSerial = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED
        return if (canReadSerial) Build.getSerial() else Build.SERIAL
    }
}
