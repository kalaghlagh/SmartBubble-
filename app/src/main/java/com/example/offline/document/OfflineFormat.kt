package com.example.offline.document

import com.example.offline.DocumentType
import com.example.offline.FormattingResult

/** Output formats of the Offline Formatting Agent (the formats the existing engines write). */
enum class OfflineFormat(val extension: String, val templateType: String) {
    DOCX("docx", "DOCX"),
    PDF("pdf", "DOCX"), // PDF uses the Word template's colors and margins
    XLSX("xlsx", "XLSX"),
    PPTX("pptx", "PPTX");

    companion object {
        /**
         * Formats the user can choose for [result]. Word and PDF always; a spreadsheet only when
         * a table was detected; a presentation only as an explicit choice of the user (it is
         * offered but never selected automatically).
         */
        fun available(result: FormattingResult): List<OfflineFormat> = buildList {
            add(DOCX)
            add(PDF)
            if (result.hasTabularData) add(XLSX)
            add(PPTX)
        }

        /** Prose defaults to Word; text that is essentially one or more tables defaults to XLSX. */
        fun default(result: FormattingResult): OfflineFormat =
            if (result.documentType.type == DocumentType.STRUCTURED_DATA && result.hasTabularData) XLSX else DOCX
    }
}
