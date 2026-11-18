package com.example.agent.files

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/** A readable part of an Office document, in reading order. */
sealed class OfficeBlock {
    data class Heading(val level: Int, val text: String, val paragraphIndex: Int) : OfficeBlock()
    data class Paragraph(val text: String, val paragraphIndex: Int, val style: String? = null) : OfficeBlock()
    data class Table(val rows: List<List<String>>) : OfficeBlock()
    data class Slide(val number: Int, val title: String?, val texts: List<String>, val tables: List<List<List<String>>>) : OfficeBlock()
    data class Cell(val ref: String, val value: String?, val formula: String?)
    data class Sheet(val name: String, val cells: List<Cell>, val truncated: Boolean) : OfficeBlock()
}

/**
 * What a read produced. [warnings] name content that was not read (so it is reported, never
 * silently dropped): embedded images, charts, a part above the size limit, a sheet cut at the
 * cell limit.
 */
data class OfficeContent(val kind: String, val blocks: List<OfficeBlock>, val warnings: List<String>) {

    /** Plain text for the model, in reading order. Tables tab-separated, cells as "A1 = value". */
    fun toText(maxChars: Int = Int.MAX_VALUE): String {
        val sb = StringBuilder()
        for (b in blocks) {
            if (sb.length >= maxChars) break
            when (b) {
                is OfficeBlock.Heading -> sb.append("#".repeat(b.level.coerceIn(1, 6))).append(' ').append(b.text).append("\n\n")
                is OfficeBlock.Paragraph -> sb.append(b.text).append("\n\n")
                is OfficeBlock.Table -> { b.rows.forEach { sb.append(it.joinToString("\t")).append('\n') }; sb.append('\n') }
                is OfficeBlock.Slide -> {
                    sb.append("Slide ").append(b.number)
                    b.title?.let { sb.append(": ").append(it) }
                    sb.append('\n')
                    b.texts.forEach { sb.append(it).append('\n') }
                    b.tables.forEach { t -> t.forEach { sb.append(it.joinToString("\t")).append('\n') } }
                    sb.append('\n')
                }
                is OfficeBlock.Sheet -> {
                    sb.append("Sheet: ").append(b.name).append('\n')
                    // A grid for the model (rows of cached values), formulas listed separately.
                    sb.append(OoxmlReader.grid(b.cells))
                    b.cells.filter { it.formula != null }.forEach { c ->
                        sb.append(c.ref).append(" = ").append(c.formula).append(" → ").append(c.value ?: "(not calculated in the file)").append('\n')
                    }
                    if (b.truncated) sb.append("(sheet truncated)\n")
                    sb.append('\n')
                }
            }
        }
        return if (sb.length > maxChars) sb.substring(0, maxChars) else sb.toString().trimEnd()
    }

    /** Body paragraphs that hold text (what a Word edit can change), by paragraph index. */
    val paragraphs: List<Pair<Int, String>>
        get() = blocks.mapNotNull {
            when (it) {
                is OfficeBlock.Heading -> it.paragraphIndex to it.text
                is OfficeBlock.Paragraph -> it.paragraphIndex to it.text
                else -> null
            }
        }
}

class OfficeReadException(val reason: String) : Exception(reason)

/**
 * Reads Word, Excel and PowerPoint files (OOXML) as data: text, headings, tables, slides, sheets,
 * cells and formulas. Pure Kotlin, no Android types.
 *
 * Safety: the package is opened read-only; entries are read by fixed part names (never written
 * to disk, so a crafted entry name cannot escape a folder); each part is size-limited; DOCTYPE
 * and external entities are refused; formulas are reported with the value the file stored and
 * are never evaluated.
 */
object OoxmlReader {
    private const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val S = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private const val A = "http://schemas.openxmlformats.org/drawingml/2006/main"
    private const val P = "http://schemas.openxmlformats.org/presentationml/2006/main"
    private const val R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val PR = "http://schemas.openxmlformats.org/package/2006/relationships"

    const val MAX_PART_BYTES = 16L * 1024 * 1024
    const val MAX_CELLS_PER_SHEET = 20_000
    const val MAX_SLIDES = 500

    fun read(file: File): OfficeContent = ZipFile(file).use { zip ->
        val names = zip.entries().asSequence().map { it.name }.toSet()
        fun part(name: String): ByteArray? {
            val e = zip.getEntry(name) ?: return null
            if (e.size > MAX_PART_BYTES) throw OfficeReadException("part_too_large:$name")
            return zip.getInputStream(e).use { bounded(it) }
        }
        when {
            "word/document.xml" in names -> docx(::part, names)
            "xl/workbook.xml" in names -> xlsx(::part)
            "ppt/presentation.xml" in names -> pptx(::part, names)
            else -> throw OfficeReadException("not_office")
        }
    }

    private fun bounded(input: InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(32 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_PART_BYTES) throw OfficeReadException("part_too_large")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** Secure DOM parse: no DOCTYPE, no external entities (shared with the Word copy editor). */
    fun parse(bytes: ByteArray): org.w3c.dom.Document {
        val head = String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1)
        if (head.contains("<!DOCTYPE", ignoreCase = true) || head.contains("<!ENTITY", ignoreCase = true)) throw OfficeReadException("doctype")
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = true
        for ((feature, value) in listOf(
            "http://apache.org/xml/features/disallow-doctype-decl" to true,
            "http://xml.org/sax/features/external-general-entities" to false,
            "http://xml.org/sax/features/external-parameter-entities" to false
        )) runCatching { f.setFeature(feature, value) }
        runCatching { f.isExpandEntityReferences = false }
        return f.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    }

    private fun Element.kids(ns: String, local: String): List<Element> {
        val out = ArrayList<Element>()
        var n: Node? = firstChild
        while (n != null) {
            if (n is Element && n.namespaceURI == ns && n.localName == local) out += n
            n = n.nextSibling
        }
        return out
    }

    private fun Element.all(ns: String, local: String): List<Element> {
        val list = getElementsByTagNameNS(ns, local)
        return (0 until list.length).map { list.item(it) as Element }
    }

    /** All w:p of the document body in document order: the index used by reads and edits. */
    fun bodyParagraphs(doc: org.w3c.dom.Document): List<Element> = doc.documentElement.all(W, "p")

    fun paragraphText(p: Element): String = buildString {
        val runs = p.getElementsByTagNameNS(W, "*")
        for (i in 0 until runs.length) {
            val e = runs.item(i) as Element
            when (e.localName) {
                "t" -> append(e.textContent)
                "tab" -> append('\t')
                "br", "cr" -> append('\n')
            }
        }
    }

    // ------------------------------------------------------------------ Word

    private fun docx(part: (String) -> ByteArray?, names: Set<String>): OfficeContent {
        val doc = parse(part("word/document.xml") ?: throw OfficeReadException("no_document"))
        val styles = part("word/styles.xml")?.let { parse(it) }
        val headingStyles = HashMap<String, Int>()
        styles?.documentElement?.all(W, "style")?.forEach { s ->
            val id = s.getAttributeNS(W, "styleId")
            val name = s.kids(W, "name").firstOrNull()?.getAttributeNS(W, "val").orEmpty().lowercase()
            val level = Regex("""^(heading|titre|عنوان)\s*(\d)$""").find(name)?.groupValues?.get(2)?.toIntOrNull()
                ?: if (name == "title") 1 else null
            if (level != null) headingStyles[id] = level
        }
        val paragraphs = bodyParagraphs(doc)
        val indexOf = HashMap<Element, Int>().apply { paragraphs.forEachIndexed { i, p -> put(p, i) } }
        val blocks = ArrayList<OfficeBlock>()
        val warnings = ArrayList<String>()
        val body = doc.documentElement.kids(W, "body").firstOrNull() ?: throw OfficeReadException("no_body")
        var n: Node? = body.firstChild
        while (n != null) {
            if (n is Element && n.namespaceURI == W) when (n.localName) {
                "p" -> {
                    val text = paragraphText(n).trim()
                    if (text.isNotEmpty()) {
                        val style = n.kids(W, "pPr").firstOrNull()?.kids(W, "pStyle")?.firstOrNull()?.getAttributeNS(W, "val")
                        val outline = n.kids(W, "pPr").firstOrNull()?.kids(W, "outlineLvl")?.firstOrNull()?.getAttributeNS(W, "val")?.toIntOrNull()
                        val level = style?.let { headingStyles[it] ?: Regex("""(?i)^heading(\d)$""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
                            ?: outline?.plus(1)
                        blocks += if (level != null) OfficeBlock.Heading(level, text, indexOf.getValue(n))
                        else OfficeBlock.Paragraph(text, indexOf.getValue(n), style)
                    }
                }
                "tbl" -> blocks += OfficeBlock.Table(n.all(W, "tr").map { tr -> tr.kids(W, "tc").map { tc -> tc.kids(W, "p").joinToString("\n") { paragraphText(it).trim() } } })
            }
            n = n.nextSibling
        }
        if (names.any { it.startsWith("word/media/") }) warnings += "images_not_read"
        if (names.any { it.startsWith("word/charts/") }) warnings += "charts_not_read"
        if (names.any { it.startsWith("word/embeddings/") }) warnings += "embedded_objects_not_read"
        return OfficeContent("DOCX", blocks, warnings)
    }

    // ------------------------------------------------------------------ Excel

    /** Safe relationship target inside the package ("../x" and absolute paths are refused). */
    fun resolve(base: String, target: String): String? {
        if (target.startsWith("/")) return target.trimStart('/').takeIf { ".." !in it.split('/') }
        val parts = base.split('/').filter { it.isNotEmpty() }.toMutableList()
        for (seg in target.split('/')) when (seg) {
            "", "." -> Unit
            ".." -> if (parts.isEmpty()) return null else parts.removeAt(parts.lastIndex)
            else -> parts += seg
        }
        return parts.joinToString("/")
    }

    private fun rels(part: (String) -> ByteArray?, path: String): Map<String, String> {
        val bytes = part(path) ?: return emptyMap()
        return parse(bytes).documentElement.all(PR, "Relationship").associate { it.getAttribute("Id") to it.getAttribute("Target") }
    }

    private fun xlsx(part: (String) -> ByteArray?): OfficeContent {
        val wb = parse(part("xl/workbook.xml") ?: throw OfficeReadException("no_workbook"))
        val shared = part("xl/sharedStrings.xml")?.let { bytes ->
            parse(bytes).documentElement.kids(S, "si").map { si -> si.all(S, "t").joinToString("") { it.textContent } }
        }.orEmpty()
        val rel = rels(part, "xl/_rels/workbook.xml.rels")
        val warnings = ArrayList<String>()
        val sheets = wb.documentElement.all(S, "sheet").mapNotNull { s ->
            val name = s.getAttribute("name")
            val target = rel[s.getAttributeNS(R, "id")]?.let { resolve("xl", it) } ?: return@mapNotNull null
            val bytes = part(target) ?: return@mapNotNull null
            val cells = ArrayList<OfficeBlock.Cell>()
            var truncated = false
            for (c in parse(bytes).documentElement.all(S, "c")) {
                if (cells.size >= MAX_CELLS_PER_SHEET) { truncated = true; break }
                val ref = c.getAttribute("r")
                val type = c.getAttribute("t")
                val formula = c.kids(S, "f").firstOrNull()?.textContent?.takeIf { it.isNotBlank() }
                val raw = c.kids(S, "v").firstOrNull()?.textContent
                val value = when (type) {
                    "s" -> raw?.toIntOrNull()?.let { shared.getOrNull(it) }
                    "inlineStr" -> c.all(S, "t").joinToString("") { it.textContent }
                    "b" -> raw?.let { if (it == "1") "TRUE" else "FALSE" }
                    else -> raw
                }
                if (value != null || formula != null) cells += OfficeBlock.Cell(ref, value, formula)
            }
            if (truncated) warnings += "sheet_truncated:$name"
            OfficeBlock.Sheet(name, cells, truncated)
        }
        return OfficeContent("XLSX", sheets, warnings)
    }

    /** Column letters of "AB12" -> 28 (1-based); row number -> 12. */
    fun column(ref: String): Int = ref.takeWhile { it.isLetter() }.uppercase().fold(0) { acc, ch -> acc * 26 + (ch - 'A' + 1) }
    fun row(ref: String): Int = ref.dropWhile { it.isLetter() }.toIntOrNull() ?: 0

    /** Cells as tab-separated rows (values as stored in the file). */
    fun grid(cells: List<OfficeBlock.Cell>, maxRows: Int = 500): String {
        if (cells.isEmpty()) return ""
        val byRow = cells.groupBy { row(it.ref) }.toSortedMap()
        val width = cells.maxOf { column(it.ref) }.coerceAtMost(60)
        return byRow.entries.take(maxRows).joinToString("\n", postfix = "\n") { (_, rowCells) ->
            val line = arrayOfNulls<String>(width)
            rowCells.forEach { c -> column(c.ref).takeIf { it in 1..width }?.let { line[it - 1] = c.value ?: "" } }
            line.joinToString("\t") { it ?: "" }.trimEnd('\t')
        }
    }

    // ------------------------------------------------------------------ PowerPoint

    private fun pptx(part: (String) -> ByteArray?, names: Set<String>): OfficeContent {
        val pres = parse(part("ppt/presentation.xml") ?: throw OfficeReadException("no_presentation"))
        val rel = rels(part, "ppt/_rels/presentation.xml.rels")
        val order = pres.documentElement.all(P, "sldId").mapNotNull { rel[it.getAttributeNS(R, "id")]?.let { t -> resolve("ppt", t) } }
        val slides = order.take(MAX_SLIDES).mapIndexedNotNull { i, path ->
            val root = parse(part(path) ?: return@mapIndexedNotNull null).documentElement
            var title: String? = null
            val texts = ArrayList<String>()
            for (sp in root.all(P, "sp")) {
                val ph = sp.all(P, "ph").firstOrNull()?.getAttribute("type")
                val text = sp.all(A, "p").joinToString("\n") { p -> p.all(A, "t").joinToString("") { it.textContent } }.trim()
                if (text.isEmpty()) continue
                if (title == null && (ph == "title" || ph == "ctrTitle")) title = text else texts += text
            }
            val tables = root.all(A, "tbl").map { t -> t.all(A, "tr").map { tr -> tr.kids(A, "tc").map { tc -> tc.all(A, "t").joinToString("") { it.textContent } } } }
            OfficeBlock.Slide(i + 1, title, texts, tables)
        }
        val warnings = ArrayList<String>()
        if (order.size > MAX_SLIDES) warnings += "slides_truncated"
        if (names.any { it.startsWith("ppt/media/") }) warnings += "images_not_read"
        if (names.any { it.startsWith("ppt/charts/") }) warnings += "charts_not_read"
        return OfficeContent("PPTX", slides, warnings)
    }
}
