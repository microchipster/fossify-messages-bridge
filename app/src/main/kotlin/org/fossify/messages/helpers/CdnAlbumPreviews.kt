/*
 * SPDX-FileCopyrightText: 2026 The Fossify Messages bridge contributors
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.fossify.messages.helpers

import android.app.Activity
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.widget.GridLayout
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getMyFileUri
import org.fossify.messages.R
import org.fossify.messages.bridge.CdnAlbumItem
import org.fossify.messages.bridge.CdnAlbumLayout
import org.fossify.messages.bridge.CdnMedia
import org.fossify.messages.bridge.CdnRenderResult
import org.fossify.messages.databinding.ItemAttachmentAudioBinding
import org.fossify.messages.databinding.ItemAttachmentDocumentBinding
import org.fossify.messages.databinding.ItemCdnAlbumCellBinding
import org.fossify.messages.databinding.ItemCdnMediaBinding
import org.fossify.messages.extensions.isAudioMimeType
import org.fossify.messages.extensions.isImageMimeType
import org.fossify.messages.extensions.isVideoMimeType
import org.fossify.messages.extensions.launchViewIntent

/**
 * Inline album gallery (the multi-item counterpart of [showCdnMedia]): a
 * manifest renders as a 2-4 column grid, every cell fetching its own item
 * under its HKDF subkey. Images become cover-cropped thumbnails, videos keep
 * their poster with a play outline, audio keeps the compact inline player and
 * everything else the file card; taps open the existing viewer path.
 */

/** Smallest width of one thumbnail cell; wider bubbles fall back to more columns. */
private const val MIN_CELL_DP = 96
private const val CELL_SPACING_DP = 2

/**
 * Replaces the card content with the album grid and hands back the cells in
 * display order. Every cell starts in its loading state; the caller drives
 * [CdnAlbumCell.show] with the decrypted item it fetched from
 * `CdnAlbumRenderer`.
 */
internal fun ItemCdnMediaBinding.showCdnAlbumGrid(
    activity: Activity,
    items: List<CdnAlbumItem>,
    maxWidthPx: Int,
    onSelect: () -> Unit,
    onLongClick: () -> Unit,
    isSelecting: () -> Boolean,
): List<CdnAlbumCell> {
    cdnMediaLoading.beGone()
    cdnMediaError.beGone()
    cdnMediaPreview.beGone()
    cdnMediaContent.removeAllViews()
    cdnMediaContent.beVisible()

    val density = activity.resources.displayMetrics.density
    val minCellPx = (MIN_CELL_DP * density).toInt()
    val spacingPx = (CELL_SPACING_DP * density).toInt()
    val columns = CdnAlbumLayout.columnsForWidth(maxWidthPx, minCellPx)
    val columnPx = (maxWidthPx / columns).coerceAtLeast(1)
    val cellPx = (columnPx - 2 * spacingPx).coerceAtLeast(1)
    val grid = GridLayout(activity).apply { columnCount = columns }
    cdnMediaContent.addView(grid)

    val cells = mutableListOf<CdnAlbumCell>()
    var row = 0
    var column = 0
    items.forEach { item ->
        val fullWidth = !item.isMedia()
        val span = if (fullWidth) columns else 1
        if (column + span > columns) {
            row++
            column = 0
        }
        val width = if (fullWidth) maxWidthPx - 2 * spacingPx else cellPx
        val binding = ItemCdnAlbumCellBinding.inflate(activity.layoutInflater, grid, false)
        val params = GridLayout.LayoutParams(
            GridLayout.spec(row, 1),
            GridLayout.spec(column, span),
        ).apply {
            this.width = width
            height = if (fullWidth) ViewGroup.LayoutParams.WRAP_CONTENT else width
            setMargins(spacingPx, spacingPx, spacingPx, spacingPx)
        }
        grid.addView(binding.root, params)
        val cell = CdnAlbumCell(binding, activity, item, width, onSelect, onLongClick, isSelecting)
        cell.showLoading()
        cells += cell
        column += span
        if (column >= columns) {
            row++
            column = 0
        }
    }
    return cells
}

/** One grid cell of an album card; reused per message bind. */
internal class CdnAlbumCell(
    private val binding: ItemCdnAlbumCellBinding,
    private val activity: Activity,
    private val item: CdnAlbumItem,
    val previewWidthPx: Int,
    private val onSelect: () -> Unit,
    private val onLongClick: () -> Unit,
    private val isSelecting: () -> Boolean,
) {
    init {
        // Long-presses keep the message selectable while an item loads.
        binding.cdnAlbumCellHolder.setOnLongClickListener {
            onLongClick()
            true
        }
    }

    /** The placeholder state of one cell, shown until its item is decrypted. */
    fun showLoading() {
        binding.cdnAlbumCellImage.beGone()
        binding.cdnAlbumCellPlay.beGone()
        binding.cdnAlbumCellContent.removeAllViews()
        binding.cdnAlbumCellContent.beGone()
        binding.cdnAlbumCellError.beGone()
        binding.cdnAlbumCellProgress.beVisible()
    }

    /** Shows the decrypted item, or the cell's own failure state. */
    fun show(result: CdnRenderResult) {
        binding.cdnAlbumCellProgress.beGone()
        when (result) {
            is CdnRenderResult.Ready -> showMedia(result)
            else -> showError()
        }
    }

    private fun showError() {
        binding.cdnAlbumCellImage.beGone()
        binding.cdnAlbumCellPlay.beGone()
        binding.cdnAlbumCellContent.removeAllViews()
        binding.cdnAlbumCellContent.beGone()
        binding.cdnAlbumCellError.beVisible()
    }

    private fun showMedia(result: CdnRenderResult.Ready) {
        val media = result.media
        val uri = activity.getMyFileUri(media.file)
        when {
            media.kind.isImage && result.bitmap != null -> showThumbnail(uri, media, result.bitmap, playable = false)
            media.kind.isVideo && result.bitmap != null -> showThumbnail(uri, media, result.bitmap, playable = true)
            media.kind.isAudio -> showAudio(uri, media)
            else -> showAttachment(uri, media)
        }
    }

    private fun showThumbnail(uri: Uri, media: CdnMedia, bitmap: Bitmap, playable: Boolean) {
        binding.cdnAlbumCellContent.beGone()
        binding.cdnAlbumCellError.beGone()
        binding.cdnAlbumCellImage.apply {
            beVisible()
            setImageBitmap(bitmap)
        }
        binding.cdnAlbumCellPlay.beVisibleIf(playable)
        binding.cdnAlbumCellHolder.setOnClickListener { openOrSelect(uri, media) }
    }

    private fun showAudio(uri: Uri, media: CdnMedia) {
        binding.cdnAlbumCellImage.beGone()
        binding.cdnAlbumCellPlay.beGone()
        binding.cdnAlbumCellError.beGone()
        binding.cdnAlbumCellContent.apply {
            removeAllViews()
            beVisible()
        }
        val audioView = ItemAttachmentAudioBinding.inflate(activity.layoutInflater).apply {
            setupAudio(
                uri = uri,
                onOpenWith = { activity.launchViewIntent(uri, media.mimeType, displayName()) },
                onSelect = onSelect,
                onLongClick = onLongClick,
                isSelecting = isSelecting,
            )
        }.root
        binding.cdnAlbumCellContent.addView(audioView)
    }

    private fun showAttachment(uri: Uri, media: CdnMedia) {
        binding.cdnAlbumCellImage.beGone()
        binding.cdnAlbumCellPlay.beGone()
        binding.cdnAlbumCellError.beGone()
        binding.cdnAlbumCellContent.apply {
            removeAllViews()
            beVisible()
        }
        val documentView = ItemAttachmentDocumentBinding.inflate(activity.layoutInflater).apply {
            setupDocumentPreview(
                uri = uri,
                title = displayName(),
                mimeType = media.mimeType,
                onClick = { openOrSelect(uri, media) },
                onLongClick = onLongClick,
            )
        }.root
        binding.cdnAlbumCellContent.addView(documentView)
    }

    private fun openOrSelect(uri: Uri, media: CdnMedia) {
        if (isSelecting()) {
            onSelect()
        } else {
            activity.launchViewIntent(uri, media.mimeType, displayName())
        }
    }

    private fun displayName(): String = item.name.ifBlank { activity.getString(R.string.cdn_render_attachment) }
}

/** True for payload types the grid can preview as a thumbnail or a player. */
private fun CdnAlbumItem.isMedia(): Boolean {
    return type.isImageMimeType() || type.isVideoMimeType() || type.isAudioMimeType()
}
