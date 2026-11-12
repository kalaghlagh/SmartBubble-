package com.example.offline

import com.example.data.model.TemplateEntity
import com.example.offline.document.OfflineFormat
import com.example.offline.document.TemplateMatcher
import com.example.offline.document.TemplateNote
import com.example.offline.document.TemplateRepository
import com.example.offline.document.TemplateSource
import com.example.template.domain.TemplateProfile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Template selection priority (explicit > default > built-in) with an in-memory repository (fake). */
class OfflineTemplateMatcherTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeRepository(val templates: List<TemplateEntity>) : TemplateRepository {
        override suspend fun findById(id: String) = templates.firstOrNull { it.id == id }
        override suspend fun defaultFor(templateType: String) = templates.firstOrNull {
            when (templateType) {
                "PPTX" -> it.isDefaultPpt
                "XLSX" -> it.isDefaultExcel
                else -> it.isDefaultWord
            }
        }
        override suspend fun all() = templates
    }

    private fun file(name: String): File = tmp.newFile(name).apply { writeBytes(ByteArray(64) { 1 }) }

    private fun template(id: String, type: String, path: String, default: Boolean = false, profile: TemplateProfile? = null) =
        TemplateEntity(
            id = id, name = "Template $id", fileType = type, originalFilePath = path,
            profileJson = (profile ?: TemplateProfile.defaultProfile().copy(templateId = id, primaryColorHex = "#AA0000")).toJson(),
            isDefaultWord = default && type == "DOCX", isDefaultPpt = default && type == "PPTX", isDefaultExcel = default && type == "XLSX"
        )

    @Test
    fun explicitTemplateWins() = runBlocking {
        val explicit = template("e", "DOCX", file("e.docx").path)
        val default = template("d", "DOCX", file("d.docx").path, default = true)
        val m = TemplateMatcher(FakeRepository(listOf(explicit, default))).match(OfflineFormat.DOCX, "e")
        assertEquals(TemplateSource.EXPLICIT, m.source)
        assertEquals("e", m.entity!!.id)
        assertEquals("#AA0000", m.profile.primaryColorHex)
        assertEquals(explicit.originalFilePath, m.file!!.path)
        assertTrue(m.notes.isEmpty())
    }

    @Test
    fun defaultTemplateWhenNothingSelected() = runBlocking {
        val default = template("d", "PPTX", file("d.pptx").path, default = true)
        val m = TemplateMatcher(FakeRepository(listOf(default, template("w", "DOCX", file("w.docx").path, default = true))))
            .match(OfflineFormat.PPTX, null)
        assertEquals(TemplateSource.DEFAULT, m.source)
        assertEquals("d", m.entity!!.id)
    }

    @Test
    fun noTemplate_usesBuiltInProfileWithoutAddedHeaderOrFooterText() = runBlocking {
        val m = TemplateMatcher(FakeRepository(emptyList())).match(OfflineFormat.DOCX, null)
        assertEquals(TemplateSource.BUILT_IN, m.source)
        assertNull(m.entity)
        assertNull(m.file)
        assertEquals("", m.profile.headerText)
        assertEquals("", m.profile.footerText)
        assertEquals(listOf<TemplateNote>(TemplateNote.NoTemplate(OfflineFormat.DOCX)), m.notes)
    }

    @Test
    fun unsupportedOrWrongTypeTemplate_isNeverUsedSilently() = runBlocking {
        val pptx = template("p", "PPTX", file("p.pptx").path)
        val pdf = template("x", "PDF", file("x.pdf").path)
        val default = template("d", "DOCX", file("d.docx").path, default = true)
        val matcher = TemplateMatcher(FakeRepository(listOf(pptx, pdf, default)))

        val wrongType = matcher.match(OfflineFormat.DOCX, "p")
        assertEquals(TemplateSource.DEFAULT, wrongType.source)
        assertEquals("d", wrongType.entity!!.id)
        assertEquals(TemplateNote.Incompatible("Template p", "PPTX", OfflineFormat.DOCX), wrongType.notes.first())

        val unsupported = matcher.match(OfflineFormat.DOCX, "x")
        assertTrue(unsupported.notes.first() is TemplateNote.Incompatible)
        assertEquals("d", unsupported.entity!!.id)

        // The picker only lists templates of the right type.
        assertEquals(listOf("d"), matcher.options(OfflineFormat.DOCX).map { it.id })
        assertFalse(TemplateMatcher.isCompatible(pdf, OfflineFormat.PDF))
    }

    @Test
    fun missingTemplateFile_keepsProfileAndReportsIt() = runBlocking {
        val missing = template("m", "DOCX", File(tmp.root, "gone.docx").path)
        val m = TemplateMatcher(FakeRepository(listOf(missing))).match(OfflineFormat.DOCX, "m")
        assertEquals(TemplateSource.EXPLICIT, m.source)
        assertNull(m.file)
        assertEquals("#AA0000", m.profile.primaryColorHex)
        assertEquals(listOf<TemplateNote>(TemplateNote.FileMissing("Template m")), m.notes)
    }

    @Test
    fun deletedExplicitTemplate_andExplicitBuiltIn() = runBlocking {
        val default = template("d", "XLSX", file("d.xlsx").path, default = true)
        val matcher = TemplateMatcher(FakeRepository(listOf(default)))
        val deleted = matcher.match(OfflineFormat.XLSX, "deleted")
        assertEquals(TemplateNote.NotFound, deleted.notes.first())
        assertEquals("d", deleted.entity!!.id)

        val builtIn = matcher.match(OfflineFormat.XLSX, TemplateMatcher.BUILT_IN_ID)
        assertEquals(TemplateSource.BUILT_IN, builtIn.source)
        assertTrue(builtIn.notes.isEmpty())
    }

    @Test
    fun pdfUsesTheWordTemplateProfileOnly_andStyleOnlyIsReported() = runBlocking {
        val word = template("w", "DOCX", file("w.docx").path, default = true)
        val pdf = TemplateMatcher(FakeRepository(listOf(word))).match(OfflineFormat.PDF, null)
        assertEquals("w", pdf.entity!!.id)
        assertNull(pdf.file)
        assertEquals(listOf<TemplateNote>(TemplateNote.PdfProfileOnly), pdf.notes)

        val styleOnly = template("s", "DOCX", file("s.docx").path, profile = TemplateProfile.defaultProfile().copy(useTemplateLayout = false))
        val m = TemplateMatcher(FakeRepository(listOf(styleOnly))).match(OfflineFormat.DOCX, "s")
        assertEquals(listOf<TemplateNote>(TemplateNote.StyleOnly("Template s")), m.notes)
    }
}
