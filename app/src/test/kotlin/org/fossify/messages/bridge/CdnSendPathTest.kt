/*
 * SPDX-FileCopyrightText: 2026 microchipster
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

@file:Suppress("MagicNumber")

package org.fossify.messages.bridge

import org.fossify.messages.helpers.FILE_SIZE_NONE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gating of the URL path: no advertised `cdn_base_url`, links disabled in
 * settings or an attachment above the app-side upload limit must all keep the
 * encrypted-link option out of the choice dialog.
 */
class CdnSendPathTest {
    private val mb = 1024L * 1024L

    private fun input(
        fileSize: Long = 5 * mb,
        mmsLimit: Long = 200 * 1024L,
        uploadLimitMb: Int = 50,
        mimeType: String = "video/mp4",
        cdnAdvertised: Boolean = true,
        settings: CdnSettings = CdnSettings(allowLinks = true),
    ) = CdnSendInput(
        fileSizeBytes = fileSize,
        mmsLimitBytes = mmsLimit,
        uploadLimitBytes = uploadLimitMb * mb,
        mimeType = mimeType,
        cdnAdvertised = cdnAdvertised,
        settings = settings,
    )

    @Test
    fun noAdvertisedBaseUrlDisablesTheLinkPath() {
        val data = input(cdnAdvertised = false)

        assertFalse(data.linkAvailable)
        assertFalse(CdnSendPath.options(data).contains(CdnSendOption.ENCRYPTED_LINK))
        // A non-image has no compressor either, so there is nothing to ask.
        assertFalse(CdnSendPath.needsChoice(data))
    }

    @Test
    fun linksDisabledBySettingsKeepTheLinkPathInert() {
        val data = input(settings = CdnSettings(allowLinks = false))

        assertFalse(data.linkAvailable)
        assertFalse(CdnSendPath.options(data).contains(CdnSendOption.ENCRYPTED_LINK))
    }

    @Test
    fun overTheUploadLimitDropsTheLinkOption() {
        val data = input(fileSize = 60 * mb, uploadLimitMb = 50)

        assertFalse(data.linkAvailable)
        assertFalse(CdnSendPath.options(data).contains(CdnSendOption.ENCRYPTED_LINK))
    }

    @Test
    fun theGcmOverheadCountsAgainstTheUploadLimit() {
        val limit = 10 * mb
        val fits = input(fileSize = limit - CdnCrypto.OVERHEAD_BYTES, uploadLimitMb = 10)
        val doesNotFit = input(fileSize = limit - CdnCrypto.OVERHEAD_BYTES + 1, uploadLimitMb = 10)

        assertTrue(fits.linkAvailable)
        assertFalse(doesNotFit.linkAvailable)
    }

    @Test
    fun smallAttachmentsKeepTheNormalSendPath() {
        val data = input(fileSize = 100 * 1024L)

        assertFalse(CdnSendPath.needsChoice(data))
        assertTrue(CdnSendPath.options(data).contains(CdnSendOption.MMS))
    }

    @Test
    fun disabledMmsLimitNeverTriggersTheChoice() {
        val data = input(fileSize = 100 * mb, mmsLimit = FILE_SIZE_NONE)

        assertFalse(data.overMmsLimit)
        assertFalse(CdnSendPath.needsChoice(data))
    }

    @Test
    fun oversizedNonImageWithLinkOffersOnlyTheLink() {
        val data = input(mimeType = "application/pdf")

        assertTrue(CdnSendPath.needsChoice(data))
        assertEquals(listOf(CdnSendOption.ENCRYPTED_LINK), CdnSendPath.options(data))
    }

    @Test
    fun oversizedImageOffersLinkAndCompression() {
        val data = input(mimeType = "image/jpeg")

        assertTrue(CdnSendPath.needsChoice(data))
        assertEquals(
            listOf(CdnSendOption.ENCRYPTED_LINK, CdnSendOption.COMPRESS),
            CdnSendPath.options(data),
        )
    }

    @Test
    fun oversizedImageWithoutLinkAsksBeforeCompressing() {
        val data = input(
            mimeType = "image/jpeg",
            cdnAdvertised = false,
            settings = CdnSettings(allowLinks = false, askCompress = true),
        )

        assertTrue(CdnSendPath.needsChoice(data))
        assertEquals(listOf(CdnSendOption.COMPRESS), CdnSendPath.options(data))
    }

    @Test
    fun oversizedImageStaysSilentWhenAskingIsOffAndNoLinkExists() {
        val data = input(
            mimeType = "image/jpeg",
            cdnAdvertised = false,
            settings = CdnSettings(allowLinks = false, askCompress = false),
        )

        assertFalse(CdnSendPath.needsChoice(data))
    }

    @Test
    fun compressionIsNotOfferedForNonImages() {
        val data = input(mimeType = "video/mp4")

        assertFalse(CdnSendPath.options(data).contains(CdnSendOption.COMPRESS))
    }

    @Test
    fun gifGetsNoCompressorButCanGoAsLink() {
        val data = input(mimeType = "image/gif")

        assertTrue(CdnSendPath.needsChoice(data))
        assertEquals(listOf(CdnSendOption.ENCRYPTED_LINK), CdnSendPath.options(data))
    }
}
