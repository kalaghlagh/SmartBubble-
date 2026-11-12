package com.example.template.analysis

import java.io.File
import java.util.zip.ZipInputStream

/**
 * Checks an imported file before it is accepted as a template. The type is decided by the
 * file's CONTENT (its OOXML main part or the PDF header), never by its name or the MIME type a provider claims,
 * and the archive is bounded so a crafted file cannot exhaust memory when it is processed.
 */
object TemplateFileInspector {

    enum class Type(val extension: String) { DOCX("docx"), PPTX("pptx"), XLSX("xlsx"), PDF("pdf") }

    sealed class Result {
        data class Valid(val type: Type) : Result()
        object TooLarge : Result()
        object NotOfficeDocument : Result()
    }

    /** Largest template file accepted. */
    const val MAX_FILE_BYTES = 50L * 1024 * 1024

    /** Limits on the unpacked archive (engines load template parts into memory). */
    const val MAX_UNPACKED_BYTES = 200L * 1024 * 1024
    const val MAX_ENTRIES = 5_000

    fun inspect(file: File): Result {
        if (!file.isFile || file.length() == 0L) return Result.NotOfficeDocument
        if (file.length() > MAX_FILE_BYTES) return Result.TooLarge
        // PDF templates: recognized by their "%PDF-" header (readability is checked when analyzed).
        if (com.example.template.pdf.PdfDocumentReader.looksLikePdf(file)) return Result.Valid(Type.PDF)
        var type: Type? = null
        var entries = 0
        var unpacked = 0L
        val buffer = ByteArray(64 * 1024)
        try {
            ZipInputStream(file.inputStream().buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (++entries > MAX_ENTRIES) return Result.TooLarge
                    when (entry.name) {
                        "word/document.xml" -> type = type ?: Type.DOCX
                        "ppt/presentation.xml" -> type = type ?: Type.PPTX
                        "xl/workbook.xml" -> type = type ?: Type.XLSX
                    }
                    // Count the real unpacked size (declared sizes cannot be trusted).
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        unpacked += read
                        if (unpacked > MAX_UNPACKED_BYTES) return Result.TooLarge
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        } catch (e: Exception) {
            return Result.NotOfficeDocument // not a readable ZIP/OOXML package
        }
        return type?.let { Result.Valid(it) } ?: Result.NotOfficeDocument
    }
}
