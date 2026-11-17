package com.example.ocr.paddle

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/** One model file shipped in the app's assets, with its expected size and SHA-256. */
data class ModelFile(val asset: String, val bytes: Long, val sha256: String) {
    val name: String get() = asset.substringAfterLast('/')
}

/**
 * The OCR models bundled with the app: PaddleOCR PP-OCRv5 mobile text detection and the Arabic
 * and Latin recognition models with their dictionaries, converted to ONNX Runtime's ORT format
 * by MakeACopy (provenance and license: third_party_licenses/paddleocr). The checksums are those
 * of the files in assets/paddleocr/v5 (SHA256SUMS); the dictionary checksums equal MakeACopy's
 * upstream pins.
 */
object PaddleModels {
    const val DIR = "paddleocr/v5"

    val DET = ModelFile("$DIR/det.ort", 4_898_208, "bfb226a460dee7e50b210e20e7c51becff55798150aea45cb9d047c81bfb9c9a")
    val ARABIC = ModelFile("$DIR/arabic_PP-OCRv5_mobile_rec.ort", 8_106_064, "17d31ec78b3dd2168c97595031fdf7adeba145c4cfa5f33278f04e8363fdea9d")
    val ARABIC_DICT = ModelFile("$DIR/arabic_PP-OCRv5_mobile_rec_dict.txt", 2_371, "2a215ea5877f01b1f8c8803783cda73707222c39a84d4a6cfee9ef502c48248e")
    val LATIN = ModelFile("$DIR/latin_PP-OCRv5_mobile_rec.ort", 8_149_136, "5bb93e0fef6fcde14ddadfec23ff9efbc331531ba1ae54baba85605d7794efda")
    val LATIN_DICT = ModelFile("$DIR/latin_PP-OCRv5_mobile_rec_dict.txt", 2_618, "b95923300a0656f8169feee90143cbfcdb62d82a37b54e6b12c224c3e584916f")

    val ALL = listOf(DET, ARABIC, ARABIC_DICT, LATIN, LATIN_DICT)
}

/** A model file is missing, incomplete or not the expected file. */
class ModelIntegrityException(message: String) : IOException(message)

/**
 * Installs model files from the app's assets into a private directory and verifies them.
 *
 * A file is copied to a temporary name while its SHA-256 is computed and renamed only when size
 * and checksum match, so an interrupted copy never leaves a file that looks installed. Before a
 * model is loaded its checksum is verified again (once per process), so a file damaged on disk is
 * reinstalled from the assets instead of being loaded. Nothing is ever downloaded.
 */
class ModelInstaller(private val dir: File, private val openAsset: (String) -> InputStream) {

    private val verified = HashSet<String>()

    /** The verified file for [model], installing it from the assets when needed. */
    @Synchronized
    fun file(model: ModelFile): File {
        val target = File(dir, model.name)
        if (model.asset in verified && target.length() == model.bytes) return target
        if (target.exists()) {
            if (target.length() == model.bytes && sha256(target) == model.sha256) {
                verified += model.asset
                return target
            }
            // Damaged or from another version: replaced from the assets.
            target.delete()
        }
        install(model, target)
        verified += model.asset
        return target
    }

    private fun install(model: ModelFile, target: File) {
        dir.mkdirs()
        val tmp = File(dir, model.name + ".part")
        tmp.delete()
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        try {
            openAsset(model.asset).use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        digest.update(buf, 0, n)
                        out.write(buf, 0, n)
                        size += n
                    }
                }
            }
        } catch (e: IOException) {
            tmp.delete()
            throw ModelIntegrityException("Model ${model.name} could not be read from the app: ${e.message}")
        }
        val sha = hex(digest.digest())
        if (size != model.bytes || sha != model.sha256) {
            tmp.delete()
            throw ModelIntegrityException("Model ${model.name} is damaged (size $size, expected ${model.bytes})")
        }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw ModelIntegrityException("Model ${model.name} could not be installed")
        }
    }

    companion object {
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                }
            }
            return hex(digest.digest())
        }

        private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    }
}
