package com.example.offline

import com.example.data.model.DocumentEntity
import com.example.data.model.TemplateEntity
import com.example.offline.document.EngineDocumentRenderer
import com.example.offline.document.OfflineDocumentComposer
import com.example.offline.document.OfflineDocumentGenerator
import com.example.offline.document.OfflineDraft
import com.example.offline.document.OfflineFormat
import com.example.offline.document.OfflineFormattingController
import com.example.offline.document.OfflineGenerationException
import com.example.offline.document.OfflineLibrary
import com.example.offline.document.OfflineMessage
import com.example.offline.document.OfflinePhase
import com.example.offline.document.TemplateMatch
import com.example.offline.document.TemplateMatcher
import com.example.offline.document.TemplateNote
import com.example.offline.document.TemplateRepository
import com.example.offline.document.TemplateSource
import com.example.template.domain.TemplateProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Document generation with the REAL Word, PowerPoint and Excel engines (pure JVM), template
 * application with a real .docx template file, cancellation, errors, and the whole controller
 * flow (paste -> preview -> apply -> save) with an in-memory Library (fake).
 */
class OfflineDocumentGenerationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() = scope.cancel()

    private val sample = """
        PROJECT STATUS REPORT

        The project started on 3 March 2025 with a budget of 45,000 EUR.

        Milestones:
        1. Design approved on 14/04/2025
        2. Prototype delivered (version 0.9)

        Risks
        - Supplier delay of 2 weeks
        - Team size: 7 people

        | Task | Owner | Days |
        |---|---|---|
        | Testing | Leïla | 12 |
        | Release | Omar | 3 |

        المرحلة التالية تبدأ في ١ يونيو.
    """.trimIndent()

    private fun analyze(text: String = sample) = runBlocking { RuleBasedFormattingEngine().analyze(text) }

    private fun zipText(file: File, entry: String): String = ZipFile(file).use { z ->
        String(z.getInputStream(z.getEntry(entry)).readBytes(), Charsets.UTF_8)
    }

    private fun wordTexts(xml: String): String =
        Regex("""<w:t(?:\s[^>]*)?>([^<]*)</w:t>""").findAll(xml).joinToString(" ") { it.groupValues[1] }
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")

    private fun builtIn() = TemplateMatch(TemplateSource.BUILT_IN, null, TemplateMatcher.builtInProfile(), null, emptyList())

    private fun generator(dir: File = tmp.newFolder("drafts")) = OfflineDocumentGenerator(dir)

    // ------------------------------------------------------------------ DOCX

    @Test
    fun docx_containsEverySourceWordAndNothingInvented() = runBlocking {
        val result = analyze()
        val draft = generator().generate(result, OfflineFormat.DOCX, builtIn())
        val xml = zipText(draft.file, "word/document.xml")
        val text = wordTexts(xml)
        val docTokens = ContentPreservation.tokens(text).filter { it != "•" }
        val sourceTokens = ContentPreservation.tokens(ContentPreservation.contentOf(result.sourceText, result.removedMarkup))
        assertEquals(sourceTokens, docTokens)
        // No organization, date, header or footer text was added.
        listOf("Smart Bubble", "Confidential", "Enterprise").forEach { assertFalse("added: $it", xml.contains(it)) }
        assertTrue(xml.contains("<w:bidi/>")) // the Arabic paragraph is right-to-left
        assertTrue(draft.notes.isEmpty())
    }

    @Test
    fun docx_appliesARealTemplateFileWithoutModifyingIt() = runBlocking {
        val templateFile = tmp.newFile("corporate.docx")
        writeDocxTemplate(templateFile)
        val before = templateFile.readBytes()
        val entity = TemplateEntity(
            id = "corp", name = "Corporate", fileType = "DOCX", originalFilePath = templateFile.path,
            profileJson = TemplateProfile.defaultProfile().copy(templateId = "corp").toJson(), isDefaultWord = true
        )
        val repo = object : TemplateRepository {
            override suspend fun findById(id: String) = entity.takeIf { it.id == id }
            override suspend fun defaultFor(templateType: String) = entity.takeIf { templateType == "DOCX" }
            override suspend fun all() = listOf(entity)
        }
        val match = TemplateMatcher(repo).match(OfflineFormat.DOCX, null)
        assertEquals(TemplateSource.DEFAULT, match.source)
        val draft = generator().generate(analyze(), OfflineFormat.DOCX, match)

        assertTrue(draft.templateApplied)
        assertArrayEquals("template file changed", before, templateFile.readBytes())
        // Template parts are carried over: styles, header part and the page margins.
        assertEquals(zipText(templateFile, "word/styles.xml"), zipText(draft.file, "word/styles.xml"))
        assertEquals("ACME HEADER", wordTexts(zipText(draft.file, "word/header1.xml")))
        val documentXml = zipText(draft.file, "word/document.xml")
        assertTrue(documentXml.contains("""w:top="2000""""))
        assertTrue(documentXml.contains("""<w:pStyle w:val="Heading1"/>"""))
        assertTrue(wordTexts(documentXml).contains("The project started on 3 March 2025 with a budget of 45,000 EUR."))
    }

    // ------------------------------------------------------------------ PPTX / XLSX

    @Test
    fun pptx_slidesHoldAllTextInOrder() = runBlocking {
        val result = analyze()
        val draft = generator().generate(result, OfflineFormat.PPTX, builtIn())
        val texts = ZipFile(draft.file).use { z ->
            z.entries().toList().map { it.name }.filter { it.matches(Regex("ppt/slides/slide\\d+\\.xml")) }
                .sortedBy { it.removePrefix("ppt/slides/slide").removeSuffix(".xml").toInt() }
                .joinToString(" ") { name ->
                    Regex("""<a:t>([^<]*)</a:t>""").findAll(String(z.getInputStream(z.getEntry(name)).readBytes()))
                        .joinToString(" ") { it.groupValues[1] }
                }
        }.replace("&amp;", "&")
        // Every source word appears on the slides in the original order (slide numbers and the
        // heading repeated on continuation slides are the only extra text).
        val slideTokens = ContentPreservation.tokens(texts)
        val sourceTokens = ContentPreservation.tokens(ContentPreservation.contentOf(result.sourceText, result.removedMarkup))
        var k = 0
        for (t in slideTokens) if (k < sourceTokens.size && t == sourceTokens[k]) k++
        assertEquals("not all source words are on the slides in order", sourceTokens.size, k)
        assertEquals("PROJECT STATUS REPORT", OfflineDocumentComposer.toPresentation(result).title)
        assertTrue(draft.pageOrSlideCount >= 3)
    }

    @Test
    fun xlsx_tablesBecomeSheetsAndValuesStayText() = runBlocking {
        val result = analyze()
        val model = OfflineDocumentComposer.toSpreadsheet(result)
        assertEquals(listOf("Text", "Risks"), model.sheets.map { it.name })
        assertEquals(listOf(listOf("Testing", "Leïla", "12"), listOf("Release", "Omar", "3")), model.sheets[1].rows)
        val draft = generator().generate(result, OfflineFormat.XLSX, builtIn())
        val sheet = zipText(draft.file, "xl/worksheets/sheet2.xml")
        assertTrue(sheet.contains("<t>12</t>")) // kept as written, not converted to a number
        assertFalse(sheet.contains("<f>")) // no invented totals
    }

    @Test
    fun formats_policy() {
        val prose = analyze("Just a few sentences of prose. Nothing structured here at all, really.")
        assertEquals(OfflineFormat.DOCX, OfflineFormat.default(prose))
        assertFalse(OfflineFormat.XLSX in OfflineFormat.available(prose)) // no table: no spreadsheet
        val table = analyze("| A | B |\n|---|---|\n| 1 | 2 |\n| 3 | 4 |")
        assertEquals(OfflineFormat.XLSX, OfflineFormat.default(table))
        // A presentation is offered but never chosen automatically.
        assertTrue(OfflineFormat.PPTX in OfflineFormat.available(prose))
        assertTrue(OfflineFormat.default(table) != OfflineFormat.PPTX)
    }

    // ------------------------------------------------------------------ cancellation and errors

    @Test
    fun cancellation_deletesTheUnfinishedFile() = runBlocking {
        val dir = tmp.newFolder("cancel")
        val slow = OfflineDocumentGenerator(dir, renderer = { _, _, _, out ->
            out.writeText("partial")
            Thread.sleep(10_000)
            false
        })
        val job = async(Dispatchers.Default) { slow.generate(analyze(), OfflineFormat.DOCX, builtIn()) }
        withTimeout(5_000) { while (dir.listFiles().isNullOrEmpty()) delay(10) }
        job.cancel()
        runCatching { job.await() }
        withTimeout(5_000) { while (!dir.listFiles().isNullOrEmpty()) delay(10) }
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test
    fun invalidOutput_isReportedAndDeleted() = runBlocking {
        val dir = tmp.newFolder("bad")
        val broken = OfflineDocumentGenerator(dir, renderer = { _, _, _, out -> out.writeText("not a zip"); false })
        try {
            broken.generate(analyze(), OfflineFormat.DOCX, builtIn())
            fail("expected an error")
        } catch (e: OfflineGenerationException) {
            assertTrue(dir.listFiles()!!.isEmpty())
        }
        val empty = runBlocking { RuleBasedFormattingEngine().analyze("   ") }
        try {
            generator().generate(empty, OfflineFormat.DOCX, builtIn())
            fail("expected an error for empty content")
        } catch (e: OfflineGenerationException) {
        }
    }

    // ------------------------------------------------------------------ controller flow

    private class MemoryLibrary : OfflineLibrary {
        val saved = mutableListOf<OfflineDraft>()
        override suspend fun save(draft: OfflineDraft): DocumentEntity {
            saved.add(draft)
            return DocumentEntity(
                id = "doc", title = draft.title + "." + draft.format.extension, fileType = draft.format.name,
                filePath = draft.file.path, fileSize = draft.file.length(), wordCount = draft.wordCount,
                pageOrSlideCount = draft.pageOrSlideCount, providerUsed = "Offline"
            )
        }
    }

    private val emptyRepo = object : TemplateRepository {
        override suspend fun findById(id: String): TemplateEntity? = null
        override suspend fun defaultFor(templateType: String): TemplateEntity? = null
        override suspend fun all(): List<TemplateEntity> = emptyList()
    }

    @Test
    fun controller_pastePreviewApplySave() = runBlocking {
        val drafts = tmp.newFolder("flow")
        val library = MemoryLibrary()
        val messages = java.util.concurrent.CopyOnWriteArrayList<OfflineMessage>()
        val c = OfflineFormattingController(
            scope = scope,
            matcher = TemplateMatcher(emptyRepo),
            generator = OfflineDocumentGenerator(drafts, EngineDocumentRenderer()),
            library = library,
            onMessage = { messages.add(it) }
        )
        val pasted = sample + "\n\n" + "Additional context sentence for length. ".repeat(10)
        c.onComposerTextChanged("", pasted, inputEnabled = true)
        assertEquals(pasted, c.state.value.suggestion!!.text)
        // Nothing is analyzed before the user asks.
        assertEquals(OfflinePhase.IDLE, c.state.value.phase)

        c.formatOffline(chatTemplateId = null)
        val preview = withTimeout(10_000) { c.state.first { it.phase == OfflinePhase.PREVIEW } }
        assertEquals(OfflineFormat.DOCX, preview.format)
        assertEquals(TemplateSource.BUILT_IN, preview.template!!.source)
        assertTrue(preview.template!!.notes.first() is TemplateNote.NoTemplate)
        assertTrue(preview.preview!!.inferredCount >= 1)
        // Nothing was written yet.
        assertTrue(drafts.listFiles().isNullOrEmpty())

        c.applyFormatting()
        val ready = withTimeout(10_000) { c.state.first { it.phase == OfflinePhase.READY } }
        assertTrue(ready.draft!!.file.isFile)
        assertTrue(library.saved.isEmpty()) // not saved before approval

        c.saveToLibrary()
        withTimeout(10_000) { c.state.first { it.phase == OfflinePhase.IDLE } }
        assertEquals(1, library.saved.size)
        assertTrue(messages.last() is OfflineMessage.Saved)
    }

    @Test
    fun controller_cancelAfterGenerationDeletesTheDraft_andDismissDoesNotReshow() = runBlocking {
        val drafts = tmp.newFolder("cancelflow")
        val library = MemoryLibrary()
        val c = OfflineFormattingController(scope, TemplateMatcher(emptyRepo), OfflineDocumentGenerator(drafts), library)
        val pasted = "A".repeat(10) + " " + "Long pasted sentence number one, with details. ".repeat(15)

        c.onComposerTextChanged("", pasted, inputEnabled = true)
        c.dismissSuggestion()
        assertNull(c.state.value.suggestion)
        // The same text pasted again (after clearing) does not bring the suggestion back.
        c.onComposerTextChanged(pasted, "", inputEnabled = true)
        c.onComposerTextChanged("", pasted, inputEnabled = true)
        assertNull(c.state.value.suggestion)

        val other = pasted + " Another distinct paragraph."
        c.onComposerTextChanged("", other, inputEnabled = true)
        c.formatOffline(null)
        withTimeout(10_000) { c.state.first { it.phase == OfflinePhase.PREVIEW } }
        c.applyFormatting()
        val ready = withTimeout(10_000) { c.state.first { it.phase == OfflinePhase.READY } }
        val file = ready.draft!!.file
        c.cancel()
        assertFalse(file.exists())
        assertEquals(OfflinePhase.IDLE, c.state.value.phase)
        assertTrue(library.saved.isEmpty())
    }

    @Test
    fun controller_voiceInputAndTypingNeverShowTheSuggestion() {
        val c = OfflineFormattingController(scope, TemplateMatcher(emptyRepo), generator(), MemoryLibrary())
        c.onComposerTextChanged("", "x".repeat(900), inputEnabled = false)
        assertNull(c.state.value.suggestion)
        c.onComposerTextChanged("Hello", "Hello!", inputEnabled = true)
        assertNull(c.state.value.suggestion)
        // Sending clears the composer: the suggestion disappears with it.
        c.onComposerTextChanged("", "y".repeat(900), inputEnabled = true)
        assertNotNull(c.state.value.suggestion)
        c.onComposerTextChanged("y".repeat(900), "", inputEnabled = true)
        assertNull(c.state.value.suggestion)
    }

    /** A minimal Word template with its own style, header part and margins. */
    private fun writeDocxTemplate(file: File) {
        val entries = linkedMapOf(
            "[Content_Types].xml" to """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/><Override PartName="/word/header1.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.header+xml"/></Types>""",
            "_rels/.rels" to """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>""",
            "word/_rels/document.xml.rels" to """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/header" Target="header1.xml"/></Relationships>""",
            "word/styles.xml" to """<?xml version="1.0" encoding="UTF-8"?><w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:style w:type="paragraph" w:styleId="Normal"><w:name w:val="Normal"/><w:rPr><w:rFonts w:ascii="Georgia"/></w:rPr></w:style><w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/><w:rPr><w:color w:val="C00000"/></w:rPr></w:style><w:style w:type="paragraph" w:styleId="Title"><w:name w:val="Title"/></w:style><w:style w:type="paragraph" w:styleId="ListBullet"><w:name w:val="List Bullet"/></w:style></w:styles>""",
            "word/header1.xml" to """<?xml version="1.0" encoding="UTF-8"?><w:hdr xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:p><w:r><w:t>ACME HEADER</w:t></w:r></w:p></w:hdr>""",
            "word/document.xml" to """<?xml version="1.0" encoding="UTF-8"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><w:body><w:p><w:r><w:t>Sample template text</w:t></w:r></w:p><w:sectPr><w:headerReference w:type="default" r:id="rId2"/><w:pgMar w:top="2000" w:right="1000" w:bottom="2000" w:left="1000"/></w:sectPr></w:body></w:document>"""
        )
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            entries.forEach { (name, xml) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(xml.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }
}
