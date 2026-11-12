package com.example.offline.document

import com.example.core.storage.DocumentFileNames
import com.example.document.DocumentFileValidator
import com.example.document.docx.DocxEngine
import com.example.document.pdf.PdfEngine
import com.example.document.pptx.PptxEngine
import com.example.document.xlsx.XlsxEngine
import com.example.offline.FormattingResult
import com.example.offline.StructureBlock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import java.io.File

/** Writes one document file with the existing engines. */
fun interface OfflineDocumentRenderer {
    /** @return true when the uploaded template file itself was used */
    fun render(result: FormattingResult, format: OfflineFormat, template: TemplateMatch, output: File): Boolean
}

/** [OfflineDocumentRenderer] over the app's Document Engine (DOCX, PDF, PPTX, XLSX). */
class EngineDocumentRenderer : OfflineDocumentRenderer {
    private val docx by lazy { DocxEngine() }
    private val pdf by lazy { PdfEngine() }
    private val pptx by lazy { PptxEngine() }
    private val xlsx by lazy { XlsxEngine() }

    override fun render(result: FormattingResult, format: OfflineFormat, template: TemplateMatch, output: File): Boolean =
        when (format) {
            OfflineFormat.DOCX -> docx.generateDocxDetailed(OfflineDocumentComposer.toWord(result), output, template.profile, template.file).templateApplied
            OfflineFormat.PDF -> { pdf.generatePdf(OfflineDocumentComposer.toWord(result), output, template.profile); false }
            OfflineFormat.PPTX -> pptx.generatePptxDetailed(OfflineDocumentComposer.toPresentation(result), output, template.profile, template.file).templateApplied
            OfflineFormat.XLSX -> xlsx.generateXlsxDetailed(OfflineDocumentComposer.toSpreadsheet(result), output, template.profile, template.file).templateApplied
        }
}

/**
 * A generated document waiting for the user's approval. It lives in the app's cache (not in
 * the Library) until [OfflineLibrary.save]; cancelling deletes it.
 */
data class OfflineDraft(
    val file: File,
    val format: OfflineFormat,
    val title: String,
    val template: TemplateMatch,
    val templateApplied: Boolean,
    /** Template facts known before and after generation. */
    val notes: List<TemplateNote>,
    val plainText: String,
    val wordCount: Int,
    val pageOrSlideCount: Int
)

class OfflineGenerationException(message: String) : Exception(message)

/**
 * Generates the approved document on a background thread, validates the file, and deletes it
 * when generation fails or is cancelled. Nothing is saved to the Library here.
 */
class OfflineDocumentGenerator(
    private val draftsDir: File,
    private val renderer: OfflineDocumentRenderer = EngineDocumentRenderer(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    suspend fun generate(result: FormattingResult, format: OfflineFormat, template: TemplateMatch): OfflineDraft {
        val title = OfflineDocumentComposer.fileTitle(result)
        if (result.blocks.isEmpty()) throw OfflineGenerationException("Nothing to format")
        draftsDir.mkdirs()
        val output = DocumentFileNames.createUniqueFile(draftsDir, title, format.extension)
        var keep = false
        try {
            // Engines do blocking file I/O; cancelling interrupts the wait and the file is deleted.
            val applied = runInterruptible(ioDispatcher) { renderer.render(result, format, template, output) }
            currentCoroutineContext().ensureActive()
            DocumentFileValidator.validate(output, format.extension)?.let { throw OfflineGenerationException(it) }
            val notes = template.notes.toMutableList()
            if (!applied && template.file != null && template.entity != null && template.profile.useTemplateLayout) {
                notes.add(TemplateNote.LayoutNotUsed(template.entity.name))
            }
            val plain = OfflineDocumentComposer.plainText(result)
            keep = true
            return OfflineDraft(
                file = output,
                format = format,
                title = title,
                template = template,
                templateApplied = applied,
                notes = notes,
                plainText = plain,
                wordCount = plain.split(Regex("\\s+")).count { it.isNotBlank() },
                pageOrSlideCount = countOf(result, format)
            )
        } finally {
            if (!keep) output.delete()
        }
    }

    private fun countOf(result: FormattingResult, format: OfflineFormat): Int = when (format) {
        OfflineFormat.PPTX -> OfflineDocumentComposer.toPresentation(result).slides.size + 1
        OfflineFormat.XLSX -> OfflineDocumentComposer.toSpreadsheet(result).sheets.size
        else -> (result.blocks.count { it is StructureBlock.Heading }).coerceAtLeast(1)
    }

    /** Deletes a draft that the user cancelled. */
    fun discard(draft: OfflineDraft?) {
        draft?.file?.delete()
    }
}
