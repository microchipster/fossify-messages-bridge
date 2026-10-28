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
 * The album decision matrix of the send path: item count x threshold x mode x
 * the album caps map to [CdnAlbumSendAction]. "Send as album" is only offered
 * for more than one media item, when the bridge advertised a `cdn_base_url`,
 * when every item and the whole album fit the caps, and either the selection
 * is above the threshold or the mode is Encrypted link.
 */
class CdnAlbumSendPathTest {
    private val mb = 1024L * 1024L
    private val carrierLimit = 1 * mb
    private val image = "image/jpeg"
    private val video = "video/mp4"

    private fun input(
        itemSizes: List<Long> = listOf(2 * mb, 2 * mb),
        mimeTypes: List<String> = listOf(image, video),
        mmsLimit: Long = carrierLimit,
        cdnAdvertised: Boolean = true,
        settings: CdnSettings = CdnSettings(),
    ) = CdnAlbumSendInput(
        itemSizesBytes = itemSizes,
        mimeTypes = mimeTypes,
        mmsLimitBytes = mmsLimit,
        cdnAdvertised = cdnAdvertised,
        settings = settings,
    )

    @Test
    fun aboveThresholdAsksBeforeUploading() {
        val input = input(
            itemSizes = listOf(3 * mb, 3 * mb),
            settings = CdnSettings(mode = CdnSendMode.ASK, thresholdMb = 5),
        )

        assertTrue(input.overThreshold)
        assertTrue(input.albumOffered)
        assertEquals(CdnAlbumSendAction.ASK, CdnAlbumSendPath.action(input))
    }

    @Test
    fun belowThresholdKeepsTheOrdinaryAttachPathInAskMode() {
        val input = input(
            itemSizes = listOf(100 * 1024L, 100 * 1024L),
            settings = CdnSettings(mode = CdnSendMode.ASK, thresholdMb = CdnThreshold.AUTO_MB),
        )

        assertFalse(input.overThreshold)
        assertFalse(input.albumOffered)
        assertEquals(CdnAlbumSendAction.NONE, CdnAlbumSendPath.action(input))
    }

    @Test
    fun theShippedDefaultsOfferTheAlbumRightAway() {
        val input = input(itemSizes = listOf(100 * 1024L, 100 * 1024L), settings = CdnSettings.DEFAULTS)

        assertTrue(input.overThreshold)
        assertTrue(input.albumOffered)
        assertEquals(CdnAlbumSendAction.LINK, CdnAlbumSendPath.action(input))
    }

    @Test
    fun linkModeUploadsEvenBelowTheThreshold() {
        val input = input(
            itemSizes = listOf(100 * 1024L, 100 * 1024L),
            settings = CdnSettings(mode = CdnSendMode.LINK, thresholdMb = CdnThreshold.AUTO_MB),
        )

        assertFalse(input.overThreshold)
        assertTrue(input.albumOffered)
        assertEquals(CdnAlbumSendAction.LINK, CdnAlbumSendPath.action(input))
    }

    @Test
    fun neverModeNeverOffersTheAlbum() {
        val input = input(settings = CdnSettings(mode = CdnSendMode.NEVER, thresholdMb = 0))

        assertFalse(input.albumOffered)
        assertEquals(CdnAlbumSendAction.NONE, CdnAlbumSendPath.action(input))
    }

    @Test
    fun aSingleItemIsNeverAnAlbum() {
        val input = input(
            itemSizes = listOf(2 * mb),
            mimeTypes = listOf(image),
            settings = CdnSettings(mode = CdnSendMode.LINK),
        )

        assertFalse(input.withinAlbumLimits)
        assertFalse(input.albumOffered)
        assertEquals(CdnAlbumSendAction.NONE, CdnAlbumSendPath.action(input))
    }

    @Test
    fun noAdvertisedBaseUrlKeepsTheAlbumPathInert() {
        val input = input(cdnAdvertised = false, settings = CdnSettings(mode = CdnSendMode.LINK))

        assertFalse(input.linkAvailable)
        assertEquals(CdnAlbumSendAction.NONE, CdnAlbumSendPath.action(input))
    }

    @Test
    fun nonMediaSelectionsKeepTheSingleAttachmentPath() {
        val input = input(mimeTypes = listOf(image, "application/pdf"), settings = CdnSettings(mode = CdnSendMode.LINK))

        assertFalse(input.allMedia)
        assertFalse(input.albumOffered)
        assertEquals(CdnAlbumSendAction.NONE, CdnAlbumSendPath.action(input))
    }

    @Test
    fun anItemAboveTheUploadLimitDropsTheAlbum() {
        val input = input(
            itemSizes = listOf(2 * mb, 60 * mb),
            settings = CdnSettings(mode = CdnSendMode.LINK, uploadLimitMb = 50),
        )

        assertFalse(input.fitsItemLimit)
        assertFalse(input.albumOffered)
        assertEquals(CdnAlbumSendAction.NONE, CdnAlbumSendPath.action(input))
    }

    @Test
    fun theItemCountCapDropsTheAlbum() {
        val sizes = List(CdnAlbum.MAX_ITEMS + 1) { 1024L }
        val input = input(
            itemSizes = sizes,
            mimeTypes = List(sizes.size) { image },
            settings = CdnSettings(mode = CdnSendMode.LINK),
        )

        assertFalse(input.withinAlbumLimits)
        assertFalse(input.albumOffered)
    }

    @Test
    fun theAlbumSizeCapDropsTheAlbum() {
        // Three items around 167 MB each: every one fits the 250 MB item limit,
        // their sum does not fit the 500 MiB album cap.
        val itemSize = CdnAlbum.MAX_ALBUM_BYTES / 3 + mb
        val sizes = List(3) { itemSize }
        val input = input(
            itemSizes = sizes,
            mimeTypes = List(sizes.size) { video },
            settings = CdnSettings(mode = CdnSendMode.LINK, uploadLimitMb = CdnSettings.MAX_UPLOAD_LIMIT_MB),
        )

        assertTrue(input.fitsItemLimit)
        assertFalse(input.withinAlbumLimits)
        assertFalse(input.albumOffered)
    }

    @Test
    fun theDefaultUploadLimitAdmitsItemsUpTo250Mb() {
        val nearLimit = CdnSettings.DEFAULT_UPLOAD_LIMIT_MB * mb - CdnCrypto.OVERHEAD_BYTES
        val input = input(
            itemSizes = listOf(nearLimit, 2 * mb),
            settings = CdnSettings(mode = CdnSendMode.LINK),
        )

        assertTrue(input.fitsItemLimit)
        assertTrue(input.albumOffered)
        assertEquals(CdnAlbumSendAction.LINK, CdnAlbumSendPath.action(input))
    }

    @Test
    fun unknownSizesOrAnEmptyThresholdAreMirroredFromTheSinglePath() {
        val unknownSize = input(itemSizes = listOf(0, 2 * mb), settings = CdnSettings(mode = CdnSendMode.LINK))
        assertFalse(unknownSize.fitsItemLimit)
        assertFalse(unknownSize.albumOffered)

        val autoWithoutCarrierLimit = input(
            itemSizes = listOf(2 * mb, 2 * mb),
            mmsLimit = FILE_SIZE_NONE,
            settings = CdnSettings(mode = CdnSendMode.ASK, thresholdMb = CdnThreshold.AUTO_MB),
        )
        assertFalse(autoWithoutCarrierLimit.overThreshold)
        assertFalse(autoWithoutCarrierLimit.albumOffered)
    }

    @Test
    fun aFixedThresholdWorksWithoutACarrierLimit() {
        val input = input(
            mmsLimit = FILE_SIZE_NONE,
            settings = CdnSettings(mode = CdnSendMode.ASK, thresholdMb = 1),
        )

        assertTrue(input.overThreshold)
        assertEquals(CdnAlbumSendAction.ASK, CdnAlbumSendPath.action(input))
    }
}
