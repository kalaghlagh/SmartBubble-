package com.example.ocr

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.document.model.ScannedPageData
import com.example.extraction.ExtractedPage
import com.example.extraction.ExtractionText
import com.example.extraction.OcrPageReader
import com.example.extraction.PageSource
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
 * On-device OCR for scanned pages using Tesseract (Tesseract4Android) with bundled Arabic,
 * English and French models. Page images never leave the device: there is no network code
 * here. Recognition is sequential and memory-bounded (one downsampled bitmap at a time).
 */
class DocumentOcrEngine(private val context: Context) {

    /**
     * Opens the engines for [language] (installing the bundled models if needed), runs [block]
     * with a page reader, and releases them. One session at a time; cancelling the coroutine
     * stops a recognition in progress.
     * @throws OcrUnavailableException when the engine cannot start
     */
    suspend fun <T> session(language: OcrLanguage, appLanguage: String, block: suspend (OcrPageReader) -> T): T = withContext(Dispatchers.Default) {
        engineMutex.withLock {
            val dataPath = try {
                withContext(Dispatchers.IO) { TessDataInstaller.ensureInstalled(context, language.models).absolutePath }
            } catch (e: Exception) {
                throw OcrUnavailableException("OCR language data could not be installed", e)
            }
            val languages = language.tesseractLanguages(appLanguage)
            val engines = ArrayList<TessBaseAPI>()
            val lock = Any()
            var recycled = false
            fun open(langs: String): TessBaseAPI? {
                val t = try { TessBaseAPI() } catch (e: Throwable) { return null }
                val ok = try { t.init(dataPath, langs, TessBaseAPI.OEM_LSTM_ONLY) } catch (e: Throwable) { false }
                if (!ok) { try { t.recycle() } catch (e: Throwable) { }; return null }
                t.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
                // Scanned pages are about 300 dpi; without this Tesseract assumes 70 dpi for bitmaps.
                t.setVariable("user_defined_dpi", "300")
                synchronized(lock) {
                    if (recycled) { t.recycle(); return null }
                    engines += t
                }
                return t
            }
            // Cancelling the coroutine aborts a recognition that is in progress.
            val cancelHandle = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
                if (cause is CancellationException) synchronized(lock) {
                    if (!recycled) engines.forEach { e -> try { e.stop() } catch (x: Throwable) { /* finished */ } }
                }
            }
            try {
                val page = open(languages) ?: throw OcrUnavailableException("OCR engine could not be initialized")
                var arabic: TessBaseAPI? = null
                var arabicTried = false
                var latin: TessBaseAPI? = null
                var latinTried = false
                val latinModels = language.models.filter { it == "fra" || it == "eng" }
                val reader = OcrPageReader(
                    pageEngine = page,
                    pageArabicFirst = languages.startsWith("ara"),
                    arabicEngine = {
                        if (!arabicTried && "ara" in language.models) { arabicTried = true; arabic = open("ara") }
                        arabic
                    },
                    latinEngine = {
                        // Only useful when the page engine also had Arabic (otherwise it is the same models).
                        if (!latinTried && latinModels.isNotEmpty() && "ara" in language.models) { latinTried = true; latin = open(latinModels.joinToString("+")) }
                        latin
                    }
                )
                block(reader)
            } finally {
                cancelHandle?.dispose()
                synchronized(lock) {
                    recycled = true
                    engines.forEach { e -> try { e.recycle() } catch (x: Throwable) { /* already released */ } }
                }
            }
        }
    }

    /**
     * Recognizes [pageUris] in order (the Chat scanner).
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
    ): OcrBatchResult = session(language, appLanguage) { reader ->
        val pages = ArrayList<ScannedPageData>(pageUris.size)
        var failed = 0
        val errors = ArrayList<String>()
        for ((offset, uri) in pageUris.withIndex()) {
            currentCoroutineContext().ensureActive()
            val pageIndex = firstPageIndex + offset
            onProgress(offset + 1, pageUris.size)
            val page = try {
                val bitmap = PageImageLoader.load(context, uri)
                try { reader.read(bitmap, pageIndex, PageSource.IMAGE_OCR, uri.toString()) } finally { bitmap.recycle() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                Log.w(TAG, "Page ${pageIndex + 1}: not enough memory to recognize the image")
                errors += "Page ${pageIndex + 1}: not enough memory"
                null
            } catch (e: Exception) {
                Log.w(TAG, "Page ${pageIndex + 1} could not be recognized", e)
                errors += "Page ${pageIndex + 1}: ${e.javaClass.simpleName}: ${e.message.orEmpty().take(200)}"
                null
            }
            currentCoroutineContext().ensureActive() // a stopped recognition is not a result
            if (page == null) failed++
            // An unreadable page is kept (empty) so the user can type its text.
            pages += page?.toScannedPage(uri.toString()) ?: ScannedDocumentStructureReconstructor.reconstructPage(
                pageIndex = pageIndex, imageUriString = uri.toString(), rawText = ""
            )
        }
        OcrBatchResult(pages, failed, errors)
    }

    private companion object {
        const val TAG = "DocumentOcrEngine"

        /** One recognition at a time: Tesseract is memory-heavy and not thread-safe. */
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
