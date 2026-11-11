package com.example

import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.document.model.WordDocumentModel
import com.example.document.model.WordSection
import com.example.document.model.WordTable
import com.example.document.pdf.PdfEngine
import com.example.template.domain.TemplateProfile
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Renders long content with the real Android PdfDocument and checks it is paginated instead of
 * being drawn past the bottom of page 1 (previous behaviour: a single page, content lost).
 */
@RunWith(AndroidJUnit4::class)
class PdfEngineMultiPageTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun pageCount(file: File): Int =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { it.pageCount }
        }

    @Test
    fun singleHugeParagraphSpansManyPages() {
        // Library export puts the whole document text into the PDF; ~1200 lines of body text.
        val text = (1..1200).joinToString("\n") { "Line $it — سطر رقم $it of the exported Library document." }
        val out = File(context.cacheDir, "multi_page_paragraph.pdf").apply { delete() }

        PdfEngine().generatePdf(
            WordDocumentModel(title = "Long export", sections = listOf(WordSection("Document Content", listOf(text)))),
            out,
            TemplateProfile.defaultProfile()
        )

        // ~14pt per line, ~700pt usable per page -> well over 15 pages.
        assertTrue("expected many pages, got ${pageCount(out)}", pageCount(out) >= 15)
    }

    @Test
    fun longTableContinuesOnFollowingPages() {
        val rows = (1..300).map { listOf("Row $it", "$it", "بند $it") }
        val out = File(context.cacheDir, "multi_page_table.pdf").apply { delete() }

        PdfEngine().generatePdf(
            WordDocumentModel(title = "Table", table = WordTable(listOf("Item", "Qty", "الوصف"), rows)),
            out,
            TemplateProfile.defaultProfile()
        )

        // 300 rows x 22pt = 6600pt -> at least 9 pages.
        assertTrue("expected table to paginate, got ${pageCount(out)}", pageCount(out) >= 9)
    }

    @Test
    fun shortDocumentStaysOnOnePage() {
        val out = File(context.cacheDir, "single_page.pdf").apply { delete() }
        PdfEngine().generatePdf(
            WordDocumentModel(title = "Short", sections = listOf(WordSection("Intro", listOf("Hello")))),
            out,
            TemplateProfile.defaultProfile()
        )
        assertTrue(pageCount(out) == 1)
    }
}
