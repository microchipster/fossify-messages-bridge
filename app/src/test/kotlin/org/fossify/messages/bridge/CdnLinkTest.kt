/*
 * SPDX-FileCopyrightText: 2026 The Fossify Messages bridge contributors
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.fossify.messages.bridge

import org.junit.Assert.assertEquals
import org.junit.Test

/** URL building for the shared link; the key only ever lives after `#`. */
class CdnLinkTest {

    @Test
    fun buildsTheShareLinkWithTheKeyFragment() {
        assertEquals(
            "https://cdn.example.com/f/abc123#KEY",
            CdnLink.build("https://cdn.example.com", "abc123", "KEY"),
        )
    }

    @Test
    fun trimsTrailingSlashesOfTheAdvertisedBase() {
        assertEquals(
            "https://cdn.example.com/f/abc123#KEY",
            CdnLink.build("https://cdn.example.com/", "abc123", "KEY"),
        )
        assertEquals(
            "https://cdn.example.com/f/abc123#KEY",
            CdnLink.build("  https://cdn.example.com//  ", "abc123", "KEY"),
        )
    }

    @Test
    fun linkForAnUploadCarriesTheFragmentKey() {
        val upload = CdnUpload(id = "file-1", size = 42, expiresAtMs = 1234, keyFragment = "KEY")

        assertEquals("https://cdn.example.com/f/file-1#KEY", upload.link("https://cdn.example.com"))
    }
}
