package com.example.agent.core

/**
 * A file the request can refer to: a Library document or a My Templates template. Only its
 * title and type are used here; its content is read by a tool.
 */
data class LibraryRef(val id: String, val title: String, val fileType: String, val source: FileSource = FileSource.LIBRARY) {
    /** The app-managed file id used by tools and grants. */
    val fileId: String get() = if (source == FileSource.LIBRARY) TaskFile.libraryId(id) else TaskFile.templateId(id)
}

/**
 * What the Agent understood from a request. Built locally (no AI request): the model never picks
 * tools or files; it only writes content for the steps the local planner chose.
 */
data class AgentIntent(
    val mode: AgentMode,
    /** "DOCX", "XLSX", "PPTX" or "PDF" when the request names an output format. */
    val outputFormat: String?,
    /** Files read for information, in the order they appear (at most [IntentAnalyzer.MAX_SOURCES]). */
    val sources: List<LibraryRef>,
    /** The user asked to send or share the result: needs approval. */
    val wantsShare: Boolean,
    /** A My Templates template used for formatting the new document. */
    val template: LibraryRef? = null,
    /** The one Word file the user asked to edit (edited as a new copy). */
    val editTarget: LibraryRef? = null,
    /** The user explicitly asked to replace the original with the edited copy (separate approval). */
    val replaceOriginal: Boolean = false,
    /** Several files could be meant and none was named exactly: the user picks one first. */
    val ambiguous: List<LibraryRef> = emptyList()
) {
    /** Every file the task will use, with its role (shown to the user, granted for this task only). */
    fun files(): List<TaskFile> = buildList {
        sources.forEach { add(TaskFile(it.fileId, it.source, it.title, it.fileType, FileRole.SOURCE)) }
        template?.let { add(TaskFile(it.fileId, it.source, it.title, it.fileType, FileRole.TEMPLATE)) }
        editTarget?.let { add(TaskFile(it.fileId, it.source, it.title, it.fileType, FileRole.EDIT_TARGET)) }
    }
}

/**
 * Local intent understanding (Arabic, French, English). A request becomes an Agent Mode task only
 * when it needs several tools: it names or attaches files (Library documents to read, a template
 * to format with, a Word file to edit), or asks to share the result it creates. Everything else
 * (write a letter, translate, answer, a simple table) is Quick Mode and goes straight to the
 * existing chat and document pipeline.
 *
 * Files are used only when the user attached them or named them exactly. A request that only
 * describes a file ("my annual report") with several possible matches is not guessed: the
 * candidates are returned in [AgentIntent.ambiguous] and the user picks one.
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
    private val edit = Regex("""(?i)\b(edit|modify|correct|fix|proofread|rewrite|revise|update|translate|modifie|modifier|corrige|corriger|réécri\w*|révise\w*|traduis|traduire|mets à jour)\b|عدّل|عدل|صحح|صحّح|راجع|أعد صياغة|ترجم|حدّث""")
    private val replace = Regex("""(?i)\b(replace the original|overwrite|remplace(r)? l['’]original|écrase(r)?)\b|استبدل الأصل|استبدال الأصل|استبدل الملف الأصلي""")
    private val fileWord = Regex("""(?i)\b(report|document|file|template|spreadsheet|presentation|rapport|fichier|modèle|tableur|présentation)\b|تقرير|ملف|مستند|قالب|نموذج|عرض""")

    fun analyze(
        text: String,
        library: List<LibraryRef>,
        templates: List<LibraryRef> = emptyList(),
        selected: List<LibraryRef> = emptyList()
    ): AgentIntent {
        val format = formats
            .mapNotNull { (f, r) -> r.find(text)?.let { f to it.range.first } }
            .minByOrNull { it.second }?.first
        val wantsShare = share.containsMatchIn(text)
        val wantsEdit = edit.containsMatchIn(text)
        val named = if (selected.isNotEmpty()) selected.distinctBy { it.fileId } else findSources(text, library + templates)
        var sources = named.filter { it.source == FileSource.LIBRARY }
        var template = named.firstOrNull { it.source == FileSource.MY_TEMPLATE }
        var editTarget: LibraryRef? = null
        // An edit needs exactly one Word file to edit; it is edited as a copy (the original is never changed here).
        if (wantsEdit && named.size == 1 && named[0].fileType.equals("DOCX", ignoreCase = true)) {
            editTarget = named[0]
            sources = emptyList()
            template = null
        }
        // An edit of another kind of file: its content is read and a new document is written.
        val effectiveFormat = format ?: if (wantsEdit && named.size == 1 && editTarget == null) named[0].fileType.takeIf { it in setOf("DOCX", "XLSX", "PPTX") } else null
        val ambiguous = if (named.isEmpty() && fileWord.containsMatchIn(text)) fuzzy(text, library + templates) else emptyList()
        val agent = sources.isNotEmpty() || template != null || editTarget != null || ambiguous.isNotEmpty() || (wantsShare && effectiveFormat != null)
        return AgentIntent(
            mode = if (agent) AgentMode.AGENT else AgentMode.QUICK,
            outputFormat = if (editTarget != null) "DOCX" else effectiveFormat,
            sources = sources.take(MAX_SOURCES),
            wantsShare = wantsShare && agent && editTarget == null,
            template = template,
            editTarget = editTarget,
            replaceOriginal = editTarget != null && editTarget.source == FileSource.LIBRARY && replace.containsMatchIn(text),
            ambiguous = ambiguous
        )
    }

    /** Files whose title (with or without extension) appears in [text]. */
    fun findSources(text: String, library: List<LibraryRef>): List<LibraryRef> {
        val lower = text.lowercase()
        return library.mapNotNull { ref ->
            val full = ref.title.trim().lowercase()
            val bare = full.substringBeforeLast('.', full).trim()
            val at = listOf(full, bare).filter { it.length >= 4 }.map { lower.indexOf(it) }.filter { it >= 0 }.minOrNull()
            at?.let { ref to it }
        }.sortedBy { it.second }.map { it.first }.distinctBy { it.fileId }.take(MAX_SOURCES)
    }

    private fun tokens(s: String): Set<String> =
        s.lowercase().substringBeforeLast('.').split(Regex("""[^\p{L}\p{N}]+""")).filter { it.length >= 4 }.toSet()

    /** Files sharing a significant word with the request, best first (at most 6): candidates to pick from. */
    fun fuzzy(text: String, files: List<LibraryRef>): List<LibraryRef> {
        val words = tokens(text)
        if (words.isEmpty()) return emptyList()
        return files.map { it to tokens(it.title).count { t -> t in words } }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .map { it.first }
            .take(6)
    }
}

/** Builds a bounded, ordered plan for an Agent Mode request. Pure and deterministic. */
object TaskPlanner {
    const val MAX_STEPS = 10

    object Tools {
        const val LIBRARY_READ = "library.read"
        const val FILE_READ = "file.read"
        const val TEMPLATE_INSPECT = "template.inspect"
        const val CONTENT_GENERATE = "content.generate"
        const val RESULT_VALIDATE = "result.validate"
        const val DOCUMENT_SHARE = "document.share"
        const val DOCX_PROPOSE_EDITS = "docx.propose_edits"
        const val DOCX_WRITE_COPY = "docx.write_copy"
        const val FILE_REPLACE_ORIGINAL = "file.replace_original"
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
        val target = intent.editTarget
        if (target != null) {
            steps += AgentStep(id = "read_1", toolId = Tools.FILE_READ, input = mapOf("fileId" to target.fileId), executionId = newId())
            steps += AgentStep(id = "propose", toolId = Tools.DOCX_PROPOSE_EDITS, input = mapOf("fileId" to target.fileId, "request" to request), dependsOn = listOf("read_1"), executionId = newId())
            steps += AgentStep(id = "write_copy", toolId = Tools.DOCX_WRITE_COPY, input = mapOf("fileId" to target.fileId), dependsOn = listOf("propose"), approval = ApprovalState.PENDING, executionId = newId())
            steps += AgentStep(id = "validate", toolId = Tools.RESULT_VALIDATE, input = mapOf("format" to "DOCX"), dependsOn = listOf("write_copy"), executionId = newId())
            if (intent.replaceOriginal) {
                steps += AgentStep(id = "replace", toolId = Tools.FILE_REPLACE_ORIGINAL, input = mapOf("fileId" to target.fileId), dependsOn = listOf("validate"), approval = ApprovalState.PENDING, executionId = newId())
            }
        } else {
            val reads = intent.sources.take(IntentAnalyzer.MAX_SOURCES).mapIndexed { i, ref ->
                AgentStep(id = "read_${i + 1}", toolId = Tools.FILE_READ, input = mapOf("fileId" to ref.fileId), executionId = newId())
            }
            steps += reads
            val inspect = intent.template?.let {
                AgentStep(id = "template", toolId = Tools.TEMPLATE_INSPECT, input = mapOf("fileId" to it.fileId), executionId = newId())
            }
            inspect?.let { steps += it }
            steps += AgentStep(
                id = "generate",
                toolId = Tools.CONTENT_GENERATE,
                input = buildMap {
                    put("request", request)
                    intent.outputFormat?.let { put("format", it) }
                    intent.template?.let { put("templateFileId", it.fileId) }
                },
                dependsOn = reads.map { it.id } + listOfNotNull(inspect?.id),
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
        }
        check(steps.size <= MAX_STEPS) { "Plan too long" }
        return AgentTask(
            id = taskId, sessionId = sessionId, request = request, createdAt = now, mode = AgentMode.AGENT,
            status = TaskStatus.PENDING, steps = steps, outputFormat = intent.outputFormat, language = language,
            files = intent.files()
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
