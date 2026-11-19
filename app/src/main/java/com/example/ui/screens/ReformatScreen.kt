package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.agent.office.BlockType
import com.example.data.model.TemplateEntity
import com.example.reformat.ExportFormat
import com.example.reformat.ExportPreview
import com.example.reformat.ReformatCore
import com.example.reformat.ReformatState
import com.example.reformat.SourceKind
import com.example.reformat.WorkBlock
import com.example.ui.components.InlineSelect
import com.example.ui.components.SelectGroup
import com.example.ui.components.SelectOption

/** Document types Insert File accepts: Word and PDF. */
val REFORMAT_FILE_TYPES = arrayOf("application/pdf", "application/vnd.openxmlformats-officedocument.wordprocessingml.document")

/** The bottom-navigation destination: wires the workspace to the ViewModel and the system pickers. */
@Composable
fun ReformatScreen(viewModel: com.example.ui.SmartBubbleViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.reformat.state.collectAsState()
    val templates by viewModel.allTemplates.collectAsState()
    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { viewModel.reformatImportFile(it) } }
    val imageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { viewModel.reformatImportImage(it) } }
    ReformatWorkspace(
        state = state,
        templates = templates.filter { it.fileType.uppercase() in setOf("DOCX", "PDF") },
        onInsertFile = { fileLauncher.launch(REFORMAT_FILE_TYPES) },
        onImage = { imageLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        onEditText = viewModel.reformat::editText,
        onEditCell = viewModel.reformat::editCell,
        onReset = viewModel.reformat::resetEdits,
        onRemoveSource = viewModel.reformat::removeSource,
        onFormatting = viewModel.reformat::setFormatting,
        onCreate = viewModel.reformat::create,
        onClosePreview = viewModel.reformat::closePreview,
        onSave = viewModel.reformat::saveToLibrary,
        onOpenSaved = viewModel::openDocumentById,
        onDismissError = viewModel.reformat::clearError,
        modifier = modifier
    )
}

/**
 * The Reformatting workspace: Insert File (Word, PDF) or Image (photo, scan read by OCR), the
 * imported content as editable blocks (uncertain OCR readings marked), the Formatting choice
 * (Keep Source Formatting or a Library template), Reset, and Create (PDF or DOC) with a checked
 * preview before saving to the Library. Stateless: everything comes from [state].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReformatWorkspace(
    state: ReformatState,
    templates: List<TemplateEntity>,
    onInsertFile: () -> Unit,
    onImage: () -> Unit,
    onEditText: (Int, String) -> Unit,
    onEditCell: (Int, Int, Int, String) -> Unit,
    onReset: () -> Unit,
    onRemoveSource: () -> Unit,
    onFormatting: (String?) -> Unit,
    onCreate: (ExportFormat) -> Unit,
    onClosePreview: () -> Unit,
    onSave: () -> Unit,
    onOpenSaved: (String) -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier
) {
    var askFormat by rememberSaveable { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Column(modifier.fillMaxSize().background(colors.background).imePadding().testTag("reformat_screen")) {
        Surface(color = colors.surface, tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.EditNote, contentDescription = null, tint = colors.primary)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.reformat_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).semantics { heading() })
                if (state.busy) CircularProgressIndicator(Modifier.size(22.dp).testTag("reformat_busy"), strokeWidth = 2.dp)
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().testTag("reformat_list"),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(key = "import") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.source == null) Text(stringResource(R.string.reformat_intro), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(onClick = onInsertFile, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp).testTag("reformat_insert_file")) {
                            Icon(Icons.Default.NoteAdd, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.reformat_insert_file))
                        }
                        FilledTonalButton(onClick = onImage, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp).testTag("reformat_image")) {
                            Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.reformat_image))
                        }
                    }
                }
            }
            state.source?.let { src ->
                item(key = "source") { SourceCard(state, onRemoveSource, onReset) }
                item(key = "formatting") {
                    val keep = SelectOption("keep", stringResource(R.string.reformat_keep_source), stringResource(R.string.reformat_keep_source_desc), Icons.Default.Verified)
                    val options = templates.map { t -> SelectOption(t.id, t.name, t.fileType.uppercase(), Icons.Default.Palette) }
                    val selected = state.templateId?.let { id -> templates.firstOrNull { it.id == id } }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.reformat_formatting), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        InlineSelect(
                            triggerLabel = selected?.name ?: stringResource(R.string.reformat_keep_source),
                            triggerIcon = if (selected == null) Icons.Default.Verified else Icons.Default.Palette,
                            groups = listOf(
                                SelectGroup(null, listOf(keep)),
                                SelectGroup(stringResource(R.string.reformat_library_templates), options)
                            ),
                            selectedKey = state.templateId ?: "keep",
                            onSelect = { key -> onFormatting(key.takeIf { it != "keep" }) },
                            testTag = "reformat_formatting",
                            optionTag = { "reformat_format_$it" },
                            enabled = !state.busy
                        )
                        if (templates.isEmpty()) Text(stringResource(R.string.reformat_no_templates), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        val note = when {
                            selected != null -> stringResource(R.string.reformat_template_note, ReformatCore.mapping(ReformatCore.current(state.blocks)).let { "${it.headings}/${it.paragraphs}/${it.tables}" })
                            src.kind == SourceKind.DOCX -> stringResource(R.string.reformat_keep_docx_note)
                            else -> stringResource(R.string.reformat_reconstruct_note)
                        }
                        Text(note, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, modifier = Modifier.testTag("reformat_formatting_note"))
                    }
                }
                items(state.blocks, key = { "b" + it.block.index }) { w -> BlockEditor(w, src.kind, onEditText, onEditCell) }
            }
        }
        if (state.source != null) {
            Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(if (state.hasEdits) R.string.reformat_unsaved_edits else R.string.reformat_no_edits),
                        style = MaterialTheme.typography.bodySmall, color = if (state.hasEdits) colors.primary else colors.onSurfaceVariant,
                        modifier = Modifier.weight(1f).testTag("reformat_edit_state")
                    )
                    Button(onClick = { askFormat = true }, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp).testTag("reformat_create")) {
                        Text(stringResource(R.string.reformat_create))
                    }
                }
            }
        }
    }
    if (askFormat) {
        AlertDialog(
            onDismissRequest = { askFormat = false },
            modifier = Modifier.testTag("reformat_format_dialog"),
            title = { Text(stringResource(R.string.reformat_create)) },
            text = { Text(stringResource(R.string.reformat_choose_output)) },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { askFormat = false; onCreate(ExportFormat.PDF) }, modifier = Modifier.heightIn(min = 48.dp).testTag("reformat_create_pdf")) { Text("PDF") }
                    Button(onClick = { askFormat = false; onCreate(ExportFormat.DOCX) }, modifier = Modifier.heightIn(min = 48.dp).testTag("reformat_create_docx")) { Text(stringResource(R.string.reformat_doc)) }
                }
            },
            dismissButton = { TextButton(onClick = { askFormat = false }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }
    state.preview?.let { PreviewDialog(it, state, onClosePreview, onSave, onOpenSaved) }
    state.error?.let { code ->
        AlertDialog(
            onDismissRequest = onDismissError,
            modifier = Modifier.testTag("reformat_error"),
            text = { Text(reformatErrorText(code)) },
            confirmButton = { TextButton(onClick = onDismissError) { Text(stringResource(R.string.action_ok)) } }
        )
    }
}

@Composable
private fun SourceCard(state: ReformatState, onRemove: () -> Unit, onReset: () -> Unit) {
    val src = state.source ?: return
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth().testTag("reformat_source")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (src.kind == SourceKind.IMAGE) Icons.Default.Image else Icons.Default.Description, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(src.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(R.string.reformat_source_info, src.kind.name, src.pages), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                androidx.compose.material3.IconButton(onClick = onRemove, modifier = Modifier.size(48.dp).testTag("reformat_remove_source")) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.reformat_remove_source))
                }
            }
            if (src.scanned || src.kind == SourceKind.IMAGE) Text(stringResource(R.string.reformat_scanned_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.uncertainBlocks > 0) Text(stringResource(R.string.reformat_uncertain, state.uncertainBlocks), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("reformat_uncertain"))
            TextButton(onClick = onReset, enabled = state.hasEdits && !state.busy, modifier = Modifier.heightIn(min = 48.dp).testTag("reformat_reset")) {
                Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.reformat_reset))
            }
        }
    }
}

/** One block: a field the user edits directly; the text keeps its own direction (Arabic right to left). */
@Composable
private fun BlockEditor(w: WorkBlock, kind: SourceKind, onEditText: (Int, String) -> Unit, onEditCell: (Int, Int, Int, String) -> Unit) {
    val b = w.block
    val style = when (b.type) {
        BlockType.HEADING -> MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, textDirection = TextDirection.Content)
        else -> MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content)
    }
    val label = stringResource(
        when (b.type) {
            BlockType.HEADING -> R.string.reformat_block_heading
            BlockType.LIST_ITEM -> R.string.reformat_block_list
            BlockType.TABLE -> R.string.reformat_block_table
            else -> R.string.reformat_block_paragraph
        }
    )
    if (b.type == BlockType.TABLE) {
        // Word tables keep their own formatting when unchanged; they are edited through a template.
        val editable = kind != SourceKind.DOCX
        Column(Modifier.fillMaxWidth().testTag("reformat_table_${b.index}"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // Wide tables scroll sideways inside their own area instead of pushing the page.
            Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                w.rows.forEachIndexed { r, cells ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        cells.forEachIndexed { c, v ->
                            OutlinedTextField(
                                value = v, onValueChange = { onEditCell(b.index, r, c, it) }, readOnly = !editable,
                                textStyle = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content),
                                modifier = Modifier.widthIn(min = 96.dp, max = 220.dp).testTag("reformat_cell_${b.index}_${r}_$c")
                            )
                        }
                    }
                }
            }
            if (!editable) Text(stringResource(R.string.reformat_docx_table_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val support: (@Composable () -> Unit)? = if (b.uncertain) { { Text(stringResource(R.string.reformat_check_reading)) } } else null
    OutlinedTextField(
        value = w.text,
        onValueChange = { onEditText(b.index, it) },
        textStyle = style,
        label = { Text(label) },
        isError = b.uncertain,
        supportingText = support,
        modifier = Modifier.fillMaxWidth().testTag("reformat_block_${b.index}")
    )
}

@Composable
private fun PreviewDialog(p: ExportPreview, state: ReformatState, onClose: () -> Unit, onSave: () -> Unit, onOpenSaved: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        modifier = Modifier.testTag("reformat_preview"),
        title = { Text(stringResource(if (p.valid) R.string.reformat_preview_title else R.string.reformat_preview_problem)) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.reformat_preview_info, if (p.format == ExportFormat.PDF) "PDF" else "DOCX",
                    p.templateName ?: stringResource(R.string.reformat_keep_source)), style = MaterialTheme.typography.bodySmall)
                (p.issues + p.notes).forEach { code ->
                    Text(reformatNoteText(code), style = MaterialTheme.typography.bodySmall,
                        color = if (code in p.issues) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("reformat_note_" + code.substringBefore(':')))
                }
                Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(8.dp)).padding(8.dp)) {
                    Text(p.text.take(4_000).ifBlank { stringResource(R.string.reformat_preview_no_text) },
                        style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content), modifier = Modifier.testTag("reformat_preview_text"))
                }
                if (p.savedDocumentId != null) Text(stringResource(R.string.reformat_saved), color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("reformat_saved"))
            }
        },
        confirmButton = {
            if (p.savedDocumentId != null) TextButton(onClick = { onOpenSaved(p.savedDocumentId) }, modifier = Modifier.testTag("reformat_open_saved")) { Text(stringResource(R.string.reformat_open)) }
            else Button(onClick = onSave, enabled = p.valid && !state.busy, modifier = Modifier.heightIn(min = 48.dp).testTag("reformat_save")) { Text(stringResource(R.string.reformat_save_library)) }
        },
        dismissButton = { TextButton(onClick = onClose, modifier = Modifier.testTag("reformat_back_to_edit")) { Text(stringResource(R.string.reformat_back_to_edit)) } }
    )
}

@Composable
private fun reformatNoteText(code: String): String {
    val n = code.substringAfter(':', "")
    return when (code.substringBefore(':')) {
        "reconstructed" -> stringResource(R.string.reformat_note_reconstructed)
        "template_applied" -> stringResource(R.string.reformat_note_template)
        "template_partial" -> stringResource(R.string.reformat_note_template_partial)
        "docx_table_edits_need_template" -> stringResource(R.string.reformat_note_table_edits)
        "some_edits_not_mapped" -> stringResource(R.string.reformat_note_not_mapped)
        "text_not_verifiable" -> stringResource(R.string.reformat_note_text_not_verifiable)
        "content_missing" -> stringResource(R.string.reformat_note_content_missing, n.toIntOrNull() ?: 0)
        "arabic_glyph_forms", "arabic_order" -> stringResource(R.string.reformat_note_arabic)
        "arabic_direction" -> stringResource(R.string.reformat_note_arabic_direction, n.toIntOrNull() ?: 0)
        "parts_lost" -> stringResource(R.string.reformat_note_parts_lost)
        "output_unreadable", "output_missing" -> stringResource(R.string.reformat_note_unreadable)
        else -> code
    }
}

@Composable
fun reformatErrorText(code: String): String = stringResource(
    when (code.substringBefore(':')) {
        "source_unavailable" -> R.string.reformat_error_unavailable
        "unsupported_format", "not_an_image" -> R.string.reformat_error_unsupported
        "encrypted_pdf" -> R.string.agent_error_encrypted_pdf
        "file_too_large", "document_too_large" -> R.string.reformat_error_too_large
        "document_empty" -> R.string.reformat_error_empty
        "file_damaged", "extraction_failed" -> R.string.reformat_error_damaged
        "source_changed" -> R.string.reformat_error_source_changed
        "template_missing", "template_unsupported" -> R.string.reformat_error_template_missing
        "edit_not_applied" -> R.string.reformat_error_edit_not_applied
        "invalid_result" -> R.string.reformat_note_unreadable
        else -> R.string.reformat_error_generic
    }
)
