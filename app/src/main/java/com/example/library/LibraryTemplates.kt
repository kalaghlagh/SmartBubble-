package com.example.library

import com.example.data.db.TemplateDao
import com.example.data.model.TemplateEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

/** A template as a picker shows it: name, file type, and whether its file can still be read. */
data class LibraryTemplateRef(val id: String, val name: String, val fileType: String, val available: Boolean)

/**
 * The single source of reusable templates, shown in the Library (Templates tab) and used by
 * Reformatting, Thesis Builder and Agent Chat's Create Content from Template. Templates keep
 * their existing records and files (the former My Templates storage): nothing is copied or moved,
 * so every existing reference (thesis projects, defaults, chat selections) still resolves.
 * Ordinary Library documents are never templates: a template is a record of this store.
 */
class LibraryTemplateRepository(private val dao: TemplateDao) {
    /** All templates, newest first; updates when templates are added, renamed or deleted. */
    fun observe(): Flow<List<TemplateEntity>> = dao.getAllTemplates()

    /** Formats Reformatting can apply as a layout (Word and PDF templates). */
    fun usableForDocuments(t: TemplateEntity): Boolean = t.fileType.uppercase() in setOf("DOCX", "PDF")

    fun refs(templates: List<TemplateEntity>): List<LibraryTemplateRef> =
        templates.map { LibraryTemplateRef(it.id, it.name, it.fileType.uppercase(), File(it.originalFilePath).isFile) }

    /** The template [id] when it still exists and its file can be read; null when deleted or missing. */
    suspend fun resolve(id: String): TemplateEntity? = withContext(Dispatchers.IO) {
        dao.getTemplateById(id)?.takeIf { File(it.originalFilePath).isFile }
    }
}
