package com.example.agent.office

import com.example.agent.core.AgentMode
import com.example.agent.core.AgentStep
import com.example.agent.core.AgentTask
import com.example.agent.core.AgentTool
import com.example.agent.core.ApprovalState
import com.example.agent.core.FileRole
import com.example.agent.core.FileSource
import com.example.agent.core.IntentAnalyzer
import com.example.agent.core.LibraryRef
import com.example.agent.core.TaskFile
import com.example.agent.core.TaskStatus
import com.example.agent.core.ToolCall
import com.example.agent.core.ToolException
import com.example.agent.core.ToolPermission
import com.example.agent.core.ToolSpec
import com.example.agent.files.AgentFileAccess
import com.example.core.storage.DocumentFileNames
import com.example.data.db.SmartBubbleDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/** A Library file operation asked in the chat. */
enum class FileOp { LIST, FIND, RENAME, COPY, DELETE, UNSUPPORTED }

data class FileRequest(val op: FileOp, val type: String? = null, val query: String? = null, val target: LibraryRef? = null, val newName: String? = null, val what: String? = null)

/**
 * Library file requests in Arabic, French and English: list (optionally one type), find by name
 * or content, rename, copy, delete (after confirmation). Merging, splitting, rotating or
 * reordering PDF pages and moving files are not available: they are answered as such, never
 * pretended. Only Library documents (never My Templates) are changed here.
 */
object FileRequestParser {
    private val list = Regex("""(?i)\b(list|show)\b.*\bmy\b.*\b(files?|documents?|pdfs?|docs?)\b|\b(list|show)\b.*\b(files?|documents?|pdfs?)\b.*\bin my library\b|\b(liste|affiche|montre)\b.*\bmes\b.*\b(fichiers?|documents?|pdfs?)\b|(اعرض|أظهر|قائمة).*(ملفاتي|مستنداتي|ملفات المكتبة)""")
    private val find = Regex("""(?i)\b(?:find|search for|search|look for)\s+(.+?)\s+in my (?:library|files|documents)\s*[.?!]?\s*$|\b(?:cherche|trouve|recherche)\s+(.+?)\s+dans (?:ma bibliothèque|mes fichiers|mes documents)\s*[.?!]?\s*$|(?:ابحث عن|ابحث)\s+(.+?)\s+في (?:مكتبتي|ملفاتي|مستنداتي)\s*[.؟!]?\s*$""")
    private val rename = Regex("""(?i)\b(?:rename)\b.*?\b(?:to|as)\s+["“«]?(.+?)["”»]?\s*$|\b(?:renomme[rz]?)\b.*?\ben\s+["“«]?(.+?)["”»]?\s*$|(?:أعد تسمية|غيّر اسم|غير اسم).*?(?:إلى|الى)\s+["“«]?(.+?)["”»]?\s*$""")
    private val renameVerb = Regex("""(?i)\brename\b|\brenomme[rz]?\b|أعد تسمية|غيّر اسم|غير اسم""")
    private val delete = Regex("""(?i)\b(delete|remove|erase|supprime[rz]?|efface[rz]?)\b|احذف|امسح""")
    private val copy = Regex("""(?i)\b(copy|duplicate|copie[rz]?|duplique[rz]?)\b|انسخ|نسخة من""")
    private val unsupported = listOf(
        "merge" to Regex("""(?i)\b(merge|combine|fusionne[rz]?)\b|ادمج|دمج"""),
        "split" to Regex("""(?i)\b(split|d[ée]coupe[rz]?|s[ée]pare[rz]?)\b|قسّم|قسم الملف|فصل الصفحات"""),
        "rotate" to Regex("""(?i)\b(rotate|tourne[rz]?|pivote[rz]?)\b|دوّر|تدوير"""),
        "reorder" to Regex("""(?i)\b(reorder|r[ée]ordonne[rz]?)\b|أعد ترتيب"""),
        "move" to Regex("""(?i)\b(move|d[ée]place[rz]?)\b|انقل"""),
        "images" to Regex("""(?i)\b(extract|extrai[st])\b.*\b(images?|figures?|logos?)\b|استخرج.*(الصور|صور|الأشكال)""")
    )
    private val types = listOf(
        "PDF" to Regex("""(?i)\bpdfs?\b"""), "DOCX" to Regex("""(?i)\b(word|docx)\b|وورد"""),
        "XLSX" to Regex("""(?i)\b(excel|xlsx)\b|إكسل"""), "PPTX" to Regex("""(?i)\b(powerpoint|pptx)\b"""),
        "IMAGE" to Regex("""(?i)\b(images?|photos?|pictures?)\b|صور""")
    )

    fun parse(text: String, library: List<LibraryRef>): FileRequest? {
        val docs = library.filter { it.source == FileSource.LIBRARY }
        val named = IntentAnalyzer.findSources(text, docs)
        // Only about files: a named Library file, or PDF files ("merge two PDFs").
        val aboutFiles = named.isNotEmpty() || Regex("""(?i)\bpdfs?\b""").containsMatchIn(text)
        unsupported.firstOrNull { it.second.containsMatchIn(text) && aboutFiles }?.let {
            return FileRequest(FileOp.UNSUPPORTED, what = it.first)
        }
        if (named.size == 1) {
            val target = named.single()
            // The verb must act on the file itself: it comes right before the file's name
            // ("delete Rapport.pdf", "supprime le fichier Rapport.pdf"), never "remove the date from Rapport.pdf".
            val at = Regex(Regex.escape(target.title.substringBeforeLast('.', target.title)), RegexOption.IGNORE_CASE).find(text)?.range?.first ?: -1
            fun actsOnFile(verb: Regex) = verb.findAll(text).any { m ->
                at >= m.range.last && Regex("""^\s*(?:(?:the|my|this|file|document|le|la|ce|cette|mon|ma|fichier|document|الملف|المستند)\s+){0,3}$""", RegexOption.IGNORE_CASE)
                    .matches(text.substring(m.range.last + 1, at))
            }
            rename.find(text)?.let { m -> m.groupValues.drop(1).firstOrNull { it.isNotBlank() }?.trim()?.let { if (actsOnFile(renameVerb)) return FileRequest(FileOp.RENAME, target = target, newName = it) } }
            if (actsOnFile(delete)) return FileRequest(FileOp.DELETE, target = target)
            if (actsOnFile(copy) && !Regex("""(?i)\b(into|dans|vers)\b|إلى""").containsMatchIn(text)) return FileRequest(FileOp.COPY, target = target)
        }
        if (list.containsMatchIn(text)) return FileRequest(FileOp.LIST, type = types.firstOrNull { it.second.containsMatchIn(text) }?.first)
        find.find(text)?.let { m ->
            val q = m.groupValues.drop(1).firstOrNull { it.isNotBlank() }?.trim()?.trim('"', '“', '”', '«', '»')
            if (!q.isNullOrBlank() && q.length >= 2) return FileRequest(FileOp.FIND, query = q)
        }
        return null
    }
}

object LibraryTools {
    const val LIST = "library.list"
    const val RENAME = "library.rename"
    const val COPY = "library.copy"
    const val DELETE = "library.delete"

    fun plan(taskId: String, sessionId: String, request: String, r: FileRequest, language: String, now: Long, newId: () -> String): AgentTask {
        val step = when (r.op) {
            FileOp.LIST, FileOp.FIND -> AgentStep(id = "list", toolId = LIST, input = buildMap { r.type?.let { put("type", it) }; r.query?.let { put("query", it) } }, executionId = newId())
            FileOp.RENAME -> AgentStep(id = "rename", toolId = RENAME, input = mapOf("fileId" to r.target!!.fileId, "name" to r.newName!!), approval = ApprovalState.PENDING, executionId = newId())
            FileOp.COPY -> AgentStep(id = "copy", toolId = COPY, input = mapOf("fileId" to r.target!!.fileId), executionId = newId())
            FileOp.DELETE -> AgentStep(id = "delete", toolId = DELETE, input = mapOf("fileId" to r.target!!.fileId), approval = ApprovalState.PENDING, executionId = newId())
            FileOp.UNSUPPORTED -> throw IllegalArgumentException("unsupported")
        }
        return AgentTask(
            id = taskId, sessionId = sessionId, request = request, createdAt = now, mode = AgentMode.AGENT, status = TaskStatus.PENDING,
            steps = listOf(step), language = language,
            files = listOfNotNull(r.target?.let { TaskFile(it.fileId, it.source, it.title, it.fileType, FileRole.EDIT_TARGET) })
        )
    }

    fun isLibraryTask(task: AgentTask) = task.steps.any { it.toolId in setOf(LIST, RENAME, COPY, DELETE) }
}

private fun libraryDoc(call: ToolCall): String {
    val fileId = call.step.input.getValue("fileId")
    if (call.task.file(fileId) == null) throw ToolException("file_not_authorized")
    if (!call.task.isGranted(fileId)) throw ToolException("file_access_revoked")
    if (!fileId.startsWith("library:")) throw ToolException("library_only")
    return fileId.removePrefix("library:")
}

/** Lists Library documents (one type, or matching a name or their text). Read-only. */
class LibraryListTool(private val database: SmartBubbleDatabase) : AgentTool {
    override val spec = ToolSpec(
        id = LibraryTools.LIST, name = "List Library", description = "Lists Library documents, by type or by a word in their name or text. Read-only.",
        optionalInputs = setOf("type", "query"), outputs = setOf("count", "items"), permissions = setOf(ToolPermission.READ_LIBRARY)
    )

    override suspend fun execute(call: ToolCall): Map<String, String> = withContext(Dispatchers.IO) {
        val type = call.step.input["type"]
        val q = call.step.input["query"]?.let { FieldDetector.normalize(it) }?.takeIf { it.isNotBlank() }
        val docs = database.documentDao().getAllDocuments().first()
            .filter { File(it.filePath).isFile }
            .filter { type == null || (if (type == "IMAGE") it.fileType.uppercase() in setOf("PNG", "JPG", "JPEG", "WEBP") else it.fileType.equals(type, true)) }
            .filter { q == null || FieldDetector.normalize(it.title).contains(q) || FieldDetector.normalize(it.textContent.take(200_000)).contains(q) }
            .sortedByDescending { it.createdAt }
        mapOf("count" to docs.size.toString(), "items" to docs.take(30).joinToString("\n") { "• ${it.title} (${it.fileType})" })
    }
}

/** Renames a Library document (its title and file name); approved first. */
class LibraryRenameTool(private val database: SmartBubbleDatabase) : AgentTool {
    override val spec = ToolSpec(
        id = LibraryTools.RENAME, name = "Rename document", description = "Renames a Library document after approval.",
        requiredInputs = setOf("fileId", "name"), outputs = setOf("documentId", "title"),
        permissions = setOf(ToolPermission.WRITE_LIBRARY), sideEffect = true, needsApproval = true
    )

    override suspend fun execute(call: ToolCall): Map<String, String> = withContext(Dispatchers.IO) {
        val doc = database.documentDao().getDocumentById(libraryDoc(call)) ?: throw ToolException("file_not_found")
        val ext = File(doc.filePath).extension
        val base = DocumentFileNames.stripDocumentExtension(call.step.input.getValue("name")).trim()
            .map { c -> if (c == '/' || c == '\\' || c == ':' || c.code < 32) '_' else c }.joinToString("").take(120)
        if (base.isBlank()) throw ToolException("invalid_name")
        val title = if (ext.isNotEmpty()) "$base.$ext" else base
        withContext(NonCancellable) { database.documentDao().updateDocument(doc.copy(title = title)) }
        mapOf("documentId" to doc.id, "title" to title)
    }

    override suspend fun reconcile(call: ToolCall): Map<String, String>? = withContext(Dispatchers.IO) {
        val doc = database.documentDao().getDocumentById(libraryDoc(call)) ?: return@withContext null
        val want = DocumentFileNames.stripDocumentExtension(call.step.input.getValue("name")).trim()
        if (DocumentFileNames.stripDocumentExtension(doc.title) == want) mapOf("documentId" to doc.id, "title" to doc.title) else null
    }
}

/** Copies a Library document into a new Library document ("<name> (copy)"). Not destructive. */
class LibraryCopyTool(private val access: AgentFileAccess, private val database: SmartBubbleDatabase, private val label: (String) -> String) : AgentTool {
    override val spec = ToolSpec(
        id = LibraryTools.COPY, name = "Copy document", description = "Copies a Library document into a new Library document.",
        requiredInputs = setOf("fileId"), outputs = setOf("documentId", "title"),
        permissions = setOf(ToolPermission.READ_LIBRARY, ToolPermission.WRITE_LIBRARY), sideEffect = true
    )

    override suspend fun execute(call: ToolCall): Map<String, String> = withContext(Dispatchers.IO) {
        val doc = database.documentDao().getDocumentById(libraryDoc(call)) ?: throw ToolException("file_not_found")
        val src = File(doc.filePath)
        if (!src.isFile) throw ToolException("file_not_found")
        val base = label(DocumentFileNames.stripDocumentExtension(doc.title))
        val target = DocumentFileNames.createUniqueFile(access.libraryDir, base, src.extension.ifBlank { "bin" })
        try {
            // createUniqueFile made this new, empty file for the copy.
            src.copyTo(target, overwrite = true)
            val copy = doc.copy(id = java.util.UUID.randomUUID().toString(), title = "$base.${src.extension}", filePath = target.absolutePath,
                createdAt = System.currentTimeMillis(), sourceRequest = "agent-copy:${call.step.executionId}", isFavorite = false)
            withContext(NonCancellable) { database.documentDao().insertDocument(copy) }
            mapOf("documentId" to copy.id, "title" to copy.title)
        } catch (e: Exception) {
            withContext(NonCancellable) { target.delete() }
            if (e is ToolException || e is kotlinx.coroutines.CancellationException) throw e
            throw ToolException("write_failed")
        }
    }

    override suspend fun reconcile(call: ToolCall): Map<String, String>? = withContext(Dispatchers.IO) {
        database.documentDao().getAllDocuments().first().firstOrNull { it.sourceRequest == "agent-copy:${call.step.executionId}" }
            ?.let { mapOf("documentId" to it.id, "title" to it.title) }
    }
}

/** Deletes a Library document and its file, after explicit approval. */
class LibraryDeleteTool(private val database: SmartBubbleDatabase) : AgentTool {
    override val spec = ToolSpec(
        id = LibraryTools.DELETE, name = "Delete document", description = "Deletes a Library document after explicit approval.",
        requiredInputs = setOf("fileId"), outputs = setOf("deleted", "title"),
        permissions = setOf(ToolPermission.WRITE_LIBRARY), sideEffect = true, needsApproval = true
    )

    override suspend fun execute(call: ToolCall): Map<String, String> = withContext(Dispatchers.IO) {
        val id = libraryDoc(call)
        val doc = database.documentDao().getDocumentById(id) ?: throw ToolException("file_not_found")
        withContext(NonCancellable) {
            database.documentDao().deleteDocument(doc)
            File(doc.filePath).delete()
        }
        mapOf("deleted" to id, "title" to doc.title)
    }

    override suspend fun reconcile(call: ToolCall): Map<String, String>? = withContext(Dispatchers.IO) {
        val id = runCatching { libraryDoc(call) }.getOrNull() ?: return@withContext null
        if (database.documentDao().getDocumentById(id) == null) mapOf("deleted" to id, "title" to "") else null
    }
}
