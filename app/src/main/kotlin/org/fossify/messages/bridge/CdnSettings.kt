/*
 * SPDX-FileCopyrightText: 2026 microchipster
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.fossify.messages.bridge

/**
 * What happens to attachments above the configured size threshold.
 *
 * [ASK] shows the choice dialog, [LINK] uploads without asking and [NEVER]
 * keeps the CDN out of the send path entirely. The link path stays inert in
 * every mode until the bridge advertises a `cdn_base_url`.
 */
internal enum class CdnSendMode(val preferenceValue: String) {
    ASK("ask"),
    LINK("link"),
    NEVER("never");

    companion object {
        /** Default mode: ask how over-threshold attachments should be sent. */
        val DEFAULT = ASK

        /** Parses a stored preference; unknown or missing values fall back to [DEFAULT]. */
        fun fromPreference(value: String?): CdnSendMode = entries.firstOrNull { it.preferenceValue == value } ?: DEFAULT

        /**
         * Maps the legacy `cdn_allow_links` boolean onto a mode. Checked kept
         * the ask dialog that offered the link, unchecked never offered one.
         */
        fun fromLegacyAllowLinks(allowLinks: Boolean): CdnSendMode = if (allowLinks) ASK else NEVER
    }
}

/**
 * Pickers and resolution of the "send files larger than" threshold.
 *
 * [AUTO_MB] follows the carrier MMS limit, which is the value the send path
 * used before the threshold existed. A fixed value below the carrier limit can
 * push smaller files to the link path, and 0 sends any attachment through it.
 */
internal object CdnThreshold {
    const val AUTO_MB = -1
    const val MIN_MB = 0
    const val MAX_MB = 100

    /** Sentinel of the hand-typed entry in the settings picker; never stored. */
    const val CUSTOM_MB = -2

    /** Presets offered in the settings before the custom size entry. */
    @Suppress("MagicNumber")
    val CHOICES_MB = listOf(1, 5, 10)

    /** Clamps a stored value; anything negative means "follow the carrier limit". */
    fun coerceMb(value: Int): Int = if (value < 0) AUTO_MB else value.coerceIn(MIN_MB, MAX_MB)

    /**
     * The threshold in bytes. [AUTO_MB] follows [carrierLimitBytes]; a disabled
     * carrier limit means there is no threshold at all.
     */
    fun bytes(thresholdMb: Int, carrierLimitBytes: Long): Long? = when {
        thresholdMb == AUTO_MB -> carrierLimitBytes.takeIf { it > 0 }
        else -> coerceMb(thresholdMb).toLong() * CdnSettings.BYTES_PER_MB
    }
}

/**
 * App-side configuration of the bridge-hosted file service.
 *
 * Kept free of Android APIs so the defaults, clamping, migration and the send
 * path that consumes them are unit-testable. Defaults keep the link path off
 * until the bridge advertises a `cdn_base_url`, consider only files above the
 * threshold (Auto = the carrier MMS limit) and ask before anything is
 * uploaded. The upload limit defaults to the largest offered size, still well
 * below the bridge's own 512 MiB `max_file_bytes`.
 */
internal data class CdnSettings(
    val mode: CdnSendMode = CdnSendMode.DEFAULT,
    val thresholdMb: Int = CdnThreshold.AUTO_MB,
    val askCompress: Boolean = DEFAULT_ASK_COMPRESS,
    val uploadLimitMb: Int = DEFAULT_UPLOAD_LIMIT_MB,
    val expiryDays: Int = CdnExpiry.DEFAULT_DAYS,
    val askExpiry: Boolean = DEFAULT_ASK_EXPIRY,
) {
    /** The upload limit in bytes; ciphertext adds [CdnCrypto.OVERHEAD_BYTES]. */
    val uploadLimitBytes: Long
        get() = uploadLimitMb.toLong() * BYTES_PER_MB

    /** Files above this many bytes take the [mode] path; null means no threshold. */
    fun thresholdBytes(carrierLimitBytes: Long): Long? = CdnThreshold.bytes(thresholdMb, carrierLimitBytes)

    fun coerce(): CdnSettings = copy(
        thresholdMb = CdnThreshold.coerceMb(thresholdMb),
        uploadLimitMb = coerceUploadLimitMb(uploadLimitMb),
        expiryDays = CdnExpiry.coerceDays(expiryDays),
    )

    companion object {
        const val DEFAULT_ASK_COMPRESS = true
        const val DEFAULT_UPLOAD_LIMIT_MB = 250
        const val DEFAULT_ASK_EXPIRY = false
        const val MIN_UPLOAD_LIMIT_MB = 1
        const val MAX_UPLOAD_LIMIT_MB = 250
        const val BYTES_PER_MB = 1024L * 1024L

        /** Upload limits offered in the settings and in the send path. */
        val UPLOAD_LIMIT_CHOICES = listOf(10, 25, 50, 100, DEFAULT_UPLOAD_LIMIT_MB)

        val DEFAULTS = CdnSettings()

        /** Clamps a stored or hand-edited limit into the supported range. */
        fun coerceUploadLimitMb(value: Int): Int = value.coerceIn(MIN_UPLOAD_LIMIT_MB, MAX_UPLOAD_LIMIT_MB)

        /**
         * Resolves the send mode from the stored preferences, preferring the
         * new mode key ([CdnSendMode.preferenceValue] in `storedMode`) and
         * falling back to the legacy `cdn_allow_links` boolean.
         */
        fun resolveMode(storedMode: String?, legacyAllowLinks: Boolean?): CdnSendMode {
            if (storedMode != null) {
                return CdnSendMode.fromPreference(storedMode)
            }
            return legacyAllowLinks?.let(CdnSendMode::fromLegacyAllowLinks) ?: CdnSendMode.DEFAULT
        }
    }
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
