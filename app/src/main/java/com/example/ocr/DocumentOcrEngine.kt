package com.example.ocr

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.document.model.ScannedPageData
import com.example.extraction.ExtractedPage
import com.example.extraction.ExtractionText
import com.example.extraction.DocumentExtractionEngine
import com.example.extraction.ExtractionSource
import com.example.extraction.OcrPageReader
import com.example.ocr.paddle.ModelInstaller
import com.example.ocr.paddle.ModelIntegrityException
import com.example.ocr.paddle.PaddleModels
import com.example.ocr.paddle.PaddleOrtOcrEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** The OCR engine could not be started (models missing/corrupt or native library failure). */
class OcrUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Result of recognizing a batch of pages. */
data class OcrBatchResult(
    val pages: List<ScannedPageData>,
    /** Number of pages whose image could not be read or recognized (they are kept, empty). */
    val failedPages: Int,
    /** Why each failed page failed ("Page 2: image could not be read"), for the user and the logs. */
    val errors: List<String> = emptyList()
)

/**
 * On-device OCR: PaddleOCR PP-OCRv5 mobile (text detection, Arabic and Latin recognition) run by
 * ONNX Runtime on the CPU ([PaddleOrtOcrEngine]). The models are bundled with the app and
 * verified by SHA-256 before use; nothing is downloaded and page images never leave the device
 * (there is no network code here). Recognition is sequential and memory-bounded.
 *
 * Chat, the Thesis Builder and the other features all extract through [DocumentExtractionEngine];
 * [processPages] is the Chat scanner's entry point onto that same pipeline.
 */
class DocumentOcrEngine(private val context: Context) {

    /** Model loading time (ms) of each model used by the last session. */
    @Volatile var lastLoadTimes: Map<String, Long> = emptyMap()
        private set

    private val installer by lazy {
        ModelInstaller(File(context.noBackupFilesDir, PaddleModels.DIR)) { path -> context.assets.open(path) }
    }

    /** Low-memory devices keep one recognition model open at a time. */
    private val lowMemory: Boolean by lazy {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        am == null || am.isLowRamDevice || am.memoryClass <= 128
    }

    /**
     * Opens the engine, runs [block] with a page reader, and releases the engine and its native
     * memory. One session at a time; cancelling the coroutine stops a recognition in progress.
     * @throws OcrUnavailableException when the models are missing or damaged or the engine
     *   cannot start
     */
    suspend fun <T> session(language: OcrLanguage, appLanguage: String, block: suspend (OcrPageReader) -> T): T = withContext(Dispatchers.Default) {
        engineMutex.withLock {
            val engine = try {
                PaddleOrtOcrEngine(installer, maxRecognizers = if (lowMemory) 1 else 2).also { e ->
                    try { withContext(Dispatchers.IO) { e.warmUp() } } catch (t: Throwable) { e.close(); throw t }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ModelIntegrityException) {
                throw OcrUnavailableException("OCR models are missing or damaged: ${e.message}", e)
            } catch (e: Throwable) {
                // Native library or session failure (also UnsatisfiedLinkError, OutOfMemoryError).
                throw OcrUnavailableException("OCR engine could not be initialized", e)
            }
            // Cancelling the coroutine aborts a recognition that is in progress.
            val cancelHandle = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
                if (cause is CancellationException) engine.stop()
            }
            try {
                block(OcrPageReader(engine, language.preferredModel(appLanguage)))
            } finally {
                cancelHandle?.dispose()
                lastLoadTimes = LinkedHashMap(engine.loadTimes)
                engine.close()
            }
        }
    }

    /**
     * Recognizes [pageUris] in order (the Chat scanner) through the shared extraction pipeline.
     * @param firstPageIndex index of the first page within the whole document (used when pages
     *   are added to an existing scan)
     * @throws OcrUnavailableException when the engine cannot start; single unreadable pages do
     *   not throw, they are returned empty and counted in [OcrBatchResult.failedPages]
     */
    suspend fun processPages(
        pageUris: List<Uri>,
        language: OcrLanguage,
        appLanguage: String,
        firstPageIndex: Int = 0,
        onProgress: (page: Int, total: Int) -> Unit = { _, _ -> }
    ): OcrBatchResult {
        val doc = DocumentExtractionEngine(context, this).extract(
            pageUris.map { ExtractionSource(it, isPdf = false) }, language, appLanguage
        ) { page, total -> onProgress(page, total) }
        val errors = ArrayList<String>()
        var failed = 0
        val pages = pageUris.mapIndexed { offset, uri ->
            val pageIndex = firstPageIndex + offset
            val page = doc.pages.getOrNull(offset)
            if (page == null || (page.error != null && page.error != "no_text_found")) {
                failed++
                errors += "Page ${pageIndex + 1}: ${page?.error ?: "not_processed"}"
                Log.w(TAG, "Page ${pageIndex + 1} could not be recognized: ${page?.error}")
                // An unreadable page is kept (empty) so the user can type its text.
                ScannedDocumentStructureReconstructor.reconstructPage(pageIndex = pageIndex, imageUriString = uri.toString(), rawText = "")
            } else {
                page.copy(index = pageIndex).toScannedPage(uri.toString())
            }
        }
        return OcrBatchResult(pages, failed, errors)
    }

    private companion object {
        const val TAG = "DocumentOcrEngine"

        /** One recognition at a time: the models are memory-heavy. */
        val engineMutex = Mutex()
    }
}

/**
 * The extracted page in the review screen's form: its text with the editor conventions ("#"
 * headings, list markers, "a | b" table rows), parsed back into sections for document
 * generation, with the structured page attached.
 */
fun ExtractedPage.toScannedPage(imageUri: String): ScannedPageData =
    ScannedDocumentStructureReconstructor.reconstructPage(
        pageIndex = index,
        imageUriString = imageUri,
        rawText = ExtractionText.page(this),
        confidence = if (isEmpty) 0f else (confidence ?: 0.95f)
    ).copy(extraction = this)
