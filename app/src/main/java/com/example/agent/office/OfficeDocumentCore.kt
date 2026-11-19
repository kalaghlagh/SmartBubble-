package com.example.agent.office

import com.example.ai.providers.Json
import java.text.Normalizer

/** The kind of a detected field. [OTHER] is a labelled form field ("Matricule : 1234"). */
enum class FieldKey { SENDER, RECIPIENT, SUBJECT, DATE, REFERENCE, PHONE, EMAIL, ADDRESS, NAME, OTHER }

/** What a block of the source document is. */
enum class BlockType { HEADING, PARAGRAPH, LIST_ITEM, TABLE, CAPTION }

/**
 * One block of an analyzed source document, in reading order: its text, where it is (page, and
 * for a Word file its body paragraph index), how it looked (alignment, bold, direction) and how
 * sure the reading is (OCR confidence; null for text read directly from the file).
 */
data class DocBlock(
    val index: Int,
    val page: Int,
    val type: BlockType,
    val text: String,
    val rtl: Boolean = false,
    val align: String = "",
    val bold: Boolean = false,
    val confidence: Float? = null,
    val uncertain: Boolean = false,
    /** Word body paragraph index (Word sources). */
    val paragraphIndex: Int? = null,
    val rows: List<List<String>> = emptyList()
)

/** A value found in the document, tied to where it is: block, offset in the block text, and its label as written. */
data class ExtractedField(
    val key: FieldKey,
    val label: String,
    val value: String,
    val block: Int,
    val start: Int,
    val uncertain: Boolean = false
)

/** The result of analyzing a source document (never changed by the analysis). */
data class DocumentAnalysis(
    /** "DOCX", "PDF" or "IMAGE". */
    val sourceKind: String,
    val pages: Int,
    val blocks: List<DocBlock>,
    val fields: List<ExtractedField>,
    val rtl: Boolean,
    /** Signatures, stamps or seals are mentioned: never reproduced, a fresh signature is needed. */
    val mentionsSignature: Boolean,
    /** An identity document, diploma, civil-status or bank record: modification refused. */
    val protectedKind: String? = null,
    /** Pages read by OCR. */
    val scanned: Boolean = false
) {
    val uncertainFields: Int get() = fields.count { it.uncertain }
}

/** One requested change: a field (by kind or label) or an exact text, and the new value. */
data class Modification(val key: FieldKey? = null, val label: String? = null, val oldText: String? = null, val newValue: String)

/** A change located in the document: block, offset and old text. */
data class PlannedChange(val block: Int, val start: Int, val oldText: String, val newText: String, val field: String)

object OfficeCodec {
    fun encode(a: DocumentAnalysis): String = Json.write(
        mapOf(
            "sourceKind" to a.sourceKind, "pages" to a.pages, "rtl" to a.rtl, "signature" to a.mentionsSignature,
            "protected" to a.protectedKind, "scanned" to a.scanned,
            "blocks" to a.blocks.map { b ->
                mapOf("i" to b.index, "p" to b.page, "t" to b.type.name, "x" to b.text, "rtl" to b.rtl, "a" to b.align, "b" to b.bold,
                    "c" to b.confidence?.toDouble(), "u" to b.uncertain, "pi" to b.paragraphIndex, "rows" to b.rows)
            },
            "fields" to a.fields.map { f -> mapOf("k" to f.key.name, "l" to f.label, "v" to f.value, "b" to f.block, "s" to f.start, "u" to f.uncertain) }
        )
    )

    fun decode(text: String): DocumentAnalysis {
        val m = Json.obj(Json.parse(text)) ?: throw IllegalArgumentException("analysis")
        return DocumentAnalysis(
            sourceKind = m["sourceKind"] as String, pages = (m["pages"] as Number).toInt(), rtl = m["rtl"] == true,
            mentionsSignature = m["signature"] == true, protectedKind = m["protected"] as? String, scanned = m["scanned"] == true,
            blocks = Json.arr(m["blocks"]).orEmpty().mapNotNull { Json.obj(it) }.map { b ->
                DocBlock(
                    index = (b["i"] as Number).toInt(), page = (b["p"] as Number).toInt(), type = BlockType.valueOf(b["t"] as String),
                    text = b["x"] as String, rtl = b["rtl"] == true, align = b["a"] as? String ?: "", bold = b["b"] == true,
                    confidence = (b["c"] as? Number)?.toFloat(), uncertain = b["u"] == true, paragraphIndex = (b["pi"] as? Number)?.toInt(),
                    rows = Json.arr(b["rows"]).orEmpty().map { r -> Json.arr(r).orEmpty().map { it as? String ?: "" } }
                )
            },
            fields = Json.arr(m["fields"]).orEmpty().mapNotNull { Json.obj(it) }.map { f ->
                ExtractedField(FieldKey.valueOf(f["k"] as String), f["l"] as? String ?: "", f["v"] as String, (f["b"] as Number).toInt(), (f["s"] as Number).toInt(), f["u"] == true)
            }
        )
    }

    fun encodeChanges(changes: List<PlannedChange>): String = Json.write(changes.map { mapOf("b" to it.block, "s" to it.start, "o" to it.oldText, "n" to it.newText, "f" to it.field) })

    fun decodeChanges(text: String?): List<PlannedChange> = Json.arr(Json.parse(text ?: "[]")).orEmpty().mapNotNull { Json.obj(it) }.map {
        PlannedChange((it["b"] as Number).toInt(), (it["s"] as Number).toInt(), it["o"] as String, it["n"] as String, it["f"] as? String ?: "")
    }
}

/**
 * Finds the information of a document (Arabic, French, English): labelled values ("Objet :",
 * "Réf :", "الموضوع:", "Date:"...), dates, reference numbers, phone numbers, e-mail addresses and
 * the recipient line ("À Monsieur le Directeur", "To:", "إلى السيد ..."). Every value keeps its
 * block and offset, so a change replaces exactly that text. Nothing is guessed: an unlabelled
 * line is not called a sender.
 */
object FieldDetector {
    private data class Label(val key: FieldKey, val regex: Regex)

    // "Label : value" at the start of a line (the label as written is kept).
    private val labels = listOf(
        Label(FieldKey.SUBJECT, Regex("""(?i)^\s*(objet|subject|re|الموضوع)\s*[:：\-–]\s*""")),
        Label(FieldKey.REFERENCE, Regex("""(?i)^\s*(n°\s*r[ée]f(?:[ée]rence)?|r[ée]f[ée]rence|r[ée]f\.?|ref(?:erence)?\.?|رقم المرجع|المرجع|الرقم)\s*[:：\-–]?\s*""")),
        Label(FieldKey.DATE, Regex("""(?i)^\s*(date|le|fait le|التاريخ|بتاريخ)\s*[:：\-–]\s*""")),
        Label(FieldKey.SENDER, Regex("""(?i)^\s*(from|de la part de|exp[ée]diteur|[ée]metteur|de|المرسل|من طرف)\s*[:：]\s*""")),
        Label(FieldKey.RECIPIENT, Regex("""(?i)^\s*(to|destinataire|[àa]|إلى|الى|المرسل إليه)\s*[:：]\s*""")),
        Label(FieldKey.PHONE, Regex("""(?i)^\s*(t[ée]l[ée]phone|t[ée]l\.?|phone|mobile|portable|رقم الهاتف|الهاتف|الجوال)\s*[:：\-–]?\s*""")),
        Label(FieldKey.EMAIL, Regex("""(?i)^\s*(e-?mail|courriel|البريد الإلكتروني|البريد)\s*[:：\-–]?\s*""")),
        Label(FieldKey.ADDRESS, Regex("""(?i)^\s*(adresse|address|العنوان)\s*[:：\-–]\s*""")),
        Label(FieldKey.NAME, Regex("""(?i)^\s*(nom(?: et pr[ée]nom)?|name|full name|الاسم(?: واللقب)?|اللقب والاسم)\s*[:：\-–]\s*"""))
    )
    private val genericLabel = Regex("""^\s*([\p{L}][\p{L}0-9 '’°.\-]{1,30}?)\s*[:：]\s*(\S.*)$""")
    private val recipientLine = Regex("""(?i)^\s*(?:[àa]\s+(?:monsieur|madame|mesdames|messieurs|m\.|mme)|to\s+(?:mr|mrs|ms|dr|the)\b|إلى\s+(?:السيد|السيدة|حضرة|السادة)|الى\s+(?:السيد|السيدة|حضرة|السادة))""")
    private val date = Regex("""\b\d{1,2}[/.\-]\d{1,2}[/.\-](?:\d{4}|\d{2})\b|\b\d{4}-\d{2}-\d{2}\b|\b\d{1,2}(?:er)?\s+(?:janvier|f[ée]vrier|mars|avril|mai|juin|juillet|ao[uû]t|septembre|octobre|novembre|d[ée]cembre|january|february|march|april|may|june|july|august|september|october|november|december)\s+\d{4}\b|\b(?:january|february|march|april|may|june|july|august|september|october|november|december)\s+\d{1,2},?\s+\d{4}\b|\b\d{1,2}\s+(?:جانفي|يناير|فيفري|فبراير|مارس|أفريل|أبريل|ماي|مايو|جوان|يونيو|جويلية|يوليو|أوت|أغسطس|سبتمبر|أكتوبر|نوفمبر|ديسمبر)\s+\d{4}""", RegexOption.IGNORE_CASE)
    private val email = Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""")
    private val phone = Regex("""(?<![\w])(?:\+|00)?\d[\d .\-]{7,16}\d(?![\w])""")
    private val reference = Regex("""\b[A-Z]{1,6}[\-/][0-9]{2,6}(?:[\-/][0-9A-Z]{1,6}){0,3}\b|\bN°\s*[0-9][0-9/\-]{1,15}\b""")

    private val signature = Regex("""(?i)\b(signature|sign[ée]|cachet|stamp|seal|visa)\b|التوقيع|الإمضاء|الختم|ختم""")
    private val protectedKinds = listOf(
        "identity" to Regex("""(?i)\b(passport|passeport|carte nationale d['’]identit[ée]|carte d['’]identit[ée]|identity card|driving licen[cs]e|permis de conduire)\b|جواز السفر|بطاقة التعريف|بطاقة الهوية|رخصة السياقة"""),
        "diploma" to Regex("""(?i)\b(diploma|dipl[ôo]me|transcript of records|relev[ée] de notes)\b|شهادة البكالوريا|شهادة الليسانس|شهادة الماستر|كشف النقاط"""),
        "civil_status" to Regex("""(?i)\b(birth certificate|acte de naissance|extrait de naissance|marriage certificate|acte de mariage)\b|شهادة الميلاد|عقد الميلاد|عقد الزواج"""),
        "financial" to Regex("""(?i)\b(bank statement|relev[ée] bancaire|bulletin de paie|payslip|pay slip|receipt|re[çc]u de paiement)\b|كشف حساب|كشف الراتب|وصل الدفع""")
    )

    fun normalize(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    fun protectedKind(text: String): String? = protectedKinds.firstOrNull { it.second.containsMatchIn(text) }?.first
    fun mentionsSignature(text: String): Boolean = signature.containsMatchIn(text)

    fun detect(blocks: List<DocBlock>): List<ExtractedField> {
        val out = ArrayList<ExtractedField>()
        fun add(key: FieldKey, label: String, block: DocBlock, start: Int, value: String) {
            val v = value.trim().trimEnd(',', ';')
            if (v.isEmpty()) return
            val s = block.text.indexOf(v, start).takeIf { it >= 0 } ?: return
            if (out.any { it.block == block.index && it.start == s }) return
            out += ExtractedField(key, label.trim(), v, block.index, s, block.uncertain || (block.confidence ?: 1f) < 0.7f)
        }
        for (b in blocks) {
            if (b.type == BlockType.TABLE) continue
            // Each line of a block on its own (a block can hold "Tél : ...\nEmail : ...").
            var lineStart = 0
            for (line in b.text.split('\n')) {
                val labelled = labels.firstNotNullOfOrNull { l -> l.regex.find(line)?.let { l to it } }
                if (labelled != null) {
                    val (l, m) = labelled
                    add(l.key, m.groupValues[1], b, lineStart + m.range.last + 1, line.substring(m.range.last + 1))
                } else if (recipientLine.containsMatchIn(line)) {
                    add(FieldKey.RECIPIENT, "", b, lineStart, line)
                } else {
                    genericLabel.find(line)?.let { m ->
                        val label = m.groupValues[1]
                        // Not a sentence with a colon: a short label and a short value.
                        if (label.split(' ').size <= 4 && m.groupValues[2].length <= 120) add(FieldKey.OTHER, label, b, lineStart + m.groups[2]!!.range.first, m.groupValues[2])
                    }
                }
                lineStart += line.length + 1
            }
            // Values recognized by their form, wherever they are.
            for (m in date.findAll(b.text)) if (out.none { it.block == b.index && m.range.first in it.start until it.start + it.value.length && it.key == FieldKey.DATE }) add(FieldKey.DATE, "", b, m.range.first, m.value)
            for (m in email.findAll(b.text)) if (out.none { it.block == b.index && it.key == FieldKey.EMAIL && it.value.contains(m.value) }) add(FieldKey.EMAIL, "", b, m.range.first, m.value)
            for (m in reference.findAll(b.text)) if (out.none { it.block == b.index && it.value.contains(m.value) }) add(FieldKey.REFERENCE, "", b, m.range.first, m.value)
            for (m in phone.findAll(b.text)) {
                val digits = m.value.count { it.isDigit() }
                if (digits in 9..15 && !date.matches(m.value.trim()) && out.none { it.block == b.index && it.value.contains(m.value.trim()) }) add(FieldKey.PHONE, "", b, m.range.first, m.value)
            }
        }
        return out.sortedWith(compareBy({ it.block }, { it.start }))
    }

    fun analyze(sourceKind: String, pages: Int, blocks: List<DocBlock>, scanned: Boolean): DocumentAnalysis {
        val all = blocks.joinToString("\n") { b -> if (b.type == BlockType.TABLE) b.rows.joinToString("\n") { it.joinToString(" ") } else b.text }
        val rtl = blocks.count { it.rtl } * 2 > blocks.size
        return DocumentAnalysis(sourceKind, pages, blocks, detect(blocks), rtl, mentionsSignature(all), protectedKind(all), scanned)
    }
}

/**
 * Understands the user's changes locally (Arabic, French, English): "change the sender to X",
 * "Date: 20/10/2026", « remplace la référence par REF-2026-020 », "غيّر التاريخ إلى ...", or an exact
 * text: replace "old" with "new". Several changes can be given on separate lines or joined by
 * "and" / "et" / "و". Nothing is invented: what cannot be read is reported.
 */
object ChangeRequestParser {
    private val synonyms: List<Pair<FieldKey, List<String>>> = listOf(
        FieldKey.RECIPIENT to listOf("recipient", "addressee", "destinataire", "المرسل إليه", "المستلم", "الجهة المستقبلة"),
        FieldKey.SENDER to listOf("sender", "expéditeur", "expediteur", "émetteur", "المرسل", "المُرسل"),
        FieldKey.SUBJECT to listOf("subject", "objet", "الموضوع"),
        FieldKey.REFERENCE to listOf("reference number", "reference", "ref", "numéro de référence", "référence", "réf", "رقم المرجع", "المرجع"),
        FieldKey.PHONE to listOf("phone number", "telephone", "phone", "numéro de téléphone", "téléphone", "tel", "tél", "رقم الهاتف", "الهاتف"),
        FieldKey.EMAIL to listOf("e-mail", "email", "courriel", "البريد الإلكتروني"),
        FieldKey.ADDRESS to listOf("address", "adresse", "العنوان"),
        FieldKey.DATE to listOf("date", "التاريخ"),
        FieldKey.NAME to listOf("name", "nom", "الاسم")
    )
    private val verb = """(?:change|replace|set|update|modify|put|make|remplace[rz]?|change[rz]?|modifie[rz]?|mets|mettre|غيّر|غير|استبدل|عدّل|عدل|اجعل|ضع)"""
    private val to = """(?:to|with|by|into|=|par|en|à|a|pour|إلى|الى|ب|بـ)"""
    private val quotedPair = Regex("""["“«]([^"“”«»]+)["”»]\s*(?:->|→|to|with|by|par|en|إلى|الى|ب|بـ)\s*["“«]([^"“”«»]+)["”»]""", RegexOption.IGNORE_CASE)
    private val quotedValue = Regex("""^\s*["“«]([^"“”«»]+)["”»]""")
    private val clauseSplit = Regex("""\n|;|؛|\s+(?:and|et|ثم)\s+|\s+و(?=\s*(?:غيّر|غير|استبدل|عدّل|عدل|ال))|,\s+(?=(?:and |et |the |la |le |l['’]|$verb))""", RegexOption.IGNORE_CASE)

    private val formatPhrase = Regex("""(?i)[,;،]?\s*\b(?:in|en|as|au format|en format|format)\s+(?:pdf|word|docx)\b|[,;،]?\s*بصيغة\s+(?:pdf|word|docx|وورد)""")

    data class Parsed(val changes: List<Modification>, val format: String?, val unreadable: List<String>)

    fun parse(text: String, fields: List<ExtractedField> = emptyList()): Parsed {
        val format = when {
            Regex("""(?i)\bpdf\b""").containsMatchIn(text) -> "PDF"
            Regex("""(?i)\b(docx|word)\b|وورد""").containsMatchIn(text) -> "DOCX"
            else -> null
        }
        val changes = ArrayList<Modification>()
        // "…, in PDF" / "en Word" / "بصيغة PDF" chooses the output; it is not part of a value.
        var rest = formatPhrase.replace(text, " ")
        // Exact texts: replace "old" with "new".
        quotedPair.findAll(rest).forEach { m ->
            changes += Modification(oldText = m.groupValues[1].trim(), newValue = m.groupValues[2].trim())
            // The pair and the verb before it are consumed ("replace "a" with "b"").
            rest = Regex("""(?i)(?:$verb\s+)?""" + Regex.escape(m.value)).replace(rest, " ")
        }
        val unreadable = ArrayList<String>()
        // Labels the document itself uses ("Matricule", "Service") are field names too.
        val labels = fields.filter { it.label.isNotBlank() }.map { it.label }.distinct()
        for (clause in rest.split(clauseSplit).map { it.trim() }.filter { it.isNotEmpty() }) {
            val found = parseClause(clause, labels)
            if (found != null) changes += found
            else if (Regex("""(?i)$verb""").containsMatchIn(clause) || clause.contains(':')) unreadable += clause
        }
        return Parsed(changes, format, unreadable)
    }

    private fun parseClause(clause: String, labels: List<String>): Modification? {
        val candidates = synonyms.flatMap { (k, words) -> words.map { Triple(k, it, null as String?) } } + labels.map { Triple(FieldKey.OTHER, it, it) }
        for ((key, word, label) in candidates.sortedByDescending { it.second.length }) {
            val w = Regex.escape(word)
            val article = """(?:the\s+|le\s+|la\s+|les\s+|l['’]\s*|un\s+|une\s+|my\s+|mon\s+|ma\s+)?"""
            val patterns = listOf(
                Regex("""(?i)$verb\s+$article$w\s+$to\s+(.+)$"""),
                Regex("""(?i)^$article$w\s*(?:[:=]|$to)\s*(.+)$""")
            )
            for (p in patterns) {
                val m = p.find(clause) ?: continue
                val raw = m.groupValues[1].trim()
                val value = quotedValue.find(raw)?.groupValues?.get(1)?.trim() ?: raw.trimEnd('.', '!', '؟', '?').trim()
                if (value.isEmpty()) continue
                return Modification(key = key.takeIf { label == null }, label = label, newValue = value)
            }
        }
        return null
    }
}

/**
 * Lines "sender: ..." / "• date: ..." written by the tools with a field kind (no label in the
 * document) get the kind's name in the user's language; labels the document uses stay as written.
 */
fun localizeFieldNames(text: String, name: (FieldKey) -> String): String = text.lines().joinToString("\n") { line ->
    val m = Regex("""^(• )?([a-z]+): """).find(line) ?: return@joinToString line
    val key = FieldKey.values().firstOrNull { it.name.lowercase() == m.groupValues[2] } ?: return@joinToString line
    m.groupValues[1] + name(key) + ": " + line.substring(m.range.last + 1)
}

/** Why a change cannot be placed. */
class ChangeException(val code: String) : Exception(code)

/** Locates changes in the analysis and builds the new content. Only the requested texts change. */
object DocumentRebuilder {
    fun keyName(k: FieldKey) = k.name.lowercase()

    /** Each modification placed at its exact block and offset, or a [ChangeException]. */
    fun plan(a: DocumentAnalysis, mods: List<Modification>): List<PlannedChange> {
        if (mods.isEmpty()) throw ChangeException("no_changes")
        val out = ArrayList<PlannedChange>()
        for (m in mods) {
            if (m.oldText != null) {
                val hits = a.blocks.filter { it.type != BlockType.TABLE }.flatMap { b ->
                    generateSequence(b.text.indexOf(m.oldText)) { i -> b.text.indexOf(m.oldText, i + 1).takeIf { it >= 0 } }.takeWhile { it >= 0 }.map { b.index to it }.toList()
                }
                if (hits.isEmpty()) throw ChangeException("text_not_found:" + m.oldText)
                hits.forEach { (b, s) -> out += PlannedChange(b, s, m.oldText, m.newValue, m.oldText) }
                continue
            }
            val matching = a.fields.filter { f ->
                if (m.label != null) FieldDetector.normalize(f.label) == FieldDetector.normalize(m.label) else f.key == m.key
            }
            val name = m.label ?: keyName(m.key ?: FieldKey.OTHER)
            if (matching.isEmpty()) throw ChangeException("field_not_found:$name")
            val values = matching.map { it.value }.distinct()
            if (values.size > 1) throw ChangeException("field_ambiguous:$name:" + values.joinToString(" | "))
            matching.forEach { f -> out += PlannedChange(f.block, f.start, f.value, m.newValue, name) }
        }
        // The same text changed twice in different ways: a conflict, not a guess.
        val clash = out.groupBy { it.block to it.start }.values.firstOrNull { g -> g.map { it.newText }.distinct().size > 1 }
        if (clash != null) throw ChangeException("conflicting_changes:" + clash.first().oldText)
        return out.distinctBy { Triple(it.block, it.start, it.newText) }
    }

    /** Blocks with the changes applied (later offsets first in each block). */
    fun apply(a: DocumentAnalysis, changes: List<PlannedChange>): List<DocBlock> {
        val byBlock = changes.groupBy { it.block }
        return a.blocks.map { b ->
            val cs = byBlock[b.index] ?: return@map b
            var t = b.text
            for (c in cs.sortedByDescending { it.start }) {
                if (t.regionMatches(c.start, c.oldText, 0, c.oldText.length)) t = t.substring(0, c.start) + c.newText + t.substring(c.start + c.oldText.length)
            }
            b.copy(text = t)
        }
    }

    /** Word replacements (paragraph index, old text, occurrence in the paragraph) for a Word source. */
    fun docxReplacements(a: DocumentAnalysis, changes: List<PlannedChange>): List<com.example.agent.files.TextReplacement> = changes.mapNotNull { c ->
        val b = a.blocks.firstOrNull { it.index == c.block } ?: return@mapNotNull null
        val p = b.paragraphIndex ?: return@mapNotNull null
        val occurrence = Regex(Regex.escape(c.oldText)).findAll(b.text.substring(0, c.start)).count()
        com.example.agent.files.TextReplacement(p, c.oldText, c.newText, occurrence)
    }

    /**
     * The rebuilt document for the existing Word and PDF engines: headings start sections,
     * paragraphs and list items keep their order and direction, tables keep rows and columns.
     */
    fun wordModel(blocks: List<DocBlock>): com.example.document.model.WordDocumentModel {
        val sections = ArrayList<com.example.document.model.WordSection>()
        var heading = ""
        var paragraphs = ArrayList<String>()
        var bullets = ArrayList<String>()
        var rtl: Boolean? = null
        fun flush(table: com.example.document.model.WordTable? = null) {
            if (heading.isNotEmpty() || paragraphs.isNotEmpty() || bullets.isNotEmpty() || table != null) {
                sections += com.example.document.model.WordSection(heading, paragraphs, bullets, isRtl = rtl, table = table)
            }
            heading = ""; paragraphs = ArrayList(); bullets = ArrayList(); rtl = null
        }
        for (b in blocks) {
            when (b.type) {
                BlockType.HEADING -> { flush(); heading = b.text; rtl = b.rtl }
                BlockType.LIST_ITEM -> { bullets += b.text; if (rtl == null) rtl = b.rtl }
                // A table is a section of its own, so it stays between the paragraphs around it.
                BlockType.TABLE -> if (b.rows.isNotEmpty()) { flush(); flush(com.example.document.model.WordTable(b.rows.first(), b.rows.drop(1))) }
                else -> { paragraphs += b.text; if (rtl == null) rtl = b.rtl }
            }
        }
        flush()
        return com.example.document.model.WordDocumentModel(title = "", organization = "", sections = sections)
    }
}
