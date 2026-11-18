package com.example.agent.files

import com.example.core.storage.DocumentFileNames
import com.example.data.db.DocumentDao
import com.example.data.model.DocumentEntity
import com.example.template.analysis.TemplateFileInspector
import com.example.template.pdf.PdfDocumentReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/** File kinds the app can read. Decided from the bytes, never from the name or claimed MIME type. */
enum class FileKind(val fileType: String, val extension: String, val mime: String) {
    PDF("PDF", "pdf", "application/pdf"),
    DOCX("DOCX", "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    XLSX("XLSX", "xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    PPTX("PPTX", "pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
    PNG("PNG", "png", "image/png"),
    JPEG("JPEG", "jpg", "image/jpeg"),
    WEBP("WEBP", "webp", "image/webp");

    val isImage: Boolean get() = this == PNG || this == JPEG || this == WEBP

    companion object {
        fun ofFileType(type: String): FileKind? = entries.firstOrNull { it.fileType.equals(type, ignoreCase = true) }
    }
}

/** Why a file was not imported, or that it was. */
enum class ImportStatus { IMPORTED, DUPLICATE, UNSUPPORTED, LEGACY_OR_ENCRYPTED_OFFICE, ENCRYPTED_PDF, DAMAGED, TOO_LARGE, UNREADABLE, NO_SPACE, CANCELLED }

data class ImportOutcome(val name: String, val sizeBytes: Long, val kind: FileKind?, val status: ImportStatus, val document: DocumentEntity? = null)

/** Content-based type detection (PDF, Word, Excel, PowerPoint, PNG, JPEG, WebP). */
object FileSniffer {
    sealed class Result {
        data class Supported(val kind: FileKind) : Result()
        data class Refused(val status: ImportStatus) : Result()
    }

    private fun head(file: File, n: Int = 16): ByteArray = file.inputStream().use { input ->
        val b = ByteArray(n)
        val read = input.read(b)
        if (read <= 0) ByteArray(0) else b.copyOf(read)
    }

    fun sniff(file: File): Result {
        if (!file.isFile || file.length() == 0L) return Result.Refused(ImportStatus.UNREADABLE)
        val h = head(file)
        fun starts(vararg bytes: Int) = h.size >= bytes.size && bytes.indices.all { (h[it].toInt() and 0xFF) == bytes[it] }
        return when {
            starts(0x89, 0x50, 0x4E, 0x47) -> Result.Supported(FileKind.PNG)
            starts(0xFF, 0xD8, 0xFF) -> Result.Supported(FileKind.JPEG)
            h.size >= 12 && String(h, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(h, 8, 4, Charsets.ISO_8859_1) == "WEBP" -> Result.Supported(FileKind.WEBP)
            // Password-protected Office files and old .doc/.xls/.ppt are OLE compound files.
            starts(0xD0, 0xCF, 0x11, 0xE0) -> Result.Refused(ImportStatus.LEGACY_OR_ENCRYPTED_OFFICE)
            PdfDocumentReader.looksLikePdf(file) -> pdf(file)
            else -> when (val r = TemplateFileInspector.inspect(file)) {
                is TemplateFileInspector.Result.Valid -> Result.Supported(
                    when (r.type) {
                        TemplateFileInspector.Type.DOCX -> FileKind.DOCX
                        TemplateFileInspector.Type.XLSX -> FileKind.XLSX
                        TemplateFileInspector.Type.PPTX -> FileKind.PPTX
                        TemplateFileInspector.Type.PDF -> FileKind.PDF
                    }
                )
                TemplateFileInspector.Result.TooLarge -> Result.Refused(ImportStatus.TOO_LARGE)
                TemplateFileInspector.Result.NotOfficeDocument -> Result.Refused(ImportStatus.UNSUPPORTED)
            }
        }
    }

    private fun pdf(file: File): Result = try {
        val reader = PdfDocumentReader.open(file)
        if (reader.encrypted) Result.Refused(ImportStatus.ENCRYPTED_PDF) else Result.Supported(FileKind.PDF)
    } catch (e: Exception) {
        Result.Refused(ImportStatus.DAMAGED)
    }
}

/**
 * Imports files the user picked on the phone (Storage Access Framework) into the existing
 * Library: the bytes are copied once into the Library folder (the picker URI is not kept, so the
 * file stays available after a restart or reboot without any storage permission), the type is
 * checked from the content, a file already in the Library with the same bytes is not imported
 * twice, and a DocumentEntity row is added to the existing documents table. Word, Excel and
 * PowerPoint text is read at import so search and the Agent can use it; PDF and image text is
 * extracted on demand by the existing extraction engine (PDF text layer / PaddleOCR).
 *
 * The original bytes are preserved; nothing is converted. A partial or refused copy is deleted.
 */
class LibraryImporter(
    private val libraryDir: File,
    private val dao: DocumentDao,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis
) {
    companion object {
        /** DocumentEntity.providerUsed of files imported from the phone. */
        const val SOURCE = "Imported"
        const val MAX_FILE_BYTES = 50L * 1024 * 1024
        const val MAX_TEXT_CHARS = 200_000

        fun sha256(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }

    suspend fun import(name: String, open: () -> InputStream?): ImportOutcome = withContext(io) {
        libraryDir.mkdirs()
        val staging = File(libraryDir, "import_${java.util.UUID.randomUUID()}.part")
        var kept: File? = null
        var size = 0L
        try {
            val input = try { open() } catch (e: Exception) { null } ?: return@withContext ImportOutcome(name, 0, null, ImportStatus.UNREADABLE)
            try {
                input.use { stream ->
                    staging.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = stream.read(buf)
                            if (n < 0) break
                            size += n
                            if (size > MAX_FILE_BYTES) return@withContext ImportOutcome(name, size, null, ImportStatus.TOO_LARGE)
                            out.write(buf, 0, n)
                        }
                    }
                }
            } catch (e: IOException) {
                val noSpace = e.message?.contains("ENOSPC") == true || e.message?.contains("No space", ignoreCase = true) == true
                return@withContext ImportOutcome(name, size, null, if (noSpace) ImportStatus.NO_SPACE else ImportStatus.UNREADABLE)
            }
            val kind = when (val r = FileSniffer.sniff(staging)) {
                is FileSniffer.Result.Supported -> r.kind
                is FileSniffer.Result.Refused -> return@withContext ImportOutcome(name, size, null, r.status)
            }
            // Same bytes already in the Library: not imported twice (only files of the same size are hashed).
            val hash = sha256(staging)
            val existing = dao.getAllDocuments().first().firstOrNull { d ->
                d.fileSize == size && File(d.filePath).let { f -> f.isFile && f.length() == size && sha256(f) == hash }
            }
            if (existing != null) return@withContext ImportOutcome(name, size, kind, ImportStatus.DUPLICATE, existing)

            val content = if (kind == FileKind.DOCX || kind == FileKind.XLSX || kind == FileKind.PPTX) {
                try {
                    OoxmlReader.read(staging)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return@withContext ImportOutcome(name, size, kind, ImportStatus.DAMAGED)
                }
            } else null
            val title = DocumentFileNames.displayTitle(name).let { t ->
                if (t.lowercase().endsWith(".${kind.extension}") || (kind == FileKind.JPEG && t.lowercase().endsWith(".jpeg"))) t else "$t.${kind.extension}"
            }
            val target = DocumentFileNames.createUniqueFile(libraryDir, title.substringBeforeLast('.', title).ifBlank { "file" }, kind.extension)
            if (!staging.renameTo(target)) {
                target.delete()
                throw IOException("rename failed")
            }
            kept = target
            val text = content?.toText(MAX_TEXT_CHARS).orEmpty()
            val doc = DocumentEntity(
                id = java.util.UUID.randomUUID().toString(),
                title = title,
                fileType = kind.fileType,
                filePath = target.absolutePath,
                fileSize = size,
                wordCount = if (text.isBlank()) 0 else text.split(Regex("\\s+")).count { it.isNotBlank() },
                pageOrSlideCount = when (content?.kind) {
                    "PPTX" -> content.blocks.size
                    "XLSX" -> content.blocks.size
                    else -> 0
                },
                providerUsed = SOURCE,
                createdAt = clock(),
                summary = "$SOURCE • ${(size + 1023) / 1024} KB",
                textContent = text,
                isOrchestratorVerified = false
            )
            withContext(NonCancellable) { dao.insertDocument(doc) }
            kept = null // stored: keep the file
            ImportOutcome(title, size, kind, ImportStatus.IMPORTED, doc)
        } catch (e: IOException) {
            val noSpace = e.message?.contains("ENOSPC") == true || e.message?.contains("No space", ignoreCase = true) == true
            ImportOutcome(name, size, null, if (noSpace) ImportStatus.NO_SPACE else ImportStatus.UNREADABLE)
        } finally {
            withContext(NonCancellable) {
                staging.delete()
                kept?.delete()
            }
        }
    }
}
