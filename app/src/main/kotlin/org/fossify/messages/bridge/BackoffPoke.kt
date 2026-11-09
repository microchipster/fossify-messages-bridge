/*
 * SPDX-FileCopyrightText: 2026 The Fossify Messages bridge contributors
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.fossify.messages.bridge

import android.util.Log
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private const val TAG = "BackoffPoke"
private const val MS_PER_SECOND = 1_000

/**
 * A one-shot wake signal that lets [PairingPoller] abort its backoff sleeps
 * when connectivity returns instead of waiting out the remaining pause.
 */
internal class BackoffPoke(
    private val initialMs: Long,
    private val maxMs: Long,
) {
    private val lock = ReentrantLock()
    private val condition = lock.newCondition()

    @Volatile
    private var requested = false

    /** Asks the next [sleep] to return immediately. */
    fun poke() {
        lock.withLock {
            requested = true
            condition.signalAll()
        }
    }

    /**
     * Sleeps for the current backoff unless a poke arrives (network change):
     * returns the next backoff, reset to the initial one after a poke.
     */
    fun sleepBackoff(backoff: Long): Long {
        val sleepMs = backoff.coerceAtMost(maxMs)
        Log.i(TAG, "retrying in ${sleepMs / MS_PER_SECOND}s")
        if (sleep(sleepMs)) {
            return initialMs
        }
        return (sleepMs * 2).coerceAtMost(maxMs)
    }

    /** Waits up to [ms]; true when a poke asked for an immediate retry. */
    fun sleep(ms: Long): Boolean {
        val deadline = System.currentTimeMillis() + ms
        lock.withLock {
            if (requested) {
                requested = false
                return true
            }
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) {
                return false
            }
            try {
                condition.await(remaining, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            if (requested) {
                requested = false
                return true
            }
        }
        return false
    }
}
