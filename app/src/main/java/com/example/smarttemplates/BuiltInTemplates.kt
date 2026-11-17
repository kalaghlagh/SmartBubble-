package com.example.smarttemplates

import com.example.template.domain.TemplateProfile

/**
 * A template that ships with the app. It is a formatting profile (page, fonts, colors,
 * spacing, borders, header/footer, table and slide styles) applied by the document engines,
 * which write genuine DOCX, PDF, XLSX and PPTX files; no template files are bundled, so the
 * APK stays small. Built-in templates cannot be deleted. Their name and description are string
 * resources ("tpl_builtin_<category>_<style>" and "..._desc").
 */
data class BuiltInTemplate(
    val id: String,
    val categoryId: String,
    val style: String,
    /** "DOCX" (also PDF), "XLSX" or "PPTX". */
    val format: String,
    /** Subcategories this style suits best (used when the user chose no default). */
    val preferredFor: Set<String>,
    val profile: TemplateProfile
) {
    val nameKey: String get() = "tpl_builtin_${categoryId}_$style"
    val descriptionKey: String get() = "${nameKey}_desc"
    val kind: OutputKind get() = OutputKind.ofFormat(format)

    /** Subcategories this template can format (every subcategory of its category). */
    val subcategories: List<String> get() = TemplateCatalog.category(categoryId)?.subcategories?.map { it.id }.orEmpty()
}

object BuiltInTemplates {

    const val ID_PREFIX = "builtin."
    const val LAYOUT_SOURCE = "BUILTIN"

    fun isBuiltIn(id: String?): Boolean = id != null && id.startsWith(ID_PREFIX)

    // A4 portrait in points.
    private const val A4_W = 595
    private const val A4_H = 842

    /** Page border "side:style:widthPt:spacePt:#color" on the given sides. */
    private fun border(style: String, width: Float, space: Int, color: String, sides: List<String> = listOf("top", "left", "bottom", "right")) =
        sides.joinToString(";") { "$it:$style:$width:$space:$color" }

    private fun doc(
        id: String, format: String = "DOCX",
        bodyFont: String, headingFont: String, body: Int, h1: Int, h2: Int,
        text: String, heading: String, primary: String, secondary: String,
        margins: IntArray, line: Float, paragraphAfter: Int,
        bodyAlign: String, headingAlign: String,
        pageNumbers: String = "footer-center", borders: String = "", borderFrom: String = "text",
        tableHead: String = primary, tableAlt: String = "#F3F6FA", tableBorder: String = "#C9D3E0",
        headingBold: Boolean = true, firstLineIndent: Int = 0
    ) = TemplateProfile(
        templateId = id, name = id, originalFileType = format,
        primaryColorHex = primary, secondaryColorHex = secondary, accentColorHex = heading,
        textColorHex = text, headingFont = headingFont, bodyFont = bodyFont,
        baseFontSizePt = body, heading1SizePt = h1, heading2SizePt = h2, heading3SizePt = (h2 + body) / 2,
        marginTopPt = margins[0], marginBottomPt = margins[1], marginLeftPt = margins[2], marginRightPt = margins[3],
        headerText = "", footerText = "", showPageNumbers = pageNumbers.isNotEmpty(),
        tableHeaderBgHex = tableHead, tableAlternateRowBgHex = tableAlt, tableBorderHex = tableBorder,
        spreadsheetDefaultFont = bodyFont,
        isAnalysisComplete = true, extractedPropertiesSummary = "",
        useTemplateLayout = false, keepTemplateContent = false,
        layoutSource = LAYOUT_SOURCE, pageWidthPt = A4_W, pageHeightPt = A4_H,
        headingColorHex = heading, headingBold = headingBold, bodyAlignment = bodyAlign, headingAlignment = headingAlign,
        lineSpacing = line, paragraphSpacingPt = paragraphAfter,
        headerFontSizePt = 9, footerFontSizePt = 9, footerAlignment = "center",
        headerOffsetPt = 36, footerOffsetPt = 28,
        pageNumberPosition = pageNumbers, tableCellAlignment = "start", listMarker = "•", listIndentPt = 18,
        pageBorders = borders, pageBorderOffsetFrom = borderFrom, firstLineIndentPt = firstLineIndent
    )

    private fun sheet(id: String, font: String, head: String, alt: String, borderColor: String, primary: String) = TemplateProfile(
        templateId = id, name = id, originalFileType = "XLSX",
        primaryColorHex = primary, secondaryColorHex = head, accentColorHex = primary, textColorHex = "#111827",
        headingFont = font, bodyFont = font, baseFontSizePt = 11,
        tableHeaderBgHex = head, tableAlternateRowBgHex = alt, tableBorderHex = borderColor,
        spreadsheetDefaultFont = font, headerText = "", footerText = "",
        useTemplateLayout = false, keepTemplateContent = false, layoutSource = LAYOUT_SOURCE
    )

    private fun slides(id: String, theme: String, font: String, primary: String, secondary: String, accent: String, background: String, text: String) = TemplateProfile(
        templateId = id, name = id, originalFileType = "PPTX",
        primaryColorHex = primary, secondaryColorHex = secondary, accentColorHex = accent, backgroundColorHex = background, textColorHex = text,
        headingFont = font, bodyFont = font, slideThemeName = theme, headerText = "", footerText = "",
        tableHeaderBgHex = primary, useTemplateLayout = false, keepTemplateContent = false, layoutSource = LAYOUT_SOURCE
    )

    private val M_STD = intArrayOf(72, 72, 72, 72)

    val all: List<BuiltInTemplate> = listOf(
        // ---------------------------------------------------------------- reports
        BuiltInTemplate("builtin.reports.classic", "reports", "classic", "DOCX",
            setOf("reports.administrative", "reports.academic", "reports.scientific", "reports.research"),
            doc("builtin.reports.classic", bodyFont = "Times New Roman", headingFont = "Times New Roman", body = 12, h1 = 20, h2 = 15,
                text = "#111111", heading = "#1F3A5F", primary = "#1F3A5F", secondary = "#3B6EA8", margins = M_STD, line = 1.5f, paragraphAfter = 6,
                bodyAlign = "justify", headingAlign = "center", borders = border("double", 0.75f, 24, "#1F3A5F"), borderFrom = "page",
                tableHead = "#D9E2F3", tableAlt = "#F5F7FB", tableBorder = "#7F8FA6")),
        BuiltInTemplate("builtin.reports.modern", "reports", "modern", "DOCX",
            setOf("reports.technical", "reports.financial", "reports.project", "reports.general"),
            doc("builtin.reports.modern", bodyFont = "Calibri", headingFont = "Calibri", body = 11, h1 = 24, h2 = 15,
                text = "#1F2937", heading = "#0F766E", primary = "#0F766E", secondary = "#14B8A6", margins = intArrayOf(64, 64, 64, 64), line = 1.25f, paragraphAfter = 8,
                bodyAlign = "start", headingAlign = "start", pageNumbers = "footer-end", borders = border("single", 6f, 18, "#0F766E", listOf("top")), borderFrom = "page",
                tableHead = "#CCFBF1", tableAlt = "#F0FDFA", tableBorder = "#99F6E4")),
        BuiltInTemplate("builtin.reports.minimal", "reports", "minimal", "DOCX", emptySet(),
            doc("builtin.reports.minimal", bodyFont = "Arial", headingFont = "Arial", body = 10, h1 = 18, h2 = 13,
                text = "#222222", heading = "#111111", primary = "#111111", secondary = "#6B7280", margins = intArrayOf(85, 85, 85, 85), line = 1.35f, paragraphAfter = 8,
                bodyAlign = "start", headingAlign = "start", tableHead = "#E5E7EB", tableAlt = "#FAFAFA", tableBorder = "#D1D5DB")),
        // ---------------------------------------------------------------- letters
        BuiltInTemplate("builtin.letters.classic", "letters", "classic", "DOCX",
            setOf("letters.recommendation", "letters.personal", "letters.general"),
            doc("builtin.letters.classic", bodyFont = "Times New Roman", headingFont = "Times New Roman", body = 12, h1 = 14, h2 = 12,
                text = "#111111", heading = "#111111", primary = "#111111", secondary = "#444444", margins = intArrayOf(72, 72, 85, 72), line = 1.15f, paragraphAfter = 10,
                bodyAlign = "justify", headingAlign = "start", pageNumbers = "")),
        BuiltInTemplate("builtin.letters.modern", "letters", "modern", "DOCX",
            setOf("letters.business", "letters.cover", "letters.employment"),
            doc("builtin.letters.modern", bodyFont = "Calibri", headingFont = "Calibri", body = 11, h1 = 15, h2 = 12,
                text = "#1F2937", heading = "#1D4ED8", primary = "#1D4ED8", secondary = "#60A5FA", margins = intArrayOf(64, 64, 72, 72), line = 1.2f, paragraphAfter = 10,
                bodyAlign = "start", headingAlign = "start", pageNumbers = "", borders = border("single", 6f, 18, "#1D4ED8", listOf("top")), borderFrom = "page")),
        BuiltInTemplate("builtin.letters.formal", "letters", "formal", "DOCX",
            setOf("letters.administrative"),
            doc("builtin.letters.formal", bodyFont = "Cambria", headingFont = "Cambria", body = 12, h1 = 14, h2 = 12,
                text = "#111111", heading = "#1F2937", primary = "#1F2937", secondary = "#4B5563", margins = intArrayOf(85, 72, 85, 85), line = 1.5f, paragraphAfter = 8,
                bodyAlign = "justify", headingAlign = "center", pageNumbers = "", borders = border("single", 0.75f, 24, "#1F2937"), borderFrom = "page")),
        // ---------------------------------------------------------------- requests
        BuiltInTemplate("builtin.requests.formal", "requests", "formal", "DOCX",
            setOf("requests.administrative", "requests.university", "requests.leave", "requests.permission", "requests.complaint", "requests.general"),
            doc("builtin.requests.formal", bodyFont = "Times New Roman", headingFont = "Times New Roman", body = 12, h1 = 14, h2 = 12,
                text = "#111111", heading = "#111111", primary = "#111111", secondary = "#444444", margins = intArrayOf(72, 72, 85, 72), line = 1.5f, paragraphAfter = 8,
                bodyAlign = "justify", headingAlign = "center", pageNumbers = "")),
        BuiltInTemplate("builtin.requests.modern", "requests", "modern", "DOCX",
            setOf("requests.job"),
            doc("builtin.requests.modern", bodyFont = "Calibri", headingFont = "Calibri", body = 11, h1 = 15, h2 = 12,
                text = "#1F2937", heading = "#7C3AED", primary = "#7C3AED", secondary = "#A78BFA", margins = intArrayOf(64, 64, 72, 72), line = 1.2f, paragraphAfter = 10,
                bodyAlign = "start", headingAlign = "start", pageNumbers = "", borders = border("single", 6f, 18, "#7C3AED", listOf("top")), borderFrom = "page")),
        // ---------------------------------------------------------------- theses and academic documents
        BuiltInTemplate("builtin.academic.formal", "academic", "formal", "DOCX",
            setOf("academic.licence", "academic.master", "academic.phd", "academic.project"),
            doc("builtin.academic.formal", bodyFont = "Times New Roman", headingFont = "Times New Roman", body = 12, h1 = 16, h2 = 14,
                text = "#000000", heading = "#000000", primary = "#1F3A5F", secondary = "#3B6EA8", margins = intArrayOf(71, 71, 85, 71), line = 1.5f, paragraphAfter = 6,
                bodyAlign = "justify", headingAlign = "center", tableHead = "#D9E2F3", tableBorder = "#808080", firstLineIndent = 28)),
        BuiltInTemplate("builtin.academic.classic", "academic", "classic", "DOCX",
            setOf("academic.research_paper", "academic.scientific_paper"),
            doc("builtin.academic.classic", bodyFont = "Cambria", headingFont = "Cambria", body = 11, h1 = 16, h2 = 13,
                text = "#111111", heading = "#7F1D1D", primary = "#7F1D1D", secondary = "#B45309", margins = intArrayOf(72, 72, 72, 72), line = 1.3f, paragraphAfter = 6,
                bodyAlign = "justify", headingAlign = "start", borders = border("single", 0.5f, 20, "#7F1D1D"), borderFrom = "page",
                tableHead = "#FDE8E8", tableAlt = "#FFF7F7", tableBorder = "#C9A9A9")),
        // ---------------------------------------------------------------- spreadsheets
        BuiltInTemplate("builtin.spreadsheets.financial", "spreadsheets", "financial", "XLSX",
            setOf("spreadsheets.financial", "spreadsheets.sales", "spreadsheets.statistics", "spreadsheets.data_analysis"),
            sheet("builtin.spreadsheets.financial", "Calibri", head = "#1F3A5F", alt = "#EEF2F7", borderColor = "#C9D3E0", primary = "#1F3A5F")),
        BuiltInTemplate("builtin.spreadsheets.clean", "spreadsheets", "clean", "XLSX",
            setOf("spreadsheets.inventory", "spreadsheets.project_tracking", "spreadsheets.attendance", "spreadsheets.general"),
            sheet("builtin.spreadsheets.clean", "Calibri", head = "#0F766E", alt = "#F0FDFA", borderColor = "#99F6E4", primary = "#0F766E")),
        BuiltInTemplate("builtin.spreadsheets.minimal", "spreadsheets", "minimal", "XLSX", emptySet(),
            sheet("builtin.spreadsheets.minimal", "Arial", head = "#374151", alt = "#F9FAFB", borderColor = "#D1D5DB", primary = "#374151")),
        // ---------------------------------------------------------------- presentations
        BuiltInTemplate("builtin.presentations.academic", "presentations", "academic", "PPTX",
            setOf("presentations.academic", "presentations.scientific", "presentations.educational"),
            slides("builtin.presentations.academic", "Academic Clean", "Cambria", primary = "#1F3A5F", secondary = "#3B6EA8", accent = "#F2C14E", background = "#FFFFFF", text = "#1F2937")),
        BuiltInTemplate("builtin.presentations.corporate", "presentations", "corporate", "PPTX",
            setOf("presentations.business", "presentations.project", "presentations.general"),
            slides("builtin.presentations.corporate", "Corporate Modern", "Calibri", primary = "#0F766E", secondary = "#F59E0B", accent = "#0B3B36", background = "#FFFFFF", text = "#111827")),
        BuiltInTemplate("builtin.presentations.minimal", "presentations", "minimal", "PPTX", emptySet(),
            slides("builtin.presentations.minimal", "Minimal", "Arial", primary = "#111827", secondary = "#6B7280", accent = "#E5E7EB", background = "#FFFFFF", text = "#111827"))
    )

    private val byId = all.associateBy { it.id }

    fun byId(id: String?): BuiltInTemplate? = id?.let { byId[it] }

    fun forCategory(categoryId: String): List<BuiltInTemplate> = all.filter { it.categoryId == categoryId }

    /** Built-in templates that can format [subcategoryId]. */
    fun forSubcategory(subcategoryId: String): List<BuiltInTemplate> {
        val category = TemplateCatalog.subcategory(subcategoryId)?.categoryId ?: return emptyList()
        return forCategory(category)
    }

    /** The built-in template that suits [subcategoryId] best (its preferred style, else the first of its category). */
    fun bestFor(subcategoryId: String): BuiltInTemplate? {
        val options = forSubcategory(subcategoryId)
        return options.firstOrNull { subcategoryId in it.preferredFor } ?: options.firstOrNull()
    }
}
