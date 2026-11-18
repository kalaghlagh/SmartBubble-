package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.agent.files.ImportStatus
import com.example.agent.files.LibraryImporter
import com.example.ui.AppNavTab
import com.example.ui.SmartBubbleViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * On a device: files imported from the phone (through the same ContentResolver path the system
 * file picker returns) land in the Library with their bytes unchanged, and the Agent reads them
 * through the app's existing extraction engine (PDF text layer; PaddleOCR on ONNX Runtime for an
 * image). The source file is never modified. The Library import action and the Agent's file
 * picker are on screen and work.
 */
@RunWith(AndroidJUnit4::class)
class AgentFilesDeviceTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val vm: SmartBubbleViewModel get() = ViewModelProvider(compose.activity)[SmartBubbleViewModel::class.java]
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun await(step: String, timeout: Long = 30_000, condition: () -> Boolean) {
        try { compose.waitUntil(timeout) { condition() } } catch (e: Throwable) {
            val tree = try { compose.onAllNodes(isRoot()).printToString(maxDepth = 14) } catch (t: Throwable) { "tree unavailable: $t" }
            throw AssertionError("Timed out: $step\n$tree", e)
        }
    }

    private fun tap(tag: String) {
        await("node $tag") { exists(tag) }
        compose.onNodeWithTag(tag).performClick()
        compose.waitForIdle()
    }

    private val stamp = System.currentTimeMillis()

    private fun makePdf(): File {
        val dir = File(context.cacheDir, "phone-files").apply { mkdirs() }
        val doc = PdfDocument()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 16f }
        val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
        page.canvas.drawText("Annual report $stamp", 60f, 80f, paint)
        page.canvas.drawText("Revenue grew by twelve percent.", 60f, 120f, paint)
        doc.finishPage(page)
        val f = File(dir, "annual-$stamp.pdf")
        FileOutputStream(f).use { doc.writeTo(it) }
        doc.close()
        return f
    }

    private fun import(file: File) {
        vm.dismissImportResults()
        vm.importFilesToLibrary(listOf(Uri.fromFile(file)))
        await("import of ${file.name}") { vm.importResults.value != null && !vm.importing.value }
    }

    @Test
    fun pdfFromPhone_importedIntoLibrary_readByTheAgent_sourceUnchanged() {
        await("app started") { exists("nav_chat") }
        val pdf = makePdf()
        val before = pdf.readBytes()
        import(pdf)
        val outcome = vm.importResults.value!!.single()
        assertEquals(ImportStatus.IMPORTED, outcome.status)
        val doc = outcome.document!!
        assertEquals("PDF", doc.fileType)
        assertEquals(LibraryImporter.SOURCE, doc.providerUsed)
        assertArrayEquals("Library copy keeps the original bytes", before, File(doc.filePath).readBytes())

        // The same file again is not added twice.
        import(pdf)
        assertEquals(ImportStatus.DUPLICATE, vm.importResults.value!!.single().status)
        vm.dismissImportResults()

        // The Agent reads it through the existing extraction engine (by id, never by path).
        val text = runBlocking { vm.agent.files.read(vm.agent.files.resolve("library:${doc.id}")).text }
        assertTrue("extracted: $text", text.contains("Annual report $stamp"))
        assertTrue("extracted: $text", text.contains("twelve percent"))
        assertArrayEquals("source file unchanged", before, pdf.readBytes())
        assertArrayEquals("Library file unchanged by reading", before, File(doc.filePath).readBytes())

        // It is in the Library list, and the import action is on screen.
        compose.runOnUiThread { vm.setNavTab(AppNavTab.LIBRARY) }
        await("library") { exists("library_import") }
        compose.onNodeWithTag("library_import").assertIsDisplayed()
        await("document listed") { compose.onAllNodes(androidx.compose.ui.test.hasText("$stamp", substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun imageFromPhone_isReadWithTheExistingOcr() {
        await("app started") { exists("nav_chat") }
        val dir = File(context.cacheDir, "phone-files").apply { mkdirs() }
        val png = File(dir, "scan-$stamp.png")
        val bmp = Bitmap.createBitmap(1240, 600, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        Canvas(bmp).drawText("Water samples from the springs", 80f, 300f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 64f })
        // A stamp-specific mark keeps this image distinct from earlier runs (no duplicate).
        Canvas(bmp).drawText("$stamp", 80f, 500f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 40f })
        FileOutputStream(png).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        import(png)
        val outcome = vm.importResults.value!!.single()
        assertEquals(ImportStatus.IMPORTED, outcome.status)
        assertEquals("PNG", outcome.document!!.fileType)
        vm.dismissImportResults()
        val text = runBlocking { vm.agent.files.read(vm.agent.files.resolve("library:${outcome.document!!.id}")).text }
        assertTrue("OCR text: $text", text.contains("spring", ignoreCase = true))
    }

    @Test
    fun agentFilePicker_attachesALibraryFileToTheNextRequest() {
        await("app started") { exists("nav_chat") }
        val pdf = makePdf()
        import(pdf)
        val doc = vm.importResults.value!!.single().document!!
        vm.dismissImportResults()
        compose.runOnUiThread { vm.setNavTab(AppNavTab.CHAT) }
        tap("chat_attach_button")
        tap("chat_action_agent_files")
        await("picker") { exists("agent_file_picker") }
        tap("agent_files_tab_library")
        tap("agent_file_library:${doc.id}")
        tap("agent_files_done")
        await("chip") { exists("agent_selected_library:${doc.id}") }
        assertEquals(listOf("library:${doc.id}"), vm.agentState.value.selected.map { it.fileId })
        // Removing the chip withdraws the file from the next request.
        tap("agent_selected_library:${doc.id}")
        await("chip removed") { !exists("agent_selected_library:${doc.id}") }
    }
}
