package com.example.ocr

import com.example.document.model.ScannedPageData
import com.example.document.model.WordDocumentModel
import com.example.document.model.WordSection
import com.example.document.model.WordTable

/** One recognized text line with its position on the page image. */
data class OcrLineInfo(
    val text: String,
    val top: Int = 0,
    val left: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    val isRtl: Boolean = false,
    /** The OCR engine marked this line as the first line of a paragraph or block. */
    val startsParagraph: Boolean = false
)

/**
 * Turns recognized lines into editable text with the conventions the review screen uses:
 * one line of text = one paragraph, a blank line between blocks, table rows as "a | b | c".
 * Pure Kotlin (unit-testable).
 */
object OcrTextAssembler {

    private val COLUMN_GAP = Regex("""\s{3,}|\t+""")
    private val BULLET = Regex("""^([•●▪◦*·\-–]|\d{1,3}[.)]|[\u0660-\u0669]{1,3}[.)\-])\s+.*""")

    private fun cells(line: String): List<String> = line.trim().split(COLUMN_GAP).filter { it.isNotBlank() }

    fun assemble(lines: List<OcrLineInfo>): String {
        val texts = lines.map { it.text.trimEnd() }
        val out = StringBuilder()
        var paragraph = StringBuilder()

        fun flush() {
            if (paragraph.isNotBlank()) out.append(paragraph.toString().trim()).append("\n\n")
            paragraph = StringBuilder()
        }

        var i = 0
        while (i < lines.size) {
            val text = texts[i].trim()
            if (text.isEmpty()) { i++; continue }

            // A table needs at least two consecutive lines that both split into columns.
            val columns = cells(texts[i]).size
            if (columns >= 2 && i + 1 < lines.size && cells(texts[i + 1]).size == columns) {
                flush()
                val first = i
                while (i < lines.size) {
                    val count = cells(texts[i]).size
                    // Rows may miss a cell; a new paragraph with a different shape ends the table.
                    val sameTable = count >= 2 && kotlin.math.abs(count - columns) <= 1 &&
                        (i == first || count == columns || !lines[i].startsParagraph)
                    if (!sameTable) break
                    out.append(cells(texts[i]).joinToString(" | ")).append('\n')
                    i++
                }
                out.append('\n')
                continue
            }

            val single = text.replace(Regex("""\s{2,}"""), " ")
            if (BULLET.matches(single)) {
                flush()
                out.append(single).append('\n')
                // Keep following list items together; a blank line is added when the list ends.
                val next = texts.getOrNull(i + 1)?.trim().orEmpty()
                if (!BULLET.matches(next)) out.append('\n')
                i++
                continue
            }

            if (lines[i].startsParagraph) flush()
            if (paragraph.isEmpty()) {
                paragraph.append(single)
            } else if (paragraph.endsWith("-") && paragraph.length >= 2 &&
                paragraph[paragraph.length - 2].isLetter() && single.first().isLowerCase()
            ) {
                // Word hyphenated across lines.
                paragraph.setLength(paragraph.length - 1)
                paragraph.append(single)
            } else {
                paragraph.append(' ').append(single)
            }
            i++
        }
        flush()
        return out.toString().trim()
    }
}

object ScannedDocumentStructureReconstructor {

    fun isArabicText(text: String): Boolean {
        return text.any { it in '\u0600'..'\u06FF' }
    }

    private val BULLET = Regex("""^([•●▪◦*·\-–]|\d{1,3}[.)]|[\u0660-\u0669أ-ي]{1,3}[.)\-])\s+(.*)""")

    private fun isHeading(line: String): Boolean {
        if (line.startsWith("#")) return true
        if (line.length !in 3..60) return false
        if (line.endsWith(":") || line.endsWith("：")) return true
        val letters = line.filter { it.isLetter() }
        // ALL-CAPS Latin line (Arabic has no case, so it never matches this rule).
        return letters.length >= 3 && letters.all { it.isUpperCase() }
    }

    /**
     * Parses page text into structured elements in reading order. Conventions (also what the
     * user edits on the review screen): one line = one paragraph; lines starting with a bullet
     * or number = list items; lines containing " | " or tabs = table rows; a short line ending
     * with ":" (or in capitals, or starting with #) = heading.
     *
     * @param confidence OCR engine confidence 0..1; null when the text was typed/edited by the user
     */
    fun reconstructPage(
        pageIndex: Int,
        imageUriString: String,
        rawText: String,
        lines: List<OcrLineInfo> = emptyList(),
        confidence: Float? = null
    ): ScannedPageData {
        val cleanRawText = rawText.trim()
        if (cleanRawText.isBlank()) {
            return ScannedPageData(
                pageIndex = pageIndex,
                imageUriString = imageUriString,
                recognizedTitle = "Page ${pageIndex + 1}",
                rawText = "",
                isArabicDetected = false,
                confidenceScore = 0.0f
            )
        }

        val textLines = cleanRawText.lines().map { it.trim() }.filter { it.isNotBlank() }

        var detectedTitle = ""
        val sections = mutableListOf<WordSection>()
        var heading = ""
        var paragraphs = mutableListOf<String>()
        var bullets = mutableListOf<String>()
        var tableLines = mutableListOf<String>()

        fun closeSection() {
            val table = if (tableLines.size >= 2) parseTableFromLines(tableLines) else null
            // A lone "table" line is ordinary text.
            if (table == null) paragraphs.addAll(tableLines)
            if (heading.isNotBlank() || paragraphs.isNotEmpty() || bullets.isNotEmpty() || table != null) {
                sections += WordSection(
                    heading = heading,
                    paragraphs = paragraphs,
                    bulletPoints = bullets,
                    isPageBreakBefore = false,
                    isRtl = null, // direction is decided per paragraph by the document engine
                    table = table
                )
            }
            heading = ""
            paragraphs = mutableListOf()
            bullets = mutableListOf()
            tableLines = mutableListOf()
        }

        for ((idx, line) in textLines.withIndex()) {
            // The first short line of the first page is the document title.
            if (pageIndex == 0 && idx == 0 && line.length < 90 && !line.contains("|")) {
                detectedTitle = line.trimStart('#').trim()
                continue
            }
            val isTableLine = line.contains("|") || line.contains("\t")
            val bullet = BULLET.matchEntire(line)
            when {
                isTableLine -> tableLines.add(line)
                else -> {
                    // A document engine section renders paragraphs, then list items, then the
                    // table. Start a new section whenever that order would change the text order.
                    if (tableLines.isNotEmpty()) closeSection()
                    when {
                        bullet != null -> bullets.add(bullet.groupValues[2].trim().ifBlank { line })
                        isHeading(line) -> {
                            closeSection()
                            heading = line.trimStart('#').trim()
                        }
                        else -> {
                            if (bullets.isNotEmpty()) closeSection()
                            paragraphs.add(line)
                        }
                    }
                }
            }
        }
        closeSection()

        val hasContent = sections.isNotEmpty() || detectedTitle.isNotBlank()
        return ScannedPageData(
            pageIndex = pageIndex,
            imageUriString = imageUriString,
            recognizedTitle = detectedTitle.ifBlank { "Page ${pageIndex + 1}" },
            heading = sections.firstOrNull { it.heading.isNotBlank() }?.heading.orEmpty(),
            rawText = cleanRawText,
            paragraphs = sections.flatMap { it.paragraphs },
            bulletPoints = sections.flatMap { it.bulletPoints },
            table = sections.firstNotNullOfOrNull { it.table },
            isArabicDetected = isArabicText(cleanRawText),
            confidenceScore = confidence ?: if (hasContent) 1.0f else 0.0f,
            sections = sections
        )
    }

    private fun parseTableFromLines(lines: List<String>): WordTable? {
        val rows = mutableListOf<List<String>>()
        for (line in lines) {
            val delimiter = when {
                line.contains("|") -> "\\|".toRegex()
                line.contains("\t") -> "\t".toRegex()
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

        for ((idx, page) in pages.withIndex()) {
            val isPageBreak = idx > 0
            if (page.sections.isNotEmpty()) {
                // Reading order preserved; each scanned page starts on a new page.
                page.sections.forEachIndexed { i, section ->
                    allSections.add(if (i == 0 && isPageBreak) section.copy(isPageBreakBefore = true) else section)
                }
                continue
            }
            if (page.paragraphs.isEmpty() && page.bulletPoints.isEmpty() && page.table == null && page.heading.isBlank()) {
                continue // empty page: nothing to add
            }
            allSections.add(
                WordSection(
                    heading = page.heading,
                    paragraphs = page.paragraphs,
                    bulletPoints = page.bulletPoints,
                    isPageBreakBefore = isPageBreak,
                    isRtl = page.isArabicDetected,
                    table = page.table
                )
            )
        }

        return WordDocumentModel(
            title = title.ifBlank { "Scanned Document" },
            subtitle = "",
            organization = "",
            date = "", // a scanned document keeps its own content; no generated date is added
            sections = allSections,
            table = null // tables are bound per section
        )
    }
}
