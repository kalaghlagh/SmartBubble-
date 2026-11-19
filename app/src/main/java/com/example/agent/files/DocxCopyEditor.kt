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

    fun writeCopy(source: File, target: File, edits: List<ParagraphEdit>): DocxEditResult {
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
                            val (xml, r) = apply(bytes, edits)
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
