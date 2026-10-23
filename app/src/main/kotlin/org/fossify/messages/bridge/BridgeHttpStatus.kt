/*
 * SPDX-FileCopyrightText: 2026 microchipster
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.fossify.messages.bridge

/** HTTP status codes used by the agent API and its error responses. */
internal object BridgeHttpStatus {
    const val OK = 200
    const val NO_CONTENT = 204
    const val BAD_REQUEST = 400
    const val UNAUTHORIZED = 401
    const val NOT_FOUND = 404
    const val TOO_LARGE = 413
    const val INTERNAL_ERROR = 500
    const val NOT_IMPLEMENTED = 501
    const val BAD_GATEWAY = 502
    const val SERVICE_UNAVAILABLE = 503

    /** Reason phrase sent in the status line of a response. */
    fun text(status: Int): String = when (status) {
        OK -> "OK"
        NO_CONTENT -> "No Content"
        BAD_REQUEST -> "Bad Request"
        UNAUTHORIZED -> "Unauthorized"
        NOT_FOUND -> "Not Found"
        TOO_LARGE -> "Payload Too Large"
        INTERNAL_ERROR -> "Internal Server Error"
        NOT_IMPLEMENTED -> "Not Implemented"
        BAD_GATEWAY -> "Bad Gateway"
        SERVICE_UNAVAILABLE -> "Service Unavailable"
        else -> "Error"
    }
}
