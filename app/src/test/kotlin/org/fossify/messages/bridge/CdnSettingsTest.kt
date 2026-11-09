/*
 * SPDX-FileCopyrightText: 2026 The Fossify Messages bridge contributors
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

@file:Suppress("MagicNumber")

package org.fossify.messages.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Defaults, clamping, threshold resolution and the mode migration. */
class CdnSettingsTest {

    @Test
    fun defaultsAreTheShippedPreferences() {
        val settings = CdnSettings.DEFAULTS

        assertEquals(CdnSendMode.LINK, settings.mode)
        assertEquals(CdnThreshold.DEFAULT_MB, settings.thresholdMb)
        assertEquals(0, settings.thresholdMb)
        assertTrue(settings.askCompress)
        assertFalse(settings.askExpiry)
        assertEquals(250, settings.uploadLimitMb)
        assertEquals(30, settings.expiryDays)
    }

    @Test
    fun legacyAllowLinksOnKeepsTheAskDialog() {
        assertEquals(CdnSendMode.ASK, CdnSendMode.fromLegacyAllowLinks(true))
        assertEquals(CdnSendMode.ASK, CdnSettings.resolveMode(null, true))
    }

    @Test
    fun legacyAllowLinksOffNeverOffersLinks() {
        assertEquals(CdnSendMode.NEVER, CdnSendMode.fromLegacyAllowLinks(false))
        assertEquals(CdnSendMode.NEVER, CdnSettings.resolveMode(null, false))
    }

    @Test
    fun storedModeWinsOverTheLegacyBoolean() {
        assertEquals(CdnSendMode.LINK, CdnSettings.resolveMode("link", true))
        assertEquals(CdnSendMode.NEVER, CdnSettings.resolveMode("never", false))
        assertEquals(CdnSendMode.ASK, CdnSettings.resolveMode("ask", false))
    }

    @Test
    fun missingAndUnknownModesFallBackToTheDefault() {
        assertEquals(CdnSendMode.LINK, CdnSettings.resolveMode(null, null))
        assertEquals(CdnSendMode.LINK, CdnSettings.resolveMode("garbage", null))
        assertEquals(CdnSendMode.LINK, CdnSendMode.fromPreference(null))
        assertEquals(CdnSendMode.LINK, CdnSendMode.fromPreference("garbage"))
        assertEquals(CdnSendMode.DEFAULT, CdnSendMode.LINK)
    }

    @Test
    fun modePreferencesRoundTrip() {
        CdnSendMode.entries.forEach { mode ->
            assertEquals(mode, CdnSendMode.fromPreference(mode.preferenceValue))
        }
    }

    @Test
    fun thresholdCoercionKeepsAutoAndClampsTheRest() {
        assertEquals(CdnThreshold.AUTO_MB, CdnThreshold.coerceMb(CdnThreshold.AUTO_MB))
        assertEquals(CdnThreshold.AUTO_MB, CdnThreshold.coerceMb(-9))
        assertEquals(CdnThreshold.MIN_MB, CdnThreshold.coerceMb(0))
        assertEquals(5, CdnThreshold.coerceMb(5))
        assertEquals(CdnThreshold.MAX_MB, CdnThreshold.coerceMb(1000))
        assertTrue(CdnThreshold.CHOICES_MB.all { it in CdnThreshold.MIN_MB..CdnThreshold.MAX_MB })
    }

    @Test
    fun autoThresholdFollowsTheCarrierLimit() {
        val carrier = 2 * CdnSettings.BYTES_PER_MB

        assertEquals(carrier, CdnThreshold.bytes(CdnThreshold.AUTO_MB, carrier))
        assertEquals(carrier, CdnSettings(thresholdMb = CdnThreshold.AUTO_MB).thresholdBytes(carrier))
        assertNull(CdnThreshold.bytes(CdnThreshold.AUTO_MB, 0))
        assertNull(CdnThreshold.bytes(CdnThreshold.AUTO_MB, -1))
        assertEquals(0L, CdnThreshold.bytes(0, carrier))
        assertEquals(5 * CdnSettings.BYTES_PER_MB, CdnThreshold.bytes(5, carrier))
    }

    @Test
    fun theDefaultThresholdAppliesTheModeToAnySize() {
        val carrier = 2 * CdnSettings.BYTES_PER_MB

        assertEquals(CdnThreshold.MIN_MB, CdnThreshold.DEFAULT_MB)
        assertEquals(0, CdnSettings.DEFAULTS.thresholdMb)
        assertEquals(0L, CdnSettings.DEFAULTS.thresholdBytes(carrier))
        assertEquals(0L, CdnSettings().thresholdBytes(carrier))
    }

    @Test
    fun uploadLimitIsClampedIntoRange() {
        assertEquals(1, CdnSettings.coerceUploadLimitMb(0))
        assertEquals(1, CdnSettings.coerceUploadLimitMb(-10))
        assertEquals(250, CdnSettings.coerceUploadLimitMb(251))
        assertEquals(250, CdnSettings.coerceUploadLimitMb(1000))
        assertEquals(50, CdnSettings.coerceUploadLimitMb(50))
        assertTrue(CdnSettings.UPLOAD_LIMIT_CHOICES.contains(50))
        assertTrue(CdnSettings.UPLOAD_LIMIT_CHOICES.contains(250))
        val range = CdnSettings.MIN_UPLOAD_LIMIT_MB..CdnSettings.MAX_UPLOAD_LIMIT_MB
        assertTrue(CdnSettings.UPLOAD_LIMIT_CHOICES.all { it in range })
        assertEquals(CdnSettings.MAX_UPLOAD_LIMIT_MB, CdnSettings.UPLOAD_LIMIT_CHOICES.max())
    }

    @Test
    fun theDefaultUploadLimitIsTheLargestOfferedSize() {
        assertEquals(250, CdnSettings.DEFAULT_UPLOAD_LIMIT_MB)
        assertEquals(CdnSettings.UPLOAD_LIMIT_CHOICES.max(), CdnSettings.DEFAULT_UPLOAD_LIMIT_MB)
    }

    @Test
    fun storedLimitsFromThePreviousOptionSetSurvive() {
        // 10/25/50/100 MB were the offered sizes before 250 MB was added; a
        // stored preference must not be rewritten or clamped away.
        listOf(10, 25, 50, 100).forEach { mb ->
            assertEquals(mb, CdnSettings.coerceUploadLimitMb(mb))
        }
    }

    @Test
    fun expiryDurationSpecMapsDays() {
        assertEquals("7d", CdnExpiry.durationSpec(7))
        assertEquals("30d", CdnExpiry.durationSpec(30))
        assertEquals("1d", CdnExpiry.durationSpec(0))
        assertEquals("365d", CdnExpiry.durationSpec(400))
    }

    @Test
    fun expiryValidationCoversTheBridgeCap() {
        assertFalse(CdnExpiry.isValidDays(0))
        assertFalse(CdnExpiry.isValidDays(-1))
        assertFalse(CdnExpiry.isValidDays(366))
        assertTrue(CdnExpiry.isValidDays(1))
        assertTrue(CdnExpiry.isValidDays(365))
        assertEquals(30, CdnExpiry.DEFAULT_DAYS)
    }

    @Test
    fun uploadLimitBytesIncludesWholeMegabytes() {
        val defaultBytes = CdnSettings.DEFAULT_UPLOAD_LIMIT_MB.toLong() * CdnSettings.BYTES_PER_MB

        assertEquals(250L * 1024 * 1024, CdnSettings().uploadLimitBytes)
        assertEquals(defaultBytes, CdnSettings().uploadLimitBytes)
        assertEquals(10L * 1024 * 1024, CdnSettings(uploadLimitMb = 10).uploadLimitBytes)
        assertEquals(250L * 1024 * 1024, CdnSettings(uploadLimitMb = 250).uploadLimitBytes)
    }

    @Test
    fun coerceClampsAStoredSnapshot() {
        val coerced = CdnSettings(uploadLimitMb = 1000, expiryDays = 0, thresholdMb = -9).coerce()

        assertEquals(CdnThreshold.AUTO_MB, coerced.thresholdMb)
        assertEquals(250, coerced.uploadLimitMb)
        assertEquals(1, coerced.expiryDays)
    }

    @Test
    fun legacyPreferencesSurviveCoercion() {
        val stored = CdnSettings(
            mode = CdnSendMode.NEVER,
            askCompress = false,
            uploadLimitMb = 25,
            expiryDays = 7,
            askExpiry = true,
        )

        assertEquals(stored, stored.coerce())
    }
}
