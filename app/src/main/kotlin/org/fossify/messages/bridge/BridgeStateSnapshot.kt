/*
 * SPDX-FileCopyrightText: 2026 The Fossify Messages bridge contributors
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.fossify.messages.bridge

import android.content.Context
import android.util.Log
import java.util.concurrent.TimeUnit

private const val TAG = "BridgeStateSnapshot"
private const val MAX_SNAPSHOT_THREADS = 50
private const val MAX_SNAPSHOT_MESSAGES = 200
private const val MAX_PREVIEW_LENGTH = 120
private const val SNAPSHOT_WINDOW_HOURS = 24
private val SNAPSHOT_WINDOW_MS = TimeUnit.HOURS.toMillis(SNAPSHOT_WINDOW_HOURS.toLong())

/**
 * Builds a bounded "current state" event set from the telephony provider.
 *
 * It is used when a cursor was lost (queue `reset: true`, an overflowing local
 * ring buffer) and on startup to fill the gap since the last successful push:
 * the bridge can then reconcile without a full history sync triggered by the
 * user.
 */
internal object BridgeStateSnapshot {

    /** Recent `thread.updated` and `message.new` events newer than [sinceMs], oldest first. */
    // A snapshot that cannot be built is skipped; the bridge will catch up on the next trigger.
    @Suppress("TooGenericExceptionCaught")
    fun recentEvents(context: Context, sinceMs: Long): List<BridgeEvent> {
        val events = ArrayList<BridgeEvent>()
        val threads = try {
            BridgeQueries.threads(context)
        } catch (e: Exception) {
            Log.e(TAG, "failed to read threads for the state snapshot", e)
            emptyList()
        }

        for (thread in threads.sortedByDescending { it.dateMs }.take(MAX_SNAPSHOT_THREADS)) {
            if (events.size >= MAX_SNAPSHOT_MESSAGES) {
                break
            }
            events.add(
                BridgeEvent(
                    type = BridgeEvents.TYPE_THREAD_UPDATED,
                    threadId = thread.id,
                    subId = thread.subId,
                    timestampMs = thread.dateMs,
                    isMms = false,
                    hasAttachment = thread.hasAttachment,
                    preview = thread.snippet.take(MAX_PREVIEW_LENGTH),
                    recipients = thread.recipients,
                )
            )

            val messages = try {
                BridgeQueries.threadMessages(
                    context = context,
                    threadId = thread.id,
                    beforeMs = 0,
                    afterMs = sinceMs,
                    limit = MAX_SNAPSHOT_MESSAGES,
                )
            } catch (e: Exception) {
                Log.e(TAG, "failed to read messages of thread ${thread.id}", e)
                emptyList()
            }
            for (message in messages) {
                if (events.size >= MAX_SNAPSHOT_MESSAGES) {
                    break
                }
                events.add(message.toReceivedEvent())
            }
        }
        return events.sortedBy { it.timestampMs }
    }

    /** The window start used when the phone has never pushed an event yet. */
    fun initialSinceMs(now: Long): Long {
        return now - SNAPSHOT_WINDOW_MS
    }

    private fun BridgeMessage.toReceivedEvent(): BridgeEvent {
        return BridgeEvent(
            type = BridgeEvents.TYPE_MESSAGE_NEW,
            messageId = id,
            threadId = threadId,
            subId = subId,
            timestampMs = timestampMs,
            sender = sender,
            isMms = isMms,
            hasAttachment = attachments.isNotEmpty(),
            preview = body.replace('\n', ' ').trim().take(MAX_PREVIEW_LENGTH),
        )
    }
}
