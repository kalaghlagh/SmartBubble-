package com.example.agent.thesis

import com.example.agent.core.AgentTool
import com.example.agent.core.ToolCall
import com.example.agent.core.ToolException
import com.example.agent.core.ToolPermission
import com.example.agent.core.ToolSpec
import com.example.agent.core.ValidationOutcome
import com.example.agent.files.AgentFileAccess
import com.example.agent.files.FileKind
import com.example.agent.files.LibraryImporter
import com.example.ai.providers.Json
import com.example.extraction.ExtractedDocument
import com.example.thesis.core.ThesisOperations
import com.example.thesis.model.SectionKind
import com.example.thesis.model.ThesisSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * What the Agent may do with Thesis Builder projects. The thesis is addressed by its stable
 * project id (never by a file name); every change goes through the thesis repository, which
 * records it as a revision (the checkpoint used to undo it).
 */
interface ThesisAccess {
    /** The thesis the user is working on (last opened in Thesis Builder), if it still exists. */
    suspend fun activeThesisId(): String?
    suspend fun projects(): List<Pair<String, String>>
    suspend fun snapshot(projectId: String): ThesisSnapshot
    /** Current revision (id, number). */
    suspend fun currentRevision(projectId: String): Pair<String?, Int>
    /** Applies [ops] as one new revision with [summary]; returns (revision id, number). */
    suspend fun apply(projectId: String, ops: List<com.example.thesis.core.ThesisOp>, summary: String): Pair<String, Int>
    /** The revision whose summary starts with [tag], if any (finds a change made before a restart). */
    suspend fun revisionTagged(projectId: String, tag: String): Pair<String, Int>?
    /** Restores revision [revisionId] as a new revision (history is kept). */
    suspend fun restore(projectId: String, revisionId: String): Int
    /** The section has edits not yet saved in the Thesis Builder editor. */
    fun hasUnsavedDraft(projectId: String, sectionId: String): Boolean
    /** Tells an open Thesis Builder workspace to reload the thesis. */
    fun changed(projectId: String)
}

/** Reads pages of a source file through the existing extraction engine (PDF text / PaddleOCR). Pages are 0-based; null = all. */
typealias SourceExtractor = suspend (file: File, isPdf: Boolean, pages: Set<Int>?) -> ExtractedDocument

object ThesisTools {
    const val LOCATE = "thesis.locate"
    const val SOURCE_EXTRACT = "source.extract"
    const val PREPARE = "thesis.prepare_edit"
    const val APPLY = "thesis.apply_edit"
    const val VALIDATE = "thesis.validate"

    /** Pages searched for a numbered table when the user names no page. */
    const val MAX_TABLE_SEARCH_PAGES = 60
    const val PREVIEW_CHARS = 1_500
    const val MAX_CONTENT_CHARS = 60_000

    fun tag(executionId: String) = "agent:$executionId"
}

/** A section as the user knows it: "Chapitre 2 : Résultats", "Chapitre 2" (no title yet), "Remerciements". */
fun sectionLabel(snapshot: ThesisSnapshot, s: com.example.thesis.model.ThesisSection): String {
    val lang = snapshot.meta.language
    val chapterNo = if (s.kind == SectionKind.CHAPTER)
        snapshot.outline().map { it.first }.filter { it.enabled && it.kind == SectionKind.CHAPTER }.indexOfFirst { it.id == s.id } + 1 else 0
    val prefix = if (chapterNo > 0) com.example.thesis.core.ThesisLabels.get(lang, "chapter") + " " + chapterNo else ""
    return when {
        s.title.isBlank() && prefix.isNotEmpty() -> prefix
        s.title.isBlank() -> com.example.thesis.core.ThesisLabels.get(lang, s.kind.name)
        prefix.isNotEmpty() && !ThesisSectionLocator.normalize(s.title).startsWith(ThesisSectionLocator.normalize(prefix)) -> "$prefix : ${s.title}"
        else -> s.title
    }
}

/**
 * Finds the target: the active thesis (or the one the user named, which must be unique), the
 * section, and for a replacement the paragraph. Read-only.
 */
class ThesisLocateTool(private val thesis: ThesisAccess) : AgentTool {
    override val spec = ToolSpec(
        id = ThesisTools.LOCATE, name = "Find target in thesis",
        description = "Finds the active thesis, the named section and paragraph by their stable ids. Read-only.",
        requiredInputs = setOf("kind"),
        optionalInputs = setOf("sectionKind", "chapter", "sectionTitle", "paragraph", "thesisTitle", "atStart", "pageHint"),
        outputs = setOf("projectId", "thesisTitle", "sectionId", "sectionTitle", "active", "elements", "paragraphs", "revisionNumber"),
        permissions = setOf(ToolPermission.READ_LIBRARY)
    )

    override suspend fun execute(call: ToolCall): Map<String, String> {
        val input = call.step.input
        val active = thesis.activeThesisId()
        val projectId = input["thesisTitle"]?.takeIf { it.isNotBlank() }?.let { named ->
            val want = ThesisSectionLocator.normalize(named)
            val matches = thesis.projects().filter { ThesisSectionLocator.normalize(it.second) == want }
            when {
                matches.isEmpty() -> throw ToolException("thesis_not_found:$named")
                matches.size > 1 -> throw ToolException("thesis_ambiguous:$named")
                else -> matches[0].first
            }
        } ?: active ?: throw ToolException("no_active_thesis")
        val snapshot = thesis.snapshot(projectId)
        val ref = SectionRef(
            kind = input["sectionKind"]?.let { k -> SectionKind.values().firstOrNull { it.name == k } },
            chapterNumber = input["chapter"]?.toIntOrNull(),
            title = input["sectionTitle"]
        )
        val section = when (val m = ThesisSectionLocator.locate(snapshot, ref)) {
            is SectionMatch.Found -> m.section
            is SectionMatch.Ambiguous -> throw ToolException("section_ambiguous:" + m.sections.joinToString(" | ") { sectionLabel(snapshot, it) })
            is SectionMatch.NotWritable -> throw ToolException("section_not_writable:" + sectionLabel(snapshot, m.section))
            is SectionMatch.NotFound -> throw ToolException("section_not_found:" + m.available.take(12).joinToString(" | ") { sectionLabel(snapshot, it) })
        }
        val paragraphs = ThesisSectionLocator.paragraphs(snapshot, section.id)
        input["paragraph"]?.toIntOrNull()?.let { n ->
            if (ThesisSectionLocator.paragraph(snapshot, section.id, n) == null) throw ToolException("paragraph_not_found:${paragraphs.size}")
        }
        return mapOf(
            "projectId" to projectId, "thesisTitle" to snapshot.meta.title, "sectionId" to section.id,
            "sectionTitle" to sectionLabel(snapshot, section), "active" to (projectId == active).toString(),
            "elements" to snapshot.elementsOf(section.id).size.toString(), "paragraphs" to paragraphs.size.toString(),
            "revisionNumber" to thesis.currentRevision(projectId).second.toString()
        )
    }
}

/**
 * Extracts pages or a numbered table from an authorized source file through the existing
 * extraction engine. Read-only: the source is never changed (its fingerprint is kept to check it).
 */
class SourceExtractTool(private val access: AgentFileAccess, private val extractor: SourceExtractor) : AgentTool {
    override val spec = ToolSpec(
        id = ThesisTools.SOURCE_EXTRACT, name = "Extract from source",
        description = "Extracts the text of given pages, or a numbered table with its rows and columns, from an authorized PDF or image. Read-only.",
        requiredInputs = setOf("fileId"), optionalInputs = setOf("pages", "tableNumber"),
        outputs = setOf("title", "content", "preview", "pages", "uncertain", "tableUncertain", "sourceSha"),
        permissions = setOf(ToolPermission.READ_LIBRARY)
    )

    override suspend fun execute(call: ToolCall): Map<String, String> {
        val fileId = call.step.input.getValue("fileId")
        if (call.task.file(fileId) == null) throw ToolException("file_not_authorized")
        if (!call.task.isGranted(fileId)) throw ToolException("file_access_revoked")
        val r = access.resolve(fileId)
        val isPdf = when (r.kind) {
            FileKind.PDF -> true
            FileKind.PNG, FileKind.JPEG, FileKind.WEBP -> false
            else -> throw ToolException("extract_unsupported_format")
        }
        val file = r.file
        val wanted = call.step.input["pages"]?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.filter { it >= 1 }.orEmpty()
        val tableNumber = call.step.input["tableNumber"]?.takeIf { it.isNotBlank() }
        val pages0: Set<Int>? = when {
            !isPdf -> null
            wanted.isNotEmpty() -> wanted.map { it - 1 }.toSet()
            tableNumber != null -> (0 until ThesisTools.MAX_TABLE_SEARCH_PAGES).toSet()
            else -> throw ToolException("pages_missing")
        }
        val sha = withContext(Dispatchers.IO) { LibraryImporter.sha256(file) }
        val doc = try { extractor(file, isPdf, pages0) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: ToolException) { throw e } catch (e: Exception) {
            throw ToolException("extraction_failed")
        }
        val read = doc.pages.filter { it.error == null }
        if (isPdf && wanted.isNotEmpty() && read.none { (it.sourcePage ?: -1) + 1 in wanted }) throw ToolException("page_not_found")
        val base = mapOf("title" to r.name, "sourceSha" to sha)
        if (tableNumber != null) {
            return when (val found = TableFinder.find(ExtractedDocument(read), tableNumber)) {
                is TableSearch.NotFound -> throw ToolException("table_not_found:$tableNumber")
                is TableSearch.Ambiguous -> throw ToolException("table_ambiguous:$tableNumber:" + found.pages.joinToString(", "))
                is TableSearch.NoStructure -> throw ToolException("table_no_structure:$tableNumber:${found.page}")
                is TableSearch.Found -> {
                    val data = ThesisEditPlanner.tableData(found.table, tableNumber)
                    val t = found.table.table
                    base + mapOf(
                        "content" to Json.write(mapOf("table" to mapOf("header" to data.tableHeader, "rows" to data.tableRows, "caption" to data.caption))),
                        "preview" to (listOf(data.tableHeader) + data.tableRows.take(6)).joinToString("\n") { it.joinToString(" | ") },
                        "pages" to found.table.page.toString(),
                        "uncertain" to (t.cellsToReview > 0).toString(),
                        "tableUncertain" to t.uncertain.toString()
                    )
                }
            }
        }
        val paragraphs = PageText.paragraphs(ExtractedDocument(read))
        if (paragraphs.isEmpty()) throw ToolException("document_empty")
        if (paragraphs.sumOf { it.length } > ThesisTools.MAX_CONTENT_CHARS) throw ToolException("content_too_large")
        val uncertain = PageText.uncertainPages(ExtractedDocument(read))
        return base + mapOf(
            "content" to Json.write(mapOf("paragraphs" to paragraphs)),
            "preview" to paragraphs.joinToString("\n\n").take(ThesisTools.PREVIEW_CHARS),
            "pages" to read.map { (it.sourcePage ?: it.index) + 1 }.joinToString(", "),
            "uncertain" to uncertain.isNotEmpty().toString(),
            "tableUncertain" to "false"
        )
    }
}

/**
 * Prepares the exact change (operations with new element ids, position, preview) against the
 * thesis as it is now, with fingerprints to detect later changes. Writes nothing.
 */
class ThesisPrepareTool(private val thesis: ThesisAccess, private val newId: () -> String) : AgentTool {
    override val spec = ToolSpec(
        id = ThesisTools.PREPARE, name = "Prepare thesis change",
        description = "Builds the exact change and its preview from the located target and the extracted or given content. Writes nothing.",
        requiredInputs = setOf("kind"), optionalInputs = setOf("text", "paragraph", "atStart", "tableNumber"),
        outputs = setOf("plan", "preview", "position", "oldText", "rows", "columns"),
        permissions = setOf(ToolPermission.READ_LIBRARY)
    )

    override suspend fun execute(call: ToolCall): Map<String, String> {
        val located = call.dependencies.values.firstOrNull { it["projectId"] != null } ?: throw ToolException("no_target")
        val extracted = call.dependencies.values.firstOrNull { it["content"] != null }
        val projectId = located.getValue("projectId")
        val sectionId = located.getValue("sectionId")
        val snapshot = thesis.snapshot(projectId)
        val atStart = call.step.input["atStart"] == "true"
        val kind = ThesisEditKind.valueOf(call.step.input.getValue("kind"))
        val plan = try {
            when (kind) {
                ThesisEditKind.INSERT_PAGE_TEXT -> {
                    val root = Json.obj(Json.parse(extracted?.get("content") ?: throw ToolException("no_content")))
                    val all = Json.arr(root?.get("paragraphs")).orEmpty().mapNotNull { it as? String }
                    // The page's own heading "Remerciements" is not repeated under the section title "Remerciements".
                    val title = ThesisSectionLocator.normalize(located["sectionTitle"].orEmpty())
                    val section = snapshot.section(sectionId)
                    val paragraphs = if (all.size > 1 && (ThesisSectionLocator.normalize(all[0]) == title ||
                            (section != null && ThesisSectionLocator.normalize(all[0]) == ThesisSectionLocator.normalize(section.title)))) all.drop(1) else all
                    ThesisEditPlanner.insertParagraphs(projectId, snapshot, sectionId, paragraphs, atStart, newId)
                }
                ThesisEditKind.INSERT_TABLE -> {
                    val root = Json.obj(Json.parse(extracted?.get("content") ?: throw ToolException("no_content")))
                    val t = Json.obj(root?.get("table")) ?: throw ToolException("no_content")
                    val header = Json.arr(t["header"]).orEmpty().map { it as? String ?: "" }
                    val rows = Json.arr(t["rows"]).orEmpty().map { r -> Json.arr(r).orEmpty().map { it as? String ?: "" } }
                    ThesisEditPlanner.insertTable(projectId, snapshot, sectionId,
                        com.example.thesis.model.ElementData(tableHeader = header, tableRows = rows, caption = t["caption"] as? String ?: ""), atStart, newId)
                }
                ThesisEditKind.APPEND_TEXT -> {
                    val text = call.step.input["text"]?.takeIf { it.isNotBlank() } ?: throw ToolException("text_missing")
                    ThesisEditPlanner.insertParagraphs(projectId, snapshot, sectionId, text.split(Regex("\\n\\s*\\n")), atStart, newId)
                }
                ThesisEditKind.REPLACE_PARAGRAPH -> {
                    val text = call.step.input["text"]?.takeIf { it.isNotBlank() } ?: throw ToolException("text_missing")
                    val n = call.step.input["paragraph"]?.toIntOrNull() ?: throw ToolException("paragraph_missing")
                    ThesisEditPlanner.replaceParagraph(projectId, snapshot, sectionId, n, text)
                        ?: throw ToolException("paragraph_not_found:${ThesisSectionLocator.paragraphs(snapshot, sectionId).size}")
                }
                ThesisEditKind.EXTRACT_ONLY -> throw ToolException("nothing_to_change")
            }
        } catch (e: IllegalArgumentException) {
            throw ToolException(e.message ?: "invalid_change")
        } catch (e: com.example.thesis.core.ThesisOpException) {
            throw ToolException("invalid_change")
        }
        val preview = when (kind) {
            ThesisEditKind.INSERT_TABLE -> extracted?.get("preview").orEmpty()
            ThesisEditKind.INSERT_PAGE_TEXT -> extracted?.get("preview").orEmpty()
            else -> call.step.input["text"].orEmpty().take(ThesisTools.PREVIEW_CHARS)
        }
        return buildMap {
            put("plan", PlanCodec.encode(plan))
            put("preview", preview)
            put("position", when {
                plan.replacedElementId != null -> "replace:" + call.step.input["paragraph"]
                atStart -> "start"
                else -> "end:" + snapshot.elementsOf(sectionId).size
            })
            plan.replacedText?.let { put("oldText", it.take(ThesisTools.PREVIEW_CHARS)) }
            plan.tableShape?.let { put("rows", it.first.toString()); put("columns", it.second.toString()) }
        }
    }
}

/** JSON form of a [PlannedThesisEdit] kept in the task (so approval, restart and validation use the same plan). */
object PlanCodec {
    fun encode(p: PlannedThesisEdit): String = Json.write(
        mapOf(
            "projectId" to p.projectId, "sectionId" to p.sectionId, "ops" to ThesisOperations.encode(p.ops),
            "newElementIds" to p.newElementIds, "replacedElementId" to p.replacedElementId, "replacedText" to p.replacedText,
            "sectionHash" to p.sectionHash, "otherSectionsHash" to p.otherSectionsHash,
            "tableRows" to p.tableShape?.first, "tableColumns" to p.tableShape?.second
        )
    )

    fun decode(text: String?): PlannedThesisEdit {
        val m = Json.obj(Json.parse(text ?: throw ToolException("no_plan"))) ?: throw ToolException("no_plan")
        val rows = (m["tableRows"] as? Number)?.toInt()
        val cols = (m["tableColumns"] as? Number)?.toInt()
        return PlannedThesisEdit(
            projectId = m["projectId"] as String, sectionId = m["sectionId"] as String,
            ops = ThesisOperations.decode(m["ops"] as String),
            newElementIds = Json.arr(m["newElementIds"]).orEmpty().mapNotNull { it as? String },
            replacedElementId = m["replacedElementId"] as? String, replacedText = m["replacedText"] as? String,
            sectionHash = m["sectionHash"] as String, otherSectionsHash = m["otherSectionsHash"] as String,
            tableShape = if (rows != null && cols != null) rows to cols else null
        )
    }
}

/**
 * Applies the approved plan to the thesis as one revision. Before writing it checks that the
 * thesis is still the one approved (and still the active one when it was), that the section has
 * no unsaved edits in the editor, and that neither the section nor the rest of the thesis
 * changed since the preview. The revision before the change is the checkpoint for undo.
 */
class ThesisApplyTool(private val thesis: ThesisAccess) : AgentTool {
    override val spec = ToolSpec(
        id = ThesisTools.APPLY, name = "Apply thesis change",
        description = "Applies the approved change to the thesis as a new revision (undo restores the previous one).",
        outputs = setOf("projectId", "revisionId", "revisionNumber", "previousRevisionId", "previousRevisionNumber"),
        permissions = setOf(ToolPermission.WRITE_LIBRARY), sideEffect = true, needsApproval = true
    )

    private fun planOf(call: ToolCall) = PlanCodec.decode(call.dependencies.values.firstNotNullOfOrNull { it["plan"] })

    override suspend fun execute(call: ToolCall): Map<String, String> {
        val plan = planOf(call)
        val located = call.task.steps.firstOrNull { it.output["projectId"] == plan.projectId }?.output.orEmpty()
        if (thesis.projects().none { it.first == plan.projectId }) throw ToolException("thesis_missing")
        if (located["active"] == "true" && thesis.activeThesisId() != plan.projectId) throw ToolException("active_thesis_changed")
        if (thesis.hasUnsavedDraft(plan.projectId, plan.sectionId)) throw ToolException("thesis_section_being_edited")
        val now = thesis.snapshot(plan.projectId)
        if (now.sectionHash(plan.sectionId) != plan.sectionHash || ThesisEditPlanner.otherSectionsHash(now, plan.sectionId) != plan.otherSectionsHash) {
            throw ToolException("thesis_changed")
        }
        val (previousId, previousNumber) = thesis.currentRevision(plan.projectId)
        val summary = ThesisTools.tag(call.step.executionId) + " " + call.task.request.take(160)
        val (revisionId, number) = try { thesis.apply(plan.projectId, plan.ops, summary) } catch (e: com.example.thesis.core.ThesisOpException) {
            throw ToolException("thesis_changed")
        }
        thesis.changed(plan.projectId)
        return mapOf(
            "projectId" to plan.projectId, "revisionId" to revisionId, "revisionNumber" to number.toString(),
            "previousRevisionId" to previousId.orEmpty(), "previousRevisionNumber" to previousNumber.toString()
        )
    }

    /** After a restart: the revision this run wrote, found by its tag (the change is never applied twice). */
    override suspend fun reconcile(call: ToolCall): Map<String, String>? {
        val plan = runCatching { planOf(call) }.getOrNull() ?: return null
        val (id, number) = thesis.revisionTagged(plan.projectId, ThesisTools.tag(call.step.executionId)) ?: return null
        val previous = (number - 1).coerceAtLeast(0)
        return mapOf("projectId" to plan.projectId, "revisionId" to id, "revisionNumber" to number.toString(), "previousRevisionId" to "", "previousRevisionNumber" to previous.toString())
    }
}

/**
 * Re-reads the thesis after the change: the new content is in the intended section (a table with
 * its rows, columns and cells), the rest of the thesis is unchanged, and the source file was not
 * modified. When the change cannot be confirmed, the previous revision is restored.
 */
class ThesisValidateTool(private val thesis: ThesisAccess, private val access: AgentFileAccess) : AgentTool {
    override val spec = ToolSpec(
        id = ThesisTools.VALIDATE, name = "Check thesis",
        description = "Checks the applied change in the saved thesis and that nothing else (nor the source) changed.",
        outputs = setOf("outcome", "issues", "restored"),
        permissions = setOf(ToolPermission.READ_LIBRARY, ToolPermission.WRITE_LIBRARY), sideEffect = true
    )

    override suspend fun execute(call: ToolCall): Map<String, String> {
        val applied = call.dependencies.values.firstOrNull { it["revisionId"] != null } ?: throw ToolException("not_applied")
        val plan = PlanCodec.decode(call.task.steps.firstNotNullOfOrNull { it.output["plan"] })
        val after = thesis.snapshot(plan.projectId)
        val issues = ArrayList(ThesisEditPlanner.validate(after, plan))
        // The source must be byte-for-byte what was read.
        call.task.steps.firstOrNull { it.output["sourceSha"] != null }?.let { ex ->
            val fileId = ex.input["fileId"] ?: return@let
            val sha = runCatching { withContext(Dispatchers.IO) { LibraryImporter.sha256(access.resolve(fileId).file) } }.getOrNull()
            if (sha != ex.output["sourceSha"]) issues += "source_changed"
        }
        var restored = ""
        if (issues.any { it != "source_changed" }) {
            val previous = applied["previousRevisionId"].orEmpty()
            val current = thesis.currentRevision(plan.projectId).first
            if (previous.isNotEmpty() && current == applied["revisionId"]) {
                restored = thesis.restore(plan.projectId, previous).toString()
                thesis.changed(plan.projectId)
            }
        }
        val outcome = when {
            issues.any { it != "source_changed" } -> ValidationOutcome.FAILED
            issues.isNotEmpty() -> ValidationOutcome.COMPLETED_WITH_WARNINGS
            else -> ValidationOutcome.COMPLETED
        }
        return mapOf("outcome" to outcome.name, "issues" to issues.joinToString(","), "restored" to restored)
    }
}

/** Rows of a table output for a text reply (extract-only requests). */
fun tableAsText(content: String): String? {
    val t = Json.obj(Json.obj(Json.parse(content))?.get("table")) ?: return null
    val header = Json.arr(t["header"]).orEmpty().map { it as? String ?: "" }
    val rows = Json.arr(t["rows"]).orEmpty().map { r -> Json.arr(r).orEmpty().map { it as? String ?: "" } }
    return buildString {
        (t["caption"] as? String)?.takeIf { it.isNotBlank() }?.let { append(it).append('\n') }
        append("| ").append(header.joinToString(" | ")).append(" |\n")
        append("|").append(header.joinToString("|") { "---" }).append("|\n")
        rows.forEach { append("| ").append(it.joinToString(" | ")).append(" |\n") }
    }.trim()
}

/** Paragraphs of a page-text output for a text reply. */
fun paragraphsAsText(content: String): String? =
    Json.arr(Json.obj(Json.parse(content))?.get("paragraphs"))?.mapNotNull { it as? String }?.joinToString("\n\n")
