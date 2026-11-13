package com.example.offline

/**
 * Whitespace-only normalization of pasted text. Wording, letters, digits, punctuation and
 * diacritics are never changed and no Unicode normalization (NFC/NFKC) is applied, so Arabic,
 * French and English text keeps its exact characters. Only these changes are made:
 *  - line endings (CRLF, CR, U+2028) become "\n"; U+2029 becomes a blank line;
 *  - byte-order marks (U+FEFF) are removed;
 *  - spaces and tabs at the end of a line are removed;
 *  - more than one blank line in a row becomes a single blank line (a paragraph break);
 *  - blank lines at the start and the end are removed.
 * Tabs and spaces inside a line are kept, because they can mark table columns.
 */
object TextNormalizationEngine {

    fun normalize(raw: String): String {
        if (raw.isEmpty()) return raw
        val out = StringBuilder(raw.length)
        var lineStart = 0 // index in out where the current line starts
        var blankRun = 0 // consecutive blank lines already written
        var i = 0

        fun endLine() {
            // Remove trailing spaces/tabs of the line just written.
            var end = out.length
            while (end > lineStart && (out[end - 1] == ' ' || out[end - 1] == '\t')) end--
            out.setLength(end)
            val blank = end == lineStart
            if (blank) {
                // Keep at most one blank line, and none at the start.
                if (blankRun >= 1 || out.isEmpty()) {
                    lineStart = out.length
                    blankRun++
                    return
                }
                blankRun++
            } else {
                blankRun = 0
            }
            out.append('\n')
            lineStart = out.length
        }

        while (i < raw.length) {
            when (val c = raw[i]) {
                '\r' -> {
                    endLine()
                    if (i + 1 < raw.length && raw[i + 1] == '\n') i++
                }
                '\n', ' ' -> endLine()
                ' ' -> { endLine(); endLine() }
                '\uFEFF' -> Unit
                else -> out.append(c)
            }
            i++
        }
        endLine()
        // Remove the final newline(s) left by the last line(s).
        var end = out.length
        while (end > 0 && out[end - 1] == '\n') end--
        out.setLength(end)
        return out.toString()
    }

    /** Collapses runs of spaces/tabs to one space (used for prose lines; never for table cells). */
    fun collapseSpaces(text: String): String {
        if (text.none { it == '\t' } && !text.contains("  ")) return text.trim()
        val sb = StringBuilder(text.length)
        var space = false
        for (c in text.trim()) {
            if (c == ' ' || c == '\t') {
                if (!space) sb.append(' ')
                space = true
            } else {
                sb.append(c)
                space = false
            }
        }
        return sb.toString()
    }
}
