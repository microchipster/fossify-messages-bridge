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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the `thread.updated` event emitted after a send
 * ([BridgeEvents.threadUpdatedEvent], used by [BridgeSender]): a send into a
 * thread the bridge has never seen (a new group MMS) must deliver the
 * participant list, because the `message.new` echo never carries recipients.
 * Without it the bridge creates a portal with no members and no sendable
 * recipients.
 */
class ThreadUpdateAfterSendTest {
    private val json = Json {
        encodeDefaults = false
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    private fun update(subId: Int = 2, recipients: List<String> = listOf("+15550100203", "15550100202")) =
        BridgeEvents.threadUpdatedEvent(
            threadId = 8,
            subId = subId,
            recipients = recipients,
            timestampMs = 1793130101745,
        )

    @Test
    fun threadUpdateCarriesParticipants() {
        val event = update()

        assertEquals(BridgeEvents.TYPE_THREAD_UPDATED, event.type)
        assertEquals(8L, event.threadId)
        assertEquals(2, event.subId)
        assertEquals(1793130101745L, event.timestampMs)
        assertEquals(listOf("+15550100203", "15550100202"), event.recipients)

        val payload = json.parseToJsonElement(json.encodeToString(BridgeEvent.serializer(), event)).jsonObject
        assertEquals("thread.updated", payload["type"]!!.jsonPrimitive.content)
        assertEquals(8L, payload["thread_id"]!!.jsonPrimitive.long)
        assertEquals(2L, payload["sub_id"]!!.jsonPrimitive.long)
        val recipients = payload["recipients"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("+15550100203", "15550100202"), recipients)
    }

    @Test
    fun threadUpdateIsNotASendEcho() {
        val encoded = json.encodeToString(BridgeEvent.serializer(), update())

        // The participant update must stay decoupled from the send
        // correlation, so it is never matched against a pending send.
        assertFalse(encoded.contains("\"send_id\""))
        assertFalse(encoded.contains("\"message_id\""))
        assertFalse(encoded.contains("\"sender\""))
    }

    @Test
    fun threadUpdateNormalizesNegativeSubId() {
        assertEquals(-1, update(subId = -1).subId)
        assertEquals(2, update(subId = 2).subId)
    }

    @Test
    fun emptyParticipantsAreOmittedFromTheWire() {
        val encoded = json.encodeToString(BridgeEvent.serializer(), update(recipients = emptyList()))

        // BridgeEvents.onThreadUpdated never emits an empty list; if one ever
        // reaches the payload it must look like "unknown", not like "clear the
        // room members".
        assertFalse(encoded.contains("\"recipients\""))
        assertTrue(encoded.contains("\"thread_id\""))
    }
}
