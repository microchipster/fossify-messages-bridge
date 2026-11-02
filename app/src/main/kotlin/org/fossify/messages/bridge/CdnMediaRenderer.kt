/*
 * SPDX-FileCopyrightText: 2026 microchipster
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.fossify.messages.bridge

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** One decrypted, sniffed payload of the file service; [file] lives in [CdnMediaCache]. */
internal data class CdnMedia(val file: File, val kind: CdnMediaKind) {
    val mimeType: String get() = kind.mimeType
}

/** The outcome of one inline render request, delivered on the main thread. */
internal sealed class CdnRenderResult {
    /** [bitmap] is the decoded preview of image and video payloads; null for the other kinds. */
    data class Ready(val media: CdnMedia, val bitmap: Bitmap?) : CdnRenderResult()

    /** The id is not a single file (the manifest route may answer): the album path may try. */
    object NotAFile : CdnRenderResult()

    /** The payload could not be fetched, decrypted or decoded. */
    object Failed : CdnRenderResult()
}

/**
 * Pure state machine of one inline card.
 *
 * A recycled row binds the same card view to another message, which starts a
 * new generation; an answer that arrives for the previous generation is
 * dropped instead of painting the new message. A card can be answered more
 * than once (a fetch joins the cache, a rebind reuses the disk), so only the
 * first answer of a generation wins.
 */
internal class CdnRenderStateMachine {
    enum class State { LOADING, READY, ERROR }

    var state: State = State.LOADING
        private set

    private var generation = 0

    /** Binds the card to another message; returns the new generation. */
    fun rebind(): Int {
        generation++
        state = State.LOADING
        return generation
    }

    /** Applies the first answer of [generation]; false when the answer is stale or late. */
    fun complete(generation: Int, loaded: Boolean): Boolean {
        if (generation != this.generation || state != State.LOADING) {
            return false
        }
        state = if (loaded) State.READY else State.ERROR
        return true
    }

    /** True while [generation] is the bound message; cell answers check it before painting. */
    fun isCurrent(generation: Int): Boolean = generation == this.generation
}

/**
 * Disk cache of decrypted payloads, keyed by the upload id only.
 *
 * The fragment key is never part of a file name, a path or a log line: it is
 * only held in memory while the fetch decrypts. Files live under
 * `cacheDir/cdn/<id>`, written through a temp file so a partial download never
 * looks complete, and the directory is trimmed oldest-first to
 * [MAX_DISK_BYTES]. The uploaded id alone cannot reconstruct the link, and the
 * same directory is what the viewer intents share through the FileProvider.
 */
internal object CdnMediaCache {
    const val DIR_NAME = "cdn"
    const val MAX_DISK_BYTES = 64L * 1024 * 1024

    /** The cache file of one upload; null when the id could escape the cache directory. */
    fun file(context: Context, id: String): File? {
        return fileName(id)?.let { File(File(context.cacheDir, DIR_NAME), it) }
    }

    /** The cache file of a decrypted album manifest; null for anything but a bridge id. */
    fun albumManifestFile(context: Context, id: String): File? {
        return albumManifestName(id)?.let { File(File(context.cacheDir, DIR_NAME), it) }
    }

    /** The cache file of one decrypted album item; null for a bad id or index. */
    fun albumItemFile(context: Context, id: String, index: Int): File? {
        return albumItemName(id, index)?.let { File(File(context.cacheDir, DIR_NAME), it) }
    }

    /** The file name of one upload; null for anything that is not a bridge id. */
    fun fileName(id: String): String? = id.takeIf { CdnLinkDetector.isValidId(it) }

    /** The cache file of a decrypted album manifest; null for anything but a bridge id. */
    fun albumManifestName(id: String): String? = fileName(id)?.let { "$it.manifest" }

    /** The cache file of one decrypted album item; null for a bad id or index. */
    fun albumItemName(id: String, index: Int): String? {
        if (index !in 0 until CdnAlbum.HARD_MAX_ITEMS) {
            return null
        }
        return fileName(id)?.let { "$it.$index" }
    }

    /** Writes [bytes] through a temp file, then trims the directory. */
    fun write(file: File, bytes: ByteArray) {
        val directory = file.parentFile ?: return
        directory.mkdirs()
        val temp = File(directory, "${file.name}.tmp")
        FileOutputStream(temp).use { it.write(bytes) }
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
        prune(directory, keep = file)
    }

    /** Keeps the newest files below [MAX_DISK_BYTES]; [keep] is never pruned. */
    private fun prune(directory: File, keep: File) {
        val files = directory.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_DISK_BYTES) {
            return
        }
        files.filter { it != keep }.sortedBy { it.lastModified() }.forEach { oldest ->
            if (total <= MAX_DISK_BYTES) {
                return
            }
            total -= oldest.length()
            oldest.delete()
        }
    }
}

/**
 * Fetches, decrypts, caches and previews file-service payloads for inline
 * rendering in a thread.
 *
 * Only one background worker touches [kinds] and [bitmaps]; the main thread
 * just hands out requests and receives results through [Handler]. Repeated
 * requests for the same upload join the first fetch, and a payload that is on
 * disk (memory and disk caches survive scrolling) is never fetched again. The
 * fragment key stays inside [CdnClient.download]; nothing here logs a link,
 * a key or a decrypted body.
 */
internal object CdnMediaRenderer {
    private const val TAG = "CdnMediaRenderer"

    /** Payloads with more ciphertext than this are not rendered inline (memory bound). */
    const val MAX_RENDER_BYTES = 24L * 1024 * 1024

    /** Preview images (and video posters) are never taller than this many preview widths. */
    const val PREVIEW_HEIGHT_RATIO = 3

    private const val MEMORY_CACHE_BYTES = 16 * 1024 * 1024
    private const val MAX_KIND_CACHE_ENTRIES = 256

    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val inFlight = HashMap<String, MutableList<(CdnRenderResult) -> Unit>>()

    /** Sniffed kinds of payloads seen this session, so a cached file is not re-read per scroll. */
    private val kinds = object : LruCache<String, CdnMediaKind>(MAX_KIND_CACHE_ENTRIES) {}

    /** Ids answered 404 by `/raw`: they are albums, so the manifest route is taken without probing again. */
    private val notFiles = object : LruCache<String, Boolean>(MAX_KIND_CACHE_ENTRIES) {}

    /** Decoded previews (thumbnails and video posters), bounded and keyed by upload id. */
    private val bitmaps = object : LruCache<String, Bitmap>(MEMORY_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount.coerceAtLeast(1)
    }

    /**
     * Delivers [ref] to [callback] on the main thread: the decoded preview
     * when the payload is already cached, otherwise after fetching and
     * decrypting it. [previewWidthPx] bounds the decoded image size.
     */
    fun load(context: Context, ref: CdnLinkRef, previewWidthPx: Int, callback: (CdnRenderResult) -> Unit) {
        val appContext = context.applicationContext
        val file = CdnMediaCache.file(appContext, ref.id)
        if (file == null) {
            mainHandler.post { callback(CdnRenderResult.Failed) }
            return
        }

        if (file.exists()) {
            worker.execute { deliver(file, ref.id, previewWidthPx, callback) }
            return
        }

        if (notFiles.get(ref.id) == true) {
            mainHandler.post { callback(CdnRenderResult.NotAFile) }
            return
        }

        val first = synchronized(inFlight) {
            val waiters = inFlight.getOrPut(ref.id) { mutableListOf() }
            waiters += callback
            waiters.size == 1
        }
        if (first) {
            worker.execute { fetch(appContext, ref, previewWidthPx) }
        }
    }

    private fun deliver(file: File, id: String, previewWidthPx: Int, callback: (CdnRenderResult) -> Unit) {
        val result = preview(id, file, previewWidthPx)
        mainHandler.post { callback(result) }
    }

    /** Blocking; runs on the worker thread. */
    @Suppress("TooGenericExceptionCaught")
    private fun fetch(context: Context, ref: CdnLinkRef, previewWidthPx: Int) {
        val file = CdnMediaCache.file(context, ref.id)
        val result = try {
            if (file == null) {
                CdnRenderResult.Failed
            } else {
                val bytes = CdnClient.download(ref.baseUrl, ref.id, ref.keyFragment, MAX_RENDER_BYTES)
                kinds.put(ref.id, CdnMediaType.sniff(bytes))
                CdnMediaCache.write(file, bytes)
                preview(ref.id, file, previewWidthPx)
            }
        } catch (e: CdnException) {
            if (e.code == CdnClient.CODE_NOT_FOUND) {
                // The id may be an album; the caller decides whether the
                // manifest route answers. Remember the 404 so a rebind does
                // not probe `/raw` again before the album path.
                notFiles.put(ref.id, true)
                CdnRenderResult.NotAFile
            } else {
                Log.w(TAG, "file service fetch failed: ${e.code}: ${e.message}")
                CdnRenderResult.Failed
            }
        } catch (e: Exception) {
            Log.w(TAG, "file service fetch failed", e)
            CdnRenderResult.Failed
        }

        val waiters = synchronized(inFlight) { inFlight.remove(ref.id).orEmpty() }
        waiters.forEach { callback -> mainHandler.post { callback(result) } }
    }

    /**
     * Sniffs, decodes and caches the preview of one decrypted payload that is
     * already on disk; blocking, runs on the worker thread. The shared caches
     * are keyed by [cacheKey], so album items reuse the file renderer's
     * bounded memory without colliding with single-file cards.
     */
    internal fun preview(cacheKey: String, file: File, previewWidthPx: Int): CdnRenderResult {
        val kind = kinds.get(cacheKey)
            ?: (sniffFile(file)?.also { kinds.put(cacheKey, it) } ?: return CdnRenderResult.Failed)
        val media = CdnMedia(file, kind)
        val bitmap = when {
            kind.isImage -> bitmaps.get(cacheKey) ?: decodeImage(file, previewWidthPx)
            kind.isVideo -> bitmaps.get(cacheKey) ?: videoPoster(file, previewWidthPx)
            else -> null
        }
        if (bitmap != null) {
            bitmaps.put(cacheKey, bitmap)
        }
        return CdnRenderResult.Ready(media, bitmap)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun sniffFile(file: File): CdnMediaKind? {
        return try {
            file.inputStream().use { stream ->
                val header = ByteArray(CdnMediaType.HEADER_BYTES)
                val read = stream.read(header)
                CdnMediaType.sniff(if (read <= 0) ByteArray(0) else header.copyOf(read))
            }
        } catch (e: Exception) {
            Log.w(TAG, "cannot read the cached payload header", e)
            null
        }
    }

    private fun decodeImage(file: File, previewWidthPx: Int): Bitmap? {
        return try {
            val targetWidth = previewWidthPx.coerceAtLeast(1)
            val targetHeight = targetWidth * PREVIEW_HEIGHT_RATIO
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null
            }
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, targetWidth, targetHeight)
            }
            val decoded = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
            scaleToFit(decoded, targetWidth, targetHeight)
        } catch (e: OutOfMemoryError) {
            // Degrade to the attachment card instead of killing the render worker.
            Log.w(TAG, "out of memory while decoding an image preview", e)
            null
        }
    }

    private fun sampleSizeFor(width: Int, height: Int, targetWidth: Int, targetHeight: Int): Int {
        var sample = 1
        while (width / sample > targetWidth || height / sample > targetHeight) {
            sample *= 2
        }
        return sample
    }

    @Suppress("TooGenericExceptionCaught")
    private fun videoPoster(file: File, previewWidthPx: Int): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
            scaleToFit(frame, previewWidthPx, previewWidthPx * PREVIEW_HEIGHT_RATIO)
        } catch (e: Exception) {
            Log.w(TAG, "cannot extract a video poster", e)
            null
        } finally {
            retriever.release()
        }
    }

    /** Scales [bitmap] down to fit the preview box without distortion; never upscales. */
    private fun scaleToFit(bitmap: Bitmap, maxWidth: Int, maxHeight: Int): Bitmap {
        if (maxWidth <= 0 || maxHeight <= 0) {
            return bitmap
        }
        val scale = minOf(maxWidth.toFloat() / bitmap.width, maxHeight.toFloat() / bitmap.height, 1f)
        if (scale >= 1f) {
            return bitmap
        }
        val width = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
        if (scaled != bitmap) {
            bitmap.recycle()
        }
        return scaled
    }
}
