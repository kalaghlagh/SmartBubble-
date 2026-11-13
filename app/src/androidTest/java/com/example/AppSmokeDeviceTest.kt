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
            Triple("en", "Thesis", "Chat"),
            Triple("fr", "Mémoire", "Discussion"),
            Triple("ar", "المذكرة", "المحادثة")
        )
        try {
            for ((code, thesisLabel, _) in languages) {
                language(code, thesisLabel)
                // Five items, in reading order: Chat first (right edge in Arabic, left edge otherwise).
                val tabs = listOf("nav_chat", "nav_thesis", "nav_templates", "nav_library", "nav_settings")
                tabs.forEach { assertTrue("$it missing in $code", exists(it)) }
                val xs = tabs.map { left(it) }
                if (code == "ar") assertTrue("Arabic bar is not right-to-left: $xs", xs.zipWithNext().all { (a, b) -> a > b })
                else assertTrue("$code bar is not left-to-right: $xs", xs.zipWithNext().all { (a, b) -> a < b })
                for ((tag, tab) in tabs.zip(listOf(AppNavTab.CHAT, AppNavTab.THESIS, AppNavTab.TEMPLATES, AppNavTab.LIBRARY, AppNavTab.SETTINGS))) {
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
            r.openPage(0).use { p ->
                // A4 in points (595 x 842), within rounding.
                assertTrue("not A4: ${p.width}x${p.height}", p.width in 590..600 && p.height in 837..847)
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
}
