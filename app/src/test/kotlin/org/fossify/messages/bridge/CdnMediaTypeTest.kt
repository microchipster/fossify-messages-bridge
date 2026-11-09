/*
 * SPDX-FileCopyrightText: 2026 The Fossify Messages bridge contributors
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

@file:Suppress("MagicNumber")

package org.fossify.messages.bridge

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Magic-byte sniffing of decrypted payloads. The bridge serves an opaque
 * `application/octet-stream`, so the kind that decides between a thumbnail,
 * the audio player, a video poster and the attachment card must come from
 * the content itself.
 */
class CdnMediaTypeTest {

    @Test
    fun sniffsImages() {
        assertEquals(CdnMediaKind.PNG, CdnMediaType.sniff(bytes(0x89, 0x50, 0x4E, 0x47)))
        assertEquals(CdnMediaKind.JPEG, CdnMediaType.sniff(bytes(0xFF, 0xD8, 0xFF)))
        assertEquals(CdnMediaKind.GIF, CdnMediaType.sniff(ascii("GIF89a")))
        assertEquals(CdnMediaKind.WEBP, CdnMediaType.sniff(riff("WEBP")))
        assertEquals(CdnMediaKind.BMP, CdnMediaType.sniff(ascii("BM")))
        assertEquals(CdnMediaKind.HEIC, CdnMediaType.sniff(ftyp("heic")))
        assertEquals(CdnMediaKind.AVIF, CdnMediaType.sniff(ftyp("avif")))
    }

    @Test
    fun sniffsVideos() {
        assertEquals(CdnMediaKind.MP4, CdnMediaType.sniff(ftyp("isom")))
        assertEquals(CdnMediaKind.THREE_GP, CdnMediaType.sniff(ftyp("3gp4")))
        assertEquals(CdnMediaKind.WEBM, CdnMediaType.sniff(bytes(0x1A, 0x45, 0xDF, 0xA3)))
    }

    @Test
    fun sniffsAudio() {
        assertEquals(CdnMediaKind.M4A, CdnMediaType.sniff(ftyp("M4A ")))
        assertEquals(CdnMediaKind.MP3, CdnMediaType.sniff(ascii("ID3")))
        assertEquals(CdnMediaKind.MP3, CdnMediaType.sniff(bytes(0xFF, 0xFB)))
        assertEquals(CdnMediaKind.AAC, CdnMediaType.sniff(bytes(0xFF, 0xF1)))
        assertEquals(CdnMediaKind.OGG, CdnMediaType.sniff(ascii("OggS")))
        assertEquals(CdnMediaKind.FLAC, CdnMediaType.sniff(ascii("fLaC")))
        assertEquals(CdnMediaKind.WAV, CdnMediaType.sniff(riff("WAVE")))
    }

    @Test
    fun sniffsAttachments() {
        assertEquals(CdnMediaKind.PDF, CdnMediaType.sniff(ascii("%PDF-1.7")))
        assertEquals(CdnMediaKind.ZIP, CdnMediaType.sniff(bytes(0x50, 0x4B, 0x03, 0x04)))
        assertEquals(CdnMediaKind.VCARD, CdnMediaType.sniff(ascii("BEGIN:VCARD")))
    }

    @Test
    fun unknownAndShortHeadersFallBackToTheAttachmentCard() {
        assertEquals(CdnMediaKind.UNKNOWN, CdnMediaType.sniff(ByteArray(0)))
        assertEquals(CdnMediaKind.UNKNOWN, CdnMediaType.sniff(bytes(0x89, 0x50, 0x4E, 0x47, size = 4)))
        assertEquals(CdnMediaKind.UNKNOWN, CdnMediaType.sniff(ascii("plain text file")))
    }

    private fun bytes(vararg magic: Int, size: Int = CdnMediaType.HEADER_BYTES): ByteArray {
        val result = ByteArray(size)
        magic.forEachIndexed { index, value -> result[index] = value.toByte() }
        return result
    }

    private fun ascii(value: String): ByteArray {
        val result = ByteArray(CdnMediaType.HEADER_BYTES)
        value.toByteArray(Charsets.US_ASCII).copyInto(result)
        return result
    }

    private fun riff(kind: String): ByteArray {
        val result = ByteArray(CdnMediaType.HEADER_BYTES)
        "RIFF".toByteArray(Charsets.US_ASCII).copyInto(result)
        kind.toByteArray(Charsets.US_ASCII).copyInto(result, 8)
        return result
    }

    private fun ftyp(brand: String): ByteArray {
        val result = ByteArray(CdnMediaType.HEADER_BYTES)
        "ftyp".toByteArray(Charsets.US_ASCII).copyInto(result, 4)
        brand.toByteArray(Charsets.US_ASCII).copyInto(result, 8)
        return result
    }
}
