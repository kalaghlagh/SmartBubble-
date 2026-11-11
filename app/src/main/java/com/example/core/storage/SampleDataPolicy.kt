package com.example.core.storage

import com.example.data.model.TemplateEntity

/** Template "default" slots. PDF has no separate slot in the current data model's usage. */
enum class DefaultSlot { WORD, PPT, EXCEL }

/**
 * Pure decision rules for sample data, kept free of Android/Room types so they are unit-testable.
 */
object SampleDataPolicy {
    const val SAMPLE_WORD_TEMPLATE_ID = "tmpl-company-report"
    const val SAMPLE_PPT_TEMPLATE_ID = "tmpl-presentation-theme"
    const val SAMPLE_EXCEL_TEMPLATE_ID = "tmpl-business-data"
    const val SAMPLE_CONVERSATION_ID = "session-smartbubble"

    val SAMPLE_TEMPLATE_IDS = setOf(SAMPLE_WORD_TEMPLATE_ID, SAMPLE_PPT_TEMPLATE_ID, SAMPLE_EXCEL_TEMPLATE_ID)

    /**
     * Samples are inserted only on a genuinely fresh install:
     *  - never once the persistent flag is set (so samples the user deleted never come back);
     *  - never on an existing install that predates the flag. Earlier versions created the
     *    `templates` directory on every launch and always stored rows, so either signal means
     *    the samples were already offered once and may have been deliberately deleted.
     */
    fun shouldInsertSamples(alreadySeeded: Boolean, templatesDirExists: Boolean, existingRowCount: Int): Boolean =
        !alreadySeeded && !templatesDirExists && existingRowCount == 0

    /**
     * When several templates claim the same default slot, choose the one to keep: prefer a
     * user's own template over a sample, then the most recently modified one.
     */
    fun chooseDefaultToKeep(candidates: List<TemplateEntity>): TemplateEntity? =
        candidates.sortedWith(
            compareBy<TemplateEntity> { it.id in SAMPLE_TEMPLATE_IDS }
                .thenByDescending { it.lastModified }
                .thenBy { it.id }
        ).firstOrNull()

    fun isDefault(template: TemplateEntity, slot: DefaultSlot): Boolean = when (slot) {
        DefaultSlot.WORD -> template.isDefaultWord
        DefaultSlot.PPT -> template.isDefaultPpt
        DefaultSlot.EXCEL -> template.isDefaultExcel
    }
}
