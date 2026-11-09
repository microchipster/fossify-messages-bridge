/*
 * SPDX-FileCopyrightText: 2026 The Fossify Messages bridge contributors
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.fossify.messages.helpers

import androidx.annotation.StringRes
import org.fossify.messages.R
import java.util.Locale

/** One tapback the user can send, in the order the picker shows them. */
data class TapbackChoice(
    val name: String,
    val emoji: String,
    @StringRes val titleRes: Int,
)

/** An iMessage-style tapback recognized in a message body. */
data class Tapback(
    val name: String,
    val emoji: String,
    val targetText: String,
    val isMedia: Boolean,
)

/** A thread message a tapback chip can be attached to, in thread order. */
data class TapbackCandidate(
    val key: Long,
    val body: String,
    val hasAttachment: Boolean,
)

/** Which tapback messages to hide and which emojis to show on their targets. */
data class ResolvedTapbacks(
    val hiddenKeys: Set<Long>,
    val reactions: Map<Long, List<String>>,
)

/**
 * Parses and formats iMessage-style tapbacks the same way the Matrix bridge
 * does (`connector/tapback.go`): they arrive as plain SMS texts such as
 * `Liked “see you at 8”` or `Loved an image`, and outgoing tapbacks are sent in
 * the same format so the bridge turns them into Matrix reactions.
 */
object Tapbacks {
    private const val OPEN_QUOTE = "\u201C"
    private const val CLOSE_QUOTE = "\u201D"
    private const val ELLIPSIS = "\u2026"
    private const val IMAGE_LABEL = "an image"

    // Same lookback window the bridge uses when matching tapbacks.
    private const val TAPBACK_LOOKBACK = 100

    private val addedNames = mapOf(
        "loved" to "Loved",
        "liked" to "Liked",
        "disliked" to "Disliked",
        "laughed at" to "Laughed at",
        "emphasized" to "Emphasized",
        "questioned" to "Questioned",
    )

    val choices: List<TapbackChoice> = listOf(
        TapbackChoice("loved", "\u2764\uFE0F", R.string.tapback_love),
        TapbackChoice("liked", "\uD83D\uDC4D", R.string.tapback_like),
        TapbackChoice("disliked", "\uD83D\uDC4E", R.string.tapback_dislike),
        TapbackChoice("laughed at", "\uD83D\uDE02", R.string.tapback_laugh),
        TapbackChoice("emphasized", "\u203C\uFE0F", R.string.tapback_emphasize),
        TapbackChoice("questioned", "\u2753", R.string.tapback_question),
    )

    private val addedNamesPattern = addedNames.values.joinToString("|") { Regex.escape(it) }

    // iMessage sends curly quotes, but carriers and gateways rewrite them to
    // straight ones, so both are accepted (same as the bridge).
    private val addedTextRegex = Regex(
        "^($addedNamesPattern) [$OPEN_QUOTE\"](.*)[$CLOSE_QUOTE\"]$"
    )
    private val addedMediaRegex = Regex("^($addedNamesPattern) an? [A-Za-z ]+$")

    /** Returns the tapback [body] holds, or null when it is a regular message. */
    fun parse(body: String): Tapback? {
        val text = body.trim()
        if (text.isEmpty()) {
            return null
        }

        addedTextRegex.matchEntire(text)?.let { match ->
            return createTapback(match.groupValues[1], match.groupValues[2], isMedia = false)
        }

        addedMediaRegex.matchEntire(text)?.let { match ->
            return createTapback(match.groupValues[1], targetText = "", isMedia = true)
        }

        return null
    }

    /**
     * Builds the outgoing tapback text. Messages with an attachment and no text
     * use the media form, e.g. `Liked an image`.
     */
    fun format(name: String, body: String, mediaLabel: String = IMAGE_LABEL): String {
        val displayName = addedNames[name] ?: return ""
        return if (body.isBlank()) {
            "$displayName $mediaLabel"
        } else {
            "$displayName $OPEN_QUOTE$body$CLOSE_QUOTE"
        }
    }

    /** The media wording for a tapback on an attachment, e.g. `Liked a video`. */
    fun mediaLabel(mimetype: String): String = when {
        mimetype.startsWith("image", ignoreCase = true) -> IMAGE_LABEL
        mimetype.startsWith("video", ignoreCase = true) -> "a video"
        else -> "a file"
    }

    /**
     * Matches tapback messages against the messages they quote. Messages are
     * expected in thread order, oldest first. Tapbacks whose target cannot be
     * found stay in the thread as regular bubbles.
     */
    fun resolve(messages: List<TapbackCandidate>): ResolvedTapbacks {
        val hiddenKeys = mutableSetOf<Long>()
        val reactions = linkedMapOf<Long, MutableList<String>>()

        messages.forEachIndexed { index, message ->
            val tapback = parse(message.body) ?: return@forEachIndexed
            val target = findTarget(messages, index, tapback) ?: return@forEachIndexed
            hiddenKeys.add(message.key)
            val targetEmojis = reactions.getOrPut(target.key) { mutableListOf() }
            if (!targetEmojis.contains(tapback.emoji)) {
                targetEmojis.add(tapback.emoji)
            }
        }

        return ResolvedTapbacks(hiddenKeys, reactions)
    }

    private fun findTarget(
        messages: List<TapbackCandidate>,
        tapbackIndex: Int,
        tapback: Tapback,
    ): TapbackCandidate? {
        val quoted = normalizeTargetText(tapback.targetText)
        val fromIndex = maxOf(0, tapbackIndex - TAPBACK_LOOKBACK)
        return messages.subList(fromIndex, tapbackIndex).asReversed().firstOrNull { candidate ->
            matchesTarget(candidate, tapback, quoted)
        }
    }

    private fun matchesTarget(candidate: TapbackCandidate, tapback: Tapback, quoted: String): Boolean {
        if (parse(candidate.body) != null) {
            return false
        }
        if (tapback.isMedia) {
            return candidate.hasAttachment
        }

        val body = candidate.body.trim()
        if (body.isEmpty() || quoted.isEmpty()) {
            return false
        }
        return body == quoted || body.startsWith(quoted)
    }

    private fun createTapback(namePattern: String, targetText: String, isMedia: Boolean): Tapback? {
        val name = namePattern.lowercase(Locale.ROOT)
        val choice = choices.firstOrNull { it.name == name } ?: return null
        return Tapback(name, choice.emoji, targetText, isMedia)
    }

    // Gateways trim long quoted messages and add an ellipsis (same as the bridge).
    private fun normalizeTargetText(text: String): String {
        var normalized = text.trim()
        normalized = normalized.removeSuffix(ELLIPSIS)
        normalized = normalized.removeSuffix("...")
        return normalized.trim()
    }
}
