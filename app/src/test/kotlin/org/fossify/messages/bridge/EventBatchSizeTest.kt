/*
 * SPDX-FileCopyrightText: 2026 microchipster
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.fossify.messages.bridge

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers splitting `POST /_smsmms/v1/events` batches by size: bursts of replayed
 * MMS with inline parts must not be sent as one request the bridge rejects as a
 * whole (which would block every later event).
 */
class EventBatchSizeTest {
    private val json = Json {
        encodeDefaults = false
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    private fun event(messageId: String, payloadChars: Int = 0) = BridgeEvent(
        type = BridgeEvents.TYPE_MESSAGE_NEW,
        messageId = messageId,
        threadId = 12,
        subId = 1,
        timestampMs = 1792749554448,
        sender = "+15551234567",
        isMms = true,
        hasAttachment = payloadChars > 0,
        preview = "…",
        message = BridgeMessage(
            id = messageId,
            threadId = 12,
            subId = 1,
            timestampMs = 1792749554448,
            sender = "+15551234567",
            recipients = emptyList(),
            isMms = true,
            type = 1,
            read = false,
            seen = false,
            status = 0,
            attachments = if (payloadChars > 0) {
                listOf(BridgeAttachment(partId = 901, contentType = "image/jpeg", base64 = "A".repeat(payloadChars)))
            } else {
                emptyList()
            },
        ),
    )

    @Test
    fun smallEventsGoOutInOneBatch() {
        val events = listOf(event("sms:1:1"), event("sms:1:2"), event("sms:1:3"))

        assertEquals(events, fitEventBatch(json, events, maxBytes = 1_000_000, envelopeBytes = 512))
    }

    @Test
    fun largePayloadsAreSplitAcrossBatches() {
        val events = listOf(event("mms:1:1", 4_000), event("mms:1:2", 4_000), event("mms:1:3", 4_000))
        val budget = eventBytes(json, events[0]) * 2 + 512

        val batch = fitEventBatch(json, events, maxBytes = budget, envelopeBytes = 512)

        assertEquals(2, batch.size)
        assertEquals(listOf("mms:1:1", "mms:1:2"), batch.map { it.messageId })
        // The rest is pushed by the next flush.
        assertEquals(1, fitEventBatch(json, events.drop(batch.size), maxBytes = budget, envelopeBytes = 512).size)
    }

    @Test
    fun singleOversizedEventIsReturnedAlone() {
        val events = listOf(event("mms:1:1", 40_000), event("mms:1:2", 40_000))

        val batch = fitEventBatch(json, events, maxBytes = 512, envelopeBytes = 512)

        assertEquals(1, batch.size)
        assertTrue(eventBytes(json, batch[0]) > 512)
    }

    @Test
    fun measuredSizeGrowsWithTheInlinePayload() {
        val plain = eventBytes(json, event("mms:1:1"))
        val withPayload = eventBytes(json, event("mms:1:1", 10_000))

        assertTrue(withPayload > plain + 10_000)
    }
}
