package com.example.ocr

/**
 * Languages the on-device OCR can read. [models] are the Tesseract language models used.
 * Arabic also loads English because Arabic documents commonly contain Latin words and numbers.
 */
enum class OcrLanguage(val code: String, val models: List<String>) {
    /** Arabic, English and French together (slower, slightly less accurate than a single language). */
    AUTO("auto", listOf("ara", "eng", "fra")),
    ARABIC("ar", listOf("ara", "eng")),
    ENGLISH("en", listOf("eng")),
    FRENCH("fr", listOf("fra"));

    /**
     * Tesseract language string. For [AUTO] the app language is tried first, which decides ties
     * between scripts.
     */
    fun tesseractLanguages(appLanguage: String): String {
        if (this != AUTO) return models.joinToString("+")
        val first = when (appLanguage.lowercase().take(2)) {
            "ar" -> "ara"
            "fr" -> "fra"
            else -> "eng"
        }
        return (listOf(first) + models.filter { it != first }).joinToString("+")
    }

    companion object {
        fun fromCode(code: String?): OcrLanguage = values().firstOrNull { it.code == code } ?: AUTO
    }
}
