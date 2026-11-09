/*
 * SPDX-FileCopyrightText: 2026 The Fossify Messages bridge contributors
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.fossify.messages.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parsing of the advertised `cdn_base_url` and the caching decision: a
 * readable payload wins, a failed fetch or unreadable payload keeps the cache.
 */
class CdnDiscoveryTest {

    @Test
    fun readsTheAdvertisedBaseUrl() {
        val body = """{"protocol_version":1,"cdn_base_url":"https://cdn.example.com"}"""

        val advertisement = CdnDiscovery.readAdvertisement(body)

        assertTrue(advertisement.parsed)
        assertEquals("https://cdn.example.com", advertisement.baseUrl)
    }

    @Test
    fun trimsWhitespaceAroundTheAdvertisedValue() {
        val body = """{"cdn_base_url":"  https://cdn.example.com/  "}"""

        assertEquals("https://cdn.example.com/", CdnDiscovery.readAdvertisement(body).baseUrl)
    }

    @Test
    fun missingFieldIsParsedAsEmpty() {
        val advertisement = CdnDiscovery.readAdvertisement("""{"protocol_version":1,"pairing_enabled":true}""")

        assertTrue(advertisement.parsed)
        assertEquals("", advertisement.baseUrl)
    }

    @Test
    fun unreadablePayloadIsNotParsed() {
        assertFalse(CdnDiscovery.readAdvertisement("not json").parsed)
        assertFalse(CdnDiscovery.readAdvertisement("").parsed)
        assertFalse(CdnDiscovery.readAdvertisement("[1,2,3]").parsed)
    }

    @Test
    fun successfulFetchOverwritesTheCache() {
        val advertisement = CdnAdvertisement(parsed = true, baseUrl = "https://cdn.example.com")

        assertEquals(
            "https://cdn.example.com",
            CdnDiscovery.nextCachedBaseUrl("https://old.example.com", advertisement, fetchSucceeded = true),
        )
    }

    @Test
    fun anAbsentAdvertisementClearsTheCache() {
        val advertisement = CdnAdvertisement(parsed = true, baseUrl = "")

        assertEquals(
            "",
            CdnDiscovery.nextCachedBaseUrl("https://cdn.example.com", advertisement, fetchSucceeded = true),
        )
    }

    @Test
    fun aFailedFetchKeepsTheCachedValue() {
        val previous = "https://cdn.example.com"

        assertEquals(previous, CdnDiscovery.nextCachedBaseUrl(previous, null, fetchSucceeded = false))
    }

    @Test
    fun anUnreadablePayloadKeepsTheCachedValue() {
        val previous = "https://cdn.example.com"
        val broken = CdnAdvertisement(parsed = false, baseUrl = "")

        assertEquals(previous, CdnDiscovery.nextCachedBaseUrl(previous, broken, fetchSucceeded = true))
    }
}
