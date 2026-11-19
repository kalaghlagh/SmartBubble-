package com.example.document.pdf

import android.text.TextDirectionHeuristic
import android.text.TextDirectionHeuristics

/**
 * Paragraph direction for the PDF engines. Android's default (first strong character, else left
 * to right) puts an Arabic paragraph that starts with a Latin word, a date, a number or a
 * reference ("REF-2026 طلب ...") in a left-to-right paragraph, so its words come out in the wrong
 * order. The direction is decided by the paragraph's letters: mostly Arabic is right to left.
 * The text itself is never reversed; shaping and the bidirectional algorithm are Android's.
 */
object PdfTextDirection {
    fun isRtl(text: CharSequence): Boolean {
        var arabic = 0
        var latin = 0
        for (c in text) {
            when {
                c in '\u0590'..'\u08FF' || c in '\uFB1D'..'\uFDFF' || c in '\uFE70'..'\uFEFF' -> arabic++
                c.isLetter() -> latin++
            }
        }
        return arabic > latin
    }

    fun of(text: CharSequence): TextDirectionHeuristic = if (isRtl(text)) TextDirectionHeuristics.RTL else TextDirectionHeuristics.LTR
}
