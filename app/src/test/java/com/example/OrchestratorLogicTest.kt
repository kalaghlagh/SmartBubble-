package com.example

import com.example.ai.orchestrator.ContentOperation
import com.example.ai.orchestrator.ContextMessage
import com.example.ai.orchestrator.ConversationContext
import com.example.ai.orchestrator.DocumentFormat
import com.example.ai.orchestrator.DocumentSchemaException
import com.example.ai.orchestrator.DocumentSchemaParser
import com.example.ai.orchestrator.ParsedDocument
import com.example.ai.providers.AiRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM tests of the orchestrator's structured-output parsing and context building. */
class OrchestratorLogicTest {

    @Test
    fun wordJsonIsParsedIncludingArabicAndTables() {
        val json = """
            Here is the document:
            ```json
            {"title":"تقرير المبيعات","sections":[
              {"heading":"الملخص","paragraphs":["فقرة"],"bullets":["نقطة"]},
              {"heading":"Data","table":{"headers":["A","B"],"rows":[["1"],["2","3","4"]]}}
            ]}
            ```
        """.trimIndent()
        val result = DocumentSchemaParser.parse(json, DocumentFormat.DOCX, "1 Jan 2026")
        val doc = result.document as ParsedDocument.Word
        assertEquals("تقرير المبيعات", doc.model.title)
        assertEquals(2, doc.model.sections.size)
        val table = doc.model.sections[1].table!!
        assertEquals(listOf("1", ""), table.rows[0]) // padded
        assertEquals(listOf("2", "3"), table.rows[1]) // truncated...
        assertTrue(result.warnings.isNotEmpty()) // ...and reported, not silently dropped
        assertTrue(doc.plainText.contains("نقطة"))
    }

    @Test
    fun presentationJsonIsParsed() {
        val json = """{"title":"Deck","slides":[{"title":"S1","bullets":["a","b"],"metric":"42%","metric_label":"growth"},{"title":""}]}"""
        val doc = DocumentSchemaParser.parse(json, DocumentFormat.PPTX).document as ParsedDocument.Presentation
        assertEquals(1, doc.model.slides.size)
        assertEquals("42%", doc.model.slides[0].calloutMetric)
    }

    @Test
    fun spreadsheetNumbersSheetNamesAndFormulasAreValidated() {
        val json = """{"title":"Budget","sheets":[
            {"name":"Q1/Q2: [Budget]","headers":["Dept","Amount"],"rows":[["Eng",125000],["Ops",6000.5]],"total_label":"Total","total_formula":"=SUM(B2:B3)"},
            {"name":"Q1/Q2: [Budget]","headers":["X"],"rows":[["y"]],"total_formula":"HYPERLINK(\"http://x\")"}
        ]}"""
        val result = DocumentSchemaParser.parse(json, DocumentFormat.XLSX)
        val book = (result.document as ParsedDocument.Spreadsheet).model
        assertEquals(125000L, book.sheets[0].rows[0][1])
        assertEquals(6000.5, book.sheets[0].rows[1][1])
        assertEquals("SUM(B2:B3)", book.sheets[0].summaryFormula)
        assertTrue(book.sheets.all { s -> s.name.none { it in "[]:*?/\\" } && s.name.length <= 31 })
        assertTrue(book.sheets[0].name != book.sheets[1].name) // unique names
        assertNull(book.sheets[1].summaryFormula) // unsafe formula removed...
        assertTrue(result.warnings.any { it.contains("formula") }) // ...and reported
    }

    @Test(expected = DocumentSchemaException::class)
    fun invalidJsonIsRejectedForRepair() {
        DocumentSchemaParser.parse("Sorry, I cannot do that.", DocumentFormat.DOCX)
    }

    @Test(expected = DocumentSchemaException::class)
    fun emptyDocumentIsRejected() {
        DocumentSchemaParser.parse("""{"title":"T","sections":[]}""", DocumentFormat.DOCX)
    }

    @Test
    fun contextKeepsMostRecentMessagesWithinBudgetAndIncludesDocuments() {
        val history = (1..100).map { ContextMessage(isUser = it % 2 == 1, text = "message $it") } +
            ContextMessage(false, "Here is your file", attachedDocumentText = "DOC BODY", attachedDocumentTitle = "Report.docx")
        val messages = ConversationContext.build(history, "What does the report say?")
        assertTrue(messages.size <= ConversationContext.MAX_MESSAGES + 1)
        assertEquals("What does the report say?", messages.last().text)
        assertEquals(AiRole.USER, messages.last().role)
        assertTrue(messages[messages.size - 2].text.contains("DOC BODY"))
        assertTrue(messages.sumOf { it.text.length } <= ConversationContext.MAX_TOTAL_CHARS)
    }

    @Test
    fun followUpActionKeysMapToOperations() {
        assertEquals(ContentOperation.CORRECT, ContentOperation.fromActionKey("SPELLING"))
        assertEquals(ContentOperation.IMPROVE_FORMAT, ContentOperation.fromActionKey("format"))
        assertEquals(ContentOperation.TRANSLATE, ContentOperation.fromActionKey("TRANSLATE"))
        assertEquals(ContentOperation.SUMMARIZE, ContentOperation.fromActionKey("SUMMARIZE"))
        assertNull(ContentOperation.fromActionKey("unknown"))
    }
}
