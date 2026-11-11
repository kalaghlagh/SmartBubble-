package com.example

import com.example.core.storage.DocumentFileNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DocumentFileNamesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun arabicAndOtherUnicodeTitlesArePreserved() {
        assertEquals("تقرير_الربع_الأول", DocumentFileNames.sanitizeBaseName("تقرير الربع الأول"))
        assertEquals("Rapport_d'activité_été", DocumentFileNames.sanitizeBaseName("Rapport d'activité été"))
        assertEquals("報告書", DocumentFileNames.sanitizeBaseName("報告書"))
    }

    @Test
    fun pathSeparatorsAndReservedCharactersAreReplaced() {
        val name = DocumentFileNames.sanitizeBaseName("Q1/Q2 \\ Budget: \"final\" <v2>? *|")
        assertFalse(name.contains('/'))
        assertFalse(name.contains('\\'))
        listOf(':', '"', '<', '>', '?', '*', '|').forEach { assertFalse("contains $it", name.contains(it)) }
        assertEquals("Q1_Q2_Budget_final_v2", name)
    }

    @Test
    fun traversalAndDotOnlyTitlesCannotEscapeTheDirectory() {
        assertEquals("Document", DocumentFileNames.sanitizeBaseName(".."))
        assertEquals("Document", DocumentFileNames.sanitizeBaseName("../"))
        assertEquals("etc_passwd", DocumentFileNames.sanitizeBaseName("../../etc/passwd"))
        assertEquals("Document", DocumentFileNames.sanitizeBaseName("   "))
    }

    @Test
    fun controlAndBidiOverrideCharactersAreRemoved() {
        // U+202E RIGHT-TO-LEFT OVERRIDE could make "report‮xcod.exe" look like "report.docx".
        val name = DocumentFileNames.sanitizeBaseName("report‮xcod\u0000line\nbreak")
        assertFalse(name.any { it == '‮' || it == '\u0000' || it == '\n' })
        assertEquals("reportxcod_line_break", name)
    }

    @Test
    fun longNamesAreTruncatedByUtf8BytesWithoutBreakingCharacters() {
        val arabic = "م".repeat(500) // 2 bytes each
        val name = DocumentFileNames.sanitizeBaseName(arabic)
        assertTrue(name.toByteArray(Charsets.UTF_8).size <= DocumentFileNames.MAX_BASE_NAME_BYTES)
        assertEquals(DocumentFileNames.MAX_BASE_NAME_BYTES / 2, name.length)

        val emoji = "😀".repeat(100) // surrogate pairs, 4 bytes each
        val e = DocumentFileNames.sanitizeBaseName(emoji)
        assertEquals(0, e.length % 2)
        assertTrue(e.toByteArray(Charsets.UTF_8).size <= DocumentFileNames.MAX_BASE_NAME_BYTES)
    }

    @Test
    fun identicalTitlesAlwaysGetDistinctPhysicalFiles() {
        val dir = tmp.newFolder("generated_docs")
        val files = (1..200).map { DocumentFileNames.createUniqueFile(dir, "Executive_Summary_Report", "docx") }
        assertEquals(200, files.map { it.absolutePath }.toSet().size)
        files.forEach {
            assertTrue(it.exists())
            assertEquals(dir.canonicalPath, it.parentFile!!.canonicalPath)
            assertTrue(it.name.startsWith("Executive_Summary_Report_"))
            assertTrue(it.name.endsWith(".docx"))
        }
    }

    @Test
    fun uniqueFileStaysInsideDirectoryForHostileTitles() {
        val dir = tmp.newFolder("docs")
        val f = DocumentFileNames.createUniqueFile(dir, "../../outside/تقرير/..", ".PDF")
        assertEquals(dir.canonicalPath, f.parentFile!!.canonicalPath)
        assertTrue(f.name.endsWith(".pdf"))
        assertTrue(f.name.contains("تقرير"))
    }

    @Test
    fun deletingOneDocumentFileNeverAffectsAnother() {
        val dir = tmp.newFolder("lib")
        val a = DocumentFileNames.createUniqueFile(dir, "Same Title", "docx").apply { writeText("A") }
        val b = DocumentFileNames.createUniqueFile(dir, "Same Title", "docx").apply { writeText("B") }
        assertTrue(a.delete())
        assertTrue(b.exists())
        assertEquals("B", b.readText())
    }

    @Test
    fun displayTitleKeepsUnicodeAndSlashButDropsControlCharacters() {
        assertEquals("تقرير 2024/2025", DocumentFileNames.displayTitle("  تقرير\n2024/2025 ‮ "))
        assertEquals("Document", DocumentFileNames.displayTitle(" \t "))
    }

    @Test
    fun pdfExportFileNameIsSafeForTitlesWithSlashesAndUnicode() {
        val id = "3f2a9c1e-77aa-4b1d-9e2f-0123456789ab"
        assertEquals("Q1_Q2_Budget_3f2a9c1e.pdf", DocumentFileNames.pdfExportFileName("Q1/Q2 Budget.docx", id))
        assertEquals("تقرير_2024_2025_3f2a9c1e.pdf", DocumentFileNames.pdfExportFileName("تقرير 2024/2025.DOCX", id))
        assertEquals("Document_3f2a9c1e.pdf", DocumentFileNames.pdfExportFileName("///", id))
        assertEquals("Report.v2_3f2a9c1e.pdf", DocumentFileNames.pdfExportFileName("Report.v2.pptx", id))
        // The export can be created inside the export directory without throwing.
        val dir = tmp.newFolder("pdf_exports")
        val f = File(dir, DocumentFileNames.pdfExportFileName("a/b\\c:d*e?.xlsx", id))
        assertTrue(f.createNewFile())
        assertEquals(dir.canonicalPath, f.parentFile!!.canonicalPath)
    }

    @Test
    fun pdfExportNamesDifferPerDocumentEvenWithSameTitle() {
        assertFalse(
            DocumentFileNames.pdfExportFileName("Same.docx", "aaaaaaaa-1") ==
                DocumentFileNames.pdfExportFileName("Same.docx", "bbbbbbbb-2")
        )
    }

    @Test
    fun stripDocumentExtensionOnlyRemovesKnownExtensions() {
        assertEquals("Report.v2", DocumentFileNames.stripDocumentExtension("Report.v2.docx"))
        assertEquals("v1.2 notes", DocumentFileNames.stripDocumentExtension("v1.2 notes"))
    }

    @Test
    fun createsMissingDirectory() {
        val dir = File(tmp.root, "nested/new/dir")
        val f = DocumentFileNames.createUniqueFile(dir, "x", "pdf")
        assertTrue(f.exists())
    }
}
