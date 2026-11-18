package com.example.agent.core

/** A Library document the request can refer to (title only; its content is read by a tool). */
data class LibraryRef(val id: String, val title: String, val fileType: String)

/**
 * What the Agent understood from a request. Built locally (no AI request): the model never picks
 * tools; it only writes content for the steps the local planner chose.
 */
data class AgentIntent(
    val mode: AgentMode,
    /** "DOCX", "XLSX", "PPTX" or "PDF" when the request names an output format. */
    val outputFormat: String?,
    /** Library documents named in the request, in the order they appear (at most [IntentAnalyzer.MAX_SOURCES]). */
    val sources: List<LibraryRef>,
    /** The user asked to send or share the result: needs approval. */
    val wantsShare: Boolean
)

/**
 * Local intent understanding (Arabic, French, English). A request becomes an Agent Mode task only
 * when it needs several tools: it names Library documents to read, or asks to share the result it
 * creates. Everything else (write a letter, translate, answer, a simple table) is Quick Mode and
 * goes straight to the existing chat and document pipeline.
 */
object IntentAnalyzer {
    const val MAX_SOURCES = 5

    private val formats = listOf(
        "XLSX" to Regex("""(?i)\b(excel|xlsx|spreadsheet|workbook|tableur|classeur)\b|إكسل|اكسل|جدول بيانات"""),
        "PPTX" to Regex("""(?i)\b(powerpoint|pptx|slides?|presentation|slide deck|diaporama|présentation)\b|بوربوينت|باوربوينت|عرض تقديمي|شرائح"""),
        "PDF" to Regex("""(?i)\bpdf\b"""),
        "DOCX" to Regex("""(?i)\b(docx|word (document|file|report)|(document|fichier|rapport) word)\b|ملف وورد|مستند وورد|وورد""")
    )
    private val share = Regex("""(?i)\b(share|send|email|partager|partage|envoyer|envoie)\b|شارك|أرسل|ارسل|مشاركة""")

    fun analyze(text: String, library: List<LibraryRef>): AgentIntent {
        val format = formats
            .mapNotNull { (f, r) -> r.find(text)?.let { f to it.range.first } }
            .minByOrNull { it.second }?.first
        val sources = findSources(text, library)
        val wantsShare = share.containsMatchIn(text)
        val agent = sources.isNotEmpty() || (wantsShare && format != null)
        return AgentIntent(if (agent) AgentMode.AGENT else AgentMode.QUICK, format, sources, wantsShare && agent)
    }

    /** Library documents whose title (with or without extension) appears in [text]. */
    fun findSources(text: String, library: List<LibraryRef>): List<LibraryRef> {
        val lower = text.lowercase()
        return library.mapNotNull { ref ->
            val full = ref.title.trim().lowercase()
            val bare = full.substringBeforeLast('.', full).trim()
            val at = listOf(full, bare).filter { it.length >= 4 }.map { lower.indexOf(it) }.filter { it >= 0 }.minOrNull()
            at?.let { ref to it }
        }.sortedBy { it.second }.map { it.first }.distinctBy { it.id }.take(MAX_SOURCES)
    }
}

/** Builds a bounded, ordered plan for an Agent Mode request. Pure and deterministic. */
object TaskPlanner {
    const val MAX_STEPS = 8

    object Tools {
        const val LIBRARY_READ = "library.read"
        const val CONTENT_GENERATE = "content.generate"
        const val RESULT_VALIDATE = "result.validate"
        const val DOCUMENT_SHARE = "document.share"
    }

    fun plan(
        taskId: String,
        sessionId: String,
        request: String,
        intent: AgentIntent,
        language: String,
        now: Long,
        newId: () -> String
    ): AgentTask {
        val steps = ArrayList<AgentStep>()
        val reads = intent.sources.take(IntentAnalyzer.MAX_SOURCES).mapIndexed { i, ref ->
            AgentStep(id = "read_${i + 1}", toolId = Tools.LIBRARY_READ, input = mapOf("documentId" to ref.id), executionId = newId())
        }
        steps += reads
        steps += AgentStep(
            id = "generate",
            toolId = Tools.CONTENT_GENERATE,
            input = buildMap {
                put("request", request)
                intent.outputFormat?.let { put("format", it) }
            },
            dependsOn = reads.map { it.id },
            executionId = newId()
        )
        steps += AgentStep(
            id = "validate",
            toolId = Tools.RESULT_VALIDATE,
            input = buildMap { intent.outputFormat?.let { put("format", it) } },
            dependsOn = listOf("generate"),
            executionId = newId()
        )
        if (intent.wantsShare) {
            steps += AgentStep(
                id = "share",
                toolId = Tools.DOCUMENT_SHARE,
                dependsOn = listOf("validate"),
                approval = ApprovalState.PENDING,
                executionId = newId()
            )
        }
        check(steps.size <= MAX_STEPS) { "Plan too long" }
        return AgentTask(
            id = taskId, sessionId = sessionId, request = request, createdAt = now, mode = AgentMode.AGENT,
            status = TaskStatus.PENDING, steps = steps, outputFormat = intent.outputFormat, language = language
        )
    }

    /** Steps in an order that respects every dependency (Kahn's algorithm; stable). Throws on a cycle or an unknown dependency. */
    fun executionOrder(steps: List<AgentStep>): List<AgentStep> {
        val ids = steps.map { it.id }.toSet()
        steps.forEach { s -> s.dependsOn.forEach { require(it in ids) { "Unknown dependency $it of ${s.id}" } } }
        val done = LinkedHashSet<String>()
        val out = ArrayList<AgentStep>()
        while (out.size < steps.size) {
            val next = steps.firstOrNull { it.id !in done && done.containsAll(it.dependsOn) }
                ?: throw IllegalArgumentException("Dependency cycle")
            done += next.id
            out += next
        }
        return out
    }
}

/**
 * Deterministic figures for tables found in source documents (tab, semicolon or pipe separated).
 * The Agent gives these to the model as verified numbers, so totals and averages in a report come
 * from application code, not from the model's arithmetic.
 */
object TableStats {
    data class ColumnStats(val name: String, val count: Int, val sum: Double, val min: Double, val max: Double) {
        val mean: Double get() = if (count == 0) 0.0 else sum / count
    }

    private val number = Regex("""^[-+]?\d{1,3}([ , ]?\d{3})*([.,]\d+)?$|^[-+]?\d+([.,]\d+)?$""")

    fun parseNumber(cell: String): Double? {
        val t = cell.trim().removeSuffix("%").trim()
        if (t.isEmpty() || !number.matches(t)) return null
        val lastSep = maxOf(t.lastIndexOf('.'), t.lastIndexOf(','))
        val normalized = if (lastSep >= 0 && t.length - lastSep - 1 in 1..2) {
            t.substring(0, lastSep).replace(Regex("[ ,. ]"), "") + "." + t.substring(lastSep + 1)
        } else t.replace(Regex("[ ,. ]"), "")
        return normalized.toDoubleOrNull()
    }

    /** Numeric columns of the first table in [text] (a header row then at least two data rows). */
    fun columns(text: String): List<ColumnStats> {
        val rows = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
            .map { line -> line.trim('|').split('\t', ';', '|').map { it.trim() } }
            .filter { it.size >= 2 }
        if (rows.size < 3) return emptyList()
        val width = rows.groupingBy { it.size }.eachCount().maxByOrNull { it.value }!!.key
        val table = rows.filter { it.size == width }
        if (table.size < 3) return emptyList()
        val header = table.first()
        val data = table.drop(1)
        return (0 until width).mapNotNull { c ->
            val values = data.mapNotNull { parseNumber(it[c]) }
            if (values.size < 2 || values.size < data.size / 2) return@mapNotNull null
            ColumnStats(header[c].ifBlank { "#${c + 1}" }, values.size, values.sum(), values.min(), values.max())
        }
    }

    fun describe(stats: List<ColumnStats>): String = stats.joinToString("\n") {
        "${it.name}: count=${it.count}, sum=${fmt(it.sum)}, mean=${fmt(it.mean)}, min=${fmt(it.min)}, max=${fmt(it.max)}"
    }

    private fun fmt(v: Double): String =
        if (v == Math.rint(v) && kotlin.math.abs(v) < 1e15) v.toLong().toString() else String.format(java.util.Locale.ROOT, "%.2f", v)
}
