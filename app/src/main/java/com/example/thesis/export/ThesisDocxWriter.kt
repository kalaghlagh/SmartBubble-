package com.example.thesis.export

import com.example.thesis.core.InlineFormat
import com.example.thesis.core.ThesisLabels
import com.example.thesis.core.ThesisNumbering
import com.example.thesis.core.ThesisStructure
import com.example.thesis.model.ElementType
import com.example.thesis.model.SectionKind
import com.example.thesis.model.ThesisAsset
import com.example.thesis.model.ThesisElement
import com.example.thesis.model.ThesisSection
import com.example.thesis.model.ThesisSnapshot
import com.example.template.domain.TemplateProfile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Formatting source of a thesis document. */
sealed class ThesisTemplate {
    abstract val profile: TemplateProfile

    /** A Word template from My Templates: its package (styles, theme, headers, footers, page setup) is reused. */
    data class DocxFile(val file: File, override val profile: TemplateProfile, val name: String) : ThesisTemplate()

    /** A PDF template's measured profile, or the built-in academic profile. */
    data class Profile(override val profile: TemplateProfile, val name: String?) : ThesisTemplate()
}

/** What happened while writing a document (shown to the user, never hidden). */
data class ThesisRenderReport(val warnings: List<String>, val figureCount: Int, val tableCount: Int)

/**
 * Writes a thesis as a real Word document: cover page from the project's metadata (only fields
 * the user filled), preliminary pages, numbered chapters and sections as heading styles, body
 * text, lists, images with captions, tables with captions, references and appendices.
 *
 * Indexes are real Word fields: the table of contents (TOC \o), list of figures (TOC \c "Figure")
 * and list of tables (TOC \c "Table") collect the heading styles and the SEQ-numbered captions,
 * and the file asks Word to update its fields when opened so page numbers are filled in by the
 * word processor. A PAGE field numbers the pages. The template file is only read.
 */
class ThesisDocxWriter {

    companion object {
        private const val EMU_PER_TWIP = 635L
        private const val W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
        private const val R_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        private const val WP_NS = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"

        /** Academic built-in profile: A4, 2.5 cm margins, Times New Roman 12 pt, 1.5 line spacing. */
        fun builtInProfile(): TemplateProfile = TemplateProfile.defaultProfile().copy(
            templateId = "thesis-builtin", name = "Built-in academic formatting", headerText = "", footerText = "",
            headingFont = "Times New Roman", bodyFont = "Times New Roman", baseFontSizePt = 12, heading1SizePt = 16, heading2SizePt = 14,
            marginTopPt = 71, marginBottomPt = 71, marginLeftPt = 71, marginRightPt = 71, primaryColorHex = "#1F3A5F",
            textColorHex = "#000000", tableHeaderBgHex = "#D9E2F3", tableBorderHex = "#808080", useTemplateLayout = false,
            pageWidthPt = 595, pageHeightPt = 842, lineSpacing = 1.5f, paragraphSpacingPt = 6, bodyAlignment = "justify",
            headingColorHex = "#1F3A5F", pageNumberPosition = "footer-center", layoutSource = ""
        )

        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

        fun isArabic(text: String) = text.any { it in '؀'..'ۿ' || it in 'ݐ'..'ݿ' || it in 'ﭐ'..'﷿' || it in 'ﹰ'..'\uFEFF' }

        private fun hex(value: String, fallback: String = "000000"): String {
            val h = value.removePrefix("#").uppercase(Locale.ROOT)
            return if (Regex("[0-9A-F]{6}").matches(h)) h else fallback
        }
    }

    private class Ctx(
        val snapshot: ThesisSnapshot,
        val numbering: ThesisNumbering,
        val assets: Map<String, ThesisAsset>,
        val language: String,
        val rtl: Boolean,
        /** Text width in twips (images are sized against it). */
        val textWidthTwips: Long,
        val media: MutableList<Pair<String, File>> = ArrayList(),
        val warnings: MutableList<String> = ArrayList(),
        var drawingId: Int = 1
    )

    fun write(snapshot: ThesisSnapshot, assets: List<ThesisAsset>, template: ThesisTemplate, output: File): ThesisRenderReport {
        val numbering = ThesisStructure.numbering(snapshot)
        val language = snapshot.meta.language
        val profile = template.profile
        return when (template) {
            is ThesisTemplate.DocxFile -> writeOnTemplate(snapshot, numbering, assets, template, output)
            is ThesisTemplate.Profile -> {
                val pageW = (if (profile.pageWidthPt > 0) profile.pageWidthPt else 595) * 20L
                val ctx = Ctx(snapshot, numbering, assets.associateBy { it.id }, language, snapshot.meta.rtl,
                    pageW - (profile.marginLeftPt + profile.marginRightPt) * 20L)
                val body = body(ctx)
                writeStandalone(ctx, body, profile, output)
                ThesisRenderReport(ctx.warnings, numbering.figures.size, numbering.tables.size)
            }
        }
    }

    // ------------------------------------------------------------------ body

    private fun pPr(style: String?, ctx: Ctx, text: String, extra: String = ""): String {
        val bidi = ctx.rtl || isArabic(text)
        return "<w:pPr>" + (style?.let { "<w:pStyle w:val=\"$it\"/>" } ?: "") + (if (bidi) "<w:bidi/>" else "") + extra + "</w:pPr>"
    }

    private fun run(text: String, ctx: Ctx, rPr: String = ""): String {
        val rtl = ctx.rtl || isArabic(text)
        val props = rPr + (if (rtl) "<w:rtl/>" else "")
        return "<w:r>" + (if (props.isNotEmpty()) "<w:rPr>$props</w:rPr>" else "") + "<w:t xml:space=\"preserve\">${esc(text)}</w:t></w:r>"
    }

    private fun para(sb: StringBuilder, ctx: Ctx, text: String, style: String? = null, extraPPr: String = "", rPr: String = "") {
        sb.append("<w:p>").append(pPr(style, ctx, text, extraPPr)).append(run(text, ctx, rPr)).append("</w:p>")
    }

    private fun pageBreak(sb: StringBuilder) = sb.append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>")

    /** Runs of **bold**, *italic* and __underline__ text (markers typed in the section editor). */
    private fun runs(text: String, ctx: Ctx, rPr: String = ""): String =
        if (!InlineFormat.hasMarkers(text)) run(text, ctx, rPr)
        else InlineFormat.parse(text).joinToString("") { r ->
            run(r.text, ctx, rPr + (if (r.bold) "<w:b/><w:bCs/>" else "") + (if (r.italic) "<w:i/><w:iCs/>" else "") + (if (r.underline) "<w:u w:val=\"single\"/>" else ""))
        }

    /** A content paragraph: formatted runs, the paragraph's own alignment and page break. */
    private fun textPara(sb: StringBuilder, ctx: Ctx, e: ThesisElement, text: String, style: String?, extraPPr: String = "") {
        val jc = when (e.data.textAlign) {
            "center" -> "<w:jc w:val=\"center\"/>"
            "end" -> "<w:jc w:val=\"${if (ctx.rtl) "left" else "right"}\"/>"
            "justify" -> "<w:jc w:val=\"both\"/>"
            else -> ""
        }
        val pb = if (e.data.pageBreakBefore) "<w:pageBreakBefore/>" else ""
        sb.append("<w:p>").append(pPr(style, ctx, text, pb + extraPPr + jc)).append(runs(text, ctx)).append("</w:p>")
    }

    private fun field(instr: String, cached: String, ctx: Ctx): String =
        "<w:r><w:fldChar w:fldCharType=\"begin\" w:dirty=\"true\"/></w:r><w:r><w:instrText xml:space=\"preserve\"> $instr </w:instrText></w:r>" +
            "<w:r><w:fldChar w:fldCharType=\"separate\"/></w:r>" + run(cached, ctx) + "<w:r><w:fldChar w:fldCharType=\"end\"/></w:r>"

    private fun body(ctx: Ctx): String {
        val sb = StringBuilder()
        val s = ctx.snapshot
        val outline = s.outline(includeDisabled = false)
        var first = true
        for ((section, _) in outline) {
            val startsPage = section.parentId == null && section.kind != SectionKind.COVER
            if (startsPage && !first) pageBreak(sb)
            first = false
            when (section.kind) {
                SectionKind.COVER -> cover(sb, ctx)
                SectionKind.TABLE_OF_CONTENTS -> index(sb, ctx, section, "TOC \\o \"1-3\" \\h \\z \\u", tocCache(ctx))
                SectionKind.LIST_OF_FIGURES -> index(sb, ctx, section, "TOC \\h \\z \\c \"Figure\"",
                    s.orderedElements().filter { it.type == ElementType.IMAGE }.map { ThesisStructure.figureCaption(it, ctx.numbering, ctx.language) })
                SectionKind.LIST_OF_TABLES -> index(sb, ctx, section, "TOC \\h \\z \\c \"Table\"",
                    s.orderedElements().filter { it.type == ElementType.TABLE }.map { ThesisStructure.tableCaption(it, ctx.numbering, ctx.language) })
                else -> {
                    val level = ThesisStructure.headingLevel(s, section)
                    para(sb, ctx, ThesisStructure.displayTitle(section, ctx.numbering, ctx.language), "Heading$level")
                    for (e in s.elementsOf(section.id)) element(sb, ctx, e, level)
                }
            }
        }
        return sb.toString()
    }

    private fun tocCache(ctx: Ctx): List<String> = ctx.snapshot.outline(includeDisabled = false)
        .filter { (sec, _) -> !sec.kind.generated }
        .map { (sec, depth) -> "  ".repeat(depth) + ThesisStructure.displayTitle(sec, ctx.numbering, ctx.language) }

    private fun index(sb: StringBuilder, ctx: Ctx, section: ThesisSection, instr: String, cached: List<String>) {
        para(sb, ctx, ThesisStructure.displayTitle(section, ctx.numbering, ctx.language), "TOCHeading")
        // The cached lines are shown until the word processor updates the field (with page numbers).
        sb.append("<w:p>").append(pPr("TOC1", ctx, "")).append(
            "<w:r><w:fldChar w:fldCharType=\"begin\" w:dirty=\"true\"/></w:r><w:r><w:instrText xml:space=\"preserve\"> $instr </w:instrText></w:r><w:r><w:fldChar w:fldCharType=\"separate\"/></w:r>"
        )
        if (cached.isEmpty()) sb.append(run("", ctx))
        cached.forEachIndexed { i, line ->
            if (i > 0) sb.append("</w:p><w:p>").append(pPr("TOC1", ctx, line))
            sb.append(run(line.trim(), ctx))
        }
        sb.append("<w:r><w:fldChar w:fldCharType=\"end\"/></w:r></w:p>")
    }

    private fun cover(sb: StringBuilder, ctx: Ctx) {
        val m = ctx.snapshot.meta
        val lang = ctx.language
        val center = "<w:jc w:val=\"center\"/>"
        m.logoAssetId?.let { id -> ctx.assets[id]?.let { image(sb, ctx, it, 30, "center") } ?: ctx.warnings.add("logo_missing") }
        listOf(m.university, m.faculty, m.department).filter { it.isNotBlank() }.forEach { para(sb, ctx, it, "CoverLine", center, "<w:b/>") }
        sb.append("<w:p/><w:p/>")
        para(sb, ctx, ThesisLabels.get(lang, m.level.name), "CoverLine", center)
        if (m.specialization.isNotBlank()) para(sb, ctx, "${ThesisLabels.get(lang, "specialization")}: ${m.specialization}", "CoverLine", center)
        sb.append("<w:p/>")
        para(sb, ctx, m.title, "Title", center)
        if (m.subtitle.isNotBlank()) para(sb, ctx, m.subtitle, "CoverLine", center, "<w:i/>")
        sb.append("<w:p/>")
        if (m.studentName.isNotBlank()) para(sb, ctx, "${ThesisLabels.get(lang, "student")}: ${m.studentName}", "CoverLine", center)
        if (m.studentId.isNotBlank()) para(sb, ctx, "${ThesisLabels.get(lang, "studentId")}: ${m.studentId}", "CoverLine", center)
        if (m.supervisor.isNotBlank()) para(sb, ctx, "${ThesisLabels.get(lang, "supervisor")}: ${m.supervisor}", "CoverLine", center)
        if (m.coSupervisor.isNotBlank()) para(sb, ctx, "${ThesisLabels.get(lang, "coSupervisor")}: ${m.coSupervisor}", "CoverLine", center)
        val jury = m.juryMembers.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (jury.isNotEmpty()) {
            sb.append("<w:p/>")
            para(sb, ctx, ThesisLabels.get(lang, "jury"), "CoverLine", center, "<w:b/>")
            jury.forEach { para(sb, ctx, it, "CoverLine", center) }
        }
        m.extraMetadata.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { para(sb, ctx, it, "CoverLine", center) }
        if (m.academicYear.isNotBlank()) {
            sb.append("<w:p/>")
            para(sb, ctx, "${ThesisLabels.get(lang, "year")}: ${m.academicYear}", "CoverLine", center)
        }
        if (m.submissionDate.isNotBlank()) para(sb, ctx, "${ThesisLabels.get(lang, "submitted")}: ${m.submissionDate}", "CoverLine", center)
    }

    private fun element(sb: StringBuilder, ctx: Ctx, e: ThesisElement, sectionLevel: Int) {
        val (text, missing) = ThesisStructure.resolveCrossReferences(e.text, ctx.numbering, ctx.language)
        if (missing.isNotEmpty()) ctx.warnings.add("crossref_missing:${missing.joinToString(",")}")
        when (e.type) {
            ElementType.HEADING -> textPara(sb, ctx, e, text, "Heading${(sectionLevel + e.data.headingLevel.coerceAtLeast(1)).coerceAtMost(4)}")
            ElementType.PARAGRAPH -> textPara(sb, ctx, e, text, null)
            ElementType.REFERENCE -> textPara(sb, ctx, e, text, "Bibliography")
            ElementType.LIST_ITEM -> {
                val marker = if (e.data.ordered) e.data.marker.ifBlank { "•" } else "•"
                val indent = 360 + e.data.headingLevel.coerceIn(0, 3) * 360
                textPara(sb, ctx, e, "$marker\t$text", "ListParagraph", "<w:ind w:${if (ctx.rtl) "right" else "left"}=\"${indent + 360}\" w:hanging=\"360\"/>")
            }
            ElementType.IMAGE -> {
                if (e.data.pageBreakBefore) pageBreak(sb)
                val asset = e.data.assetId?.let { ctx.assets[it] }
                if (asset == null || !File(asset.path).isFile) {
                    ctx.warnings.add("image_missing:${e.id}")
                } else {
                    image(sb, ctx, asset, e.data.widthPercent, e.data.align)
                }
                caption(sb, ctx, "Figure", "figure", ctx.numbering.figures[e.id] ?: 0, e.data.caption, e.data.source)
            }
            ElementType.TABLE -> {
                if (e.data.pageBreakBefore) pageBreak(sb)
                // Academic convention: table captions above the table.
                caption(sb, ctx, "Table", "table", ctx.numbering.tables[e.id] ?: 0, e.data.caption, "")
                table(sb, ctx, e)
                sb.append("<w:p/>")
            }
        }
    }

    private fun caption(sb: StringBuilder, ctx: Ctx, seq: String, labelKey: String, number: Int, caption: String, source: String) {
        val label = ThesisLabels.get(ctx.language, labelKey)
        val captionText = caption.ifBlank { "" }
        sb.append("<w:p>").append(pPr("Caption", ctx, label + captionText, "<w:jc w:val=\"center\"/>"))
            .append(run("$label ", ctx))
            .append(field("SEQ $seq \\* ARABIC", number.toString(), ctx))
        if (captionText.isNotEmpty()) sb.append(run(": $captionText", ctx))
        sb.append("</w:p>")
        if (source.isNotBlank()) para(sb, ctx, source, "Caption", "<w:jc w:val=\"center\"/>")
    }

    private fun image(sb: StringBuilder, ctx: Ctx, asset: ThesisAsset, widthPercent: Int, align: String) {
        val file = File(asset.path)
        val ext = when {
            asset.mimeType.contains("png") || file.extension.equals("png", true) -> "png"
            asset.mimeType.contains("gif") -> "gif"
            else -> "jpeg"
        }
        val index = ctx.media.size + 1
        val rid = "rIdThesisImg$index"
        ctx.media.add("thesisimg$index.$ext" to file)
        val cx = ctx.textWidthTwips * widthPercent.coerceIn(10, 100) / 100 * EMU_PER_TWIP
        val ratio = if (asset.width > 0 && asset.height > 0) asset.height.toDouble() / asset.width else 0.75
        val cy = (cx * ratio).toLong().coerceAtMost(9_000_000L * 2)
        val jc = when (align) { "start" -> if (ctx.rtl) "right" else "left"; "end" -> if (ctx.rtl) "left" else "right"; else -> "center" }
        val id = ctx.drawingId++
        sb.append("<w:p><w:pPr><w:keepNext/><w:jc w:val=\"$jc\"/></w:pPr><w:r><w:drawing>")
            .append("<wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\"><wp:extent cx=\"$cx\" cy=\"$cy\"/><wp:docPr id=\"$id\" name=\"Picture $id\"/>")
            .append("<wp:cNvGraphicFramePr><a:graphicFrameLocks xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" noChangeAspect=\"1\"/></wp:cNvGraphicFramePr>")
            .append("<a:graphic xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\"><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">")
            .append("<pic:pic xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><pic:nvPicPr><pic:cNvPr id=\"$id\" name=\"thesisimg$index.$ext\"/><pic:cNvPicPr/></pic:nvPicPr>")
            .append("<pic:blipFill><a:blip r:embed=\"$rid\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>")
            .append("<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr></pic:pic>")
            .append("</a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>")
    }

    private fun table(sb: StringBuilder, ctx: Ctx, e: ThesisElement) {
        val header = e.data.tableHeader
        if (header.isEmpty()) return
        sb.append("<w:tbl><w:tblPr><w:tblStyle w:val=\"ThesisTable\"/><w:tblW w:w=\"5000\" w:type=\"pct\"/>")
        if (ctx.rtl) sb.append("<w:bidiVisual/>")
        sb.append("<w:tblLook w:val=\"04A0\" w:firstRow=\"1\" w:lastRow=\"0\" w:firstColumn=\"0\" w:lastColumn=\"0\" w:noHBand=\"1\" w:noVBand=\"1\"/></w:tblPr><w:tblGrid>")
        repeat(header.size) { sb.append("<w:gridCol/>") }
        sb.append("</w:tblGrid>")
        fun row(cells: List<String>, isHeader: Boolean) {
            sb.append("<w:tr>")
            if (isHeader) sb.append("<w:trPr><w:tblHeader/></w:trPr>")
            for (c in (cells + List((header.size - cells.size).coerceAtLeast(0)) { "" }).take(header.size)) {
                sb.append("<w:tc><w:tcPr><w:tcW w:w=\"0\" w:type=\"auto\"/>")
                if (isHeader) sb.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"HEADERFILL\"/>")
                sb.append("</w:tcPr><w:p>").append(pPr("TableText", ctx, c)).append(run(c, ctx, if (isHeader) "<w:b/>" else "")).append("</w:p></w:tc>")
            }
            sb.append("</w:tr>")
        }
        row(header, true)
        e.data.tableRows.forEach { row(it, false) }
        sb.append("</w:tbl>")
    }

    // ------------------------------------------------------------------ styles

    private fun stylesXml(p: TemplateProfile, rtl: Boolean): String {
        val font = esc(p.bodyFont)
        val hFont = esc(p.headingFont)
        val body = p.baseFontSizePt.coerceIn(8, 20) * 2
        val text = hex(p.textColorHex)
        val heading = hex(p.headingColorHex.ifEmpty { p.primaryColorHex }, "1F3A5F")
        val line = if (p.lineSpacing > 0f) (p.lineSpacing * 240).toInt().coerceIn(240, 720) else 360
        val after = if (p.paragraphSpacingPt >= 0) p.paragraphSpacingPt * 20 else 120
        val jc = when (p.bodyAlignment) { "center" -> "center"; "end" -> if (rtl) "left" else "right"; "start" -> if (rtl) "right" else "left"; else -> "both" }
        val sizes = listOf(p.heading1SizePt, p.heading2SizePt, (p.heading2SizePt + p.baseFontSizePt) / 2, p.baseFontSizePt).map { it.coerceIn(8, 36) * 2 }
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:styles xmlns:w="$W_NS">""" +
            """<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="$font" w:hAnsi="$font" w:cs="$font" w:eastAsia="$font"/><w:color w:val="$text"/><w:sz w:val="$body"/><w:szCs w:val="$body"/></w:rPr></w:rPrDefault>""" +
            """<w:pPrDefault><w:pPr><w:spacing w:after="$after" w:line="$line" w:lineRule="auto"/></w:pPr></w:pPrDefault></w:docDefaults>""" +
            """<w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/><w:qFormat/><w:pPr><w:jc w:val="$jc"/></w:pPr></w:style>""" +
            """<w:style w:type="paragraph" w:styleId="Title"><w:name w:val="Title"/><w:basedOn w:val="Normal"/><w:qFormat/><w:pPr><w:spacing w:before="240" w:after="240"/><w:jc w:val="center"/></w:pPr><w:rPr><w:rFonts w:ascii="$hFont" w:hAnsi="$hFont" w:cs="$hFont"/><w:b/><w:bCs/><w:color w:val="$heading"/><w:sz w:val="${(sizes[0] + 8)}"/><w:szCs w:val="${(sizes[0] + 8)}"/></w:rPr></w:style>""" +
            (1..4).joinToString("") { lvl ->
                """<w:style w:type="paragraph" w:styleId="Heading$lvl"><w:name w:val="heading $lvl"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:qFormat/><w:pPr><w:keepNext/><w:keepLines/><w:spacing w:before="${if (lvl == 1) 360 else 240}" w:after="120"/><w:jc w:val="${if (rtl) "right" else "left"}"/><w:outlineLvl w:val="${lvl - 1}"/></w:pPr><w:rPr><w:rFonts w:ascii="$hFont" w:hAnsi="$hFont" w:cs="$hFont"/><w:b/><w:bCs/><w:color w:val="$heading"/><w:sz w:val="${sizes[lvl - 1]}"/><w:szCs w:val="${sizes[lvl - 1]}"/></w:rPr></w:style>"""
            } + extraStyles(p, rtl) + "</w:styles>"
    }

    /** Styles the thesis needs; added to a Word template only when it does not define them. */
    private fun extraStyles(p: TemplateProfile, rtl: Boolean): String {
        val border = hex(p.tableBorderHex, "808080")
        val small = (p.baseFontSizePt.coerceIn(8, 20) - 1) * 2
        val tocTabPos = 9000
        return """<w:style w:type="paragraph" w:styleId="Caption"><w:name w:val="caption"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:qFormat/><w:pPr><w:spacing w:before="60" w:after="200"/><w:jc w:val="center"/></w:pPr><w:rPr><w:i/><w:iCs/><w:sz w:val="$small"/><w:szCs w:val="$small"/></w:rPr></w:style>""" +
            """<w:style w:type="paragraph" w:styleId="CoverLine"><w:name w:val="Cover Line"/><w:basedOn w:val="Normal"/><w:pPr><w:spacing w:after="80"/><w:jc w:val="center"/></w:pPr></w:style>""" +
            """<w:style w:type="paragraph" w:styleId="TOCHeading"><w:name w:val="TOC Heading"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:pPr><w:spacing w:before="240" w:after="240"/><w:jc w:val="center"/></w:pPr><w:rPr><w:b/><w:bCs/><w:sz w:val="32"/><w:szCs w:val="32"/></w:rPr></w:style>""" +
            (1..3).joinToString("") { lvl ->
                """<w:style w:type="paragraph" w:styleId="TOC$lvl"><w:name w:val="toc $lvl"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:pPr><w:tabs><w:tab w:val="right" w:leader="dot" w:pos="$tocTabPos"/></w:tabs><w:spacing w:after="60"/><w:ind w:${if (rtl) "right" else "left"}="${(lvl - 1) * 240}"/><w:jc w:val="${if (rtl) "right" else "left"}"/></w:pPr></w:style>"""
            } +
            """<w:style w:type="paragraph" w:styleId="ListParagraph"><w:name w:val="List Paragraph"/><w:basedOn w:val="Normal"/><w:pPr><w:spacing w:after="60"/><w:jc w:val="${if (rtl) "right" else "left"}"/></w:pPr></w:style>""" +
            """<w:style w:type="paragraph" w:styleId="Bibliography"><w:name w:val="Bibliography"/><w:basedOn w:val="Normal"/><w:pPr><w:ind w:${if (rtl) "right" else "left"}="720" w:hanging="720"/><w:jc w:val="${if (rtl) "right" else "left"}"/></w:pPr></w:style>""" +
            """<w:style w:type="paragraph" w:styleId="TableText"><w:name w:val="Table Text"/><w:basedOn w:val="Normal"/><w:pPr><w:spacing w:before="40" w:after="40" w:line="240" w:lineRule="auto"/><w:jc w:val="${if (rtl) "right" else "left"}"/></w:pPr></w:style>""" +
            """<w:style w:type="table" w:styleId="ThesisTable"><w:name w:val="Thesis Table"/><w:tblPr><w:tblBorders>""" +
            listOf("top", "left", "bottom", "right", "insideH", "insideV").joinToString("") { """<w:$it w:val="single" w:sz="4" w:space="0" w:color="$border"/>""" } +
            """</w:tblBorders><w:tblCellMar><w:left w:w="100" w:type="dxa"/><w:right w:w="100" w:type="dxa"/></w:tblCellMar></w:tblPr></w:style>""" +
            """<w:style w:type="paragraph" w:styleId="Footer"><w:name w:val="footer"/><w:basedOn w:val="Normal"/><w:pPr><w:spacing w:after="0"/><w:jc w:val="center"/></w:pPr></w:style>""" +
            """<w:style w:type="paragraph" w:styleId="Header"><w:name w:val="header"/><w:basedOn w:val="Normal"/><w:pPr><w:spacing w:after="0"/></w:pPr></w:style>"""
    }

    private fun footerXml(p: TemplateProfile, ctx: Ctx): String {
        val text = p.footerText
        val sb = StringBuilder("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:ftr xmlns:w="$W_NS">""")
        if (text.isNotBlank()) para(sb, ctx, text, "Footer")
        sb.append("<w:p>").append(pPr("Footer", ctx, "", "<w:jc w:val=\"center\"/>")).append(field("PAGE", "1", ctx)).append("</w:p>")
        sb.append("</w:ftr>")
        return sb.toString()
    }

    private fun headerXml(p: TemplateProfile, ctx: Ctx): String? {
        if (p.headerText.isBlank()) return null
        val sb = StringBuilder("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:hdr xmlns:w="$W_NS">""")
        para(sb, ctx, p.headerText, "Header")
        return sb.append("</w:hdr>").toString()
    }

    // ------------------------------------------------------------------ packages

    private fun contentTypes(extraOverrides: String, media: List<Pair<String, File>>): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
        append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/>""")
        append(mediaDefaults(media, emptySet()))
        append(extraOverrides)
        append("</Types>")
    }

    private fun mediaDefaults(media: List<Pair<String, File>>, existing: Set<String>): String =
        media.map { it.first.substringAfterLast('.') }.distinct().filter { it !in existing }.joinToString("") { ext ->
            """<Default Extension="$ext" ContentType="image/$ext"/>"""
        }

    private fun mediaRels(media: List<Pair<String, File>>): String = media.withIndex().joinToString("") { (i, m) ->
        """<Relationship Id="rIdThesisImg${i + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/${m.first}"/>"""
    }

    private fun writeStandalone(ctx: Ctx, body: String, p: TemplateProfile, output: File) {
        val pageW = (if (p.pageWidthPt > 0) p.pageWidthPt else 595) * 20
        val pageH = (if (p.pageHeightPt > 0) p.pageHeightPt else 842) * 20
        val header = headerXml(p, ctx)
        val footer = footerXml(p, ctx)
        val sectPr = "<w:sectPr>" + (if (header != null) "<w:headerReference w:type=\"default\" r:id=\"rIdThesisHdr\"/>" else "") +
            "<w:footerReference w:type=\"default\" r:id=\"rIdThesisFtr\"/>" +
            "<w:pgSz w:w=\"$pageW\" w:h=\"$pageH\"${if (pageW > pageH) " w:orient=\"landscape\"" else ""}/>" +
            "<w:pgMar w:top=\"${p.marginTopPt * 20}\" w:right=\"${p.marginRightPt * 20}\" w:bottom=\"${p.marginBottomPt * 20}\" w:left=\"${p.marginLeftPt * 20}\" w:header=\"708\" w:footer=\"708\" w:gutter=\"0\"/>" +
            (if (ctx.rtl) "<w:bidi/>" else "") + "<w:titlePg/></w:sectPr>"
        val document = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:document xmlns:w="$W_NS" xmlns:r="$R_NS" xmlns:wp="$WP_NS"><w:body>""" +
            body.replace("HEADERFILL", hex(p.tableHeaderBgHex, "D9E2F3")) + sectPr + "</w:body></w:document>"
        val overrides = """<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>""" +
            """<Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>""" +
            """<Override PartName="/word/settings.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml"/>""" +
            """<Override PartName="/word/footer1.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.footer+xml"/>""" +
            (if (header != null) """<Override PartName="/word/header1.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.header+xml"/>""" else "")
        val rels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
            """<Relationship Id="rIdThesisStyles" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""" +
            """<Relationship Id="rIdThesisSettings" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/settings" Target="settings.xml"/>""" +
            """<Relationship Id="rIdThesisFtr" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/footer" Target="footer1.xml"/>""" +
            (if (header != null) """<Relationship Id="rIdThesisHdr" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/header" Target="header1.xml"/>""" else "") +
            mediaRels(ctx.media) + "</Relationships>"
        val parts = linkedMapOf(
            "[Content_Types].xml" to contentTypes(overrides, ctx.media),
            "_rels/.rels" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>""",
            "word/_rels/document.xml.rels" to rels,
            "word/document.xml" to document,
            "word/styles.xml" to stylesXml(p, ctx.rtl),
            // Ask Word to refresh the TOC / list fields and page numbers when the file is opened.
            "word/settings.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:settings xmlns:w="$W_NS"><w:updateFields w:val="true"/></w:settings>""",
            "word/footer1.xml" to footer
        )
        header?.let { parts["word/header1.xml"] = it }
        writeZip(output, parts.mapValues { it.value.toByteArray(Charsets.UTF_8) }, ctx.media)
    }

    private fun writeZip(output: File, parts: Map<String, ByteArray>, media: List<Pair<String, File>>) {
        ZipOutputStream(FileOutputStream(output)).use { zip ->
            for ((name, bytes) in parts) {
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
            for ((name, file) in media) {
                zip.putNextEntry(ZipEntry("word/media/$name"))
                file.inputStream().use { it.copyTo(zip) } // the original image is only read
                zip.closeEntry()
            }
        }
    }

    /**
     * Builds the thesis inside a copy of a Word template: the template's styles, theme, fonts,
     * numbering, headers, footers and section properties are kept; only the body is replaced and
     * the styles the thesis needs are added when the template lacks them.
     */
    private fun writeOnTemplate(
        snapshot: ThesisSnapshot,
        numbering: ThesisNumbering,
        assets: List<ThesisAsset>,
        template: ThesisTemplate.DocxFile,
        output: File
    ): ThesisRenderReport {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipFile(template.file).use { zip ->
            for (e in zip.entries()) {
                if (e.isDirectory) continue
                if (e.size > 64L * 1024 * 1024) throw IllegalStateException("Template part too large")
                entries[e.name] = zip.getInputStream(e).use { input -> ByteArrayOutputStream().also { input.copyTo(it) }.toByteArray() }
            }
        }
        fun text(name: String) = entries[name]?.toString(Charsets.UTF_8)
        val documentXml = text("word/document.xml") ?: throw IllegalStateException("The template is not a Word document")
        val bodyOpen = Regex("""<w:body(\s[^>]*)?>""").find(documentXml) ?: throw IllegalStateException("The template has no body")
        val bodyClose = documentXml.lastIndexOf("</w:body>")
        val inner = documentXml.substring(bodyOpen.range.last + 1, bodyClose)
        val sectStart = inner.lastIndexOf("<w:sectPr")
        var sectPr = if (sectStart >= 0 && inner.indexOf("</w:p>", sectStart) < 0) inner.substring(sectStart) else ""

        val pgW = Regex("""<w:pgSz[^>]*w:w="(\d+)"""").find(sectPr)?.groupValues?.get(1)?.toLongOrNull() ?: 12240L
        val left = Regex("""<w:pgMar[^>]*w:left="(\d+)"""").find(sectPr)?.groupValues?.get(1)?.toLongOrNull() ?: 1440L
        val right = Regex("""<w:pgMar[^>]*w:right="(\d+)"""").find(sectPr)?.groupValues?.get(1)?.toLongOrNull() ?: 1440L
        val ctx = Ctx(snapshot, numbering, assets.associateBy { it.id }, snapshot.meta.language, snapshot.meta.rtl, (pgW - left - right).coerceAtLeast(2000))
        val body = body(ctx).replace("HEADERFILL", hex(template.profile.tableHeaderBgHex, "D9E2F3"))

        var rels = text("word/_rels/document.xml.rels")
            ?: """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"></Relationships>"""
        var contentTypes = text("[Content_Types].xml") ?: throw IllegalStateException("The template package is incomplete")
        contentTypes = contentTypes.replace(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.template.main+xml",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"
        )
        // Page numbers: the template's own footer is kept; a numbered footer is added only when it has none.
        if (sectPr.isEmpty()) sectPr = "<w:sectPr></w:sectPr>"
        if (!sectPr.contains("footerReference")) {
            entries["word/footerThesis.xml"] = footerXml(template.profile.copy(footerText = ""), ctx).toByteArray(Charsets.UTF_8)
            rels = rels.replace("</Relationships>", """<Relationship Id="rIdThesisFtr" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/footer" Target="footerThesis.xml"/></Relationships>""")
            contentTypes = contentTypes.replace("</Types>", """<Override PartName="/word/footerThesis.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.footer+xml"/></Types>""")
            sectPr = sectPr.replaceFirst(Regex("""<w:sectPr(\s[^>]*)?>"""), "$0<w:footerReference w:type=\"default\" r:id=\"rIdThesisFtr\"/>")
            ctx.warnings.add("template_footer_added")
        }
        if (snapshot.meta.rtl && !sectPr.contains("<w:bidi/>")) sectPr = sectPr.replace("</w:sectPr>", "<w:bidi/></w:sectPr>")

        // Root element: make sure the namespaces used by the thesis body are declared.
        var root = documentXml.substring(0, bodyOpen.range.first)
        if (!root.contains("xmlns:r=")) root = root.replaceFirst("<w:document ", "<w:document xmlns:r=\"$R_NS\" ")
        if (!root.contains("xmlns:wp=")) root = root.replaceFirst("<w:document ", "<w:document xmlns:wp=\"$WP_NS\" ")
        entries["word/document.xml"] = (root + bodyOpen.value + body + sectPr + documentXml.substring(bodyClose)).toByteArray(Charsets.UTF_8)

        // Styles: keep the template's, add the missing ones the thesis uses.
        val styles = text("word/styles.xml")
        if (styles == null) {
            entries["word/styles.xml"] = stylesXml(template.profile, ctx.rtl).toByteArray(Charsets.UTF_8)
            rels = rels.replace("</Relationships>", """<Relationship Id="rIdThesisStyles" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>""")
            contentTypes = contentTypes.replace("</Types>", """<Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/></Types>""")
        } else {
            val ids = Regex("""w:styleId="([^"]+)"""").findAll(styles).map { it.groupValues[1] }.toSet()
            val generated = stylesXml(template.profile, ctx.rtl)
            val missing = Regex("""<w:style [^>]*w:styleId="([^"]+)"[^>]*>[\s\S]*?</w:style>""").findAll(generated)
                .filter { it.groupValues[1] !in ids && it.groupValues[1] != "Normal" }.joinToString("") { it.value }
            if (missing.isNotEmpty()) entries["word/styles.xml"] = styles.replace("</w:styles>", "$missing</w:styles>").toByteArray(Charsets.UTF_8)
        }

        // Settings: ask Word to update the index fields on open.
        val settingsName = Regex("""Target="([^"]*settings\.xml)"""").find(rels)?.groupValues?.get(1)?.let { "word/" + it.removePrefix("/word/").removePrefix("./") }
        val settings = settingsName?.let { text(it) }
        if (settings != null && settingsName != null) {
            if (!settings.contains("updateFields")) {
                entries[settingsName] = settings.replaceFirst(Regex("""<w:settings[^>]*>"""), "$0<w:updateFields w:val=\"true\"/>").toByteArray(Charsets.UTF_8)
            }
        } else {
            entries["word/settingsThesis.xml"] = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:settings xmlns:w="$W_NS"><w:updateFields w:val="true"/></w:settings>""".toByteArray(Charsets.UTF_8)
            rels = rels.replace("</Relationships>", """<Relationship Id="rIdThesisSettings" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/settings" Target="settingsThesis.xml"/></Relationships>""")
            contentTypes = contentTypes.replace("</Types>", """<Override PartName="/word/settingsThesis.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml"/></Types>""")
        }

        // Images.
        rels = rels.replace("</Relationships>", mediaRels(ctx.media) + "</Relationships>")
        val existingDefaults = Regex("""<Default Extension="([^"]+)"""").findAll(contentTypes).map { it.groupValues[1].lowercase() }.toSet()
        contentTypes = contentTypes.replaceFirst("</Types>", mediaDefaults(ctx.media, existingDefaults) + "</Types>")
        entries["word/_rels/document.xml.rels"] = rels.toByteArray(Charsets.UTF_8)
        entries["[Content_Types].xml"] = contentTypes.toByteArray(Charsets.UTF_8)
        // Thesis images use their own names ("thesisimgN"), so the template's images are kept.
        writeZip(output, entries.filterKeys { k -> ctx.media.none { "word/media/${it.first}" == k } }, ctx.media)
        return ThesisRenderReport(ctx.warnings, numbering.figures.size, numbering.tables.size)
    }
}
