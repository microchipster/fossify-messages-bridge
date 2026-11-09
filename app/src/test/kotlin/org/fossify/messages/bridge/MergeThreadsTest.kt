/*
 * SPDX-FileCopyrightText: 2026 The Fossify Messages bridge contributors
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

@file:Suppress("MagicNumber")

package org.fossify.messages.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the conversation-list selection state and the `merge_threads` payload
 * the app pushes: the merge action is only usable with at least two distinct
 * threads, the target defaults to the first one, and the request carries the
 * wire field names the bridge expects.
 */
class MergeThreadsTest {
    private val json = Json {
        encodeDefaults = false
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    private fun candidate(threadId: Long, title: String = "Alice", address: String = "+15551234567") =
        MergeCandidate(threadId = threadId, title = title, address = address)

    private fun selection(vararg candidates: MergeCandidate, target: Long = 0L) =
        MergeSelection(candidates.toList(), preferredTargetThreadId = target)

    @Test
    fun selectionNeedsTwoDistinctThreads() {
        assertFalse(selection().canMerge)
        assertFalse(selection(candidate(11)).canMerge)
        assertTrue(selection(candidate(11), candidate(12)).canMerge)

        // Selecting the same thread twice (e.g. an inconsistent list) stays one.
        val duplicated = selection(candidate(11), candidate(11))
        assertEquals(listOf(11L), duplicated.threadIds)
        assertFalse(duplicated.canMerge)
    }

    @Test
    fun targetDefaultsToTheFirstThread() {
        assertEquals(11L, selection(candidate(11), candidate(12)).targetThreadId)
        assertEquals(12L, selection(candidate(11), candidate(12), target = 12).targetThreadId)
        // A preferred target outside the selection is ignored.
        assertEquals(11L, selection(candidate(11), candidate(12), target = 99).targetThreadId)
        // An empty selection has no target.
        assertEquals(0L, selection().targetThreadId)
    }

    @Test
    fun participantLinesCarryTitleAndAddresses() {
        val lines = selection(
            candidate(11, title = "Alice", address = "+15551234567"),
            candidate(12, title = "Bob", address = "+15557654321, +15550001111"),
        ).participantLines()

        assertEquals(listOf("Alice — +15551234567", "Bob — +15557654321, +15550001111"), lines)
    }

    @Test
    fun requestEventCarriesTheSelectionAndCorrelationId() {
        val event = selection(candidate(11), candidate(12), target = 12)
            .requestEvent(sendId = "merge-1761600000000-abcd", timestampMs = 1761600000000)

        assertEquals(BridgeEvents.TYPE_MERGE_THREADS, event.type)
        assertEquals(12L, event.threadId)
        assertEquals(12L, event.targetThread)
        assertEquals(listOf(11L, 12L), event.threadIds)
        assertEquals("merge-1761600000000-abcd", event.sendId)
        assertEquals(1761600000000L, event.timestampMs)
        assertFalse(event.isMms)
        assertEquals(-1, event.subId)
    }

    @Test
    fun requestEventUsesTheDocumentedWireFieldNames() {
        val event = selection(candidate(11), candidate(12), target = 12)
            .requestEvent(sendId = "merge-1", timestampMs = 1761600000000)

        val encoded = json.encodeToString(BridgeEvent.serializer(), event)
        val payload = json.parseToJsonElement(encoded).jsonObject
        assertEquals("merge_threads", payload["type"]!!.jsonPrimitive.content)
        assertEquals("merge-1", payload["send_id"]!!.jsonPrimitive.content)
        assertEquals(
            listOf(11L, 12L),
            payload["thread_ids"]!!.jsonArray.map { it.jsonPrimitive.long },
        )
        assertEquals(12L, payload["target_thread"]!!.jsonPrimitive.long)
        // The bridge ignores the thread identity fields of a merge request, so
        // an unknown sub id must not look like a resolved SIM.
        assertEquals(-1L, payload["sub_id"]!!.jsonPrimitive.long)
    }

    @Test
    fun requestEventRefusesASingleThread() {
        assertThrows(IllegalArgumentException::class.java) {
            selection(candidate(11)).requestEvent(sendId = "merge-1", timestampMs = 1)
        }
    }

    @Test
    fun sendIdIsPrefixedAndUnique() {
        val first = MergeThreads.newSendId(1761600000000)
        val second = MergeThreads.newSendId(1761600000000)

        assertTrue(first.startsWith("merge-1761600000000-"))
        assertTrue(second.startsWith("merge-1761600000000-"))
        assertTrue(first != second)
    }

    @Test
    fun outcomeForSuccessfulResult() {
        val outcome = MergeThreads.outcomeFor(
            QueueCommand(type = QueueCommand.TYPE_MERGE_RESULT, sendId = "merge-1", roomId = "!target:example.com")
        )

        assertTrue(outcome.ok)
        assertEquals("!target:example.com", outcome.roomId)
        assertEquals("", outcome.message)
    }

    @Test
    fun outcomeForRefusedResult() {
        val outcome = MergeThreads.outcomeFor(
            QueueCommand(
                type = QueueCommand.TYPE_MERGE_RESULT,
                sendId = "merge-1",
                error = BridgeEventError("bad_request", "no bridged room found for phone thread 99"),
            )
        )

        assertFalse(outcome.ok)
        assertEquals("", outcome.roomId)
        assertEquals("bad_request: no bridged room found for phone thread 99", outcome.message)
    }

    @Test
    fun outcomeWithoutRoomOrErrorIsNotSuccess() {
        val outcome = MergeThreads.outcomeFor(QueueCommand(type = QueueCommand.TYPE_MERGE_RESULT, sendId = "merge-1"))

        assertFalse(outcome.ok)
    }
}
