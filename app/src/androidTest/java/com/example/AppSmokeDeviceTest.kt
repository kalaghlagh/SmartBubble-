package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToString
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.diagnostics.CrashLog
import com.example.document.docx.DocxEngine
import com.example.document.model.PresentationModel
import com.example.document.model.SheetModel
import com.example.document.model.SlideModel
import com.example.document.model.SpreadsheetModel
import com.example.document.model.WordDocumentModel
import com.example.document.pptx.PptxEngine
import com.example.document.xlsx.XlsxEngine
import com.example.thesis.model.AcademicLevel
import com.example.thesis.model.SectionKind
import com.example.thesis.model.ThesisMeta
import com.example.ui.AppNavTab
import com.example.ui.SmartBubbleViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

/**
 * The whole app on a real Android runtime (emulator in CI): every destination of the five-item
 * bottom bar in English, French and Arabic (with the RTL layout), My Templates with Word,
 * PowerPoint and Excel files imported through the real import path and previewed, Settings, the
 * Library and Chat, and Thesis Builder from a new project to the DOCX and PDF files, whose
 * contents are checked (the PDF is rendered with Android's PdfRenderer, which the JVM tests lack).
 * Screenshots go to the app's external files ("app-device"); CI keeps them with the logcat.
 */
@RunWith(AndroidJUnit4::class)
class AppSmokeDeviceTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val vm: SmartBubbleViewModel get() = ViewModelProvider(compose.activity)[SmartBubbleViewModel::class.java]
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun noOldCrashDialog() = CrashLog.clear(InstrumentationRegistry.getInstrumentation().targetContext)

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    private fun textShown(text: String) = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun diagnostics(): String = buildString {
        append("tab=${vm.currentTab.value} thesis=${vm.thesis.state.value.screen} busy=${vm.thesis.state.value.busy}\n")
        try { append(compose.onAllNodes(isRoot()).printToString(maxDepth = 25)) } catch (e: Throwable) { append("tree unavailable: $e") }
    }

    private fun await(step: String, timeout: Long = 20_000, condition: () -> Boolean) {
        try { compose.waitUntil(timeout) { condition() } } catch (e: Throwable) { throw AssertionError("Timed out: $step\n" + diagnostics(), e) }
    }

    private fun tap(tag: String) {
        await("node $tag") { exists(tag) }
        val containers = compose.onAllNodes(hasScrollAction() and hasAnyDescendant(hasTestTag(tag)))
        repeat(containers.fetchSemanticsNodes().size) { i -> try { containers[i].performScrollToNode(hasTestTag(tag)) } catch (e: Throwable) { } }
        val node = compose.onNodeWithTag(tag)
        try { node.performScrollTo() } catch (e: AssertionError) { }
        node.performClick()
        compose.waitForIdle()
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        val dir = File(context.getExternalFilesDir(null), "app-device").apply { mkdirs() }
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        FileOutputStream(File(dir, "$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun language(code: String, navThesisLabel: String) {
        await("app started") { exists("nav_thesis") }
        vm.setAppLanguage(code)
        await("language $code") { compose.onAllNodes(hasTestTag("nav_thesis") and hasText(navThesisLabel)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun left(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.left

    @Test
    fun everyDestination_inEnglishFrenchAndArabic() {
        val languages = listOf(
            Triple("en", "Thesis", "Agent"),
            Triple("fr", "Mémoire", "Agent"),
            Triple("ar", "المذكرة", "الوكيل")
        )
        try {
            for ((code, thesisLabel, _) in languages) {
                language(code, thesisLabel)
                // Five items in reading order (right to left in Arabic).
                // Stitch order: Thesis, My Templates, Agent (centre), Library, Settings.
                val tabs = listOf("nav_thesis", "nav_templates", "nav_chat", "nav_library", "nav_settings")
                tabs.forEach { assertTrue("$it missing in $code", exists(it)) }
                val xs = tabs.map { left(it) }
                if (code == "ar") assertTrue("Arabic bar is not right-to-left: $xs", xs.zipWithNext().all { (a, b) -> a > b })
                else assertTrue("$code bar is not left-to-right: $xs", xs.zipWithNext().all { (a, b) -> a < b })
                for ((tag, tab) in tabs.zip(listOf(AppNavTab.THESIS, AppNavTab.TEMPLATES, AppNavTab.CHAT, AppNavTab.LIBRARY, AppNavTab.SETTINGS))) {
                    tap(tag)
                    await("$code $tab shown") { vm.currentTab.value == tab }
                    shot("$code-${tab.name.lowercase()}")
                }
            }
        } finally {
            vm.setAppLanguage("en")
        }
    }

    @Test
    fun myTemplates_importWordPowerPointExcel_andPreviewEach() {
        language("en", "Thesis")
        val dir = File(context.filesDir, "generated_docs").apply { mkdirs() }
        val docx = DocxEngine().generateDocx(WordDocumentModel(title = "Device Word template"), File(dir, "device-template.docx"))
        val pptx = PptxEngine().generatePptx(PresentationModel("Device slides", "Sub", slides = listOf(SlideModel("Slide one", bulletPoints = listOf("Point")))), File(dir, "device-template.pptx"))
        val xlsx = XlsxEngine().generateXlsx(SpreadsheetModel("Device book", listOf(SheetModel("Data", listOf("A", "B"), listOf(listOf("x", 1))))), File(dir, "device-template.xlsx"))
        val before = runBlocking { vm.allTemplates.first().size }
        for (f in listOf(docx, pptx, xlsx)) {
            val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", f)
            vm.importTemplateFromUri(uri, f.nameWithoutExtension, f.name)
        }
        await("three templates imported", 30_000) { runBlocking { vm.allTemplates.first().size } >= before + 3 }
        val imported = runBlocking { vm.allTemplates.first() }.filter { it.name.startsWith("device-template") }
        assertEquals(setOf("DOCX", "PPTX", "XLSX"), imported.map { it.fileType.uppercase() }.toSet())
        tap("nav_templates")
        await("templates screen") { vm.currentTab.value == AppNavTab.TEMPLATES }
        shot("templates-library")
        // The uploaded templates (the library also lists the built-in categories).
        tap("st_filter_uploaded")
        await("only uploads listed") { !exists("st_category_reports") && !exists("categories_header") }
        shot("templates-list")
        for (t in imported) {
            tap("template_menu_${t.id}")
            tap("menu_preview_${t.id}")
            await("preview dialog ${t.fileType}") { exists("template_preview_dialog") }
            await("preview loaded ${t.fileType}", 30_000) { !vm.isPreviewLoading.value }
            shot("template-preview-${t.fileType.lowercase()}")
            tap("template_preview_close_btn")
            await("preview closed") { !exists("template_preview_dialog") }
        }
    }

    @Test
    fun settingsLibraryAndChat_open_withoutCrash() {
        language("en", "Thesis")
        tap("nav_settings")
        await("settings") { exists("add_provider_button") }
        tap("add_provider_button")
        await("add provider form") { exists("provider_api_key_field") }
        shot("settings-add-provider")
        back()
        tap("nav_library")
        await("library") { vm.currentTab.value == AppNavTab.LIBRARY }
        if (exists("library_search_toggle")) { tap("library_search_toggle"); shot("library-search"); back() }
        tap("nav_chat")
        await("chat input") { exists("chat_input_field") }
        compose.onNodeWithTag("chat_input_field").performTextInput("Bonjour مرحبا hello")
        compose.waitForIdle()
        shot("chat-typed")
    }

    @Test
    fun apiKeys_areEncryptedWithTheAndroidKeystore_andReadBack() {
        // The Android Keystore exists only on a device: the JVM tests cannot check this.
        val key = "sk-device-test-0123456789abcdef"
        val stored = com.example.core.security.KeystoreHelper.encrypt(key)
        assertTrue("stored in plaintext", !stored.contains(key) && !stored.contains("0123456789abcdef"))
        assertTrue(com.example.core.security.KeystoreHelper.isEncryptedValue(stored))
        assertEquals(key, com.example.core.security.KeystoreHelper.decrypt(stored))
    }

    @Test
    fun thesis_newProject_editSaveReopen_previewAndExportDocxPdf() {
        language("en", "Thesis")
        tap("nav_thesis")
        val thesis = vm.thesis
        thesis.createProject(ThesisMeta("Device export test", AcademicLevel.MASTER, "ar", studentName = "طالب الاختبار"), aiAssist = false)
        await("project created") { thesis.state.value.snapshot != null && thesis.state.value.busy == null }
        val projectId = thesis.state.value.projectId!!
        val intro = thesis.state.value.snapshot!!.sections.first { it.kind == SectionKind.INTRODUCTION }.id
        compose.runOnUiThread { thesis.selectSection(intro) }
        await("editor") { exists("thesis_editor_text") && thesis.state.value.draft != null }
        val body = "# العنوان الأول\n{center} **نص عريض** و *مائل* و __مسطر__\n- بند أول\n- بند ثان\nEnglish line and ligne française."
        compose.runOnUiThread { thesis.updateDraft(body) }
        tap("thesis_save_changes")
        await("saved") { thesis.state.value.busy == null && thesis.state.value.draft?.dirty == false }
        shot("thesis-editor-saved")

        // Reopened from the database: the text and its formatting are still there.
        compose.runOnUiThread { thesis.closeWorkspace(); thesis.openProject(projectId) }
        await("reopened") { thesis.state.value.snapshot != null && thesis.state.value.busy == null }
        compose.runOnUiThread { thesis.selectSection(intro) }
        await("draft reloaded") { thesis.state.value.draft?.sectionId == intro }
        // Saving splits the text into paragraphs (shown with a blank line between them): the
        // lines, their formatting markers and their order are what must survive.
        fun lines(t: String) = t.lines().filter { it.isNotBlank() }
        assertEquals(lines(body), lines(thesis.state.value.draft!!.text))

        thesis.buildPreview()
        await("preview", 60_000) { thesis.state.value.busy == null && thesis.state.value.previewFile != null }
        shot("thesis-preview")

        thesis.export("DOCX")
        await("docx", 60_000) { thesis.state.value.busy == null && thesis.state.value.lastExport?.format == "DOCX" }
        val docx = File(thesis.state.value.lastExport!!.document.filePath)
        ZipFile(docx).use { z ->
            val xml = z.getInputStream(z.getEntry("word/document.xml")).bufferedReader().readText()
            for (s in listOf("العنوان الأول", "نص عريض", "مائل", "مسطر", "بند أول", "English line and ligne française.")) assertTrue("DOCX lacks \"$s\"", xml.contains(s))
            assertTrue("formatting markers leaked into the DOCX", !xml.contains("**") && !xml.contains("{center}") && !xml.contains("__"))
            assertTrue("no bold run", xml.contains("<w:b/>") || xml.contains("<w:b "))
            assertTrue("no right-to-left paragraph", xml.contains("<w:bidi"))
        }

        thesis.export("PDF")
        await("pdf", 60_000) { thesis.state.value.busy == null && thesis.state.value.lastExport?.format == "PDF" }
        val pdf = File(thesis.state.value.lastExport!!.document.filePath)
        PdfRenderer(ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)).use { r ->
            assertTrue("PDF has no pages", r.pageCount >= 1)
            // The page size of the template this thesis uses: A4 (595 x 842 pt) with the built-in
            // formatting, or the default Word template's own page (its PDF follows the Word export).
            val resolved = runBlocking { com.example.thesis.export.ThesisTemplateResolver.resolve(com.example.offline.document.RoomTemplateRepository(vm.database.templateDao()), null) }
            val expected = com.example.thesis.export.ThesisTemplateResolver.pdfProfile(resolved).let { p ->
                (if (p.pageWidthPt > 0) p.pageWidthPt else 595) to (if (p.pageHeightPt > 0) p.pageHeightPt else 842)
            }
            android.util.Log.i("AppSmokeDeviceTest", "Thesis template: ${resolved.source} ${resolved.entity?.name}, page ${expected.first}x${expected.second}")
            r.openPage(0).use { p ->
                assertTrue("page ${p.width}x${p.height}, template page ${expected.first}x${expected.second} (${resolved.source} ${resolved.entity?.name})",
                    Math.abs(p.width - expected.first) <= 5 && Math.abs(p.height - expected.second) <= 5)
                if (resolved.entity == null) assertTrue("not A4: ${p.width}x${p.height}", p.width in 590..600 && p.height in 837..847)
            }
            // Ink in the body of each page (the footer band with the page number is left out), on
            // a 2x render so a page holding a single heading still counts.
            val ink = IntArray(r.pageCount)
            for (i in 0 until r.pageCount) r.openPage(i).use { p ->
                val bmp = Bitmap.createBitmap(p.width * 2, p.height * 2, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
                p.render(bmp, null, android.graphics.Matrix().apply { setScale(2f, 2f) }, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                val bodyBottom = bmp.height - 2 * 60
                val px = IntArray(bmp.width)
                for (y in 0 until bodyBottom) {
                    bmp.getPixels(px, 0, bmp.width, 0, y, bmp.width, 1)
                    for (c in px) if ((Color.red(c) + Color.green(c) + Color.blue(c)) / 3 < 160) ink[i]++
                }
                if (i < 4) FileOutputStream(File(File(context.getExternalFilesDir(null), "app-device").apply { mkdirs() }, "thesis-pdf-page-${i + 1}.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            val inked = ink.count { it > 50 }
            android.util.Log.i("AppSmokeDeviceTest", "PDF ink per page: ${ink.toList()}")
            assertEquals("blank PDF pages, ink per page: ${ink.toList()}", r.pageCount, inked)
        }
        assertNotNull(thesis.state.value.lastExport)
        assertTrue("crash report written", CrashLog.read(context) == null)
    }

    // ------------------------------------------------------------------ template, logo, preview

    private fun png(w: Int, h: Int, color: Int, file: File): File {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        FileOutputStream(file).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        b.recycle()
        return file
    }

    /**
     * A university template as Word writes it: A4, a header with a blue logo image and a line of
     * text, a footer with a PAGE field, a red page border measured from the page edge.
     */
    private fun universityTemplate(out: File, headerLogo: File): File {
        val ns = """xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture""""
        val parts = linkedMapOf(
            "[Content_Types].xml" to """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Default Extension="png" ContentType="image/png"/>""" +
                """<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>""" +
                """<Override PartName="/word/header1.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.header+xml"/><Override PartName="/word/footer1.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.footer+xml"/></Types>""",
            "_rels/.rels" to """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>""",
            "word/document.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:document $ns><w:body><w:p><w:r><w:t>Template body</w:t></w:r></w:p>""" +
                """<w:sectPr><w:headerReference w:type="default" r:id="rId10"/><w:footerReference w:type="default" r:id="rId11"/><w:pgSz w:w="11906" w:h="16838"/>""" +
                """<w:pgMar w:top="1701" w:right="1134" w:bottom="1418" w:left="1418" w:header="567" w:footer="567" w:gutter="0"/>""" +
                """<w:pgBorders w:offsetFrom="page"><w:top w:val="single" w:sz="24" w:space="20" w:color="FF0000"/><w:left w:val="single" w:sz="24" w:space="20" w:color="FF0000"/><w:bottom w:val="single" w:sz="24" w:space="20" w:color="FF0000"/><w:right w:val="single" w:sz="24" w:space="20" w:color="FF0000"/></w:pgBorders></w:sectPr></w:body></w:document>""",
            "word/_rels/document.xml.rels" to """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""" +
                """<Relationship Id="rId10" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/header" Target="header1.xml"/><Relationship Id="rId11" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/footer" Target="footer1.xml"/></Relationships>""",
            "word/styles.xml" to """<?xml version="1.0" encoding="UTF-8"?><w:styles $ns><w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/><w:pPr><w:jc w:val="both"/><w:spacing w:after="120" w:line="360" w:lineRule="auto"/></w:pPr><w:rPr><w:rFonts w:ascii="Times New Roman" w:hAnsi="Times New Roman"/><w:sz w:val="24"/></w:rPr></w:style></w:styles>""",
            "word/header1.xml" to """<?xml version="1.0" encoding="UTF-8"?><w:hdr $ns><w:p><w:pPr><w:jc w:val="center"/></w:pPr><w:r><w:drawing><wp:inline><wp:extent cx="1143000" cy="571500"/><a:graphic><a:graphicData><pic:pic><pic:blipFill><a:blip r:embed="rIdLogo"/></pic:blipFill></pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>""" +
                """<w:p><w:pPr><w:jc w:val="center"/></w:pPr><w:r><w:t>UNIVERSITY HEADER TEST</w:t></w:r></w:p></w:hdr>""",
            "word/_rels/header1.xml.rels" to """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rIdLogo" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/univ.png"/></Relationships>""",
            "word/footer1.xml" to """<?xml version="1.0" encoding="UTF-8"?><w:ftr $ns><w:p><w:pPr><w:jc w:val="center"/></w:pPr><w:r><w:fldChar w:fldCharType="begin"/></w:r><w:r><w:instrText> PAGE </w:instrText></w:r><w:r><w:fldChar w:fldCharType="separate"/></w:r><w:r><w:t>1</w:t></w:r><w:r><w:fldChar w:fldCharType="end"/></w:r></w:p></w:ftr>"""
        )
        java.util.zip.ZipOutputStream(FileOutputStream(out)).use { z ->
            for ((name, xml) in parts) { z.putNextEntry(java.util.zip.ZipEntry(name)); z.write(xml.toByteArray(Charsets.UTF_8)); z.closeEntry() }
            z.putNextEntry(java.util.zip.ZipEntry("word/media/univ.png")); z.write(headerLogo.readBytes()); z.closeEntry()
        }
        return out
    }

    /** Pixels of [page] (rendered at 1 px per point) close to [color], inside [area] (fractions of the page). */
    private fun countColor(pdf: File, page: Int, color: Int, area: android.graphics.RectF = android.graphics.RectF(0f, 0f, 1f, 1f)): Int =
        PdfRenderer(ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)).use { r ->
            r.openPage(page).use { p ->
                val bmp = Bitmap.createBitmap(p.width, p.height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
                p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                var n = 0
                for (y in (area.top * bmp.height).toInt() until (area.bottom * bmp.height).toInt()) for (x in (area.left * bmp.width).toInt() until (area.right * bmp.width).toInt()) {
                    val c = bmp.getPixel(x, y)
                    if (Math.abs(Color.red(c) - Color.red(color)) < 50 && Math.abs(Color.green(c) - Color.green(color)) < 50 && Math.abs(Color.blue(c) - Color.blue(color)) < 50) n++
                }
                bmp.recycle()
                n
            }
        }

    @Test
    fun thesis_universityTemplateAndLogo_inPreviewPdfAndDocx_previewIsAViewerOnly() {
        language("en", "Thesis")
        // Under generated_docs: the folder the app's FileProvider serves (as for the other imports).
        val dir = File(context.filesDir, "generated_docs/device-template-test").apply { deleteRecursively(); mkdirs() }
        val blue = Color.rgb(0, 0, 200)
        val green = Color.rgb(0, 160, 0)
        val red = Color.rgb(255, 0, 0)
        val template = universityTemplate(File(dir, "university-template.docx"), png(120, 60, blue, File(dir, "univ.png")))
        val logo = png(200, 100, green, File(dir, "logo.png"))

        // 1. Imported through the real import path: the formatting is read from the Word file.
        val before = runBlocking { vm.allTemplates.first() }.map { it.id }.toSet()
        vm.importTemplateFromUri(FileProvider.getUriForFile(context, context.packageName + ".fileprovider", template), "University template", template.name)
        await("template imported", 30_000) { runBlocking { vm.allTemplates.first() }.any { it.id !in before && it.fileType == "DOCX" } }
        val entity = runBlocking { vm.allTemplates.first() }.first { it.id !in before && it.fileType == "DOCX" }
        val profile = com.example.template.domain.TemplateProfile.fromJson(entity.profileJson)
        assertEquals("DOCX", profile.layoutSource)
        assertEquals("UNIVERSITY HEADER TEST", profile.headerText)
        assertEquals("{PAGE}", profile.footerText)
        assertTrue(profile.headerImage, profile.headerImage.startsWith("word/media/univ.png|90|45|center"))
        assertTrue(profile.pageBorders, profile.pageBorders.contains("top:single:3.0:20:#FF0000"))
        assertEquals("page", profile.pageBorderOffsetFrom)
        assertEquals(595 to 842, profile.pageWidthPt to profile.pageHeightPt)
        assertEquals("Times New Roman" to 12, profile.bodyFont to profile.baseFontSizePt)
        tap("nav_templates")
        tap("st_filter_uploaded")
        await("only uploads listed") { !exists("st_category_reports") }
        // The details are a child of the clickable card, whose semantics merge its children.
        await("template card") { compose.onAllNodes(hasTestTag("template_format_${entity.id}"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        shot("template-docx-formatting")

        // 2. New thesis with the template and a logo picked on the form (staged before the project exists).
        tap("nav_thesis")
        val thesis = vm.thesis
        thesis.stagePendingLogo({ logo.inputStream() }, "image/png")
        await("logo staged") { thesis.state.value.pendingLogo != null && thesis.state.value.busy == null }
        thesis.createProject(ThesisMeta("Template and logo test", AcademicLevel.MASTER, "fr", studentName = "Étudiant Test", university = "Université de Test", templateId = entity.id), aiAssist = false)
        await("project created") { thesis.state.value.snapshot != null && thesis.state.value.busy == null && thesis.state.value.screen == com.example.thesis.ThesisScreen.PROJECT }
        val logoId = thesis.state.value.snapshot!!.meta.logoAssetId
        assertNotNull("logo not attached", logoId)
        val projectId = thesis.state.value.projectId!!
        val intro = thesis.state.value.snapshot!!.sections.first { it.kind == SectionKind.INTRODUCTION }.id
        compose.runOnUiThread { thesis.selectSection(intro) }
        await("editor") { thesis.state.value.draft != null }
        compose.runOnUiThread { thesis.updateDraft((1..40).joinToString("\n") { "Paragraphe $it du mémoire, avec assez de texte pour remplir plusieurs pages du document." }) }
        tap("thesis_save_changes")
        await("saved") { thesis.state.value.busy == null && thesis.state.value.draft?.dirty == false }

        // Closed and reopened: the logo is still the project's logo, its file still there.
        compose.runOnUiThread { thesis.closeWorkspace(); thesis.openProject(projectId) }
        await("reopened") { thesis.state.value.snapshot != null && thesis.state.value.busy == null && thesis.state.value.screen == com.example.thesis.ThesisScreen.PROJECT }
        assertEquals(logoId, thesis.state.value.snapshot!!.meta.logoAssetId)
        assertTrue(File(thesis.state.value.assets.single { it.id == logoId }.path).isFile)

        // 3. Preview from the workspace: a viewer only (no export buttons or export card), the
        // whole page width inside the viewport, navigation under the page, page counter right.
        tap("thesis_open_preview")
        await("preview built", 90_000) { thesis.state.value.busy == null && thesis.state.value.previewFile != null && exists("thesis_page_0") }
        for (tag in listOf("thesis_export_docx", "thesis_export_pdf", "thesis_export_result", "thesis_export_section")) assertTrue("$tag on the preview", !exists(tag))
        val viewport = compose.onNodeWithTag("thesis_preview_viewport").fetchSemanticsNode().boundsInRoot
        // Unclipped layout size and position (boundsInRoot is clipped to what is visible: a page
        // taller than the screen scrolls vertically, which is allowed; sideways it must fit).
        val pageNode = compose.onNodeWithTag("thesis_page_0").fetchSemanticsNode()
        val pageLeft = pageNode.positionInRoot.x
        val pageRight = pageLeft + pageNode.size.width
        assertTrue("page cut off sideways: $pageLeft..$pageRight, viewport $viewport", pageLeft >= viewport.left - 1 && pageRight <= viewport.right + 1)
        assertTrue("page is not A4-shaped: ${pageNode.size}", Math.abs(pageNode.size.height.toFloat() / pageNode.size.width - 842f / 595f) < 0.03f)
        for (arrow in listOf("thesis_page_prev", "thesis_page_next")) {
            val a = compose.onNodeWithTag(arrow).fetchSemanticsNode().boundsInRoot
            assertTrue("$arrow covers the page area: $a viewport $viewport", a.top >= viewport.bottom - 1)
        }
        val pages = thesis.state.value.previewPages.takeIf { it > 0 } ?: PdfRenderer(ParcelFileDescriptor.open(thesis.state.value.previewFile!!, ParcelFileDescriptor.MODE_READ_ONLY)).use { it.pageCount }
        assertTrue("expected several pages, got $pages", pages >= 3)
        assertTrue(textShown("Page 1 / $pages"))
        shot("thesis-preview-viewer-page1")
        tap("thesis_page_next")
        await("page 2") { textShown("Page 2 / $pages") && exists("thesis_page_1") }
        shot("thesis-preview-viewer-page2")
        // The preview is the PDF: template and logo are drawn in it.
        val previewPdf = thesis.state.value.previewFile!!
        assertTrue("logo missing on the preview cover", countColor(previewPdf, 0, green, android.graphics.RectF(0.3f, 0f, 0.7f, 0.4f)) > 500)
        assertTrue("header image missing in the preview", countColor(previewPdf, 1, blue, android.graphics.RectF(0.3f, 0f, 0.7f, 0.15f)) > 500)
        tap("thesis_preview_back_workspace")
        await("workspace") { thesis.state.value.screen == com.example.thesis.ThesisScreen.PROJECT && thesis.state.value.projectId == projectId }

        // Full thesis from a one-section preview.
        compose.runOnUiThread { thesis.buildSectionPreview(intro) }
        await("section preview", 60_000) { thesis.state.value.busy == null && thesis.state.value.previewSectionId == intro && exists("thesis_page_0") }
        tap("thesis_preview_whole")
        await("whole thesis", 90_000) { thesis.state.value.busy == null && thesis.state.value.previewSectionId == null && thesis.state.value.previewFile != null }
        tap("thesis_preview_back_workspace")
        await("workspace 2") { thesis.state.value.screen == com.example.thesis.ThesisScreen.PROJECT }

        // 4. PDF export: logo on the cover, the template's header image and red border on the pages.
        tap("thesis_export_pdf")
        await("pdf", 90_000) { thesis.state.value.busy == null && thesis.state.value.lastExport?.format == "PDF" }
        val pdf = File(thesis.state.value.lastExport!!.document.filePath)
        assertTrue("logo missing on the PDF cover", countColor(pdf, 0, green, android.graphics.RectF(0.3f, 0f, 0.7f, 0.4f)) > 500)
        for (page in listOf(0, 2)) {
            assertTrue("header image missing on PDF page ${page + 1}", countColor(pdf, page, blue, android.graphics.RectF(0.3f, 0f, 0.7f, 0.15f)) > 500)
            // Border lines 20 pt from each edge, 3 pt wide.
            assertTrue("left border missing on page ${page + 1}", countColor(pdf, page, red, android.graphics.RectF(0f, 0.1f, 0.06f, 0.9f)) > 300)
            assertTrue("top border missing on page ${page + 1}", countColor(pdf, page, red, android.graphics.RectF(0.1f, 0f, 0.9f, 0.04f)) > 300)
        }
        shot("thesis-workspace-after-pdf")
        tap("thesis_export_dismiss")
        await("result dismissed") { thesis.state.value.lastExport == null }

        // 5. Word export: the template package (header with its image, border) and the logo on the cover.
        tap("thesis_export_docx")
        await("docx", 90_000) { thesis.state.value.busy == null && thesis.state.value.lastExport?.format == "DOCX" }
        ZipFile(File(thesis.state.value.lastExport!!.document.filePath)).use { z ->
            val xml = z.getInputStream(z.getEntry("word/document.xml")).bufferedReader().readText()
            assertTrue("page border lost", xml.contains("w:pgBorders"))
            assertTrue("template header lost", z.entries().toList().any { it.name.startsWith("word/header") && z.getInputStream(it).bufferedReader().readText().contains("UNIVERSITY HEADER TEST") })
            val media = z.entries().toList().filter { it.name.startsWith("word/media/") }.map { z.getInputStream(it).readBytes() }
            assertTrue("logo missing in the Word file", media.any { it.contentEquals(logo.readBytes()) })
            assertTrue("template header image missing in the Word file", media.any { it.contentEquals(File(dir, "univ.png").readBytes()) })
            assertTrue("Paragraphe 40 du mémoire" in xml)
        }

        // 6. The logo can be removed: none on the cover afterwards (no empty placeholder).
        compose.runOnUiThread { thesis.removeLogo() }
        await("logo removed") { thesis.state.value.busy == null && thesis.state.value.snapshot?.meta?.logoAssetId == null }
        compose.runOnUiThread { thesis.export("PDF") }
        await("pdf without logo", 90_000) { thesis.state.value.busy == null && thesis.state.value.lastExport?.format == "PDF" && thesis.state.value.lastExport!!.document.filePath != pdf.path }
        assertEquals(0, countColor(File(thesis.state.value.lastExport!!.document.filePath), 0, green))
        assertTrue("crash report written", CrashLog.read(context) == null)
    }

    // ------------------------------------------------------------------ Smart Templates

    /**
     * The template library on the device: categories, a subcategory default set in the UI and
     * kept after the screen is recreated, built-in details with a sample page, Auto Format in
     * Settings, and built-in documents drawn as real PDF/DOCX/XLSX/PPTX files with their styles.
     */
    @Test
    fun smartTemplates_library_defaults_settings_andBuiltInOutputs() {
        language("en", "Thesis")
        runBlocking { vm.smartTemplates.clearDefault("letters.recommendation") }
        tap("nav_templates")
        await("library") { exists("templates_list") }
        // All six categories are listed (the list is lazy: scrolled to each).
        for (c in com.example.smarttemplates.TemplateCatalog.categories) {
            scrollToTag("st_category_${c.id}")
            assertTrue("category ${c.id}", exists("st_category_${c.id}"))
        }
        shot("st-library")
        scrollToTag("st_category_letters")
        tap("st_category_letters")
        await("letters") { exists("st_category_screen") && exists("st_sub_letters.recommendation") }
        shot("st-letters")
        // Default for Recommendation Letters: the modern style (only that subcategory).
        tap("st_set_default_letters.recommendation_builtin.letters.modern")
        await("default saved") { runBlocking { vm.smartTemplates.defaults() }["letters.recommendation"] == "builtin.letters.modern" }
        await("badge") { exists("st_default_badge_letters.recommendation_builtin.letters.modern") }
        assertEquals(null, runBlocking { vm.smartTemplates.defaults() }["letters.business"])
        // Details with a sample page drawn with the template.
        tap("st_preview_letters.recommendation_builtin.letters.modern")
        await("details") { exists("st_details_dialog") }
        await("sample page", 30_000) { exists("st_sample_page") }
        shot("st-details-letter-modern")
        tap("st_details_close")
        // Favorite.
        tap("st_favorite_letters.recommendation_builtin.letters.classic")
        await("favorite") { vm.templateLibrary.value.single { it.id == "builtin.letters.classic" }.favorite }
        // Kept after the activity is recreated (Room).
        compose.activityRule.scenario.recreate()
        await("recreated") { exists("nav_templates") }
        assertEquals("builtin.letters.modern", runBlocking { vm.smartTemplates.defaults() }["letters.recommendation"])
        assertEquals("SUBCATEGORY_DEFAULT", runBlocking { vm.smartTemplates.resolve("letters.recommendation", "DOCX") }.source.name)
        // Settings: Auto Format.
        tap("nav_settings")
        await("settings") { vm.currentTab.value == AppNavTab.SETTINGS }
        // Smart Templates settings: collapsed at first, the whole header opens and closes them.
        scrollToTag("st_settings_header")
        assertTrue("settings collapsed at first", !exists("st_auto_format_switch"))
        tap("st_settings_header")
        await("settings expanded") { exists("st_auto_format_switch") && exists("st_manage_templates") }
        scrollToTag("st_auto_format_switch")
        val before = vm.autoFormat.value
        tap("st_auto_format_switch")
        await("auto format toggled") { vm.autoFormat.value != before }
        tap("st_auto_format_switch")
        await("auto format back") { vm.autoFormat.value == before }
        shot("st-settings")
        scrollToTag("st_settings_header")
        tap("st_settings_header")
        await("settings collapsed") { !exists("st_auto_format_switch") }

        // Built-in outputs on the device: PDF pages drawn with the style (border, top band, Arabic).
        val dir = File(context.cacheDir, "st-device").apply { deleteRecursively(); mkdirs() }
        val formal = com.example.smarttemplates.BuiltInTemplates.byId("builtin.letters.formal")!!
        val pdf = File(dir, "formal.pdf")
        com.example.document.pdf.PdfEngine().generatePdf(com.example.smarttemplates.SampleContent.word("letters", "Formal"), pdf, formal.profile)
        // Single border 24 pt from the page edge on the left (the margin there holds no text).
        assertTrue("formal page border", countDark(pdf, 0, android.graphics.RectF(0.02f, 0.2f, 0.07f, 0.8f)) > 200)
        val modern = com.example.smarttemplates.BuiltInTemplates.byId("builtin.reports.modern")!!
        val pdf2 = File(dir, "modern.pdf")
        com.example.document.pdf.PdfEngine().generatePdf(com.example.smarttemplates.SampleContent.word("reports", "Modern"), pdf2, modern.profile)
        assertTrue("modern top band", countColor(pdf2, 0, Color.parseColor("#0F766E"), android.graphics.RectF(0.1f, 0f, 0.9f, 0.04f)) > 1000)
        PdfRenderer(ParcelFileDescriptor.open(pdf2, ParcelFileDescriptor.MODE_READ_ONLY)).use { r ->
            r.openPage(0).use { p -> assertTrue("A4", p.width in 590..600 && p.height in 837..847) }
        }
        val arabic = File(dir, "ar.pdf")
        com.example.document.pdf.PdfEngine().generatePdf(com.example.document.model.WordDocumentModel("طلب إجازة", sections = listOf(
            com.example.document.model.WordSection("", listOf("أتقدم إليكم بطلب إجازة لمدة أسبوع ابتداء من الأحد القادم."))),
            letter = com.example.document.model.LetterParts(sender = listOf("أمينة بلقاسم"), subject = "الموضوع: طلب إجازة", signatureName = "أمينة بلقاسم", showTitle = true)),
            arabic, com.example.smarttemplates.BuiltInTemplates.byId("builtin.requests.formal")!!.profile.copy(rtl = true))
        // Right-to-left: the body text sits against the right margin.
        val right = countDark(arabic, 0, android.graphics.RectF(0.6f, 0.08f, 0.9f, 0.5f))
        val left = countDark(arabic, 0, android.graphics.RectF(0.1f, 0.08f, 0.4f, 0.5f))
        assertTrue("Arabic text not right-aligned: right=$right left=$left", right > left)
        // Native files of the other kinds open as valid packages.
        for (t in com.example.smarttemplates.BuiltInTemplates.all) {
            val f = File(dir, t.id + "." + t.format.lowercase())
            when (t.kind) {
                com.example.smarttemplates.OutputKind.SPREADSHEET -> com.example.document.xlsx.XlsxEngine().generateXlsx(com.example.smarttemplates.SampleContent.sheet("S"), f, t.profile)
                com.example.smarttemplates.OutputKind.PRESENTATION -> com.example.document.pptx.PptxEngine().generatePptx(com.example.smarttemplates.SampleContent.slides("S"), f, t.profile)
                com.example.smarttemplates.OutputKind.DOCUMENT -> com.example.document.docx.DocxEngine().generateDocx(com.example.smarttemplates.SampleContent.word(t.categoryId, "S"), f, t.profile)
            }
            assertEquals(t.id, null, com.example.document.DocumentFileValidator.validate(f, t.format.lowercase()))
        }
        assertTrue("crash report written", CrashLog.read(context) == null)
    }

    /** Scrolls every scrollable container (lazy ones included) until the node tagged [tag] is composed. */
    private fun scrollToTag(tag: String) {
        val containers = compose.onAllNodes(hasScrollAction())
        repeat(containers.fetchSemanticsNodes().size) { i -> try { containers[i].performScrollToNode(hasTestTag(tag)) } catch (e: Throwable) { } }
        compose.waitForIdle()
        await("node $tag") { exists(tag) }
    }

    /** Dark pixels (lines, text) of [page] inside [area] (fractions of the page). */
    private fun countDark(pdf: File, page: Int, area: android.graphics.RectF): Int =
        PdfRenderer(ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)).use { r ->
            r.openPage(page).use { p ->
                val bmp = Bitmap.createBitmap(p.width * 2, p.height * 2, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
                p.render(bmp, null, android.graphics.Matrix().apply { setScale(2f, 2f) }, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                var n = 0
                for (y in (area.top * bmp.height).toInt() until (area.bottom * bmp.height).toInt()) for (x in (area.left * bmp.width).toInt() until (area.right * bmp.width).toInt()) {
                    val c = bmp.getPixel(x, y)
                    if ((Color.red(c) + Color.green(c) + Color.blue(c)) / 3 < 160) n++
                }
                bmp.recycle()
                n
            }
        }
}
