package com.example.document.docx

import com.example.document.model.WordDocumentModel
import com.example.template.domain.TemplateProfile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * DocxEngine produces genuine OpenXML (.docx) files.
 * When an uploaded template is provided, it genuinely preserves original styles,
 * headers, footers, theme colors, section properties, and margins, and inserts
 * AI-generated structured content into a NEW document without overwriting the template.
 */
class DocxEngine {

    fun generateDocx(
        model: WordDocumentModel,
        outputFile: File,
        template: TemplateProfile = TemplateProfile.defaultProfile(),
        originalTemplateFile: File? = null
    ): File {
        if (outputFile.parentFile?.exists() == false) {
            outputFile.parentFile?.mkdirs()
        }

        // 1. Attempt genuine template preservation if template file exists
        if (originalTemplateFile != null && originalTemplateFile.exists() && originalTemplateFile.length() > 0) {
            try {
                if (generateFromExistingTemplate(originalTemplateFile, model, outputFile, template)) {
                    return outputFile
                }
            } catch (e: Exception) {
                // Fall back to clean generation using template styling profile
            }
        }

        // 2. Fallback: generate clean standard OOXML with template profile styling
        generateDefaultDocx(model, outputFile, template)
        return outputFile
    }

    private fun generateFromExistingTemplate(
        templateFile: File,
        model: WordDocumentModel,
        outputFile: File,
        templateProfile: TemplateProfile
    ): Boolean {
        val entries = mutableMapOf<String, ByteArray>()

        ZipInputStream(FileInputStream(templateFile)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val baos = ByteArrayOutputStream()
                zip.copyTo(baos)
                entries[entry.name] = baos.toByteArray()
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        val documentXmlBytes = entries["word/document.xml"] ?: return false
        val documentXmlStr = String(documentXmlBytes, Charsets.UTF_8)

        // Extract section properties <w:sectPr> to preserve page size, margins, headers, footers
        val sectPrRegex = """<w:sectPr[\s\S]*?</w:sectPr>""".toRegex()
        val preservedSectPr = sectPrRegex.find(documentXmlStr)?.value ?: buildDefaultSectPr(templateProfile)

        // Check if template contains placeholders
        val hasPlaceholders = listOf("{{TITLE}}", "[TITLE]", "{{BODY}}", "{{CONTENT}}", "{{DATE}}").any {
            documentXmlStr.contains(it)
        }

        val updatedDocumentXml = if (hasPlaceholders) {
            var replaced = documentXmlStr
                .replace("{{TITLE}}", escapeXml(model.title))
                .replace("[TITLE]", escapeXml(model.title))
                .replace("{{SUBTITLE}}", escapeXml(model.subtitle))
                .replace("[SUBTITLE]", escapeXml(model.subtitle))
                .replace("{{DATE}}", escapeXml(model.date))
                .replace("{{ORGANIZATION}}", escapeXml(model.organization))
                .replace("{{SIGNATURE}}", escapeXml("${model.signatoryName}\n${model.signatoryTitle}"))

            val bodyContent = buildBodyParagraphsOnly(model, templateProfile)
            replaced.replace("{{BODY}}", bodyContent).replace("{{CONTENT}}", bodyContent)
        } else {
            // Re-compose body preserving header items and appending structured content
            val bodyContent = buildFullBodyXml(model, templateProfile, preservedSectPr)
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
            xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
    $bodyContent
</w:document>"""
        }

        entries["word/document.xml"] = updatedDocumentXml.toByteArray(Charsets.UTF_8)

        // Write to new output file
        ZipOutputStream(FileOutputStream(outputFile)).use { outZip ->
            for ((name, data) in entries) {
                outZip.putNextEntry(ZipEntry(name))
                outZip.write(data)
                outZip.closeEntry()
            }
        }

        return true
    }

    private fun generateDefaultDocx(
        model: WordDocumentModel,
        outputFile: File,
        template: TemplateProfile
    ) {
        val primaryHex = template.primaryColorHex.removePrefix("#")
        val font = template.headingFont
        val topMarginTwips = template.marginTopPt * 20
        val leftMarginTwips = template.marginLeftPt * 20

        ZipOutputStream(FileOutputStream(outputFile)).use { zip ->
            zip.putNextEntry(ZipEntry("[Content_Types].xml"))
            zip.write(buildContentTypesXml().toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("_rels/.rels"))
            zip.write(buildRootRelsXml().toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("word/_rels/document.xml.rels"))
            zip.write(buildWordRelsXml().toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("word/styles.xml"))
            zip.write(buildStylesXml(font, primaryHex).toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write(buildDocumentXml(model, template, primaryHex, font, topMarginTwips, leftMarginTwips).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }

    private fun buildDefaultSectPr(template: TemplateProfile): String {
        val topMarginTwips = template.marginTopPt * 20
        val leftMarginTwips = template.marginLeftPt * 20
        return """<w:sectPr>
        <w:pgSz w:w="12240" w:h="15840"/>
        <w:pgMar w:top="$topMarginTwips" w:right="$leftMarginTwips" w:bottom="$topMarginTwips" w:left="$leftMarginTwips" w:header="720" w:footer="720" w:gutter="0"/>
    </w:sectPr>"""
    }

    private fun buildBodyParagraphsOnly(model: WordDocumentModel, template: TemplateProfile): String {
        val sb = StringBuilder()
        for (section in model.sections) {
            sb.append("""
<w:p>
    <w:pPr><w:pStyle w:val="Heading1"/></w:pPr>
    <w:r><w:t>${escapeXml(section.heading)}</w:t></w:r>
</w:p>""")
            for (p in section.paragraphs) {
                sb.append("""
<w:p>
    <w:pPr><w:pStyle w:val="Normal"/></w:pPr>
    <w:r><w:t>${escapeXml(p)}</w:t></w:r>
</w:p>""")
            }
            for (b in section.bulletPoints) {
                sb.append("""
<w:p>
    <w:pPr><w:ind w:left="480"/></w:pPr>
    <w:r><w:t>• ${escapeXml(b)}</w:t></w:r>
</w:p>""")
            }
        }
        return sb.toString()
    }

    private fun buildFullBodyXml(
        model: WordDocumentModel,
        template: TemplateProfile,
        sectPrXml: String
    ): String {
        val primaryHex = template.primaryColorHex.removePrefix("#")
        val sb = StringBuilder()
        sb.append("<w:body>")

        if (template.headerText.isNotBlank()) {
            sb.append("""
<w:p>
    <w:pPr><w:jc w:val="right"/><w:spacing w:after="120"/></w:pPr>
    <w:r><w:rPr><w:sz w:val="18"/><w:color w:val="859395"/></w:rPr><w:t>${escapeXml(template.headerText)}</w:t></w:r>
</w:p>""")
        }

        // Title
        sb.append("""
<w:p>
    <w:pPr><w:pStyle w:val="Heading1"/><w:spacing w:before="240" w:after="100"/></w:pPr>
    <w:r>
        <w:rPr><w:b/><w:sz w:val="44"/><w:color w:val="$primaryHex"/></w:rPr>
        <w:t>${escapeXml(model.title)}</w:t>
    </w:r>
</w:p>""")

        if (model.subtitle.isNotBlank()) {
            sb.append("""
<w:p>
    <w:pPr><w:spacing w:after="240"/></w:pPr>
    <w:r>
        <w:rPr><w:i/><w:sz w:val="24"/><w:color w:val="4B5563"/></w:rPr>
        <w:t>${escapeXml(model.subtitle)}</w:t>
    </w:r>
</w:p>""")
        }

        if (model.organization.isNotBlank() || model.date.isNotBlank()) {
            sb.append("""
<w:p>
    <w:pPr><w:spacing w:after="200"/></w:pPr>
    <w:r><w:rPr><w:b/><w:sz w:val="20"/></w:rPr><w:t>${escapeXml(model.organization)}</w:t></w:r>
    <w:r><w:rPr><w:sz w:val="20"/><w:color w:val="6B7280"/></w:rPr><w:t> — ${escapeXml(model.date)}</w:t></w:r>
</w:p>""")
        }

        // Sections
        for (section in model.sections) {
            if (section.isPageBreakBefore) {
                sb.append("""
<w:p>
    <w:r><w:br w:type="page"/></w:r>
</w:p>""")
            }

            if (section.heading.isNotBlank()) {
                val isHeadingRtl = section.isRtl ?: containsArabic(section.heading)
                val bidiTag = if (isHeadingRtl) "<w:bidi/><w:jc w:val=\"right\"/>" else ""
                val rtlRun = if (isHeadingRtl) "<w:rtl/>" else ""
                sb.append("""
<w:p>
    <w:pPr><w:pStyle w:val="Heading2"/><w:spacing w:before="240" w:after="100"/>$bidiTag</w:pPr>
    <w:r><w:rPr><w:b/><w:sz w:val="28"/><w:color w:val="$primaryHex"/>$rtlRun</w:rPr><w:t>${escapeXml(section.heading)}</w:t></w:r>
</w:p>""")
            }

            for (paragraph in section.paragraphs) {
                val isParaRtl = section.isRtl ?: containsArabic(paragraph)
                val bidiTag = if (isParaRtl) "<w:bidi/><w:jc w:val=\"right\"/>" else ""
                val rtlRun = if (isParaRtl) "<w:rtl/>" else ""
                sb.append("""
<w:p>
    <w:pPr><w:pStyle w:val="Normal"/><w:spacing w:after="160"/>$bidiTag</w:pPr>
    <w:r><w:rPr><w:sz w:val="22"/><w:color w:val="1F2937"/>$rtlRun</w:rPr><w:t>${escapeXml(paragraph)}</w:t></w:r>
</w:p>""")
            }

            for (bullet in section.bulletPoints) {
                val isBulletRtl = section.isRtl ?: containsArabic(bullet)
                val bidiTag = if (isBulletRtl) "<w:bidi/><w:jc w:val=\"right\"/>" else ""
                val rtlRun = if (isBulletRtl) "<w:rtl/>" else ""
                sb.append("""
<w:p>
    <w:pPr><w:ind w:left="480"/><w:spacing w:after="80"/>$bidiTag</w:pPr>
    <w:r><w:rPr><w:b/><w:color w:val="$primaryHex"/>$rtlRun</w:rPr><w:t>• </w:t></w:r>
    <w:r><w:rPr><w:sz w:val="22"/>$rtlRun</w:rPr><w:t>${escapeXml(bullet)}</w:t></w:r>
</w:p>""")
            }

            // Section-specific table if present
            if (section.table != null && section.table.headers.isNotEmpty()) {
                appendTableXml(sb, section.table, template)
            }
        }

        // Table
        if (model.table != null && model.table.headers.isNotEmpty()) {
            appendTableXml(sb, model.table, template)
        }

        // Signature block
        if (model.signatoryName.isNotBlank()) {
            val isSigRtl = containsArabic(model.signatoryName) || containsArabic(model.signatoryTitle)
            val bidiTag = if (isSigRtl) "<w:bidi/><w:jc w:val=\"right\"/>" else ""
            val rtlRun = if (isSigRtl) "<w:rtl/>" else ""
            sb.append("""
<w:p>
    <w:pPr><w:spacing w:before="400" w:after="80"/>$bidiTag</w:pPr>
    <w:r><w:rPr><w:b/><w:sz w:val="22"/>$rtlRun</w:rPr><w:t>${escapeXml(model.signatoryName)}</w:t></w:r>
</w:p>
<w:p>
    <w:pPr><w:spacing w:after="200"/>$bidiTag</w:pPr>
    <w:r><w:rPr><w:sz w:val="20"/><w:color w:val="6B7280"/>$rtlRun</w:rPr><w:t>${escapeXml(model.signatoryTitle)}</w:t></w:r>
</w:p>""")
        }

        sb.append(sectPrXml)
        sb.append("</w:body>")
        return sb.toString()
    }

    private fun containsArabic(text: String): Boolean {
        return text.any { it in '\u0600'..'\u06FF' }
    }

    private fun appendTableXml(sb: StringBuilder, table: com.example.document.model.WordTable, template: TemplateProfile) {
        val hasArabic = table.headers.any { containsArabic(it) } ||
                table.rows.any { row -> row.any { containsArabic(it) } }
        val bidiVisual = if (hasArabic) "<w:bidiVisual/>" else ""
        val jc = if (hasArabic) "<w:jc w:val=\"right\"/>" else ""

        sb.append("""
<w:tbl>
    <w:tblPr>
        <w:tblW w:w="5000" w:type="pct"/>
        $bidiVisual
        $jc
        <w:tblBorders>
            <w:top w:val="single" w:sz="6" w:space="0" w:color="D1D5DB"/>
            <w:left w:val="none"/>
            <w:bottom w:val="single" w:sz="6" w:space="0" w:color="D1D5DB"/>
            <w:right w:val="none"/>
            <w:insideH w:val="single" w:sz="4" w:space="0" w:color="E5E7EB"/>
            <w:insideV w:val="none"/>
        </w:tblBorders>
    </w:tblPr>
    <w:tr>""")
        for (header in table.headers) {
            val isRtl = containsArabic(header)
            val pBidi = if (isRtl) "<w:bidi/><w:jc w:val=\"right\"/>" else ""
            val rRtl = if (isRtl) "<w:rtl/>" else ""
            sb.append("""
        <w:tc>
            <w:tcPr><w:shd w:val="clear" w:color="auto" w:fill="${template.tableHeaderBgHex.removePrefix("#")}"/></w:tcPr>
            <w:p><w:pPr><w:spacing w:before="120" w:after="120"/>$pBidi</w:pPr><w:r><w:rPr><w:b/><w:sz w:val="20"/><w:color w:val="FFFFFF"/>$rRtl</w:rPr><w:t>${escapeXml(header)}</w:t></w:r></w:p>
        </w:tc>""")
        }
        sb.append("</w:tr>")

        for ((rIdx, row) in table.rows.withIndex()) {
            val fill = if (rIdx % 2 == 1) template.tableAlternateRowBgHex.removePrefix("#") else "FFFFFF"
            sb.append("<w:tr>")
            for (cell in row) {
                val isRtl = containsArabic(cell)
                val pBidi = if (isRtl) "<w:bidi/><w:jc w:val=\"right\"/>" else ""
                val rRtl = if (isRtl) "<w:rtl/>" else ""
                sb.append("""
        <w:tc>
            <w:tcPr><w:shd w:val="clear" w:color="auto" w:fill="$fill"/></w:tcPr>
            <w:p><w:pPr><w:spacing w:before="100" w:after="100"/>$pBidi</w:pPr><w:r><w:rPr><w:sz w:val="20"/>$rRtl</w:rPr><w:t>${escapeXml(cell)}</w:t></w:r></w:p>
        </w:tc>""")
            }
            sb.append("</w:tr>")
        }
        sb.append("</w:tbl>")
    }

    private fun escapeXml(str: String): String {
        return str.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    private fun buildContentTypesXml(): String {
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
    <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
    <Default Extension="xml" ContentType="application/xml"/>
    <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
    <Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
</Types>""".trimIndent()
    }

    private fun buildRootRelsXml(): String {
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
    <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>""".trimIndent()
    }

    private fun buildWordRelsXml(): String {
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
    <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>""".trimIndent()
    }

    private fun buildStylesXml(font: String, primaryHex: String): String {
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
    <w:docDefaults>
        <w:rPrDefault>
            <w:rPr>
                <w:rFonts w:ascii="$font" w:hAnsi="$font" w:cs="$font"/>
                <w:sz w:val="22"/>
                <w:szCs w:val="22"/>
                <w:color w:val="1F2937"/>
            </w:rPr>
        </w:rPrDefault>
    </w:docDefaults>
    <w:style w:type="paragraph" w:default="1" w:styleId="Normal">
        <w:name w:val="Normal"/>
        <w:pPr>
            <w:spacing w:line="276" w:lineRule="auto" w:after="160"/>
        </w:pPr>
    </w:style>
    <w:style w:type="paragraph" w:styleId="Heading1">
        <w:name w:val="heading 1"/>
        <w:pPr>
            <w:spacing w:before="240" w:after="120"/>
        </w:pPr>
        <w:rPr>
            <w:b/>
            <w:color w:val="$primaryHex"/>
            <w:sz w:val="36"/>
        </w:rPr>
    </w:style>
    <w:style w:type="paragraph" w:styleId="Heading2">
        <w:name w:val="heading 2"/>
        <w:pPr>
            <w:spacing w:before="180" w:after="80"/>
        </w:pPr>
        <w:rPr>
            <w:b/>
            <w:color w:val="10182B"/>
            <w:sz w:val="28"/>
        </w:rPr>
    </w:style>
</w:styles>""".trimIndent()
    }

    private fun buildDocumentXml(
        model: WordDocumentModel,
        template: TemplateProfile,
        primaryHex: String,
        font: String,
        topMargin: Int,
        leftMargin: Int
    ): String {
        val sectPr = """<w:sectPr>
    <w:pgSz w:w="12240" w:h="15840"/>
    <w:pgMar w:top="$topMargin" w:right="$leftMargin" w:bottom="$topMargin" w:left="$leftMargin" w:header="720" w:footer="720" w:gutter="0"/>
</w:sectPr>"""
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
    ${buildFullBodyXml(model, template, sectPr)}
</w:document>"""
    }
}
