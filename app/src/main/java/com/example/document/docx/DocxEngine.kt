package com.example.document.docx

import com.example.document.model.WordDocumentModel
import com.example.template.domain.TemplateProfile
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * DocxEngine produces genuine, standard OpenXML (.docx) files
 * fully compatible with Microsoft Word, LibreOffice, and Google Docs.
 * Incorporates styles, fonts, margins, and colors from TemplateProfile.
 */
class DocxEngine {

    fun generateDocx(
        model: WordDocumentModel,
        outputFile: File,
        template: TemplateProfile = TemplateProfile.defaultProfile()
    ): File {
        if (outputFile.parentFile?.exists() == false) {
            outputFile.parentFile?.mkdirs()
        }

        val primaryHex = template.primaryColorHex.removePrefix("#")
        val font = template.headingFont
        val topMarginTwips = template.marginTopPt * 20
        val leftMarginTwips = template.marginLeftPt * 20

        ZipOutputStream(FileOutputStream(outputFile)).use { zip ->
            // 1. [Content_Types].xml
            zip.putNextEntry(ZipEntry("[Content_Types].xml"))
            zip.write(buildContentTypesXml().toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            // 2. _rels/.rels
            zip.putNextEntry(ZipEntry("_rels/.rels"))
            zip.write(buildRootRelsXml().toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            // 3. word/_rels/document.xml.rels
            zip.putNextEntry(ZipEntry("word/_rels/document.xml.rels"))
            zip.write(buildWordRelsXml().toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            // 4. word/styles.xml
            zip.putNextEntry(ZipEntry("word/styles.xml"))
            zip.write(buildStylesXml(font, primaryHex).toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            // 5. word/document.xml
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write(buildDocumentXml(model, template, primaryHex, font, topMarginTwips, leftMarginTwips).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }

        return outputFile
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
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
<w:body>""")

        // Header metadata band
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
    <w:pPr><w:spacing w:before="200" w:after="80"/></w:pPr>
    <w:r>
        <w:rPr><w:b/><w:sz w:val="48"/><w:color w:val="$primaryHex"/></w:rPr>
        <w:t>${escapeXml(model.title)}</w:t>
    </w:r>
</w:p>""")

        // Subtitle
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

        // Sections
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
            for (bullet in section.bulletPoints) {
                sb.append("""
<w:p>
    <w:pPr>
        <w:ind w:left="360"/>
        <w:spacing w:after="60"/>
    </w:pPr>
    <w:r><w:t>•  ${escapeXml(bullet)}</w:t></w:r>
</w:p>""")
            }
        }

        // Table if present
        if (model.table != null && model.table.headers.isNotEmpty()) {
            sb.append("""
<w:tbl>
    <w:tblPr>
        <w:tblW w:w="0" w:type="auto"/>
        <w:tblBorders>
            <w:top w:val="single" w:sz="4" w:space="0" w:color="D1D5DB"/>
            <w:left w:val="none"/>
            <w:bottom w:val="single" w:sz="6" w:space="0" w:color="$primaryHex"/>
            <w:right w:val="none"/>
            <w:insideH w:val="single" w:sz="4" w:space="0" w:color="E5E7EB"/>
            <w:insideV w:val="none"/>
        </w:tblBorders>
    </w:tblPr>
    <w:tr>""")
            for (header in model.table.headers) {
                sb.append("""
        <w:tc>
            <w:tcPr>
                <w:shd w:val="clear" w:color="auto" w:fill="$primaryHex"/>
                <w:tcMar><w:top w:w="120"/><w:bottom w:w="120"/><w:left w:w="140"/><w:right w:w="140"/></w:tcMar>
            </w:tcPr>
            <w:p><w:r><w:rPr><w:b/><w:color w:val="FFFFFF"/></w:rPr><w:t>${escapeXml(header)}</w:t></w:r></w:p>
        </w:tc>""")
            }
            sb.append("\n    </w:tr>")

            var rowIndex = 0
            for (row in model.table.rows) {
                val fill = if (rowIndex % 2 == 1) "F9FAFB" else "FFFFFF"
                sb.append("\n    <w:tr>")
                for (cell in row) {
                    sb.append("""
        <w:tc>
            <w:tcPr>
                <w:shd w:val="clear" w:color="auto" w:fill="$fill"/>
                <w:tcMar><w:top w:w="100"/><w:bottom w:w="100"/><w:left w:w="140"/><w:right w:w="140"/></w:tcMar>
            </w:tcPr>
            <w:p><w:r><w:t>${escapeXml(cell)}</w:t></w:r></w:p>
        </w:tc>""")
                }
                sb.append("\n    </w:tr>")
                rowIndex++
            }
            sb.append("\n</w:tbl>\n<w:p/>")
        }

        // Signatures / signoff
        if (model.signatoryName.isNotBlank()) {
            sb.append("""
<w:p><w:pPr><w:spacing w:before="400"/></w:pPr></w:p>
<w:p>
    <w:r><w:rPr><w:b/></w:rPr><w:t>${escapeXml(model.signatoryName)}</w:t></w:r>
</w:p>
<w:p>
    <w:r><w:rPr><w:color w:val="6B7280"/></w:rPr><w:t>${escapeXml(model.signatoryTitle)}</w:t></w:r>
</w:p>""")
        }

        // Page Margins in sectPr
        sb.append("""
<w:sectPr>
    <w:pgSz w:w="11906" w:h="16838"/>
    <w:pgMar w:top="$topMargin" w:right="$leftMargin" w:bottom="$topMargin" w:left="$leftMargin" w:header="708" w:footer="708" w:gutter="0"/>
</w:sectPr>
</w:body>
</w:document>""")
        return sb.toString()
    }
}
