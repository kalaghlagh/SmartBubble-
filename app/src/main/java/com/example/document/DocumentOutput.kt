package com.example.document

import java.io.File
import java.util.zip.ZipFile

/**
 * Outcome of a document engine run.
 * @property templateApplied true only when the uploaded template file itself was used
 * @property notes limitations the user should be told about (e.g. why a template was not used)
 */
data class EngineResult(
    val file: File,
    val templateApplied: Boolean,
    val notes: List<String> = emptyList()
)

/** Verifies that a generated file is really a readable document before it is reported as created. */
object DocumentFileValidator {

    private val REQUIRED_PARTS = mapOf(
        "docx" to listOf("[Content_Types].xml", "word/document.xml"),
        "pptx" to listOf("[Content_Types].xml", "ppt/presentation.xml"),
        "xlsx" to listOf("[Content_Types].xml", "xl/workbook.xml")
    )

    /** Returns null when valid, otherwise a short reason. */
    fun validate(file: File, extension: String): String? {
        if (!file.isFile || file.length() == 0L) return "The file was not written."
        val ext = extension.lowercase()
        if (ext == "pdf") {
            val header = file.inputStream().use { input -> ByteArray(5).also { input.read(it) } }
            return if (String(header, Charsets.US_ASCII) == "%PDF-") null else "The PDF file is invalid."
        }
        val required = REQUIRED_PARTS[ext] ?: return null
        return try {
            ZipFile(file).use { zip ->
                val missing = required.filter { zip.getEntry(it) == null }
                if (missing.isEmpty()) null else "The document package is incomplete (${missing.joinToString()})."
            }
        } catch (e: Exception) {
            "The document package could not be read."
        }
    }
}
