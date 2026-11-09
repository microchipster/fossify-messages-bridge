/*
 * SPDX-FileCopyrightText: 2026 The Fossify Messages bridge contributors
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.fossify.messages.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.messages.extensions.runHistoryCleanup

/**
 * Daily alarm of the history retention; armed by `scheduleHistoryCleanup()`.
 */
class HistoryCleanupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        ensureBackgroundThread {
            try {
                context.runHistoryCleanup()
            } finally {
                pendingResult.finish()
            }
        }
    }
}
