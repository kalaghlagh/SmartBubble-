package com.example.reformat

import com.example.agent.files.DocxCopyEditor
import com.example.agent.files.FileKind
import com.example.agent.files.FileSniffer
import com.example.agent.files.ImportStatus
import com.example.agent.files.LibraryImporter
import com.example.agent.files.OfficeReadException
import com.example.agent.files.OoxmlReader
import com.example.agent.office.BlockType
import com.example.agent.office.DocBlock
import com.example.agent.office.DocumentRebuilder
import com.example.agent.office.OfficeOutput
import com.example.agent.office.blocksOf
import com.example.agent.thesis.SourceExtractor
import com.example.core.storage.DocumentFileNames
import com.example.data.db.SmartBubbleDatabase
import com.example.data.model.DocumentEntity
import com.example.data.model.TemplateEntity
import com.example.extraction.ExtractedDocument
import com.example.extraction.PageSource
import com.example.library.LibraryTemplateRepository
import com.example.template.domain.TemplateProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/** A workspace failure with a stable code (shown localized). */
class ReformatException(val code: String) : Exception(code)

/**
 * The Reformatting workspace: imports a document (Word, text or scanned PDF) or a photo, keeps
 * its content editable, and creates a NEW document (PDF or DOCX) either with the source's own
 * formatting or with a Library template's layout. The source is never written: it is read into a
 * private copy (not into the Library), checked by fingerprint before every export, and the
 * workspace (content, edits, chosen formatting) is saved after every change so it survives a
 * restart. Everything runs locally with the app's existing engines; no AI request is made.
 */
class ReformatController(
    private val scope: CoroutineScope,
    private val database: SmartBubbleDatabase,
    private val templates: LibraryTemplateRepository,
    /** Private folder of the workspace (source copy, state, previews). */
    private val workDir: File,
    /** The Library's folder (saved results). */
    private val libraryDir: File,
    /** The existing extraction engine: PDF text layer, PaddleOCR for scanned pages and photos. Pages 0-based. */
    private val extract: SourceExtractor,
    /** Localized "<name> (reformatted)". */
    private val resultLabel: (String) -> String,
    private val io: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO
) {
    companion object {
        const val MAX_SOURCE_BYTES = 50L * 1024 * 1024
        const val MAX_PDF_PAGES = 40
        const val MAX_CHARS = 200_000
    }

    private val _state = MutableStateFlow(ReformatState())
    val state: StateFlow<ReformatState> = _state.asStateFlow()
    private var job: Job? = null

    private val sourceDir get() = File(workDir, "source")
    private val previewDir get() = File(workDir, "preview")
    private val stateFile get() = File(workDir, "workspace.json")

    private fun update(f: (ReformatState) -> ReformatState) {
        _state.value = f(_state.value)
        persist()
    }

    /** Writes of the workspace file run one at a time (they share one temporary file). */
    private val persistLock = Mutex()

    /**
     * Saves the workspace. Each write takes the state as it is when the write runs, under
     * [persistLock]: writes started for older changes can finish late, but never leave an older
     * state on disk after a newer one. An empty workspace removes the file.
     */
    private fun persist() {
        scope.launch(io) {
            persistLock.withLock {
                runCatching {
                    val current = _state.value
                    if (current.source == null) { stateFile.delete(); return@runCatching }
                    workDir.mkdirs()
                    val tmp = File(workDir, "workspace.json.part")
                    tmp.writeText(ReformatCore.encode(current))
                    if (!tmp.renameTo(stateFile)) { stateFile.delete(); tmp.renameTo(stateFile) }
                }
            }
        }
    }

    private fun launch(block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true, error = null)
        job = scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ReformatException) {
                _state.value = _state.value.copy(error = e.code)
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = "failed:${e.javaClass.simpleName}")
            } finally {
                _state.value = _state.value.copy(busy = false)
            }
        }
    }

    /** After a restart: the last workspace (source copy, edits, formatting) when its source copy is still there. */
    suspend fun restore() = withContext(io) {
        val text = runCatching { stateFile.readText() }.getOrNull() ?: return@withContext
        val (restored, saved) = ReformatCore.decode(text) ?: return@withContext
        val src = restored.source
        if (src != null && !File(src.path).isFile) return@withContext
        _state.value = restored.copy(preview = null, error = null)
        lastSavedId = saved
    }

    /** The id of the Library document saved from the last preview (a restart never saves it twice). */
    private var lastSavedId: String? = null

    fun clearError() { _state.value = _state.value.copy(error = null) }

    // ------------------------------------------------------------------ import

    /** Insert File: a document picked from the phone (Word or PDF); [name] is its display name. */
    fun importFile(name: String, open: () -> InputStream?) = launch { load(name, open, image = false) }

    /** Image: a photo or a scanned page, read by the existing OCR engine (PaddleOCR). */
    fun importImage(name: String, open: () -> InputStream?) = launch { load(name, open, image = true) }

    private suspend fun load(name: String, open: () -> InputStream?, image: Boolean) {
        val staged = stage(name, open)
        try {
            val kind = (FileSniffer.sniff(staged) as? FileSniffer.Result.Supported)?.kind
                ?: (FileSniffer.sniff(staged) as? FileSniffer.Result.Refused)?.let {
                    throw ReformatException(if (it.status == ImportStatus.ENCRYPTED_PDF) "encrypted_pdf" else "unsupported_format")
                } ?: throw ReformatException("unsupported_format")
            if (image && !kind.isImage) throw ReformatException("not_an_image")
            if (!image && kind != FileKind.DOCX && kind != FileKind.PDF) throw ReformatException("unsupported_format")
            val (srcKind, pages, blocks, scanned) = read(staged, kind)
            if (blocks.isEmpty() || blocks.all { it.text.isBlank() && it.rows.isEmpty() }) throw ReformatException("document_empty")
            if (blocks.sumOf { it.text.length } > MAX_CHARS) throw ReformatException("document_too_large")
            val sha = withContext(io) { LibraryImporter.sha256(staged) }
            // The new source replaces the previous one (its private copy is removed).
            val previous = _state.value.source?.path
            if (previous != null && previous != staged.absolutePath) withContext(io) { File(previous).parentFile?.deleteRecursively() }
            lastSavedId = null
            update { ReformatState(source = WorkSource(name, srcKind, pages, scanned, sha, staged.absolutePath), blocks = blocks.map { WorkBlock(it) }, templateId = it.templateId) }
        } catch (e: Exception) {
            withContext(NonCancellable + io) { staged.parentFile?.deleteRecursively() }
            throw e
        }
    }

    private suspend fun stage(name: String, open: () -> InputStream?): File = withContext(io) {
        val dir = File(sourceDir, java.util.UUID.randomUUID().toString()).apply { mkdirs() }
        val safe = name.map { c -> if (c == '/' || c == '\\' || c == ':' || c.code < 32) '_' else c }.joinToString("").trim().take(120)
            .ifBlank { "document" }.let { if (it == "." || it == "..") "document" else it }
        val target = File(dir, safe)
        try {
            val input = try { open() } catch (e: SecurityException) { null } ?: throw ReformatException("source_unavailable")
            input.use { inp ->
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_SOURCE_BYTES) throw ReformatException("file_too_large")
                        out.write(buf, 0, n)
                    }
                }
            }
            if (target.length() == 0L) throw ReformatException("document_empty")
            target
        } catch (e: Exception) {
            dir.deleteRecursively()
            if (e is ReformatException) throw e
            throw ReformatException("source_unavailable")
        }
    }

    private data class Read(val kind: SourceKind, val pages: Int, val blocks: List<DocBlock>, val scanned: Boolean)

    private suspend fun read(file: File, kind: FileKind): Read = when (kind) {
        FileKind.DOCX -> withContext(io) {
            val content = try { OoxmlReader.read(file) } catch (e: OfficeReadException) { throw ReformatException("file_damaged") } catch (e: java.util.zip.ZipException) { throw ReformatException("file_damaged") }
            Read(SourceKind.DOCX, 1, blocksOf(content), false)
        }
        else -> {
            val isPdf = kind == FileKind.PDF
            val doc = try { extract(file, isPdf, if (isPdf) (0 until MAX_PDF_PAGES).toSet() else null) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                throw ReformatException("extraction_failed")
            }
            val pages = doc.pages.filter { it.error == null }
            if (pages.isEmpty()) throw ReformatException("document_empty")
            Read(if (isPdf) SourceKind.PDF else SourceKind.IMAGE, pages.size, blocksOf(ExtractedDocument(pages)), pages.any { it.source != PageSource.PDF_TEXT })
        }
    }

    // ------------------------------------------------------------------ editing

    fun editText(index: Int, text: String) = update { s ->
        s.copy(blocks = s.blocks.map { if (it.block.index == index) it.copy(text = text) else it }, preview = null)
    }

    /** A table cell. Word tables keep their source formatting only when unchanged: they are edited through a template. */
    fun editCell(index: Int, row: Int, column: Int, text: String) = update { s ->
        s.copy(blocks = s.blocks.map { w ->
            if (w.block.index != index) w
            else w.copy(rows = w.rows.mapIndexed { r, cells -> if (r != row) cells else cells.mapIndexed { c, v -> if (c == column) text else v } })
        }, preview = null)
    }

    /** Back to the imported content (every edit is dropped). */
    fun resetEdits() = update { s -> s.copy(blocks = s.blocks.map { WorkBlock(it.block) }, preview = null) }

    /** Closes the source: the private copy and the workspace are removed (the original was never changed). */
    fun removeSource() {
        val path = _state.value.source?.path
        lastSavedId = null
        _state.value = ReformatState(templateId = _state.value.templateId)
        scope.launch(io) {
            persistLock.withLock {
                path?.let { File(it).parentFile?.deleteRecursively() }
                // Only when no new source was imported meanwhile.
                if (_state.value.source == null) stateFile.delete()
                previewDir.deleteRecursively()
            }
        }
    }

    /** null: Keep Source Formatting; else a Library template. Edits are kept. */
    fun setFormatting(templateId: String?) = update { it.copy(templateId = templateId, preview = null) }

    fun closePreview() { _state.value = _state.value.copy(preview = null) }

    // ------------------------------------------------------------------ create

    fun create(format: ExportFormat) = launch {
        val s = _state.value
        val src = s.source ?: throw ReformatException("no_source")
        val file = File(src.path)
        if (!file.isFile) throw ReformatException("source_unavailable")
        if (withContext(io) { LibraryImporter.sha256(file) } != src.sha) throw ReformatException("source_changed")
        val template = s.templateId?.let { id ->
            templates.resolve(id) ?: run {
                // The template was deleted or its file is gone: back to the source's formatting.
                update { it.copy(templateId = null) }
                throw ReformatException("template_missing")
            }
        }
        if (template != null && !templates.usableForDocuments(template)) throw ReformatException("template_unsupported")
        val expected = ReformatCore.current(s.blocks)
        val out = withContext(io) {
            previewDir.deleteRecursively(); previewDir.mkdirs()
            File(previewDir, "result." + format.extension)
        }
        val notes = ArrayList<String>()
        withContext(io) { render(src, s.blocks, expected, template, format, file, out, notes) }
        val text = withContext(io) { OfficeOutput.text(out, if (format == ExportFormat.PDF) "PDF" else "DOCX") }
        val (issues, checkNotes) = ReformatCore.check(expected, text, exact = format == ExportFormat.DOCX)
        val structure = withContext(io) { structureIssues(out, format, src, template) }
        _state.value = _state.value.copy(
            preview = ExportPreview(out.absolutePath, format, template?.name, text.take(20_000), issues + structure, notes + checkNotes)
        )
    }

    private fun render(src: WorkSource, blocks: List<WorkBlock>, expected: List<DocBlock>, template: TemplateEntity?, format: ExportFormat, source: File, out: File, notes: MutableList<String>) {
        val model = DocumentRebuilder.wordModel(expected)
        val rtl = expected.count { it.rtl } * 2 > expected.size
        if (template != null) {
            // A copy of the template receives the content: its styles, page setup, headers and footers are kept.
            val profile = runCatching { TemplateProfile.fromJson(template.profileJson) }.getOrDefault(TemplateProfile.defaultProfile())
            val templateFile = File(template.originalFilePath)
            if (format == ExportFormat.DOCX) {
                val r = com.example.document.docx.DocxEngine().generateDocxDetailed(model, out, profile, templateFile.takeIf { template.fileType.equals("DOCX", true) })
                if (r.notes.isNotEmpty()) notes += "template_partial"
            } else {
                com.example.document.pdf.PdfEngine().generatePdf(model, out, profile)
            }
            notes += "template_applied"
            return
        }
        if (src.kind == SourceKind.DOCX && format == ExportFormat.DOCX) {
            // Keep Source Formatting: a copy of the original package where only the edited text changes, run by run.
            val replacements = blocks.filter { it.edited && it.block.type != BlockType.TABLE }.mapNotNull { w ->
                val p = w.block.paragraphIndex ?: return@mapNotNull null
                ReformatCore.minimalReplacement(p, w.block.text, w.text)
            }
            if (blocks.any { it.edited && it.block.type == BlockType.TABLE }) notes += "docx_table_edits_need_template"
            if (blocks.any { it.edited && it.block.paragraphIndex == null && it.block.type != BlockType.TABLE }) notes += "some_edits_not_mapped"
            if (replacements.isEmpty()) source.copyTo(out, overwrite = true)
            else {
                val r = DocxCopyEditor.writeCopyReplacing(source, out, replacements)
                if (r.skipped.isNotEmpty()) throw ReformatException("edit_not_applied")
            }
            return
        }
        // A PDF, a photo, or a PDF made from a Word source: rebuilt by the existing engines with the source's measured layout.
        notes += "reconstructed"
        val profile = when (src.kind) {
            SourceKind.PDF -> if (!src.scanned) runCatching { com.example.template.pdf.PdfTemplateAnalyzer.analyze(source).toProfile("reformat", "source", source.name) }.getOrNull() else null
            SourceKind.DOCX -> runCatching { com.example.template.docx.DocxTemplateAnalyzer.analyze(source, "reformat", "source") }.getOrNull()
            SourceKind.IMAGE -> null
        } ?: TemplateProfile.defaultProfile()
        val layout = profile.copy(rtl = rtl)
        if (format == ExportFormat.DOCX) com.example.document.docx.DocxEngine().generateDocxDetailed(model, out, layout)
        else com.example.document.pdf.PdfEngine().generatePdf(model, out, layout)
    }

    /**
     * Structure checks of the generated file: a Word file is a valid package whose untouched parts
     * are the source's (Keep Source Formatting) or the template's (template); Arabic paragraphs
     * carry right-to-left properties; a PDF opens and has pages.
     */
    private fun structureIssues(out: File, format: ExportFormat, src: WorkSource, template: TemplateEntity?): List<String> {
        if (!out.isFile || out.length() == 0L) return listOf("output_missing")
        return if (format == ExportFormat.PDF) {
            if (runCatching { com.example.template.pdf.PdfDocumentReader.open(out).pages().isNotEmpty() }.getOrDefault(false)) emptyList() else listOf("output_unreadable")
        } else {
            val issues = ArrayList<String>()
            val parts = runCatching { java.util.zip.ZipFile(out).use { z -> z.entries().toList().map { it.name } } }.getOrNull() ?: return listOf("output_unreadable")
            if ("word/document.xml" !in parts) issues += "output_unreadable"
            if (template == null && src.kind == SourceKind.DOCX) {
                val before = java.util.zip.ZipFile(File(src.path)).use { z -> z.entries().toList().map { it.name }.toSet() }
                if (!parts.toSet().containsAll(before)) issues += "parts_lost"
            }
            // Only direction problems the export introduced (a source's own paragraphs are kept as they were).
            val baseline = if (template == null && src.kind == SourceKind.DOCX) RtlCheck.wrongParagraphs(File(src.path)) else 0
            val wrong = RtlCheck.wrongParagraphs(out)
            if (wrong < 0) issues += "output_unreadable" else if (wrong > baseline) issues += "arabic_direction:${wrong - baseline}"
            issues
        }
    }

    // ------------------------------------------------------------------ save

    /** Saves the checked result as a NEW Library document (once; a second tap or a restart does not save it twice). */
    fun saveToLibrary() = launch {
        val p = _state.value.preview ?: throw ReformatException("no_preview")
        if (!p.valid) throw ReformatException("invalid_result")
        p.savedDocumentId?.let { return@launch }
        val src = _state.value.source ?: throw ReformatException("no_source")
        val doc = withContext(io) {
            val base = resultLabel(DocumentFileNames.stripDocumentExtension(src.name))
            val target = DocumentFileNames.createUniqueFile(libraryDir, base, p.format.extension)
            try {
                File(p.path).copyTo(target, overwrite = true)
                val entity = DocumentEntity(
                    id = java.util.UUID.randomUUID().toString(), title = "$base.${p.format.extension}", fileType = p.format.name,
                    filePath = target.absolutePath, fileSize = target.length(),
                    wordCount = p.text.split(Regex("\\s+")).count { it.isNotBlank() }, pageOrSlideCount = 0,
                    providerUsed = "Reformatting", summary = "Reformatting • ${(target.length() + 1023) / 1024} KB",
                    textContent = p.text.take(LibraryImporter.MAX_TEXT_CHARS), isOrchestratorVerified = false
                )
                withContext(NonCancellable) { database.documentDao().insertDocument(entity) }
                entity
            } catch (e: Exception) {
                withContext(NonCancellable) { target.delete() }
                throw e
            }
        }
        lastSavedId = doc.id
        _state.value = _state.value.copy(preview = p.copy(savedDocumentId = doc.id))
        persist()
    }
}

/** Right-to-left properties of Word paragraphs whose text is Arabic. */
object RtlCheck {
    private val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    /** Paragraphs mostly in Arabic without right-to-left properties (w:bidi or w:rtl runs); -1 when the file cannot be read. */
    fun wrongParagraphs(file: File): Int = try {
        java.util.zip.ZipFile(file).use { z ->
            val xml = z.getInputStream(z.getEntry("word/document.xml")).use { it.readBytes() }
            val doc = OoxmlReader.parse(xml)
            val paragraphs = doc.getElementsByTagNameNS(W, "p")
            var wrong = 0
            for (i in 0 until paragraphs.length) {
                val p = paragraphs.item(i) as org.w3c.dom.Element
                val text = p.getElementsByTagNameNS(W, "t").let { ts -> (0 until ts.length).joinToString("") { ts.item(it).textContent } }
                val arabic = text.count { it in '؀'..'ۿ' }
                val latin = text.count { it in 'A'..'Z' || it in 'a'..'z' }
                if (arabic == 0 || arabic <= latin) continue
                val bidi = p.getElementsByTagNameNS(W, "bidi").length > 0
                val rtlRuns = p.getElementsByTagNameNS(W, "rtl").length > 0
                if (!bidi && !rtlRuns) wrong++
            }
            wrong
        }
    } catch (e: Exception) { -1 }
}
