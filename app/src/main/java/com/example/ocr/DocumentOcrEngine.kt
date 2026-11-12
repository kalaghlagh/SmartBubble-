package com.example.ocr

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import com.example.document.model.ScannedPageData
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
    val failedPages: Int
)

/**
 * On-device OCR for scanned pages using Tesseract (Tesseract4Android) with bundled Arabic,
 * English and French models. Page images never leave the device: there is no network code
 * here. Recognition is sequential and memory-bounded (one downsampled bitmap at a time).
 */
class DocumentOcrEngine(private val context: Context) {

    /**
     * Recognizes [pageUris] in order.
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
    ): OcrBatchResult = withContext(Dispatchers.Default) {
        engineMutex.withLock {
            val dataPath = try {
                withContext(Dispatchers.IO) {
                    TessDataInstaller.ensureInstalled(context, language.models).absolutePath
                }
            } catch (e: Exception) {
                throw OcrUnavailableException("OCR language data could not be installed", e)
            }

            val tess = try {
                TessBaseAPI()
            } catch (e: Throwable) {
                throw OcrUnavailableException("OCR engine could not be loaded", e)
            }
            val lock = Any()
            var recycled = false
            // Cancelling the coroutine aborts a recognition that is in progress.
            val cancelHandle = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
                if (cause is CancellationException) {
                    synchronized(lock) {
                        if (!recycled) try { tess.stop() } catch (e: Throwable) { /* already finished */ }
                    }
                }
            }
            try {
                val started = try {
                    tess.init(dataPath, language.tesseractLanguages(appLanguage), TessBaseAPI.OEM_LSTM_ONLY)
                } catch (e: Throwable) {
                    throw OcrUnavailableException("OCR engine could not be initialized", e)
                }
                if (!started) throw OcrUnavailableException("OCR engine could not be initialized")
                tess.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
                // Keep the gaps between columns so simple tables can be reconstructed.
                tess.setVariable("preserve_interword_spaces", "1")

                val pages = ArrayList<ScannedPageData>(pageUris.size)
                var failed = 0
                for ((offset, uri) in pageUris.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    val pageIndex = firstPageIndex + offset
                    onProgress(offset + 1, pageUris.size)
                    val page = try {
                        recognizePage(tess, uri, pageIndex)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: OutOfMemoryError) {
                        Log.w(TAG, "Page ${pageIndex + 1}: not enough memory to recognize the image")
                        null
                    } catch (e: Exception) {
                        Log.w(TAG, "Page ${pageIndex + 1} could not be recognized: ${e.javaClass.simpleName}")
                        null
                    }
                    currentCoroutineContext().ensureActive() // a stopped recognition is not a result
                    if (page == null) failed++
                    // An unreadable page is kept (empty) so the user can type its text.
                    pages += page ?: ScannedDocumentStructureReconstructor.reconstructPage(
                        pageIndex = pageIndex, imageUriString = uri.toString(), rawText = ""
                    )
                }
                OcrBatchResult(pages, failed)
            } finally {
                cancelHandle?.dispose()
                synchronized(lock) {
                    recycled = true
                    try { tess.recycle() } catch (e: Throwable) { /* native instance already released */ }
                }
            }
        }
    }

    private fun recognizePage(tess: TessBaseAPI, uri: Uri, pageIndex: Int): ScannedPageData {
        val bitmap: Bitmap = PageImageLoader.load(context, uri)
        try {
            tess.setImage(bitmap)
            // getHOCRText runs the recognition in the mode that stop() can interrupt (see the
            // library documentation); getUTF8Text then returns the already computed result.
            tess.getHOCRText(0)
            val plainText = tess.getUTF8Text().orEmpty()
            val confidence = (tess.meanConfidence() / 100f).coerceIn(0f, 1f)
            val lines = readLines(tess)
            val text = if (lines.isNotEmpty()) OcrTextAssembler.assemble(lines) else plainText.trim()
            return ScannedDocumentStructureReconstructor.reconstructPage(
                pageIndex = pageIndex,
                imageUriString = uri.toString(),
                rawText = text,
                lines = lines,
                confidence = if (text.isBlank()) 0f else confidence
            )
        } finally {
            tess.clear()
            bitmap.recycle()
        }
    }

    /** Text lines in reading order with paragraph starts, from Tesseract's layout analysis. */
    private fun readLines(tess: TessBaseAPI): List<OcrLineInfo> {
        val iterator = tess.resultIterator ?: return emptyList()
        val lines = ArrayList<OcrLineInfo>()
        try {
            val level = TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE
            iterator.begin()
            do {
                val text = iterator.getUTF8Text(level)?.trimEnd('\n', '\r') ?: continue
                if (text.isBlank()) continue
                val box = iterator.getBoundingRect(level)
                lines += OcrLineInfo(
                    text = text,
                    top = box.top,
                    left = box.left,
                    width = box.width(),
                    height = box.height(),
                    isRtl = ScannedDocumentStructureReconstructor.isArabicText(text),
                    startsParagraph = iterator.isAtBeginningOf(TessBaseAPI.PageIteratorLevel.RIL_PARA) ||
                        iterator.isAtBeginningOf(TessBaseAPI.PageIteratorLevel.RIL_BLOCK)
                )
            } while (iterator.next(level))
        } finally {
            iterator.delete()
        }
        return lines
    }

    private companion object {
        const val TAG = "DocumentOcrEngine"

        /** One recognition at a time: Tesseract is memory-heavy and not thread-safe. */
        val engineMutex = Mutex()
    }
}
