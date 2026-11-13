package com.example.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.template.domain.TemplateProfile
import com.example.template.preview.TemplatePreviewData
import kotlin.math.abs

/** Standard paper name for a page size in points, or null. */
internal fun paperName(widthPt: Int, heightPt: Int): String? {
    val (s, l) = if (widthPt <= heightPt) widthPt to heightPt else heightPt to widthPt
    val sizes = listOf("A3" to (842 to 1191), "A4" to (595 to 842), "A5" to (420 to 595), "Letter" to (612 to 792), "Legal" to (612 to 1008))
    return sizes.firstOrNull { (_, d) -> abs(d.first - s) <= 3 && abs(d.second - l) <= 3 }?.first
}

@Composable
private fun alignmentLabel(value: String): String = stringResource(
    when (value) {
        "center" -> R.string.pdf_align_center
        "end" -> R.string.pdf_align_end
        "justify" -> R.string.pdf_align_justify
        else -> R.string.pdf_align_start
    }
)

@Composable
private fun positionLabel(position: String): String {
    val zone = stringResource(if (position.startsWith("header")) R.string.pdf_zone_header else R.string.pdf_zone_footer)
    return "$zone · ${alignmentLabel(position.substringAfter('-'))}"
}

private fun parseColor(hex: String): Color? = try {
    Color(android.graphics.Color.parseColor(if (hex.startsWith("#")) hex else "#$hex"))
} catch (e: Exception) {
    null
}

@Composable
private fun Swatch(hex: String) {
    val c = parseColor(hex) ?: return
    Box(
        Modifier
            .size(14.dp)
            .clip(CircleShape)
            .background(c)
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
    )
    Spacer(Modifier.width(6.dp))
}

@Composable
private fun InfoRow(label: String, value: String, swatches: List<String> = emptyList(), tag: String? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .let { if (tag != null) it.testTag(tag) else it },
        verticalAlignment = Alignment.Top
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(min = 96.dp, max = 132.dp)
        )
        Spacer(Modifier.width(8.dp))
        swatches.forEach { Swatch(it) }
        Text(
            value,
            style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content),
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * Preview of a PDF template: its rendered first page (the original PDF is the visual
 * reference), a button to open the original, and every style value measured on it. Values that
 * could not be measured are shown as such; a scanned PDF is reported, not treated as empty.
 */
@Composable
fun PdfTemplatePreview(pdf: TemplatePreviewData.PdfPreview, pageImage: ImageBitmap?, onOpenOriginal: (() -> Unit)?) {
    val p: TemplateProfile = pdf.profile
    val none = stringResource(R.string.pdf_value_none)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.testTag("pdf_template_preview")) {
        // Page image.
        Card(
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            val ratio = if (p.pageWidthPt > 0 && p.pageHeightPt > 0) p.pageWidthPt.toFloat() / p.pageHeightPt else 0.707f
            if (pageImage != null) {
                Image(
                    bitmap = pageImage,
                    contentDescription = stringResource(R.string.template_pdf_first_page),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(ratio.coerceIn(0.3f, 3f))
                        .testTag("pdf_page_image")
                )
            } else {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(ratio.coerceIn(0.3f, 3f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stringResource(if (pdf.fileMissing) R.string.pdf_original_missing else R.string.pdf_page_image_unavailable),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.DarkGray,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }
        if (onOpenOriginal != null && !pdf.fileMissing) {
            OutlinedButton(onClick = onOpenOriginal, modifier = Modifier.fillMaxWidth().testTag("pdf_open_original")) {
                Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.pdf_open_original))
            }
        }

        if (p.isImageOnlyPdf || p.pdfTextStatus == "PARTIAL") {
            val message = stringResource(if (p.isImageOnlyPdf) R.string.pdf_no_text_detail else R.string.pdf_partial_detail)
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = message }.testTag("pdf_text_status")
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Info, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                    Spacer(Modifier.width(8.dp))
                    Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                }
            }
        }

        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth().testTag("pdf_profile_card")
        ) {
            Column(Modifier.padding(14.dp)) {
                Text(stringResource(R.string.pdf_extracted_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.pdf_extracted_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.size(6.dp))
                HorizontalDivider()
                val orientation = stringResource(if (p.pageWidthPt > p.pageHeightPt) R.string.pdf_landscape else R.string.pdf_portrait)
                val paper = paperName(p.pageWidthPt, p.pageHeightPt)
                InfoRow(
                    stringResource(R.string.pdf_label_page),
                    listOfNotNull(paper, orientation, "${p.pageWidthPt} × ${p.pageHeightPt} pt").joinToString(" · ") +
                        if (p.pdfPageCount > 0) " · " + stringResource(R.string.pdf_pages_count, p.pdfPageCount) else "",
                    tag = "pdf_row_page"
                )
                if (!p.isImageOnlyPdf) {
                    InfoRow(stringResource(R.string.pdf_label_margins), stringResource(R.string.pdf_margins_value, p.marginTopPt, p.marginBottomPt, p.marginLeftPt, p.marginRightPt))
                    InfoRow(stringResource(R.string.pdf_label_body), "${p.bodyFont} · ${p.baseFontSizePt} pt", listOf(p.textColorHex), tag = "pdf_row_body")
                    InfoRow(
                        stringResource(R.string.pdf_label_headings),
                        "${p.headingFont} · ${p.heading1SizePt} / ${p.heading2SizePt} pt" + if (p.headingBold) " · " + stringResource(R.string.pdf_bold) else "",
                        listOf(p.headingColorHex.ifEmpty { p.primaryColorHex })
                    )
                    InfoRow(stringResource(R.string.pdf_label_alignment), alignmentLabel(p.bodyAlignment) + " · " + stringResource(R.string.pdf_headings_short) + " " + alignmentLabel(p.headingAlignment))
                    InfoRow(
                        stringResource(R.string.pdf_label_spacing),
                        listOfNotNull(
                            if (p.lineSpacing > 0f) stringResource(R.string.pdf_line_spacing, String.format(java.util.Locale.ROOT, "%.2f", p.lineSpacing)) else null,
                            if (p.paragraphSpacingPt >= 0) stringResource(R.string.pdf_paragraph_spacing, p.paragraphSpacingPt) else null
                        ).joinToString(" · ").ifEmpty { none }
                    )
                    InfoRow(stringResource(R.string.pdf_label_header), p.headerText.ifBlank { none }, tag = "pdf_row_header")
                    InfoRow(stringResource(R.string.pdf_label_footer), p.footerText.ifBlank { none })
                    InfoRow(stringResource(R.string.pdf_label_page_numbers), if (p.pageNumberPosition.isEmpty()) none else positionLabel(p.pageNumberPosition))
                    InfoRow(stringResource(R.string.pdf_label_lists), p.listMarker.ifBlank { none })
                    InfoRow(
                        stringResource(R.string.pdf_label_table),
                        if (p.tableDetected) stringResource(R.string.pdf_table_detected, alignmentLabel(p.tableCellAlignment)) else none,
                        if (p.tableDetected) listOf(p.tableHeaderBgHex, p.tableBorderHex) else emptyList(),
                        tag = "pdf_row_table"
                    )
                    InfoRow(stringResource(R.string.pdf_label_direction), stringResource(if (p.rtl) R.string.pdf_rtl else R.string.pdf_ltr))
                }
                val shapes = p.decorations.split(';').filter { it.isNotBlank() }
                InfoRow(
                    stringResource(R.string.pdf_label_background),
                    listOfNotNull(
                        if (!p.backgroundColorHex.equals("#FFFFFF", true)) p.backgroundColorHex else null,
                        if (shapes.isNotEmpty()) stringResource(R.string.pdf_shapes_count, shapes.size) else null
                    ).joinToString(" · ").ifEmpty { none },
                    listOfNotNull(p.backgroundColorHex.takeIf { !it.equals("#FFFFFF", true) }) + shapes.mapNotNull { it.split(',').getOrNull(4) }.distinct().take(4)
                )
            }
        }
        Text(
            stringResource(R.string.pdf_fidelity_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
