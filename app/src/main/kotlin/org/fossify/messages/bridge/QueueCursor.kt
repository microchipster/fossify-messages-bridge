/*
 * SPDX-FileCopyrightText: 2026 The Fossify Messages bridge contributors
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.fossify.messages.bridge

/**
 * Cursor update rule for `GET /_smsmms/v1/queue` responses.
 *
 * A normal response may arrive stale (control `SYNC` polls in parallel with
 * the long-poll loop), so the stored cursor only ever moves forward.
 *
 * A reset means the bridge dropped its command queue (usually a restart) and
 * reports a fresh cursor, which may be lower than the stored one. That cursor
 * is authoritative: keeping the old one would make the poller ask for a cursor
 * the bridge no longer has, receive the same reset forever and never drain a
 * command.
 */
internal fun nextQueueCursor(current: Long, serverCursor: Long, reset: Boolean): Long =
    if (reset) serverCursor else maxOf(current, serverCursor)
