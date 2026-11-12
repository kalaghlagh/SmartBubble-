package com.example.ocr

import android.content.Context
import java.io.File
import java.io.IOException

/**
 * Copies the bundled Tesseract language models from the APK assets to private app storage
 * (Tesseract needs real files). The models are never downloaded: OCR works fully offline.
 */
object TessDataInstaller {

    /** Model file name -> exact size in bytes (see assets/tessdata/README.md). */
    val MODELS: Map<String, Long> = mapOf(
        "ara" to 1_432_056L,
        "eng" to 4_113_088L,
        "fra" to 1_130_365L
    )

    private const val ASSET_DIR = "tessdata"

    /**
     * Ensures the given [models] are installed and returns the Tesseract data path (the
     * directory that contains `tessdata/`). Files with a wrong size are replaced.
     */
    @Synchronized
    fun ensureInstalled(context: Context, models: Collection<String>): File {
        // noBackupFilesDir: the models are re-created from the APK, so they are not backed up.
        val root = File(context.noBackupFilesDir, "tesseract")
        val dataDir = File(root, "tessdata")
        if (!dataDir.isDirectory && !dataDir.mkdirs() && !dataDir.isDirectory) {
            throw IOException("Cannot create OCR data directory")
        }
        for (model in models) {
            val expected = MODELS[model] ?: throw IOException("Unknown OCR model: $model")
            val target = File(dataDir, "$model.traineddata")
            if (target.isFile && target.length() == expected) continue
            val partial = File(dataDir, "$model.traineddata.part")
            try {
                context.assets.open("$ASSET_DIR/$model.traineddata").use { input ->
                    partial.outputStream().use { output -> input.copyTo(output) }
                }
                if (partial.length() != expected) throw IOException("OCR model $model is incomplete")
                if (target.exists()) target.delete()
                if (!partial.renameTo(target)) throw IOException("Cannot install OCR model $model")
            } finally {
                if (partial.exists()) partial.delete()
            }
        }
        return root
    }
}
