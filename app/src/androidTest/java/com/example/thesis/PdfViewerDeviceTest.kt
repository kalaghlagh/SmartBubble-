package com.example.thesis

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.thesis.ui.PdfPageMark
import com.example.thesis.ui.PdfPageViewer
import com.example.ui.pdf.PdfPageRenderer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

/**
 * The PDF page viewer of Thesis Builder on a real Android runtime: a 25-page PDF made on the
 * device (A4 portrait and landscape pages alternating, each with a black block at a known place)
 * is drawn by the platform renderer; the drawn pages hold that content, keep their proportions,
 * are drawn lazily into a bounded cache and released on close; the tapped page is the one
 * selected and the one handed to extraction.
 */
@RunWith(AndroidJUnit4::class)
class PdfViewerDeviceTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val viewerDir get() = File(context.cacheDir, "pdf-viewer")
    private lateinit var pdf: File

    private fun portrait(i: Int) = i % 2 == 0

    @Before
    fun makePdf() {
        viewerDir.deleteRecursively()
        pdf = File(context.cacheDir, "viewer-test.pdf")
        val doc = PdfDocument()
        val black = Paint().apply { color = Color.BLACK }
        val text = Paint().apply { color = Color.BLACK; textSize = 18f }
        for (i in 0 until PAGES) {
            val (w, h) = if (portrait(i)) 595 to 842 else 842 to 595
            val page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, i + 1).create())
            // Portrait: block in the upper left; landscape: block in the lower right.
            if (portrait(i)) page.canvas.drawRect(w * 0.1f, h * 0.1f, w * 0.4f, h * 0.3f, black)
            else page.canvas.drawRect(w * 0.6f, h * 0.7f, w * 0.9f, h * 0.9f, black)
            page.canvas.drawText("Page ${i + 1}", w * 0.45f, h * 0.5f, text)
            doc.finishPage(page)
        }
        FileOutputStream(pdf).use { doc.writeTo(it) }
        doc.close()
    }

    private fun dark(b: Bitmap, l: Float, t: Float, r: Float, btm: Float): Double {
        var n = 0; var all = 0
        for (y in (t * b.height).toInt() until (btm * b.height).toInt() step 2) for (x in (l * b.width).toInt() until (r * b.width).toInt() step 2) {
            val c = b.getPixel(x, y); all++
            if ((Color.red(c) + Color.green(c) + Color.blue(c)) / 3 < 100) n++
        }
        return n.toDouble() / all
    }

    @Test
    fun renderer_drawsTheRealPages_proportionsKept_cacheBounded_releasedOnClose() = runBlocking {
        val r = checkNotNull(PdfPageRenderer.open(context, Uri.fromFile(pdf))) { "PDF not opened" }
        assertEquals(PAGES, r.pageCount)
        assertEquals(595 to 842, r.pageSizes[0])
        assertEquals(842 to 595, r.pageSizes[1])
        val p0 = r.render(0, 1080)!!
        assertEquals(1080, p0.width)
        assertTrue("portrait ratio ${p0.width}x${p0.height}", abs(p0.width.toDouble() / p0.height - 595.0 / 842) < 0.01)
        // The block is drawn where the page has it, the rest of the page stays white.
        assertTrue("block drawn", dark(p0, 0.12f, 0.12f, 0.38f, 0.28f) > 0.95)
        assertTrue("page white elsewhere", dark(p0, 0.55f, 0.65f, 0.95f, 0.95f) < 0.01)
        val p1 = r.render(1, 1080)!!
        assertTrue("landscape ratio", abs(p1.width.toDouble() / p1.height - 842.0 / 595) < 0.01)
        assertTrue("landscape block", dark(p1, 0.62f, 0.72f, 0.88f, 0.88f) > 0.95)
        assertTrue("landscape white elsewhere", dark(p1, 0.05f, 0.05f, 0.4f, 0.4f) < 0.01)
        // Same page, same width: taken from the cache, not drawn again.
        assertTrue(p0 === r.render(0, 1080))
        // Every page drawn: the cache never holds more than its limit.
        val start = System.nanoTime()
        for (i in 0 until PAGES) { assertNotNull(r.render(i, 1080)); assertTrue("cache ${r.cachedBytes} > ${r.cacheLimitBytes}", r.cachedBytes <= r.cacheLimitBytes) }
        android.util.Log.i("PdfViewerDeviceTest", "$PAGES pages drawn in ${(System.nanoTime() - start) / 1_000_000} ms, cache ${r.cachedBytes / 1024} KB of ${r.cacheLimitBytes / 1024} KB")
        assertNull("bad index", r.render(PAGES, 1080))
        assertEquals(1, viewerDir.listFiles()!!.size)
        r.close()
        assertNull("closed renderer draws nothing", r.render(2, 1080))
        assertEquals(0, r.cachedBytes)
        assertEquals("copy deleted", 0, viewerDir.listFiles()!!.size)
        r.close() // twice: no error
    }

    @Test
    fun renderer_damagedOrEmptyFile_isNull_leavesNoCopy() = runBlocking {
        val bad = File(context.cacheDir, "damaged.pdf").apply { writeText("%PDF-1.4 not really a pdf") }
        assertNull(PdfPageRenderer.open(context, Uri.fromFile(bad)))
        val empty = File(context.cacheDir, "empty.pdf").apply { writeBytes(ByteArray(0)) }
        assertNull(PdfPageRenderer.open(context, Uri.fromFile(empty)))
        assertNull(PdfPageRenderer.open(context, Uri.fromFile(File(context.cacheDir, "missing.pdf"))))
        assertEquals(0, viewerDir.listFiles()?.size ?: 0)
    }

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun await(tag: String) = compose.waitUntil(20_000) { exists(tag) }

    @Test
    fun viewer_showsPages_lazily_selectsTheTappedPage_extractsIt_releasesOnClose() {
        val extracted = mutableListOf<Int>()
        var open by mutableStateOf(true)
        var marks by mutableStateOf<Map<Int, PdfPageMark>>(emptyMap())
        compose.setContent {
            MaterialTheme {
                if (open) PdfPageViewer(Uri.fromFile(pdf), "PDF", marks, onExtract = { extracted += it }, onClose = { open = false })
            }
        }
        await("pdf_page_image_0")
        // Lazy: the last page is not drawn before it is scrolled to.
        assertTrue(!exists("pdf_page_image_${PAGES - 1}"))
        // Page 1 selected by default.
        compose.onNodeWithTag("pdf_page_0").assertIsSelected()
        assertTrue(exists("pdf_selected_badge_0"))
        // Portrait and landscape pages fill the width, their height in their own proportions.
        // (Measured on the laid-out page once scrolled into view: boundsInRoot alone is clipped
        // to the visible part of the list, which is what run #79 measured: 995x213.)
        assertShownInProportion(0, 595, 842)
        assertShownInProportion(1, 842, 595)
        // Tap page 4: it is selected (outlined, badge), page 1 no longer is, and it is extracted.
        compose.onNodeWithTag("pdf_pages").performScrollToIndex(3)
        await("pdf_page_3")
        compose.onNodeWithTag("pdf_page_3").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("pdf_page_3").assertIsSelected()
        assertTrue(exists("pdf_selected_badge_3"))
        assertTrue(!exists("pdf_selected_badge_0"))
        await("pdf_page_image_3")
        compose.onNodeWithTag("pdf_extract").performClick()
        compose.waitForIdle()
        assertEquals(listOf(3), extracted)
        // Another page then: exactly that one.
        compose.onNodeWithTag("pdf_pages").performScrollToIndex(1)
        await("pdf_page_1")
        compose.onNodeWithTag("pdf_page_1").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("pdf_page_3").let { if (exists("pdf_page_3")) it.assertIsNotSelected() }
        compose.onNodeWithTag("pdf_extract").performClick()
        compose.waitForIdle()
        assertEquals(listOf(3, 1), extracted)
        // The extraction state of a page is shown on it.
        marks = mapOf(1 to PdfPageMark.CHECK, 3 to PdfPageMark.EXTRACTED)
        await("pdf_mark_check_1")
        // The last page can be reached and drawn.
        compose.onNodeWithTag("pdf_pages").performScrollToIndex(PAGES - 1)
        await("pdf_page_image_${PAGES - 1}")
        // Closing releases the renderer and deletes the copy.
        compose.onNodeWithTag("pdf_close").performClick()
        compose.waitForIdle()
        compose.waitUntil(10_000) { !exists("pdf_viewer") && (viewerDir.listFiles()?.size ?: 0) == 0 }
    }

    @Test
    fun viewer_damagedPdf_showsTheError_noExtraction() {
        val bad = File(context.cacheDir, "damaged-view.pdf").apply { writeText("not a pdf") }
        val extracted = mutableListOf<Int>()
        compose.setContent { MaterialTheme { PdfPageViewer(Uri.fromFile(bad), "PDF", emptyMap(), onExtract = { extracted += it }, onClose = {}) } }
        await("pdf_viewer_error")
        assertTrue(!exists("pdf_extract"))
        assertTrue(extracted.isEmpty())
    }

    /**
     * Page [index] (W x H points as displayed) scrolled into view: its laid-out width is the
     * width available (the list's width less its 16 dp side padding), never more, and its height
     * that width x H / W; when it fits in the list it is wholly visible at that size.
     */
    private fun assertShownInProportion(index: Int, w: Int, h: Int) {
        compose.onNodeWithTag("pdf_pages").performScrollToIndex(index)
        compose.waitForIdle()
        await("pdf_page_image_$index")
        val list = compose.onNodeWithTag("pdf_pages").fetchSemanticsNode()
        val node = compose.onNodeWithTag("pdf_page_$index").fetchSemanticsNode()
        val available = list.size.width - with(compose.density) { 32.dp.toPx() }
        val shown = node.size // laid out, not clipped
        val expectedHeight = shown.width * h.toFloat() / w
        val where = "page ${index + 1} ($w x $h) shown ${shown.width}x${shown.height}, available width $available"
        assertTrue("$where: wider than the screen", shown.width <= available + 1f)
        assertTrue("$where: not fitted to the width", shown.width >= available - 2f)
        assertTrue("$where: expected height $expectedHeight", abs(shown.height - expectedHeight) <= 1.5f)
        if (shown.height < list.size.height * 0.8f) {
            val visible = node.boundsInRoot
            assertTrue("$where: visible ${visible.width}x${visible.height}", abs(visible.width - shown.width) <= 1.5f && abs(visible.height - shown.height) <= 1.5f)
        }
    }

    @Test
    fun viewer_landscapeRatios_andRotatedPages_keepTheirProportions_selectedPageExtracted() {
        val f = File(context.cacheDir, "viewer-ratios.pdf").apply { writeBytes(pdfBytes(MIXED)) }
        val extracted = mutableListOf<Int>()
        compose.setContent { MaterialTheme { PdfPageViewer(Uri.fromFile(f), "PDF", emptyMap(), onExtract = { extracted += it }, onClose = {}) } }
        await("pdf_page_image_0")
        MIXED.forEachIndexed { i, (w, h, rotate) -> if (rotate % 180 == 0) assertShownInProportion(i, w, h) else assertShownInProportion(i, h, w) }
        // The rotated page tapped is the one extracted.
        compose.onNodeWithTag("pdf_pages").performScrollToIndex(3)
        await("pdf_page_3")
        compose.onNodeWithTag("pdf_page_3").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("pdf_page_3").assertIsSelected()
        compose.onNodeWithTag("pdf_extract").performClick()
        compose.waitForIdle()
        assertEquals(listOf(3), extracted)
    }

    @Test
    fun renderer_rotatedPages_displayedSizeAndContentTurned() = runBlocking {
        val f = File(context.cacheDir, "rotated.pdf").apply { writeBytes(pdfBytes(listOf(Triple(595, 842, 0), Triple(595, 842, 90), Triple(595, 842, 270)))) }
        val r = checkNotNull(PdfPageRenderer.open(context, Uri.fromFile(f))) { "PDF not opened" }
        try {
            // The block is drawn at 50..250 pt from the page's lower left corner.
            assertEquals(595 to 842, r.pageSizes[0])
            val up = r.render(0, 1080)!!
            assertTrue(abs(up.width.toDouble() / up.height - 595.0 / 842) < 0.01)
            assertTrue("upright block lower left", dark(up, 0.10f, 0.72f, 0.40f, 0.92f) > 0.95)
            assertTrue("upright top right white", dark(up, 0.55f, 0.05f, 0.95f, 0.45f) < 0.01)
            // Rotate 90 (clockwise): shown 842 wide, 595 high, the lower left corner now upper left.
            assertEquals(842 to 595, r.pageSizes[1])
            val cw = r.render(1, 1080)!!
            assertTrue("rotated 90 ratio ${cw.width}x${cw.height}", abs(cw.width.toDouble() / cw.height - 842.0 / 595) < 0.01)
            assertTrue("rotated 90 block upper left", dark(cw, 0.08f, 0.11f, 0.28f, 0.39f) > 0.95)
            assertTrue("rotated 90 lower right white", dark(cw, 0.60f, 0.55f, 0.95f, 0.95f) < 0.01)
            // Rotate 270: the lower left corner now lower right.
            assertEquals(842 to 595, r.pageSizes[2])
            val ccw = r.render(2, 1080)!!
            assertTrue("rotated 270 ratio", abs(ccw.width.toDouble() / ccw.height - 842.0 / 595) < 0.01)
            assertTrue("rotated 270 block lower right", dark(ccw, 0.72f, 0.61f, 0.92f, 0.89f) > 0.95)
            assertTrue("rotated 270 upper left white", dark(ccw, 0.05f, 0.05f, 0.40f, 0.45f) < 0.01)
        } finally { r.close() }
    }

    private companion object {
        const val PAGES = 25

        /** Pages (width, height, /Rotate) of the proportions test: landscape 1.42, 2.5 and 3 to 1, turned, tall. */
        val MIXED = listOf(Triple(842, 595, 0), Triple(1000, 400, 0), Triple(900, 300, 0), Triple(595, 842, 90), Triple(595, 842, 270), Triple(400, 1000, 0))

        /** A minimal PDF (MediaBox and /Rotate per page) with a black block 50..250 pt from each page's lower left corner. */
        fun pdfBytes(pages: List<Triple<Int, Int, Int>>): ByteArray {
            val objs = mutableListOf("<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [" + pages.indices.joinToString(" ") { "${3 + 2 * it} 0 R" } + "] /Count ${pages.size} >>")
            pages.forEachIndexed { i, (w, h, rotate) ->
                objs += "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $w $h] /Rotate $rotate /Contents ${4 + 2 * i} 0 R >>"
                val c = "0 0 0 rg 50 50 200 200 re f"
                objs += "<< /Length ${c.length} >>\nstream\n$c\nendstream"
            }
            val sb = StringBuilder("%PDF-1.4\n")
            val offsets = objs.mapIndexed { i, o -> sb.length.also { sb.append("${i + 1} 0 obj\n$o\nendobj\n") } }
            val xref = sb.length
            sb.append("xref\n0 ${objs.size + 1}\n0000000000 65535 f \n")
            offsets.forEach { sb.append(String.format(java.util.Locale.ROOT, "%010d 00000 n \n", it)) }
            sb.append("trailer\n<< /Size ${objs.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%EOF\n")
            return sb.toString().toByteArray(Charsets.US_ASCII)
        }
    }
}
