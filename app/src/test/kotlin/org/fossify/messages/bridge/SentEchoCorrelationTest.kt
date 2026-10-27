/*
 * SPDX-FileCopyrightText: 2026 microchipster
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

@file:Suppress("MagicNumber")

package org.fossify.messages.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the wire payload of the outgoing `message.new` echo
 * ([BridgeEvents.sentEvent]): it must carry the send correlation (send_id and,
 * when the provider row is known, message_id) so the bridge can suppress it no
 * matter whether the echo or the `send.result` is processed first, and it must
 * never carry participants — an empty recipient list must not become a room
 * membership update that clears the members and the name.
 */
class SentEchoCorrelationTest {
    private val json = Json {
        encodeDefaults = false
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    private fun echo(rowId: Long, sendId: String = "send-42") = BridgeEvents.sentEvent(
        kind = "mms",
        rowId = rowId,
        threadId = 2,
        subId = 2,
        body = "see you at 8",
        isMms = true,
        hasAttachment = false,
        sendId = sendId,
        timestampMs = 1793103872856,
    )

    @Test
    fun echoCarriesSendAndMessageCorrelation() {
        val event = echo(rowId = 57)

        assertEquals(BridgeEvents.TYPE_MESSAGE_NEW, event.type)
        assertEquals("send-42", event.sendId)
        assertEquals("mms:2:57", event.messageId)
        assertEquals(2L, event.threadId)
        assertEquals(2, event.subId)
        assertEquals(1793103872856L, event.timestampMs)
        assertEquals("see you at 8", event.preview)

        val payload = json.parseToJsonElement(json.encodeToString(BridgeEvent.serializer(), event)).jsonObject
        assertEquals("message.new", payload["type"]!!.jsonPrimitive.content)
        assertEquals("send-42", payload["send_id"]!!.jsonPrimitive.content)
        assertEquals("mms:2:57", payload["message_id"]!!.jsonPrimitive.content)
        assertEquals(2L, payload["thread_id"]!!.jsonPrimitive.long)
        assertEquals(2L, payload["sub_id"]!!.jsonPrimitive.long)
    }

    @Test
    fun echoWithoutAResolvedProviderRowOmitsTheMessageId() {
        val event = echo(rowId = 0)

        assertEquals("", event.messageId)
        val encoded = json.encodeToString(BridgeEvent.serializer(), event)
        // The send id is still there, so the bridge can match the pending send.
        assertTrue(encoded.contains("\"send_id\""))
        assertFalse(encoded.contains("\"message_id\""))
    }

    @Test
    fun echoNeverCarriesRecipients() {
        val event = echo(rowId = 57)

        assertEquals(emptyList<String>(), event.recipients)
        assertEquals("", event.sender)
        val encoded = json.encodeToString(BridgeEvent.serializer(), event)
        // The echo is not a participant update: an empty recipient list must
        // not reach the bridge as an empty full-member reconciliation.
        assertFalse(encoded.contains("\"recipients\""))
        assertFalse(encoded.contains("\"sender\""))
    }

    @Test
    fun threadUpdateOmitsEmptyRecipientsButCarriesKnownOnes() {
        val unknownThread = BridgeEvent(
            type = BridgeEvents.TYPE_THREAD_UPDATED,
            threadId = 99,
            subId = -1,
            timestampMs = 1,
            isMms = false,
            hasAttachment = false,
            recipients = emptyList(),
        )
        val emptyEncoded = json.encodeToString(BridgeEvent.serializer(), unknownThread)
        assertFalse(emptyEncoded.contains("\"recipients\""))

        val knownThread = unknownThread.copy(recipients = listOf("+15550100203", "+15550100201"))
        val knownEncoded = json.encodeToString(BridgeEvent.serializer(), knownThread)
        assertTrue(knownEncoded.contains("\"recipients\""))
    }
}
