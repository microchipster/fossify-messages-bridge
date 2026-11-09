/*
 * SPDX-FileCopyrightText: 2026 The Fossify Messages bridge contributors
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
 * The decision matrix of the send path: threshold x mode x attachment size x
 * carrier MMS limit maps to exactly one [CdnSendAction]. The link is only
 * offered above the threshold, when the bridge advertised a `cdn_base_url` and
 * when the ciphertext fits the upload limit; everything else keeps the
 * pre-threshold compress/MMS behavior.
 */
class CdnSendPathTest {
    private val mb = 1024L * 1024L
    private val carrierLimit = 1 * mb

    private fun input(
        fileSize: Long = 5 * mb,
        mmsLimit: Long = carrierLimit,
        uploadLimitMb: Int = CdnSettings.DEFAULT_UPLOAD_LIMIT_MB,
        mimeType: String = "video/mp4",
        cdnAdvertised: Boolean = true,
        settings: CdnSettings = CdnSettings(),
    ) = CdnSendInput(
        fileSizeBytes = fileSize,
        mmsLimitBytes = mmsLimit,
        uploadLimitBytes = uploadLimitMb * mb,
        mimeType = mimeType,
        cdnAdvertised = cdnAdvertised,
        settings = settings,
    )

    private data class MatrixCase(
        val thresholdMb: Int,
        val mode: CdnSendMode,
        val fileSize: Long,
        val expected: CdnSendAction,
        val mimeType: String = "image/jpeg",
    ) {
        override fun toString(): String = "threshold=$thresholdMb mode=$mode size=$fileSize mime=$mimeType"
    }

    private fun assertMatrix(cases: List<MatrixCase>, mmsLimit: Long = carrierLimit) {
        cases.forEach { case ->
            val input = input(
                fileSize = case.fileSize,
                mmsLimit = mmsLimit,
                mimeType = case.mimeType,
                settings = CdnSettings(mode = case.mode, thresholdMb = case.thresholdMb),
            )
            assertEquals(case.toString(), case.expected, CdnSendPath.action(input))
        }
    }

    @Test
    fun imageMatrix() {
        assertMatrix(
            listOf(
                // Auto threshold: the carrier limit decides, exactly like the old send path.
                MatrixCase(CdnThreshold.AUTO_MB, CdnSendMode.ASK, 100 * 1024L, CdnSendAction.NORMAL),
                MatrixCase(CdnThreshold.AUTO_MB, CdnSendMode.LINK, 100 * 1024L, CdnSendAction.NORMAL),
                MatrixCase(CdnThreshold.AUTO_MB, CdnSendMode.NEVER, 100 * 1024L, CdnSendAction.NORMAL),
                MatrixCase(CdnThreshold.AUTO_MB, CdnSendMode.ASK, 2 * mb, CdnSendAction.ASK),
                MatrixCase(CdnThreshold.AUTO_MB, CdnSendMode.LINK, 2 * mb, CdnSendAction.LINK),
                MatrixCase(CdnThreshold.AUTO_MB, CdnSendMode.NEVER, 2 * mb, CdnSendAction.ASK),
                MatrixCase(CdnThreshold.AUTO_MB, CdnSendMode.ASK, 12 * mb, CdnSendAction.ASK),
                MatrixCase(CdnThreshold.AUTO_MB, CdnSendMode.LINK, 12 * mb, CdnSendAction.LINK),
                MatrixCase(CdnThreshold.AUTO_MB, CdnSendMode.NEVER, 12 * mb, CdnSendAction.ASK),
                // Any-size threshold: even small files take the mode path.
                MatrixCase(0, CdnSendMode.ASK, 100 * 1024L, CdnSendAction.ASK),
                MatrixCase(0, CdnSendMode.LINK, 100 * 1024L, CdnSendAction.LINK),
                MatrixCase(0, CdnSendMode.NEVER, 100 * 1024L, CdnSendAction.MMS),
                MatrixCase(0, CdnSendMode.ASK, 2 * mb, CdnSendAction.ASK),
                MatrixCase(0, CdnSendMode.LINK, 2 * mb, CdnSendAction.LINK),
                MatrixCase(0, CdnSendMode.NEVER, 2 * mb, CdnSendAction.ASK),
                // Fixed threshold above the carrier limit: files in between keep
                // the ask-whether-to-compress path and never reach the CDN.
                MatrixCase(5, CdnSendMode.ASK, 100 * 1024L, CdnSendAction.NORMAL),
                MatrixCase(5, CdnSendMode.LINK, 100 * 1024L, CdnSendAction.NORMAL),
                MatrixCase(5, CdnSendMode.NEVER, 100 * 1024L, CdnSendAction.NORMAL),
                MatrixCase(5, CdnSendMode.ASK, 2 * mb, CdnSendAction.ASK),
                MatrixCase(5, CdnSendMode.LINK, 2 * mb, CdnSendAction.ASK),
                MatrixCase(5, CdnSendMode.NEVER, 2 * mb, CdnSendAction.ASK),
                MatrixCase(5, CdnSendMode.ASK, 12 * mb, CdnSendAction.ASK),
                MatrixCase(5, CdnSendMode.LINK, 12 * mb, CdnSendAction.LINK),
                MatrixCase(5, CdnSendMode.NEVER, 12 * mb, CdnSendAction.ASK),
                // A fixed threshold decides by size: 12 MB is above it, 2 MB below.
                MatrixCase(10, CdnSendMode.ASK, 12 * mb, CdnSendAction.ASK),
                MatrixCase(10, CdnSendMode.LINK, 12 * mb, CdnSendAction.LINK),
                MatrixCase(10, CdnSendMode.NEVER, 12 * mb, CdnSendAction.ASK),
                MatrixCase(10, CdnSendMode.LINK, 2 * mb, CdnSendAction.ASK),
                // The default 250 MB upload limit carries 249 MB, drops 251 MB
                // (the input helper builds the limit from the shipped default).
                MatrixCase(CdnThreshold.AUTO_MB, CdnSendMode.LINK, 249 * mb, CdnSendAction.LINK),
                MatrixCase(CdnThreshold.AUTO_MB, CdnSendMode.ASK, 251 * mb, CdnSendAction.ASK),
            )
        )
    }

    @Test
    fun nonImageMatrix() {
        assertMatrix(
            listOf(
                MatrixCase(CdnThreshold.AUTO_MB, CdnSendMode.ASK, 100 * 1024L, CdnSendAction.NORMAL, "video/mp4"),
                MatrixCase(CdnThreshold.AUTO_MB, CdnSendMode.ASK, 2 * mb, CdnSendAction.ASK, "video/mp4"),
                MatrixCase(CdnThreshold.AUTO_MB, CdnSendMode.LINK, 2 * mb, CdnSendAction.LINK, "video/mp4"),
                MatrixCase(CdnThreshold.AUTO_MB, CdnSendMode.NEVER, 2 * mb, CdnSendAction.REJECT, "video/mp4"),
                MatrixCase(0, CdnSendMode.ASK, 100 * 1024L, CdnSendAction.ASK, "video/mp4"),
                MatrixCase(0, CdnSendMode.LINK, 100 * 1024L, CdnSendAction.LINK, "video/mp4"),
                MatrixCase(0, CdnSendMode.NEVER, 100 * 1024L, CdnSendAction.MMS, "video/mp4"),
                MatrixCase(5, CdnSendMode.LINK, 2 * mb, CdnSendAction.REJECT, "video/mp4"),
                MatrixCase(5, CdnSendMode.NEVER, 2 * mb, CdnSendAction.REJECT, "video/mp4"),
                MatrixCase(10, CdnSendMode.ASK, 12 * mb, CdnSendAction.ASK, "video/mp4"),
                MatrixCase(10, CdnSendMode.LINK, 12 * mb, CdnSendAction.LINK, "video/mp4"),
                MatrixCase(10, CdnSendMode.NEVER, 12 * mb, CdnSendAction.REJECT, "video/mp4"),
            )
        )
    }

    @Test
    fun carrierLimitMatrix() {
        // A carrier limit of 5 MB moves the Auto threshold and the fallback boundary.
        assertMatrix(
            listOf(
                MatrixCase(CdnThreshold.AUTO_MB, CdnSendMode.ASK, 2 * mb, CdnSendAction.NORMAL),
                MatrixCase(CdnThreshold.AUTO_MB, CdnSendMode.LINK, 2 * mb, CdnSendAction.NORMAL),
                MatrixCase(CdnThreshold.AUTO_MB, CdnSendMode.LINK, 6 * mb, CdnSendAction.LINK),
                MatrixCase(CdnThreshold.AUTO_MB, CdnSendMode.NEVER, 6 * mb, CdnSendAction.ASK),
                MatrixCase(1, CdnSendMode.ASK, 2 * mb, CdnSendAction.ASK),
                MatrixCase(1, CdnSendMode.LINK, 2 * mb, CdnSendAction.LINK),
                MatrixCase(1, CdnSendMode.NEVER, 2 * mb, CdnSendAction.MMS),
                MatrixCase(1, CdnSendMode.ASK, 6 * mb, CdnSendAction.ASK),
                MatrixCase(1, CdnSendMode.LINK, 6 * mb, CdnSendAction.LINK),
                MatrixCase(1, CdnSendMode.NEVER, 6 * mb, CdnSendAction.ASK),
            ),
            mmsLimit = 5 * mb,
        )
    }

    @Test
    fun askCompressOffCompressesImagesSilently() {
        val input = input(
            fileSize = 2 * mb,
            mimeType = "image/jpeg",
            cdnAdvertised = false,
            settings = CdnSettings(mode = CdnSendMode.ASK, askCompress = false),
        )

        assertEquals(CdnSendAction.COMPRESS, CdnSendPath.action(input))
    }

    @Test
    fun neverModeCompressesSilentlyWhenAskingIsOff() {
        val input = input(
            fileSize = 2 * mb,
            mimeType = "image/jpeg",
            settings = CdnSettings(mode = CdnSendMode.NEVER, askCompress = false),
        )

        assertEquals(CdnSendAction.COMPRESS, CdnSendPath.action(input))
    }

    @Test
    fun noAdvertisedBaseUrlKeepsTheLinkPathInert() {
        val input = input(
            fileSize = 2 * mb,
            mimeType = "image/jpeg",
            cdnAdvertised = false,
            settings = CdnSettings(mode = CdnSendMode.LINK, askCompress = false),
        )

        assertFalse(input.linkAvailable)
        assertFalse(input.linkOffered)
        assertFalse(CdnSendPath.options(input).contains(CdnSendOption.ENCRYPTED_LINK))
        assertEquals(CdnSendAction.COMPRESS, CdnSendPath.action(input))
    }

    @Test
    fun overTheUploadLimitDropsTheLinkOption() {
        val input = input(
            fileSize = 60 * mb,
            uploadLimitMb = 50,
            mimeType = "image/jpeg",
            settings = CdnSettings(mode = CdnSendMode.LINK),
        )

        assertFalse(input.linkAvailable)
        assertEquals(CdnSendAction.ASK, CdnSendPath.action(input))
        assertEquals(listOf(CdnSendOption.COMPRESS), CdnSendPath.options(input))
    }

    @Test
    fun theDefaultUploadLimitCarriesLargeFilesUpTo250Mb() {
        val limit = CdnSettings.DEFAULT_UPLOAD_LIMIT_MB * mb
        val fits = input(
            fileSize = limit - CdnCrypto.OVERHEAD_BYTES,
            uploadLimitMb = CdnSettings.DEFAULT_UPLOAD_LIMIT_MB,
            settings = CdnSettings(mode = CdnSendMode.LINK),
        )
        val doesNotFit = input(
            fileSize = limit - CdnCrypto.OVERHEAD_BYTES + 1,
            uploadLimitMb = CdnSettings.DEFAULT_UPLOAD_LIMIT_MB,
            mimeType = "image/jpeg",
            settings = CdnSettings(mode = CdnSendMode.LINK),
        )

        assertTrue(fits.linkAvailable)
        assertEquals(CdnSendAction.LINK, CdnSendPath.action(fits))
        assertFalse(doesNotFit.linkAvailable)
        assertFalse(CdnSendPath.options(doesNotFit).contains(CdnSendOption.ENCRYPTED_LINK))
        assertEquals(CdnSendAction.ASK, CdnSendPath.action(doesNotFit))
    }

    @Test
    fun theGcmOverheadCountsAgainstTheUploadLimit() {
        val limit = 10 * mb
        val fits = input(
            fileSize = limit - CdnCrypto.OVERHEAD_BYTES,
            uploadLimitMb = 10,
            settings = CdnSettings(mode = CdnSendMode.LINK),
        )
        val doesNotFit = input(
            fileSize = limit - CdnCrypto.OVERHEAD_BYTES + 1,
            uploadLimitMb = 10,
            settings = CdnSettings(mode = CdnSendMode.LINK),
        )

        assertTrue(fits.linkAvailable)
        assertEquals(CdnSendAction.LINK, CdnSendPath.action(fits))
        assertFalse(doesNotFit.linkAvailable)
    }

    @Test
    fun autoThresholdIsInertWithoutACarrierLimit() {
        listOf(CdnSendMode.ASK, CdnSendMode.LINK, CdnSendMode.NEVER).forEach { mode ->
            val input = input(
                fileSize = 100 * mb,
                mmsLimit = FILE_SIZE_NONE,
                settings = CdnSettings(mode = mode, thresholdMb = CdnThreshold.AUTO_MB),
            )

            assertFalse(input.overThreshold)
            assertEquals(CdnSendAction.NORMAL, CdnSendPath.action(input))
        }
    }

    @Test
    fun theShippedDefaultsLinkEveryAttachment() {
        val input = input(
            fileSize = 100 * 1024L,
            mimeType = "image/jpeg",
            settings = CdnSettings.DEFAULTS,
        )

        assertTrue(input.overThreshold)
        assertTrue(input.linkOffered)
        assertEquals(CdnSendAction.LINK, CdnSendPath.action(input))
    }

    @Test
    fun aFixedThresholdWorksWithoutACarrierLimit() {
        val input = input(
            fileSize = 12 * mb,
            mmsLimit = FILE_SIZE_NONE,
            settings = CdnSettings(mode = CdnSendMode.LINK, thresholdMb = 5),
        )

        assertTrue(input.overThreshold)
        assertEquals(CdnSendAction.LINK, CdnSendPath.action(input))
    }

    @Test
    fun zeroThresholdUploadsEvenSmallAttachments() {
        val input = input(
            fileSize = 100 * 1024L,
            settings = CdnSettings(mode = CdnSendMode.LINK, thresholdMb = 0),
        )

        assertTrue(input.overThreshold)
        assertEquals(CdnSendAction.LINK, CdnSendPath.action(input))
    }

    @Test
    fun theDialogOffersLinkCompressionAndMmsWhenAllApply() {
        val input = input(
            fileSize = 2 * mb,
            mmsLimit = 5 * mb,
            mimeType = "image/jpeg",
            settings = CdnSettings(mode = CdnSendMode.ASK, thresholdMb = 1),
        )

        assertEquals(CdnSendAction.ASK, CdnSendPath.action(input))
        assertEquals(
            listOf(CdnSendOption.ENCRYPTED_LINK, CdnSendOption.COMPRESS, CdnSendOption.MMS),
            CdnSendPath.options(input),
        )
    }

    @Test
    fun neverModeNeverListsTheLinkOption() {
        val input = input(
            fileSize = 2 * mb,
            mimeType = "image/jpeg",
            settings = CdnSettings(mode = CdnSendMode.NEVER),
        )

        assertEquals(listOf(CdnSendOption.COMPRESS), CdnSendPath.options(input))
    }

    @Test
    fun withinCarrierLimitWithoutADialogGoesStraightToMms() {
        val input = input(
            fileSize = 2 * mb,
            mmsLimit = 5 * mb,
            cdnAdvertised = false,
            settings = CdnSettings(mode = CdnSendMode.ASK, thresholdMb = 1),
        )

        assertEquals(CdnSendAction.MMS, CdnSendPath.action(input))
    }

    @Test
    fun gifHasNoCompressorButCanGoAsLink() {
        val linked = input(
            fileSize = 2 * mb,
            mimeType = "image/gif",
            settings = CdnSettings(mode = CdnSendMode.ASK),
        )
        val noLink = input(
            fileSize = 2 * mb,
            mimeType = "image/gif",
            cdnAdvertised = false,
            settings = CdnSettings(mode = CdnSendMode.ASK),
        )

        assertEquals(listOf(CdnSendOption.ENCRYPTED_LINK), CdnSendPath.options(linked))
        assertEquals(CdnSendAction.REJECT, CdnSendPath.action(noLink))
    }
}
