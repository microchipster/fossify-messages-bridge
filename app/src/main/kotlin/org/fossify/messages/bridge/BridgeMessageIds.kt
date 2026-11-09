/*
 * SPDX-FileCopyrightText: 2026 The Fossify Messages bridge contributors
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.fossify.messages.bridge

/**
 * Wire message ids of the agent API: `sms:<sub_id>:<row_id>` / `mms:<sub_id>:<row_id>`.
 */
internal object BridgeMessageIds {
    private const val MESSAGE_ID_PARTS = 3
    private const val KIND_INDEX = 0
    private const val SUB_ID_INDEX = 1
    private const val ROW_ID_INDEX = 2

    private const val KIND_SMS = "sms"
    private const val KIND_MMS = "mms"

    fun format(kind: String, subId: Int, rowId: Long): String {
        return "$kind:$subId:$rowId"
    }

    /** Splits a wire message id, returning null when it is malformed. */
    fun parse(messageId: String): Triple<String, Int, Long>? {
        val parts = messageId.split(":")
        if (parts.size != MESSAGE_ID_PARTS) {
            return null
        }
        val kind = parts[KIND_INDEX]
        if (kind != KIND_SMS && kind != KIND_MMS) {
            return null
        }
        val subId = parts[SUB_ID_INDEX].toIntOrNull()
        val rowId = parts[ROW_ID_INDEX].toLongOrNull()
        return if (subId != null && rowId != null) {
            Triple(kind, subId, rowId)
        } else {
            null
        }
    }
}
