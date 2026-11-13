package com.example.thesis.model

/** Academic document type. */
enum class AcademicLevel { LICENCE, MASTER, DOCTORATE }

/**
 * Kind of a thesis section. Generated sections (cover, indexes) are produced from the project
 * data when the document is rendered; users do not write into them.
 */
enum class SectionKind(val preliminary: Boolean = false, val generated: Boolean = false, val container: Boolean = false) {
    COVER(preliminary = true, generated = true),
    DEDICATION(preliminary = true),
    ACKNOWLEDGEMENTS(preliminary = true),
    ABSTRACT(preliminary = true),
    TABLE_OF_CONTENTS(preliminary = true, generated = true),
    LIST_OF_FIGURES(preliminary = true, generated = true),
    LIST_OF_TABLES(preliminary = true, generated = true),
    ABBREVIATIONS(preliminary = true),
    INTRODUCTION,
    PART(container = true),
    CHAPTER(container = true),
    SECTION(container = true),
    SUBSECTION,
    CONCLUSION,
    REFERENCES,
    APPENDIX,
    CUSTOM;

    /** Content can be written into this section. */
    val writable: Boolean get() = !generated

    companion object {
        fun parse(value: String?): SectionKind = values().firstOrNull { it.name == value } ?: CUSTOM
    }
}

enum class ElementType {
    HEADING, PARAGRAPH, LIST_ITEM, IMAGE, TABLE, REFERENCE;

    companion object {
        fun parse(value: String?): ElementType = values().firstOrNull { it.name == value } ?: PARAGRAPH
    }
}

/** Where a change came from (shown in the history). */
enum class ChangeSource { USER, AI, FORMATTING, IMAGE, TABLE, STRUCTURE, RESTORE;
    companion object { fun parse(v: String?) = values().firstOrNull { it.name == v } ?: USER }
}

enum class ProposalStatus { PENDING, NEEDS_DESTINATION, ACCEPTED, REJECTED, CONFLICT;
    companion object { fun parse(v: String?) = values().firstOrNull { it.name == v } ?: PENDING }
}

/** Academic metadata of a thesis project (everything optional except the title). */
data class ThesisMeta(
    val title: String,
    val level: AcademicLevel = AcademicLevel.MASTER,
    /** Language of the thesis content: "ar", "fr" or "en" (independent of the app language). */
    val language: String = "en",
    val studentName: String = "",
    val university: String = "",
    val faculty: String = "",
    val department: String = "",
    val specialization: String = "",
    val supervisor: String = "",
    val coSupervisor: String = "",
    val academicYear: String = "",
    /** One jury member per line. */
    val juryMembers: String = "",
    /** Additional "Label: value" lines shown on the cover. */
    val extraMetadata: String = "",
    val logoAssetId: String? = null,
    /** Template bound from My Templates (null = default template, then built-in formatting). */
    val templateId: String? = null
) {
    val rtl: Boolean get() = language == "ar"
}

/** A section of the outline. [parentId] nests parts, chapters, sections and subsections. */
data class ThesisSection(
    val id: String,
    val parentId: String?,
    val kind: SectionKind,
    val title: String,
    val position: Int,
    val enabled: Boolean = true
)

/**
 * Structured payload of an element. Only the fields of its type are used: heading level, list
 * marker, image reference with caption and size, table cells, or reference status.
 */
data class ElementData(
    val headingLevel: Int = 0,
    val ordered: Boolean = false,
    val marker: String = "",
    val assetId: String? = null,
    val caption: String = "",
    val source: String = "",
    /** Image width as a percentage of the text width. */
    val widthPercent: Int = 80,
    /** "start", "center" or "end". */
    val align: String = "center",
    val tableHeader: List<String> = emptyList(),
    val tableRows: List<List<String>> = emptyList(),
    /** A reference the user confirmed as checked (AI-suggested references start unverified). */
    val verified: Boolean = false
) {
    companion object { val EMPTY = ElementData() }
}

/** One content element with a stable id (never derived from a page number). */
data class ThesisElement(
    val id: String,
    val sectionId: String,
    val type: ElementType,
    val position: Int,
    val text: String,
    val data: ElementData = ElementData.EMPTY
)

/** An image stored in the project's private folder (the original file is never changed). */
data class ThesisAsset(
    val id: String,
    val originalName: String,
    val path: String,
    val width: Int,
    val height: Int,
    val mimeType: String
)

/** The complete content of a thesis at one revision. */
data class ThesisSnapshot(
    val meta: ThesisMeta,
    val sections: List<ThesisSection>,
    val elements: List<ThesisElement>
) {
    private val sectionIndex by lazy { sections.associateBy { it.id } }
    private val elementIndex by lazy { elements.associateBy { it.id } }

    fun section(id: String?): ThesisSection? = id?.let { sectionIndex[it] }
    fun element(id: String?): ThesisElement? = id?.let { elementIndex[it] }

    fun children(parentId: String?): List<ThesisSection> = sections.filter { it.parentId == parentId }.sortedBy { it.position }

    fun elementsOf(sectionId: String): List<ThesisElement> = elements.filter { it.sectionId == sectionId }.sortedBy { it.position }

    /** Sections in reading order (depth-first), each with its depth. */
    fun outline(includeDisabled: Boolean = true): List<Pair<ThesisSection, Int>> {
        val out = ArrayList<Pair<ThesisSection, Int>>()
        fun walk(parent: String?, depth: Int) {
            for (s in children(parent)) {
                if (!includeDisabled && !s.enabled) continue
                out.add(s to depth)
                if (depth < 8) walk(s.id, depth + 1)
            }
        }
        walk(null, 0)
        return out
    }

    /** Elements in document order (enabled sections only). */
    fun orderedElements(): List<ThesisElement> = outline(includeDisabled = false).flatMap { elementsOf(it.first.id) }

    /** Fingerprint of one section's content, used to detect changes made after a proposal. */
    fun sectionHash(sectionId: String): String {
        val s = section(sectionId) ?: return "missing"
        val content = buildString {
            append(s.title).append('|').append(s.enabled).append('|').append(s.parentId).append('\n')
            for (e in elementsOf(sectionId)) append(e.id).append('|').append(e.type).append('|').append(e.text).append('|').append(e.data).append('\n')
        }
        return sha1(content)
    }

    companion object {
        fun sha1(text: String): String =
            java.security.MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
