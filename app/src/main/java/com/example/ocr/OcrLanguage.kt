package com.example.ocr

import com.example.extraction.OcrModel

/**
 * Languages the user can choose for on-device OCR. Every choice can read Arabic, French and
 * English (an Arabic page often holds Latin terms, a French one Arabic names): the choice only
 * decides which recognition model reads each region first; the other one reads it too when the
 * first reading shows another script or uncertain characters.
 */
enum class OcrLanguage(val code: String) {
    /** The page's own script decides (the app language breaks ties on the first regions). */
    AUTO("auto"),
    ARABIC("ar"),
    ENGLISH("en"),
    FRENCH("fr");

    /** The recognition model read first. */
    fun preferredModel(appLanguage: String): OcrModel = when (this) {
        ARABIC -> OcrModel.ARABIC
        ENGLISH, FRENCH -> OcrModel.LATIN
        AUTO -> if (appLanguage.lowercase().startsWith("ar")) OcrModel.ARABIC else OcrModel.LATIN
    }

    companion object {
        fun fromCode(code: String?): OcrLanguage = values().firstOrNull { it.code == code } ?: AUTO
    }
}
