package com.example.core.storage

import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Separates what the user sees (a document title, any Unicode including Arabic) from what the
 * filesystem stores (a sanitized, collision-free physical file name).
 */
object DocumentFileNames {

    /** Max UTF-8 bytes for the sanitized base name; leaves room for "_xxxxxxxx.docx" under 255. */
    const val MAX_BASE_NAME_BYTES = 120

    private const val DEFAULT_BASE_NAME = "Document"

    /** Path separators, characters reserved on common filesystems, and control characters. */
    private val INVALID_CHARS = Regex("""[\\/:*?"<>|\u0000-\u001F\u007F]""")

    /** Invisible format characters (incl. RTL/LTR overrides that can visually spoof extensions). */
    private val FORMAT_CHARS = Regex("""\p{Cf}""")

    private val WHITESPACE = Regex("""\s+""")
    private val REPEATED_UNDERSCORES = Regex("_{2,}")

    /**
     * Returns a filesystem-safe base name (no extension) for [title]. Unicode letters and digits,
     * including Arabic, are preserved; separators, reserved and control characters become "_".
     */
    fun sanitizeBaseName(title: String, fallback: String = DEFAULT_BASE_NAME): String {
        val cleaned = title
            .replace(FORMAT_CHARS, "")
            .replace(INVALID_CHARS, "_")
            .replace(WHITESPACE, "_")
            .replace(REPEATED_UNDERSCORES, "_")
            .trim('_', '.', ' ', '-')
        val truncated = truncateUtf8(cleaned, MAX_BASE_NAME_BYTES).trim('_', '.', ' ', '-')
        return if (truncated.isEmpty()) fallback else truncated
    }

    /**
     * Returns a clean single-line visible title: any Unicode is kept (including "/" and Arabic),
     * only control/format characters are removed and whitespace is collapsed.
     */
    fun displayTitle(title: String, fallback: String = DEFAULT_BASE_NAME): String {
        val cleaned = title
            .replace(FORMAT_CHARS, "")
            .replace(Regex("""[\u0000-\u001F\u007F]"""), " ")
            .replace(WHITESPACE, " ")
            .trim()
        return cleaned.ifEmpty { fallback }
    }

    /**
     * Atomically creates and returns a NEW empty file in [directory] whose name is derived from
     * [title] plus a random suffix, so two documents can never share a physical file even when
     * they have identical titles. The caller writes the content and deletes the file on failure.
     */
    fun createUniqueFile(directory: File, title: String, extension: String): File {
        if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("Cannot create directory: ${directory.absolutePath}")
        }
        val base = sanitizeBaseName(title)
        val ext = sanitizeBaseName(extension.trimStart('.').lowercase(), fallback = "bin")
        repeat(16) {
            val candidate = File(directory, "${base}_${UUID.randomUUID().toString().take(8)}.$ext")
            if (candidate.createNewFile()) return candidate
        }
        val last = File(directory, "${base}_${UUID.randomUUID()}.$ext")
        if (last.createNewFile()) return last
        throw IOException("Could not allocate a unique file name in ${directory.absolutePath}")
    }

    private val OFFICE_EXTENSION = Regex("""\.(docx|pptx|xlsx|pdf)$""", RegexOption.IGNORE_CASE)

    /** "Report.v2.docx" -> "Report.v2"; titles without a known document extension are kept. */
    fun stripDocumentExtension(title: String): String = title.trim().replace(OFFICE_EXTENSION, "")

    /**
     * Stable, filesystem-safe name for a document's PDF export. It is derived from the document
     * id, so re-exporting replaces only that document's own export and never another file.
     */
    fun pdfExportFileName(title: String, documentId: String): String {
        val base = sanitizeBaseName(stripDocumentExtension(title))
        val idPart = sanitizeBaseName(documentId, fallback = "doc").take(8)
        return "${base}_$idPart.pdf"
    }

    /** Truncates to at most [maxBytes] UTF-8 bytes without splitting a code point. */
    internal fun truncateUtf8(value: String, maxBytes: Int): String {
        var bytes = 0
        var i = 0
        while (i < value.length) {
            val cp = value.codePointAt(i)
            val len = when {
                cp < 0x80 -> 1
                cp < 0x800 -> 2
                cp < 0x10000 -> 3
                else -> 4
            }
            if (bytes + len > maxBytes) break
            bytes += len
            i += Character.charCount(cp)
        }
        return value.substring(0, i)
    }
}
