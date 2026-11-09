/*
 * SPDX-FileCopyrightText: 2026 The Fossify Messages bridge contributors
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.fossify.messages.helpers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/*
 * Pure retention and redaction decisions, kept free of Android types so they
 * stay unit-testable on the JVM. The storage (preferences), the database and
 * the daily worker live in `extensions/HistoryCleanup.kt`.
 *
 * A message is deleted when it is older than the retention period that
 * applies to its conversation, or when its body matches one of the redaction
 * patterns (regardless of age).
 */

/** One message as offered to [HistoryRetention.decide]. */
data class RetentionMessage(
    val id: Long,
    val threadId: Long,
    val dateSeconds: Long,
    val body: String,
    val isMMS: Boolean,
)

/** A compiled, named redaction pattern. */
data class RedactionPattern(val name: String, val pattern: Regex)

/**
 * Named redaction pattern sets that ship with the app. Users enable the
 * presets they want and may add custom patterns on top.
 */
enum class RedactionPreset(val id: String, val pattern: Regex) {
    OTP(
        "otp",
        Regex(
            "\\b(?:otp|one[- ]time (?:code|password|pin)" +
                "|(?:verification|security|authentication|auth|confirmation|login) code" +
                "|2fa code|passcode)\\b",
            RegexOption.IGNORE_CASE,
        ),
    ),
    PASSWORD_RESET(
        "password_reset",
        Regex(
            "\\b(?:password|passcode|pin)\\b[\\s\\S]{0,60}?" +
                "\\b(?:reset|renew|chang|updat|expir|creat|enabl|disabl|set up)" +
                "|\\b(?:reset|renew|chang|updat|expir)\\w*\\b[\\s\\S]{0,60}?\\b(?:password|passcode|pin)\\b",
            RegexOption.IGNORE_CASE,
        ),
    ),
    CARD_NUMBER(
        "card_number",
        Regex("\\b(?:\\d[ -]?){13,19}(?!\\d)"),
    ),
    SSN(
        "ssn",
        Regex("\\b\\d{3}-\\d{2}-\\d{4}\\b"),
    ),
    ;

    companion object {
        fun fromId(id: String): RedactionPreset? = entries.firstOrNull { it.id == id }
    }
}

/**
 * The redaction configuration: the enabled preset names plus custom
 * extension patterns (`name -> regular expression`).
 *
 * Serialized as one JSON object:
 * `{"presets":["otp"],"extensions":{"promo":"(?i)\\bunsubscribe\\b"}}`.
 */
class RedactionConfig(
    val presets: Set<RedactionPreset>,
    val extensions: Map<String, String>,
) {
    val isEmpty: Boolean
        get() = presets.isEmpty() && extensions.isEmpty()

    /** Presets first (declaration order), then custom patterns sorted by name. Invalid custom regexes are skipped. */
    fun patterns(): List<RedactionPattern> {
        val presetPatterns = presets.sortedBy { it.ordinal }.map { RedactionPattern(it.id, it.pattern) }
        val customPatterns = extensions.entries.sortedBy { it.key }
            .mapNotNull { (name, expression) -> compile(name, expression) }
        return presetPatterns + customPatterns
    }

    fun matches(body: String): Boolean = patterns().any { it.pattern.containsMatchIn(body) }

    fun encode(): String {
        val payload = buildJsonObject {
            put(FIELD_PRESETS, buildJsonArray { presets.sortedBy { it.ordinal }.forEach { add(JsonPrimitive(it.id)) } })
            put(
                FIELD_EXTENSIONS,
                buildJsonObject {
                    extensions.entries.sortedBy { it.key }.forEach { (name, expression) -> put(name, expression) }
                },
            )
        }
        return historyJson.encodeToString(JsonObject.serializer(), payload)
    }

    companion object {
        const val FIELD_PRESETS = "presets"
        const val FIELD_EXTENSIONS = "extensions"

        private const val CUSTOM_PATTERN_SEPARATOR = '='
        private const val CUSTOM_PATTERN_COMMENT = '#'

        /** Tolerant read path: malformed JSON, unknown presets and invalid custom regexes are ignored. */
        fun parse(raw: String?): RedactionConfig {
            val payload = historyJson.parseOrNull(raw) ?: return RedactionConfig(emptySet(), emptyMap())
            val presets = (payload[FIELD_PRESETS] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.content }
                .mapNotNull { RedactionPreset.fromId(it) }
                .toSet()
            val extensions = (payload[FIELD_EXTENSIONS] as? JsonObject).orEmpty()
                .mapNotNull { (name, value) ->
                    val expression = (value as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.content
                    if (expression != null && isValidRegex(expression)) name to expression else null
                }
                .toMap()
            return RedactionConfig(presets, extensions)
        }

        /**
         * Parses the `name=regex` line format used by the settings dialog.
         * Blank lines and `#` comments are ignored; null is returned when any
         * line is malformed or holds an invalid regular expression.
         */
        fun parseExtensions(text: String): Map<String, String>? {
            val extensions = linkedMapOf<String, String>()
            for (line in text.lines()) {
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith(CUSTOM_PATTERN_COMMENT)) {
                    continue
                }
                val separator = trimmed.indexOf(CUSTOM_PATTERN_SEPARATOR)
                if (separator <= 0) {
                    return null
                }
                val name = trimmed.substring(0, separator).trim()
                val expression = trimmed.substring(separator + 1).trim()
                if (name.isEmpty() || expression.isEmpty() || !isValidRegex(expression)) {
                    return null
                }
                extensions[name] = expression
            }
            return extensions
        }

        /** Renders custom patterns back into the `name=regex` line format. */
        fun extensionsText(extensions: Map<String, String>): String {
            val lines = extensions.entries.sortedBy { it.key }
                .map { (name, expression) -> "$name=$expression" }
            return lines.joinToString("\n")
        }

        fun isValidRegex(expression: String): Boolean = runCatching { Regex(expression) }.isSuccess

        private fun compile(name: String, expression: String): RedactionPattern? {
            if (!isValidRegex(expression)) {
                return null
            }
            return RedactionPattern(name, Regex(expression))
        }
    }
}

private val historyJson = Json {
    encodeDefaults = false
    explicitNulls = false
    ignoreUnknownKeys = true
}

private fun Json.parseOrNull(raw: String?): JsonObject? {
    if (raw.isNullOrBlank()) {
        return null
    }
    return try {
        parseToJsonElement(raw) as? JsonObject
    } catch (_: Exception) {
        null
    }
}

/** Per-conversation retention decisions. `KEEP_FOREVER` (or less) disables the retention for that conversation. */
object HistoryRetention {
    const val KEEP_FOREVER = 0
    const val THIRTY_DAYS = 30
    const val NINETY_DAYS = 90
    const val ONE_YEAR_DAYS = 365

    private const val SECONDS_PER_DAY = 86_400L

    data class Decision(
        val expired: List<RetentionMessage>,
        val redacted: List<RetentionMessage>,
    ) {
        /** The affected messages, expired first, without duplicates. */
        val messages: List<RetentionMessage> get() = (expired + redacted).distinctBy { it.id }

        val ids: List<Long> get() = messages.map { it.id }
    }

    /** The retention of one conversation: its override when set, otherwise the global value. */
    fun effectiveRetentionDays(threadId: Long, globalDays: Int, overrides: Map<Long, Int>): Int {
        return overrides[threadId] ?: globalDays
    }

    /** True when the message is older than the retention period. A retention of `KEEP_FOREVER` never expires. */
    fun isExpired(dateSeconds: Long, retentionDays: Int, nowSeconds: Long): Boolean {
        if (retentionDays <= KEEP_FOREVER) {
            return false
        }
        return dateSeconds < nowSeconds - retentionDays * SECONDS_PER_DAY
    }

    /**
     * Splits the offered messages into the ones over their retention and the
     * ones matching a redaction pattern. Input order is preserved within both
     * lists; a message can appear in both.
     */
    fun decide(
        messages: List<RetentionMessage>,
        globalRetentionDays: Int,
        conversationOverrides: Map<Long, Int>,
        redactions: RedactionConfig,
        nowSeconds: Long,
    ): Decision {
        val patterns = redactions.patterns()
        val expired = messages.filter {
            isExpired(
                dateSeconds = it.dateSeconds,
                retentionDays = effectiveRetentionDays(it.threadId, globalRetentionDays, conversationOverrides),
                nowSeconds = nowSeconds,
            )
        }
        val redacted = messages.filter { message -> patterns.any { it.pattern.containsMatchIn(message.body) } }
        return Decision(expired, redacted)
    }
}

/**
 * Lean storage codec for the per-conversation retention overrides: one JSON
 * object of `threadId -> days` pairs. `0` means "keep forever"; entries with
 * negative values are dropped.
 */
object HistoryRetentionOverrides {
    fun encode(overrides: Map<Long, Int>): String {
        return historyJson.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                overrides.entries.sortedBy { it.key }.forEach { (threadId, days) ->
                    if (days >= HistoryRetention.KEEP_FOREVER) {
                        put(threadId.toString(), days)
                    }
                }
            },
        )
    }

    /** Tolerant read path: malformed JSON and unusable entries are ignored. */
    fun decode(raw: String?): Map<Long, Int> {
        val payload = historyJson.parseOrNull(raw) ?: return emptyMap()
        return payload.mapNotNull { (key, value) ->
            val threadId = key.toLongOrNull()
            val primitive = (value as? JsonPrimitive)?.takeIf { !it.isString }
            val days = if (primitive == null) null else primitive.intOrNull
            if (threadId == null || days == null || days < HistoryRetention.KEEP_FOREVER) null else threadId to days
        }.toMap()
    }
}
