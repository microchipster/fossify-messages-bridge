/*
 * SPDX-FileCopyrightText: 2026 The Fossify Messages bridge contributors
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.fossify.messages.bridge

/**
 * A payload type the bridge file service can carry.
 *
 * The service stores ciphertext only and never the original name or type, so
 * the app sniffs the magic bytes of the decrypted file, like the wrapper page
 * does. Everything that is not an image, audio, video or PDF gets the
 * attachment card.
 */
internal enum class CdnMediaKind(val mimeType: String) {
    JPEG("image/jpeg"),
    PNG("image/png"),
    GIF("image/gif"),
    WEBP("image/webp"),
    BMP("image/bmp"),
    HEIC("image/heic"),
    AVIF("image/avif"),
    MP4("video/mp4"),
    WEBM("video/webm"),
    THREE_GP("video/3gpp"),
    M4A("audio/mp4"),
    MP3("audio/mpeg"),
    AAC("audio/aac"),
    OGG("audio/ogg"),
    FLAC("audio/flac"),
    WAV("audio/wav"),
    PDF("application/pdf"),
    ZIP("application/zip"),
    VCARD("text/vcard"),
    UNKNOWN("application/octet-stream");

    val isImage: Boolean get() = mimeType.startsWith("image/")
    val isVideo: Boolean get() = mimeType.startsWith("video/")
    val isAudio: Boolean get() = mimeType.startsWith("audio/")
}

/**
 * Magic-byte sniffing of decrypted payloads.
 *
 * The bridge serves an opaque `application/octet-stream`, so the type that
 * decides how a link is rendered comes from the content itself. Pure byte
 * inspection, covered by a JVM test ([CdnMediaTypeTest]).
 */
internal object CdnMediaType {
    /** Enough content for every check below (an ISO brand ends at byte 12). */
    const val HEADER_BYTES = 64

    private const val MIN_HEADER_BYTES = 12
    private const val FTYP_OFFSET = 4
    private const val RIFF_KIND_OFFSET = 8
    private const val BRAND_OFFSET = 8
    private const val BRAND_LENGTH = 4
    private const val BYTE_MASK = 0xFF
    private const val ADTS_LAYER_MASK = 0xF6
    private const val ADTS_SYNC = 0xF0
    private const val MPEG_SYNC_MASK = 0xE0
    private const val MPEG_SYNC = 0xE0

    private val HEIF_BRANDS = setOf("heic", "heix", "hevc", "hevx", "mif1", "msf1")
    private val AVIF_BRANDS = setOf("avif", "avis")
    private val THREE_GP_BRANDS = setOf("3gp4", "3gp5", "3g2a", "3g2b")
    private val AUDIO_MP4_BRANDS = setOf("M4A ", "M4B ", "M4P ", "mp4a")

    /** The kind of the payload starting with [header], or [CdnMediaKind.UNKNOWN]. */
    @Suppress("MagicNumber")
    fun sniff(header: ByteArray): CdnMediaKind {
        if (header.size < MIN_HEADER_BYTES) {
            return CdnMediaKind.UNKNOWN
        }
        return when {
            header.matches(0x89, 0x50, 0x4E, 0x47) -> CdnMediaKind.PNG
            header.matches(0xFF, 0xD8, 0xFF) -> CdnMediaKind.JPEG
            header.matchesAscii(0, "GIF8") -> CdnMediaKind.GIF
            header.matchesAscii(0, "BM") -> CdnMediaKind.BMP
            header.matches(0x1A, 0x45, 0xDF, 0xA3) -> CdnMediaKind.WEBM
            header.matchesAscii(0, "OggS") -> CdnMediaKind.OGG
            header.matchesAscii(0, "fLaC") -> CdnMediaKind.FLAC
            header.matchesAscii(0, "ID3") -> CdnMediaKind.MP3
            else -> sniffContainer(header) ?: sniffDocument(header) ?: kindOfAudioFrame(header)
        }
    }

    /** RIFF payloads (WebP, WAV) and ISO base media files (MP4 family, HEIF, AVIF). */
    private fun sniffContainer(header: ByteArray): CdnMediaKind? {
        return when {
            header.matchesAscii(0, "RIFF") && header.matchesAscii(RIFF_KIND_OFFSET, "WEBP") -> CdnMediaKind.WEBP
            header.matchesAscii(0, "RIFF") && header.matchesAscii(RIFF_KIND_OFFSET, "WAVE") -> CdnMediaKind.WAV
            header.matchesAscii(FTYP_OFFSET, "ftyp") -> kindOfIsoBrand(header)
            else -> null
        }
    }

    private fun sniffDocument(header: ByteArray): CdnMediaKind? {
        return when {
            header.matchesAscii(0, "%PDF-") -> CdnMediaKind.PDF
            header.matchesAscii(0, "PK") -> CdnMediaKind.ZIP
            header.matchesAscii(0, "BEGIN:VCARD") -> CdnMediaKind.VCARD
            else -> null
        }
    }

    private fun kindOfIsoBrand(header: ByteArray): CdnMediaKind {
        val brand = header.asciiAt(BRAND_OFFSET, BRAND_LENGTH)
        return when (brand) {
            in HEIF_BRANDS -> CdnMediaKind.HEIC
            in AVIF_BRANDS -> CdnMediaKind.AVIF
            in THREE_GP_BRANDS -> CdnMediaKind.THREE_GP
            in AUDIO_MP4_BRANDS -> CdnMediaKind.M4A
            else -> CdnMediaKind.MP4
        }
    }

    private fun kindOfAudioFrame(header: ByteArray): CdnMediaKind {
        val first = header[0].toInt() and BYTE_MASK
        val second = header[1].toInt() and BYTE_MASK
        if (first != BYTE_MASK) {
            return CdnMediaKind.UNKNOWN
        }
        return when {
            (second and ADTS_LAYER_MASK) == ADTS_SYNC -> CdnMediaKind.AAC
            (second and MPEG_SYNC_MASK) == MPEG_SYNC -> CdnMediaKind.MP3
            else -> CdnMediaKind.UNKNOWN
        }
    }

    private fun ByteArray.matches(vararg magic: Int): Boolean {
        if (size < magic.size) {
            return false
        }
        return magic.indices.all { (this[it].toInt() and BYTE_MASK) == magic[it] }
    }

    private fun ByteArray.matchesAscii(offset: Int, value: String): Boolean {
        return asciiAt(offset, value.length) == value
    }

    private fun ByteArray.asciiAt(offset: Int, length: Int): String {
        if (offset < 0 || offset + length > size) {
            return ""
        }
        return String(this, offset, length, Charsets.US_ASCII)
    }
}
