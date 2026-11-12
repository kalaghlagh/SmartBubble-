package com.example.document.model

data class WordDocumentModel(
    val title: String,
    val subtitle: String = "",
    val organization: String = "Smart Bubble Enterprise",
    val date: String = "",
    val sections: List<WordSection> = emptyList(),
    val table: WordTable? = null,
    val signatoryName: String = "",
    val signatoryTitle: String = ""
)

data class WordSection(
    val heading: String,
    val paragraphs: List<String> = emptyList(),
    val bulletPoints: List<String> = emptyList(),
    val isPageBreakBefore: Boolean = false,
    val isRtl: Boolean? = null,
    val table: WordTable? = null
)

data class WordTable(
    val headers: List<String>,
    val rows: List<List<String>>
)

data class ScannedPageData(
    val pageIndex: Int,
    val imageUriString: String,
    val recognizedTitle: String = "",
    val heading: String = "",
    val rawText: String = "",
    val paragraphs: List<String> = emptyList(),
    val bulletPoints: List<String> = emptyList(),
    val table: WordTable? = null,
    val isArabicDetected: Boolean = false,
    /** Recognition confidence 0..1 reported by the OCR engine (1.0 for text typed by the user). */
    val confidenceScore: Float = 0.95f,
    /**
     * The page content in reading order (headings, paragraphs, lists, tables). When present it
     * is used for document generation instead of the flattened fields above.
     */
    val sections: List<WordSection> = emptyList()
)

data class ScannedDocumentReviewState(
    val documentTitle: String = "Scanned_Document",
    val pages: List<ScannedPageData> = emptyList(),
    val activePageIndex: Int = 0,
    val selectedTemplateId: String? = null, // null = original reconstructed, "default" = default, or specific template id
    val isAiEnhanced: Boolean = true,
    val isProcessing: Boolean = false,
    val processingMessage: String = ""
)

data class PresentationModel(
    val title: String,
    val subtitle: String,
    val presenter: String = "Smart Bubble AI",
    val slides: List<SlideModel>
)

data class SlideModel(
    val title: String,
    val subtitle: String = "",
    val bulletPoints: List<String> = emptyList(),
    val calloutMetric: String? = null,
    val calloutLabel: String? = null,
    val speakerNotes: String = ""
)

data class SpreadsheetModel(
    val workbookTitle: String,
    val sheets: List<SheetModel>
)

data class SheetModel(
    val name: String,
    val headers: List<String>,
    val rows: List<List<Any>>, // string, double/int, formula
    val summaryFormulaLabel: String? = null,
    val summaryFormula: String? = null,
    /** Write every cell as text exactly as given (no conversion of "1,250" or "007" to numbers). */
    val keepValuesAsText: Boolean = false
)
