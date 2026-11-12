package com.example.offline.document

import com.example.data.db.TemplateDao
import com.example.data.model.TemplateEntity
import com.example.template.domain.TemplateProfile
import java.io.File

/** Read access to the existing My Templates repository (no second template store). */
interface TemplateRepository {
    suspend fun findById(id: String): TemplateEntity?
    suspend fun defaultFor(templateType: String): TemplateEntity?
    suspend fun all(): List<TemplateEntity>
}

/** [TemplateRepository] over the app's Room template table. */
class RoomTemplateRepository(private val dao: TemplateDao) : TemplateRepository {
    override suspend fun findById(id: String): TemplateEntity? = dao.getTemplateById(id)

    override suspend fun defaultFor(templateType: String): TemplateEntity? = when (templateType) {
        "PPTX" -> dao.getDefaultPptTemplate()
        "XLSX" -> dao.getDefaultExcelTemplate()
        "PDF" -> dao.getDefaultPdfTemplate()
        else -> dao.getDefaultWordTemplate()
    }

    override suspend fun all(): List<TemplateEntity> = dao.getAllTemplatesOnce()
}

/** Why a PDF template cannot be used for a format. */
enum class IncompatibleReason { PDF_NO_TABLE_FOR_XLSX, PDF_NOT_FOR_PPTX }

/** Why a template was used. */
enum class TemplateSource { EXPLICIT, DEFAULT, BUILT_IN }

/** Facts about template fidelity shown in the preview (localized by the UI). */
sealed class TemplateNote {
    /**
     * The chosen (or default) template does not fit the output format; it was not used.
     * @property reason why a PDF template does not fit (null for a plain type mismatch)
     */
    data class Incompatible(
        val templateName: String,
        val templateType: String,
        val format: OfflineFormat,
        val reason: IncompatibleReason? = null,
        val isDefault: Boolean = false
    ) : TemplateNote()

    /**
     * A PDF template is applied through its measured style (page size, margins, fonts, colors,
     * header/footer): close to the original but not an exact copy.
     * @property imageOnly scanned PDF: only its page size could be used
     * @property shapesNotInWord its colored shapes cannot be reproduced in a Word document
     */
    data class PdfProfileApplied(val templateName: String, val imageOnly: Boolean, val shapesNotInWord: Boolean) : TemplateNote()

    /** The chosen template no longer exists in My Templates. */
    object NotFound : TemplateNote()

    /** The template's file is missing: only the colors and fonts saved in its profile apply. */
    data class FileMissing(val templateName: String) : TemplateNote()

    /** PDF output applies the template's colors and margins only. */
    object PdfProfileOnly : TemplateNote()

    /** The template is set to "style only": its layout file is not used. */
    data class StyleOnly(val templateName: String) : TemplateNote()

    /** The engine could not use the template file (reported after generation). */
    data class LayoutNotUsed(val templateName: String) : TemplateNote()

    /** No template of this type exists: the built-in clean formatting is used. */
    data class NoTemplate(val format: OfflineFormat) : TemplateNote()
}

/**
 * The template applied to a document.
 * @property file the uploaded template file, only read (never modified); null when absent or
 *   when the format cannot use it (PDF)
 */
data class TemplateMatch(
    val source: TemplateSource,
    val entity: TemplateEntity?,
    val profile: TemplateProfile,
    val file: File?,
    val notes: List<TemplateNote>
)

/**
 * Chooses the template for an offline document, in this order:
 *  1. the template the user selected explicitly (in Chat or in the preview), if it fits;
 *  2. the user's default template for the format (My Templates default);
 *  3. the built-in clean formatting profile.
 * An unrelated template is never used silently: an explicit template of the wrong type is
 * reported with [TemplateNote.Incompatible] and the choice continues with step 2.
 *
 * PDF templates take part like the others. They apply to Word and PDF output (through their
 * measured style), to Excel only when the PDF contains a table, and never to PowerPoint. When
 * no default of the output's own type exists, the default PDF template is considered; if it
 * does not fit, that is reported and the built-in formatting is used.
 */
class TemplateMatcher(private val repository: TemplateRepository) {

    companion object {
        /** Selection value meaning "use the built-in formatting" chosen explicitly. */
        const val BUILT_IN_ID = "offline-builtin"

        private val SUPPORTED_TYPES = setOf("DOCX", "PPTX", "XLSX", "PDF")

        /**
         * Neutral formatting used when no template applies. Unlike the AI path's default profile
         * it adds no header or footer text, so nothing is written that the user did not paste.
         */
        fun builtInProfile(): TemplateProfile = TemplateProfile.defaultProfile().copy(
            templateId = BUILT_IN_ID,
            name = "Built-in clean formatting",
            primaryColorHex = "#1F3A5F",
            secondaryColorHex = "#3B6EA8",
            accentColorHex = "#10182B",
            tableHeaderBgHex = "#1F3A5F",
            tableAlternateRowBgHex = "#F3F6FA",
            tableBorderHex = "#C9D3E0",
            headerText = "",
            footerText = "",
            useTemplateLayout = false,
            keepTemplateContent = false
        )

        fun isCompatible(entity: TemplateEntity, format: OfflineFormat): Boolean = incompatibility(entity, format) == null &&
            entity.fileType.uppercase() in SUPPORTED_TYPES

        /** Null when [entity] can be used for [format]; otherwise why it cannot. */
        fun incompatibility(entity: TemplateEntity, format: OfflineFormat): TemplateNote.Incompatible? {
            val type = entity.fileType.uppercase()
            val mismatch = TemplateNote.Incompatible(entity.name, type, format)
            if (type == "PDF") {
                return when (format) {
                    OfflineFormat.DOCX, OfflineFormat.PDF -> null
                    OfflineFormat.XLSX -> if (TemplateProfile.fromJson(entity.profileJson).tableDetected) null
                        else mismatch.copy(reason = IncompatibleReason.PDF_NO_TABLE_FOR_XLSX)
                    OfflineFormat.PPTX -> mismatch.copy(reason = IncompatibleReason.PDF_NOT_FOR_PPTX)
                }
            }
            return if (type in SUPPORTED_TYPES && type == format.templateType) null else mismatch
        }
    }

    /** Templates of My Templates that can be used for [format] (for the template picker). */
    suspend fun options(format: OfflineFormat): List<TemplateEntity> =
        repository.all().filter { isCompatible(it, format) }

    /**
     * @param explicitId template chosen by the user, [BUILT_IN_ID] for the built-in formatting,
     *   or null when nothing was chosen (the default template applies)
     */
    suspend fun match(format: OfflineFormat, explicitId: String?): TemplateMatch {
        val notes = ArrayList<TemplateNote>()
        if (explicitId == BUILT_IN_ID) return TemplateMatch(TemplateSource.BUILT_IN, null, builtInProfile(), null, emptyList())
        if (explicitId != null) {
            val explicit = repository.findById(explicitId)
            when {
                explicit == null -> notes.add(TemplateNote.NotFound)
                isCompatible(explicit, format) -> return resolve(TemplateSource.EXPLICIT, explicit, format, notes)
                else -> notes.add(incompatibility(explicit, format) ?: TemplateNote.Incompatible(explicit.name, explicit.fileType.uppercase(), format))
            }
        }
        // Defaults: the output's own type first, then the other compatible type.
        val defaultTypes = when (format) {
            OfflineFormat.PDF -> listOf("PDF", "DOCX")
            OfflineFormat.DOCX -> listOf("DOCX", "PDF")
            OfflineFormat.XLSX -> listOf("XLSX", "PDF")
            OfflineFormat.PPTX -> listOf("PPTX", "PDF")
        }
        val rejectedDefaults = ArrayList<TemplateNote>()
        for (type in defaultTypes) {
            val candidate = repository.defaultFor(type) ?: continue
            val problem = incompatibility(candidate, format)
            if (problem == null && candidate.fileType.uppercase() in SUPPORTED_TYPES) {
                return resolve(TemplateSource.DEFAULT, candidate, format, notes)
            }
            rejectedDefaults.add((problem ?: TemplateNote.Incompatible(candidate.name, candidate.fileType.uppercase(), format)).copy(isDefault = true))
        }
        notes.addAll(rejectedDefaults)
        notes.add(TemplateNote.NoTemplate(format))
        return TemplateMatch(TemplateSource.BUILT_IN, null, builtInProfile(), null, notes)
    }

    private fun resolve(source: TemplateSource, entity: TemplateEntity, format: OfflineFormat, notes: MutableList<TemplateNote>): TemplateMatch {
        // A damaged profile falls back to default colors inside fromJson.
        val profile = TemplateProfile.fromJson(entity.profileJson)
        if (profile.isPdfLayout) {
            // The PDF is never used as a file template: its measured style is reproduced.
            notes.add(TemplateNote.PdfProfileApplied(entity.name, profile.isImageOnlyPdf, format == OfflineFormat.DOCX && profile.decorations.isNotEmpty()))
            return TemplateMatch(source, entity, profile, null, notes)
        }
        val file = File(entity.originalFilePath).takeIf { it.isFile && it.length() > 0 }
        when {
            format == OfflineFormat.PDF -> notes.add(TemplateNote.PdfProfileOnly)
            file == null -> notes.add(TemplateNote.FileMissing(entity.name))
            !profile.useTemplateLayout -> notes.add(TemplateNote.StyleOnly(entity.name))
        }
        return TemplateMatch(source, entity, profile, if (format == OfflineFormat.PDF) null else file, notes)
    }
}
