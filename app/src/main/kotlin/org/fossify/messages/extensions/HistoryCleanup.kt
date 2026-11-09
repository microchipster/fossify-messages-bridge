/*
 * SPDX-FileCopyrightText: 2026 The Fossify Messages bridge contributors
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.fossify.messages.extensions

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.Telephony.Mms
import android.provider.Telephony.Sms
import org.fossify.commons.helpers.DAY_SECONDS
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.messages.R
import org.fossify.messages.helpers.HistoryRetention
import org.fossify.messages.helpers.MessagingCache
import org.fossify.messages.helpers.RetentionMessage
import org.fossify.messages.helpers.refreshConversations
import org.fossify.messages.helpers.refreshMessages
import org.fossify.messages.receivers.HistoryCleanupReceiver

private const val HISTORY_CLEANUP_REQUEST_CODE = 1102

/** Arms the daily retention check. It is re-armed after a reboot by `RescheduleAlarmsReceiver`. */
fun Context.scheduleHistoryCleanup() {
    val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val intent = Intent(this, HistoryCleanupReceiver::class.java)
    val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    val pendingIntent = PendingIntent.getBroadcast(this, HISTORY_CLEANUP_REQUEST_CODE, intent, flags)
    alarmManager.setInexactRepeating(
        AlarmManager.RTC,
        System.currentTimeMillis() + AlarmManager.INTERVAL_DAY,
        AlarmManager.INTERVAL_DAY,
        pendingIntent,
    )
}

/**
 * Runs the retention check at most once a day, for example when the app is
 * opened. It complements the daily alarm, which some devices drop while the
 * app is idle.
 */
fun Context.checkAndRunHistoryCleanup() {
    if (config.lastHistoryCleanupCheck >= System.currentTimeMillis() - DAY_SECONDS * 1000L) {
        return
    }
    config.lastHistoryCleanupCheck = System.currentTimeMillis()
    ensureBackgroundThread {
        runHistoryCleanup()
    }
}

/** The retention value shown in the settings, e.g. "30 days" or "Keep forever". */
fun Context.historyRetentionText(days: Int): String {
    return if (days <= HistoryRetention.KEEP_FOREVER) {
        getString(R.string.history_retention_forever)
    } else {
        resources.getQuantityString(R.plurals.history_retention_days, days, days)
    }
}

/**
 * Deletes every message that is over its conversation retention or matches an
 * enabled redaction pattern. Runs on the calling thread and returns the number
 * of deleted messages.
 *
 * The row is removed from the system telephony provider first, so a delete
 * that the provider refuses (for example without the SMS role) keeps the
 * mirrored row instead of resurrecting the message on the next sync.
 */
fun Context.runHistoryCleanup(): Int {
    val retentionDays = config.historyRetentionDays
    val overrides = config.historyRetentionOverrides
    val redactions = config.historyRedactions
    if (retentionDays <= HistoryRetention.KEEP_FOREVER && overrides.isEmpty() && redactions.isEmpty) {
        return 0
    }

    val candidates = try {
        messagesDB.getAll().filterNot { it.isScheduled }
    } catch (_: Exception) {
        return 0
    }
    val decision = HistoryRetention.decide(
        messages = candidates.map {
            RetentionMessage(
                id = it.id,
                threadId = it.threadId,
                dateSeconds = it.date.toLong(),
                body = it.body,
                isMMS = it.isMMS,
            )
        },
        globalRetentionDays = retentionDays,
        conversationOverrides = overrides,
        redactions = redactions,
        nowSeconds = System.currentTimeMillis() / 1000L,
    )

    val deleted = decision.messages.filter { deleteRetainedMessage(it) }
    if (deleted.isEmpty()) {
        return 0
    }

    val deletedIds = deleted.map { it.id }
    try {
        attachmentsDB.deleteForMessages(deletedIds)
        messageAttachmentsDB.deleteForMessages(deletedIds)
    } catch (_: Exception) {
    }

    for (threadId in deleted.map { it.threadId }.distinct()) {
        try {
            refreshConversationAfterCleanup(threadId)
        } catch (_: Exception) {
        }
    }

    refreshMessages()
    refreshConversations()
    return deleted.size
}

private fun Context.deleteRetainedMessage(message: RetentionMessage): Boolean {
    val uri = if (message.isMMS) Mms.CONTENT_URI else Sms.CONTENT_URI
    return try {
        contentResolver.delete(uri, "${Sms._ID} = ?", arrayOf(message.id.toString()))
        messagesDB.delete(message.id)
        true
    } catch (_: Exception) {
        false
    }
}

/**
 * Keeps the local conversation mirror consistent after its messages were
 * deleted: refresh the snippet and date while messages remain, drop the
 * conversation when none remain. A conversation that only holds scheduled
 * messages is left untouched.
 */
private fun Context.refreshConversationAfterCleanup(threadId: Long) {
    val remaining = messagesDB.getNonRecycledThreadMessages(threadId)
    when {
        remaining.any { !it.isScheduled } -> updateLastConversationMessage(threadId)
        remaining.isEmpty() -> {
            conversationsDB.deleteThreadId(threadId)
            MessagingCache.participantsCache.remove(threadId)
        }

        else -> Unit
    }
}
