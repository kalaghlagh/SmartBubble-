package com.example.template.domain

enum class TemplateMode {
    STRICT_MATCH,
    SMART_ADAPTATION,
    STYLE_ONLY
}

data class TemplateProfile(
    val templateId: String,
    val name: String,
    val originalFileType: String, // "DOCX", "PPTX", "XLSX", "PDF"
    val supportedOutputTypes: List<String> = listOf("DOCX", "PPTX", "XLSX", "PDF"),
    val primaryColorHex: String = "#24D6E8",
    val secondaryColorHex: String = "#8665F7",
    val accentColorHex: String = "#10182B",
    val backgroundColorHex: String = "#FFFFFF",
    val textColorHex: String = "#1F2937",
    val headingFont: String = "Calibri",
    val bodyFont: String = "Calibri",
    val baseFontSizePt: Int = 11,
    val heading1SizePt: Int = 18,
    val heading2SizePt: Int = 14,
    val marginTopPt: Int = 54,
    val marginBottomPt: Int = 54,
    val marginLeftPt: Int = 54,
    val marginRightPt: Int = 54,
    val headerText: String = "",
    val footerText: String = "Smart Bubble Document",
    val showPageNumbers: Boolean = true,
    val tableHeaderBgHex: String = "#24D6E8",
    val tableAlternateRowBgHex: String = "#F3F4F6",
    val tableBorderHex: String = "#D1D5DB",
    val slideThemeName: String = "Modern Executive",
    val slideWidthPt: Int = 960,
    val slideHeightPt: Int = 540,
    val spreadsheetDefaultFont: String = "Calibri",
    val isAnalysisComplete: Boolean = true,
    val extractedPropertiesSummary: String = "Fonts, Margins, Colors, Table Styles",
    val version: Int = 1,
    /**
     * Generation setting: use the uploaded template FILE itself (its styles, headers/footers,
     * masters, layouts, theme). When false only the colors/fonts extracted into this profile are
     * applied to a newly built document.
     */
    val useTemplateLayout: Boolean = true,
    /**
     * Generation setting: keep the content that is already in the template (text, slides,
     * sheets) and add the generated content after it. When false that content is replaced.
     */
    val keepTemplateContent: Boolean = false,
    // ---- Formatting extracted from a PDF template (layoutSource == "PDF"). Defaults keep the
    // behaviour of every other template unchanged.
    /** "PDF" when the values below were measured on a PDF template; "" otherwise. */
    val layoutSource: String = "",
    /** Page size in points (0 = the engine's default page). */
    val pageWidthPt: Int = 0,
    val pageHeightPt: Int = 0,
    /** Heading text color ("" = [primaryColorHex]). */
    val headingColorHex: String = "",
    val headingBold: Boolean = true,
    /** "start", "center", "end" or "justify" (logical: "start" is the right edge in RTL text). */
    val bodyAlignment: String = "start",
    val headingAlignment: String = "start",
    /** Line height as a multiple of the font size (0 = engine default). */
    val lineSpacing: Float = 0f,
    /** Extra space between paragraphs in points (-1 = engine default). */
    val paragraphSpacingPt: Int = -1,
    val headerFontSizePt: Int = 0,
    val footerFontSizePt: Int = 0,
    val headerAlignment: String = "start",
    val footerAlignment: String = "start",
    /** Distance from the top edge to the header baseline / from the bottom edge to the footer baseline. */
    val headerOffsetPt: Int = 0,
    val footerOffsetPt: Int = 0,
    /** Where page numbers are printed: "header-start", "footer-center", ... ("" = not detected). */
    val pageNumberPosition: String = "",
    val tableDetected: Boolean = false,
    val tableCellAlignment: String = "start",
    /** List marker used by the template ("•", "-", "1.") and its indent in points. */
    val listMarker: String = "",
    val listIndentPt: Int = 0,
    /** Colored shapes as "left,top,width,height,#RRGGBB;..." in fractions of the page (top-left origin). */
    val decorations: String = "",
    /** "TEXT", "PARTIAL" (some content could not be decoded) or "NO_TEXT" (image-only/scanned). */
    val pdfTextStatus: String = "",
    val pdfPageCount: Int = 0,
    /** The template's text runs right to left (Arabic). */
    val rtl: Boolean = false,
    /** File name the user imported (for display). */
    val sourceFileName: String = ""
) {
    /** True for profiles measured on a PDF template. */
    val isPdfLayout: Boolean get() = layoutSource == "PDF"

    /** A scanned / image-only PDF: only the page size is known. */
    val isImageOnlyPdf: Boolean get() = isPdfLayout && pdfTextStatus == "NO_TEXT"

    fun toJson(): String {
        val map = linkedMapOf<String, String>()
        map["templateId"] = templateId
        map["name"] = name
        map["originalFileType"] = originalFileType
        map["primaryColorHex"] = primaryColorHex
        map["secondaryColorHex"] = secondaryColorHex
        map["accentColorHex"] = accentColorHex
        map["backgroundColorHex"] = backgroundColorHex
        map["textColorHex"] = textColorHex
        map["headingFont"] = headingFont
        map["bodyFont"] = bodyFont
        map["baseFontSizePt"] = baseFontSizePt.toString()
        map["heading1SizePt"] = heading1SizePt.toString()
        map["heading2SizePt"] = heading2SizePt.toString()
        map["marginTopPt"] = marginTopPt.toString()
        map["marginBottomPt"] = marginBottomPt.toString()
        map["marginLeftPt"] = marginLeftPt.toString()
        map["marginRightPt"] = marginRightPt.toString()
        map["headerText"] = headerText
        map["footerText"] = footerText
        map["showPageNumbers"] = showPageNumbers.toString()
        map["tableHeaderBgHex"] = tableHeaderBgHex
        map["tableAlternateRowBgHex"] = tableAlternateRowBgHex
        map["tableBorderHex"] = tableBorderHex
        map["slideThemeName"] = slideThemeName
        map["slideWidthPt"] = slideWidthPt.toString()
        map["slideHeightPt"] = slideHeightPt.toString()
        map["spreadsheetDefaultFont"] = spreadsheetDefaultFont
        map["isAnalysisComplete"] = isAnalysisComplete.toString()
        map["extractedPropertiesSummary"] = extractedPropertiesSummary
        map["version"] = version.toString()
        map["useTemplateLayout"] = useTemplateLayout.toString()
        map["keepTemplateContent"] = keepTemplateContent.toString()
        if (layoutSource.isNotEmpty() || sourceFileName.isNotEmpty()) {
            map["layoutSource"] = layoutSource
            map["pageWidthPt"] = pageWidthPt.toString()
            map["pageHeightPt"] = pageHeightPt.toString()
            map["headingColorHex"] = headingColorHex
            map["headingBold"] = headingBold.toString()
            map["bodyAlignment"] = bodyAlignment
            map["headingAlignment"] = headingAlignment
            map["lineSpacing"] = lineSpacing.toString()
            map["paragraphSpacingPt"] = paragraphSpacingPt.toString()
            map["headerFontSizePt"] = headerFontSizePt.toString()
            map["footerFontSizePt"] = footerFontSizePt.toString()
            map["headerAlignment"] = headerAlignment
            map["footerAlignment"] = footerAlignment
            map["headerOffsetPt"] = headerOffsetPt.toString()
            map["footerOffsetPt"] = footerOffsetPt.toString()
            map["pageNumberPosition"] = pageNumberPosition
            map["tableDetected"] = tableDetected.toString()
            map["tableCellAlignment"] = tableCellAlignment
            map["listMarker"] = listMarker
            map["listIndentPt"] = listIndentPt.toString()
            map["decorations"] = decorations
            map["pdfTextStatus"] = pdfTextStatus
            map["pdfPageCount"] = pdfPageCount.toString()
            map["rtl"] = rtl.toString()
            map["sourceFileName"] = sourceFileName
        }

        return map.entries.joinToString(prefix = "{", postfix = "}", separator = ",") { (k, v) ->
            "\"$k\":\"${v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")}\""
        }
    }

    companion object {
        fun fromJson(jsonStr: String): TemplateProfile {
            if (jsonStr.isBlank()) return defaultProfile()
            return try {
                val map = mutableMapOf<String, String>()
                val regex = Regex(""""([^"]+)":\s*"((?:\\.|[^"\\])*)"""")
                regex.findAll(jsonStr).forEach { match ->
                    val k = match.groupValues[1]
                    val v = match.groupValues[2].replace("\\\"", "\"").replace("\\\\", "\\").replace("\\n", "\n")
                    map[k] = v
                }
                TemplateProfile(
                    templateId = map["templateId"] ?: "default",
                    name = map["name"] ?: "Default Corporate",
                    originalFileType = map["originalFileType"] ?: "DOCX",
                    primaryColorHex = map["primaryColorHex"] ?: "#24D6E8",
                    secondaryColorHex = map["secondaryColorHex"] ?: "#8665F7",
                    accentColorHex = map["accentColorHex"] ?: "#10182B",
                    backgroundColorHex = map["backgroundColorHex"] ?: "#FFFFFF",
                    textColorHex = map["textColorHex"] ?: "#1F2937",
                    headingFont = map["headingFont"] ?: "Calibri",
                    bodyFont = map["bodyFont"] ?: "Calibri",
                    baseFontSizePt = map["baseFontSizePt"]?.toIntOrNull() ?: 11,
                    heading1SizePt = map["heading1SizePt"]?.toIntOrNull() ?: 18,
                    heading2SizePt = map["heading2SizePt"]?.toIntOrNull() ?: 14,
                    marginTopPt = map["marginTopPt"]?.toIntOrNull() ?: 54,
                    marginBottomPt = map["marginBottomPt"]?.toIntOrNull() ?: 54,
                    marginLeftPt = map["marginLeftPt"]?.toIntOrNull() ?: 54,
                    marginRightPt = map["marginRightPt"]?.toIntOrNull() ?: 54,
                    headerText = map["headerText"] ?: "",
                    footerText = map["footerText"] ?: "Smart Bubble Document",
                    showPageNumbers = map["showPageNumbers"]?.toBooleanStrictOrNull() ?: true,
                    tableHeaderBgHex = map["tableHeaderBgHex"] ?: "#24D6E8",
                    tableAlternateRowBgHex = map["tableAlternateRowBgHex"] ?: "#F3F4F6",
                    tableBorderHex = map["tableBorderHex"] ?: "#D1D5DB",
                    slideThemeName = map["slideThemeName"] ?: "Modern Executive",
                    slideWidthPt = map["slideWidthPt"]?.toIntOrNull() ?: 960,
                    slideHeightPt = map["slideHeightPt"]?.toIntOrNull() ?: 540,
                    spreadsheetDefaultFont = map["spreadsheetDefaultFont"] ?: "Calibri",
                    isAnalysisComplete = map["isAnalysisComplete"]?.toBooleanStrictOrNull() ?: true,
                    extractedPropertiesSummary = map["extractedPropertiesSummary"] ?: "Corporate Identity Extracted",
                    version = map["version"]?.toIntOrNull() ?: 1,
                    useTemplateLayout = map["useTemplateLayout"]?.toBooleanStrictOrNull() ?: true,
                    keepTemplateContent = map["keepTemplateContent"]?.toBooleanStrictOrNull() ?: false,
                    layoutSource = map["layoutSource"] ?: "",
                    pageWidthPt = map["pageWidthPt"]?.toIntOrNull() ?: 0,
                    pageHeightPt = map["pageHeightPt"]?.toIntOrNull() ?: 0,
                    headingColorHex = map["headingColorHex"] ?: "",
                    headingBold = map["headingBold"]?.toBooleanStrictOrNull() ?: true,
                    bodyAlignment = map["bodyAlignment"] ?: "start",
                    headingAlignment = map["headingAlignment"] ?: "start",
                    lineSpacing = map["lineSpacing"]?.toFloatOrNull() ?: 0f,
                    paragraphSpacingPt = map["paragraphSpacingPt"]?.toIntOrNull() ?: -1,
                    headerFontSizePt = map["headerFontSizePt"]?.toIntOrNull() ?: 0,
                    footerFontSizePt = map["footerFontSizePt"]?.toIntOrNull() ?: 0,
                    headerAlignment = map["headerAlignment"] ?: "start",
                    footerAlignment = map["footerAlignment"] ?: "start",
                    headerOffsetPt = map["headerOffsetPt"]?.toIntOrNull() ?: 0,
                    footerOffsetPt = map["footerOffsetPt"]?.toIntOrNull() ?: 0,
                    pageNumberPosition = map["pageNumberPosition"] ?: "",
                    tableDetected = map["tableDetected"]?.toBooleanStrictOrNull() ?: false,
                    tableCellAlignment = map["tableCellAlignment"] ?: "start",
                    listMarker = map["listMarker"] ?: "",
                    listIndentPt = map["listIndentPt"]?.toIntOrNull() ?: 0,
                    decorations = map["decorations"] ?: "",
                    pdfTextStatus = map["pdfTextStatus"] ?: "",
                    pdfPageCount = map["pdfPageCount"]?.toIntOrNull() ?: 0,
                    rtl = map["rtl"]?.toBooleanStrictOrNull() ?: false,
                    sourceFileName = map["sourceFileName"] ?: ""
                )
            } catch (e: Exception) {
                defaultProfile()
            }
        }

        fun defaultProfile(): TemplateProfile {
            return TemplateProfile(
                templateId = "default-corp",
                name = "Smart Bubble Executive Theme",
                originalFileType = "DOCX",
                primaryColorHex = "#24D6E8",
                secondaryColorHex = "#8665F7",
                accentColorHex = "#10182B",
                backgroundColorHex = "#FFFFFF",
                textColorHex = "#0C1220",
                headingFont = "Calibri",
                bodyFont = "Calibri",
                baseFontSizePt = 11,
                heading1SizePt = 18,
                heading2SizePt = 14,
                marginTopPt = 54,
                marginBottomPt = 54,
                marginLeftPt = 54,
                marginRightPt = 54,
                headerText = "Smart Bubble Enterprise",
                footerText = "Confidential • Smart Bubble Workspace",
                showPageNumbers = true,
                tableHeaderBgHex = "#24D6E8",
                tableAlternateRowBgHex = "#F0F9FF",
                tableBorderHex = "#BAE6FD"
            )
        }
    }
}
