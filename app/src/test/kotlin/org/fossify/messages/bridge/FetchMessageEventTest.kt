/*
 * SPDX-FileCopyrightText: 2026 microchipster
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.fossify.messages.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the `message.new` payload the app pushes for a `fetch_message`
 * command: the bridge asked for the message because its replayed event carried
 * no payload, so the reply must carry the full message with inline parts.
 */
class FetchMessageEventTest {
    private fun mmsMessage() = BridgeMessage(
        id = "mms:1:534",
        threadId = 12,
        subId = 1,
        timestampMs = 1792749554448,
        sender = "+15551234567",
        recipients = emptyList(),
        body = "look\nat this",
        isMms = true,
        type = 1,
        read = false,
        seen = false,
        status = 0,
        attachments = listOf(
            BridgeAttachment(
                partId = 901,
                contentType = "image/jpeg",
                fileName = "photo.jpg",
                base64 = "aGVsbG8=",
            )
        ),
    )

    @Test
    fun replyCarriesTheFullMessagePayload() {
        val event = messageNewEvent(mmsMessage())

        assertEquals(BridgeEvents.TYPE_MESSAGE_NEW, event.type)
        assertEquals("mms:1:534", event.messageId)
        assertEquals(12L, event.threadId)
        assertEquals(1, event.subId)
        assertEquals(1792749554448L, event.timestampMs)
        assertEquals("+15551234567", event.sender)
        assertTrue(event.isMms)
        assertTrue(event.hasAttachment)
        assertEquals("aGVsbG8=", event.message?.attachments?.single()?.base64)
    }

    @Test
    fun previewCollapsesNewlines() {
        val event = messageNewEvent(mmsMessage())

        assertEquals("look at this", event.preview)
    }

    @Test
    fun plainSmsReplyHasNoAttachment() {
        val event = messageNewEvent(
            BridgeMessage(
                id = "sms:1:77",
                threadId = 12,
                subId = 1,
                timestampMs = 1792749554448,
                recipients = emptyList(),
                body = "hi",
                isMms = false,
                type = 1,
                read = true,
                seen = true,
                status = 0,
            )
        )

        assertFalse(event.hasAttachment)
        assertFalse(event.isMms)
        assertEquals("hi", event.preview)
    }
}
