package com.example.thesis.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.example.R
import com.example.extraction.ExtractedBlock
import com.example.extraction.ExtractedPage
import com.example.extraction.ExtractionSource
import com.example.extraction.ExtractionText
import com.example.extraction.PageSource
import com.example.extraction.Script
import com.example.extraction.TableCell
import com.example.thesis.core.ExtractionInsertMode

/** The app's extractor, as the controller takes it. */
internal fun ThesisHost.extractor(): com.example.thesis.Extractor = { sources, lang, progress -> extractDocument(sources, lang, progress) }

/**
 * "Extract from Image/PDF": asks for images (one or several pages) or a PDF, then starts the
 * extraction for the open section at the cursor ([cursor]); [sync] saves the editor text into
 * the draft first.
 */
@Composable
internal fun ExtractChooser(host: ThesisHost, cursor: () -> Int, sync: () -> Unit, onDismiss: () -> Unit, onPdf: (Uri) -> Unit) {
    val controller = host.thesis
    fun start(sources: List<ExtractionSource>) {
        sync()
        controller.startExtraction(sources, host.ocrLanguageCode, cursor(), host.extractor())
    }
    val images = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(30)) { uris: List<Uri> ->
        onDismiss()
        if (uris.isNotEmpty()) start(uris.map { ExtractionSource(it, isPdf = false) })
    }
    val pdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        onDismiss()
        // A PDF opens in the page viewer: the user sees its pages and extracts the one chosen.
        if (uri != null) onPdf(uri)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dx_extract_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.dx_extract_intro), style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = { images.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("dx_pick_images")) {
                    Icon(Icons.Outlined.Image, contentDescription = null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.dx_pick_images))
                }
                OutlinedButton(onClick = { pdf.launch(arrayOf("application/pdf")) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("dx_pick_pdf")) {
                    Icon(Icons.Outlined.PictureAsPdf, contentDescription = null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.dx_pick_pdf))
                }
                Text(stringResource(R.string.dx_on_device_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}

/** Editable table: every cell, rows and columns can be added or removed. */
@Composable
private fun TableEditor(t: ExtractedBlock.Table, onChange: (ExtractedBlock.Table) -> Unit) {
    val width = t.columns.coerceAtLeast(1)
    // Cells spread over their columns for editing (merged cells become separate cells).
    val grid = t.rows.map { r ->
        val cells = ArrayList<String>()
        for (c in r) { cells += c.text; repeat(c.colSpan - 1) { cells += "" } }
        (cells + List((width - cells.size).coerceAtLeast(0)) { "" }).take(width)
    }
    // Cells to check (uncertain reading), by position; a corrected cell is no longer marked.
    val review = t.rows.map { r -> r.flatMap { c -> List(c.colSpan) { c.needsReview } } }
    fun emit(g: List<List<String>>) = onChange(t.copy(rows = g.mapIndexed { ri, r -> r.mapIndexed { ci, v ->
        TableCell(v, needsReview = review.getOrNull(ri)?.getOrNull(ci) == true && grid.getOrNull(ri)?.getOrNull(ci) == v)
    } }))
    Column(Modifier.fillMaxWidth()) {
        if (t.uncertain) Text(stringResource(R.string.dx_table_check), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
        if (t.rows.any { r -> r.any { it.colSpan > 1 } }) Text(stringResource(R.string.dx_table_merged), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            grid.forEachIndexed { r, row ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    row.forEachIndexed { c, cell ->
                        OutlinedTextField(
                            value = cell,
                            onValueChange = { v -> emit(grid.mapIndexed { i, rr -> if (i == r) rr.mapIndexed { j, x -> if (j == c) v else x } else rr }) },
                            textStyle = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content, fontWeight = if (r == 0 && t.header) FontWeight.Bold else null),
                            // An uncertain reading is outlined: compare it with the original page.
                            isError = review.getOrNull(r)?.getOrNull(c) == true,
                            modifier = Modifier.width(140.dp).padding(2.dp).testTag("dx_cell_${r}_$c")
                        )
                    }
                    IconButton(onClick = { emit(grid.filterIndexed { i, _ -> i != r }) }, enabled = grid.size > 1, modifier = Modifier.testTag("dx_row_delete_$r")) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.dx_delete_row))
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { emit(grid + listOf(List(width) { "" })) }, modifier = Modifier.testTag("dx_add_row")) {
                Icon(Icons.Default.Add, null); Text(stringResource(R.string.dx_add_row))
            }
            TextButton(onClick = { emit(grid.map { it + "" }) }, modifier = Modifier.testTag("dx_add_column")) {
                Icon(Icons.Default.Add, null); Text(stringResource(R.string.dx_add_column))
            }
            if (width > 1) TextButton(onClick = { emit(grid.map { it.dropLast(1) }) }, modifier = Modifier.testTag("dx_remove_column")) {
                Text(stringResource(R.string.dx_remove_column))
            }
        }
    }
}

@Composable
private fun BlockEditor(b: ExtractedBlock, onChange: (ExtractedBlock?) -> Unit, tag: String) {
    // Each block is an item of a LazyColumn, measured with an unbounded height: its field is
    // bounded to part of the screen and scrolls inside, however long the block.
    val maxBlockHeight = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.6f).dp
    val label = stringResource(when (b) {
        is ExtractedBlock.Heading -> R.string.dx_block_heading
        is ExtractedBlock.Paragraph -> R.string.dx_block_paragraph
        is ExtractedBlock.ListItem -> R.string.dx_block_list
        is ExtractedBlock.Table -> R.string.dx_block_table
        is ExtractedBlock.Caption -> R.string.dx_block_caption
        is ExtractedBlock.PageNumber -> R.string.dx_block_page_number
    })
    Column(Modifier.fillMaxWidth().testTag(tag)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label + if (b is ExtractedBlock.Heading) " ${b.level}" else "", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
            if (b.region?.needsReview == true) Text(stringResource(R.string.dx_check_region), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.padding(end = 4.dp).testTag("${tag}_review"))
            IconButton(onClick = { onChange(null) }, modifier = Modifier.size(36.dp).testTag("${tag}_delete")) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.dx_remove_block), modifier = Modifier.size(18.dp))
            }
        }
        val style = MaterialTheme.typography.bodyMedium.copy(textDirection = if (b.rtl) TextDirection.Rtl else TextDirection.Content)
        when (b) {
            is ExtractedBlock.Table -> TableEditor(b) { onChange(it) }
            is ExtractedBlock.Heading -> OutlinedTextField(b.text, { onChange(b.copy(text = it)) }, textStyle = style.copy(fontWeight = FontWeight.Bold), modifier = Modifier.fillMaxWidth().heightIn(max = maxBlockHeight).testTag("${tag}_text"))
            is ExtractedBlock.Paragraph -> {
                OutlinedTextField(b.text, { onChange(b.copy(text = it, uncertainWords = emptyList())) }, textStyle = style, modifier = Modifier.fillMaxWidth().heightIn(max = maxBlockHeight).testTag("${tag}_text"))
                if (b.uncertainWords.isNotEmpty()) Text(stringResource(R.string.dx_check_words, b.uncertainWords.take(8).joinToString("، ")),
                    style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content), color = MaterialTheme.colorScheme.tertiary)
            }
            is ExtractedBlock.ListItem -> OutlinedTextField(b.text, { onChange(b.copy(text = it)) }, textStyle = style, prefix = { Text(b.marker + " ") }, modifier = Modifier.fillMaxWidth().heightIn(max = maxBlockHeight).testTag("${tag}_text"))
            is ExtractedBlock.Caption -> OutlinedTextField(b.text, { onChange(b.copy(text = it)) }, textStyle = style, modifier = Modifier.fillMaxWidth().heightIn(max = maxBlockHeight).testTag("${tag}_text"))
            is ExtractedBlock.PageNumber -> Text(b.text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * Review of extracted content before it goes into the section it was started from: progress
 * (with Cancel), what was found on each page, the original page image, editable text and
 * tables, reading a page again in another language, and the ways to insert or copy it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ExtractionReviewDialog(host: ThesisHost) {
    val controller = host.thesis
    val state by controller.state.collectAsState()
    val x = state.extraction ?: return
    val context = LocalContext.current
    val sectionTitle = state.snapshot?.let { s -> s.section(x.sectionId)?.let { com.example.thesis.core.ThesisStructure.displayTitle(it, com.example.thesis.core.ThesisStructure.numbering(s), s.meta.language) } }.orEmpty()
    var pageIndex by remember(x.document) { mutableStateOf(x.document?.pages?.firstOrNull()?.index ?: 0) }
    var showOriginal by remember { mutableStateOf(false) }
    var confirmRetry by remember { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = { controller.cancelExtraction() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().testTag("dx_review"), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                BlueTopBar(stringResource(R.string.dx_review_title), onBack = { controller.cancelExtraction() })
                Text(stringResource(R.string.dx_for_section, sectionTitle), style = MaterialTheme.typography.titleSmall.copy(textDirection = TextDirection.Content),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("dx_target_section"))
                val doc = x.document
                when {
                    x.running -> Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(if (x.total > 0) stringResource(R.string.dx_progress, x.page, x.total) else stringResource(R.string.dx_starting), modifier = Modifier.testTag("dx_progress"))
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(stringResource(R.string.dx_keep_working), style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { controller.cancelExtraction() }, modifier = Modifier.heightIn(min = 48.dp).testTag("dx_cancel")) { Text(stringResource(R.string.action_cancel)) }
                    }
                    doc == null -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(when (x.error) {
                            "ocr_unavailable" -> R.string.scan_ocr_unavailable
                            else -> R.string.dx_failed
                        }), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("dx_error"))
                        OutlinedButton(onClick = { controller.cancelExtraction() }) { Text(stringResource(R.string.action_close)) }
                    }
                    else -> {
                        val page = doc.pages.firstOrNull { it.index == pageIndex } ?: doc.pages.first()
                        Column(Modifier.weight(1f)) {
                            if (x.error == "no_text") Text(stringResource(R.string.dx_no_text_anywhere), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp).testTag("dx_no_text"))
                            if (doc.pages.size > 1) FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                doc.pages.forEach { p ->
                                    FilterChip(selected = p.index == page.index, onClick = { pageIndex = p.index },
                                        label = { Text(stringResource(R.string.dx_page_n, p.index + 1) + if (p.error != null || p.reviewRecommended) " ⚠" else "") },
                                        modifier = Modifier.testTag("dx_page_${p.index}"))
                                }
                            }
                            LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                item { com.example.ui.components.ExtractionPageReport(page) }
                                item {
                                    // Read this page again in another language mode (other pages and corrections are kept).
                                    Column {
                                        Text(stringResource(R.string.dx_retry_page), style = MaterialTheme.typography.labelLarge)
                                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            listOf("auto" to R.string.dx_lang_auto, "ar" to R.string.dx_lang_ar, "fr" to R.string.dx_lang_fr, "en" to R.string.dx_lang_en).forEach { (code, label) ->
                                                FilterChip(selected = false, enabled = x.retryingPage == null, onClick = {
                                                    if (page.index in x.editedPages) confirmRetry = code
                                                    else controller.retryExtractedPage(page.index, code, host.extractor())
                                                }, label = { Text(stringResource(label)) }, modifier = Modifier.testTag("dx_retry_$code"))
                                            }
                                        }
                                        if (x.retryingPage == page.index) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("dx_retrying"))
                                        page.imageUri?.let {
                                            TextButton(onClick = { showOriginal = !showOriginal }, modifier = Modifier.testTag("dx_show_original")) {
                                                Text(stringResource(if (showOriginal) R.string.dx_hide_original else R.string.dx_show_original))
                                            }
                                            if (showOriginal) AsyncImage(model = it, contentDescription = stringResource(R.string.dx_original_page), modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp))
                                        }
                                    }
                                }
                                itemsIndexed(page.blocks) { i, b ->
                                    BlockEditor(b, { nb -> controller.updateExtractedBlock(page.index, i, nb) }, "dx_block_$i")
                                }
                            }
                        }
                        HorizontalDivider()
                        // Insertion: always into the section the extraction was started from.
                        val tables = doc.pages.sumOf { it.tables }
                        FlowRow(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(onClick = { controller.insertExtraction(ExtractionInsertMode.AT_CURSOR) }, modifier = Modifier.heightIn(min = 48.dp).testTag("dx_insert_cursor")) { Text(stringResource(R.string.dx_insert_cursor)) }
                            OutlinedButton(onClick = { controller.insertExtraction(ExtractionInsertMode.APPEND) }, modifier = Modifier.heightIn(min = 48.dp).testTag("dx_insert_append")) { Text(stringResource(R.string.dx_insert_append)) }
                            OutlinedButton(onClick = { controller.insertExtraction(ExtractionInsertMode.AS_PARAGRAPHS) }, modifier = Modifier.heightIn(min = 48.dp).testTag("dx_insert_paragraphs")) { Text(stringResource(R.string.dx_insert_paragraphs)) }
                            OutlinedButton(onClick = { controller.insertExtraction(ExtractionInsertMode.HEADING_AND_CONTENT) }, modifier = Modifier.heightIn(min = 48.dp).testTag("dx_insert_heading")) { Text(stringResource(R.string.dx_insert_heading)) }
                            OutlinedButton(onClick = { controller.insertExtraction(ExtractionInsertMode.TABLES_ONLY) }, enabled = tables > 0, modifier = Modifier.heightIn(min = 48.dp).testTag("dx_insert_tables")) { Text(stringResource(R.string.dx_insert_tables, tables)) }
                            OutlinedButton(onClick = {
                                (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.setPrimaryClip(ClipData.newPlainText("SmartBubble", ExtractionText.plain(doc)))
                                android.widget.Toast.makeText(context, context.getString(R.string.scan_text_copied), android.widget.Toast.LENGTH_SHORT).show()
                            }, modifier = Modifier.heightIn(min = 48.dp).testTag("dx_copy")) { Text(stringResource(R.string.scan_copy_text)) }
                            TextButton(onClick = { controller.cancelExtraction() }, modifier = Modifier.heightIn(min = 48.dp).testTag("dx_cancel_review")) { Text(stringResource(R.string.action_cancel)) }
                        }
                    }
                }
            }
        }
    }
    confirmRetry?.let { code ->
        AlertDialog(
            onDismissRequest = { confirmRetry = null },
            title = { Text(stringResource(R.string.dx_retry_page)) },
            text = { Text(stringResource(R.string.dx_retry_discards)) },
            confirmButton = { Button(onClick = { confirmRetry = null; controller.retryExtractedPage(pageIndex, code, host.extractor()) }, modifier = Modifier.testTag("dx_retry_confirm")) { Text(stringResource(R.string.dx_retry_anyway)) } },
            dismissButton = { TextButton(onClick = { confirmRetry = null }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }
}
