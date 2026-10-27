/*
 * SPDX-FileCopyrightText: 2026 microchipster
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

@file:Suppress("MagicNumber")

package org.fossify.messages.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Detection of file-service links in message text (roadmap item F): only the
 * configured base URLs count, the fragment key must decode to the 32 bytes
 * the crypto expects, the id must look like a bridge id, and the trailing
 * punctuation of a sentence never becomes part of the link.
 */
class CdnLinkDetectorTest {
    private val baseUrl = "https://cdn.example.com"
    private val id = "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG"
    private val key = CdnCrypto.keyToFragment(ByteArray(CdnCrypto.KEY_BYTES) { (it + 1).toByte() })
    private val link = "$baseUrl/f/$id#$key"
    private val bases = listOf(baseUrl)

    @Test
    fun detectsTheAdvertisedLink() {
        val ref = CdnLinkDetector.detect("sent $link", bases)

        assertEquals(baseUrl, ref?.baseUrl)
        assertEquals(id, ref?.id)
        assertEquals(key, ref?.keyFragment)
        assertEquals(link, ref?.url)
        assertEquals(5 until link.length + 5, ref?.range)
    }

    @Test
    fun stripsTrailingPunctuation() {
        assertEquals(link, CdnLinkDetector.detect("look $link.", bases)?.url)
        assertEquals(link, CdnLinkDetector.detect("look $link, and more", bases)?.url)
        assertEquals(link, CdnLinkDetector.detect("($link)", bases)?.url)
        assertEquals(link, CdnLinkDetector.detect("$link!", bases)?.url)
    }

    @Test
    fun requiresTheFragmentKey() {
        assertNull(CdnLinkDetector.detect("$baseUrl/f/$id", bases))
        assertNull(CdnLinkDetector.detect("$baseUrl/f/$id#", bases))
        assertNull(CdnLinkDetector.detect("$baseUrl/f/$id#short-key", bases))
    }

    @Test
    fun ignoresForeignBaseUrls() {
        assertNull(CdnLinkDetector.detect("https://files.example.com/f/$id#$key", bases))
        assertNull(CdnLinkDetector.detect("http://cdn.example.com/f/$id#$key", bases))
        assertNull(CdnLinkDetector.detect(link, emptyList()))
    }

    @Test
    fun ignoresWrongPaths() {
        assertNull(CdnLinkDetector.detect("$baseUrl/f/$id/raw#$key", bases))
        assertNull(CdnLinkDetector.detect("$baseUrl/raw/$id#$key", bases))
    }

    @Test
    fun ignoresMalformedIds() {
        assertNull(CdnLinkDetector.detect("$baseUrl/f/${id.take(21)}#$key", bases))
        assertNull(CdnLinkDetector.detect("$baseUrl/f/${id}ABCDEFGHIJKLMNOPQRSTUVWXYZ#$key", bases))
        assertNull(CdnLinkDetector.detect("https://cdn.example.com/f/ab.cdefghijklmnopqrstuv#$key", bases))
    }

    @Test
    fun detectsLinksOfACustomBaseUrl() {
        val customBase = "https://files.example.com"
        val customLink = "$customBase/f/$id#$key"
        val urls = CdnLinkDetector.baseUrls(baseUrl, customBase)

        assertEquals(listOf(baseUrl, customBase), urls)
        assertEquals(customBase, CdnLinkDetector.detect("look $customLink", urls)?.baseUrl)
    }

    @Test
    fun baseUrlsTrimsDropsAndDeduplicates() {
        assertEquals(listOf(baseUrl), CdnLinkDetector.baseUrls(" $baseUrl/ ", baseUrl))
        assertEquals(emptyList<String>(), CdnLinkDetector.baseUrls("", "  "))
        assertEquals(listOf(baseUrl), CdnLinkDetector.baseUrls(baseUrl))
    }

    @Test
    fun detectsTheFirstOfSeveralLinks() {
        val otherId = id.dropLast(1) + "H"
        val otherLink = "$baseUrl/f/$otherId#$key"

        val refs = CdnLinkDetector.detectAll("$link then $otherLink", bases)

        assertEquals(2, refs.size)
        assertEquals(id, refs[0].id)
        assertEquals(otherId, refs[1].id)
        assertEquals(id, CdnLinkDetector.detect("$link then $otherLink", bases)?.id)
    }

    @Test
    fun displayBodyDropsTheLinkAndTheLabelSeparator() {
        val ref = CdnLinkDetector.detect("clip.mp4 (encrypted link): $link", bases)!!

        assertEquals("clip.mp4 (encrypted link)", CdnLinkDetector.displayBody("clip.mp4 (encrypted link): $link", ref))
    }

    @Test
    fun displayBodyKeepsSurroundingText() {
        val middleRef = CdnLinkDetector.detect("hi $link bye", bases)!!
        assertEquals("hi bye", CdnLinkDetector.displayBody("hi $link bye", middleRef))

        val onlyRef = CdnLinkDetector.detect(link, bases)!!
        assertEquals("", CdnLinkDetector.displayBody(link, onlyRef))

        val dashesRef = CdnLinkDetector.detect("- $link -", bases)!!
        assertTrue(CdnLinkDetector.displayBody("- $link -", dashesRef).isNotEmpty())
    }
}
