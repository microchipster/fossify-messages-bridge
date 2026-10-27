/*
 * SPDX-FileCopyrightText: 2026 microchipster
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.fossify.messages.bridge

/**
 * App-side configuration of the bridge-hosted file service.
 *
 * Kept free of Android APIs so the defaults, clamping and the send path that
 * consumes them are unit-testable. Defaults are conservative: the link path
 * stays off until the user enables it *and* the bridge advertises a
 * `cdn_base_url`, and the app asks before downscaling an oversized attachment.
 */
internal data class CdnSettings(
    val allowLinks: Boolean = DEFAULT_ALLOW_LINKS,
    val askCompress: Boolean = DEFAULT_ASK_COMPRESS,
    val uploadLimitMb: Int = DEFAULT_UPLOAD_LIMIT_MB,
    val expiryDays: Int = CdnExpiry.DEFAULT_DAYS,
    val askExpiry: Boolean = DEFAULT_ASK_EXPIRY,
) {
    /** The upload limit in bytes; ciphertext adds [CdnCrypto.OVERHEAD_BYTES]. */
    val uploadLimitBytes: Long
        get() = uploadLimitMb.toLong() * BYTES_PER_MB

    companion object {
        const val DEFAULT_ALLOW_LINKS = false
        const val DEFAULT_ASK_COMPRESS = true
        const val DEFAULT_UPLOAD_LIMIT_MB = 50
        const val DEFAULT_ASK_EXPIRY = false
        const val MIN_UPLOAD_LIMIT_MB = 1
        const val MAX_UPLOAD_LIMIT_MB = 100
        const val BYTES_PER_MB = 1024L * 1024L

        /** Upload limits offered in the settings and in the send path. */
        val UPLOAD_LIMIT_CHOICES = listOf(10, 25, 50, 100)

        val DEFAULTS = CdnSettings()

        /** Clamps a stored or hand-edited limit into the supported range. */
        fun coerceUploadLimitMb(value: Int): Int = value.coerceIn(MIN_UPLOAD_LIMIT_MB, MAX_UPLOAD_LIMIT_MB)
    }

    fun coerce(): CdnSettings = copy(
        uploadLimitMb = coerceUploadLimitMb(uploadLimitMb),
        expiryDays = CdnExpiry.coerceDays(expiryDays),
    )
}

/**
 * Expiry helpers for URL uploads. The bridge accepts duration strings such as
 * `12h` or `30d` and hard-rejects anything above 365 days.
 */
internal object CdnExpiry {
    const val MIN_DAYS = 1
    const val MAX_DAYS = 365
    const val DEFAULT_DAYS = 30

    /** Expiry choices offered in the settings and in the per-upload dialog. */
    @Suppress("MagicNumber")
    val DAY_CHOICES = listOf(1, 7, 30, 90, 365)

    /** Formats a day count as the `?expires=` duration the bridge speaks. */
    fun durationSpec(days: Int): String = "${coerceDays(days)}d"

    /** Clamps a configured value into the range the bridge accepts. */
    fun coerceDays(days: Int): Int = days.coerceIn(MIN_DAYS, MAX_DAYS)

    /** True when [days] is inside the bridge's 1..365 day window. */
    fun isValidDays(days: Int): Boolean = days in MIN_DAYS..MAX_DAYS
}
