package com.example.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Unit tests (pure JVM) of long-paste detection in the Chat composer. No clipboard is used. */
class PasteDetectionTest {

    private val long = buildString {
        repeat(12) { append("Paragraph line number $it explains the plan in detail. ") }
    } // > 500 characters

    @Test
    fun shortTextAndTyping_doNotTrigger() {
        val d = PasteDetectionService()
        assertNull(d.onTextChanged("", "Hello"))
        var text = ""
        // Typing one character at a time up to a long text never looks like a paste.
        for (c in long) {
            val next = text + c
            assertNull(d.onTextChanged(text, next))
            text = next
        }
    }

    @Test
    fun longPaste_triggersWithTheExactInsertedText() {
        val d = PasteDetectionService()
        val event = d.onTextChanged("Intro: ", "Intro: $long")
        assertNotNull(event)
        assertEquals(long, event!!.text)
    }

    @Test
    fun pasteInTheMiddleOfExistingText_isTheInsertedPartOnly() {
        val d = PasteDetectionService()
        val event = d.onTextChanged("Start  end", "Start $long end")
        assertEquals(long, event!!.text)
    }

    @Test
    fun belowThreshold_andExactlyAtThreshold() {
        val d = PasteDetectionService(threshold = 500)
        assertNull(d.onTextChanged("", "a".repeat(499)))
        assertNotNull(d.onTextChanged("", "a".repeat(500)))
        // Surrounding whitespace does not count.
        assertNull(PasteDetectionService().onTextChanged("", "\n\n" + "b".repeat(499) + "   "))
    }

    @Test
    fun thresholdIsConfigurable() {
        val d = PasteDetectionService(threshold = 50)
        assertNotNull(d.onTextChanged("", "x".repeat(60)))
        assertEquals(PasteDetectionService.DEFAULT_THRESHOLD, PasteDetectionService().threshold)
    }

    @Test
    fun repeatedPaste_ofTheSameContentIsSuggestedOnce() {
        val d = PasteDetectionService()
        val first = d.onTextChanged("", long)!!
        d.markHandled(first)
        // Cleared, then the same text pasted again.
        assertNull(d.onTextChanged("", long))
        // Different content is suggested.
        assertNotNull(d.onTextChanged("", long + " Extra sentence."))
    }

    @Test
    fun emptyAndBlankContent_doNotTrigger() {
        val d = PasteDetectionService()
        assertNull(d.onTextChanged("", ""))
        assertNull(d.onTextChanged(long, "")) // deletion
        assertNull(d.onTextChanged("", " ".repeat(800)))
        assertNull(d.onTextChanged("", "\n".repeat(800)))
    }

    @Test
    fun multiParagraphPaste_keepsLineBreaks() {
        val text = "Title\n\n$long\n\n- item one\n- item two"
        val event = PasteDetectionService().onTextChanged("", text)
        assertEquals(text, event!!.text)
    }

    @Test
    fun disabledWhileVoiceInputUsesTheComposer() {
        assertNull(PasteDetectionService().onTextChanged("", long, enabled = false))
    }
}
