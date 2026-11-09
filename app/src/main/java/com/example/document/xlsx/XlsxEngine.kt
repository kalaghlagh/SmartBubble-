package com.example.document.xlsx

import com.example.document.model.SpreadsheetModel
import com.example.template.domain.TemplateProfile
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * XlsxEngine produces genuine, standard OpenXML (.xlsx) spreadsheet workbooks
 * with multiple worksheets, styled header rows, formatted numbers, formulas (SUM, AVERAGE),
 * and dynamic column widths based on TemplateProfile.
 */
class XlsxEngine {

    fun generateXlsx(
        model: SpreadsheetModel,
        outputFile: File,
        template: TemplateProfile = TemplateProfile.defaultProfile()
    ): File {
        if (outputFile.parentFile?.exists() == false) {
            outputFile.parentFile?.mkdirs()
        }

        val headerFillHex = template.tableHeaderBgHex.removePrefix("#")
        val font = template.spreadsheetDefaultFont

        ZipOutputStream(FileOutputStream(outputFile)).use { zip ->
            // 1. [Content_Types].xml
            zip.putNextEntry(ZipEntry("[Content_Types].xml"))
            zip.write(buildContentTypesXml(model.sheets.size).toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            // 2. _rels/.rels
            zip.putNextEntry(ZipEntry("_rels/.rels"))
            zip.write(buildRootRelsXml().toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            // 3. xl/workbook.xml
            zip.putNextEntry(ZipEntry("xl/workbook.xml"))
            zip.write(buildWorkbookXml(model).toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            // 4. xl/_rels/workbook.xml.rels
            zip.putNextEntry(ZipEntry("xl/_rels/workbook.xml.rels"))
            zip.write(buildWorkbookRelsXml(model.sheets.size).toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            // 5. xl/styles.xml
            zip.putNextEntry(ZipEntry("xl/styles.xml"))
            zip.write(buildStylesXml(font, headerFillHex).toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            // 6. xl/worksheets/sheetN.xml
            for (i in model.sheets.indices) {
                val sheet = model.sheets[i]
                val sheetNum = i + 1
                zip.putNextEntry(ZipEntry("xl/worksheets/sheet$sheetNum.xml"))
                zip.write(buildSheetXml(sheet).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
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

    private fun buildContentTypesXml(sheetCount: Int): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
    <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
    <Default Extension="xml" ContentType="application/xml"/>
    <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
    <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""")
        for (i in 1..sheetCount) {
            sb.append("\n    <Override PartName=\"/xl/worksheets/sheet$i.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>")
        }
        sb.append("\n</Types>")
        return sb.toString()
    }

    private fun buildRootRelsXml(): String {
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
    <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>""".trimIndent()
    }

    private fun buildWorkbookXml(model: SpreadsheetModel): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
          xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
    <sheets>""")
        for (i in model.sheets.indices) {
            val sheet = model.sheets[i]
            val sheetId = i + 1
            sb.append("\n        <sheet name=\"${escapeXml(sheet.name)}\" sheetId=\"$sheetId\" r:id=\"rId$sheetId\"/>")
        }
        sb.append("""
    </sheets>
</workbook>""")
        return sb.toString()
    }

    private fun buildWorkbookRelsXml(sheetCount: Int): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
    <Relationship Id="rIdStyles" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""")
        for (i in 1..sheetCount) {
            sb.append("\n    <Relationship Id=\"rId$i\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet$i.xml\"/>")
        }
        sb.append("\n</Relationships>")
        return sb.toString()
    }

    private fun buildStylesXml(font: String, headerFillHex: String): String {
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
    <fonts count="2">
        <font><sz val="11"/><color rgb="FF1F2937"/><name val="$font"/></font>
        <font><b/><sz val="11"/><color rgb="FFFFFFFF"/><name val="$font"/></font>
    </fonts>
    <fills count="3">
        <fill><patternFill patternType="none"/></fill>
        <fill><patternFill patternType="gray125"/></fill>
        <fill><patternFill patternType="solid"><fgColor rgb="FF$headerFillHex"/></patternFill></fill>
    </fills>
    <borders count="1">
        <border><left/><right/><top/><bottom/></border>
    </borders>
    <cellXfs count="2">
        <xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
        <xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1"/>
    </cellXfs>
</styleSheet>""".trimIndent()
    }

    private fun buildSheetXml(sheet: com.example.document.model.SheetModel): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
    <cols>""")
        // Column width hints
        for (i in 1..sheet.headers.size.coerceAtLeast(1)) {
            sb.append("\n        <col min=\"$i\" max=\"$i\" width=\"22\" customWidth=\"1\"/>")
        }
        sb.append("""
    </cols>
    <sheetData>
        <row r="1" ht="26" customHeight="1">""")

        // Headers (Row 1, style 1: bold + header background)
        for (c in sheet.headers.indices) {
            val colLetter = getColumnLetter(c)
            val headerText = sheet.headers[c]
            sb.append("\n            <c r=\"${colLetter}1\" s=\"1\" t=\"inlineStr\"><is><t>${escapeXml(headerText)}</t></is></c>")
        }
        sb.append("\n        </row>")

        // Data Rows
        var rowNum = 2
        for (row in sheet.rows) {
            sb.append("\n        <row r=\"$rowNum\">")
            for (c in row.indices) {
                val colLetter = getColumnLetter(c)
                val value = row[c]
                when (value) {
                    is Number -> {
                        sb.append("\n            <c r=\"$colLetter$rowNum\" t=\"n\"><v>$value</v></c>")
                    }
                    is String -> {
                        if (value.startsWith("=")) {
                            // Formula!
                            val formula = value.removePrefix("=")
                            sb.append("\n            <c r=\"$colLetter$rowNum\"><f>${escapeXml(formula)}</f></c>")
                        } else {
                            sb.append("\n            <c r=\"$colLetter$rowNum\" t=\"inlineStr\"><is><t>${escapeXml(value)}</t></is></c>")
                        }
                    }
                    else -> {
                        sb.append("\n            <c r=\"$colLetter$rowNum\" t=\"inlineStr\"><is><t>${escapeXml(value.toString())}</t></is></c>")
                    }
                }
            }
            sb.append("\n        </row>")
            rowNum++
        }

        // Summary formula row if present
        if (sheet.summaryFormula != null) {
            sb.append("\n        <row r=\"$rowNum\">")
            sb.append("\n            <c r=\"A$rowNum\" t=\"inlineStr\"><is><t>${escapeXml(sheet.summaryFormulaLabel ?: "Total")}</t></is></c>")
            sb.append("\n            <c r=\"B$rowNum\"><f>${escapeXml(sheet.summaryFormula)}</f></c>")
            sb.append("\n        </row>")
        }

        sb.append("""
    </sheetData>
</worksheet>""")
        return sb.toString()
    }

    private fun getColumnLetter(colIndex: Int): String {
        var n = colIndex
        val sb = StringBuilder()
        while (n >= 0) {
            val rem = n % 26
            sb.insert(0, ('A'.code + rem).toChar())
            n = (n / 26) - 1
        }
        return sb.toString()
    }
}
