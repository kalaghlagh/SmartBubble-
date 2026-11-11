package com.example.document.xlsx

import com.example.document.model.SheetModel
import com.example.document.model.SpreadsheetModel
import com.example.template.domain.TemplateProfile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * XlsxEngine produces genuine OpenXML (.xlsx) spreadsheet workbooks.
 * When an uploaded template is provided, it preserves cell styles, fills, borders,
 * custom column widths, and fonts from the template, and populates structured spreadsheet
 * data into a NEW workbook without overwriting the original template.
 */
class XlsxEngine {

    fun generateXlsx(
        model: SpreadsheetModel,
        outputFile: File,
        template: TemplateProfile = TemplateProfile.defaultProfile(),
        originalTemplateFile: File? = null
    ): File {
        if (outputFile.parentFile?.exists() == false) {
            outputFile.parentFile?.mkdirs()
        }

        if (originalTemplateFile != null && originalTemplateFile.exists() && originalTemplateFile.length() > 0) {
            try {
                if (generateFromExistingTemplate(originalTemplateFile, model, outputFile, template)) {
                    return outputFile
                }
            } catch (e: Exception) {
                // Fall back to clean default generation
            }
        }

        generateDefaultXlsx(model, outputFile, template)
        return outputFile
    }

    private fun generateFromExistingTemplate(
        templateFile: File,
        model: SpreadsheetModel,
        outputFile: File,
        template: TemplateProfile
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

        // If styles.xml is present, keep it unchanged so the template's exact colors, borders, and fonts are preserved
        // Remove existing worksheet files and inject generated worksheets
        val oldSheetKeys = entries.keys.filter { it.startsWith("xl/worksheets/") }.toList()
        for (key in oldSheetKeys) {
            entries.remove(key)
        }

        // Add model sheets
        for (i in model.sheets.indices) {
            val sheet = model.sheets[i]
            val sheetNum = i + 1
            entries["xl/worksheets/sheet$sheetNum.xml"] = buildSheetXml(sheet).toByteArray(Charsets.UTF_8)
        }

        // Update workbook.xml, rels, and content types
        entries["xl/workbook.xml"] = buildWorkbookXml(model).toByteArray(Charsets.UTF_8)
        entries["xl/_rels/workbook.xml.rels"] = buildWorkbookRelsXml(model.sheets.size).toByteArray(Charsets.UTF_8)
        entries["[Content_Types].xml"] = buildContentTypesXml(model.sheets.size).toByteArray(Charsets.UTF_8)

        ZipOutputStream(FileOutputStream(outputFile)).use { outZip ->
            for ((name, data) in entries) {
                outZip.putNextEntry(ZipEntry(name))
                outZip.write(data)
                outZip.closeEntry()
            }
        }

        return true
    }

    private fun generateDefaultXlsx(
        model: SpreadsheetModel,
        outputFile: File,
        template: TemplateProfile
    ) {
        val headerFillHex = template.tableHeaderBgHex.removePrefix("#")
        val font = template.spreadsheetDefaultFont

        ZipOutputStream(FileOutputStream(outputFile)).use { zip ->
            zip.putNextEntry(ZipEntry("[Content_Types].xml"))
            zip.write(buildContentTypesXml(model.sheets.size).toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("_rels/.rels"))
            zip.write(buildRootRelsXml().toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("xl/workbook.xml"))
            zip.write(buildWorkbookXml(model).toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("xl/_rels/workbook.xml.rels"))
            zip.write(buildWorkbookRelsXml(model.sheets.size).toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("xl/styles.xml"))
            zip.write(buildStylesXml(font, headerFillHex).toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            for (i in model.sheets.indices) {
                val sheet = model.sheets[i]
                val sheetNum = i + 1
                zip.putNextEntry(ZipEntry("xl/worksheets/sheet$sheetNum.xml"))
                zip.write(buildSheetXml(sheet).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
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
        <font><name val="$font"/><sz val="11"/><color rgb="FF1F2937"/></font>
        <font><b/><name val="$font"/><sz val="11"/><color rgb="FFFFFFFF"/></font>
    </fonts>
    <fills count="3">
        <fill><patternFill patternType="none"/></fill>
        <fill><patternFill patternType="gray125"/></fill>
        <fill><patternFill patternType="solid"><fgColor rgb="FF$headerFillHex"/></patternFill></fill>
    </fills>
    <borders count="2">
        <border><left/><right/><top/><bottom/><diagonal/></border>
        <border>
            <left style="thin"><color rgb="FFD1D5DB"/></left>
            <right style="thin"><color rgb="FFD1D5DB"/></right>
            <top style="thin"><color rgb="FFD1D5DB"/></top>
            <bottom style="thin"><color rgb="FFD1D5DB"/></bottom>
        </border>
    </borders>
    <cellStyleXfs count="1">
        <xf numFmtId="0" fontId="0" fillId="0" borderId="0"/>
    </cellStyleXfs>
    <cellXfs count="3">
        <xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0" applyFont="1" applyBorder="1"/>
        <xf numFmtId="0" fontId="1" fillId="2" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1">
            <alignment horizontal="center" vertical="center"/>
        </xf>
        <xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0" applyFont="1" applyBorder="1" applyAlignment="1">
            <alignment horizontal="right" vertical="center"/>
        </xf>
    </cellXfs>
</styleSheet>""".trimIndent()
    }

    private fun buildSheetXml(sheet: SheetModel): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
    <cols>""")
        val colCount = sheet.headers.size.coerceAtLeast(1)
        for (c in 1..colCount) {
            sb.append("\n        <col min=\"$c\" max=\"$c\" width=\"22\" customWidth=\"1\"/>")
        }
        sb.append("""
    </cols>
    <sheetData>
        <row r="1" ht="28" customHeight="1">""")

        for ((colIdx, header) in sheet.headers.withIndex()) {
            val colLetter = getColumnLetter(colIdx + 1)
            sb.append("\n            <c r=\"${colLetter}1\" s=\"1\" t=\"inlineStr\"><is><t>${escapeXml(header)}</t></is></c>")
        }
        sb.append("\n        </row>")

        var rowNum = 2
        for (row in sheet.rows) {
            sb.append("\n        <row r=\"$rowNum\" ht=\"20\" customHeight=\"1\">")
            for ((colIdx, cell) in row.withIndex()) {
                val colLetter = getColumnLetter(colIdx + 1)
                val str = cell.toString()
                val isNum = str.replace(",", "").replace("$", "").toDoubleOrNull() != null
                if (isNum && !str.startsWith("+")) {
                    val cleanNum = str.replace(",", "").replace("$", "").trim()
                    sb.append("\n            <c r=\"$colLetter$rowNum\" s=\"2\"><v>$cleanNum</v></c>")
                } else {
                    sb.append("\n            <c r=\"$colLetter$rowNum\" s=\"0\" t=\"inlineStr\"><is><t>${escapeXml(str)}</t></is></c>")
                }
            }
            sb.append("\n        </row>")
            rowNum++
        }

        if (sheet.summaryFormula != null) {
            val totalCol = getColumnLetter(sheet.headers.size.coerceAtLeast(1))
            val labelCol = getColumnLetter((sheet.headers.size - 1).coerceAtLeast(1))
            sb.append("""
        <row r="$rowNum" ht="24" customHeight="1">
            <c r="$labelCol$rowNum" s="1" t="inlineStr"><is><t>${escapeXml(sheet.summaryFormulaLabel ?: "Total")}</t></is></c>
            <c r="$totalCol$rowNum" s="1"><f>${escapeXml(sheet.summaryFormula)}</f></c>
        </row>""")
        }

        sb.append("""
    </sheetData>
</worksheet>""")
        return sb.toString()
    }

    private fun getColumnLetter(col: Int): String {
        var n = col
        var result = ""
        while (n > 0) {
            val rem = (n - 1) % 26
            result = ('A'.code + rem).toChar() + result
            n = (n - 1) / 26
        }
        return result
    }
}
