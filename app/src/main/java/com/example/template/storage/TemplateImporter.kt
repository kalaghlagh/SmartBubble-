package com.example.template.storage

import com.example.data.db.TemplateDao
import com.example.data.model.TemplateEntity
import com.example.template.analysis.TemplateAnalysisEngine
import com.example.template.analysis.TemplateFileInspector
import com.example.template.pdf.PdfEncryptedException
import com.example.template.pdf.PdfReadException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID

/** Why a file could not become a template (shown to the user, localized by the UI). */
class TemplateImportException(val reason: Reason) : Exception(reason.name) {
    enum class Reason { UNREADABLE, TOO_LARGE, UNSUPPORTED, PDF_ENCRYPTED, PDF_DAMAGED }
}

/**
 * Imports a template file picked with the Storage Access Framework: bounded copy into the
 * app's private template folder, type detection from the content (DOCX, PPTX, XLSX or PDF),
 * local analysis into a [com.example.template.domain.TemplateProfile], and registration in the
 * existing template table. The picked document is only read; nothing leaves the device. A
 * partial copy is deleted when the import fails or is cancelled.
 */
class TemplateImporter(
    private val templatesDir: File,
    private val dao: TemplateDao,
    private val analysis: TemplateAnalysisEngine = TemplateAnalysisEngine(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Renders a first-page thumbnail for PDF templates (device only; null in plain JVM tests). */
    private val pdfThumbnail: ((pdf: File, templateId: String) -> Unit)? = null
) {

    suspend fun import(open: () -> InputStream?, userName: String, sourceFileName: String?): TemplateEntity {
        var stored: File? = null
        var saved = false
        try {
            val (file, profile) = withContext(io) {
                templatesDir.mkdirs()
                val staging = File(templatesDir, "import_${UUID.randomUUID()}.tmp")
                stored = staging
                val input = try { open() } catch (e: Exception) { null } ?: throw TemplateImportException(TemplateImportException.Reason.UNREADABLE)
                input.use { stream ->
                    FileOutputStream(staging).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val read = try { stream.read(buffer) } catch (e: IOException) { throw TemplateImportException(TemplateImportException.Reason.UNREADABLE) }
                            if (read < 0) break
                            total += read
                            if (total > TemplateFileInspector.MAX_FILE_BYTES) throw TemplateImportException(TemplateImportException.Reason.TOO_LARGE)
                            output.write(buffer, 0, read)
                        }
                    }
                }
                val type = when (val result = TemplateFileInspector.inspect(staging)) {
                    is TemplateFileInspector.Result.Valid -> result.type
                    TemplateFileInspector.Result.TooLarge -> throw TemplateImportException(TemplateImportException.Reason.TOO_LARGE)
                    TemplateFileInspector.Result.NotOfficeDocument -> throw TemplateImportException(TemplateImportException.Reason.UNSUPPORTED)
                }
                val target = File(templatesDir, "template_${UUID.randomUUID()}.${type.extension}")
                if (!staging.renameTo(target)) throw IOException("Could not store the template file.")
                stored = target
                val profile = try {
                    analysis.analyzeFile(target, userName, sourceFileName)
                } catch (e: PdfEncryptedException) {
                    throw TemplateImportException(TemplateImportException.Reason.PDF_ENCRYPTED)
                } catch (e: PdfReadException) {
                    throw TemplateImportException(TemplateImportException.Reason.PDF_DAMAGED)
                }
                target to profile
            }
            val entity = TemplateEntity(
                id = profile.templateId,
                name = profile.name,
                fileType = profile.originalFileType,
                originalFilePath = file.absolutePath,
                fileSize = file.length(),
                profileJson = profile.toJson(),
                compatibilityStatus = when {
                    !profile.isPdfLayout -> "Compatible"
                    profile.pdfTextStatus == "NO_TEXT" -> "Review"
                    profile.pdfTextStatus == "PARTIAL" -> "Partial"
                    else -> "Compatible"
                },
                thumbnailColorHex = profile.primaryColorHex
            )
            dao.insertTemplate(entity)
            saved = true
            if (entity.fileType == "PDF") {
                withContext(io) { runCatching { pdfThumbnail?.invoke(file, entity.id) } }
            }
            return entity
        } finally {
            if (!saved) withContext(NonCancellable + io) { stored?.delete() }
        }
    }
}
