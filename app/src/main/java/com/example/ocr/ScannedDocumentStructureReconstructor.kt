package com.example.ocr

import com.example.document.model.ScannedPageData
import com.example.document.model.WordDocumentModel
import com.example.document.model.WordSection
import com.example.document.model.WordTable

data class OcrLineInfo(
    val text: String,
    val top: Int = 0,
    val left: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    val isRtl: Boolean = false
)

object ScannedDocumentStructureReconstructor {

    fun isArabicText(text: String): Boolean {
        return text.any { it in '\u0600'..'\u06FF' }
    }

    /**
     * Parses raw extracted OCR text and lines into structured document elements:
     * title, headings, paragraphs, bullet points, numbered lists, and tables.
     */
    fun reconstructPage(
        pageIndex: Int,
        imageUriString: String,
        rawText: String,
        lines: List<OcrLineInfo> = emptyList()
    ): ScannedPageData {
        val cleanRawText = rawText.trim()
        val isArabic = isArabicText(cleanRawText)

        if (cleanRawText.isBlank()) {
            return ScannedPageData(
                pageIndex = pageIndex,
                imageUriString = imageUriString,
                recognizedTitle = "Page ${pageIndex + 1}",
                heading = "",
                rawText = "",
                paragraphs = emptyList(),
                bulletPoints = emptyList(),
                table = null,
                isArabicDetected = false,
                confidenceScore = 0.0f
            )
        }

        val textLines = if (lines.isNotEmpty()) lines.map { it.text.trim() } else cleanRawText.lines().map { it.trim() }
        val filteredLines = textLines.filter { it.isNotBlank() }

        var detectedTitle = ""
        var currentHeading = ""
        val paragraphs = mutableListOf<String>()
        val bulletPoints = mutableListOf<String>()
        val tableLines = mutableListOf<String>()

        var isCollectingTable = false

        for ((idx, line) in filteredLines.withIndex()) {
            // Check for title candidate on the very first few lines
            if (idx == 0 && (line.length < 90 || line.startsWith("#"))) {
                detectedTitle = line.removePrefix("#").trim()
                continue
            }

            // Check if line is part of a table (contains '|' or tab or multiple comma-aligned entries)
            val isTableLine = line.contains("|") || line.contains("\t") ||
                    (line.count { it == ',' } >= 2 && !line.endsWith("."))

            if (isTableLine) {
                isCollectingTable = true
                tableLines.add(line)
                continue
            } else if (isCollectingTable) {
                // Table ended
                isCollectingTable = false
            }

            // Check for Headings
            if (line.startsWith("##") || (line.length < 60 && (line.endsWith(":") || (line.all { it.isUpperCase() || it.isWhitespace() || it.isDigit() } && line.length > 3)))) {
                if (currentHeading.isBlank()) {
                    currentHeading = line.removePrefix("##").trim()
                } else {
                    paragraphs.add(line)
                }
                continue
            }

            // Check for Bullet points or numbered lists
            val isBullet = line.startsWith("•") || line.startsWith("-") || line.startsWith("*") ||
                    line.matches("^[0-9]+[\\.\\)]\\s+.*".toRegex()) ||
                    line.matches("^[أ-ي0-9]+[\\-\\)]\\s+.*".toRegex())

            if (isBullet) {
                val cleanBullet = line.replace("^[•\\-\\*0-9أ-ي]+[\\.\\)\\-]\\s*".toRegex(), "").trim()
                bulletPoints.add(cleanBullet.ifBlank { line })
            } else {
                paragraphs.add(line)
            }
        }

        // Reconstruct Table if detected
        val detectedTable = if (tableLines.size >= 2) {
            parseTableFromLines(tableLines)
        } else null

        return ScannedPageData(
            pageIndex = pageIndex,
            imageUriString = imageUriString,
            recognizedTitle = detectedTitle.ifBlank { "Page ${pageIndex + 1}" },
            heading = currentHeading,
            rawText = cleanRawText,
            paragraphs = paragraphs,
            bulletPoints = bulletPoints,
            table = detectedTable,
            isArabicDetected = isArabic,
            confidenceScore = if (paragraphs.isNotEmpty() || detectedTable != null) 0.95f else 0.40f
        )
    }

    private fun parseTableFromLines(lines: List<String>): WordTable? {
        val rows = mutableListOf<List<String>>()
        for (line in lines) {
            val delimiter = when {
                line.contains("|") -> "\\|".toRegex()
                line.contains("\t") -> "\t".toRegex()
                line.contains(",") -> ",".toRegex()
                else -> "\\s{2,}".toRegex()
            }
            val cells = line.split(delimiter).map { it.trim() }.filter { it.isNotBlank() }
            if (cells.isNotEmpty()) {
                rows.add(cells)
            }
        }

        if (rows.size < 2) return null
        val headers = rows.first()
        val dataRows = rows.drop(1)
        val maxCols = (listOf(headers) + dataRows).maxOfOrNull { it.size } ?: 1

        val normalizedHeaders = headers.take(maxCols) + List((maxCols - headers.size).coerceAtLeast(0)) { "Col ${it + 1}" }
        val normalizedRows = dataRows.map { r ->
            r.take(maxCols) + List((maxCols - r.size).coerceAtLeast(0)) { "" }
        }

        return WordTable(
            headers = normalizedHeaders,
            rows = normalizedRows
        )
    }

    /**
     * Synthesizes all reviewed and edited pages into an actual WordDocumentModel
     * ready for generation by DocxEngine.
     */
    fun buildWordDocumentModel(
        title: String,
        pages: List<ScannedPageData>
    ): WordDocumentModel {
        val allSections = mutableListOf<WordSection>()
        var primaryTable: WordTable? = null

        for ((idx, page) in pages.withIndex()) {
            val isPageBreak = idx > 0
            val sectionHeading = page.heading.ifBlank {
                if (pages.size > 1) "Page ${idx + 1}" else ""
            }

            allSections.add(
                WordSection(
                    heading = sectionHeading,
                    paragraphs = page.paragraphs,
                    bulletPoints = page.bulletPoints,
                    isPageBreakBefore = isPageBreak,
                    isRtl = page.isArabicDetected,
                    table = page.table
                )
            )

            if (primaryTable == null && page.table != null) {
                primaryTable = page.table
            }
        }

        return WordDocumentModel(
            title = title.ifBlank { "Scanned Document" },
            subtitle = if (pages.size > 1) "${pages.size} Scanned Pages" else "",
            organization = "SmartBubble Office",
            date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date()),
            sections = allSections,
            table = null // tables are bound per section
        )
    }
}
