/*
 * SPDX-FileCopyrightText: 2026 microchipster
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

@file:Suppress("MagicNumber")

package org.fossify.messages.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cache keys and the grid layout of the album renderer (the Android parts of
 * [CdnAlbumRenderer] - fetching, decrypting, decoding - are exercised on
 * device; the decisions here are pure). Album items share the single-file
 * cache directory but never its names: a manifest is `<id>.manifest` and an
 * item `<id>.<index>`.
 */
class CdnAlbumRendererTest {
    private val id = "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG"

    @Test
    fun manifestCacheKeyIsTheIdPlusAManifestSuffix() {
        assertEquals("$id.manifest", CdnMediaCache.albumManifestName(id))
    }

    @Test
    fun itemCacheKeyIsTheIdPlusTheZeroBasedIndex() {
        assertEquals("$id.0", CdnMediaCache.albumItemName(id, 0))
        assertEquals("$id.1", CdnMediaCache.albumItemName(id, 1))
        assertEquals("$id.254", CdnMediaCache.albumItemName(id, CdnAlbum.HARD_MAX_ITEMS - 1))
    }

    @Test
    fun cacheKeysRejectBadIdsAndIndexes() {
        assertNull(CdnMediaCache.albumManifestName(""))
        assertNull(CdnMediaCache.albumManifestName("short"))
        assertNull(CdnMediaCache.albumManifestName("$id/"))
        assertNull(CdnMediaCache.albumItemName(id, -1))
        assertNull(CdnMediaCache.albumItemName(id, CdnAlbum.HARD_MAX_ITEMS))
        assertNull(CdnMediaCache.albumItemName("short", 0))
    }

    @Test
    fun albumKeysNeverCollideWithSingleFileOrEachOther() {
        assertEquals(id, CdnMediaCache.fileName(id))
        assertNotEquals(CdnMediaCache.fileName(id), CdnMediaCache.albumManifestName(id))
        assertNotEquals(CdnMediaCache.albumManifestName(id), CdnMediaCache.albumItemName(id, 0))
        assertNotEquals(CdnMediaCache.albumItemName(id, 0), CdnMediaCache.albumItemName(id, 1))
    }

    @Test
    fun columnsFollowTheBubbleWidthBetweenTwoAndFour() {
        val minCell = 200

        assertEquals(2, CdnAlbumLayout.columnsForWidth(400, minCell))
        assertEquals(2, CdnAlbumLayout.columnsForWidth(500, minCell))
        assertEquals(3, CdnAlbumLayout.columnsForWidth(600, minCell))
        assertEquals(3, CdnAlbumLayout.columnsForWidth(790, minCell))
        assertEquals(4, CdnAlbumLayout.columnsForWidth(800, minCell))
        assertEquals(4, CdnAlbumLayout.columnsForWidth(4000, minCell))
    }

    @Test
    fun columnsStayInRangeForDegenerateWidths() {
        assertEquals(CdnAlbumLayout.MIN_COLUMNS, CdnAlbumLayout.columnsForWidth(0, 200))
        assertEquals(CdnAlbumLayout.MIN_COLUMNS, CdnAlbumLayout.columnsForWidth(-1, 200))
        assertEquals(CdnAlbumLayout.MIN_COLUMNS, CdnAlbumLayout.columnsForWidth(100, 0))
        assertEquals(CdnAlbumLayout.MIN_COLUMNS, CdnAlbumLayout.columnsForWidth(100, -5))
        assertTrue(CdnAlbumLayout.MAX_COLUMNS >= CdnAlbumLayout.MIN_COLUMNS)
        assertFalse(CdnAlbumLayout.columnsForWidth(10_000, 1) > CdnAlbumLayout.MAX_COLUMNS)
    }
}
