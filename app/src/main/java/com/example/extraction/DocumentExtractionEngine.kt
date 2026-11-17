package com.example.extraction

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import com.example.ocr.DocumentOcrEngine
import com.example.ocr.OcrLanguage
import com.example.ocr.PageImageLoader
import com.example.template.pdf.PdfDocumentReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/** A file to extract: an image (one page) or a PDF (its pages, or only [pages] when given). */
data class ExtractionSource(val uri: Uri, val isPdf: Boolean, val pages: Set<Int>? = null)

/**
 * The reusable extraction engine for Chat and the Thesis Builder. Images and scanned PDF pages
 * go through on-device OCR ([OcrPageReader]); PDF pages that carry their text are read directly
 * (no OCR). Everything runs on the device, page by page, in the background and can be
 * cancelled. Page contents are not logged.
 */
class DocumentExtractionEngine(private val context: Context, private val ocr: DocumentOcrEngine = DocumentOcrEngine(context)) {

    /** Progress: page being processed (1-based) of the total. */
    fun interface Progress { fun onPage(page: Int, total: Int) }

    private val cacheDir: File get() = File(context.cacheDir, "extraction").apply { mkdirs() }

    /** True when [uri] is a PDF (by type, or by its first bytes). */
    fun isPdf(uri: Uri): Boolean {
        val type = try { context.contentResolver.getType(uri) } catch (e: Exception) { null }
        if (type == "application/pdf") return true
        if (type != null && type.startsWith("image/")) return false
        return try {
            context.contentResolver.openInputStream(uri)?.use { s -> ByteArray(5).let { b -> s.read(b) == 5 && String(b, Charsets.ISO_8859_1) == "%PDF-" } } ?: false
        } catch (e: Exception) { false }
    }

    /** Number of pages of a PDF (0 when it cannot be opened). */
    fun pdfPageCount(uri: Uri): Int = try {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { fd -> PdfRenderer(fd).use { it.pageCount } } ?: 0
    } catch (e: Exception) { 0 }

    private fun copyToCache(uri: Uri): File {
        val f = File(cacheDir, "src-" + System.nanoTime() + ".pdf")
        context.contentResolver.openInputStream(uri)?.use { input -> FileOutputStream(f).use { input.copyTo(it) } }
            ?: throw java.io.IOException("Cannot open the file")
        return f
    }

    /** Renders one PDF page for OCR (about 250 dpi, at most 3000 px), white background, saved for the review. */
    private fun render(renderer: PdfRenderer, index: Int): Pair<Bitmap, String> {
        renderer.openPage(index).use { page ->
            val scale = (250f / 72f).coerceAtMost(3000f / maxOf(page.width, page.height))
            val w = (page.width * scale).toInt().coerceAtLeast(1)
            val h = (page.height * scale).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
            val file = File(cacheDir, "page-" + System.nanoTime() + ".png")
            FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            return bmp to Uri.fromFile(file).toString()
        }
    }

    /**
     * Extracts [sources] in order (pages numbered across all sources).
     * @throws com.example.ocr.OcrUnavailableException when OCR is needed but cannot start
     */
    suspend fun extract(
        sources: List<ExtractionSource>,
        language: OcrLanguage,
        appLanguage: String,
        progress: Progress = Progress { _, _ -> }
    ): ExtractedDocument = withContext(Dispatchers.Default) {
        // Plan: for PDFs, read the text pages directly first; only the others need OCR.
        data class Job(val source: ExtractionSource, val pdfPage: Int?, val direct: ExtractedPage?, val pdfFile: File?, val sourceIndex: Int = 0)
        val jobs = ArrayList<Job>()
        val errors = HashMap<Int, String>()
        val tempFiles = ArrayList<File>()
        try {
            for ((si, src) in sources.withIndex()) {
                currentCoroutineContext().ensureActive()
                if (!src.isPdf) { jobs += Job(src, null, null, null, si); continue }
                val file = try { copyToCache(src.uri).also { tempFiles += it } } catch (e: Exception) {
                    errors[jobs.size] = "pdf_unreadable"; jobs += Job(src, 0, null, null, si); continue
                }
                val direct: Map<Int, ExtractedPage?> = try {
                    PdfTextExtraction.readPages(PdfDocumentReader.open(file)).toMap()
                } catch (e: Exception) {
                    // Encrypted or damaged structure: every page goes to OCR.
                    Log.w(TAG, "PDF text could not be read directly: ${e.javaClass.simpleName}")
                    emptyMap()
                }
                val count = try { ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use { it.pageCount } } } catch (e: Exception) { direct.size }
                if (count == 0) { errors[jobs.size] = "pdf_unreadable"; jobs += Job(src, 0, null, null, si); continue }
                for (i in 0 until count) if (src.pages == null || i in src.pages) jobs += Job(src, i, direct[i], file, si)
            }

            val pages = arrayOfNulls<ExtractedPage>(jobs.size)
            // Text pages need no OCR engine.
            jobs.forEachIndexed { i, j -> j.direct?.let { pages[i] = it.copy(index = i) } }
            val needOcr = jobs.indices.filter { pages[it] == null && errors[it] == null }
            if (needOcr.isNotEmpty()) {
                ocr.session(language, appLanguage) { reader ->
                    var renderer: PdfRenderer? = null
                    var rendererFile: File? = null
                    var fd: ParcelFileDescriptor? = null
                    try {
                        for (i in needOcr) {
                            currentCoroutineContext().ensureActive()
                            progress.onPage(i + 1, jobs.size)
                            val j = jobs[i]
                            pages[i] = try {
                                if (j.pdfPage == null) {
                                    val bmp = PageImageLoader.load(context, j.source.uri)
                                    try { reader.read(bmp, i, PageSource.IMAGE_OCR, j.source.uri.toString()) } finally { bmp.recycle() }
                                } else {
                                    if (rendererFile != j.pdfFile) {
                                        renderer?.close(); fd?.close()
                                        fd = ParcelFileDescriptor.open(j.pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
                                        renderer = PdfRenderer(fd!!)
                                        rendererFile = j.pdfFile
                                    }
                                    val (bmp, image) = render(renderer!!, j.pdfPage)
                                    try { reader.read(bmp, i, PageSource.PDF_OCR, image) } finally { bmp.recycle() }
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: OutOfMemoryError) {
                                ExtractedPage(i, if (j.pdfPage == null) PageSource.IMAGE_OCR else PageSource.PDF_OCR, emptyList(), error = "out_of_memory")
                            } catch (e: Exception) {
                                Log.w(TAG, "Page ${i + 1} could not be read: ${e.javaClass.simpleName}")
                                ExtractedPage(i, if (j.pdfPage == null) PageSource.IMAGE_OCR else PageSource.PDF_OCR, emptyList(),
                                    error = if (j.pdfPage == null) "image_unreadable" else "page_unreadable")
                            }
                        }
                    } finally {
                        try { renderer?.close() } catch (e: Exception) { }
                        try { fd?.close() } catch (e: Exception) { }
                    }
                }
            }
            for ((i, e) in errors) pages[i] = ExtractedPage(i, PageSource.PDF_TEXT, emptyList(), error = e)
            ExtractedDocument(pages.mapIndexed { i, p ->
                (p ?: ExtractedPage(i, PageSource.IMAGE_OCR, emptyList(), error = "not_processed"))
                    .copy(index = i, sourceIndex = jobs.getOrNull(i)?.sourceIndex ?: 0, sourcePage = jobs.getOrNull(i)?.pdfPage)
            })
        } finally {
            tempFiles.forEach { it.delete() }
        }
    }

    private companion object { const val TAG = "DocumentExtraction" }
}
