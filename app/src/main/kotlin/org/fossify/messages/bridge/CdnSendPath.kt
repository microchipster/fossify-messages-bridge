/*
 * SPDX-FileCopyrightText: 2026 microchipster
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.fossify.messages.bridge

import org.fossify.messages.extensions.isGifMimeType
import org.fossify.messages.extensions.isImageMimeType

/** One way an oversized attachment can leave the device. */
internal enum class CdnSendOption { ENCRYPTED_LINK, COMPRESS, MMS }

/**
 * The single decision the send path makes for one attachment.
 *
 * [NORMAL] and [MMS] keep the ordinary attach path, [ASK] opens the choice
 * dialog (see [CdnSendPath.options]), [LINK] uploads without asking,
 * [COMPRESS] downscales silently and [REJECT] refuses the attachment because
 * nothing can carry it.
 */
internal enum class CdnSendAction { NORMAL, MMS, ASK, LINK, COMPRESS, REJECT }

/**
 * The inputs the send path uses to decide how an attachment is handled. Pure
 * data, so the gating (no advertised `cdn_base_url`, the configured threshold
 * and send mode, the carrier and upload limits) is unit-testable.
 */
internal data class CdnSendInput(
    val fileSizeBytes: Long,
    val mmsLimitBytes: Long,
    val uploadLimitBytes: Long,
    val mimeType: String,
    val cdnAdvertised: Boolean,
    val settings: CdnSettings,
) {
    /** The attachment is larger than the configured MMS limit. */
    val overMmsLimit: Boolean
        get() = mmsLimitBytes > 0 && fileSizeBytes > mmsLimitBytes

    /** True for types a lean in-app compressor exists for (images except GIFs). */
    val compressible: Boolean
        get() = mimeType.isImageMimeType() && !mimeType.isGifMimeType()

    /** True while the configured carrier limit (the MMS limit) still carries it. */
    val withinCarrierLimit: Boolean
        get() = !overMmsLimit

    /** Accounting for the GCM overhead, the ciphertext still fits the app-side limit. */
    val fitsUploadLimit: Boolean
        get() = fileSizeBytes + CdnCrypto.OVERHEAD_BYTES <= uploadLimitBytes

    /** Bytes above which the attachment takes the configured mode; null means no threshold. */
    val thresholdBytes: Long?
        get() = settings.thresholdBytes(mmsLimitBytes)

    /** The attachment is above the threshold; Auto follows the carrier limit. */
    val overThreshold: Boolean
        get() = thresholdBytes?.let { fileSizeBytes > it } ?: false

    /** Every condition for the encrypted link itself: advertised and within the upload limit. */
    val linkAvailable: Boolean
        get() = cdnAdvertised && fitsUploadLimit

    /** Whether the configured mode offers the link for this attachment. */
    val linkOffered: Boolean
        get() = overThreshold && linkAvailable && settings.mode != CdnSendMode.NEVER
}

internal object CdnSendPath {
    /**
     * The action for [input]. Above the threshold the mode decides: link
     * uploads, ask opens the dialog, never falls through. Everything else uses
     * the pre-threshold path: compress images (asking first when configured),
     * send within the carrier limit, refuse what no compressor can shrink.
     */
    fun action(input: CdnSendInput): CdnSendAction {
        if (input.linkOffered) {
            return if (input.settings.mode == CdnSendMode.LINK) CdnSendAction.LINK else CdnSendAction.ASK
        }
        if (!input.overMmsLimit) {
            return if (input.overThreshold) CdnSendAction.MMS else CdnSendAction.NORMAL
        }
        if (input.compressible) {
            return if (input.settings.askCompress) CdnSendAction.ASK else CdnSendAction.COMPRESS
        }
        return CdnSendAction.REJECT
    }

    /** The options that apply to [input], in the order they are offered. */
    fun options(input: CdnSendInput): List<CdnSendOption> {
        val options = mutableListOf<CdnSendOption>()
        if (input.linkOffered) {
            options += CdnSendOption.ENCRYPTED_LINK
        }
        if (input.compressible) {
            options += CdnSendOption.COMPRESS
        }
        if (input.withinCarrierLimit) {
            options += CdnSendOption.MMS
        }
        return options
    }
}
