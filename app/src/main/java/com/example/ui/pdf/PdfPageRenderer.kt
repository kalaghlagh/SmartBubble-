package com.example.ui.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream

/** Sizes of rendered pages and of the page cache (no Android types: tested on the JVM). */
object PdfPageGeometry {

    /** Longest side of a rendered page, in pixels (keeps one page bitmap under ~16 MB). */
    const val MAX_SIDE = 2048

    /**
     * Pixel size of a page [pageWidth] x [pageHeight] (points) drawn [targetWidth] pixels wide,
     * its proportions kept, its longest side at most [maxSide].
     */
    fun renderSize(pageWidth: Int, pageHeight: Int, targetWidth: Int, maxSide: Int = MAX_SIDE): Pair<Int, Int> {
        require(pageWidth > 0 && pageHeight > 0) { "empty page" }
        var w = targetWidth.coerceAtLeast(1).toDouble()
        var h = w * pageHeight / pageWidth
        val longest = maxOf(w, h)
        if (longest > maxSide) { val f = maxSide / longest; w *= f; h *= f }
        return w.toInt().coerceAtLeast(1) to h.toInt().coerceAtLeast(1)
    }

    /** Bytes the page cache may hold: an eighth of the app's heap, at most 64 MB, at least 8 MB. */
    fun cacheBytes(maxHeapBytes: Long): Int = (maxHeapBytes / 8).coerceIn(8L * 1024 * 1024, 64L * 1024 * 1024).toInt()
}

/**
 * The pages of a PDF drawn for viewing, on the device ([PdfRenderer]): the file is copied once to
 * the app's cache (the renderer needs a seekable file), pages are drawn one at a time (the
 * renderer opens one page at a time and is not thread-safe) at the width they are shown, and kept
 * in a cache bounded in bytes. [close] releases the renderer, the file descriptor, the cache and
 * the copy. Rendering errors are returned as null, never thrown to the UI.
 */
class PdfPageRenderer private constructor(
    private val copy: File,
    private val fd: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
    /** Size of each page in points (width, height), read once. */
    val pageSizes: List<Pair<Int, Int>>,
    maxHeapBytes: Long
) : Closeable {

    private val lock = Mutex()
    @Volatile private var closed = false

    private val cache = object : LruCache<String, Bitmap>(PdfPageGeometry.cacheBytes(maxHeapBytes)) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    val pageCount: Int get() = pageSizes.size

    /** Bytes the cached pages take now (diagnostics and tests). */
    val cachedBytes: Int get() = cache.size()

    val cacheLimitBytes: Int get() = cache.maxSize()

    /** Page [index] drawn [targetWidth] pixels wide, or null when it cannot be drawn. */
    suspend fun render(index: Int, targetWidth: Int, dispatcher: CoroutineDispatcher = Dispatchers.IO): Bitmap? {
        if (index !in pageSizes.indices || targetWidth <= 0) return null
        val (pw, ph) = pageSizes[index]
        val (w, h) = PdfPageGeometry.renderSize(pw, ph, targetWidth)
        val key = "$index@$w"
        cache.get(key)?.let { return it }
        return withContext(dispatcher) {
            lock.withLock {
                if (closed) return@withLock null
                cache.get(key)?.let { return@withLock it }
                try {
                    renderer.openPage(index).use { page ->
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        cache.put(key, bmp)
                        bmp
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Page ${index + 1} could not be drawn: ${e.javaClass.simpleName}")
                    null
                } catch (e: OutOfMemoryError) {
                    cache.evictAll()
                    Log.w(TAG, "Page ${index + 1}: not enough memory to draw it")
                    null
                }
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        // Taken without suspending: a page being drawn finishes first.
        while (!lock.tryLock()) Thread.sleep(5)
        try {
            cache.evictAll()
            try { renderer.close() } catch (e: Exception) { }
            try { fd.close() } catch (e: Exception) { }
            copy.delete()
        } finally {
            lock.unlock()
        }
    }

    companion object {
        private const val TAG = "PdfPageRenderer"

        /**
         * Opens [uri] for viewing (on a background thread). Null when the file cannot be read or
         * is not a PDF the platform can draw (damaged, password-protected).
         */
        suspend fun open(context: Context, uri: Uri): PdfPageRenderer? {
            var made: PdfPageRenderer? = null
            try {
                return withContext(Dispatchers.IO) { openNow(context, uri).also { made = it } }
            } catch (e: CancellationException) {
                // The viewer was closed while the file was being opened: nothing keeps it open.
                made?.close()
                throw e
            }
        }

        private fun openNow(context: Context, uri: Uri): PdfPageRenderer? {
            val dir = File(context.cacheDir, "pdf-viewer").apply { mkdirs() }
            val copy = File(dir, "view-" + System.nanoTime() + ".pdf")
            var fd: ParcelFileDescriptor? = null
            try {
                val input = if (uri.scheme == "file") java.io.FileInputStream(File(uri.path!!)) else context.contentResolver.openInputStream(uri)
                (input ?: return null).use { i -> FileOutputStream(copy).use { i.copyTo(it) } }
                fd = ParcelFileDescriptor.open(copy, ParcelFileDescriptor.MODE_READ_ONLY)
                val renderer = PdfRenderer(fd)
                if (renderer.pageCount == 0) { renderer.close(); throw IllegalStateException("no page") }
                val sizes = (0 until renderer.pageCount).map { i -> renderer.openPage(i).use { it.width to it.height } }
                return PdfPageRenderer(copy, fd, renderer, sizes, Runtime.getRuntime().maxMemory())
            } catch (e: Exception) {
                Log.w(TAG, "PDF cannot be displayed: ${e.javaClass.simpleName}")
                try { fd?.close() } catch (x: Exception) { }
                copy.delete()
                return null
            }
        }
    }
}
