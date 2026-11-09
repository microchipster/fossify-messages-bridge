/*
 * SPDX-FileCopyrightText: 2026 The Fossify Messages bridge contributors
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.fossify.messages.bridge

import org.junit.Assert.assertEquals
import org.junit.Test

class QueueCursorTest {
    @Test
    fun normalResponseMovesTheCursorForward() {
        assertEquals(12L, nextQueueCursor(current = 5L, serverCursor = 12L, reset = false))
    }

    @Test
    fun duplicateResponseKeepsTheCursor() {
        assertEquals(5L, nextQueueCursor(current = 5L, serverCursor = 5L, reset = false))
    }

    @Test
    fun staleLowerResponseWithoutResetIsIgnored() {
        assertEquals(5L, nextQueueCursor(current = 5L, serverCursor = 2L, reset = false))
    }

    @Test
    fun staleLowerResponseWithResetAdoptsTheServerCursor() {
        assertEquals(5L, nextQueueCursor(current = 6L, serverCursor = 5L, reset = true))
    }

    @Test
    fun resetAfterABridgeRestartAdoptsAFreshQueue() {
        assertEquals(0L, nextQueueCursor(current = 6L, serverCursor = 0L, reset = true))
    }

    @Test
    fun resetWithTheSameCursorKeepsIt() {
        assertEquals(5L, nextQueueCursor(current = 5L, serverCursor = 5L, reset = true))
    }
}
