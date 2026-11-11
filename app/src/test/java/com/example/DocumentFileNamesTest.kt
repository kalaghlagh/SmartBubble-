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
    fun createsMissingDirectory() {
        val dir = File(tmp.root, "nested/new/dir")
        val f = DocumentFileNames.createUniqueFile(dir, "x", "pdf")
        assertTrue(f.exists())
    }
}
