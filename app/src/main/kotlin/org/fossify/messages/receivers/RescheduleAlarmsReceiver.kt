package org.fossify.messages.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.messages.bridge.BridgeService
import org.fossify.messages.extensions.rescheduleAllScheduledMessages
import org.fossify.messages.extensions.scheduleHistoryCleanup

/**
 * Reschedules alarms after boot/package updates.
 */
class RescheduleAlarmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        ensureBackgroundThread {
            BridgeService.maybeStart(context)
            context.rescheduleAllScheduledMessages()
            context.scheduleHistoryCleanup()
            pendingResult.finish()
        }
    }
}
