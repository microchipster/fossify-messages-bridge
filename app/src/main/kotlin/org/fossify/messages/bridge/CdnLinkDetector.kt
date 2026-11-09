/*
 * SPDX-FileCopyrightText: 2026 The Fossify Messages bridge contributors
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.fossify.messages.bridge

/**
 * One file-service link found in a message body.
 *
 * [url] is the link exactly as it appears in the text (without trailing
 * punctuation), [range] is its position inside the body.
 *
 * Albums share this shape and the id space of single files: only the fetch
 * path tells them apart (`/raw` answers 404 for an album id, `/manifest` for a
 * file id), so a [CdnLinkRef] is exactly what the album renderer consumes too.
 */
internal data class CdnLinkRef(
    val baseUrl: String,
    val id: String,
    val keyFragment: String,
    val url: String,
    val range: IntRange,
)

/**
 * Finds bridge file-service links in message text.
 *
 * A link is `<base>/f/<id>#<key>`: the key must be present and decode to the
 * 32 bytes [CdnCrypto] expects, and the id must be shaped like a bridge id
 * (22..64 unpadded base64url characters), so a foreign URL, a truncated link
 * or a plain `#anchor` is never rendered. Album links (one id, one fragment
 * key, several encrypted items) are indistinguishable here and are detected
 * by the same rules. Only the base URLs passed in are considered; the app
 * never guesses a hostname of its own.
 *
 * Everything here is free of Android APIs, so the detection matrix is a JVM
 * test ([CdnLinkDetectorTest]).
 */
internal object CdnLinkDetector {
    /** Id length bounds the bridge enforces (`cdnMinIDLen` / `cdnMaxIDLen`). */
    const val MIN_ID_LENGTH = 22
    const val MAX_ID_LENGTH = 64

    /**
     * The base URLs to look for, in order: the advertised one first, then an
     * optional user-configured one. Roadmap item B wires that setting into
     * the second parameter; detection already supports it.
     */
    fun baseUrls(advertised: String, custom: String = ""): List<String> {
        return listOf(advertised, custom)
            .map { it.trim().trimEnd('/') }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    /** The first file-service link in [text], or null. */
    fun detect(text: String, baseUrls: List<String>): CdnLinkRef? {
        return detectAll(text, baseUrls).firstOrNull()
    }

    /** All file-service links in [text], ordered by position. */
    fun detectAll(text: String, baseUrls: List<String>): List<CdnLinkRef> {
        if (text.isEmpty() || baseUrls.isEmpty()) {
            return emptyList()
        }

        val refs = mutableListOf<CdnLinkRef>()
        for (baseUrl in baseUrls) {
            val prefix = "${baseUrl.trimEnd('/')}${CdnLink.PATH_PREFIX}/"
            var from = text.indexOf(prefix)
            while (from >= 0) {
                parseAt(text, from, prefix, baseUrl)?.let { refs += it }
                from = text.indexOf(prefix, from + 1)
            }
        }
        return refs.distinctBy { it.range }.sortedBy { it.range.first }
    }

    /**
     * The bubble text for a message whose link goes into a media card: the
     * link and the separator of the app label (`… (encrypted link): <url>`)
     * are dropped, and an empty body hides the bubble.
     */
    fun displayBody(text: String, ref: CdnLinkRef): String {
        val before = text.substring(0, ref.range.first).trimEnd()
        val after = text.substring(ref.range.last + 1).trimStart()
        val body = when {
            before.isEmpty() -> after
            after.isEmpty() -> before
            else -> "$before $after"
        }
        return body.trimEnd(':').trim()
    }

    /** A bridge-generated id: 22..64 unpadded base64url characters. */
    fun isValidId(id: String): Boolean {
        if (id.length < MIN_ID_LENGTH || id.length > MAX_ID_LENGTH) {
            return false
        }
        return id.all(::isTokenChar)
    }

    private fun parseAt(text: String, start: Int, prefix: String, baseUrl: String): CdnLinkRef? {
        val idStart = start + prefix.length
        val idEnd = idStart + scanToken(text, idStart)
        if (idEnd == idStart || idEnd >= text.length || text[idEnd] != '#') {
            return null
        }

        val keyStart = idEnd + 1
        val keyEnd = keyStart + scanToken(text, keyStart)
        if (keyEnd == keyStart) {
            return null
        }

        val id = text.substring(idStart, idEnd)
        val key = text.substring(keyStart, keyEnd)
        if (!isValidId(id) || CdnCrypto.fragmentToKey(key) == null) {
            return null
        }
        return CdnLinkRef(
            baseUrl = baseUrl.trimEnd('/'),
            id = id,
            keyFragment = key,
            url = text.substring(start, keyEnd),
            range = start until keyEnd,
        )
    }

    /** Length of the leading run of base64url characters, so `id#key.` stops before the dot. */
    private fun scanToken(text: String, start: Int): Int {
        var end = start
        while (end < text.length && isTokenChar(text[end])) {
            end++
        }
        return end - start
    }

    private fun isTokenChar(char: Char): Boolean {
        return when (char) {
            in 'a'..'z', in 'A'..'Z', in '0'..'9', '-', '_' -> true
            else -> false
        }
    }
}
