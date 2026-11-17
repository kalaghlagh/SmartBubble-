package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.extraction.ExtractedPage
import com.example.extraction.PageSource
import com.example.extraction.Script

/*
 * What the extraction engine knows about a page, shown in the Chat scanner review and in the
 * Thesis Builder extraction review: how it was read, scripts, tables, confidence (only when the
 * engine gave one), recovered or re-read lines, problems, and whether a manual check is advised.
 */

@Composable
private fun scriptName(s: Script): String = stringResource(when (s) {
    Script.ARABIC -> R.string.dx_script_arabic
    Script.LATIN -> R.string.dx_script_latin
    Script.NUMERIC -> R.string.dx_script_numbers
    Script.MIXED -> R.string.dx_script_mixed
    Script.UNKNOWN -> R.string.dx_script_unknown
})

@Composable
fun extractionErrorText(code: String): String = stringResource(when (code) {
    "no_text_found" -> R.string.dx_error_no_text
    "image_unreadable" -> R.string.dx_error_image
    "pdf_unreadable", "page_unreadable" -> R.string.dx_error_pdf
    "out_of_memory" -> R.string.dx_error_memory
    else -> R.string.dx_error_other
})

/** What is known about how the page was read: source, scripts, tables, confidence, recoveries, review advice. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExtractionPageReport(p: ExtractedPage) {
    Card(Modifier.fillMaxWidth().testTag("dx_page_report"), shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = if (p.reviewRecommended) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(when (p.source) {
                PageSource.PDF_TEXT -> R.string.dx_source_pdf_text
                PageSource.PDF_OCR -> R.string.dx_source_pdf_ocr
                PageSource.IMAGE_OCR -> R.string.dx_source_image
            }), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            if (p.scripts.isNotEmpty()) Text(stringResource(R.string.dx_scripts, p.scripts.map { scriptName(it) }.joinToString(", ")), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.dx_tables_found, p.tables), style = MaterialTheme.typography.bodySmall)
            // A confidence is shown only when the engine gave one (never for PDF text).
            p.confidence?.let { Text(stringResource(R.string.dx_confidence, (it * 100).toInt()), style = MaterialTheme.typography.bodySmall) }
            Text(stringResource(when (p.level) {
                com.example.extraction.ReconstructionLevel.FORMATTING -> R.string.dx_level_formatting
                com.example.extraction.ReconstructionLevel.STRUCTURE -> R.string.dx_level_structure
                com.example.extraction.ReconstructionLevel.TEXT -> R.string.dx_level_text
                com.example.extraction.ReconstructionLevel.NONE -> R.string.dx_level_none
            }), style = MaterialTheme.typography.bodySmall)
            for (n in p.notes) {
                val (code, value) = n.substringBefore(':') to n.substringAfter(':', "")
                val t = when (code) {
                    "lines_recovered" -> stringResource(R.string.dx_note_recovered, value)
                    "arabic_lines_reread" -> stringResource(R.string.dx_note_arabic, value)
                    "latin_lines_reread" -> stringResource(R.string.dx_note_latin, value)
                    "deskewed" -> stringResource(R.string.dx_note_deskewed, value)
                    "rotated" -> stringResource(R.string.dx_note_rotated, value)
                    "table_uncertain" -> stringResource(R.string.dx_note_table_uncertain)
                    "pdf_incomplete" -> stringResource(R.string.dx_note_pdf_incomplete)
                    "lines_rerouted" -> stringResource(R.string.dx_note_rerouted, value)
                    "possible_missing_lines" -> stringResource(R.string.dx_note_missing, value)
                    else -> null
                }
                if (t != null) Text("• $t", style = MaterialTheme.typography.bodySmall)
            }
            if (p.regionsToReview > 0) Text(stringResource(R.string.dx_regions_to_review, p.regionsToReview), style = MaterialTheme.typography.bodySmall)
            if (p.uncertainWords > 0) Text(stringResource(R.string.dx_uncertain_words, p.uncertainWords), style = MaterialTheme.typography.bodySmall)
            p.error?.let { Text(extractionErrorText(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("dx_page_error")) }
            if (p.reviewRecommended) Text(stringResource(R.string.dx_review_recommended), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
        }
    }
}

