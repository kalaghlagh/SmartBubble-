package com.example.template.analysis

import com.example.extraction.ExtractedBlock
import com.example.extraction.ExtractedDocument
import com.example.template.domain.TemplateProfile

/**
 * Structure of a scanned (image-only) PDF template, read by the shared extraction engine: text
 * direction, whether it holds tables, its list marker, and a summary of its headings, tables
 * and lists. Only structure is taken; the recognized text itself never becomes template content,
 * and fonts, colours and margins stay unknown (a scan does not carry them).
 */
object ScannedTemplateStructure {

    fun apply(profile: TemplateProfile, doc: ExtractedDocument): TemplateProfile {
        val blocks = doc.pages.flatMap { it.blocks }
        if (blocks.isEmpty()) return profile
        val directional = blocks.filter { it !is ExtractedBlock.PageNumber }
        val rtl = directional.count { it.rtl } * 2 > directional.size
        val headings = blocks.count { it is ExtractedBlock.Heading }
        val tables = blocks.count { it is ExtractedBlock.Table }
        val items = blocks.filterIsInstance<ExtractedBlock.ListItem>()
        val marker = items.groupingBy { it.marker.trim() }.eachCount().maxByOrNull { it.value }?.key.orEmpty()
        val summary = "Scanned layout read on the device: $headings headings, $tables tables, ${items.size} list items" +
            (if (rtl) ", right to left" else "")
        return profile.copy(
            rtl = rtl,
            tableDetected = profile.tableDetected || tables > 0,
            listMarker = profile.listMarker.ifEmpty { marker },
            extractedPropertiesSummary = listOf(profile.extractedPropertiesSummary, summary).filter { it.isNotBlank() }.joinToString("; ")
        )
    }
}
