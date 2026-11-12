package com.example.offline

/** A long block of text that was inserted into the Chat composer in one edit. */
data class PasteEvent(
    /** The inserted text exactly as it arrived in the composer (never modified). */
    val text: String,
    /** Identifies the content, so the same paste does not trigger the suggestion twice. */
    val fingerprint: Long
)

/**
 * Detects long pastes into the Chat composer by comparing the composer text before and after
 * each edit. It never reads the system clipboard: the only input is the text the composer
 * already received. Typing changes one or a few characters per edit, so an edit that inserts
 * at least [threshold] characters at once is a paste (or another bulk insertion such as a
 * keyboard's clipboard suggestion).
 *
 * The same content is suggested once: after the suggestion was shown (and dismissed or used),
 * pasting identical text again does not show it again.
 */
class PasteDetectionService(val threshold: Int = DEFAULT_THRESHOLD) {

    companion object {
        /** Initial minimum length (characters, surrounding whitespace excluded) of a long paste. */
        const val DEFAULT_THRESHOLD = 500

        /** How many recent pastes are remembered to avoid repeated suggestions. */
        private const val MEMORY = 32

        fun fingerprintOf(text: String): Long {
            val t = text.trim()
            return (t.length.toLong() shl 32) xor (t.hashCode().toLong() and 0xFFFFFFFFL)
        }

        /** The text inserted by the edit [before] -> [after], or "" when nothing was inserted. */
        fun insertedText(before: String, after: String): String {
            if (after.isEmpty()) return ""
            var prefix = 0
            val maxPrefix = minOf(before.length, after.length)
            while (prefix < maxPrefix && before[prefix] == after[prefix]) prefix++
            var suffix = 0
            val maxSuffix = minOf(before.length, after.length) - prefix
            while (suffix < maxSuffix && before[before.length - 1 - suffix] == after[after.length - 1 - suffix]) suffix++
            val end = after.length - suffix
            return if (end > prefix) after.substring(prefix, end) else ""
        }
    }

    init {
        require(threshold > 0)
    }

    private val seen = LinkedHashSet<Long>()

    /**
     * Called for every composer edit. Returns the paste to suggest formatting for, or null.
     * @param enabled false while voice input fills the composer or a request is running
     */
    fun onTextChanged(before: String, after: String, enabled: Boolean = true): PasteEvent? {
        if (!enabled) return null
        // Cheap exit for typing: the composer grew by less than the threshold.
        if (after.length - before.length < threshold && after.length < threshold) return null
        val inserted = insertedText(before, after)
        if (inserted.isBlank() || inserted.trim().length < threshold) return null
        val fingerprint = fingerprintOf(inserted)
        if (fingerprint in seen) return null
        return PasteEvent(inserted, fingerprint)
    }

    /** Remembers [event] so the same content is not suggested again (shown, dismissed or used). */
    fun markHandled(event: PasteEvent) {
        seen.remove(event.fingerprint)
        seen.add(event.fingerprint)
        while (seen.size > MEMORY) seen.remove(seen.first())
    }

    fun wasHandled(text: String): Boolean = fingerprintOf(text) in seen
}
