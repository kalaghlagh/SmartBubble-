package com.example.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.File
import java.io.IOException

/** Loads scanned page images for OCR with bounded memory use, and cleans up temporary scans. */
object PageImageLoader {

    /**
     * Longest side handed to the OCR engine. About 300 dpi for an A4 page; larger images only
     * cost memory and time (a 12 MP photo would otherwise need ~48 MB as a bitmap).
     */
    const val MAX_DIMENSION = 3000

    /** Power-of-two sample size so the decoded long side is at most [maxDimension]. */
    fun sampleSizeFor(width: Int, height: Int, maxDimension: Int = MAX_DIMENSION): Int {
        var sample = 1
        var longest = maxOf(width, height)
        while (longest > maxDimension) {
            sample *= 2
            longest /= 2
        }
        return sample
    }

    /** Decodes [uri] downsampled and upright (EXIF rotation applied) as ARGB_8888. */
    fun load(context: Context, uri: Uri): Bitmap {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: throw IOException("Cannot open image")
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Not a readable image")

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw IOException("Cannot decode image")

        val degrees = try {
            resolver.openInputStream(uri)?.use { stream ->
                when (ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        } catch (e: Exception) {
            0f // no usable EXIF data: keep the image as decoded
        }

        var bitmap = decoded
        if (degrees != 0f) {
            val rotated = Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees) }, true
            )
            if (rotated !== bitmap) bitmap.recycle()
            bitmap = rotated
        }
        if (bitmap.config != Bitmap.Config.ARGB_8888) {
            val converted = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                ?: throw IOException("Cannot convert image")
            bitmap.recycle()
            bitmap = converted
        }
        return bitmap
    }

    /**
     * Deletes scanned page images that live in this app's cache (the document scanner writes
     * its JPEGs there). Images picked from the gallery belong to the user and are never touched.
     */
    fun deleteTemporaryScans(context: Context, imageUris: Collection<String>) {
        val cacheRoot = context.cacheDir.canonicalFile
        for (value in imageUris) {
            try {
                val uri = Uri.parse(value)
                if (uri.scheme != "file") continue
                val file = File(uri.path ?: continue).canonicalFile
                if (file.isFile && file.path.startsWith(cacheRoot.path + File.separator)) file.delete()
            } catch (e: Exception) {
                // Best effort; the periodic purge below removes anything left behind.
            }
        }
    }

    /** Removes scanner images left in the cache by an interrupted session. */
    fun purgeStaleScans(context: Context, olderThanMillis: Long = 24L * 60 * 60 * 1000) {
        val dir = File(context.cacheDir, "mlkit_docscan_ui_client")
        val cutoff = System.currentTimeMillis() - olderThanMillis
        dir.walkTopDown().filter { it.isFile && it.lastModified() < cutoff }.forEach { it.delete() }
    }
}
