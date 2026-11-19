package com.example.agent.files

import org.w3c.dom.Element
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/** One paragraph change: [index] is the body paragraph index from [OoxmlReader]. */
data class ParagraphEdit(val index: Int, val newText: String)

/**
 * Replace the [occurrence]-th (0-based) [oldText] in paragraph [index] by [newText], inside the
 * runs that hold it: the formatting of every run is kept (the new text takes the formatting of
 * the run where the old text started).
 */
data class TextReplacement(val index: Int, val oldText: String, val newText: String, val occurrence: Int = 0)

/** What applying edits did. [mixedFormatting]: edited paragraphs whose runs had different formatting. */
data class DocxEditResult(val changed: Int, val skipped: List<Int>, val mixedFormatting: List<Int>)

/**
 * Writes an edited COPY of a Word document. The source is only read. Every package part except
 * word/document.xml is copied byte for byte; in document.xml only the text of the edited
 * paragraphs changes: the paragraph and run properties stay, the new text goes into the first
 * text run and the paragraph's other text runs are emptied (so a paragraph that mixed bold and
 * plain words takes the first run's formatting; [DocxEditResult.mixedFormatting] reports it).
 *
 * The copy is written to a temporary file next to [target] and renamed only when complete, so a
 * failure never leaves a half-written document. Entry names with "..", absolute paths or
 * backslashes refuse the whole file (they are never needed by Word and are a traversal sign).
 */
object DocxCopyEditor {
    private const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val MAX_ENTRIES = 5_000

    fun safeEntryName(name: String): Boolean =
        name.isNotEmpty() && !name.startsWith("/") && '\\' !in name && name.split('/').none { it == ".." } && ':' !in name

    /** Which edited paragraphs mix run formatting (shown to the user before approval). */
    fun mixedFormatting(source: File, indexes: Collection<Int>): List<Int> {
        ZipFile(source).use { zip ->
            val entry = zip.getEntry("word/document.xml") ?: return emptyList()
            val doc = OoxmlReader.parse(zip.getInputStream(entry).use { it.readBytes() })
            val paragraphs = OoxmlReader.bodyParagraphs(doc)
            return indexes.filter { i -> paragraphs.getOrNull(i)?.let { p -> runProperties(p).distinct().size > 1 } == true }
        }
    }

    private fun runProperties(p: Element): List<String> {
        val runs = p.getElementsByTagNameNS(W, "r")
        return (0 until runs.length).map { i ->
            val r = runs.item(i) as Element
            val pr = r.getElementsByTagNameNS(W, "rPr").item(0)
            if (r.getElementsByTagNameNS(W, "t").length == 0) "" else pr?.let { serialize(it) } ?: "-"
        }.filter { it.isNotEmpty() }
    }

    private fun serialize(node: org.w3c.dom.Node): String {
        val out = ByteArrayOutputStream()
        TransformerFactory.newInstance().newTransformer().apply { setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes") }
            .transform(DOMSource(node), StreamResult(out))
        return out.toString("UTF-8")
    }

    fun writeCopy(source: File, target: File, edits: List<ParagraphEdit>): DocxEditResult = writeWith(source, target) { apply(it, edits) }

    /**
     * Writes a copy where only the given texts are replaced, run by run (bold labels, fonts,
     * colors, tables, headers and everything else unchanged). A replacement whose text is not
     * found is reported in [DocxEditResult.skipped] (by paragraph index).
     */
    fun writeCopyReplacing(source: File, target: File, replacements: List<TextReplacement>): DocxEditResult =
        writeWith(source, target) { replace(it, replacements) }

    private fun replace(bytes: ByteArray, replacements: List<TextReplacement>): Pair<ByteArray, DocxEditResult> {
        val doc = OoxmlReader.parse(bytes)
        val paragraphs = OoxmlReader.bodyParagraphs(doc)
        var changed = 0
        val skipped = ArrayList<Int>()
        // Later occurrences first, so earlier offsets stay valid within a paragraph.
        for (r in replacements.sortedWith(compareBy<TextReplacement> { it.index }.thenByDescending { it.occurrence })) {
            val p = paragraphs.getOrNull(r.index)
            val nodes = p?.getElementsByTagNameNS(W, "t")?.let { list -> (0 until list.length).map { list.item(it) as Element } }.orEmpty()
            val texts = nodes.map { it.textContent }
            val full = texts.joinToString("")
            var at = -1
            for (n in 0..r.occurrence) {
                at = full.indexOf(r.oldText, at + 1)
                if (at < 0) break
            }
            if (r.oldText.isEmpty() || at < 0) { skipped += r.index; continue }
            val end = at + r.oldText.length
            var offset = 0
            var placed = false
            for ((k, node) in nodes.withIndex()) {
                val t = texts[k]
                val nodeStart = offset
                val nodeEnd = offset + t.length
                offset = nodeEnd
                if (nodeEnd <= at || nodeStart >= end) continue
                val from = (at - nodeStart).coerceAtLeast(0)
                val to = (end - nodeStart).coerceAtMost(t.length)
                val first = !placed
                val replacement = if (!placed) r.newText.also { placed = true } else ""
                node.textContent = t.substring(0, from) + replacement + t.substring(to)
                node.setAttributeNS("http://www.w3.org/XML/1998/namespace", "xml:space", "preserve")
                // Arabic placed in a run that was not right to left: the run becomes right to left
                // (w:rtl), so Word orders and shapes it as Arabic; its other formatting is kept.
                if (first && hasArabic(r.newText) && !hasArabic(t)) (node.parentNode as? Element)?.let { run -> markRtl(run) }
            }
            // A paragraph that becomes mostly Arabic gets a right-to-left direction (w:bidi);
            // a paragraph that already was keeps its own properties.
            val after = nodes.joinToString("") { it.textContent }
            if (p != null && !mostlyArabic(full) && mostlyArabic(after)) markBidi(p)
            changed++
        }
        val out = ByteArrayOutputStream()
        TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.ENCODING, "UTF-8")
            setOutputProperty(OutputKeys.STANDALONE, "yes")
        }.transform(DOMSource(doc), StreamResult(out))
        return out.toByteArray() to DocxEditResult(changed, skipped.distinct(), emptyList())
    }

    private fun hasArabic(s: String) = s.any { it in '\u0600'..'\u06FF' || it in '\u0750'..'\u077F' }
    private fun mostlyArabic(s: String) = s.count { it in '\u0600'..'\u06FF' } > s.count { it in 'A'..'Z' || it in 'a'..'z' }

    /** Adds w:rtl (and w:cs for complex-script formatting) to a run's properties, creating them first when needed. */
    private fun markRtl(run: Element) {
        if (run.localName != "r") return
        val doc = run.ownerDocument
        var rPr = (0 until run.childNodes.length).map { run.childNodes.item(it) }.firstOrNull { it is Element && it.localName == "rPr" } as? Element
        if (rPr == null) {
            rPr = doc.createElementNS(W, "w:rPr")
            run.insertBefore(rPr, run.firstChild)
        }
        if (rPr.getElementsByTagNameNS(W, "rtl").length == 0) insertInOrder(rPr, doc.createElementNS(W, "w:rtl"), AFTER_RTL)
    }

    // OOXML schema order: an element is inserted before the first sibling that must follow it.
    private val AFTER_RTL = setOf("cs", "em", "lang", "eastAsianLayout", "specVanish", "oMath")
    private val AFTER_BIDI = setOf("adjustRightInd", "snapToGrid", "spacing", "ind", "contextualSpacing", "mirrorIndents", "suppressOverlap",
        "jc", "textDirection", "textAlignment", "textboxTightWrap", "outlineLvl", "divId", "cnfStyle", "rPr", "sectPr", "pPrChange")

    private fun insertInOrder(parent: Element, child: Element, after: Set<String>) {
        val next = (0 until parent.childNodes.length).map { parent.childNodes.item(it) }.firstOrNull { it is Element && it.localName in after }
        if (next != null) parent.insertBefore(child, next) else parent.appendChild(child)
    }

    /** Adds w:bidi to a paragraph's properties (created first when needed). */
    private fun markBidi(p: Element) {
        val doc = p.ownerDocument
        var pPr = (0 until p.childNodes.length).map { p.childNodes.item(it) }.firstOrNull { it is Element && it.localName == "pPr" } as? Element
        if (pPr == null) {
            pPr = doc.createElementNS(W, "w:pPr")
            p.insertBefore(pPr, p.firstChild)
        }
        if (pPr.getElementsByTagNameNS(W, "bidi").length == 0) insertInOrder(pPr, doc.createElementNS(W, "w:bidi"), AFTER_BIDI)
    }

    private fun writeWith(source: File, target: File, transform: (ByteArray) -> Pair<ByteArray, DocxEditResult>): DocxEditResult {
        require(source.canonicalPath != target.canonicalPath) { "The copy must not be the original" }
        val temp = File(target.parentFile, target.name + ".part")
        var result: DocxEditResult? = null
        try {
            ZipFile(source).use { zip ->
                val entries = zip.entries().toList()
                if (entries.size > MAX_ENTRIES) throw OfficeReadException("too_many_entries")
                if (entries.any { !safeEntryName(it.name) }) throw OfficeReadException("unsafe_entry_name")
                ZipOutputStream(FileOutputStream(temp)).use { out ->
                    for (e in entries) {
                        out.putNextEntry(ZipEntry(e.name))
                        if (e.name == "word/document.xml") {
                            val bytes = zip.getInputStream(e).use { it.readBytes() }
                            val (xml, r) = transform(bytes)
                            result = r
                            out.write(xml)
                        } else {
                            zip.getInputStream(e).use { it.copyTo(out) }
                        }
                        out.closeEntry()
                    }
                }
            }
            if (result == null) throw OfficeReadException("no_document")
            if (target.exists() && !target.delete()) throw java.io.IOException("target exists")
            if (!temp.renameTo(target)) throw java.io.IOException("rename failed")
            return result!!
        } finally {
            temp.delete()
        }
    }

    private fun apply(bytes: ByteArray, edits: List<ParagraphEdit>): Pair<ByteArray, DocxEditResult> {
        val doc = OoxmlReader.parse(bytes)
        val paragraphs = OoxmlReader.bodyParagraphs(doc)
        var changed = 0
        val skipped = ArrayList<Int>()
        val mixed = ArrayList<Int>()
        for (edit in edits.distinctBy { it.index }) {
            val p = paragraphs.getOrNull(edit.index)
            val texts = p?.getElementsByTagNameNS(W, "t")
            if (p == null || texts == null || texts.length == 0) {
                skipped += edit.index
                continue
            }
            if (runProperties(p).distinct().size > 1) mixed += edit.index
            for (i in 0 until texts.length) {
                val t = texts.item(i) as Element
                t.textContent = if (i == 0) edit.newText else ""
                if (i == 0) t.setAttributeNS("http://www.w3.org/XML/1998/namespace", "xml:space", "preserve")
            }
            changed++
        }
        val out = ByteArrayOutputStream()
        TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.ENCODING, "UTF-8")
            setOutputProperty(OutputKeys.STANDALONE, "yes")
        }.transform(DOMSource(doc), StreamResult(out))
        return out.toByteArray() to DocxEditResult(changed, skipped, mixed)
    }
}
