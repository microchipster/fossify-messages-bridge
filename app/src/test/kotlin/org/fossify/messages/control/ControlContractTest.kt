/*
 * SPDX-FileCopyrightText: 2026 microchipster
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

@file:Suppress("MagicNumber")

package org.fossify.messages.control

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlContractTest {
    private val json = Json

    @Test
    fun commandActionsRoundTrip() {
        val commands = listOf(
            ControlCommand.STATUS,
            ControlCommand.CONFIG,
            ControlCommand.PAIR,
            ControlCommand.UNPAIR,
            ControlCommand.SEND,
            ControlCommand.SYNC,
        )
        for (command in commands) {
            assertEquals("org.fossify.messages.bridge.control.${command.commandName}", command.action)
            assertEquals(command, ControlCommand.fromAction(command.action))
        }
        assertNull(ControlCommand.fromAction(null))
        assertNull(ControlCommand.fromAction("org.fossify.messages.bridge.control.status"))
        assertNull(ControlCommand.fromAction("org.fossify.messages.bridge.control.NOPE"))
    }

    @Test
    fun extraNamesMatchTheShellWrapper() {
        assertEquals("control_token", ControlContract.EXTRA_CONTROL_TOKEN)
        assertEquals("config", ControlContract.EXTRA_CONFIG)
        assertEquals("pair", ControlContract.EXTRA_PAIR)
        assertEquals("to", ControlContract.EXTRA_TO)
        assertEquals("text", ControlContract.EXTRA_TEXT)
        assertEquals("file", ControlContract.EXTRA_FILE)
    }

    @Test
    fun authorizationMatrix() {
        // adb shell holds DUMP, so no token is needed.
        assertTrue(ControlContract.isAuthorized("", "secret", hasDumpPermission = true))
        assertTrue(ControlContract.isAuthorized("wrong", "secret", hasDumpPermission = true))
        assertTrue(ControlContract.isAuthorized("", "", hasDumpPermission = true))
        // Token holders pass without DUMP.
        assertTrue(ControlContract.isAuthorized("secret", "secret", hasDumpPermission = false))
        // Everything else is rejected.
        assertFalse(ControlContract.isAuthorized("", "secret", hasDumpPermission = false))
        assertFalse(ControlContract.isAuthorized("wrong", "secret", hasDumpPermission = false))
        assertFalse(ControlContract.isAuthorized("secret", "", hasDumpPermission = false))
        assertFalse(ControlContract.isAuthorized("secret", "secret-longer", hasDumpPermission = false))
        assertFalse(ControlContract.isAuthorized("secret-longer", "secret", hasDumpPermission = false))
        assertFalse(ControlContract.isAuthorized("", "", hasDumpPermission = false))
    }

    @Test
    fun parseRecipientsTrimsDropsEmptyAndDeduplicates() {
        assertEquals(listOf("+1", "+2"), ControlContract.parseRecipients(" +1, +2 ,, +1, "))

        val empty = ControlContract.parseRecipients(null)
        assertTrue(empty.isEmpty())
        assertTrue(ControlContract.parseRecipients("").isEmpty())
        assertTrue(ControlContract.parseRecipients(" , , ").isEmpty())
    }

    @Test
    fun objectOrNullRejectsNonObjects() {
        assertNull(ControlContract.objectOrNull(null))
        assertNull(ControlContract.objectOrNull(""))
        assertNull(ControlContract.objectOrNull("not json"))
        assertNull(ControlContract.objectOrNull("[1,2]"))
        assertNull(ControlContract.objectOrNull("\"text\""))
        assertEquals(setOf("a"), ControlContract.objectOrNull("{\"a\":1}")?.keys)
    }

    @Test
    fun strictReadersRejectForeignJsonTypes() {
        val payload = ControlContract.objectOrNull(
            """{"b":true,"s":"text","i":42,"string_i":"42","string_b":"true"}"""
        )!!
        assertNull(ControlContract.booleanValue(payload, "string_b"))
        assertNull(ControlContract.intValue(payload, "string_i"))
        assertNull(ControlContract.stringValue(payload, "i"))
        assertNull(ControlContract.booleanValue(payload, "missing"))
        assertNull(ControlContract.intValue(payload, "missing"))
        assertNull(ControlContract.stringValue(payload, "missing"))
        assertEquals(true, ControlContract.booleanValue(payload, "b"))
        assertEquals("text", ControlContract.stringValue(payload, "s"))
        assertEquals(42, ControlContract.intValue(payload, "i"))
    }

    @Test
    fun validateConfigAcceptsDocumentedKeys() {
        val valid = ControlContract.objectOrNull(
            """{"bridge_enabled":true,"bridge_port":8391,"bridge_device_id":"id","bridge_token":"t",
               "bridge_hs_url":"https://matrix.example.com","bridge_base_url":"https://bridge.example.com",
               "control_enabled":true,"control_token":"c"}"""
        )!!
        assertNull(ControlContract.validateConfig(valid))
        assertNull(ControlContract.validateConfig(ControlContract.objectOrNull("{}")!!))
    }

    @Test
    fun validateConfigRejectsUnknownKeysAndWrongTypes() {
        assertEquals(
            "unknown config key 'nope'",
            ControlContract.validateConfig(ControlContract.objectOrNull("""{"nope":1}""")!!),
        )
        assertEquals(
            "config key 'bridge_enabled' must be a boolean",
            ControlContract.validateConfig(ControlContract.objectOrNull("""{"bridge_enabled":"yes"}""")!!),
        )
        assertEquals(
            "config key 'bridge_port' must be a number",
            ControlContract.validateConfig(ControlContract.objectOrNull("""{"bridge_port":"8391"}""")!!),
        )
        assertEquals(
            "config key 'bridge_port' must be between 1024 and 65535",
            ControlContract.validateConfig(ControlContract.objectOrNull("""{"bridge_port":80}""")!!),
        )
        assertEquals(
            "config key 'control_token' must be a string",
            ControlContract.validateConfig(ControlContract.objectOrNull("""{"control_token":1}""")!!),
        )
    }

    @Test
    fun errorCodeMappings() {
        assertEquals(ControlContract.ERROR_INVALID_REQUEST, ControlContract.errorCodeForSendFailure("unsupported"))
        assertEquals(ControlContract.ERROR_INVALID_REQUEST, ControlContract.errorCodeForSendFailure("too_large"))
        assertEquals(ControlContract.ERROR_INVALID_REQUEST, ControlContract.errorCodeForSendFailure("too_long"))
        assertEquals(ControlContract.ERROR_UNAVAILABLE, ControlContract.errorCodeForSendFailure("send_failed"))
        assertEquals(
            ControlContract.ERROR_UNAVAILABLE,
            ControlContract.errorCodeForSendFailure("mms_send_unsupported"),
        )
        assertEquals(ControlContract.ERROR_INVALID_REQUEST, ControlContract.errorCodeForPairingFailure("bad_request"))
        assertEquals(ControlContract.ERROR_UNAVAILABLE, ControlContract.errorCodeForPairingFailure("not_found"))
    }

    @Test
    fun errorJsonMatchesTheDocumentedShape() {
        val payload = json.parseToJsonElement(ControlContract.errorJson("invalid_token", "nope")).jsonObject
        val error = payload["error"]!!.jsonObject
        assertEquals(setOf("error"), payload.keys)
        assertEquals("invalid_token", error["code"]!!.jsonPrimitive.content)
        assertEquals("nope", error["message"]!!.jsonPrimitive.content)
    }

    @Test
    fun statusJsonContainsEveryDocumentedField() {
        val status = ControlStatusPayload(
            enabled = true,
            serviceRunning = true,
            paired = true,
            deviceId = "android-test-0001",
            userId = "@alice:example.com",
            hsUrl = "https://matrix.example.com",
            bridgeBaseUrl = "https://bridge.example.com",
            bridgePort = 8391,
            defaultSmsRole = true,
            permissions = mapOf("sms" to true, "read_sms" to true, "post_notifications" to false),
            lastHeartbeatMs = 1761600000000L,
            queueCursor = 42,
            eventCursor = 17,
            outboxSms = 0,
            outboxMms = 1,
            versionName = "1.10.1-bridge.1",
            versionCode = 4,
        )
        val payload = json.parseToJsonElement(ControlContract.statusJson(status)).jsonObject
        assertEquals(
            setOf(
                "enabled",
                "service_running",
                "paired",
                "device_id",
                "user_id",
                "hs_url",
                "bridge_base_url",
                "bridge_port",
                "default_sms_role",
                "permissions",
                "last_heartbeat_ms",
                "queue_cursor",
                "event_cursor",
                "outbox_sms",
                "outbox_mms",
                "version_name",
                "version_code",
            ),
            payload.keys,
        )
        assertEquals(8391, payload["bridge_port"]!!.jsonPrimitive.int)
        assertEquals(1761600000000L, payload["last_heartbeat_ms"]!!.jsonPrimitive.long)
        assertFalse(payload.containsKey("bridge_token"))
        assertFalse(payload.containsKey("control_token"))
        assertFalse(payload.toString().contains("secret"))
    }

    @Test
    fun configJsonOmitsUnappliedKeys() {
        val payload = json.parseToJsonElement(
            ControlContract.configJson(ControlConfigPayload(bridgePort = 9000, controlTokenSet = true))
        ).jsonObject
        assertEquals(setOf("bridge_port", "control_token_set"), payload.keys)
        assertEquals(9000, payload["bridge_port"]!!.jsonPrimitive.int)
        assertTrue(payload["control_token_set"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun configJsonKeepsExplicitlyAppliedFalseFlags() {
        val payload = json.parseToJsonElement(
            ControlContract.configJson(ControlConfigPayload(bridgeEnabled = false, controlEnabled = false))
        ).jsonObject
        assertEquals(setOf("bridge_enabled", "control_enabled"), payload.keys)
        assertFalse(payload["bridge_enabled"]!!.jsonPrimitive.boolean)
        assertFalse(payload["control_enabled"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun pairAndUnpairJsonMatchTheDocumentedShape() {
        val paired = json.parseToJsonElement(
            ControlContract.pairJson(
                ControlPairPayload(
                    paired = true,
                    deviceId = "android-test-0001",
                    userId = "@alice:example.com",
                    hsUrl = "https://matrix.example.com",
                )
            )
        ).jsonObject
        assertEquals(setOf("paired", "device_id", "user_id", "hs_url"), paired.keys)
        assertTrue(paired["paired"]!!.jsonPrimitive.boolean)

        val unpaired = json.parseToJsonElement(ControlContract.unpairJson()).jsonObject
        assertEquals(setOf("paired"), unpaired.keys)
        assertFalse(unpaired["paired"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun sendAndSyncJsonMatchTheDocumentedShape() {
        val sent = json.parseToJsonElement(
            ControlContract.sendJson(
                ControlSendPayload(messageId = 1234, threadId = 5, pending = true, mode = "mms")
            )
        ).jsonObject
        assertEquals(setOf("message_id", "thread_id", "pending", "mode"), sent.keys)
        assertEquals(1234L, sent["message_id"]!!.jsonPrimitive.long)
        assertEquals("mms", sent["mode"]!!.jsonPrimitive.content)
        assertTrue(sent["pending"]!!.jsonPrimitive.boolean)

        val synced = json.parseToJsonElement(
            ControlContract.syncJson(ControlSyncPayload(queueCursor = 42, eventCursor = 17))
        ).jsonObject
        assertEquals(setOf("queue_cursor", "event_cursor"), synced.keys)
        assertEquals(42L, synced["queue_cursor"]!!.jsonPrimitive.long)
        assertEquals(17L, synced["event_cursor"]!!.jsonPrimitive.long)
    }
}
