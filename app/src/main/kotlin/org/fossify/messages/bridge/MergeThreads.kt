/*
 * SPDX-FileCopyrightText: 2026 microchipster
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.fossify.messages.bridge

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.messages.R
import org.fossify.messages.models.Events
import org.greenrobot.eventbus.EventBus
import java.util.UUID

private const val TAG = "MergeThreads"
private const val SEND_ID_RANDOM_LENGTH = 8

/**
 * One conversation selected for a merge: what the confirmation dialog and the
 * `merge_threads` request need.
 */
internal data class MergeCandidate(
    val threadId: Long,
    val title: String,
    val address: String,
)

/**
 * The conversations the user selected in the conversation list, reduced to the
 * `merge_threads` payload. Everything here is pure Kotlin, so the selection
 * rules and the payload shape are unit-testable without Android.
 *
 * The bridge runs the same validation and merge as `!sms merge --yes`: the
 * confirmation dialog the user saw is the confirmation step, so a valid
 * selection is applied directly and a refused one comes back as an error.
 */
internal data class MergeSelection(
    val candidates: List<MergeCandidate>,
    val preferredTargetThreadId: Long = 0L,
) {
    /** Selected thread ids in selection order, without duplicates. */
    val threadIds: List<Long>
        get() = candidates.map { it.threadId }.distinct()

    /** True when the selection can become one merged group chat. */
    val canMerge: Boolean
        get() = threadIds.size >= 2

    /** The thread whose room becomes the merged target (the first one by default). */
    val targetThreadId: Long
        get() = preferredTargetThreadId.takeIf { it in threadIds } ?: threadIds.firstOrNull() ?: 0L

    /** One line per selected conversation for the confirmation dialog. */
    fun participantLines(): List<String> {
        return candidates.map { candidate ->
            listOf(candidate.title.trim(), candidate.address.trim())
                .filter { it.isNotEmpty() }
                .joinToString(" — ")
        }
    }

    /** The `merge_threads` request event for this selection. */
    fun requestEvent(sendId: String, timestampMs: Long): BridgeEvent {
        val ids = threadIds
        require(ids.size >= 2) { "a merge request needs at least two threads" }
        return BridgeEvent(
            type = BridgeEvents.TYPE_MERGE_THREADS,
            threadId = targetThreadId,
            // The phone thread identity is implicit in the request; the bridge
            // matches the threads by id.
            subId = BridgeEvents.normalizeSubId(-1),
            timestampMs = timestampMs,
            isMms = false,
            hasAttachment = false,
            sendId = sendId,
            threadIds = ids,
            targetThread = targetThreadId,
        )
    }
}

/** The bridge's verdict on a `merge_threads` request, shown to the user. */
@Serializable
internal data class MergeOutcome(
    val ok: Boolean,
    val roomId: String = "",
    val message: String = "",
)

/**
 * App side of the app -> bridge merge request: builds the `merge_threads` event,
 * pushes it through the pairing outbox and holds the bridge's
 * `merge_threads.result` until the conversation list can show it.
 */
internal object MergeThreads {
    private val json = Json {
        encodeDefaults = false
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    /** Generates the correlation id of a merge request (echoed in its result). */
    fun newSendId(timestampMs: Long): String {
        return "merge-$timestampMs-${UUID.randomUUID().toString().take(SEND_ID_RANDOM_LENGTH)}"
    }

    /** True when a paired bridge can answer merge requests. */
    fun isAvailable(context: Context): Boolean {
        return PairingStore.newInstance(context).isPaired
    }

    /** Confirmation text of the merge dialog: participants plus the tombstone warning. */
    fun confirmationMessage(context: Context, selection: MergeSelection): String {
        val participants = selection.participantLines().joinToString("\n")
        return context.getString(R.string.merge_threads_confirmation, selection.threadIds.size, participants)
    }

    /** Enqueues one merge request and pushes it in the background. */
    fun request(context: Context, selection: MergeSelection) {
        val store = PairingStore.newInstance(context)
        if (!store.isPaired || !selection.canMerge) {
            Log.w(TAG, "ignoring merge request: not paired or fewer than two threads")
            return
        }
        val now = System.currentTimeMillis()
        val event = selection.requestEvent(newSendId(now), now)
        PairingOutbox(context, store).enqueue(event)
        BridgeService.maybeStart(context)
        // The poller flushes on its own schedule; syncOnce pushes the request
        // now and picks up the result as soon as the bridge has answered.
        ensureBackgroundThread {
            try {
                PairingPoller(context).syncOnce()
            } catch (e: PairingException) {
                Log.w(TAG, "merge request push failed: ${e.code}: ${e.message}")
            }
        }
    }

    /** The outcome carried by a `merge_threads.result` queue command. */
    fun outcomeFor(command: QueueCommand): MergeOutcome {
        val error = command.error
        val ok = error == null && command.roomId.isNotEmpty()
        val message = listOfNotNull(error?.code, error?.message).filter { it.isNotEmpty() }.joinToString(": ")
        return MergeOutcome(ok = ok, roomId = command.roomId, message = message)
    }

    /** Stores the outcome and wakes the foreground list so it can show it. */
    fun rememberOutcome(context: Context, outcome: MergeOutcome) {
        PairingStore.newInstance(context).mergeOutcome =
            json.encodeToString(MergeOutcome.serializer(), outcome)
        EventBus.getDefault().post(Events.MergeResult())
    }

    /** Returns the pending outcome once and clears it, so it is shown exactly once. */
    // A persisted outcome that cannot be parsed is dropped instead of breaking the UI.
    @Suppress("TooGenericExceptionCaught")
    fun consumeOutcome(context: Context): MergeOutcome? {
        val store = PairingStore.newInstance(context)
        val raw = store.mergeOutcome
        if (raw.isEmpty()) {
            return null
        }
        store.mergeOutcome = ""
        return try {
            json.decodeFromString(MergeOutcome.serializer(), raw)
        } catch (e: Exception) {
            Log.e(TAG, "failed to parse the stored merge outcome", e)
            null
        }
    }
}
