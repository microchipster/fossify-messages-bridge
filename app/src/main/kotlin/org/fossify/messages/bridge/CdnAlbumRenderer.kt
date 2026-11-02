/*
 * SPDX-FileCopyrightText: 2026 microchipster
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.fossify.messages.bridge

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** The outcome of one album manifest request, delivered on the main thread. */
internal sealed class CdnAlbumResult {
    /** [items] is the decrypted, validated manifest in display order. */
    data class Ready(val items: List<CdnAlbumItem>) : CdnAlbumResult()

    /** The link is not a readable album (wrong key, gone, or not an album). */
    object Failed : CdnAlbumResult()
}

/**
 * Pure column math of the inline album grid: two to four columns by bubble
 * width, so a phone shows a 2- or 3-column grid and wider layouts reach four.
 * Everything here is free of Android APIs, so the matrix is a JVM test.
 */
internal object CdnAlbumLayout {
    const val MIN_COLUMNS = 2
    const val MAX_COLUMNS = 4

    /** Columns that fit [widthPx], clamped to [MIN_COLUMNS]..[MAX_COLUMNS]. */
    fun columnsForWidth(widthPx: Int, minCellWidthPx: Int): Int {
        if (widthPx <= 0 || minCellWidthPx <= 0) {
            return MIN_COLUMNS
        }
        return (widthPx / minCellWidthPx).coerceIn(MIN_COLUMNS, MAX_COLUMNS)
    }
}

/**
 * Fetches, decrypts and caches album manifests and items for inline gallery
 * rendering, mirroring [CdnMediaRenderer].
 *
 * A manifest is fetched once per id (waiters join the first request), then
 * cached as its decrypted JSON; every item is fetched and decrypted under the
 * HKDF subkey of its 0-based position and cached next to the single files,
 * trimmed by the same bounded disk budget. Decoded thumbnails and kinds go
 * through the file renderer's bounded memory caches, keyed by
 * `<id>.<index>` so an album item never aliases a single-file card. The
 * fragment key stays inside [CdnClient]; nothing here logs a link, a key or a
 * decrypted body.
 */
internal object CdnAlbumRenderer {
    private const val TAG = "CdnAlbumRenderer"
    private const val MISSING_CACHE_ENTRIES = 128

    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val manifestInFlight = HashMap<String, MutableList<(CdnAlbumResult) -> Unit>>()
    private val itemInFlight = HashMap<String, MutableList<(CdnRenderResult) -> Unit>>()

    /** Ids whose manifest route answered 404, so a rebind does not refetch a dead album. */
    private val missingManifests = object : LruCache<String, Boolean>(MISSING_CACHE_ENTRIES) {}

    /**
     * Delivers the decrypted manifest to [callback] on the main thread: straight
     * from the disk cache when present, otherwise after fetching and
     * decrypting it. A 404 (single file, unknown or expired id) is [CdnAlbumResult.Failed].
     */
    fun loadManifest(context: Context, ref: CdnLinkRef, callback: (CdnAlbumResult) -> Unit) {
        val appContext = context.applicationContext
        val file = CdnMediaCache.albumManifestFile(appContext, ref.id)
        if (file == null) {
            mainHandler.post { callback(CdnAlbumResult.Failed) }
            return
        }

        if (file.exists()) {
            worker.execute { deliverManifest(file, callback) }
            return
        }

        if (missingManifests.get(ref.id) == true) {
            mainHandler.post { callback(CdnAlbumResult.Failed) }
            return
        }

        val first = synchronized(manifestInFlight) {
            val waiters = manifestInFlight.getOrPut(ref.id) { mutableListOf() }
            waiters += callback
            waiters.size == 1
        }
        if (first) {
            worker.execute { fetchManifest(appContext, ref) }
        }
    }

    /**
     * Delivers one decrypted item to [callback] on the main thread, like
     * [CdnMediaRenderer.load]; a payload on disk (memory and disk caches
     * survive scrolling) is only decoded again. [previewWidthPx] bounds the
     * decoded preview size.
     */
    fun loadItem(
        context: Context,
        ref: CdnLinkRef,
        index: Int,
        previewWidthPx: Int,
        callback: (CdnRenderResult) -> Unit,
    ) {
        val appContext = context.applicationContext
        val cacheKey = CdnMediaCache.albumItemName(ref.id, index)
        val file = CdnMediaCache.albumItemFile(appContext, ref.id, index)
        if (cacheKey == null || file == null) {
            mainHandler.post { callback(CdnRenderResult.Failed) }
            return
        }

        if (file.exists()) {
            worker.execute { deliverItem(cacheKey, file, previewWidthPx, callback) }
            return
        }

        val first = synchronized(itemInFlight) {
            val waiters = itemInFlight.getOrPut(cacheKey) { mutableListOf() }
            waiters += callback
            waiters.size == 1
        }
        if (first) {
            worker.execute { fetchItem(appContext, ref, index, cacheKey, previewWidthPx) }
        }
    }

    private fun deliverManifest(file: File, callback: (CdnAlbumResult) -> Unit) {
        val result = parseManifestFile(file)
        mainHandler.post { callback(result) }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun parseManifestFile(file: File): CdnAlbumResult {
        return try {
            CdnAlbum.parseManifest(file.readBytes())?.let(CdnAlbumResult::Ready) ?: CdnAlbumResult.Failed
        } catch (e: Exception) {
            Log.w(TAG, "cannot read the cached album manifest", e)
            CdnAlbumResult.Failed
        }
    }

    /** Blocking; runs on the worker thread. */
    @Suppress("TooGenericExceptionCaught")
    private fun fetchManifest(context: Context, ref: CdnLinkRef) {
        val file = CdnMediaCache.albumManifestFile(context, ref.id)
        val result = try {
            val items = CdnClient.downloadAlbumManifest(ref.baseUrl, ref.id, ref.keyFragment, token = CdnClient.ownerToken(context))
            if (items == null || file == null) {
                missingManifests.put(ref.id, true)
                CdnAlbumResult.Failed
            } else {
                CdnMediaCache.write(file, CdnAlbum.manifestJson(items))
                CdnAlbumResult.Ready(items)
            }
        } catch (e: CdnException) {
            Log.w(TAG, "album manifest fetch failed: ${e.code}: ${e.message}")
            CdnAlbumResult.Failed
        } catch (e: Exception) {
            Log.w(TAG, "album manifest fetch failed", e)
            CdnAlbumResult.Failed
        }

        val waiters = synchronized(manifestInFlight) { manifestInFlight.remove(ref.id).orEmpty() }
        waiters.forEach { callback -> mainHandler.post { callback(result) } }
    }

    private fun deliverItem(cacheKey: String, file: File, previewWidthPx: Int, callback: (CdnRenderResult) -> Unit) {
        val result = CdnMediaRenderer.preview(cacheKey, file, previewWidthPx)
        mainHandler.post { callback(result) }
    }

    /** Blocking; runs on the worker thread. */
    @Suppress("TooGenericExceptionCaught")
    private fun fetchItem(context: Context, ref: CdnLinkRef, index: Int, cacheKey: String, previewWidthPx: Int) {
        val file = CdnMediaCache.albumItemFile(context, ref.id, index)
        val result = try {
            if (file == null) {
                CdnRenderResult.Failed
            } else {
                val bytes = CdnClient.downloadAlbumItem(
                    baseUrl = ref.baseUrl,
                    id = ref.id,
                    index = index,
                    keyFragment = ref.keyFragment,
                    token = CdnClient.ownerToken(context),
                    maxBytes = CdnMediaRenderer.MAX_RENDER_BYTES,
                )
                CdnMediaCache.write(file, bytes)
                CdnMediaRenderer.preview(cacheKey, file, previewWidthPx)
            }
        } catch (e: CdnException) {
            Log.w(TAG, "album item fetch failed: ${e.code}: ${e.message}")
            CdnRenderResult.Failed
        } catch (e: Exception) {
            Log.w(TAG, "album item fetch failed", e)
            CdnRenderResult.Failed
        }

        val waiters = synchronized(itemInFlight) { itemInFlight.remove(cacheKey).orEmpty() }
        waiters.forEach { callback -> mainHandler.post { callback(result) } }
    }
}
