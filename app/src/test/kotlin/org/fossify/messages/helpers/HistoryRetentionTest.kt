/*
 * SPDX-FileCopyrightText: 2026 The Fossify Messages bridge contributors
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

@file:Suppress("MagicNumber")

package org.fossify.messages.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryRetentionTest {
    private val day = 86_400L
    private val now = 1_800_000_000L

    private val noRedactions = RedactionConfig(emptySet(), emptyMap())

    private fun message(
        id: Long,
        ageDays: Long,
        threadId: Long = 1L,
        body: String = "hello there",
    ) = RetentionMessage(
        id = id,
        threadId = threadId,
        dateSeconds = now - ageDays * day,
        body = body,
        isMMS = false,
    )

    private fun decide(
        messages: List<RetentionMessage>,
        globalDays: Int = 90,
        overrides: Map<Long, Int> = emptyMap(),
        redactions: RedactionConfig = noRedactions,
    ) = HistoryRetention.decide(messages, globalDays, overrides, redactions, now)

    @Test
    fun globalRetentionDeletesOnlyOlderMessages() {
        val messages = listOf(
            message(1, ageDays = 91),
            message(2, ageDays = 90),
            message(3, ageDays = 10),
        )
        val decision = decide(messages, globalDays = 90)
        assertEquals(listOf(1L), decision.expired.map { it.id })
        assertTrue(decision.redacted.isEmpty())
        assertEquals(listOf(1L), decision.ids)
    }

    @Test
    fun disabledRetentionDeletesNothing() {
        val messages = listOf(message(1, ageDays = 5000), message(2, ageDays = 1))
        assertTrue(decide(messages, globalDays = HistoryRetention.KEEP_FOREVER).ids.isEmpty())
        assertTrue(decide(messages, globalDays = -1).ids.isEmpty())
    }

    @Test
    fun conversationOverrideReplacesGlobalRetention() {
        val messages = listOf(
            message(1, ageDays = 100, threadId = 1L),
            message(2, ageDays = 100, threadId = 2L),
            message(3, ageDays = 40, threadId = 2L),
        )
        val extended = decide(messages, globalDays = 90, overrides = mapOf(2L to 200))
        assertEquals(listOf(1L), extended.expired.map { it.id })

        val shortened = decide(messages, globalDays = 365, overrides = mapOf(2L to 30))
        assertEquals(listOf(2L, 3L), shortened.expired.map { it.id })
    }

    @Test
    fun conversationOverrideCanKeepForever() {
        val messages = listOf(message(1, ageDays = 5000, threadId = 7L), message(2, ageDays = 5000, threadId = 8L))
        val decision = decide(messages, globalDays = 30, overrides = mapOf(7L to HistoryRetention.KEEP_FOREVER))
        assertEquals(listOf(2L), decision.expired.map { it.id })
    }

    @Test
    fun redactionDeletesRegardlessOfAgeAndRetention() {
        val otp = "Your OTP is 123456"
        val messages = listOf(message(1, ageDays = 0, body = otp), message(2, ageDays = 0))
        val enabled = RedactionConfig(setOf(RedactionPreset.OTP), emptyMap())

        val recent = decide(messages, globalDays = HistoryRetention.KEEP_FOREVER, redactions = enabled)
        assertEquals(listOf(1L), recent.redacted.map { it.id })
        assertTrue(recent.expired.isEmpty())

        val old = decide(
            listOf(message(3, ageDays = 5000, body = otp)),
            globalDays = 30,
            redactions = enabled,
        )
        assertEquals(listOf(3L), old.expired.map { it.id })
        assertEquals(listOf(3L), old.redacted.map { it.id })
        assertEquals(listOf(3L), old.ids)
    }

    @Test
    fun decisionReportsMatchingMessagesOnce() {
        val otp = "Your verification code is 424242"
        val messages = listOf(
            message(1, ageDays = 100, body = otp),
            message(2, ageDays = 100),
            message(3, ageDays = 1, body = otp),
        )
        val decision = decide(
            messages,
            globalDays = 90,
            redactions = RedactionConfig(setOf(RedactionPreset.OTP), emptyMap()),
        )
        assertEquals(listOf(1L, 2L), decision.expired.map { it.id })
        assertEquals(listOf(1L, 3L), decision.redacted.map { it.id })
        assertEquals(listOf(1L, 2L, 3L), decision.ids)
        assertEquals(3, decision.messages.size)
    }

    @Test
    fun customExtensionPatternsAreHonored() {
        val redactions = RedactionConfig(emptySet(), mapOf("promo" to "(?i)\\bunsubscribe\\b"))
        val messages = listOf(
            message(1, ageDays = 0, body = "Reply STOP to unsubscribe"),
            message(2, ageDays = 0, body = "see you tomorrow"),
        )
        assertEquals(listOf(1L), decide(messages, redactions = redactions).redacted.map { it.id })
        assertTrue(redactions.matches("please UNSUBSCRIBE here"))
        assertFalse(redactions.matches("see you tomorrow"))
    }

    @Test
    fun presetPatternsMatchTheirSubject() {
        assertTrue(RedactionPreset.OTP.pattern.containsMatchIn("Your OTP is 123456"))
        assertTrue(RedactionPreset.OTP.pattern.containsMatchIn("123456 is your verification code"))
        assertTrue(RedactionPreset.OTP.pattern.containsMatchIn("2FA code: 918273"))
        assertFalse(RedactionPreset.OTP.pattern.containsMatchIn("The package code is 1234"))

        assertTrue(RedactionPreset.PASSWORD_RESET.pattern.containsMatchIn("Your password was changed"))
        assertTrue(RedactionPreset.PASSWORD_RESET.pattern.containsMatchIn("Reset your password now"))
        assertFalse(RedactionPreset.PASSWORD_RESET.pattern.containsMatchIn("the password is secret"))

        assertTrue(RedactionPreset.CARD_NUMBER.pattern.containsMatchIn("4111 1111 1111 1111"))
        assertTrue(RedactionPreset.CARD_NUMBER.pattern.containsMatchIn("4111111111111111"))
        assertFalse(RedactionPreset.CARD_NUMBER.pattern.containsMatchIn("1234 5678 9012"))

        assertTrue(RedactionPreset.SSN.pattern.containsMatchIn("123-45-6789"))
        assertFalse(RedactionPreset.SSN.pattern.containsMatchIn("123456789"))
    }

    @Test
    fun parseKeepsKnownPresetsAndValidExtensions() {
        val config = RedactionConfig.parse(
            """{"presets":["otp","card_number","nope"],"extensions":{"promo":"(?i)unsubscribe","bad":"("}}"""
        )
        assertEquals(setOf(RedactionPreset.OTP, RedactionPreset.CARD_NUMBER), config.presets)
        assertEquals(mapOf("promo" to "(?i)unsubscribe"), config.extensions)
        assertEquals(
            """{"presets":["otp","card_number"],"extensions":{"promo":"(?i)unsubscribe"}}""",
            config.encode(),
        )
        assertEquals(config.presets, RedactionConfig.parse(config.encode()).presets)
        assertEquals(config.extensions, RedactionConfig.parse(config.encode()).extensions)
    }

    @Test
    fun parseIgnoresGarbage() {
        for (raw in listOf(null, "", "   ", "not json", "[]", "42", """{"presets":"otp","extensions":[]}""")) {
            assertTrue(RedactionConfig.parse(raw).isEmpty)
        }
        assertTrue(RedactionConfig(emptySet(), emptyMap()).isEmpty)
        assertFalse(RedactionConfig(setOf(RedactionPreset.SSN), emptyMap()).isEmpty)
    }

    @Test
    fun patternsAreNamedAndOrdered() {
        val config = RedactionConfig(
            presets = setOf(RedactionPreset.SSN, RedactionPreset.OTP),
            extensions = mapOf("b" to "b", "a" to "a", "bad" to "("),
        )
        assertEquals(listOf("otp", "ssn", "a", "b"), config.patterns().map { it.name })
    }

    @Test
    fun extensionsTextRoundTrips() {
        assertEquals("a=x\nb=y", RedactionConfig.extensionsText(linkedMapOf("b" to "y", "a" to "x")))
        assertEquals(
            mapOf("a" to "(?i)x", "b" to "y"),
            RedactionConfig.parseExtensions("# comment\n\n a=(?i)x \nb=y\n"),
        )
        assertNull(RedactionConfig.parseExtensions("a"))
        assertNull(RedactionConfig.parseExtensions("a="))
        assertNull(RedactionConfig.parseExtensions("=b"))
        assertNull(RedactionConfig.parseExtensions("a=("))
    }

    @Test
    fun overridesCodecRoundTrips() {
        val overrides = mapOf(2L to HistoryRetention.KEEP_FOREVER, 1L to 30)
        assertEquals("""{"1":30,"2":0}""", HistoryRetentionOverrides.encode(overrides))
        assertEquals(overrides, HistoryRetentionOverrides.decode(HistoryRetentionOverrides.encode(overrides)))
    }

    @Test
    fun overridesCodecIgnoresUnusableEntries() {
        assertTrue(HistoryRetentionOverrides.decode(null).isEmpty())
        assertTrue(HistoryRetentionOverrides.decode("").isEmpty())
        assertTrue(HistoryRetentionOverrides.decode("not json").isEmpty())
        assertTrue(HistoryRetentionOverrides.decode("""{"1":-5,"2":"30","x":1,"3":true}""").isEmpty())
        assertEquals(
            mapOf(4L to 30),
            HistoryRetentionOverrides.decode("""{"4":30,"5":null}"""),
        )
        assertEquals("{}", HistoryRetentionOverrides.encode(mapOf(1L to -1)))
    }

    @Test
    fun effectiveRetentionPrefersTheOverride() {
        assertEquals(90, HistoryRetention.effectiveRetentionDays(1L, 90, emptyMap()))
        assertEquals(7, HistoryRetention.effectiveRetentionDays(1L, 90, mapOf(1L to 7)))
        assertEquals(0, HistoryRetention.effectiveRetentionDays(1L, 90, mapOf(1L to 0)))
    }
}
