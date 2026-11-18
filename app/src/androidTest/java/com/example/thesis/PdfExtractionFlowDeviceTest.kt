package com.example.thesis

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToString
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.MainActivity
import com.example.diagnostics.CrashLog
import com.example.extraction.ExtractionText
import com.example.extraction.PageSource
import com.example.thesis.model.AcademicLevel
import com.example.thesis.model.SectionKind
import com.example.thesis.model.ThesisMeta
import com.example.ui.SmartBubbleViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * The whole "Extract from PDF" workflow of Thesis Builder in the app, on a real Android runtime:
 * a 6-page PDF (pages 1-4 and 6 carry their text, page 5 is a scanned image) is opened in the
 * page viewer, pages are selected out of order (not consecutive), Next extracts them with the
 * app's own extraction (text pages read directly, the scanned page by PaddleOCR), the review
 * shows them in document order, Back keeps the selection without extracting again, a changed
 * selection is extracted anew, and Append / Insert at Cursor put exactly those pages into the
 * section being edited.
 */
@RunWith(AndroidJUnit4::class)
class PdfExtractionFlowDeviceTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val vm: SmartBubbleViewModel get() = ViewModelProvider(compose.activity)[SmartBubbleViewModel::class.java]
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val draftText: String get() = vm.thesis.state.value.draft!!.text

    @Before
    fun noOldCrashDialog() = CrashLog.clear(InstrumentationRegistry.getInstrumentation().targetContext)

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun diagnostics(): String = buildString {
        val st = vm.thesis.state.value
        append("pdf=${st.pdfToExtract} extraction=${st.extraction?.let { "running=${it.running} pages=${it.sources.map { s -> s.pages }} error=${it.error} doc=${it.document?.pages?.map { p -> p.sourcePage to p.source }}" }}\n")
        try { append(compose.onAllNodes(isRoot()).printToString(maxDepth = 20)) } catch (e: Throwable) { append("tree unavailable: $e") }
    }

    private fun await(step: String, timeout: Long = 20_000, condition: () -> Boolean) {
        try { compose.waitUntil(timeout) { condition() } } catch (e: Throwable) { throw AssertionError("Timed out: $step\n" + diagnostics(), e) }
    }

    private fun tap(tag: String) {
        await("node $tag") { exists(tag) }
        try { compose.onNodeWithTag(tag).performScrollTo() } catch (e: AssertionError) { }
        compose.onNodeWithTag(tag).performClick()
        compose.waitForIdle()
    }

    private fun tapPage(index: Int) {
        compose.onNodeWithTag("pdf_pages").performScrollToIndex(index)
        await("page $index") { exists("pdf_page_$index") }
        compose.onNodeWithTag("pdf_page_$index").performClick()
        compose.waitForIdle()
    }

    private fun assertPageSelected(index: Int, selected: Boolean) {
        compose.onNodeWithTag("pdf_pages").performScrollToIndex(index)
        await("page $index") { exists("pdf_page_$index") }
        if (selected) compose.onNodeWithTag("pdf_page_$index").assertIsSelected() else compose.onNodeWithTag("pdf_page_$index").assertIsNotSelected()
    }

    private fun line(n: Int) = "The wells of zone $n were sampled in March."

    /** Pages 1-6; page 5 only an image of its text (scanned), the others real PDF text. */
    private fun makePdf(): Uri {
        val dir = File(context.cacheDir, "flow-test").apply { deleteRecursively(); mkdirs() }
        val doc = PdfDocument()
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 14f }
        for (n in 1..6) {
            val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, n).create())
            if (n == 5) {
                // A scan: the text exists only as pixels.
                val bmp = Bitmap.createBitmap(1240, 1754, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
                val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 64f }
                Canvas(bmp).apply {
                    drawText("Scanned appendix about springs", 100f, 300f, big)
                    drawText("Water samples from the springs", 100f, 420f, big)
                }
                page.canvas.drawBitmap(bmp, null, RectF(0f, 0f, 595f, 842f), Paint(Paint.FILTER_BITMAP_FLAG))
                bmp.recycle()
            } else {
                page.canvas.drawText("Source page $n", 60f, 80f, text)
                page.canvas.drawText(line(n), 60f, 110f, text)
            }
            doc.finishPage(page)
        }
        val f = File(dir, "source.pdf")
        FileOutputStream(f).use { doc.writeTo(it) }
        doc.close()
        return Uri.fromFile(f)
    }

    /** Thesis tab, a new project, its Introduction open with "Existing paragraph." in the editor. */
    private fun openEditor() {
        await("app started") { exists("nav_thesis") }
        vm.setAppLanguage("en")
        await("english") { compose.onAllNodes(hasTestTag("nav_thesis") and hasText("Thesis")).fetchSemanticsNodes().isNotEmpty() }
        tap("nav_thesis")
        vm.thesis.createProject(ThesisMeta("PDF flow test", AcademicLevel.MASTER, "en", studentName = "Test Student"), aiAssist = false)
        await("project created") { vm.thesis.state.value.snapshot != null && vm.thesis.state.value.busy == null }
        val intro = vm.thesis.state.value.snapshot!!.sections.first { it.kind == SectionKind.INTRODUCTION }.id
        compose.runOnUiThread { vm.thesis.selectSection(intro) }
        await("editor") { exists("thesis_editor_text") && vm.thesis.state.value.draft != null }
        compose.onNodeWithTag("thesis_editor_text").performTextInput("Existing paragraph.")
        await("typed") { vm.thesis.state.value.draft?.text?.contains("Existing paragraph.") == true }
    }

    private fun openPdf(uri: Uri) {
        compose.runOnUiThread { vm.thesis.openPdfForExtraction(uri) }
        await("viewer") { exists("pdf_page_image_0") }
    }

    private fun words(s: String) = s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.toSet()

    @Test
    fun selectPages_next_review_back_changeSelection_append_thenInsertAtCursor() {
        openEditor()
        val uri = makePdf()
        openPdf(uri)
        // Nothing selected: Next disabled.
        compose.onNodeWithTag("pdf_next").assertIsNotEnabled()
        // Pages 5, 1, 3 (that order; not consecutive).
        for (i in listOf(4, 0, 2)) tapPage(i)
        compose.onNodeWithTag("pdf_selected_label").assertTextEquals(context.getString(com.example.R.string.dx_pdf_selected_count, 3))
        // Next: the review of these pages, extracted with the app's engine.
        tap("pdf_next")
        await("review", timeout = 180_000) { exists("dx_review") && vm.thesis.state.value.extraction?.document != null }
        val first = vm.thesis.state.value.extraction!!.document!!
        assertEquals("document order", listOf(0, 2, 4), first.pages.map { it.sourcePage })
        assertEquals("text pages read directly, the scan by OCR", listOf(PageSource.PDF_TEXT, PageSource.PDF_TEXT, PageSource.PDF_OCR), first.pages.map { it.source })
        assertTrue(ExtractionText.page(first.pages[0]).contains(line(1)))
        assertTrue(ExtractionText.page(first.pages[1]).contains(line(3)))
        val scanned = words(ExtractionText.page(first.pages[2]))
        val expected = words("Scanned appendix about springs Water samples from the springs")
        assertTrue("scanned page read by OCR: $scanned", scanned.intersect(expected).size >= expected.size * 7 / 10)
        compose.onNodeWithTag("dx_review_pages").assertTextEquals("Pages: 1, 3, 5")
        for (a in listOf("dx_insert_cursor", "dx_insert_append", "dx_insert_paragraphs", "dx_insert_heading", "dx_insert_tables", "dx_copy")) assertTrue("action $a", exists(a))
        // Back: the pages as selected, nothing extracted again.
        tap("dx_back")
        await("pages again") { exists("pdf_viewer") && !exists("dx_review") }
        assertSame(first, vm.thesis.state.value.extraction!!.document)
        for (i in 0 until 6) assertPageSelected(i, i in setOf(0, 2, 4))
        // Next with the same pages: the same review at once (no new extraction).
        tap("pdf_next")
        await("same review") { exists("dx_review") }
        assertSame(first, vm.thesis.state.value.extraction!!.document)
        // Back, another selection (page 5 out, page 4 in): extracted anew.
        tap("dx_back")
        await("pages") { exists("pdf_viewer") }
        tapPage(4)
        tapPage(3)
        tap("pdf_next")
        await("new review", timeout = 60_000) { vm.thesis.state.value.extraction?.document?.pages?.map { it.sourcePage } == listOf(0, 2, 3) }
        assertNotSame(first, vm.thesis.state.value.extraction!!.document)
        // Append: these pages, after the section's text, and the viewer closes.
        tap("dx_insert_append")
        await("inserted") { vm.thesis.state.value.extraction == null && vm.thesis.state.value.pdfToExtract == null && !exists("pdf_flow") }
        var text = draftText
        assertTrue(text, text.startsWith("Existing paragraph."))
        listOf(1, 3, 4).forEach { n -> assertEquals("zone $n once", 1, text.windowed(line(n).length).count { it == line(n) }) }
        assertTrue("pages not selected stay out: $text", !text.contains(line(2)) && !text.contains(line(6)) && !text.contains("Scanned appendix"))
        assertTrue("document order", text.indexOf(line(1)) < text.indexOf(line(3)) && text.indexOf(line(3)) < text.indexOf(line(4)))
        assertTrue("no page heading inserted", text.lines().none { it.trim() == "Page 1" || it.trim() == "Page 3" })
        // Insert at Cursor from a second run: page 2 only, in the same section.
        openPdf(uri)
        tapPage(1)
        tap("pdf_next")
        await("review 2", timeout = 60_000) { vm.thesis.state.value.extraction?.document?.pages?.map { it.sourcePage } == listOf(1) }
        tap("dx_insert_cursor")
        await("inserted 2") { vm.thesis.state.value.extraction == null && !exists("pdf_flow") }
        text = draftText
        assertEquals(1, text.windowed(line(2).length).count { it == line(2) })
        listOf(1, 3, 4).forEach { n -> assertEquals("zone $n still once", 1, text.windowed(line(n).length).count { it == line(n) }) }
        assertTrue("crash report written", CrashLog.read(context) == null)
    }

    @Test
    fun closingTheViewer_dropsItsExtraction_andReleasesThePdf() {
        openEditor()
        openPdf(makePdf())
        tapPage(0)
        tap("pdf_next")
        await("review", timeout = 60_000) { vm.thesis.state.value.extraction?.document != null }
        tap("dx_back")
        await("pages") { exists("pdf_viewer") }
        tap("pdf_close")
        await("closed") { !exists("pdf_flow") && vm.thesis.state.value.pdfToExtract == null && vm.thesis.state.value.extraction == null }
        await("copy released") { (File(context.cacheDir, "pdf-viewer").listFiles()?.size ?: 0) == 0 }
        assertEquals("Existing paragraph.", draftText.trim())
    }
}
