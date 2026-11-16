package com.example.smarttemplates

import com.example.data.db.SmartBubbleDatabase
import com.example.data.model.TemplateDefaultEntity
import com.example.data.model.TemplateEntity
import com.example.data.model.TemplateFavoriteEntity
import com.example.template.domain.TemplateProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.io.File

/** A template of the library as the screens show it: built-in or uploaded, with its user states. */
data class LibraryTemplate(
    val id: String,
    val builtIn: BuiltInTemplate?,
    val uploaded: TemplateEntity?,
    /** "DOCX", "PDF", "XLSX" or "PPTX". */
    val fileType: String,
    val categoryId: String?,
    /** Subcategories it is classified in (all of its category for a built-in; null subcategory = whole category). */
    val subcategoryId: String?,
    val favorite: Boolean,
    /** Scopes (subcategory or category ids) this template is the default of. */
    val defaultFor: List<String>
) {
    val isBuiltIn: Boolean get() = builtIn != null

    /** True when the template can format documents of [subcategoryId]. */
    fun appliesTo(subcategoryId: String): Boolean {
        val category = TemplateCatalog.subcategory(subcategoryId)?.categoryId ?: return false
        if (builtIn != null) return builtIn.categoryId == category
        val kind = TemplateCatalog.category(category)?.kind ?: return false
        if (fileType.uppercase() !in kind.templateFileTypes) return false
        // Unclassified uploads appear wherever their file type fits; classified ones in their place.
        return when {
            categoryId == null -> true
            categoryId != category -> false
            this.subcategoryId == null -> true
            else -> this.subcategoryId == subcategoryId
        }
    }
}

/** The template that formats one document. */
data class ResolvedTemplate(
    val templateId: String?,
    val source: SelectionSource,
    val builtIn: BuiltInTemplate?,
    val entity: TemplateEntity?,
    val profile: TemplateProfile,
    /** The uploaded template file (only read, never modified); null for built-ins and PDF output. */
    val file: File?
) {
    val isBuiltIn: Boolean get() = builtIn != null
}

/**
 * The template library shared by Chat, My Templates and Thesis Builder: built-in templates and
 * uploaded ones, their categories, the default template of each subcategory/category and the
 * favorites, all persisted in Room. Template ids are stable (never list positions or names).
 */
class SmartTemplateService(private val database: SmartBubbleDatabase) {

    private val dao get() = database.smartTemplateDao()
    private val templates get() = database.templateDao()

    fun library(): Flow<List<LibraryTemplate>> =
        combine(templates.getAllTemplates(), dao.observeDefaults(), dao.observeFavorites()) { uploaded, defaults, favorites ->
            build(uploaded, defaults.associate { it.scopeId to it.templateId }, favorites.map { it.templateId }.toSet())
        }

    suspend fun libraryOnce(): List<LibraryTemplate> =
        build(templates.getAllTemplatesOnce(), defaults(), dao.favorites().map { it.templateId }.toSet())

    private fun build(uploaded: List<TemplateEntity>, defaults: Map<String, String>, favorites: Set<String>): List<LibraryTemplate> {
        val byTemplate = defaults.entries.groupBy({ it.value }, { it.key })
        val builtIns = BuiltInTemplates.all.map { t ->
            LibraryTemplate(t.id, t, null, t.format, t.categoryId, null, t.id in favorites, byTemplate[t.id].orEmpty())
        }
        val mine = uploaded.map { e ->
            LibraryTemplate(e.id, null, e, e.fileType.uppercase(), e.categoryId, e.subcategoryId, e.id in favorites, byTemplate[e.id].orEmpty())
        }
        return builtIns + mine
    }

    suspend fun defaults(): Map<String, String> = dao.defaults().associate { it.scopeId to it.templateId }

    /** Makes [templateId] the default of [scopeId] (a subcategory or category id); one default per scope. */
    suspend fun setDefault(scopeId: String, templateId: String) {
        require(TemplateCatalog.subcategory(scopeId) != null || TemplateCatalog.category(scopeId) != null) { "Unknown category $scopeId" }
        val candidate = candidate(templateId) ?: throw IllegalArgumentException("Unknown template $templateId")
        val kind = (TemplateCatalog.category(scopeId) ?: TemplateCatalog.categoryOf(scopeId))!!.kind
        require(candidate.fits(kind)) { "Template $templateId cannot format $scopeId" }
        dao.setDefault(TemplateDefaultEntity(scopeId, templateId))
    }

    suspend fun clearDefault(scopeId: String) = dao.clearDefault(scopeId)

    suspend fun setFavorite(templateId: String, favorite: Boolean) {
        if (favorite) dao.addFavorite(TemplateFavoriteEntity(templateId)) else dao.removeFavorite(templateId)
    }

    suspend fun classify(templateId: String, categoryId: String?, subcategoryId: String?) {
        require(!BuiltInTemplates.isBuiltIn(templateId)) { "Built-in templates have a fixed category" }
        dao.classify(templateId, categoryId, subcategoryId)
    }

    /** Called when an uploaded template is deleted: its defaults and favorite go with it. */
    suspend fun forget(templateId: String) {
        dao.clearDefaultsOf(templateId)
        dao.removeFavorite(templateId)
    }

    suspend fun candidate(id: String): TemplateCandidate? {
        BuiltInTemplates.byId(id)?.let { return TemplateCandidate(it.id, it.id, it.format, true, it.categoryId, null) }
        val e = templates.getTemplateById(id) ?: return null
        return TemplateCandidate(e.id, e.name, e.fileType.uppercase(), false, e.categoryId, e.subcategoryId)
    }

    /** The template for a document of [subcategoryId] in [format] (see [TemplateSelection] for the order). */
    suspend fun resolve(subcategoryId: String, format: String, overrideId: String? = null): ResolvedTemplate {
        val defaults = defaults()
        val known = HashMap<String, TemplateCandidate?>()
        for (id in listOfNotNull(overrideId) + defaults.values) known[id] = candidate(id)
        val choice = TemplateSelection.choose(subcategoryId, format, overrideId, defaults) { id -> known[id] ?: BuiltInTemplates.byId(id)?.let { TemplateCandidate(it.id, it.id, it.format, true, it.categoryId, null) } }
        return materialize(choice, format)
    }

    /** Profile and file of a chosen template; the neutral built-in formatting when nothing fits. */
    suspend fun materialize(choice: TemplateChoice, format: String): ResolvedTemplate {
        val id = choice.templateId
        BuiltInTemplates.byId(id)?.let { return ResolvedTemplate(it.id, choice.source, it, null, it.profile, null) }
        val entity = id?.let { templates.getTemplateById(it) }
            ?: return ResolvedTemplate(null, SelectionSource.NONE, null, null, com.example.offline.document.TemplateMatcher.builtInProfile(), null)
        val profile = TemplateProfile.fromJson(entity.profileJson)
        val file = File(entity.originalFilePath).takeIf { it.isFile && it.length() > 0 && !profile.isPdfLayout && format != "PDF" }
        return ResolvedTemplate(entity.id, choice.source, null, entity, profile, file)
    }

    /**
     * Category and subcategory suggested for an uploaded file from its name and type; null
     * subcategory when the name says nothing (the user then picks one).
     */
    fun suggestClassification(fileName: String, fileType: String): Pair<String, String?> {
        val kind = OutputKind.ofFormat(if (fileType.equals("PDF", true)) "DOCX" else fileType)
        val name = fileName.substringBeforeLast('.').replace('_', ' ').replace('-', ' ')
        val intent = DocumentIntentDetector.detect("create $name ${if (kind == OutputKind.SPREADSHEET) "excel" else if (kind == OutputKind.PRESENTATION) "powerpoint" else ""}")
        if (intent != null && TemplateCatalog.category(intent.categoryId)?.kind == kind) return intent.categoryId to intent.subcategoryId?.takeIf { intent.confidence >= 0.85 }
        return when (kind) {
            OutputKind.SPREADSHEET -> "spreadsheets" to null
            OutputKind.PRESENTATION -> "presentations" to null
            OutputKind.DOCUMENT -> "reports" to null
        }
    }
}
