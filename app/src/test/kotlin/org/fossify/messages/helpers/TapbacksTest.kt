/*
 * SPDX-FileCopyrightText: 2026 microchipster
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.fossify.messages.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TapbacksTest {
    @Test
    fun tapbackChoicesMatchTheBridge() {
        assertEquals(
            listOf("loved", "liked", "disliked", "laughed at", "emphasized", "questioned"),
            Tapbacks.choices.map { it.name }
        )
        val emojis = listOf(
            "\u2764\uFE0F", "\uD83D\uDC4D", "\uD83D\uDC4E", "\uD83D\uDE02", "\u203C\uFE0F", "\u2753"
        )
        assertEquals(emojis, Tapbacks.choices.map { it.emoji })
    }

    @Test
    fun addedTapbacksParse() {
        for (choice in Tapbacks.choices) {
            val body = Tapbacks.format(choice.name, "see you at 8")
            val tapback = Tapbacks.parse(body)

            assertEquals(choice.name, tapback?.name)
            assertEquals(choice.emoji, tapback?.emoji)
            assertEquals("see you at 8", tapback?.targetText)
            assertFalse(tapback?.isMedia ?: true)
        }
    }

    @Test
    fun straightQuotesAreAccepted() {
        val tapback = Tapbacks.parse("Liked \"see you at 8\"")

        assertEquals("liked", tapback?.name)
        assertEquals("see you at 8", tapback?.targetText)
        assertFalse(tapback?.isMedia ?: true)
    }

    @Test
    fun mediaTapbacksParse() {
        for (body in listOf("Liked an image", "Loved a photo", "Laughed at a video", "Questioned a file")) {
            val tapback = Tapbacks.parse(body)

            assertTrue(body, tapback?.isMedia ?: false)
            assertEquals("", tapback?.targetText)
        }
    }

    @Test
    fun regularMessagesAreNotTapbacks() {
        assertNull(Tapbacks.parse(""))
        assertNull(Tapbacks.parse("Liked"))
        assertNull(Tapbacks.parse("I liked “see you at 8”"))
        assertNull(Tapbacks.parse("Removed a like from “see you at 8”"))
        assertNull(Tapbacks.parse("Liked an"))
    }

    @Test
    fun formatUsesCurlyQuotes() {
        assertEquals("Liked \u201Csee you at 8\u201D", Tapbacks.format("liked", "see you at 8"))
        assertEquals("Laughed at \u201Csee you at 8\u201D", Tapbacks.format("laughed at", "see you at 8"))
    }

    @Test
    fun formatFallsBackToMediaWording() {
        assertEquals("Loved a video", Tapbacks.format("loved", "", "a video"))
        assertEquals("Liked an image", Tapbacks.format("liked", "   "))
        assertEquals("", Tapbacks.format("nope", "see you at 8"))
    }

    @Test
    fun mediaLabelPicksTheWording() {
        assertEquals("an image", Tapbacks.mediaLabel("image/gif"))
        assertEquals("a video", Tapbacks.mediaLabel("video/mp4"))
        assertEquals("a file", Tapbacks.mediaLabel("application/pdf"))
        assertEquals("a file", Tapbacks.mediaLabel(""))
    }

    @Test
    fun resolveAttachesChipsAndHidesMatchedTapbacks() {
        val resolved = Tapbacks.resolve(
            listOf(
                TapbackCandidate(key = 1, body = "see you at 8", hasAttachment = false),
                TapbackCandidate(key = 2, body = "Liked \u201Csee you at 8\u201D", hasAttachment = false),
                TapbackCandidate(key = 3, body = "Loved \u201Cnot in the thread\u201D", hasAttachment = false)
            )
        )

        assertEquals(setOf(2L), resolved.hiddenKeys)
        assertEquals(listOf("\uD83D\uDC4D"), resolved.reactions[1L])
        assertEquals(null, resolved.reactions[3L])
    }

    @Test
    fun resolveMatchesPrefixedAndTruncatedQuotes() {
        val resolved = Tapbacks.resolve(
            listOf(
                TapbackCandidate(key = 1, body = "see you at 8 with bells on", hasAttachment = false),
                TapbackCandidate(key = 2, body = "Emphasized \u201Csee you at 8\u2026\u201D", hasAttachment = false)
            )
        )

        assertEquals(setOf(2L), resolved.hiddenKeys)
        assertEquals(listOf("\u203C\uFE0F"), resolved.reactions[1L])
    }

    @Test
    fun resolvePrefersTheLatestMatchingMessage() {
        val resolved = Tapbacks.resolve(
            listOf(
                TapbackCandidate(key = 1, body = "hi", hasAttachment = false),
                TapbackCandidate(key = 2, body = "hi", hasAttachment = false),
                TapbackCandidate(key = 3, body = "Liked \u201Chi\u201D", hasAttachment = false)
            )
        )

        assertEquals(setOf(3L), resolved.hiddenKeys)
        assertEquals(listOf("\uD83D\uDC4D"), resolved.reactions[2L])
        assertEquals(null, resolved.reactions[1L])
    }

    @Test
    fun resolveMatchesMediaTapbacksToTheLatestAttachment() {
        val resolved = Tapbacks.resolve(
            listOf(
                TapbackCandidate(key = 1, body = "just text", hasAttachment = false),
                TapbackCandidate(key = 2, body = "", hasAttachment = true),
                TapbackCandidate(key = 3, body = "Loved an image", hasAttachment = false)
            )
        )

        assertEquals(setOf(3L), resolved.hiddenKeys)
        assertEquals(listOf("\u2764\uFE0F"), resolved.reactions[2L])
    }

    @Test
    fun resolveDoesNotChainTapbacks() {
        val chainedBody = "Laughed at \u201CLiked \u201Chello\u201D\u201D"
        val resolved = Tapbacks.resolve(
            listOf(
                TapbackCandidate(key = 1, body = "hello", hasAttachment = false),
                TapbackCandidate(key = 2, body = "Liked \u201Chello\u201D", hasAttachment = false),
                TapbackCandidate(key = 3, body = chainedBody, hasAttachment = false)
            )
        )

        assertEquals(setOf(2L), resolved.hiddenKeys)
        assertEquals(listOf("\uD83D\uDC4D"), resolved.reactions[1L])
        assertEquals(null, resolved.reactions[2L])
    }

    @Test
    fun resolveKeepsOneChipPerEmoji() {
        val resolved = Tapbacks.resolve(
            listOf(
                TapbackCandidate(key = 1, body = "hello", hasAttachment = false),
                TapbackCandidate(key = 2, body = "Liked \u201Chello\u201D", hasAttachment = false),
                TapbackCandidate(key = 3, body = "Liked \u201Chello\u201D", hasAttachment = false)
            )
        )

        assertEquals(setOf(2L, 3L), resolved.hiddenKeys)
        assertEquals(listOf("\uD83D\uDC4D"), resolved.reactions[1L])
    }
}
