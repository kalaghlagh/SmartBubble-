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
    val version: Int = 1
) {
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
                    version = map["version"]?.toIntOrNull() ?: 1
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
