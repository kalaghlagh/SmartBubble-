package com.example.smarttemplates

/** Why a template was chosen for a document. */
enum class SelectionSource {
    /** Chosen for this one document (does not change any saved default). */
    OVERRIDE,
    SUBCATEGORY_DEFAULT,
    CATEGORY_DEFAULT,
    BUILT_IN_BEST_MATCH,
    /** Nothing fits: the user is asked to choose. */
    NONE
}

/** A template that may format a document: built-in or uploaded. */
data class TemplateCandidate(
    val id: String,
    val name: String,
    /** File type of the template ("DOCX", "PDF", "XLSX", "PPTX"). */
    val fileType: String,
    val builtIn: Boolean,
    val categoryId: String?,
    val subcategoryId: String?
) {
    /** True when this template can format a document of [kind]. */
    fun fits(kind: OutputKind): Boolean = fileType.uppercase() in kind.templateFileTypes
}

data class TemplateChoice(val templateId: String?, val source: SelectionSource)

/**
 * Which template formats a document of [subcategoryId] in [format]:
 *  1. the template chosen for this document ([overrideId]);
 *  2. the user's default for the subcategory;
 *  3. the user's default for the main category;
 *  4. the built-in template that suits the subcategory best;
 *  5. none: the user is asked.
 * The older "default Word/PowerPoint/Excel template" setting of My Templates is not used here
 * (it applied one template to every kind of document); it still applies where no kind of
 * document is known (the offline formatter, Thesis Builder without a Smart Templates default).
 * A saved default that no longer exists or does not fit the output (e.g. a PowerPoint template
 * for a letter) is skipped, never used silently.
 */
object TemplateSelection {

    fun choose(
        subcategoryId: String,
        format: String,
        overrideId: String?,
        defaults: Map<String, String>,
        lookup: (String) -> TemplateCandidate?
    ): TemplateChoice {
        val kind = OutputKind.ofFormat(format)
        fun usable(id: String?): TemplateCandidate? = id?.let(lookup)?.takeIf { it.fits(kind) }
        usable(overrideId)?.let { return TemplateChoice(it.id, SelectionSource.OVERRIDE) }
        usable(defaults[subcategoryId])?.let { return TemplateChoice(it.id, SelectionSource.SUBCATEGORY_DEFAULT) }
        val categoryId = TemplateCatalog.subcategory(subcategoryId)?.categoryId
        if (categoryId != null) usable(defaults[categoryId])?.let { return TemplateChoice(it.id, SelectionSource.CATEGORY_DEFAULT) }
        BuiltInTemplates.bestFor(subcategoryId)?.takeIf { it.kind == kind }?.let { return TemplateChoice(it.id, SelectionSource.BUILT_IN_BEST_MATCH) }
        return TemplateChoice(null, SelectionSource.NONE)
    }
}
