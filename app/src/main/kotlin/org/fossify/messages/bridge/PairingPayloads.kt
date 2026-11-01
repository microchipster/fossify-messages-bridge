/*
 * SPDX-FileCopyrightText: 2026 microchipster
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.fossify.messages.bridge

import android.net.Uri

/**
 * Parses the pairing payloads the app understands: the `smsmms://pair` links
 * shown by the bridge and the `https://<homeserver>/_smsmms/pair` App Link
 * variant. Query parameters: `hs` (homeserver), `b` (bridge base, optional)
 * and `code` (the one-time pairing code).
 */
object PairingPayloads {
    private const val APP_SCHEME = "smsmms"
    private const val APP_HOST = "pair"
    private const val WEB_PATH = "/_smsmms/pair"

    data class Payload(val homeserverUrl: String, val bridgeUrl: String, val code: String)

    fun parse(raw: String): Payload? {
        val uri = Uri.parse(raw.trim())
        val isAppLink = uri.scheme.equals(APP_SCHEME, ignoreCase = true) &&
            uri.host.equals(APP_HOST, ignoreCase = true)
        val isWebLink = uri.scheme.equals("https", ignoreCase = true) &&
            uri.path.orEmpty().trimEnd('/').equals(WEB_PATH, ignoreCase = true)
        if (!isAppLink && !isWebLink) {
            return null
        }

        val homeserverUrl = uri.getQueryParameter("hs").orEmpty().trim()
        val bridgeUrl = uri.getQueryParameter("b").orEmpty().trim()
        val code = uri.getQueryParameter("code").orEmpty().trim()
        if (homeserverUrl.isEmpty() || code.isEmpty()) {
            return null
        }
        return Payload(homeserverUrl = homeserverUrl, bridgeUrl = bridgeUrl, code = code)
    }
}
