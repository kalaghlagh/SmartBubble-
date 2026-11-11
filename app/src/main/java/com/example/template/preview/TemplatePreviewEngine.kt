package com.example.template.preview

import com.example.data.model.TemplateEntity
import com.example.template.domain.TemplateProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipInputStream

sealed class TemplatePreviewResult {
    data class Success(val data: TemplatePreviewData) : TemplatePreviewResult()
    data class Fallback(val data: TemplatePreviewData, val warningMessage: String) : TemplatePreviewResult()
    data class Error(val errorMessage: String) : TemplatePreviewResult()
}

sealed class TemplatePreviewData {
    data class DocxPreview(
        val title: String,
        val subtitle: String = "",
        val headerText: String = "",
        val footerText: String = "",
        val orientation: String = "Portrait",
        val marginTopPt: Int = 54,
        val marginLeftPt: Int = 54,
        val primaryColorHex: String = "#1D52D8",
        val headingFont: String = "Calibri",
        val bodyFont: String = "Calibri",
        val sections: List<DocxPreviewSection> = emptyList(),
        val table: DocxPreviewTable? = null
    ) : TemplatePreviewData()

    data class PptxPreview(
        val themeName: String = "Presentation Theme",
        val primaryColorHex: String = "#E05D26",
        val secondaryColorHex: String = "#8665F7",
        val font: String = "Segoe UI",
        val slides: List<PptxPreviewSlide> = emptyList()
    ) : TemplatePreviewData()

    data class XlsxPreview(
        val workbookName: String = "Workbook",
        val primaryColorHex: String = "#107C41",
        val headerBgHex: String = "#E2F0D9",
        val font: String = "Calibri",
        val sheets: List<XlsxPreviewSheet> = emptyList()
    ) : TemplatePreviewData()
}

data class DocxPreviewSection(
    val heading: String,
    val paragraphs: List<String>,
    val bulletPoints: List<String> = emptyList()
)

data class DocxPreviewTable(
    val headers: List<String>,
    val rows: List<List<String>>
)

data class PptxPreviewSlide(
    val slideNumber: Int,
    val title: String,
    val subtitle: String = "",
    val bulletPoints: List<String> = emptyList(),
    val calloutMetric: String? = null,
    val calloutLabel: String? = null
)

data class XlsxPreviewSheet(
    val name: String,
    val headers: List<String>,
    val rows: List<List<String>>
)

class TemplatePreviewEngine {

    suspend fun loadPreview(template: TemplateEntity): TemplatePreviewResult = withContext(Dispatchers.IO) {
        val file = File(template.originalFilePath)
        val profile = TemplateProfile.fromJson(template.profileJson)

        if (!file.exists() || file.length() == 0L) {
            // File does not exist on disk, fallback to stored profile
            return@withContext TemplatePreviewResult.Fallback(
                data = buildFallbackFromProfile(profile, template.fileType),
                warningMessage = "Physical template file is missing on storage. Displaying extracted template profile."
            )
        }

        try {
            when (template.fileType.uppercase()) {
                "DOCX" -> parseDocx(file, profile)
                "PPTX" -> parsePptx(file, profile)
                "XLSX" -> parseXlsx(file, profile)
                else -> parseDocx(file, profile)
            }
        } catch (e: Exception) {
            TemplatePreviewResult.Fallback(
                data = buildFallbackFromProfile(profile, template.fileType),
                warningMessage = "Error reading template structure: ${e.message}"
            )
        }
    }

    private fun parseDocx(file: File, profile: TemplateProfile): TemplatePreviewResult {
        var documentXml: String? = null
        var headerXml: String? = null
        var footerXml: String? = null

        ZipInputStream(FileInputStream(file)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                when {
                    entry.name.endsWith("word/document.xml") -> {
                        documentXml = zip.bufferedReader(Charsets.UTF_8).readText()
                    }
                    entry.name.contains("word/header") && headerXml == null -> {
                        headerXml = zip.bufferedReader(Charsets.UTF_8).readText()
                    }
                    entry.name.contains("word/footer") && footerXml == null -> {
                        footerXml = zip.bufferedReader(Charsets.UTF_8).readText()
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        if (documentXml.isNullOrBlank()) {
            return TemplatePreviewResult.Fallback(
                data = buildFallbackFromProfile(profile, "DOCX"),
                warningMessage = "Could not locate document structure in template archive."
            )
        }

        val doc = documentXml!!

        // Orientation
        val isLandscape = doc.contains("w:orient=\"landscape\"")
        val orientation = if (isLandscape) "Landscape" else "Portrait"

        // Margins
        var marginTop = profile.marginTopPt
        var marginLeft = profile.marginLeftPt
        val marginMatch = Regex("""w:pgMar\s+[^>]*w:top="(\d+)"[^>]*w:left="(\d+)"""").find(doc)
        if (marginMatch != null) {
            val topTwips = marginMatch.groupValues[1].toIntOrNull() ?: 1080
            val leftTwips = marginMatch.groupValues[2].toIntOrNull() ?: 1080
            marginTop = (topTwips / 20).coerceIn(24, 96)
            marginLeft = (leftTwips / 20).coerceIn(24, 96)
        }

        // Header / Footer text
        val headerText = headerXml?.let { extractTextFromXml(it).trim() }?.takeIf { it.isNotBlank() }
            ?: profile.headerText.ifBlank { "SmartBubble Template Header" }
        val footerText = footerXml?.let { extractTextFromXml(it).trim() }?.takeIf { it.isNotBlank() }
            ?: profile.footerText.ifBlank { "Page 1 • Confidential" }

        // Extract paragraphs and sections
        val sections = mutableListOf<DocxPreviewSection>()
        val pRegex = Regex("""<w:p(?:\s+[^>]*)?>(.*?)</w:p>""", RegexOption.DOT_MATCHES_ALL)
        var currentHeading = ""
        val currentParagraphs = mutableListOf<String>()
        val currentBullets = mutableListOf<String>()

        var docTitle = ""
        var docSubtitle = ""

        val paragraphs = pRegex.findAll(doc).map { it.groupValues[1] }.toList()

        // 1. First pass: find explicit Heading1 or Title paragraph
        for (pXml in paragraphs) {
            val text = extractTextFromXml(pXml).trim()
            if (text.isBlank() || text == headerText) continue
            val isExplicitTitle = pXml.contains("w:pStyle w:val=\"Heading1\"") ||
                    pXml.contains("w:pStyle w:val=\"Title\"")
            if (isExplicitTitle) {
                docTitle = text
                break
            }
        }

        // 2. Iterate and build sections
        for (pXml in paragraphs) {
            val text = extractTextFromXml(pXml).trim()
            if (text.isBlank() || text == headerText) continue

            if (docTitle.isNotBlank() && text == docTitle) {
                continue
            }

            if (docTitle.isBlank() && text.length < 90) {
                docTitle = text
                continue
            }

            val isHeading = pXml.contains("w:pStyle w:val=\"Heading") ||
                    pXml.contains("w:pStyle w:val=\"Title") ||
                    pXml.contains("<w:outlineLvl") ||
                    (pXml.contains("<w:b/>") && (pXml.contains("w:sz w:val=\"28\"") || pXml.contains("w:sz w:val=\"3") || pXml.contains("w:sz w:val=\"4")))

            val isBullet = pXml.contains("<w:numPr>") || text.startsWith("•") || text.startsWith("- ")

            if (docSubtitle.isBlank() && text.length < 120 && !isHeading && !isBullet) {
                docSubtitle = text
                continue
            }

            if (isHeading) {
                if (currentHeading.isNotBlank()) {
                    sections.add(
                        DocxPreviewSection(
                            heading = currentHeading,
                            paragraphs = currentParagraphs.toList(),
                            bulletPoints = currentBullets.toList()
                        )
                    )
                    currentParagraphs.clear()
                    currentBullets.clear()
                } else {
                    // Filter out short document header/meta lines (< 30 chars like org/date)
                    val substantiveParas = currentParagraphs.filter { it.length > 30 }
                    if (substantiveParas.isNotEmpty()) {
                        sections.add(
                            DocxPreviewSection(
                                heading = "Overview",
                                paragraphs = substantiveParas,
                                bulletPoints = currentBullets.toList()
                            )
                        )
                    }
                    currentParagraphs.clear()
                    currentBullets.clear()
                }
                currentHeading = text
            } else if (isBullet) {
                currentBullets.add(text.removePrefix("•").removePrefix("-").trim())
            } else {
                currentParagraphs.add(text)
            }
        }

        if (currentHeading.isNotBlank() || currentParagraphs.isNotEmpty() || currentBullets.isNotEmpty()) {
            sections.add(
                DocxPreviewSection(
                    heading = currentHeading.ifBlank { "Template Content" },
                    paragraphs = currentParagraphs.toList(),
                    bulletPoints = currentBullets.toList()
                )
            )
        }

        // Extract Table if present
        var previewTable: DocxPreviewTable? = null
        val tblRegex = Regex("""<w:tbl(?:\s+[^>]*)?>(.*?)</w:tbl>""", RegexOption.DOT_MATCHES_ALL)
        tblRegex.find(doc)?.let { match ->
            val tblXml = match.groupValues[1]
            val trRegex = Regex("""<w:tr(?:\s+[^>]*)?>(.*?)</w:tr>""", RegexOption.DOT_MATCHES_ALL)
            val tcRegex = Regex("""<w:tc(?:\s+[^>]*)?>(.*?)</w:tc>""", RegexOption.DOT_MATCHES_ALL)

            val rows = mutableListOf<List<String>>()
            for (trMatch in trRegex.findAll(tblXml)) {
                val rowCells = tcRegex.findAll(trMatch.groupValues[1]).map { tcMatch ->
                    extractTextFromXml(tcMatch.groupValues[1]).trim()
                }.filter { it.isNotBlank() }.toList()

                if (rowCells.isNotEmpty()) {
                    rows.add(rowCells)
                }
            }

            if (rows.isNotEmpty()) {
                val headers = rows.first()
                val dataRows = rows.drop(1)
                previewTable = DocxPreviewTable(headers, dataRows)
            }
        }

        val finalTitle = docTitle.ifBlank { profile.name }

        return TemplatePreviewResult.Success(
            data = TemplatePreviewData.DocxPreview(
                title = finalTitle,
                subtitle = docSubtitle,
                headerText = headerText,
                footerText = footerText,
                orientation = orientation,
                marginTopPt = marginTop,
                marginLeftPt = marginLeft,
                primaryColorHex = profile.primaryColorHex,
                headingFont = profile.headingFont,
                bodyFont = profile.bodyFont,
                sections = if (sections.isNotEmpty()) sections else listOf(
                    DocxPreviewSection(
                        heading = "Template Content Specification",
                        paragraphs = listOf("This template sets standard enterprise typography, margins, header branding, and content sections.")
                    )
                ),
                table = previewTable
            )
        )
    }

    private fun parsePptx(file: File, profile: TemplateProfile): TemplatePreviewResult {
        val slideFiles = mutableListOf<Pair<String, String>>()
        var themeXml: String? = null

        ZipInputStream(FileInputStream(file)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name.matches(""".*ppt/slides/slide\d+\.xml$""".toRegex())) {
                    val content = zip.bufferedReader(Charsets.UTF_8).readText()
                    slideFiles.add(entry.name to content)
                } else if (entry.name.contains("ppt/theme/theme1.xml")) {
                    themeXml = zip.bufferedReader(Charsets.UTF_8).readText()
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        // Sort slide files naturally (slide1, slide2, slide3...)
        slideFiles.sortBy { (name, _) ->
            Regex("""slide(\d+)\.xml""").find(name)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        }

        if (slideFiles.isEmpty()) {
            return TemplatePreviewResult.Fallback(
                data = buildFallbackFromProfile(profile, "PPTX"),
                warningMessage = "No slides found in presentation archive. Displaying default slide master."
            )
        }

        val slides = mutableListOf<PptxPreviewSlide>()

        for ((idx, pair) in slideFiles.withIndex()) {
            val content = pair.second
            val allTexts = mutableListOf<String>()
            val textRegex = Regex("""<a:t>([^<]+)</a:t>""")
            for (match in textRegex.findAll(content)) {
                val t = match.groupValues[1].trim()
                if (t.isNotBlank()) allTexts.add(t)
            }

            val title = allTexts.firstOrNull() ?: "Slide ${idx + 1}"
            val subtitle = if (allTexts.size > 1 && allTexts[1].length < 80) allTexts[1] else ""
            val bullets = if (allTexts.size > 2) allTexts.drop(2).take(5) else emptyList()

            slides.add(
                PptxPreviewSlide(
                    slideNumber = idx + 1,
                    title = title,
                    subtitle = subtitle,
                    bulletPoints = bullets,
                    calloutMetric = if (idx == 0) "16:9" else null,
                    calloutLabel = if (idx == 0) "Master Theme" else null
                )
            )
        }

        return TemplatePreviewResult.Success(
            data = TemplatePreviewData.PptxPreview(
                themeName = profile.name,
                primaryColorHex = profile.primaryColorHex,
                secondaryColorHex = profile.secondaryColorHex,
                font = profile.headingFont,
                slides = slides
            )
        )
    }

    private fun parseXlsx(file: File, profile: TemplateProfile): TemplatePreviewResult {
        var workbookXml: String? = null
        var sharedStringsXml: String? = null
        val sheetEntries = mutableListOf<Pair<String, String>>() // filename to content

        ZipInputStream(FileInputStream(file)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                when {
                    entry.name.endsWith("xl/workbook.xml") -> {
                        workbookXml = zip.bufferedReader(Charsets.UTF_8).readText()
                    }
                    entry.name.endsWith("xl/sharedStrings.xml") -> {
                        sharedStringsXml = zip.bufferedReader(Charsets.UTF_8).readText()
                    }
                    entry.name.matches(""".*xl/worksheets/sheet\d+\.xml$""".toRegex()) -> {
                        val content = zip.bufferedReader(Charsets.UTF_8).readText()
                        sheetEntries.add(entry.name to content)
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        // Parse shared strings table
        val sharedStrings = mutableListOf<String>()
        sharedStringsXml?.let { ssXml ->
            val tRegex = Regex("""<t(?:\s+[^>]*)?>([^<]*)</t>""")
            for (match in tRegex.findAll(ssXml)) {
                sharedStrings.add(match.groupValues[1])
            }
        }

        // Parse sheet names from workbook.xml
        val sheetNames = mutableListOf<String>()
        workbookXml?.let { wbXml ->
            val sheetRegex = Regex("""<sheet\s+[^>]*name="([^"]+)"""")
            for (match in sheetRegex.findAll(wbXml)) {
                sheetNames.add(match.groupValues[1])
            }
        }

        sheetEntries.sortBy { (name, _) ->
            Regex("""sheet(\d+)\.xml""").find(name)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        }

        if (sheetEntries.isEmpty()) {
            return TemplatePreviewResult.Fallback(
                data = buildFallbackFromProfile(profile, "XLSX"),
                warningMessage = "No worksheets found in spreadsheet archive."
            )
        }

        val sheets = mutableListOf<XlsxPreviewSheet>()

        for ((idx, pair) in sheetEntries.withIndex()) {
            val sheetName = sheetNames.getOrNull(idx) ?: "Sheet ${idx + 1}"
            val content = pair.second

            val rows = mutableListOf<List<String>>()
            val rowRegex = Regex("""<row\s+[^>]*>(.*?)</row>""", RegexOption.DOT_MATCHES_ALL)
            val cRegex = Regex("""<c\s+[^>]*?(?:t="([^"]*)")?[^>]*>(?:<v>([^<]*)</v>|<is><t>([^<]*)</t></is>)?""")

            for (rowMatch in rowRegex.findAll(content).take(20)) {
                val rowCells = mutableListOf<String>()
                for (cMatch in cRegex.findAll(rowMatch.groupValues[1])) {
                    val type = cMatch.groupValues[1]
                    val rawVal = cMatch.groupValues[2]
                    val inlineStr = cMatch.groupValues[3]

                    val cellText = when {
                        inlineStr.isNotBlank() -> inlineStr
                        type == "s" -> {
                            val strIdx = rawVal.toIntOrNull() ?: -1
                            sharedStrings.getOrNull(strIdx) ?: rawVal
                        }
                        else -> rawVal
                    }
                    if (cellText.isNotBlank()) {
                        rowCells.add(cellText)
                    }
                }
                if (rowCells.isNotEmpty()) {
                    rows.add(rowCells)
                }
            }

            val headers = if (rows.isNotEmpty()) rows.first() else listOf("A", "B", "C", "D")
            val dataRows = if (rows.size > 1) rows.drop(1) else emptyList()

            sheets.add(
                XlsxPreviewSheet(
                    name = sheetName,
                    headers = headers,
                    rows = dataRows
                )
            )
        }

        return TemplatePreviewResult.Success(
            data = TemplatePreviewData.XlsxPreview(
                workbookName = profile.name,
                primaryColorHex = profile.primaryColorHex,
                headerBgHex = profile.tableHeaderBgHex,
                font = profile.spreadsheetDefaultFont,
                sheets = sheets
            )
        )
    }

    private fun extractTextFromXml(xml: String): String {
        return xml.replace("""<[^>]+>""".toRegex(), " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("""\s+""".toRegex(), " ")
    }

    private fun buildFallbackFromProfile(profile: TemplateProfile, fileType: String): TemplatePreviewData {
        return when (fileType.uppercase()) {
            "PPTX" -> TemplatePreviewData.PptxPreview(
                themeName = profile.name,
                primaryColorHex = profile.primaryColorHex,
                secondaryColorHex = profile.secondaryColorHex,
                font = profile.headingFont,
                slides = listOf(
                    PptxPreviewSlide(
                        slideNumber = 1,
                        title = profile.name,
                        subtitle = "Master Template • 16:9 Presentation Format",
                        bulletPoints = listOf("Theme Color: ${profile.primaryColorHex}", "Heading Font: ${profile.headingFont}"),
                        calloutMetric = "16:9",
                        calloutLabel = "Corporate Ratio"
                    )
                )
            )
            "XLSX" -> TemplatePreviewData.XlsxPreview(
                workbookName = profile.name,
                primaryColorHex = profile.primaryColorHex,
                headerBgHex = profile.tableHeaderBgHex,
                font = profile.spreadsheetDefaultFont,
                sheets = listOf(
                    XlsxPreviewSheet(
                        name = "Overview",
                        headers = listOf("Category", "Target", "Status"),
                        rows = listOf(
                            listOf("Operations", "$100,000", "Approved"),
                            listOf("Technology", "$150,000", "Approved")
                        )
                    )
                )
            )
            else -> TemplatePreviewData.DocxPreview(
                title = profile.name,
                subtitle = "Standard Enterprise Template Format",
                headerText = profile.headerText.ifBlank { "SmartBubble Template Header" },
                footerText = profile.footerText.ifBlank { "SmartBubble Document • Page 1" },
                orientation = "Portrait",
                marginTopPt = profile.marginTopPt,
                marginLeftPt = profile.marginLeftPt,
                primaryColorHex = profile.primaryColorHex,
                headingFont = profile.headingFont,
                bodyFont = profile.bodyFont,
                sections = listOf(
                    DocxPreviewSection(
                        heading = "Executive Summary",
                        paragraphs = listOf("This reference document defines standard margins, header/footer branding, and typography for enterprise documents."),
                        bulletPoints = listOf("Margin Specification: ${profile.marginTopPt}pt top/bottom, ${profile.marginLeftPt}pt sides", "Heading Typography: ${profile.headingFont}")
                    )
                )
            )
        }
    }
}
