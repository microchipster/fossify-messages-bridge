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
 * The inputs the send path uses to decide how an oversized attachment is
 * handled. Pure data, so the gating (links disabled, no advertised
 * `cdn_base_url`, above the app-side upload limit) is unit-testable.
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

    /** Every condition for the encrypted-link option: allowed and inert otherwise. */
    val linkAvailable: Boolean
        get() = settings.allowLinks && cdnAdvertised && fitsUploadLimit
}

internal object CdnSendPath {
    /** True when the attachment is large enough to ask how it should be sent. */
    fun needsChoice(input: CdnSendInput): Boolean {
        return input.overMmsLimit &&
            (input.linkAvailable || (input.settings.askCompress && input.compressible))
    }

    /** The options that apply to [input], in the order they are offered. */
    fun options(input: CdnSendInput): List<CdnSendOption> {
        val options = mutableListOf<CdnSendOption>()
        if (input.linkAvailable) {
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
