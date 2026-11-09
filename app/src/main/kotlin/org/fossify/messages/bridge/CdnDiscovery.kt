/*
 * SPDX-FileCopyrightText: 2026 The Fossify Messages bridge contributors
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.fossify.messages.bridge

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * One reading of a bridge discovery payload.
 *
 * [parsed] tells an intentional "no file service advertised" (field missing)
 * apart from an unreadable response, so a broken payload never drops a
 * previously working base URL.
 */
internal data class CdnAdvertisement(val parsed: Boolean, val baseUrl: String)

/**
 * Discovery of the bridge-hosted file service.
 *
 * The bridge advertises the public base URL of its file service as
 * `cdn_base_url` in `/info` and in the pairing responses. The app only ever
 * uses that advertised value; it never derives a hostname of its own.
 */
internal object CdnDiscovery {
    private const val TAG = "CdnDiscovery"
    private val json = Json { ignoreUnknownKeys = true }

    /** Reads `cdn_base_url` from a bridge JSON payload. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    fun readAdvertisement(body: String): CdnAdvertisement {
        val root = try {
            json.parseToJsonElement(body) as? JsonObject
        } catch (expected: Exception) {
            null
        } ?: return CdnAdvertisement(parsed = false, baseUrl = "")

        val baseUrl = (root["cdn_base_url"] as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
        return CdnAdvertisement(parsed = true, baseUrl = baseUrl)
    }

    /**
     * Decides what to cache after a discovery fetch.
     *
     * A readable payload always wins: an absent `cdn_base_url` means the
     * operator turned the service off, so the cache is cleared. A failed
     * fetch or an unreadable payload keeps the previous value, so a transient
     * network error does not drop a working link path.
     */
    fun nextCachedBaseUrl(previous: String, advertisement: CdnAdvertisement?, fetchSucceeded: Boolean): String {
        if (!fetchSucceeded || advertisement == null || !advertisement.parsed) {
            return previous.trim()
        }
        return advertisement.baseUrl
    }

    /** The advertised base URL, cached from pairing or the last `/info` refresh. */
    fun cachedBaseUrl(context: Context): String = PairingStore.newInstance(context).cdnBaseUrl

    /**
     * Re-reads `cdn_base_url` from the bridge `/info` endpoint and caches the
     * advertised value. Blocking; failures keep the cached value.
     */
    @Suppress("TooGenericExceptionCaught")
    fun refresh(context: Context): String {
        val store = PairingStore.newInstance(context)
        val cached = store.cdnBaseUrl
        if (!store.isPaired || store.bridgeBaseUrl.isEmpty()) {
            return cached
        }

        val body = try {
            PairingHttp.requestJson(PairingHttp.endpoint(store.bridgeBaseUrl, "/_smsmms/v1/info"), "GET")
        } catch (e: Exception) {
            Log.w(TAG, "file service discovery refresh failed", e)
            return cached
        }
        val advertisement = readAdvertisement(body)
        val next = nextCachedBaseUrl(cached, advertisement, fetchSucceeded = true)
        if (next != cached) {
            store.cdnBaseUrl = next
        }
        return next
    }
}

/**
 * Builds the share link of an uploaded file.
 *
 * The unguessable id lives in the path, the AES key lives only in the
 * fragment; a fragment is never sent to the server, so the bridge cannot read
 * the key and browser clients can decrypt locally.
 */
internal object CdnLink {
    const val PATH_PREFIX = "/f"

    fun build(cdnBaseUrl: String, id: String, keyFragment: String): String {
        val base = cdnBaseUrl.trim().trimEnd('/')
        return "$base$PATH_PREFIX/$id#$keyFragment"
    }
}
