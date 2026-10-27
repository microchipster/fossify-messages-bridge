/*
 * SPDX-FileCopyrightText: 2026 microchipster
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.fossify.messages.helpers

import android.app.Activity
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import androidx.core.view.updateLayoutParams
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getColorStateList
import org.fossify.messages.R
import org.fossify.messages.bridge.CdnMedia
import org.fossify.messages.bridge.CdnMediaRenderer
import org.fossify.messages.databinding.ItemAttachmentAudioBinding
import org.fossify.messages.databinding.ItemAttachmentDocumentBinding
import org.fossify.messages.databinding.ItemCdnMediaBinding
import org.fossify.messages.extensions.launchViewIntent

/**
 * Inline file-service cards (roadmap item F), the encrypted-link counterpart
 * of [AttachmentPreviews]: the renderer decides what a payload is, these
 * extensions turn it into the card the thread shows.
 */

/** The compact placeholder shown while the payload is fetched and decrypted. */
internal fun ItemCdnMediaBinding.showCdnLoading(textColor: Int, primaryColor: Int) {
    cdnMediaLoadingText.setTextColor(textColor)
    cdnMediaError.setTextColor(textColor)
    cdnMediaProgress.indeterminateTintList = primaryColor.getColorStateList()
    cdnMediaImage.setImageDrawable(null)
    cdnMediaPreview.beGone()
    cdnMediaContent.removeAllViews()
    cdnMediaContent.beGone()
    cdnMediaError.beGone()
    cdnMediaLoading.beVisible()
}

/** The `Couldn't load` state; the bubble stays long-pressable for copying the link. */
internal fun ItemCdnMediaBinding.showCdnError() {
    cdnMediaLoading.beGone()
    cdnMediaPreview.beGone()
    cdnMediaContent.removeAllViews()
    cdnMediaContent.beGone()
    cdnMediaError.beVisible()
}

/**
 * Shows a decrypted payload: a thumbnail for images, a poster frame with a
 * play outline for videos, the inline audio player, or - for PDFs and
 * everything else - the compact attachment card that opens an external
 * viewer.
 */
internal fun ItemCdnMediaBinding.showCdnMedia(
    activity: Activity,
    uri: Uri,
    media: CdnMedia,
    bitmap: Bitmap?,
    maxPreviewWidth: Int,
    onSelect: () -> Unit,
    onLongClick: () -> Unit,
    isSelecting: () -> Boolean
) {
    cdnMediaLoading.beGone()
    cdnMediaError.beGone()
    cdnMediaContent.removeAllViews()
    when {
        media.kind.isImage && bitmap != null -> showCdnPreview(
            activity, uri, media, bitmap, maxPreviewWidth, onSelect, onLongClick, isSelecting, playable = false
        )

        media.kind.isVideo && bitmap != null -> showCdnPreview(
            activity, uri, media, bitmap, maxPreviewWidth, onSelect, onLongClick, isSelecting, playable = true
        )

        media.kind.isAudio -> showCdnAudio(activity, uri, media, onSelect, onLongClick, isSelecting)
        else -> showCdnAttachment(activity, uri, media, onSelect, onLongClick, isSelecting)
    }
}

private fun ItemCdnMediaBinding.showCdnPreview(
    activity: Activity,
    uri: Uri,
    media: CdnMedia,
    bitmap: Bitmap,
    maxPreviewWidth: Int,
    onSelect: () -> Unit,
    onLongClick: () -> Unit,
    isSelecting: () -> Boolean,
    playable: Boolean
) {
    cdnMediaContent.beGone()
    cdnMediaPreview.beVisible()
    cdnMediaPlay.beVisibleIf(playable)
    cdnMediaImage.setImageBitmap(bitmap)

    // A small preview fills the bubble width; a tall one keeps its aspect
    // ratio inside the same height bound the MMS images use.
    val maxWidth = maxPreviewWidth.coerceAtLeast(1)
    val maxHeight = maxWidth * CdnMediaRenderer.PREVIEW_HEIGHT_RATIO
    val scale = minOf(maxWidth / bitmap.width.toFloat(), maxHeight / bitmap.height.toFloat())
    cdnMediaImage.updateLayoutParams<ViewGroup.LayoutParams> {
        width = (bitmap.width * scale).toInt().coerceAtLeast(1)
        height = (bitmap.height * scale).toInt().coerceAtLeast(1)
    }

    cdnMediaPreview.setOnClickListener {
        if (isSelecting()) onSelect() else activity.launchViewIntent(uri, media.mimeType, media.file.name)
    }
    cdnMediaPreview.setOnLongClickListener {
        onLongClick()
        true
    }
}

private fun ItemCdnMediaBinding.showCdnAudio(
    activity: Activity,
    uri: Uri,
    media: CdnMedia,
    onSelect: () -> Unit,
    onLongClick: () -> Unit,
    isSelecting: () -> Boolean
) {
    cdnMediaContent.beVisible()
    val audioView = ItemAttachmentAudioBinding.inflate(activity.layoutInflater).apply {
        setupAudio(
            uri = uri,
            onOpenWith = { activity.launchViewIntent(uri, media.mimeType, media.file.name) },
            onSelect = onSelect,
            onLongClick = onLongClick,
            isSelecting = isSelecting
        )
    }.root
    cdnMediaContent.addView(audioView)
}

private fun ItemCdnMediaBinding.showCdnAttachment(
    activity: Activity,
    uri: Uri,
    media: CdnMedia,
    onSelect: () -> Unit,
    onLongClick: () -> Unit,
    isSelecting: () -> Boolean
) {
    cdnMediaContent.beVisible()
    val attachmentView = ItemAttachmentDocumentBinding.inflate(activity.layoutInflater).apply {
        setupDocumentPreview(
            uri = uri,
            title = activity.getString(R.string.cdn_render_attachment),
            mimeType = media.mimeType,
            onClick = {
                if (isSelecting()) onSelect() else activity.launchViewIntent(uri, media.mimeType, media.file.name)
            },
            onLongClick = onLongClick
        )
    }.root
    cdnMediaContent.addView(attachmentView)
}
