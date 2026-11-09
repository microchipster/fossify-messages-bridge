/*
 * SPDX-FileCopyrightText: 2026 The Fossify Messages bridge contributors
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.fossify.messages.bridge

import android.content.Context

/**
 * On-device audit trail of pairing attempts (timestamp, target homeserver,
 * outcome). Never stores codes, tokens or other secrets. Also provides the
 * local attempt rate limit, which exists only to block obvious brute forcing.
 */
object PairingAuditLog {
    private const val PREFS = "pairing_audit"
    private const val KEY_ENTRIES = "entries"
    private const val MAX_ENTRIES = 64

    /** High cap: a human never hits it, a brute-force script does. */
    const val MAX_ATTEMPTS_PER_WINDOW = 30
    private const val ATTEMPT_WINDOW_MS = 10 * 60 * 1000L

    fun record(context: Context, homeserverUrl: String, outcome: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val entries = prefs.getString(KEY_ENTRIES, "").orEmpty()
            .split('\n')
            .filter { it.isNotBlank() }
            .takeLast(MAX_ENTRIES - 1)
        val line = "${System.currentTimeMillis()}|$outcome|${homeserverUrl.replace('|', ' ')}"
        prefs.edit().putString(KEY_ENTRIES, (entries + line).joinToString("\n")).apply()
    }

    fun recentAttemptCount(context: Context): Int {
        val cutoff = System.currentTimeMillis() - ATTEMPT_WINDOW_MS
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ENTRIES, "").orEmpty()
            .split('\n')
            .count { line ->
                val timestamp = line.substringBefore('|').toLongOrNull()
                timestamp != null && timestamp >= cutoff
            }
    }
}
