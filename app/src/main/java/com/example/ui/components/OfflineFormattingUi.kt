package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.offline.DocumentType
import com.example.offline.PreviewItem
import com.example.offline.document.OfflineFormat
import com.example.offline.document.OfflineFormattingUiState
import com.example.offline.document.OfflinePhase
import com.example.offline.document.TemplateMatcher
import com.example.offline.document.TemplateNote
import com.example.offline.document.TemplateSource

/** Small "Offline" label: the work runs on the device, with no internet or AI provider. */
@Composable
fun OfflineBadge(modifier: Modifier = Modifier) {
    val description = stringResource(R.string.offline_badge_desc)
    Surface(
        modifier = modifier
            .semantics { contentDescription = description }
            .testTag("offline_badge"),
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.CloudOff,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Spacer(Modifier.width(4.dp))
            Text(
                stringResource(R.string.offline_badge),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 1
            )
        }
    }
}

/**
 * Compact suggestion shown above the composer after a long paste. Nothing happens until the user
 * taps "Format Offline"; "Dismiss" only hides it (the pasted text stays in the composer).
 */
@Composable
fun OfflineFormattingSuggestion(
    analyzing: Boolean,
    onFormat: () -> Unit,
    onDismiss: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val description = stringResource(R.string.offline_suggestion_desc)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .semantics { contentDescription = description }
            .testTag("offline_suggestion"),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
        shadowElevation = 4.dp
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.offline_suggest_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                OfflineBadge()
            }
            if (analyzing) {
                val analyzingText = stringResource(R.string.offline_analyzing)
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(18.dp)
                            .semantics { contentDescription = analyzingText }
                            .testTag("offline_analyzing"),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(analyzingText, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = onCancel, modifier = Modifier.testTag("offline_cancel_analysis")) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            } else {
                Text(
                    stringResource(R.string.offline_suggest_message),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss, modifier = Modifier.testTag("offline_dismiss_button")) {
                        Text(stringResource(R.string.offline_dismiss), maxLines = 1)
                    }
                    Spacer(Modifier.width(4.dp))
                    Button(onClick = onFormat, modifier = Modifier.testTag("offline_format_button")) {
                        Text(stringResource(R.string.offline_format), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun documentTypeLabel(type: DocumentType): String = stringResource(
    when (type) {
        DocumentType.REPORT -> R.string.offline_type_report
        DocumentType.LETTER -> R.string.offline_type_letter
        DocumentType.PROPOSAL -> R.string.offline_type_proposal
        DocumentType.MEETING_NOTES -> R.string.offline_type_meeting
        DocumentType.STRUCTURED_DATA -> R.string.offline_type_data
        DocumentType.GENERAL -> R.string.offline_type_general
    }
)

@Composable
fun templateNoteText(note: TemplateNote): String = when (note) {
    is TemplateNote.Incompatible -> stringResource(R.string.offline_note_incompatible, note.templateName, note.templateType, note.format.name)
    TemplateNote.NotFound -> stringResource(R.string.offline_note_not_found)
    is TemplateNote.FileMissing -> stringResource(R.string.offline_note_file_missing, note.templateName)
    TemplateNote.PdfProfileOnly -> stringResource(R.string.offline_note_pdf)
    is TemplateNote.StyleOnly -> stringResource(R.string.offline_note_style_only, note.templateName)
    is TemplateNote.LayoutNotUsed -> stringResource(R.string.offline_note_layout_not_used, note.templateName)
    is TemplateNote.NoTemplate -> stringResource(R.string.offline_note_no_template, note.format.templateType)
}

/** Text of the pasted content: its own direction (Arabic right to left) whatever the app language. */
@Composable
private fun contentStyle() = LocalTextStyle.current.copy(textDirection = TextDirection.Content)

/**
 * Review screen: detected structure, format, template, preview of every block, then
 * "Apply Formatting" (generate) and "Save to Library". Cancel is available until saving starts.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OfflineFormattingDialog(
    state: OfflineFormattingUiState,
    onSelectFormat: (OfflineFormat) -> Unit,
    onSelectTemplate: (String?) -> Unit,
    onApply: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit
) {
    val preview = state.preview ?: return
    val busy = state.phase == OfflinePhase.GENERATING || state.phase == OfflinePhase.SAVING
    Dialog(
        onDismissRequest = { if (state.phase != OfflinePhase.SAVING) onCancel() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .testTag("offline_preview_dialog"),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(Modifier.statusBarsPadding().navigationBarsPadding()) {
                // Top bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onCancel, enabled = state.phase != OfflinePhase.SAVING) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_cancel))
                    }
                    Text(
                        stringResource(R.string.offline_preview_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .weight(1f)
                            .semantics { heading() }
                    )
                    OfflineBadge(Modifier.padding(end = 12.dp))
                }
                HorizontalDivider()

                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .testTag("offline_preview_list"),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item(key = "summary") { PreviewSummary(state) }
                    item(key = "format") {
                        FormatAndTemplate(state, enabled = state.phase == OfflinePhase.PREVIEW, onSelectFormat, onSelectTemplate)
                    }
                    item(key = "divider") { HorizontalDivider() }
                    items(preview.items, key = { it.key }) { PreviewRow(it) }
                }

                HorizontalDivider()
                BottomActions(state, busy, onApply, onSave, onCancel)
            }
        }
    }
}

@Composable
private fun PreviewSummary(state: OfflineFormattingUiState) {
    val preview = state.preview ?: return
    val result = preview.result
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.offline_detected, documentTypeLabel(result.documentType.type)),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            stringResource(R.string.offline_counts, preview.headingCount, preview.listCount, preview.tableCount, preview.paragraphCount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            stringResource(R.string.offline_rules_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            if (result.usedFallback) stringResource(R.string.offline_fallback)
            else stringResource(R.string.offline_preserved, result.preservation.sourceWordCount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.testTag("offline_preservation")
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FormatAndTemplate(
    state: OfflineFormattingUiState,
    enabled: Boolean,
    onSelectFormat: (OfflineFormat) -> Unit,
    onSelectTemplate: (String?) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.offline_format_label), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.formats.forEach { format ->
                FilterChip(
                    selected = format == state.format,
                    onClick = { onSelectFormat(format) },
                    enabled = enabled,
                    label = { Text(format.name) },
                    modifier = Modifier.testTag("offline_format_chip_${format.name}")
                )
            }
        }
        when (state.format) {
            OfflineFormat.PPTX -> Hint(stringResource(R.string.offline_hint_pptx))
            OfflineFormat.XLSX -> Hint(stringResource(R.string.offline_hint_xlsx))
            else -> Unit
        }

        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.offline_select_template), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = state.selectedTemplateId == null,
                onClick = { onSelectTemplate(null) },
                enabled = enabled,
                label = { Text(stringResource(R.string.offline_template_default_option), maxLines = 1) },
                modifier = Modifier.testTag("offline_template_chip_default")
            )
            state.templateOptions.forEach { template ->
                FilterChip(
                    selected = state.selectedTemplateId == template.id,
                    onClick = { onSelectTemplate(template.id) },
                    enabled = enabled,
                    label = { Text(template.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp)) },
                    modifier = Modifier.testTag("offline_template_chip_${template.id}")
                )
            }
            FilterChip(
                selected = state.selectedTemplateId == TemplateMatcher.BUILT_IN_ID,
                onClick = { onSelectTemplate(TemplateMatcher.BUILT_IN_ID) },
                enabled = enabled,
                label = { Text(stringResource(R.string.offline_builtin), maxLines = 1) },
                modifier = Modifier.testTag("offline_template_chip_builtin")
            )
        }
        state.template?.let { match ->
            Text(
                when (match.source) {
                    TemplateSource.EXPLICIT -> stringResource(R.string.offline_source_explicit, match.entity?.name.orEmpty())
                    TemplateSource.DEFAULT -> stringResource(R.string.offline_source_default, match.entity?.name.orEmpty())
                    TemplateSource.BUILT_IN -> stringResource(R.string.offline_source_builtin)
                },
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.testTag("offline_template_source")
            )
            match.notes.forEach { Hint(templateNoteText(it)) }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun PreviewRow(item: PreviewItem) {
    when (item) {
        is PreviewItem.Heading -> {
            val label = when {
                item.isTitle -> stringResource(R.string.offline_title_label)
                item.inferred -> stringResource(R.string.offline_heading_inferred, item.confidencePercent)
                else -> stringResource(R.string.offline_heading_explicit)
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (item.inferred) MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f) else MaterialTheme.colorScheme.background,
                        RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            ) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                Text(
                    item.text,
                    style = contentStyle(),
                    fontWeight = FontWeight.Bold,
                    fontSize = when {
                        item.isTitle -> 22.sp
                        item.level == 1 -> 19.sp
                        item.level == 3 -> 15.sp
                        else -> 17.sp
                    },
                    modifier = Modifier.semantics { heading() }
                )
            }
        }
        is PreviewItem.Paragraph -> Text(item.text, style = contentStyle().copy(fontSize = 14.sp, lineHeight = 20.sp))
        is PreviewItem.ListEntry -> Column {
            if (item.startsList) {
                Text(
                    stringResource(if (item.ordered) R.string.offline_list_numbered else R.string.offline_list_bulleted),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
            Row(Modifier.padding(start = (12 + item.level * 16).dp)) {
                Text(item.marker, style = contentStyle(), fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                Text(item.text, style = contentStyle().copy(fontSize = 14.sp), modifier = Modifier.weight(1f))
            }
        }
        is PreviewItem.Table -> Column {
            Text(
                stringResource(R.string.offline_table_label, item.confidencePercent),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary
            )
            Column(
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(top = 4.dp)
            ) {
                (listOf(item.header) + item.rows).forEachIndexed { r, row ->
                    Row(
                        Modifier.background(
                            if (r == 0) MaterialTheme.colorScheme.primaryContainer
                            else if (r % 2 == 0) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            else MaterialTheme.colorScheme.background
                        )
                    ) {
                        row.forEach { cell ->
                            Box(Modifier.width(128.dp).padding(6.dp)) {
                                Text(
                                    cell,
                                    style = contentStyle().copy(fontSize = 13.sp),
                                    fontWeight = if (r == 0) FontWeight.SemiBold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BottomActions(
    state: OfflineFormattingUiState,
    busy: Boolean,
    onApply: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        val draft = state.draft
        if (state.phase == OfflinePhase.READY && draft != null) {
            Text(
                stringResource(R.string.offline_ready, draft.file.name, (draft.file.length() / 1024).toInt()),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.testTag("offline_ready_text")
            )
            if (draft.templateApplied && draft.template.entity != null) {
                Hint(stringResource(R.string.offline_template_applied, draft.template.entity.name))
            }
            draft.notes.filterIsInstance<TemplateNote.LayoutNotUsed>().forEach { Hint(templateNoteText(it)) }
            Hint(stringResource(R.string.offline_ready_hint))
            Spacer(Modifier.height(8.dp))
        }
        val progressText = when (state.phase) {
            OfflinePhase.GENERATING -> stringResource(R.string.offline_generating)
            OfflinePhase.SAVING -> stringResource(R.string.offline_saving)
            else -> null
        }
        if (progressText != null) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(18.dp)
                        .semantics { contentDescription = progressText },
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.width(10.dp))
                Text(progressText, style = MaterialTheme.typography.bodySmall)
            }
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
        ) {
            OutlinedButton(
                onClick = onCancel,
                enabled = state.phase != OfflinePhase.SAVING,
                modifier = Modifier.testTag("offline_cancel_button")
            ) { Text(stringResource(R.string.action_cancel), maxLines = 1) }
            if (state.phase == OfflinePhase.READY || state.phase == OfflinePhase.SAVING) {
                Button(
                    onClick = onSave,
                    enabled = !busy,
                    modifier = Modifier.testTag("offline_save_button")
                ) { Text(stringResource(R.string.offline_save), maxLines = 1) }
            } else {
                Button(
                    onClick = onApply,
                    enabled = !busy && state.template != null,
                    modifier = Modifier.testTag("offline_apply_button")
                ) { Text(stringResource(R.string.offline_apply), maxLines = 1) }
            }
        }
    }
}
