/*
 * SPDX-FileCopyrightText: 2026 microchipster
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
    fun defaultsAreConservative() {
        val settings = CdnSettings.DEFAULTS

        assertEquals(CdnSendMode.ASK, settings.mode)
        assertEquals(CdnThreshold.AUTO_MB, settings.thresholdMb)
        assertTrue(settings.askCompress)
        assertFalse(settings.askExpiry)
        assertEquals(50, settings.uploadLimitMb)
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
        assertEquals(CdnSendMode.ASK, CdnSettings.resolveMode(null, null))
        assertEquals(CdnSendMode.ASK, CdnSettings.resolveMode("garbage", null))
        assertEquals(CdnSendMode.ASK, CdnSendMode.fromPreference(null))
        assertEquals(CdnSendMode.ASK, CdnSendMode.fromPreference("garbage"))
        assertEquals(CdnSendMode.DEFAULT, CdnSendMode.ASK)
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
        assertEquals(carrier, CdnSettings().thresholdBytes(carrier))
        assertNull(CdnThreshold.bytes(CdnThreshold.AUTO_MB, 0))
        assertNull(CdnThreshold.bytes(CdnThreshold.AUTO_MB, -1))
        assertEquals(0L, CdnThreshold.bytes(0, carrier))
        assertEquals(5 * CdnSettings.BYTES_PER_MB, CdnThreshold.bytes(5, carrier))
    }

    @Test
    fun uploadLimitIsClampedIntoRange() {
        assertEquals(1, CdnSettings.coerceUploadLimitMb(0))
        assertEquals(1, CdnSettings.coerceUploadLimitMb(-10))
        assertEquals(100, CdnSettings.coerceUploadLimitMb(500))
        assertEquals(50, CdnSettings.coerceUploadLimitMb(50))
        assertTrue(CdnSettings.UPLOAD_LIMIT_CHOICES.contains(50))
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
        assertEquals(50L * 1024 * 1024, CdnSettings().uploadLimitBytes)
        assertEquals(10L * 1024 * 1024, CdnSettings(uploadLimitMb = 10).uploadLimitBytes)
    }

    @Test
    fun coerceClampsAStoredSnapshot() {
        val coerced = CdnSettings(uploadLimitMb = 1000, expiryDays = 0, thresholdMb = -9).coerce()

        assertEquals(CdnThreshold.AUTO_MB, coerced.thresholdMb)
        assertEquals(100, coerced.uploadLimitMb)
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
