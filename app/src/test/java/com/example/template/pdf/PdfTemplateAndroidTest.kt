package com.example.template.pdf

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.test.core.app.ApplicationProvider
import com.example.R
import com.example.data.db.SmartBubbleDatabase
import com.example.data.model.TemplateEntity
import com.example.document.docx.DocxEngine
import com.example.document.model.WordDocumentModel
import com.example.document.model.WordSection
import com.example.document.pdf.PdfEngine
import com.example.template.domain.TemplateProfile
import com.example.template.preview.TemplatePreviewData
import com.example.template.preview.TemplatePreviewEngine
import com.example.template.preview.TemplatePreviewResult
import com.example.template.storage.TemplateImportException
import com.example.template.storage.TemplateImporter
import com.example.template.storage.TemplateThumbnails
import com.example.ui.components.PdfTemplatePreview
import com.example.ui.screens.TemplateItemCard
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

/**
 * PDF templates on Robolectric: import into private storage and the real (in-memory) Room
 * template table, default flags, preview, PDF output with a PDF template, first-page rendering,
 * and the My Templates UI pieces with the real resources in English, French and Arabic.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PdfTemplateAndroidTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: SmartBubbleDatabase
    private lateinit var templatesDir: File
    private lateinit var importer: TemplateImporter

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, SmartBubbleDatabase::class.java).allowMainThreadQueries().build()
        templatesDir = tmp.newFolder("templates")
        importer = TemplateImporter(templatesDir, db.templateDao())
    }

    @After
    fun tearDown() = db.close()

    private suspend fun import(bytes: ByteArray, name: String, fileName: String): TemplateEntity =
        importer.import({ ByteArrayInputStream(bytes) }, name, fileName)

    // ------------------------------------------------------------------ import

    @Test
    fun pdfImport_copiesTheOriginalIntoPrivateStorageAndRegistersIt() = runBlocking {
        val bytes = TestPdf.corporateTemplate()
        val entity = import(bytes, "Corporate letter", "Corporate Letter.pdf")
        assertEquals("PDF", entity.fileType)
        assertEquals("Corporate letter", entity.name)
        assertEquals("Compatible", entity.compatibilityStatus)
        val stored = File(entity.originalFilePath)
        assertEquals(templatesDir.canonicalPath, stored.parentFile!!.canonicalPath)
        assertTrue(stored.name.endsWith(".pdf"))
        assertArrayEquals(bytes, stored.readBytes()) // the original is kept unchanged
        val row = db.templateDao().getTemplateById(entity.id)!!
        val profile = TemplateProfile.fromJson(row.profileJson)
        assertEquals("Corporate Letter.pdf", profile.sourceFileName)
        assertTrue(profile.isPdfLayout)
        assertEquals(595, profile.pageWidthPt)
        assertEquals(bytes.size.toLong(), row.fileSize)
    }

    @Test
    fun scannedPdf_isKeptAndMarkedForReview() = runBlocking {
        val entity = import(TestPdf.scannedPage(), "Scan", "scan.pdf")
        assertEquals("Review", entity.compatibilityStatus)
        assertTrue(TemplateProfile.fromJson(entity.profileJson).isImageOnlyPdf)
        assertTrue(File(entity.originalFilePath).isFile)
    }

    @Test
    fun encryptedDamagedUnsupportedAndTooLargeFiles_areRefusedCleanly() = runBlocking {
        val encrypted = TestPdf().apply { encrypt = true; page("BT /F1 12 Tf 72 700 Td (x) Tj ET") }.build()
        val cases = listOf(
            encrypted to TemplateImportException.Reason.PDF_ENCRYPTED,
            "%PDF-1.7\n1 0 obj << /Type /Catalog >> endobj\n".toByteArray() to TemplateImportException.Reason.PDF_DAMAGED,
            "just some text".toByteArray() to TemplateImportException.Reason.UNSUPPORTED
        )
        for ((bytes, reason) in cases) {
            try {
                import(bytes, "x", "x.pdf")
                fail("expected $reason")
            } catch (e: TemplateImportException) {
                assertEquals(reason, e.reason)
            }
        }
        // More than 50 MB: the copy stops at the limit.
        val huge = object : InputStream() {
            var left = 50L * 1024 * 1024 + 10
            override fun read(): Int = if (left-- > 0) 'x'.code else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (left <= 0) return -1
                val n = minOf(len.toLong(), left).toInt()
                java.util.Arrays.fill(b, off, off + n, 'x'.code.toByte())
                left -= n
                return n
            }
        }
        try {
            importer.import({ huge }, "big", "big.pdf")
            fail("expected TOO_LARGE")
        } catch (e: TemplateImportException) {
            assertEquals(TemplateImportException.Reason.TOO_LARGE, e.reason)
        }
        // Nothing was stored or registered.
        assertTrue(templatesDir.listFiles()!!.isEmpty())
        assertEquals(0, db.templateDao().countTemplates())
    }

    @Test
    fun docxImport_stillWorks() = runBlocking {
        val docx = tmp.newFile("t.docx")
        DocxEngine().generateDocx(WordDocumentModel(title = "T", sections = listOf(WordSection("H", listOf("p")))), docx)
        val entity = import(docx.readBytes(), "Word", "Word.docx")
        assertEquals("DOCX", entity.fileType)
        assertFalse(TemplateProfile.fromJson(entity.profileJson).isPdfLayout)
    }

    // ------------------------------------------------------------------ defaults, duplicate, delete

    @Test
    fun pdfDefaultFlag_isExclusiveAndIndependentOfOtherTypes() = runBlocking {
        val a = import(TestPdf.corporateTemplate(), "A", "a.pdf")
        val b = import(TestPdf.corporateTemplate(landscape = true), "B", "b.pdf")
        val dao = db.templateDao()
        dao.insertTemplate(TemplateEntity(id = "w", name = "W", fileType = "DOCX", originalFilePath = "", profileJson = "{}", isDefaultWord = true))
        dao.clearDefaultPdf(); dao.setDefaultPdfFlag(a.id, true)
        assertEquals(a.id, dao.getDefaultPdfTemplate()!!.id)
        dao.clearDefaultPdf(); dao.setDefaultPdfFlag(b.id, true)
        assertEquals(b.id, dao.getDefaultPdfTemplate()!!.id)
        assertFalse(dao.getTemplateById(a.id)!!.isDefaultPdf)
        assertEquals("w", dao.getDefaultWordTemplate()!!.id)
        dao.setDefaultPdfFlag(b.id, false)
        assertNull(dao.getDefaultPdfTemplate())

        // Duplicate = independent copy of the file and row; delete removes only that one.
        val copy = File(templatesDir, "copy.pdf").also { File(a.originalFilePath).copyTo(it) }
        dao.insertTemplate(a.copy(id = "dup", name = "A (copy)", originalFilePath = copy.path, isDefaultPdf = false))
        dao.deleteTemplateById("dup")
        copy.delete()
        assertTrue(File(a.originalFilePath).isFile)
        assertEquals(3, dao.countTemplates())
    }

    // ------------------------------------------------------------------ preview and rendering

    @Test
    fun preview_usesTheStoredProfileAndReportsAMissingFile() = runBlocking {
        val entity = import(TestPdf.corporateTemplate(pages = 2), "P", "p.pdf")
        val ok = TemplatePreviewEngine().loadPreview(entity) as TemplatePreviewResult.Success
        val data = ok.data as TemplatePreviewData.PdfPreview
        assertEquals(2, data.pageCount)
        assertEquals("ACME Consulting Group", data.profile.headerText)

        File(entity.originalFilePath).delete()
        val missing = TemplatePreviewEngine().loadPreview(entity) as TemplatePreviewResult.Fallback
        assertTrue((missing.data as TemplatePreviewData.PdfPreview).fileMissing)
    }

    @Test
    fun pdfOutput_followsThePdfTemplatePageSize() {
        for (landscape in listOf(false, true)) {
            val profile = PdfTemplateAnalyzer.analyze(PdfDocumentReader.open(TestPdf.corporateTemplate(landscape = landscape))).toProfile("x", "X", "x.pdf")
            val out = tmp.newFile("out$landscape.pdf")
            try {
                PdfEngine().generatePdf(WordDocumentModel(title = "Report", organization = "", sections = listOf(WordSection("Heading", listOf("Body text ".repeat(40)), listOf("one", "two")))), out, profile)
            } catch (e: Throwable) {
                Assume.assumeNoException("PDF rendering is not available in this test runtime", e)
            }
            // Read the generated file back with the app's own PDF reader.
            val page = PdfDocumentReader.open(out).pages().first()
            assertEquals(if (landscape) 842.0 else 595.0, page.width, 0.5)
            assertEquals(if (landscape) 595.0 else 842.0, page.height, 0.5)
        }
    }

    @Test
    fun firstPageThumbnail_whenRenderingIsAvailable() {
        val pdf = tmp.newFile("thumb.pdf").apply { writeBytes(TestPdf.corporateTemplate()) }
        val dest = TemplateThumbnails.fileFor(templatesDir, "t1")
        val rendered = TemplateThumbnails.renderPdfFirstPage(pdf, dest)
        Assume.assumeTrue("PdfRenderer is not available in this test runtime", rendered)
        assertTrue(dest.length() > 0)
        // A damaged file never crashes the renderer.
        assertFalse(TemplateThumbnails.renderPdfFirstPage(tmp.newFile("bad.pdf").apply { writeText("%PDF-broken") }, File(templatesDir, "b.png")))
    }

    // ------------------------------------------------------------------ UI

    private fun pdfEntity(bytes: ByteArray = TestPdf.corporateTemplate(), default: Boolean = false): TemplateEntity {
        val file = tmp.newFile("e${System.nanoTime()}.pdf").apply { writeBytes(bytes) }
        val profile = PdfTemplateAnalyzer.analyze(file).toProfile("e1", "Corporate", "Corporate Letter.pdf")
        return TemplateEntity(id = "e1", name = "Corporate", fileType = "PDF", originalFilePath = file.path, profileJson = profile.toJson(), fileSize = file.length(), isDefaultPdf = default)
    }

    @Test
    @Config(qualifiers = "en")
    fun templateCard_showsPdfTypeFileNameDefaultBadgeAndMenu() {
        var deleted = 0
        compose.setContent {
            TemplateItemCard(
                template = pdfEntity(default = true), onPreview = {}, onDuplicate = {}, onSetDefault = {},
                onRemoveDefault = {}, onDelete = { deleted++ }, onSelectForChat = {}
            )
        }
        compose.onNodeWithText("PDF", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("template_filename_e1", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Corporate Letter.pdf", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("default_badge_e1", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("template_menu_e1").performClick()
        compose.onNodeWithTag("menu_preview_e1").assertIsDisplayed()
        compose.onNodeWithTag("menu_duplicate_e1").assertIsDisplayed()
        compose.onNodeWithTag("menu_remove_default_e1").assertIsDisplayed()
        // Office generation settings do not apply to a PDF template.
        compose.onNodeWithTag("menu_options_e1").assertDoesNotExist()
        compose.onNodeWithTag("menu_delete_e1").performClick()
        assertEquals(1, deleted)
    }

    @Test
    @Config(qualifiers = "en")
    fun pdfPreview_englishShowsMeasuredValuesAndOpensTheOriginal() {
        var opened = 0
        val entity = pdfEntity()
        val data = TemplatePreviewData.PdfPreview(TemplateProfile.fromJson(entity.profileJson), 1)
        compose.setContent { Column(Modifier.verticalScroll(rememberScrollState())) { PdfTemplatePreview(data, null) { opened++ } } }
        compose.onNodeWithText("Extracted formatting").assertExists()
        compose.onNodeWithText("A4 · Portrait · 595 × 842 pt", substring = true, useUnmergedTree = true).assertExists()
        compose.onNodeWithText("ACME Consulting Group", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Times · 11 pt", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("pdf_open_original").performClick()
        assertEquals(1, opened)
    }

    @Test
    @Config(qualifiers = "fr")
    fun pdfPreview_french() {
        val data = TemplatePreviewData.PdfPreview(TemplateProfile.fromJson(pdfEntity().profileJson), 1)
        compose.setContent { Column(Modifier.verticalScroll(rememberScrollState())) { PdfTemplatePreview(data, null, null) } }
        compose.onNodeWithText("Mise en forme extraite").assertExists()
        compose.onNodeWithText("Marges", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("pdf_open_original").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "ar-w360dp-h800dp")
    fun pdfPreview_arabicScannedTemplateIsReportedRightToLeft() {
        val data = TemplatePreviewData.PdfPreview(TemplateProfile.fromJson(pdfEntity(TestPdf.scannedPage()).profileJson), 1)
        compose.setContent { Column(Modifier.verticalScroll(rememberScrollState())) { PdfTemplatePreview(data, null, null) } }
        val message = context.getString(R.string.pdf_no_text_detail)
        assertTrue(message.startsWith("لا يحتوي"))
        compose.onNodeWithTag("pdf_text_status").assertExists()
        compose.onNodeWithText("التنسيق المستخرج").assertExists()
        // RTL: the row label starts on the right half.
        val row = compose.onNodeWithTag("pdf_row_page").fetchSemanticsNode().boundsInRoot
        val label = compose.onNodeWithText("الصفحة", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("label not on the right: $label in $row", label.left > row.center.x)
    }
}
