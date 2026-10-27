/*
 * SPDX-FileCopyrightText: 2026 microchipster
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.fossify.messages.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cache keys and the state machine of the inline renderer (the Android parts
 * of [CdnMediaRenderer] - fetching, decoding, the LruCache - are exercised on
 * device; the decisions here are pure).
 */
class CdnMediaRendererTest {
    private val id = "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG"

    @Test
    fun cacheFileNameIsTheUploadId() {
        assertEquals(id, CdnMediaCache.fileName(id))
    }

    @Test
    fun cacheFileNameRejectsAnythingButABridgeId() {
        assertNull(CdnMediaCache.fileName(""))
        assertNull(CdnMediaCache.fileName("short"))
        assertNull(CdnMediaCache.fileName(id.dropLast(2) + "ABCDEFGHIJKLMNOPQRSTUVWXYZ"))
        assertNull(CdnMediaCache.fileName(id.dropLast(1) + "/"))
        assertNull(CdnMediaCache.fileName(id.dropLast(1) + "."))
        assertNull(CdnMediaCache.fileName("../".repeat(8) + "abcdefghij"))
    }

    @Test
    fun firstAnswerOfAGenerationWins() {
        val machine = CdnRenderStateMachine()
        val generation = machine.rebind()

        assertEquals(CdnRenderStateMachine.State.LOADING, machine.state)
        assertTrue(machine.complete(generation, loaded = true))
        assertEquals(CdnRenderStateMachine.State.READY, machine.state)

        // A second answer of the same generation (a joined fetch, a late
        // decode) does not flip the card back.
        assertFalse(machine.complete(generation, loaded = false))
        assertEquals(CdnRenderStateMachine.State.READY, machine.state)
    }

    @Test
    fun aFailedFetchBecomesTheErrorState() {
        val machine = CdnRenderStateMachine()
        val generation = machine.rebind()

        assertTrue(machine.complete(generation, loaded = false))
        assertEquals(CdnRenderStateMachine.State.ERROR, machine.state)
    }

    @Test
    fun anAnswerForAPreviousMessageIsDropped() {
        val machine = CdnRenderStateMachine()
        val stale = machine.rebind()
        val current = machine.rebind()

        assertFalse(machine.complete(stale, loaded = true))
        assertEquals(CdnRenderStateMachine.State.LOADING, machine.state)
        assertTrue(machine.complete(current, loaded = true))
        assertEquals(CdnRenderStateMachine.State.READY, machine.state)
    }

    @Test
    fun rebindingStartsAFreshLoadingState() {
        val machine = CdnRenderStateMachine()
        val first = machine.rebind()
        assertTrue(machine.complete(first, loaded = true))

        val second = machine.rebind()

        assertTrue(second > first)
        assertEquals(CdnRenderStateMachine.State.LOADING, machine.state)
        assertFalse(machine.complete(first, loaded = true))
    }

    @Test
    fun onlyTheBoundGenerationIsCurrentForCellAnswers() {
        val machine = CdnRenderStateMachine()
        val first = machine.rebind()
        assertTrue(machine.isCurrent(first))

        val second = machine.rebind()

        assertFalse(machine.isCurrent(first))
        assertTrue(machine.isCurrent(second))
        // A ready card keeps its generation until the row is rebound.
        assertTrue(machine.complete(second, loaded = true))
        assertTrue(machine.isCurrent(second))
    }
}
