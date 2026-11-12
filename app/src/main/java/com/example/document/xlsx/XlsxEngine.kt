package com.example.document.xlsx

import com.example.document.EngineResult
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
    ): File = generateXlsxDetailed(model, outputFile, template, originalTemplateFile).file

    /** Generates the workbook and reports whether the uploaded template file itself was used. */
    fun generateXlsxDetailed(
        model: SpreadsheetModel,
        outputFile: File,
        template: TemplateProfile = TemplateProfile.defaultProfile(),
        originalTemplateFile: File? = null
    ): EngineResult {
        if (outputFile.parentFile?.exists() == false) {
            outputFile.parentFile?.mkdirs()
        }

        if (template.useTemplateLayout && originalTemplateFile != null && originalTemplateFile.exists() && originalTemplateFile.length() > 0) {
            val reason = try {
                if (generateFromExistingTemplate(originalTemplateFile, model, outputFile, template)) {
                    return EngineResult(outputFile, templateApplied = true)
                }
                "the template is not an Excel (XLSX) workbook with a style sheet"
            } catch (e: Exception) {
                "the template file could not be read"
            }
            generateDefaultXlsx(model, outputFile, template)
            return EngineResult(
                outputFile,
                templateApplied = false,
                notes = listOf("The template workbook could not be used ($reason); only its fonts and header colors were applied.")
            )
        }

        generateDefaultXlsx(model, outputFile, template)
        return EngineResult(outputFile, templateApplied = false)
    }

    /** Cell style indices (cellXfs) used for body text, header cells and numbers. */
    private data class CellStyles(val body: Int, val header: Int, val number: Int)

    /**
     * Uses the template workbook: keeps its styles, theme and shared parts; replaces only the
     * worksheets. SmartBubble's header/body styles are APPENDED to the template's styles.xml so
     * every style index the new sheets reference exists.
     */
    private fun generateFromExistingTemplate(
        templateFile: File,
        model: SpreadsheetModel,
        outputFile: File,
        template: TemplateProfile
    ): Boolean {
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(FileInputStream(templateFile)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val baos = ByteArrayOutputStream()
                    zip.copyTo(baos)
                    entries[entry.name] = baos.toByteArray()
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        fun text(name: String) = entries[name]?.let { String(it, Charsets.UTF_8) }
        var workbook = text("xl/workbook.xml") ?: return false
        var workbookRels = text("xl/_rels/workbook.xml.rels") ?: return false
        var contentTypes = text("[Content_Types].xml") ?: return false
        val stylesPath = Regex("""Target="/?(?:xl/)?([^"]*styles\.xml)"""").find(workbookRels)?.groupValues?.get(1)
            ?.let { "xl/$it" } ?: return false
        val styles = text(stylesPath) ?: return false

        val (mergedStyles, cellStyles) = appendSmartBubbleStyles(
            styles, template.spreadsheetDefaultFont, template.tableHeaderBgHex
        ) ?: return false
        entries[stylesPath] = mergedStyles.toByteArray(Charsets.UTF_8)

        val keepExisting = template.keepTemplateContent
        var firstSheetNumber = 1
        var nextSheetId = 1
        val takenNames = mutableSetOf<String>()
        if (keepExisting) {
            // Keep the template's sheets; new sheets get file numbers, ids and names after them.
            firstSheetNumber = 1 + (entries.keys.mapNotNull {
                Regex("""xl/worksheets/sheet(\d+)\.xml""").matchEntire(it)?.groupValues?.get(1)?.toIntOrNull()
            }.maxOrNull() ?: 0)
            nextSheetId = 1 + (Regex("""<sheet\b[^>]*\bsheetId="(\d+)"""").findAll(workbook)
                .mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull() ?: 0)
            Regex("""<sheet\b[^>]*\bname="([^"]*)"""").findAll(workbook)
                .forEach { takenNames += it.groupValues[1].lowercase() }
        } else {
            // Remove the template's sheets and the parts that only make sense with them.
            val removedPrefixes = listOf(
                "xl/worksheets/", "xl/chartsheets/", "xl/drawings/", "xl/charts/", "xl/tables/",
                "xl/pivotTables/", "xl/pivotCache/", "xl/queryTables/", "xl/calcChain.xml"
            )
            entries.keys.filter { k -> removedPrefixes.any { k.startsWith(it) } }.forEach { entries.remove(it) }
            contentTypes = contentTypes.replace(
                Regex("""<Override[^>]*PartName="/xl/(worksheets|chartsheets|drawings|charts|tables|pivotTables|pivotCache|queryTables)/[^"]*"[^>]*/>"""), ""
            ).replace(Regex("""<Override[^>]*PartName="/xl/calcChain\.xml"[^>]*/>"""), "")
            workbookRels = workbookRels.replace(
                Regex("""<Relationship[^>]*Type="[^"]*/relationships/(worksheet|chartsheet|calcChain|pivotCacheDefinition)"[^>]*/>"""), ""
            )
            workbook = workbook
                .replace(Regex("""<definedNames>[\s\S]*?</definedNames>"""), "")
                .replace(Regex("""<pivotCaches>[\s\S]*?</pivotCaches>"""), "")
                .replace(Regex("""\b(activeTab|firstSheet)="\d+""""), "$1=\"0\"")
        }

        val usedIds = Regex("""Id="([^"]+)"""").findAll(workbookRels).map { it.groupValues[1] }.toSet()
        val newSheets = StringBuilder()
        val rels = StringBuilder()
        val overrides = StringBuilder()
        model.sheets.forEachIndexed { i, sheet ->
            val n = firstSheetNumber + i
            var relId = "rIdSb$n"
            var k = 0
            while (relId in usedIds) relId = "rIdSb${n}_${++k}"
            // Sheet names must be unique within the workbook (max 31 characters).
            var name = sheet.name
            var suffix = 2
            while (!takenNames.add(name.lowercase())) {
                name = sheet.name.take(27) + " ($suffix)"
                suffix++
            }
            newSheets.append("<sheet name=\"${escapeXml(name)}\" sheetId=\"${nextSheetId + i}\" r:id=\"$relId\"/>")
            rels.append("<Relationship Id=\"$relId\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet$n.xml\"/>")
            overrides.append("<Override PartName=\"/xl/worksheets/sheet$n.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>")
            entries["xl/worksheets/sheet$n.xml"] = buildSheetXml(sheet, cellStyles).toByteArray(Charsets.UTF_8)
        }

        workbook = when {
            keepExisting && workbook.contains("</sheets>") -> workbook.replace("</sheets>", "$newSheets</sheets>")
            Regex("""<sheets\s*/>""").containsMatchIn(workbook) ->
                workbook.replace(Regex("""<sheets\s*/>""")) { "<sheets>$newSheets</sheets>" }
            workbook.contains("<sheets>") ->
                workbook.replace(Regex("""<sheets>[\s\S]*?</sheets>""")) { "<sheets>$newSheets</sheets>" }
            else -> return false
        }
        if (!workbook.contains("xmlns:r=")) {
            workbook = workbook.replaceFirst("<workbook ", "<workbook xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" ")
        }
        workbookRels = workbookRels.replace("</Relationships>", "$rels</Relationships>")
        contentTypes = contentTypes.replace("</Types>", "$overrides</Types>")
            // An .xltx declares a template main part; the output must be a normal workbook.
            .replace(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.template.main+xml",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"
            )

        entries["xl/workbook.xml"] = workbook.toByteArray(Charsets.UTF_8)
        entries["xl/_rels/workbook.xml.rels"] = workbookRels.toByteArray(Charsets.UTF_8)
        entries["[Content_Types].xml"] = contentTypes.toByteArray(Charsets.UTF_8)

        ZipOutputStream(FileOutputStream(outputFile)).use { outZip ->
            val ordered = entries.entries.sortedBy { if (it.key == "[Content_Types].xml") 0 else 1 }
            for ((name, data) in ordered) {
                outZip.putNextEntry(ZipEntry(name))
                outZip.write(data)
                outZip.closeEntry()
            }
        }
        return true
    }

    /**
     * Appends 2 fonts, 1 fill, 1 border and 3 cell formats to an existing styles.xml and returns
     * the merged XML plus the new cell style indices; null if the style sheet is not usable.
     */
    private fun appendSmartBubbleStyles(styles: String, font: String, headerFillHex: String): Pair<String, CellStyles>? {
        val fillHex = headerFillHex.removePrefix("#").takeLast(6).let {
            if (Regex("[0-9A-Fa-f]{6}").matches(it)) it.uppercase() else "24D6E8"
        }
        val safeFont = escapeXml(font)
        var xml = styles

        fun appendTo(tag: String, itemTag: String, items: List<String>): Int? {
            val open = Regex("""<$tag(\s[^>]*)?>""").find(xml) ?: return null
            if (open.value.endsWith("/>")) return null
            val close = xml.indexOf("</$tag>", open.range.last)
            if (close < 0) return null
            val block = xml.substring(open.range.last + 1, close)
            val existing = Regex("""<$itemTag(\s[^>]*)?/?>""").findAll(block).count()
            val newOpen = if (open.value.contains("count=")) {
                open.value.replace(Regex("""count="\d+""""), "count=\"${existing + items.size}\"")
            } else open.value
            xml = xml.substring(0, open.range.first) + newOpen + block + items.joinToString("") + xml.substring(close)
            return existing
        }

        val fontBase = appendTo("fonts", "font", listOf(
            "<font><sz val=\"11\"/><color rgb=\"FF1F2937\"/><name val=\"$safeFont\"/></font>",
            "<font><b/><sz val=\"11\"/><color rgb=\"FFFFFFFF\"/><name val=\"$safeFont\"/></font>"
        )) ?: return null
        val fillBase = appendTo("fills", "fill", listOf(
            "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FF$fillHex\"/><bgColor indexed=\"64\"/></patternFill></fill>"
        )) ?: return null
        val borderBase = appendTo("borders", "border", listOf(
            "<border><left style=\"thin\"><color rgb=\"FFD1D5DB\"/></left><right style=\"thin\"><color rgb=\"FFD1D5DB\"/></right><top style=\"thin\"><color rgb=\"FFD1D5DB\"/></top><bottom style=\"thin\"><color rgb=\"FFD1D5DB\"/></bottom><diagonal/></border>"
        )) ?: return null
        val bodyFont = fontBase
        val headerFont = fontBase + 1
        val xfBase = appendTo("cellXfs", "xf", listOf(
            "<xf numFmtId=\"0\" fontId=\"$bodyFont\" fillId=\"0\" borderId=\"$borderBase\" xfId=\"0\" applyFont=\"1\" applyBorder=\"1\"/>",
            "<xf numFmtId=\"0\" fontId=\"$headerFont\" fillId=\"$fillBase\" borderId=\"$borderBase\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\" applyAlignment=\"1\"><alignment horizontal=\"center\" vertical=\"center\" wrapText=\"1\"/></xf>",
            "<xf numFmtId=\"0\" fontId=\"$bodyFont\" fillId=\"0\" borderId=\"$borderBase\" xfId=\"0\" applyFont=\"1\" applyBorder=\"1\" applyAlignment=\"1\"><alignment horizontal=\"right\" vertical=\"center\"/></xf>"
        )) ?: return null
        return xml to CellStyles(body = xfBase, header = xfBase + 1, number = xfBase + 2)
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
                zip.write(buildSheetXml(sheet, CellStyles(body = 0, header = 1, number = 2)).toByteArray(Charsets.UTF_8))
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

    private fun buildSheetXml(sheet: SheetModel, styles: CellStyles): String {
        val sb = StringBuilder()
        val rtl = (sheet.headers + sheet.rows.flatten().map { it.toString() }).any { t -> t.any { it in '\u0600'..'\u06FF' } }
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
        if (rtl) sb.append("""
    <sheetViews><sheetView rightToLeft="1" workbookViewId="0"/></sheetViews>""")
        sb.append("""
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
            sb.append("\n            <c r=\"${colLetter}1\" s=\"${styles.header}\" t=\"inlineStr\"><is><t>${escapeXml(header)}</t></is></c>")
        }
        sb.append("\n        </row>")

        var rowNum = 2
        for (row in sheet.rows) {
            sb.append("\n        <row r=\"$rowNum\" ht=\"20\" customHeight=\"1\">")
            for ((colIdx, cell) in row.withIndex()) {
                val colLetter = getColumnLetter(colIdx + 1)
                val str = cell.toString()
                val numeric = str.replace(",", "").replace("$", "").trim()
                val parsed = numeric.toDoubleOrNull()
                // Only plain finite numbers become numeric cells; keep IDs with leading zeros
                // (e.g. "007") and values such as "NaN"/"1e5"/"+5%" as text.
                val isNum = parsed != null && parsed.isFinite() && numeric.none { it.isLetter() } &&
                    !(numeric.length > 1 && numeric.startsWith("0") && !numeric.startsWith("0."))
                if (isNum && !str.startsWith("+")) {
                    val cleanNum = str.replace(",", "").replace("$", "").trim()
                    sb.append("\n            <c r=\"$colLetter$rowNum\" s=\"${styles.number}\"><v>$cleanNum</v></c>")
                } else {
                    sb.append("\n            <c r=\"$colLetter$rowNum\" s=\"${styles.body}\" t=\"inlineStr\"><is><t>${escapeXml(str)}</t></is></c>")
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
            ${if (labelCol != totalCol) "<c r=\"$labelCol$rowNum\" s=\"${styles.header}\" t=\"inlineStr\"><is><t>${escapeXml(sheet.summaryFormulaLabel ?: "Total")}</t></is></c>" else ""}
            <c r="$totalCol$rowNum" s="${styles.header}"><f>${escapeXml(sheet.summaryFormula)}</f></c>
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
