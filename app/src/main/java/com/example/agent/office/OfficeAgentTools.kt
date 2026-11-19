package com.example.agent.office

import com.example.agent.core.AgentMode
import com.example.agent.core.AgentStep
import com.example.agent.core.AgentTask
import com.example.agent.core.AgentTool
import com.example.agent.core.ApprovalState
import com.example.agent.core.FileRole
import com.example.agent.core.LibraryRef
import com.example.agent.core.TaskFile
import com.example.agent.core.TaskStatus
import com.example.agent.core.ToolCall
import com.example.agent.core.ToolException
import com.example.agent.core.ToolPermission
import com.example.agent.core.ToolSpec
import com.example.agent.core.ValidationOutcome
import com.example.agent.files.AgentFileAccess
import com.example.agent.files.DocxCopyEditor
import com.example.agent.files.FileKind
import com.example.agent.files.LibraryImporter
import com.example.agent.files.OfficeBlock
import com.example.agent.files.OfficeReadException
import com.example.agent.files.OoxmlReader
import com.example.agent.thesis.SourceExtractor
import com.example.core.storage.DocumentFileNames
import com.example.data.db.SmartBubbleDatabase
import com.example.data.model.DocumentEntity
import com.example.extraction.Alignment
import com.example.extraction.ExtractedBlock
import com.example.extraction.ExtractedDocument
import com.example.extraction.ScriptDetector
import com.example.template.domain.TemplateProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/** Document Reformatting: the Agent's tools for analyzing a source document and writing an updated copy. */
object OfficeTools {
    const val ANALYZE = "office.analyze"
    const val PREPARE = "office.prepare"
    const val WRITE = "office.write"
    const val VALIDATE = "office.validate"

    /** PDF pages analyzed at most. */
    const val MAX_PAGES = 20
    const val MAX_TEXT_CHARS = 60_000

    fun tag(executionId: String) = "agent-office:$executionId"
}

/** Blocks of an extracted PDF or image (the extraction engine's structure: headings, paragraphs, lists, tables). */
fun blocksOf(doc: ExtractedDocument): List<DocBlock> {
    val out = ArrayList<DocBlock>()
    for (page in doc.pages) {
        val pageNo = (page.sourcePage ?: page.index) + 1
        for (b in page.blocks) {
            val region = b.region
            val uncertain = region?.needsReview == true
            val confidence = region?.confidence
            val block = when (b) {
                is ExtractedBlock.PageNumber -> null
                is ExtractedBlock.Heading -> DocBlock(out.size, pageNo, BlockType.HEADING, b.text, b.rtl, bold = true, confidence = confidence, uncertain = uncertain)
                is ExtractedBlock.Paragraph -> DocBlock(
                    out.size, pageNo, BlockType.PARAGRAPH, b.text, b.rtl,
                    align = when (b.alignment) { Alignment.CENTER -> "center"; Alignment.END -> "end"; Alignment.JUSTIFIED -> "justify"; else -> "" },
                    bold = b.bold, confidence = confidence, uncertain = uncertain || b.uncertainWords.isNotEmpty()
                )
                is ExtractedBlock.ListItem -> DocBlock(out.size, pageNo, BlockType.LIST_ITEM, b.text, b.rtl, confidence = confidence, uncertain = uncertain)
                is ExtractedBlock.Caption -> DocBlock(out.size, pageNo, BlockType.CAPTION, b.text, b.rtl, confidence = confidence, uncertain = uncertain)
                is ExtractedBlock.Table -> {
                    val width = b.columns
                    val rows = b.rows.map { r ->
                        val cells = ArrayList<String>()
                        for (c in r) { cells += c.text; repeat(c.colSpan - 1) { cells += "" } }
                        (cells + List((width - cells.size).coerceAtLeast(0)) { "" }).take(width)
                    }
                    DocBlock(out.size, pageNo, BlockType.TABLE, rows.joinToString("\n") { it.joinToString(" | ") }, b.rtl,
                        uncertain = b.uncertain || b.cellsToReview > 0, rows = rows)
                }
            }
            if (block != null && (block.text.isNotBlank() || block.rows.isNotEmpty())) out += block
        }
    }
    return out
}

/** Blocks of a Word document (body paragraphs keep their index, so a change touches only that paragraph). */
fun blocksOf(content: com.example.agent.files.OfficeContent): List<DocBlock> {
    val out = ArrayList<DocBlock>()
    for (b in content.blocks) {
        when (b) {
            is OfficeBlock.Heading -> out += DocBlock(out.size, 1, BlockType.HEADING, b.text, ScriptDetector.isRtl(b.text), bold = true, paragraphIndex = b.paragraphIndex)
            is OfficeBlock.Paragraph -> if (b.text.isNotBlank()) out += DocBlock(out.size, 1, BlockType.PARAGRAPH, b.text, ScriptDetector.isRtl(b.text), paragraphIndex = b.paragraphIndex)
            is OfficeBlock.Table -> out += DocBlock(out.size, 1, BlockType.TABLE, b.rows.joinToString("\n") { it.joinToString(" | ") },
                ScriptDetector.isRtl(b.rows.flatten().joinToString(" ")), rows = b.rows)
            else -> Unit
        }
    }
    return out
}

private fun requireGranted(call: ToolCall, fileId: String) {
    if (call.task.file(fileId) == null) throw ToolException("file_not_authorized")
    if (!call.task.isGranted(fileId)) throw ToolException("file_access_revoked")
}

/** The detected information as a short table for the conversation and the approval card. */
fun fieldsSummary(a: DocumentAnalysis, maxRows: Int = 24): String = a.fields.take(maxRows).joinToString("\n") { f ->
    val name = f.label.ifBlank { f.key.name.lowercase() }
    "• $name: ${f.value}" + if (f.uncertain) " (?)" else ""
}

/**
 * Analyzes a source document (Library or phone copy) with the existing engines: Word text and
 * tables, PDF text layer, PaddleOCR for scans and images. Read-only; the source's fingerprint is
 * kept so a later change can check the document is still the one analyzed.
 */
class OfficeAnalyzeTool(private val access: AgentFileAccess, private val extractor: SourceExtractor) : AgentTool {
    override val spec = ToolSpec(
        id = OfficeTools.ANALYZE, name = "Analyze document",
        description = "Reads an authorized source document's content, layout blocks and fields (sender, recipient, subject, dates, references, phone numbers...). Read-only.",
        requiredInputs = setOf("fileId"),
        outputs = setOf("title", "analysis", "summary", "fields", "uncertain", "pages", "sourceSha", "protected", "signature", "scanned"),
        permissions = setOf(ToolPermission.READ_LIBRARY)
    )

    override suspend fun execute(call: ToolCall): Map<String, String> {
        val fileId = call.step.input.getValue("fileId")
        requireGranted(call, fileId)
        val r = access.resolve(fileId)
        val file = r.file
        val sha = withContext(Dispatchers.IO) { LibraryImporter.sha256(file) }
        val (kind, pages, blocks, scanned) = when (r.kind) {
            FileKind.DOCX -> {
                val content = withContext(Dispatchers.IO) {
                    try { OoxmlReader.read(file) } catch (e: OfficeReadException) {
                        throw ToolException(if (e.reason.startsWith("part_too_large")) "file_too_large" else "file_damaged")
                    } catch (e: java.util.zip.ZipException) { throw ToolException("file_damaged") }
                }
                Quad("DOCX", 1, blocksOf(content), false)
            }
            FileKind.PDF, FileKind.PNG, FileKind.JPEG, FileKind.WEBP -> {
                val isPdf = r.kind == FileKind.PDF
                val doc = try { extractor(file, isPdf, if (isPdf) (0 until OfficeTools.MAX_PAGES).toSet() else null) } catch (e: CancellationException) { throw e } catch (e: ToolException) { throw e } catch (e: Exception) {
                    throw ToolException("extraction_failed")
                }
                val read = doc.pages.filter { it.error == null }
                if (read.isEmpty()) throw ToolException("document_empty")
                Quad(if (isPdf) "PDF" else "IMAGE", read.size, blocksOf(ExtractedDocument(read)),
                    read.any { it.source != com.example.extraction.PageSource.PDF_TEXT })
            }
            else -> throw ToolException("unsupported_format")
        }
        if (blocks.isEmpty()) throw ToolException("document_empty")
        if (blocks.sumOf { it.text.length } > OfficeTools.MAX_TEXT_CHARS) throw ToolException("document_too_large_to_edit")
        val analysis = FieldDetector.analyze(kind, pages, blocks, scanned)
        return mapOf(
            "title" to r.name, "analysis" to OfficeCodec.encode(analysis), "summary" to fieldsSummary(analysis),
            "fields" to analysis.fields.size.toString(), "uncertain" to analysis.uncertainFields.toString(), "pages" to pages.toString(),
            "sourceSha" to sha, "protected" to (analysis.protectedKind ?: ""), "signature" to analysis.mentionsSignature.toString(),
            "scanned" to scanned.toString()
        )
    }

    private data class Quad(val kind: String, val pages: Int, val blocks: List<DocBlock>, val scanned: Boolean)
}

/**
 * Turns the user's request into exact changes (field by field, or exact texts), located in the
 * analysis. Writes nothing. Identity documents, diplomas, civil-status and financial records are
 * refused: changing them could deceive a third party.
 */
class OfficePrepareTool : AgentTool {
    override val spec = ToolSpec(
        id = OfficeTools.PREPARE, name = "Prepare changes",
        description = "Locates the requested changes in the analyzed document and shows them as old -> new. Writes nothing.",
        requiredInputs = setOf("request"), outputs = setOf("changes", "preview", "format", "uncertain", "unread", "count"),
        permissions = setOf(ToolPermission.READ_LIBRARY)
    )

    override suspend fun execute(call: ToolCall): Map<String, String> {
        val analyzed = call.dependencies.values.firstOrNull { it["analysis"] != null } ?: throw ToolException("no_analysis")
        val a = OfficeCodec.decode(analyzed.getValue("analysis"))
        a.protectedKind?.let { throw ToolException("protected_document:$it") }
        val parsed = ChangeRequestParser.parse(call.step.input.getValue("request"), a.fields)
        if (parsed.changes.isEmpty()) throw ToolException("no_changes_understood")
        val changes = try { DocumentRebuilder.plan(a, parsed.changes) } catch (e: ChangeException) { throw ToolException(e.code) }
        val touched = changes.any { c -> a.fields.any { f -> f.block == c.block && f.start == c.start && f.uncertain } || a.blocks.firstOrNull { it.index == c.block }?.uncertain == true }
        // Word unless the user asks for PDF: the updated document stays editable.
        val format = parsed.format ?: "DOCX"
        return mapOf(
            "changes" to OfficeCodec.encodeChanges(changes),
            "preview" to changes.distinctBy { it.oldText to it.newText }.joinToString("\n") { "${it.field}: ${it.oldText} → ${it.newText}" },
            "format" to format, "uncertain" to touched.toString(), "unread" to parsed.unreadable.joinToString(" | "),
            "count" to changes.size.toString()
        )
    }
}

/**
 * Writes the updated document as a NEW Library document (the source is never written):
 * - Word source, Word output: the source's own package with only the changed texts replaced, run
 *   by run (styles, fonts, tables, headers, footers, images and page setup are the original ones).
 * - Other cases: the document rebuilt from its blocks with the existing Word / PDF engines, using
 *   the layout measured from the source (page size, margins, fonts, colours) when it is a PDF.
 * The new document is tagged with this step's execution id, so a restart finds it.
 */
class OfficeWriteTool(
    private val access: AgentFileAccess,
    private val database: SmartBubbleDatabase,
    private val copyLabel: (String) -> String,
    private val clock: () -> Long = System::currentTimeMillis
) : AgentTool {
    override val spec = ToolSpec(
        id = OfficeTools.WRITE, name = "Create updated document",
        description = "Creates the updated document as a new Library document after approval. The source is not changed.",
        optionalInputs = setOf("fileId"), outputs = setOf("documentId", "format", "changed", "skipped", "rebuilt"),
        permissions = setOf(ToolPermission.READ_LIBRARY, ToolPermission.WRITE_LIBRARY), sideEffect = true, needsApproval = true
    )

    private fun tag(call: ToolCall) = OfficeTools.tag(call.step.executionId)

    override suspend fun execute(call: ToolCall): Map<String, String> {
        val analyzeStep = call.task.steps.firstOrNull { it.output["analysis"] != null } ?: throw ToolException("no_analysis")
        val fileId = analyzeStep.input.getValue("fileId")
        requireGranted(call, fileId)
        if (!access.isGranted(call.task.id, fileId)) throw ToolException("file_access_revoked")
        val prepared = call.dependencies.values.firstOrNull { it["changes"] != null } ?: throw ToolException("no_changes")
        val a = OfficeCodec.decode(analyzeStep.output.getValue("analysis"))
        val changes = OfficeCodec.decodeChanges(prepared["changes"])
        if (changes.isEmpty()) throw ToolException("no_changes")
        val format = prepared["format"]?.takeIf { it == "PDF" || it == "DOCX" } ?: "DOCX"
        val r = access.resolve(fileId)
        return withContext(Dispatchers.IO) {
            // The document must still be the one that was analyzed and shown.
            if (LibraryImporter.sha256(r.file) != analyzeStep.output["sourceSha"]) throw ToolException("source_changed")
            val base = DocumentFileNames.stripDocumentExtension(r.name)
            val label = copyLabel(base)
            val ext = if (format == "PDF") "pdf" else "docx"
            val target = DocumentFileNames.createUniqueFile(access.libraryDir, label, ext)
            try {
                var skipped = emptyList<Int>()
                val rebuilt: Boolean
                if (a.sourceKind == "DOCX" && format == "DOCX") {
                    skipped = DocxCopyEditor.writeCopyReplacing(r.file, target, DocumentRebuilder.docxReplacements(a, changes)).skipped
                    if (skipped.isNotEmpty()) throw ToolException("change_not_applied")
                    rebuilt = false
                } else {
                    val model = DocumentRebuilder.wordModel(DocumentRebuilder.apply(a, changes))
                    val profile = layoutOf(r.file, a)
                    if (format == "PDF") com.example.document.pdf.PdfEngine().generatePdf(model, target, profile)
                    else com.example.document.docx.DocxEngine().generateDocxDetailed(model, target, profile)
                    rebuilt = true
                }
                val text = OfficeOutput.text(target, format)
                val doc = DocumentEntity(
                    id = java.util.UUID.randomUUID().toString(), title = "$label.$ext", fileType = format,
                    filePath = target.absolutePath, fileSize = target.length(),
                    wordCount = text.split(Regex("\\s+")).count { it.isNotBlank() }, pageOrSlideCount = 0,
                    providerUsed = "Agent", createdAt = clock(),
                    summary = "Agent • ${(target.length() + 1023) / 1024} KB", textContent = text.take(LibraryImporter.MAX_TEXT_CHARS),
                    isOrchestratorVerified = false, sourceRequest = tag(call)
                )
                withContext(NonCancellable) { database.documentDao().insertDocument(doc) }
                mapOf("documentId" to doc.id, "format" to format, "changed" to changes.size.toString(), "skipped" to skipped.joinToString(","), "rebuilt" to rebuilt.toString())
            } catch (e: Exception) {
                withContext(NonCancellable) { target.delete() }
                if (e is CancellationException || e is ToolException) throw e
                throw ToolException("write_failed")
            }
        }
    }

    /** Page size, margins, fonts and colours measured from a PDF source (the existing PDF analyzer), else the default layout. */
    private fun layoutOf(file: File, a: DocumentAnalysis): TemplateProfile {
        val measured = if (a.sourceKind == "PDF" && !a.scanned) runCatching {
            com.example.template.pdf.PdfTemplateAnalyzer.analyze(file).toProfile("agent-source", "source", file.name)
        }.getOrNull() else null
        val docx = if (a.sourceKind == "DOCX") runCatching {
            com.example.template.docx.DocxTemplateAnalyzer.analyze(file, "agent-source", "source")
        }.getOrNull() else null
        return (measured ?: docx ?: TemplateProfile.defaultProfile()).copy(rtl = a.rtl)
    }

    override suspend fun reconcile(call: ToolCall): Map<String, String>? = withContext(Dispatchers.IO) {
        database.documentDao().getAllDocuments().first().firstOrNull { it.sourceRequest == tag(call) }
            ?.let { mapOf("documentId" to it.id, "format" to it.fileType, "changed" to "", "skipped" to "", "rebuilt" to "") }
    }
}

/** Text of a produced document, read back with the app's own readers. */
object OfficeOutput {
    fun text(file: File, format: String): String = try {
        if (format == "PDF") {
            val pages = com.example.extraction.PdfTextExtraction.readPages(com.example.template.pdf.PdfDocumentReader.open(file))
            pages.mapNotNull { it.second }.joinToString("\n") { com.example.extraction.ExtractionText.page(it) }
        } else OoxmlReader.read(file).toText(OfficeTools.MAX_TEXT_CHARS * 2)
    } catch (e: Exception) { "" }

    fun squash(s: String) = s.replace(Regex("\\s+"), " ").trim()
}

/**
 * Re-reads the new document: it opens, every new value is in it, the unchanged content is still
 * there (for a Word source: every other paragraph identical), and the source is unchanged. When
 * the result cannot be confirmed, the new document is removed (it is never kept as final).
 */
class OfficeValidateTool(private val access: AgentFileAccess, private val database: SmartBubbleDatabase) : AgentTool {
    override val spec = ToolSpec(
        id = OfficeTools.VALIDATE, name = "Check updated document",
        description = "Checks the new document: readable, requested changes present, other content kept, source unchanged.",
        outputs = setOf("outcome", "issues", "documentId", "removed"),
        permissions = setOf(ToolPermission.READ_LIBRARY, ToolPermission.WRITE_LIBRARY), sideEffect = true
    )

    override suspend fun execute(call: ToolCall): Map<String, String> = withContext(Dispatchers.IO) {
        val written = call.dependencies.values.firstOrNull { it["documentId"] != null } ?: throw ToolException("not_written")
        val analyzeStep = call.task.steps.first { it.output["analysis"] != null }
        val a = OfficeCodec.decode(analyzeStep.output.getValue("analysis"))
        val changes = OfficeCodec.decodeChanges(call.task.steps.firstNotNullOfOrNull { it.output["changes"] })
        val doc = database.documentDao().getDocumentById(written.getValue("documentId")) ?: throw ToolException("document_not_found")
        val out = File(doc.filePath)
        val issues = ArrayList<String>()
        val warnings = ArrayList<String>()
        val text = if (out.isFile && out.length() > 0) OfficeOutput.text(out, doc.fileType) else ""
        // Readable: a Word file whose text reads back, a PDF that opens with its pages (a PDF's
        // text cannot always be read back word for word: then the content checks are warnings).
        val readable = if (doc.fileType == "PDF") out.isFile && runCatching { com.example.template.pdf.PdfDocumentReader.open(out).pages().isNotEmpty() }.getOrDefault(false)
        else text.isNotBlank()
        if (!readable) issues += "unreadable" else if (text.isBlank()) warnings += "text_unverifiable"
        val flat = OfficeOutput.squash(text)
        val expected = DocumentRebuilder.apply(a, changes)
        // A PDF's text read back may differ in shaping (Arabic) or spacing: reported, not fatal.
        val soft = doc.fileType == "PDF" || a.rtl
        if (text.isNotBlank()) {
            if (changes.any { c -> OfficeOutput.squash(c.newText) !in flat }) (if (soft) warnings else issues) += "change_missing"
            if (a.sourceKind == "DOCX" && doc.fileType == "DOCX") {
                val before = a.blocks.filter { it.paragraphIndex != null }.associate { it.paragraphIndex!! to it.text }
                val now = runCatching { OoxmlReader.read(out).paragraphs.toMap() }.getOrDefault(emptyMap())
                val changedIdx = changes.mapNotNull { c -> a.blocks.firstOrNull { it.index == c.block }?.paragraphIndex }.toSet()
                if (before.keys.any { it !in changedIdx && now[it] != before[it] }) issues += "other_content_changed"
                val want = expected.filter { it.paragraphIndex != null }.associate { it.paragraphIndex!! to it.text }
                if (changedIdx.any { now[it] != want[it] }) issues += "change_missing"
            } else {
                val missing = expected.filter { it.type != BlockType.TABLE }.count { b -> OfficeOutput.squash(b.text).take(40).let { it.isNotEmpty() && it !in flat } }
                if (missing > 0) (if (soft) warnings else issues) += "content_missing:$missing"
            }
        }
        val fileId = analyzeStep.input.getValue("fileId")
        val sha = runCatching { LibraryImporter.sha256(access.resolve(fileId).file) }.getOrNull()
        if (sha != analyzeStep.output["sourceSha"]) issues += "source_changed"
        var removed = ""
        if (issues.isNotEmpty()) {
            withContext(NonCancellable) { database.documentDao().deleteDocument(doc); out.delete() }
            removed = doc.id
        }
        val outcome = when {
            issues.isNotEmpty() -> ValidationOutcome.FAILED
            warnings.isNotEmpty() -> ValidationOutcome.COMPLETED_WITH_WARNINGS
            else -> ValidationOutcome.COMPLETED
        }
        mapOf("outcome" to outcome.name, "issues" to (issues + warnings).joinToString(","), "documentId" to (if (removed.isEmpty()) doc.id else ""), "removed" to removed)
    }
}

/** Plans the two Document Reformatting tasks: analyze a source; then apply the user's changes as a new document. */
object OfficeTaskPlanner {
    fun analyze(taskId: String, sessionId: String, request: String, source: LibraryRef, language: String, now: Long, newId: () -> String) = AgentTask(
        id = taskId, sessionId = sessionId, request = request, createdAt = now, mode = AgentMode.AGENT, status = TaskStatus.PENDING,
        steps = listOf(AgentStep(id = "analyze", toolId = OfficeTools.ANALYZE, input = mapOf("fileId" to source.fileId), executionId = newId())),
        language = language, files = listOf(TaskFile(source.fileId, source.source, source.title, source.fileType, FileRole.SOURCE))
    )

    /** The document is analyzed again (it may have changed since), then the changes are prepared, approved, written and checked. */
    fun change(taskId: String, sessionId: String, request: String, source: LibraryRef, language: String, now: Long, newId: () -> String) = AgentTask(
        id = taskId, sessionId = sessionId, request = request, createdAt = now, mode = AgentMode.AGENT, status = TaskStatus.PENDING,
        steps = listOf(
            AgentStep(id = "analyze", toolId = OfficeTools.ANALYZE, input = mapOf("fileId" to source.fileId), executionId = newId()),
            AgentStep(id = "prepare", toolId = OfficeTools.PREPARE, input = mapOf("request" to request.take(4_000)), dependsOn = listOf("analyze"), executionId = newId()),
            AgentStep(id = "write", toolId = OfficeTools.WRITE, dependsOn = listOf("prepare"), approval = ApprovalState.PENDING, executionId = newId()),
            AgentStep(id = "verify", toolId = OfficeTools.VALIDATE, dependsOn = listOf("write"), executionId = newId())
        ),
        language = language, files = listOf(TaskFile(source.fileId, source.source, source.title, source.fileType, FileRole.SOURCE))
    )

    fun isOfficeTask(task: AgentTask) = task.steps.any { it.toolId == OfficeTools.ANALYZE }
}
