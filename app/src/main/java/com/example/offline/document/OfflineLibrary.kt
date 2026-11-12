package com.example.offline.document

import com.example.core.storage.DocumentFileNames
import com.example.data.db.DocumentDao
import com.example.data.model.DocumentEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Saves an approved draft to the existing Library. */
fun interface OfflineLibrary {
    suspend fun save(draft: OfflineDraft): DocumentEntity
}

/**
 * Moves the draft into the Library folder used by every generated document and inserts its
 * Library entry. `providerUsed` is [SOURCE] so the Library shows the document was formatted on
 * the device and not by an AI provider.
 */
class RoomOfflineLibrary(
    private val documentsDir: File,
    private val documentDao: DocumentDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : OfflineLibrary {

    companion object {
        const val SOURCE = "Offline"
    }

    override suspend fun save(draft: OfflineDraft): DocumentEntity = withContext(ioDispatcher) {
        if (!draft.file.isFile) throw OfflineGenerationException("The document is no longer available")
        documentsDir.mkdirs()
        val target = DocumentFileNames.createUniqueFile(documentsDir, draft.title, draft.format.extension)
        if (!draft.file.renameTo(target)) {
            draft.file.copyTo(target, overwrite = true)
            draft.file.delete()
        }
        val entity = DocumentEntity(
            id = UUID.randomUUID().toString(),
            title = DocumentFileNames.displayTitle(draft.title) + "." + draft.format.extension,
            fileType = draft.format.name,
            filePath = target.absolutePath,
            fileSize = target.length(),
            wordCount = draft.wordCount,
            pageOrSlideCount = draft.pageOrSlideCount,
            templateId = draft.template.entity?.id,
            templateName = draft.template.entity?.name,
            providerUsed = SOURCE,
            summary = "$SOURCE • ${target.length() / 1024} KB",
            textContent = draft.plainText,
            isOrchestratorVerified = false
        )
        try {
            documentDao.insertDocument(entity)
        } catch (e: Exception) {
            target.delete()
            throw e
        }
        entity
    }
}
