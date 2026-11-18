package com.example.thesis

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
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
import androidx.compose.ui.test.performScrollToNode
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
    private val keyboard by lazy { InsetsAnimationWatcher(compose) }

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
        keyboard.awaitSettled("before tapping $tag")
        await("node $tag") { exists(tag) }
        try { compose.onNodeWithTag(tag).performScrollTo() } catch (e: AssertionError) { }
        compose.onNodeWithTag(tag).performClick()
        compose.waitForIdle()
    }

    private fun tapPage(index: Int) {
        keyboard.awaitSettled("before tapping page $index")
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
                if (n == 2) drawTable(page.canvas, 150f, text)
            }
            doc.finishPage(page)
        }
        val f = File(dir, "source.pdf")
        FileOutputStream(f).use { doc.writeTo(it) }
        doc.close()
        return Uri.fromFile(f)
    }

    private val table = listOf(listOf("Well", "Depth", "Nitrates"), listOf("W1", "120", "35"), listOf("W2", "85", "52"))

    /** A ruled table with text cells (as ExtractionDeviceTest's digital PDF). */
    private fun drawTable(c: Canvas, top: Float, text: Paint) {
        val stroke = Paint().apply { strokeWidth = 1f; style = Paint.Style.STROKE }
        val cw = 150f
        table.forEachIndexed { r, row -> row.forEachIndexed { k, t -> c.drawText(t, 66f + k * cw, top + r * 22f + 15f, text) } }
        for (r in 0..table.size) c.drawLine(60f, top + r * 22f, 60f + cw * table[0].size, top + r * 22f, stroke)
        for (k in 0..table[0].size) c.drawLine(60f + k * cw, top, 60f + k * cw, top + table.size * 22f, stroke)
    }

    private val actions = listOf("dx_insert_cursor", "dx_insert_append", "dx_insert_paragraphs", "dx_insert_heading", "dx_insert_tables", "dx_copy", "dx_cancel_review")

    /** Height of the app's windows in pixels (the review dialog and the activity are edge to edge). */
    private fun windowHeight() = compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().maxOf { it.boundsInRoot.height }

    /** Lowest pixel a control may reach: above the navigation bar and the keyboard, measured on the device. */
    private fun usableBottom(): Float { val b = keyboard.bottoms(); return windowHeight() - maxOf(b.navigation, b.ime) }

    /** The floating bar's Cancel and Next are displayed, wholly above the navigation bar and the keyboard. */
    private fun assertBarAboveNavigation(label: String): Pair<androidx.compose.ui.geometry.Rect, androidx.compose.ui.geometry.Rect> {
        await("action bar") { exists("pdf_action_bar") }
        keyboard.awaitSettled("action bar shown")
        compose.onNodeWithTag("pdf_next").assertIsDisplayed()
        compose.onNodeWithTag("pdf_cancel").assertIsDisplayed()
        val next = compose.onNodeWithTag("pdf_next").fetchSemanticsNode().boundsInRoot
        val cancel = compose.onNodeWithTag("pdf_cancel").fetchSemanticsNode().boundsInRoot
        val bottom = usableBottom()
        android.util.Log.i("PdfReviewEvidence", "$label pdf_next=$next pdf_cancel=$cancel usableBottom=$bottom")
        assertTrue("Next $next under the navigation bar or the keyboard (usable bottom $bottom)", next.top >= 0f && next.bottom <= bottom + 1f)
        assertTrue("Cancel $cancel under the navigation bar or the keyboard (usable bottom $bottom)", cancel.top >= 0f && cancel.bottom <= bottom + 1f)
        return next to cancel
    }

    /** Display, density, font scale and insets of this run (logcat PdfReviewEvidence, printed in the CI summary). */
    private fun logMetrics(label: String) {
        val m = context.resources.displayMetrics
        val c = context.resources.configuration
        val b = keyboard.bottoms()
        android.util.Log.i("PdfReviewEvidence", "$label display=${m.widthPixels}x${m.heightPixels}px ${c.screenWidthDp}x${c.screenHeightDp}dp " +
            "density=${m.density} fontScale=${c.fontScale} window=${windowHeight()} navBottom=${b.navigation} imeBottom=${b.ime} sdk=${android.os.Build.VERSION.SDK_INT} model=${android.os.Build.MODEL}")
    }

    /**
     * The review's seven actions are on screen (displayed, not only in the tree), each wholly
     * inside the usable window: above the navigation bar and the keyboard as measured on the
     * device ([withKeyboard]: each is first scrolled into view in the action area, which is what
     * a user does with the keyboard open). Bounds and metrics go to logcat (tag PdfReviewEvidence,
     * printed in the CI summary) and a screenshot is saved (thesis-device/[shotName].png).
     */
    private fun assertActionsDisplayed(shotName: String, withKeyboard: Boolean = false) {
        keyboard.awaitSettled("review shown", shown = withKeyboard)
        logMetrics(shotName)
        val area = compose.onNodeWithTag("dx_actions").fetchSemanticsNode().boundsInRoot
        val list = compose.onNodeWithTag("dx_pages").fetchSemanticsNode().boundsInRoot
        android.util.Log.i("PdfReviewEvidence", "$shotName text=$list actions=$area")
        for (tag in actions) {
            if (withKeyboard) { compose.onNodeWithTag(tag).performScrollTo(); keyboard.awaitSettled("scrolled to $tag", shown = true) }
            compose.onNodeWithTag(tag).assertIsDisplayed()
            val b = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            val bottom = usableBottom()
            val visible = b.top >= 0f && b.bottom <= bottom + 1f
            android.util.Log.i("PdfReviewEvidence", "$shotName $tag displayed=true bounds=$b usableBottom=$bottom visible=$visible")
            assertTrue("$tag not wholly in the usable window: $b (usable bottom $bottom, window ${windowHeight()})", b.width > 0f && b.height > 0f && visible)
            assertTrue("$tag $b over the text $list", b.top >= list.bottom - 1f || b.left >= list.right - 1f || b.right <= list.left + 1f)
        }
        shot(shotName)
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        val dir = File(context.getExternalFilesDir(null), "thesis-device").apply { mkdirs() }
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        FileOutputStream(File(dir, "$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun count(text: String, part: String) = text.windowed(part.length).count { it == part }

    /** Opens the PDF, selects [pages] (0-based, in that order) and presses Next; returns once the review shows their result. */
    private fun extract(uri: Uri, pages: List<Int>, timeout: Long = 180_000) {
        openPdf(uri)
        for (i in pages) tapPage(i)
        keyboard.awaitSettled("pages selected")
        assertBarAboveNavigation("pages ${pages.sorted()}")
        tap("pdf_next")
        await("review of ${pages.sorted()}", timeout) {
            exists("dx_review") && vm.thesis.state.value.extraction?.document?.pages?.map { it.sourcePage } == pages.sorted()
        }
    }

    private fun clipboardText(): String? {
        var t: String? = null
        compose.runOnUiThread {
            t = (context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
        }
        return t
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
        keyboard.awaitSettled("keyboard opened by typing", shown = true)
    }

    private fun openPdf(uri: Uri) {
        compose.runOnUiThread { vm.thesis.openPdfForExtraction(uri) }
        keyboard.awaitSettled("viewer opened (the keyboard closes)")
        await("viewer") { exists("pdf_page_image_0") }
    }

    private fun words(s: String) = s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.toSet()

    @Test
    fun selectPages_next_review_back_changeSelection_append_thenInsertAtCursor() {
        openEditor()
        val uri = makePdf()
        openPdf(uri)
        // Nothing selected: no floating bar.
        assertTrue("action bar without a selection", !exists("pdf_action_bar") && !exists("pdf_next"))
        // Cancel: the selection is cleared, nothing extracted, still the pages.
        tapPage(1)
        await("bar shown") { exists("pdf_action_bar") }
        tap("pdf_cancel")
        await("bar hidden after Cancel") { !exists("pdf_action_bar") }
        assertTrue("no extraction after Cancel", vm.thesis.state.value.extraction == null)
        assertTrue(exists("pdf_viewer"))
        assertPageSelected(1, false)
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
        assertActionsDisplayed("pdf-review-pages-1-3-5")
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
        tap("pdf_back")
        await("closed") { !exists("pdf_flow") && vm.thesis.state.value.pdfToExtract == null && vm.thesis.state.value.extraction == null }
        await("copy released") { (File(context.cacheDir, "pdf-viewer").listFiles()?.size ?: 0) == 0 }
        assertEquals("Existing paragraph.", draftText.trim())
    }

    /** Share of the words of [expected] found in [got] (OCR of the scanned page). */
    private fun recall(expected: String, got: String): Double { val e = words(expected); return e.intersect(words(got)).size.toDouble() / e.size }

    /**
     * The review after Next: the extracted text of the selected pages shown page by page, all
     * seven actions on screen, and each of them doing its job on those pages (digital pages and a
     * scanned one): Cancel and Copy keep the review and insert nothing, Insert at cursor, Tables
     * only, As paragraphs and Heading + content insert exactly the selected pages once.
     */
    @Test
    fun review_showsTheTextAndAllSevenActions_eachActionWorks_digitalAndScannedPages() {
        openEditor()
        val uri = makePdf()
        // Pages 5 (scanned), 1 and 3 (digital), selected in that order.
        extract(uri, listOf(4, 0, 2))
        val first = vm.thesis.state.value.extraction!!.document!!
        assertEquals(listOf(PageSource.PDF_TEXT, PageSource.PDF_TEXT, PageSource.PDF_OCR), first.pages.map { it.source })
        // The extracted text is on screen, under the pages' original numbers.
        compose.onNodeWithTag("dx_page_header_0").assertTextEquals("Page 1")
        await("text of page 1 shown") { compose.onAllNodes(hasText(line(1), substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        assertActionsDisplayed("pdf-review-actions")
        compose.onNodeWithTag("dx_insert_tables").assertIsNotEnabled() // these pages have no table
        val before = draftText
        // Cancel: back to the pages as selected, nothing inserted; Next shows the same result.
        tap("dx_cancel_review")
        await("pages after Cancel") { exists("pdf_viewer") && !exists("dx_review") }
        assertEquals(before, draftText)
        for (i in 0 until 6) assertPageSelected(i, i in setOf(0, 2, 4))
        tap("pdf_next")
        await("same review") { exists("dx_review") }
        assertSame("not extracted again", first, vm.thesis.state.value.extraction!!.document)
        // Copy text: the pages' text on the clipboard; nothing inserted, the review stays.
        tap("dx_copy")
        await("copied", 20_000) { clipboardText()?.let { it.contains(line(1)) && it.contains(line(3)) } == true }
        assertEquals(before, draftText)
        assertTrue(exists("dx_review"))
        // Insert at cursor: the three pages once, the section's text kept.
        tap("dx_insert_cursor")
        await("inserted at cursor") { vm.thesis.state.value.extraction == null && !exists("pdf_flow") }
        var text = draftText
        assertEquals(1, count(text, "Existing paragraph."))
        assertEquals(1, count(text, line(1)))
        assertEquals(1, count(text, line(3)))
        assertTrue("scanned page inserted: $text", recall("Scanned appendix about springs Water samples from the springs", text) >= 0.7)
        assertEquals(0, count(text, line(2)) + count(text, line(4)) + count(text, line(6)))
        // Tables only (page 2): its table as an editable table, not its text.
        extract(uri, listOf(1))
        compose.onNodeWithTag("dx_insert_tables").assertIsEnabled()
        assertActionsDisplayed("pdf-review-table")
        tap("dx_insert_tables")
        await("table inserted") { vm.thesis.state.value.extraction == null && !exists("pdf_flow") }
        text = draftText
        assertEquals("tables only: no text", 0, count(text, line(2)))
        val tables = vm.thesis.state.value.draft!!.media.values.filter { it.data.tableHeader.isNotEmpty() }
        assertEquals(1, tables.size)
        assertEquals(listOf("Well", "Depth", "Nitrates"), tables.single().data.tableHeader)
        assertEquals(listOf(listOf("W1", "120", "35"), listOf("W2", "85", "52")), tables.single().data.tableRows)
        // As paragraphs (page 6): its text once, no heading made of it.
        extract(uri, listOf(5))
        tap("dx_insert_paragraphs")
        await("paragraphs inserted") { vm.thesis.state.value.extraction == null && !exists("pdf_flow") }
        text = draftText
        assertEquals(1, count(text, line(6)))
        assertTrue(text, text.lines().none { it.startsWith("#") && it.contains("Source page 6") })
        // Heading + content (page 4): a heading line, then its text once.
        extract(uri, listOf(3))
        tap("dx_insert_heading")
        await("heading inserted") { vm.thesis.state.value.extraction == null && !exists("pdf_flow") }
        text = draftText
        assertEquals(1, count(text, line(4)))
        assertTrue("heading line: $text", text.lines().any { it.startsWith("# ") && it.contains("Source page 4") })
        // Nothing inserted twice over the whole session.
        listOf(1, 3, 4, 6).forEach { n -> assertEquals("zone $n", 1, count(text, line(n))) }
        assertTrue("crash report written", CrashLog.read(context) == null)
    }

    /** Pages of Arabic and mixed Arabic/Latin text, 38 lines each. */
    private fun makeArabicPdf(): Uri {
        val dir = File(context.cacheDir, "flow-test-ar").apply { deleteRecursively(); mkdirs() }
        val doc = PdfDocument()
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 12f; textAlign = Paint.Align.RIGHT }
        for (n in 1..4) {
            val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, n).create())
            for (i in 1..38) {
                val s = if (i % 2 == 0) "تعد المياه الجوفية مصدرا أساسيا لمياه الشرب في المنطقة $n، السطر $i"
                else "قيمة pH في البئر W$i هي 7.2 ودرجة الحرارة 18 °C، الصفحة $n"
                page.canvas.drawText(s, 540f, 50f + i * 20f, text)
            }
            doc.finishPage(page)
        }
        val f = File(dir, "arabic.pdf")
        FileOutputStream(f).use { doc.writeTo(it) }
        doc.close()
        return Uri.fromFile(f)
    }

    /**
     * Long Arabic and mixed Arabic/Latin pages through the whole flow, twice: the review shows
     * them with every action on screen and scrolls, and each insertion leaves the section editor
     * bounded by the window (no "Can't represent ... in Constraints" crash) and editable.
     */
    @Test
    fun longArabicAndMixedPages_reviewAndInsertTwice_noLayoutCrash() {
        openEditor()
        val uri = makeArabicPdf()
        for ((k, action) in listOf("dx_insert_paragraphs", "dx_insert_append").withIndex()) {
            extract(uri, listOf(3, 0, 2), timeout = 300_000)
            assertActionsDisplayed("pdf-review-arabic-$k")
            if (k == 0) {
                // Correcting a block opens the keyboard: every action stays reachable above it.
                compose.onAllNodes(androidx.compose.ui.test.SemanticsMatcher("block text field") {
                    it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag)?.matches(Regex("dx_block_\\d+_\\d+_text")) == true
                })[0].performClick()
                assertActionsDisplayed("pdf-review-arabic-keyboard", withKeyboard = true)
            }
            compose.onNodeWithTag("dx_pages").performScrollToNode(hasTestTag("dx_page_header_2"))
            tap(action)
            await("inserted $action") { vm.thesis.state.value.extraction == null && !exists("pdf_flow") }
            keyboard.awaitSettled("editor after $action")
            val window = compose.onAllNodes(isRoot()).fetchSemanticsNodes().maxOf { it.size.height }
            val editor = compose.onNodeWithTag("thesis_editor_text").fetchSemanticsNode().size.height
            assertTrue("editor height $editor within the window $window", editor <= window)
        }
        val text = draftText
        assertTrue("Arabic text inserted", text.count { it in '\u0600'..'\u06FF' } > 600)
        assertEquals(1, count(text, "Existing paragraph."))
        assertTrue("crash report written", CrashLog.read(context) == null)
    }

    /**
     * Arabic (right to left): the floating bar appears with the first selected page, above the
     * navigation bar, Next at the start side (left in RTL) of Cancel; Next opens the review, Back
     * returns to the pages as selected; the top bar's back arrow closes the viewer.
     */
    @Test
    fun arabic_floatingBar_rightToLeft_aboveNavigation_nextReviewBackClose() {
        openEditor()
        vm.setAppLanguage("ar")
        // The app language is kept by the app: back to English whatever happens, for the next tests.
        try { arabicFlow() } finally { compose.runOnUiThread { vm.setAppLanguage("en") } }
    }

    private fun arabicFlow() {
        await("arabic") { compose.activity.resources.configuration.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL && exists("thesis_editor_text") }
        val uri = makePdf()
        openPdf(uri)
        assertTrue(!exists("pdf_action_bar"))
        tapPage(2)
        val (next, cancel) = assertBarAboveNavigation("arabic")
        assertTrue("RTL order: Next $next left of Cancel $cancel", next.left < cancel.left)
        shot("pdf-viewer-arabic-bar")
        tap("pdf_next")
        await("review", timeout = 60_000) { exists("dx_review") && vm.thesis.state.value.extraction?.document?.pages?.map { it.sourcePage } == listOf(2) }
        assertActionsDisplayed("pdf-review-arabic-rtl")
        tap("dx_back")
        await("pages again") { exists("pdf_viewer") && !exists("dx_review") }
        assertPageSelected(2, true)
        tap("pdf_back")
        await("closed") { !exists("pdf_flow") && vm.thesis.state.value.pdfToExtract == null }
    }

    /**
     * Rotation while pages are selected: the PDF, the selected pages (non-consecutive, picked out
     * of order) and the floating bar are kept, the bar is above the navigation bar in landscape,
     * and Next there extracts exactly those pages (document order).
     */
    @Test
    fun rotation_keepsThePdfAndSelection_barAboveNavigation_nextExtractsThem() {
        openEditor()
        val uri = makePdf()
        openPdf(uri)
        for (i in listOf(3, 0)) tapPage(i)
        try {
            compose.runOnUiThread { compose.activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            await("landscape", 20_000) {
                compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE && exists("pdf_viewer")
            }
            keyboard.awaitSettled("rotated")
            assertEquals(uri, vm.thesis.state.value.pdfToExtract)
            for (i in 0 until 6) assertPageSelected(i, i in setOf(0, 3))
            assertBarAboveNavigation("landscape")
            logMetrics("pdf-viewer-landscape")
            shot("pdf-viewer-landscape-bar")
            tap("pdf_next")
            await("review in landscape", 60_000) { exists("dx_review") && vm.thesis.state.value.extraction?.document?.pages?.map { it.sourcePage } == listOf(0, 3) }
            assertEquals(listOf<Set<Int>?>(setOf(0, 3)), vm.thesis.state.value.extraction!!.sources.map { it.pages })
            assertEquals(uri, vm.thesis.state.value.extraction!!.sources.single().uri)
            assertActionsDisplayed("pdf-review-landscape")
        } finally {
            compose.runOnUiThread { compose.activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        }
    }

    /**
     * A PDF that cannot be read: the viewer says so, no bar, nothing extracted, and it closes.
     * Then a selection closed with the back arrow is not reused: the same PDF reopens with no
     * page selected, and so does another PDF after a Cancel.
     */
    @Test
    fun missingPdf_error_noBar_noExtraction_andNoStaleSelectionWhenReopened() {
        openEditor()
        val missing = Uri.fromFile(File(context.cacheDir, "flow-test-missing.pdf").apply { delete() })
        compose.runOnUiThread { vm.thesis.openPdfForExtraction(missing) }
        await("error shown") { exists("pdf_viewer_error") }
        assertTrue("no bar for an unreadable PDF", !exists("pdf_action_bar") && !exists("pdf_next"))
        assertTrue(vm.thesis.state.value.extraction == null)
        tap("pdf_back")
        await("closed") { !exists("pdf_flow") && vm.thesis.state.value.pdfToExtract == null }
        // Pages selected, then the viewer closed: reopened, no page is selected.
        val uri = makePdf()
        openPdf(uri)
        tapPage(1); tapPage(4)
        await("bar") { exists("pdf_action_bar") }
        tap("pdf_back")
        await("closed again") { !exists("pdf_flow") }
        openPdf(uri)
        assertTrue("stale bar", !exists("pdf_action_bar"))
        for (i in 0 until 6) assertPageSelected(i, false)
        // Cancel, then another PDF: nothing selected there either; a new selection works.
        tapPage(2)
        tap("pdf_cancel")
        await("bar hidden") { !exists("pdf_action_bar") }
        tap("pdf_back")
        await("closed a third time") { !exists("pdf_flow") }
        val other = File(context.cacheDir, "flow-test-other.pdf").also { File(uri.path!!).copyTo(it, overwrite = true) }
        openPdf(Uri.fromFile(other))
        assertTrue(!exists("pdf_action_bar"))
        tapPage(5)
        tap("pdf_next")
        await("review of the other PDF", 60_000) { vm.thesis.state.value.extraction?.document?.pages?.map { it.sourcePage } == listOf(5) }
        assertEquals(Uri.fromFile(other), vm.thesis.state.value.extraction!!.sources.single().uri)
        assertTrue("the original PDF is kept", File(uri.path!!).exists())
    }

    /**
     * Gesture and three-button navigation (switched with the system navigation-mode overlays):
     * in each, the floating bar's Cancel and Next are above the navigation bar as measured.
     */
    @Test
    fun actionBar_aboveTheNavigationBar_inThreeButtonAndGestureNavigation() {
        openEditor()
        openPdf(makePdf())
        tapPage(0)
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun shell(c: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(c)).bufferedReader().readText()
        val density = context.resources.displayMetrics.density
        val wasThreeButton = keyboard.bottoms().navigation > 40 * density
        try {
            for ((mode, overlay) in listOf("three-button" to "com.android.internal.systemui.navbar.threebutton", "gesture" to "com.android.internal.systemui.navbar.gestural")) {
                val out = shell("cmd overlay enable-exclusive --category $overlay")
                android.util.Log.i("PdfReviewEvidence", "navigation $mode: $out")
                await("$mode navigation", 30_000) {
                    val n = keyboard.bottoms().navigation
                    if (mode == "three-button") n > 40 * density else n in 1..(32 * density).toInt()
                }
                await("pages after the navigation change") { exists("pdf_viewer") }
                assertBarAboveNavigation("navigation-$mode")
                shot("pdf-viewer-bar-$mode")
            }
        } finally {
            shell("cmd overlay enable-exclusive --category " + if (wasThreeButton) "com.android.internal.systemui.navbar.threebutton" else "com.android.internal.systemui.navbar.gestural")
        }
    }
}
