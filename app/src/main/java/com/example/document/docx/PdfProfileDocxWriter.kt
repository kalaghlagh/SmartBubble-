package com.example.document.docx

import com.example.document.model.WordDocumentModel
import com.example.document.model.WordTable
import com.example.template.domain.TemplateProfile
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes a Word document whose formatting reproduces a PDF template's measured style
 * ([TemplateProfile.isPdfLayout]): page size and orientation, the four margins, body and heading
 * fonts, sizes and colors, alignment, line and paragraph spacing, list indent, table borders and
 * header-row fill, page background, and a real Word header/footer holding only the text the PDF
 * template itself had there (plus a page number field where the template shows page numbers).
 * Colored shapes of the PDF are not reproduced in Word.
 */
internal object PdfProfileDocxWriter {

    fun write(model: WordDocumentModel, output: File, p: TemplateProfile) {
        val pageW = (if (p.pageWidthPt > 0) p.pageWidthPt else 612) * 20
        val pageH = (if (p.pageHeightPt > 0) p.pageHeightPt else 792) * 20
        val headerPart = headerXml(p, top = true)
        val footerPart = headerXml(p, top = false)
        val background = p.backgroundColorHex.takeIf { hex(it) != "FFFFFF" }

        val entries = linkedMapOf<String, String>()
        entries["[Content_Types].xml"] = buildString {
            append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
            append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/>""")
            append("""<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>""")
            append("""<Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>""")
            append("""<Override PartName="/word/settings.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml"/>""")
            if (headerPart != null) append("""<Override PartName="/word/header1.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.header+xml"/>""")
            if (footerPart != null) append("""<Override PartName="/word/footer1.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.footer+xml"/>""")
            append("</Types>")
        }
        entries["_rels/.rels"] = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>"""
        entries["word/_rels/document.xml.rels"] = buildString {
            append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
            append("""<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""")
            append("""<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/settings" Target="settings.xml"/>""")
            if (headerPart != null) append("""<Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/header" Target="header1.xml"/>""")
            if (footerPart != null) append("""<Relationship Id="rId4" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/footer" Target="footer1.xml"/>""")
            append("</Relationships>")
        }
        entries["word/settings.xml"] = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:settings xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">""" +
            (if (background != null) "<w:displayBackgroundShape/>" else "") + "</w:settings>"
        entries["word/styles.xml"] = stylesXml(p)
        headerPart?.let { entries["word/header1.xml"] = it }
        footerPart?.let { entries["word/footer1.xml"] = it }

        val sectPr = buildString {
            append("<w:sectPr>")
            if (headerPart != null) append("""<w:headerReference w:type="default" r:id="rId3"/>""")
            if (footerPart != null) append("""<w:footerReference w:type="default" r:id="rId4"/>""")
            append("""<w:pgSz w:w="$pageW" w:h="$pageH"${if (pageW > pageH) " w:orient=\"landscape\"" else ""}/>""")
            val headerDist = ((p.headerOffsetPt - p.headerFontSizePt).takeIf { p.headerOffsetPt > 0 } ?: 36).coerceAtLeast(12) * 20
            val footerDist = ((p.footerOffsetPt - 2).takeIf { p.footerOffsetPt > 0 } ?: 36).coerceAtLeast(12) * 20
            append("""<w:pgMar w:top="${p.marginTopPt * 20}" w:right="${p.marginRightPt * 20}" w:bottom="${p.marginBottomPt * 20}" w:left="${p.marginLeftPt * 20}" w:header="$headerDist" w:footer="$footerDist" w:gutter="0"/>""")
            if (p.rtl) append("<w:bidi/>")
            append("</w:sectPr>")
        }
        entries["word/document.xml"] = buildString {
            append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">""")
            if (background != null) append("""<w:background w:color="${hex(background)}"/>""")
            append("<w:body>")
            append(body(model, p))
            append(sectPr)
            append("</w:body></w:document>")
        }

        ZipOutputStream(FileOutputStream(output)).use { zip ->
            for ((name, xml) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(xml.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }

    private fun hex(value: String): String {
        val h = value.removePrefix("#").uppercase(Locale.ROOT)
        return if (Regex("[0-9A-F]{6}").matches(h)) h else "000000"
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun isArabic(text: String) = text.any { it in '؀'..'ۿ' || it in 'ݐ'..'ݿ' }

    /** Word alignment for a logical alignment in a paragraph of the given direction. */
    private fun jc(alignment: String, rtl: Boolean): String = when (alignment) {
        "center" -> "center"
        "justify" -> "both"
        "end" -> if (rtl) "left" else "right"
        else -> if (rtl) "right" else "left"
    }

    private fun fonts(name: String) = esc(name).let { """<w:rFonts w:ascii="$it" w:hAnsi="$it" w:cs="$it" w:eastAsia="$it"/>""" }

    private fun stylesXml(p: TemplateProfile): String {
        val body = p.baseFontSizePt.coerceIn(6, 72) * 2
        val h1 = p.heading1SizePt.coerceIn(6, 96) * 2
        val h2 = p.heading2SizePt.coerceIn(6, 96) * 2
        val text = hex(p.textColorHex)
        val heading = hex(p.headingColorHex.ifEmpty { p.primaryColorHex })
        val bold = if (p.headingBold) "<w:b/><w:bCs/>" else ""
        val after = if (p.paragraphSpacingPt >= 0) p.paragraphSpacingPt * 20 else 160
        val line = if (p.lineSpacing > 0f) """ w:line="${(p.lineSpacing * 240).toInt().coerceIn(120, 720)}" w:lineRule="auto"""" else ""
        val bodyJc = jc(p.bodyAlignment, p.rtl)
        val headJc = jc(p.headingAlignment, p.rtl)
        val border = hex(p.tableBorderHex)
        val listIndent = (if (p.listIndentPt > 0) p.listIndentPt else 18) * 20 + 240
        fun headingStyle(id: String, name: String, size: Int, outline: Int) =
            """<w:style w:type="paragraph" w:styleId="$id"><w:name w:val="$name"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:qFormat/>""" +
                """<w:pPr><w:keepNext/><w:spacing w:before="${size * 6}" w:after="${size * 3}" w:line="240" w:lineRule="auto"/><w:jc w:val="$headJc"/><w:outlineLvl w:val="$outline"/></w:pPr>""" +
                """<w:rPr>${fonts(p.headingFont)}$bold<w:color w:val="$heading"/><w:sz w:val="$size"/><w:szCs w:val="$size"/></w:rPr></w:style>"""
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">""" +
            """<w:docDefaults><w:rPrDefault><w:rPr>${fonts(p.bodyFont)}<w:color w:val="$text"/><w:sz w:val="$body"/><w:szCs w:val="$body"/></w:rPr></w:rPrDefault>""" +
            """<w:pPrDefault><w:pPr><w:spacing w:after="$after"$line/></w:pPr></w:pPrDefault></w:docDefaults>""" +
            """<w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/><w:qFormat/><w:pPr><w:jc w:val="$bodyJc"/></w:pPr></w:style>""" +
            headingStyle("Title", "Title", h1, 0) +
            headingStyle("Heading1", "heading 1", h2, 0) +
            headingStyle("Heading2", "heading 2", ((h2 + body) / 2).coerceAtLeast(body), 1) +
            """<w:style w:type="paragraph" w:styleId="ListBullet"><w:name w:val="List Bullet"/><w:basedOn w:val="Normal"/><w:pPr><w:ind w:${if (p.rtl) "right" else "left"}="$listIndent" w:hanging="240"/><w:jc w:val="${jc("start", p.rtl)}"/></w:pPr></w:style>""" +
            """<w:style w:type="paragraph" w:styleId="Header"><w:name w:val="header"/><w:basedOn w:val="Normal"/><w:pPr><w:spacing w:after="0"/></w:pPr><w:rPr><w:sz w:val="${(p.headerFontSizePt.takeIf { it > 0 } ?: 9) * 2}"/></w:rPr></w:style>""" +
            """<w:style w:type="paragraph" w:styleId="Footer"><w:name w:val="footer"/><w:basedOn w:val="Normal"/><w:pPr><w:spacing w:after="0"/></w:pPr><w:rPr><w:sz w:val="${(p.footerFontSizePt.takeIf { it > 0 } ?: 9) * 2}"/></w:rPr></w:style>""" +
            """<w:style w:type="table" w:styleId="TableGrid"><w:name w:val="Table Grid"/><w:tblPr><w:tblBorders>""" +
            listOf("top", "left", "bottom", "right", "insideH", "insideV").joinToString("") { """<w:$it w:val="single" w:sz="4" w:space="0" w:color="$border"/>""" } +
            """</w:tblBorders><w:tblCellMar><w:left w:w="100" w:type="dxa"/><w:right w:w="100" w:type="dxa"/></w:tblCellMar></w:tblPr></w:style>""" +
            "</w:styles>"
    }

    /** Header or footer part with the template's own text and/or a page number; null when it had none. */
    private fun headerXml(p: TemplateProfile, top: Boolean): String? {
        val text = if (top) p.headerText else p.footerText
        val zone = if (top) "header-" else "footer-"
        val pageNumber = p.pageNumberPosition.startsWith(zone)
        if (text.isBlank() && !pageNumber) return null
        val tag = if (top) "hdr" else "ftr"
        val style = if (top) "Header" else "Footer"
        val color = hex(p.textColorHex)
        fun para(content: String, alignment: String, rtl: Boolean) =
            """<w:p><w:pPr><w:pStyle w:val="$style"/>${if (rtl) "<w:bidi/>" else ""}<w:jc w:val="${jc(alignment, rtl)}"/></w:pPr>$content</w:p>"""
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:$tag xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">""")
        if (text.isNotBlank()) {
            val rtl = isArabic(text)
            sb.append(para("""<w:r><w:rPr><w:color w:val="$color"/>${if (rtl) "<w:rtl/>" else ""}</w:rPr><w:t xml:space="preserve">${esc(text)}</w:t></w:r>""", if (top) p.headerAlignment else p.footerAlignment, rtl))
        }
        if (pageNumber) {
            sb.append(para("""<w:fldSimple w:instr=" PAGE "><w:r><w:rPr><w:color w:val="$color"/></w:rPr><w:t>1</w:t></w:r></w:fldSimple>""", p.pageNumberPosition.removePrefix(zone), p.rtl))
        }
        sb.append("</w:$tag>")
        return sb.toString()
    }

    private fun body(model: WordDocumentModel, p: TemplateProfile): String {
        val sb = StringBuilder()
        fun para(text: String, style: String?, extraPPr: String = "") {
            val rtl = isArabic(text)
            sb.append("<w:p><w:pPr>")
            if (style != null) sb.append("""<w:pStyle w:val="$style"/>""")
            if (rtl) sb.append("<w:bidi/>")
            sb.append(extraPPr)
            sb.append("</w:pPr><w:r>")
            if (rtl) sb.append("<w:rPr><w:rtl/></w:rPr>")
            sb.append("""<w:t xml:space="preserve">${esc(text)}</w:t></w:r></w:p>""")
        }
        // An Arabic paragraph's alignment follows its own direction.
        fun dirJc(text: String, alignment: String) = if (isArabic(text) != p.rtl) """<w:jc w:val="${jc(alignment, isArabic(text))}"/>""" else ""

        if (model.title.isNotBlank()) para(model.title, "Title", dirJc(model.title, p.headingAlignment))
        if (model.subtitle.isNotBlank()) para(model.subtitle, null)
        if (model.date.isNotBlank()) para(model.date, null)
        val marker = p.listMarker.takeIf { it.isNotBlank() && it.length <= 2 && it[0] !in "1a" } ?: "•"
        for (section in model.sections) {
            if (section.isPageBreakBefore) sb.append("""<w:p><w:r><w:br w:type="page"/></w:r></w:p>""")
            if (section.heading.isNotBlank()) para(section.heading, "Heading1", dirJc(section.heading, p.headingAlignment))
            section.paragraphs.forEach { para(it, null, dirJc(it, p.bodyAlignment)) }
            section.bulletPoints.forEach { para("$marker\t$it", "ListBullet") }
            section.table?.let { table(sb, it, p) }
        }
        model.table?.let { table(sb, it, p) }
        if (model.signatoryName.isNotBlank()) para(model.signatoryName, null)
        if (model.signatoryTitle.isNotBlank()) para(model.signatoryTitle, null)
        // Word requires at least one paragraph in the body.
        if (sb.isEmpty()) sb.append("<w:p/>")
        return sb.toString()
    }

    private fun table(sb: StringBuilder, table: WordTable, p: TemplateProfile) {
        if (table.headers.isEmpty()) return
        val rtl = p.rtl || table.headers.any { isArabic(it) }
        val fill = hex(p.tableHeaderBgHex)
        val cellJc = jc(p.tableCellAlignment, rtl)
        sb.append("""<w:tbl><w:tblPr><w:tblStyle w:val="TableGrid"/><w:tblW w:w="5000" w:type="pct"/>""")
        if (rtl) sb.append("<w:bidiVisual/>")
        sb.append("</w:tblPr><w:tblGrid>")
        repeat(table.headers.size) { sb.append("<w:gridCol/>") }
        sb.append("</w:tblGrid>")
        fun row(cells: List<String>, header: Boolean) {
            sb.append("<w:tr>")
            if (header) sb.append("<w:trPr><w:tblHeader/></w:trPr>")
            for (cell in cells) {
                val cellRtl = isArabic(cell)
                sb.append("<w:tc><w:tcPr>")
                if (header) sb.append("""<w:shd w:val="clear" w:color="auto" w:fill="$fill"/>""")
                sb.append("</w:tcPr><w:p><w:pPr><w:spacing w:after=\"0\"/>")
                if (cellRtl) sb.append("<w:bidi/>")
                sb.append("""<w:jc w:val="${if (cellRtl != rtl) jc(p.tableCellAlignment, cellRtl) else cellJc}"/></w:pPr><w:r>""")
                val rPr = (if (header) "<w:b/>" else "") + (if (cellRtl) "<w:rtl/>" else "")
                if (rPr.isNotEmpty()) sb.append("<w:rPr>$rPr</w:rPr>")
                sb.append("""<w:t xml:space="preserve">${esc(cell)}</w:t></w:r></w:p></w:tc>""")
            }
            sb.append("</w:tr>")
        }
        row(table.headers, header = true)
        table.rows.forEach { r -> row((r + List((table.headers.size - r.size).coerceAtLeast(0)) { "" }).take(table.headers.size), header = false) }
        sb.append("</w:tbl><w:p/>")
    }
}
