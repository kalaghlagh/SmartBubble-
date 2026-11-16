package com.example.thesis.export

import com.example.core.storage.DocumentFileNames
import com.example.data.model.TemplateEntity
import com.example.document.DocumentFileValidator
import com.example.offline.document.TemplateRepository
import com.example.offline.document.TemplateSource
import com.example.template.docx.DocxTemplateAnalyzer
import com.example.template.domain.TemplateProfile
import com.example.thesis.data.ThesisRepository
import com.example.thesis.model.ThesisAsset
import com.example.thesis.model.ThesisSnapshot
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import java.io.File

/** The template a thesis is formatted with, and why. */
data class ResolvedThesisTemplate(
    val template: ThesisTemplate,
    val entity: TemplateEntity?,
    val source: TemplateSource,
    /** Codes explained to the user: "pdf_profile", "file_missing:<name>", "incompatible:<name>:<type>", "not_found", "builtin". */
    val notes: List<String>
)

/**
 * Template choice for a thesis, with the usual priority: the template bound to the project, then
 * the default Word template, then the default PDF template, then the built-in academic profile.
 * Word templates are used as files (their package is reused); PDF templates through their
 * measured profile; PowerPoint and Excel templates do not fit a thesis and are reported.
 */
object ThesisTemplateResolver {

    /** Smart Templates subcategory of a thesis level. */
    fun subcategoryOf(level: String?): String? = when (level) {
        "LICENCE" -> "academic.licence"
        "MASTER" -> "academic.master"
        "DOCTORATE" -> "academic.phd"
        else -> null
    }

    /**
     * @param level the thesis level ("LICENCE", "MASTER", "DOCTORATE"): without a template of its
     *   own, the thesis uses the Smart Templates default of its level, then of "Theses and academic
     *   documents", before the older default Word/PDF template.
     */
    suspend fun resolve(repository: TemplateRepository, templateId: String?, level: String? = null): ResolvedThesisTemplate {
        val notes = ArrayList<String>()
        val chosen = templateId ?: repository.smartDefault(listOfNotNull(subcategoryOf(level), "academic"))
        // A built-in Smart Templates style (academic formatting drawn from its profile).
        com.example.smarttemplates.BuiltInTemplates.byId(chosen)?.takeIf { it.kind == com.example.smarttemplates.OutputKind.DOCUMENT }?.let { b ->
            return ResolvedThesisTemplate(ThesisTemplate.Profile(b.profile, b.id), null,
                if (templateId != null) TemplateSource.EXPLICIT else TemplateSource.DEFAULT, notes)
        }
        fun use(entity: TemplateEntity, source: TemplateSource): ResolvedThesisTemplate? {
            val profile = TemplateProfile.fromJson(entity.profileJson)
            return when (entity.fileType.uppercase()) {
                "DOCX" -> {
                    val file = File(entity.originalFilePath)
                    if (file.isFile && file.length() > 0) {
                        // Templates imported before the full Word analysis existed are read again.
                        val full = if (profile.layoutSource == "DOCX") profile else DocxTemplateAnalyzer.analyze(file, entity.id, entity.name).copy(
                            useTemplateLayout = profile.useTemplateLayout, keepTemplateContent = profile.keepTemplateContent,
                            sourceFileName = profile.sourceFileName.ifBlank { file.name })
                        ResolvedThesisTemplate(ThesisTemplate.DocxFile(file, full, entity.name), entity, source, notes)
                    }
                    else {
                        notes.add("file_missing:${entity.name}")
                        ResolvedThesisTemplate(ThesisTemplate.Profile(profile.copy(layoutSource = ""), entity.name), entity, source, notes)
                    }
                }
                "PDF" -> {
                    notes.add(if (profile.isImageOnlyPdf) "pdf_image_only:${entity.name}" else "pdf_profile:${entity.name}")
                    ResolvedThesisTemplate(ThesisTemplate.Profile(profile, entity.name), entity, source, notes)
                }
                else -> {
                    notes.add("incompatible:${entity.name}:${entity.fileType.uppercase()}")
                    null
                }
            }
        }
        // The Smart Templates default of the thesis level or of the academic category (uploaded template).
        if (templateId == null && chosen != null) {
            repository.findById(chosen)?.let { e -> use(e, TemplateSource.DEFAULT)?.let { return it } }
        }
        if (templateId != null) {
            val explicit = repository.findById(templateId)
            if (explicit == null) notes.add("not_found") else use(explicit, TemplateSource.EXPLICIT)?.let { return it }
        }
        for (type in listOf("DOCX", "PDF")) {
            repository.defaultFor(type)?.takeIf { it.fileType.equals(type, true) }?.let { d -> use(d, TemplateSource.DEFAULT)?.let { return it } }
        }
        notes.add("builtin")
        return ResolvedThesisTemplate(ThesisTemplate.Profile(ThesisDocxWriter.builtInProfile(), null), null, TemplateSource.BUILT_IN, notes)
    }

    /**
     * Profile used for PDF output and the page preview (one profile for both, so the preview is
     * the PDF). A Word template gives the page setup, margins, header, footer, page borders,
     * header image and text styles read from its file; a missing value falls back to the
     * built-in academic formatting.
     */
    fun pdfProfile(resolved: ResolvedThesisTemplate): TemplateProfile {
        val t = resolved.template
        val p = t.profile
        if (t !is ThesisTemplate.DocxFile) return p
        if (p.layoutSource != "DOCX") {
            val base = ThesisDocxWriter.builtInProfile()
            return base.copy(
                bodyFont = p.bodyFont, headingFont = p.headingFont, marginTopPt = p.marginTopPt, marginBottomPt = p.marginBottomPt,
                marginLeftPt = p.marginLeftPt, marginRightPt = p.marginRightPt, primaryColorHex = p.primaryColorHex,
                headingColorHex = p.primaryColorHex, tableHeaderBgHex = p.tableHeaderBgHex, tableBorderHex = p.tableBorderHex
            )
        }
        // The header image is drawn from a copy next to the template file (made once).
        val image = p.headerImage.split('|')
        val headerImage = if (image.size == 4 && image[0].isNotEmpty()) {
            val ext = image[0].substringAfterLast('.', "png").lowercase().take(5)
            val copy = File(t.file.parentFile, t.file.nameWithoutExtension + "_header." + ext)
            if (!copy.isFile) DocxTemplateAnalyzer.partBytes(t.file, image[0])?.let { bytes -> runCatching { copy.writeBytes(bytes) } }
            if (copy.isFile) listOf(copy.absolutePath, image[1], image[2], image[3]).joinToString("|") else ""
        } else ""
        return p.copy(
            headerImage = headerImage,
            lineSpacing = p.lineSpacing.takeIf { it > 0f } ?: 1.15f,
            // Like the Word export, which adds a numbered footer to a template without a footer.
            pageNumberPosition = p.pageNumberPosition.ifEmpty { if (p.footerText.isBlank()) "footer-center" else "" }
        )
    }
}

/** A generated document (not yet in the Library). */
data class ThesisExport(val file: File, val format: String, val template: ResolvedThesisTemplate, val warnings: List<String>, val pages: Int)

/**
 * Produces thesis documents with the local document code (no AI provider is involved). DOCX is
 * written by [ThesisDocxWriter]; PDF by the injected renderer (Android's PdfDocument on the
 * device). Files are validated before they are returned; a failed or cancelled export leaves
 * no file behind.
 */
class ThesisExporter(
    private val repository: ThesisRepository,
    private val templates: TemplateRepository,
    private val outputDir: File,
    private val pdfRenderer: (TemplateProfile, ThesisSnapshot, List<ThesisAsset>, File) -> Int,
    private val io: CoroutineDispatcher = Dispatchers.IO
) {
    /** [part] limits the document (e.g. one section for "Preview page"); the saved content is never changed. */
    suspend fun export(projectId: String, format: String, fileName: String? = null, part: (ThesisSnapshot) -> ThesisSnapshot = { it }): ThesisExport {
        val snapshot = part(repository.snapshot(projectId))
        val assets = repository.assets(projectId)
        val resolved = ThesisTemplateResolver.resolve(templates, snapshot.meta.templateId, snapshot.meta.level.name)
        outputDir.mkdirs()
        val ext = if (format == "PDF") "pdf" else "docx"
        val out = if (fileName != null) File(outputDir, fileName) else DocumentFileNames.createUniqueFile(outputDir, snapshot.meta.title.ifBlank { "Thesis" }, ext)
        var ok = false
        try {
            var warnings: List<String> = emptyList()
            var pages = 0
            runInterruptible(io) {
                if (ext == "pdf") {
                    pages = pdfRenderer(ThesisTemplateResolver.pdfProfile(resolved), snapshot, assets, out)
                } else {
                    try {
                        warnings = ThesisDocxWriter().write(snapshot, assets, resolved.template, out).warnings
                    } catch (e: Exception) {
                        if (resolved.template !is ThesisTemplate.DocxFile) throw e
                        // The Word template could not be used: built-in formatting, reported.
                        warnings = ThesisDocxWriter().write(snapshot, assets, ThesisTemplate.Profile(ThesisDocxWriter.builtInProfile(), null), out).warnings + "template_unusable"
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            DocumentFileValidator.validate(out, ext)?.let { throw IllegalStateException(it) }
            ok = true
            return ThesisExport(out, format, resolved, warnings, pages)
        } finally {
            if (!ok) out.delete()
        }
    }
}
