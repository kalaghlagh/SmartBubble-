package com.example.thesis.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.School
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.data.model.TemplateEntity
import com.example.data.model.ThesisRevisionEntity
import com.example.template.storage.TemplateThumbnails
import com.example.thesis.ProposalReview
import com.example.thesis.ThesisBusy
import com.example.thesis.ThesisController
import com.example.thesis.ThesisExportResult
import com.example.thesis.ThesisScreen
import com.example.thesis.core.ChangeKind
import com.example.thesis.core.EditMode
import com.example.thesis.core.ElementChange
import com.example.thesis.core.NewElement
import com.example.thesis.core.SegmentKind
import com.example.thesis.core.ThesisDiffResult
import com.example.thesis.core.ThesisOp
import com.example.thesis.core.ThesisOperations
import com.example.thesis.core.ThesisStructure
import com.example.thesis.model.AcademicLevel
import com.example.thesis.model.ChangeSource
import com.example.thesis.model.ElementData
import com.example.thesis.model.ElementType
import com.example.thesis.model.ProposalStatus
import com.example.thesis.model.SectionKind
import com.example.thesis.model.ThesisAsset
import com.example.thesis.model.ThesisElement
import com.example.thesis.model.ThesisMeta
import com.example.thesis.model.ThesisSection
import com.example.thesis.model.ThesisSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

/** Content of the thesis follows its own direction (Arabic right to left) whatever the app language. */
@Composable
internal fun contentStyle() = LocalTextStyle.current.copy(textDirection = TextDirection.Content)

@Composable
internal fun levelLabel(level: String) = stringResource(
    when (level) { "LICENCE" -> R.string.thesis_level_licence; "DOCTORATE" -> R.string.thesis_level_doctorate; else -> R.string.thesis_level_master }
)

@Composable
internal fun sourceLabel(source: String) = stringResource(
    when (ChangeSource.parse(source)) {
        ChangeSource.USER -> R.string.thesis_source_user
        ChangeSource.AI -> R.string.thesis_source_ai
        ChangeSource.FORMATTING -> R.string.thesis_source_formatting
        ChangeSource.IMAGE -> R.string.thesis_source_image
        ChangeSource.TABLE -> R.string.thesis_source_table
        ChangeSource.STRUCTURE -> R.string.thesis_source_structure
        ChangeSource.RESTORE -> R.string.thesis_source_restore
    }
)

internal fun formatDate(time: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(time))

/** Small image preview decoded at reduced size (the stored original is untouched). */
@Composable
fun ThesisImageThumb(path: String?, modifier: Modifier = Modifier, maxPx: Int = 480) {
    val bitmap by produceState<ImageBitmap?>(null, path) {
        value = path?.let { p ->
            withContext(Dispatchers.IO) {
                try {
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(p, o)
                    var sample = 1
                    while (o.outWidth / (sample * 2) >= maxPx) sample *= 2
                    BitmapFactory.decodeFile(p, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
                } catch (e: Throwable) { null }
            }
        }
    }
    val b = bitmap
    if (b != null) Image(b, contentDescription = stringResource(R.string.thesis_image), modifier = modifier, contentScale = ContentScale.Fit)
    else Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) { Text(stringResource(R.string.thesis_image), style = MaterialTheme.typography.labelSmall) }
}

@Composable
fun BusyBar(controller: ThesisController, busy: ThesisBusy) {
    val label = stringResource(
        when (busy) {
            ThesisBusy.ANALYZING -> R.string.thesis_busy_analyzing
            ThesisBusy.ASKING_AI -> R.string.thesis_busy_ai
            ThesisBusy.EXPORTING -> R.string.thesis_busy_exporting
            ThesisBusy.PREVIEWING -> R.string.thesis_busy_preview
            ThesisBusy.SAVING -> R.string.thesis_busy_saving
        }
    )
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).semantics { contentDescription = label }.testTag("thesis_busy"), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        if (busy != ThesisBusy.SAVING) TextButton(onClick = { controller.cancel() }, modifier = Modifier.testTag("thesis_stop")) { Text(stringResource(R.string.thesis_stop)) }
    }
}

// ------------------------------------------------------------------ metadata form (create / settings)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ThesisMetaForm(
    host: ThesisHost,
    initial: ThesisMeta?,
    initialAi: Boolean = true,
    busy: Boolean,
    saveLabel: Int,
    onCancel: () -> Unit,
    onOpenExisting: (() -> Unit)? = null,
    header: @Composable () -> Unit = {},
    onSave: (ThesisMeta, Boolean) -> Unit
) {
    val templates by host.templateList.collectAsState()
    var meta by remember(initial) { mutableStateOf(initial ?: ThesisMeta("", language = host.defaultContentLanguage.takeIf { it in listOf("ar", "fr", "en") } ?: "en")) }
    var ai by remember(initial) { mutableStateOf(initialAi) }
    var titleError by remember { mutableStateOf(false) }
    val usable = templates.filter { it.fileType.equals("DOCX", true) || it.fileType.equals("PDF", true) }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            header()
            onOpenExisting?.let {
                OutlinedButton(onClick = it, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("thesis_open_existing")) { Text(stringResource(R.string.thesis_open_existing)) }
            }
            @Composable
            fun field(label: Int, value: String, tag: String, lines: Int = 1, onChange: (String) -> Unit) = OutlinedTextField(
                value = value, onValueChange = onChange, label = { Text(stringResource(label)) }, singleLine = lines == 1, minLines = lines,
                textStyle = contentStyle(), modifier = Modifier.fillMaxWidth().testTag(tag)
            )
            OutlinedTextField(
                value = meta.title, onValueChange = { meta = meta.copy(title = it); if (it.isNotBlank()) titleError = false },
                label = { Text(stringResource(R.string.thesis_field_title) + " *") }, isError = titleError,
                supportingText = { if (titleError) Text(stringResource(R.string.thesis_title_required), modifier = Modifier.testTag("thesis_title_error")) },
                textStyle = contentStyle(), modifier = Modifier.fillMaxWidth().testTag("thesis_field_title")
            )
            Text(stringResource(R.string.thesis_field_level), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AcademicLevel.values().forEach { l ->
                    FilterChip(selected = meta.level == l, onClick = { meta = meta.copy(level = l) }, label = { Text(levelLabel(l.name)) }, modifier = Modifier.testTag("thesis_level_${l.name}"))
                }
            }
            Text(stringResource(R.string.thesis_field_language), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("ar" to "العربية", "fr" to "Français", "en" to "English").forEach { (code, name) ->
                    FilterChip(selected = meta.language == code, onClick = { meta = meta.copy(language = code) }, label = { Text(name) }, modifier = Modifier.testTag("thesis_lang_$code"))
                }
            }
            Text(stringResource(R.string.thesis_field_template), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = meta.templateId == null, onClick = { meta = meta.copy(templateId = null) }, label = { Text(stringResource(R.string.thesis_template_default)) })
                usable.forEach { t: TemplateEntity ->
                    FilterChip(selected = meta.templateId == t.id, onClick = { meta = meta.copy(templateId = t.id) },
                        label = { Text("${t.name} (${t.fileType})", maxLines = 1, overflow = TextOverflow.Ellipsis) }, modifier = Modifier.testTag("thesis_template_${t.id}"))
                }
            }
            Text(stringResource(R.string.thesis_template_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            field(R.string.thesis_field_subtitle, meta.subtitle, "thesis_field_subtitle") { meta = meta.copy(subtitle = it) }
            field(R.string.thesis_field_student, meta.studentName, "thesis_field_student") { meta = meta.copy(studentName = it) }
            field(R.string.thesis_field_student_id, meta.studentId, "thesis_field_student_id") { meta = meta.copy(studentId = it) }
            field(R.string.thesis_field_university, meta.university, "thesis_field_university") { meta = meta.copy(university = it) }
            field(R.string.thesis_field_faculty, meta.faculty, "thesis_field_faculty") { meta = meta.copy(faculty = it) }
            field(R.string.thesis_field_department, meta.department, "thesis_field_department") { meta = meta.copy(department = it) }
            field(R.string.thesis_field_specialization, meta.specialization, "thesis_field_specialization") { meta = meta.copy(specialization = it) }
            field(R.string.thesis_field_supervisor, meta.supervisor, "thesis_field_supervisor") { meta = meta.copy(supervisor = it) }
            field(R.string.thesis_field_cosupervisor, meta.coSupervisor, "thesis_field_cosupervisor") { meta = meta.copy(coSupervisor = it) }
            field(R.string.thesis_field_year, meta.academicYear, "thesis_field_year") { meta = meta.copy(academicYear = it) }
            field(R.string.thesis_field_submission_date, meta.submissionDate, "thesis_field_submission_date") { meta = meta.copy(submissionDate = it) }
            field(R.string.thesis_field_jury, meta.juryMembers, "thesis_field_jury", 3) { meta = meta.copy(juryMembers = it) }
            field(R.string.thesis_field_extra, meta.extraMetadata, "thesis_field_extra", 2) { meta = meta.copy(extraMetadata = it) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.thesis_ai_assist), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.thesis_ai_assist_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = ai, onCheckedChange = { ai = it }, modifier = Modifier.testTag("thesis_ai_switch"))
            }
        }
        HorizontalDivider()
        // Fixed action bar: Create stays visible however long the form is.
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onCancel, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp).testTag("thesis_cancel_meta")) { Text(stringResource(R.string.action_cancel)) }
            Button(
                onClick = {
                    if (meta.title.isBlank()) titleError = true
                    else onSave(meta.copy(title = meta.title.trim()), ai)
                },
                // Disabled while saving: tapping again cannot create a second project.
                enabled = !busy,
                modifier = Modifier.heightIn(min = 48.dp).testTag("thesis_save_meta")
            ) {
                if (busy) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                Text(stringResource(saveLabel))
            }
        }
    }
}

/** Shown only after the file was written and saved to the Library. */
@Composable
internal fun ExportResultCard(host: ThesisHost, e: ThesisExportResult) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val mime = if (e.format == "PDF") "application/pdf" else "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    // Storage Access Framework: the user chooses where the copy goes (Downloads, Drive, ...).
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mime)) { uri: Uri? ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(uri)?.use { out -> File(e.document.filePath).inputStream().use { it.copyTo(out) } } != null
                } catch (ex: Exception) { false }
            }
            android.widget.Toast.makeText(context, context.getString(if (ok) R.string.thesis_saved_to_device else R.string.thesis_save_device_failed), android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    Card(Modifier.fillMaxWidth().testTag("thesis_export_result"), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))) {
        Column(Modifier.padding(12.dp)) {
            Text(stringResource(R.string.thesis_export_saved, e.format, e.document.title), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            e.notes.distinct().forEach { Text(noteText(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { host.openExportedDocument(e.document) }, modifier = Modifier.testTag("thesis_export_open")) { Text(stringResource(R.string.thesis_open_file)) }
                TextButton(onClick = { host.shareExportedDocument(e.document) }, modifier = Modifier.testTag("thesis_export_share")) { Text(stringResource(R.string.thesis_share_file)) }
                TextButton(onClick = { saver.launch(File(e.document.filePath).name) }, modifier = Modifier.testTag("thesis_export_save_device")) { Text(stringResource(R.string.thesis_save_to_device)) }
            }
        }
    }
}

@Composable
internal fun DeleteProjectDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.thesis_delete_project_title)) },
        text = { Text(stringResource(R.string.thesis_delete_project_text)) },
        confirmButton = { Button(onClick = onConfirm, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error), modifier = Modifier.testTag("thesis_delete_project_confirm")) { Text(stringResource(R.string.template_delete)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}

// ------------------------------------------------------------------ pending changes

@Composable
internal fun ThesisChangesScreen(controller: ThesisController) {
    val proposals by controller.openProposals.collectAsState()
    LazyColumn(Modifier.fillMaxSize().testTag("thesis_changes"), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text(stringResource(R.string.thesis_changes_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (proposals.isEmpty()) item { Text(stringResource(R.string.thesis_no_pending), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("thesis_no_pending")) }
        items(proposals, key = { it.id }) { p ->
            Card(Modifier.fillMaxWidth().clickable { controller.openReview(p.id) }.testTag("thesis_pending_${p.id}"),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f))) {
                Column(Modifier.padding(12.dp)) {
                    Text(sourceLabel(p.source) + " · " + statusLabel(p.status), style = MaterialTheme.typography.labelMedium)
                    Text(formatDate(p.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(p.request.take(160), style = contentStyle().copy(fontSize = 13.sp), maxLines = 3, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = { controller.openReview(p.id) }, modifier = Modifier.testTag("thesis_open_review_${p.id}")) { Text(stringResource(R.string.thesis_review_open)) }
                }
            }
        }
    }
}

@Composable
internal fun statusLabel(status: String) = stringResource(
    when (ProposalStatus.parse(status)) {
        ProposalStatus.NEEDS_DESTINATION -> R.string.thesis_status_needs_destination
        ProposalStatus.CONFLICT -> R.string.thesis_status_conflict
        ProposalStatus.ACCEPTED -> R.string.thesis_status_accepted
        ProposalStatus.REJECTED -> R.string.thesis_status_rejected
        ProposalStatus.PENDING -> R.string.thesis_status_pending
    }
)

@Composable
internal fun TextInputDialog(title: String, initial: String, lines: Int = 1, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, minLines = lines, textStyle = contentStyle(), modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).testTag("thesis_text_input")) },
        confirmButton = { Button(onClick = { onConfirm(text) }, modifier = Modifier.testTag("thesis_text_confirm")) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}

@Composable
fun ElementBody(e: ThesisElement, assets: List<ThesisAsset>, caption: String?) {
    when (e.type) {
        ElementType.HEADING -> Text(e.text, style = contentStyle(), fontWeight = FontWeight.Bold)
        ElementType.LIST_ITEM -> Text((if (e.data.ordered) e.data.marker else "•") + "  " + e.text, style = contentStyle().copy(fontSize = 14.sp))
        ElementType.IMAGE -> Column {
            ThesisImageThumb(assets.firstOrNull { it.id == e.data.assetId }?.path, Modifier.fillMaxWidth().height(160.dp))
            caption?.let { Text(it, style = contentStyle().copy(fontSize = 12.sp), fontWeight = FontWeight.Medium) }
        }
        ElementType.TABLE -> Column {
            caption?.let { Text(it, style = contentStyle().copy(fontSize = 12.sp), fontWeight = FontWeight.Medium) }
            Column(Modifier.horizontalScroll(rememberScrollState())) {
                (listOf(e.data.tableHeader) + e.data.tableRows).forEachIndexed { r, row ->
                    Row(Modifier.background(if (r == 0) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)) {
                        row.forEach { c -> Text(c, style = contentStyle().copy(fontSize = 12.sp), modifier = Modifier.width(110.dp).padding(4.dp), fontWeight = if (r == 0) FontWeight.Bold else FontWeight.Normal) }
                    }
                }
            }
        }
        else -> Text(e.text, style = contentStyle().copy(fontSize = 14.sp))
    }
}

/** Caption, source, width and alignment of a figure (or caption of a table). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MediaDataDialog(data: ElementData, isImage: Boolean, onDismiss: () -> Unit, onSave: (ElementData) -> Unit) {
    var caption by remember { mutableStateOf(data.caption) }
    var source by remember { mutableStateOf(data.source) }
    var width by remember { mutableIntStateOf(data.widthPercent) }
    var align by remember { mutableStateOf(data.align) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (isImage) R.string.thesis_figure_settings else R.string.thesis_table_caption)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(caption, { caption = it }, label = { Text(stringResource(R.string.thesis_caption)) }, textStyle = contentStyle(), modifier = Modifier.fillMaxWidth().testTag("thesis_caption_input"))
                if (isImage) {
                    OutlinedTextField(source, { source = it }, label = { Text(stringResource(R.string.thesis_image_source)) }, textStyle = contentStyle(), modifier = Modifier.fillMaxWidth())
                    Text(stringResource(R.string.thesis_image_width, width))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(40, 60, 80, 100).forEach { w -> FilterChip(selected = width == w, onClick = { width = w }, label = { Text("$w%") }) }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("start" to R.string.pdf_align_start, "center" to R.string.pdf_align_center, "end" to R.string.pdf_align_end).forEach { (a, l) ->
                            FilterChip(selected = align == a, onClick = { align = a }, label = { Text(stringResource(l)) })
                        }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onSave(data.copy(caption = caption.trim(), source = source.trim(), widthPercent = width, align = align)) }, modifier = Modifier.testTag("thesis_media_save")) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}

/** Table cells edited as lines of "cell | cell"; values are kept exactly as typed. */
@Composable
internal fun TableEditDialog(e: ThesisElement, onDismiss: () -> Unit, onSave: (ElementData) -> Unit) {
    var caption by remember { mutableStateOf(e.data.caption) }
    var text by remember { mutableStateOf((listOf(e.data.tableHeader) + e.data.tableRows).joinToString("\n") { it.joinToString(" | ") }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.thesis_edit_table)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(caption, { caption = it }, label = { Text(stringResource(R.string.thesis_caption)) }, textStyle = contentStyle(), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(text, { text = it }, minLines = 4, textStyle = contentStyle(), modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp))
                Text(stringResource(R.string.thesis_table_hint), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(onClick = {
                val rows = text.lines().filter { it.isNotBlank() }.map { l -> l.split('|').map { it.trim() } }
                if (rows.isNotEmpty()) onSave(e.data.copy(caption = caption.trim(), tableHeader = rows.first(), tableRows = rows.drop(1)))
            }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}

// ------------------------------------------------------------------ review

@Composable
internal fun noteText(code: String): String {
    val parts = code.split(':')
    return when (parts[0]) {
        "pdf_profile" -> stringResource(R.string.thesis_tpl_pdf_profile, parts.getOrElse(1) { "" })
        "pdf_image_only" -> stringResource(R.string.offline_note_pdf_image_only, parts.getOrElse(1) { "" })
        "file_missing" -> stringResource(R.string.offline_note_file_missing, parts.getOrElse(1) { "" })
        "incompatible" -> stringResource(R.string.thesis_tpl_incompatible, parts.getOrElse(1) { "" }, parts.getOrElse(2) { "" })
        "not_found" -> stringResource(R.string.offline_note_not_found)
        "builtin" -> stringResource(R.string.thesis_tpl_builtin)
        "template_footer_added" -> stringResource(R.string.thesis_tpl_footer_added)
        "template_unusable" -> stringResource(R.string.thesis_tpl_unusable)
        "image_missing" -> stringResource(R.string.thesis_warn_image_missing)
        "logo_missing" -> stringResource(R.string.thesis_warn_image_missing)
        "crossref_missing" -> stringResource(R.string.thesis_warn_crossref)
        "duplicate" -> stringResource(R.string.thesis_warn_duplicate)
        "citation_removed" -> stringResource(R.string.thesis_warn_citation_removed)
        "citation_added" -> stringResource(R.string.thesis_warn_citation_added)
        "number_changed" -> stringResource(R.string.thesis_warn_number_changed)
        "number_added" -> stringResource(R.string.thesis_warn_number_added)
        "to_verify" -> stringResource(R.string.thesis_warn_to_verify)
        "ignored" -> stringResource(R.string.thesis_warn_ignored)
        else -> code
    }
}

@Composable
internal fun explanationText(code: String): String = when {
    code.startsWith("local:") -> stringResource(R.string.thesis_expl_local)
    code.startsWith("ai:") -> stringResource(R.string.thesis_expl_ai, code.removePrefix("ai:"))
    code.startsWith("ask:") -> code.removePrefix("ask:").ifBlank { stringResource(R.string.thesis_expl_ask) }
    else -> code
}

@Composable
fun ThesisReviewDialog(controller: ThesisController, review: ProposalReview) {
    val state by controller.state.collectAsState()
    val p = review.proposal
    val status = ProposalStatus.parse(p.status)
    val ops = remember(p.payload) { ThesisOperations.decode(p.payload) }
    val added = ops.filterIsInstance<ThesisOp.AddElements>().flatMap { it.elements }
    var choosing by remember { mutableStateOf(status == ProposalStatus.NEEDS_DESTINATION) }
    var editingMedia by remember { mutableStateOf<NewElement?>(null) }
    val numbering = remember(review.before) { ThesisStructure.numbering(review.before) }
    Dialog(onDismissRequest = { controller.closeReview() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().testTag("thesis_review"), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { controller.closeReview() }) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_close)) }
                    Text(stringResource(R.string.thesis_review_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).semantics { heading() })
                }
                state.busy?.let { BusyBar(controller, it) }
                HorizontalDivider()
                LazyColumn(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        Text(sourceLabel(p.source) + " · " + statusLabel(p.status), style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("thesis_review_status"))
                        if (p.request.isNotBlank()) Text(p.request.take(300), style = contentStyle().copy(fontSize = 13.sp), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        Text(explanationText(p.explanation), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.thesis_review_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        if (review.conflicts.isNotEmpty() || status == ProposalStatus.CONFLICT) {
                            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().testTag("thesis_conflict")) {
                                Column(Modifier.padding(10.dp)) {
                                    Text(stringResource(R.string.thesis_conflict_text), style = MaterialTheme.typography.bodySmall)
                                    TextButton(onClick = { controller.recheck(p.id) }, modifier = Modifier.testTag("thesis_recheck")) { Text(stringResource(R.string.thesis_recheck)) }
                                }
                            }
                        }
                        (p.warnings.lines().filter { it.isNotBlank() }.map { it.substringBefore(':') }.distinct()).forEach {
                            Text("⚠ " + noteText(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                        }
                        if (added.isNotEmpty()) {
                            val dest = ops.filterIsInstance<ThesisOp.AddElements>().firstOrNull()?.sectionId
                            val destTitle = review.before.section(dest)?.let { ThesisStructure.displayTitle(it, numbering, review.before.meta.language) }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.thesis_destination, destTitle ?: stringResource(R.string.thesis_destination_none)),
                                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).testTag("thesis_destination"))
                                TextButton(onClick = { choosing = true }, modifier = Modifier.testTag("thesis_choose_destination")) { Text(stringResource(R.string.thesis_change_destination)) }
                            }
                        }
                    }
                    if (status == ProposalStatus.NEEDS_DESTINATION || review.after == null) {
                        items(added, key = { it.id }) { n ->
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(10.dp)) {
                                    ElementBody(ThesisElement(n.id, "", n.type, 0, n.text, n.data), state.assets, null)
                                    if (n.type == ElementType.IMAGE || n.type == ElementType.TABLE) TextButton(onClick = { editingMedia = n }) { Text(stringResource(R.string.thesis_caption_edit)) }
                                }
                            }
                        }
                    } else {
                        val diff = review.diff
                        val after = review.after
                        if (diff != null) {
                            item { DiffSummary(diff) }
                            items(diff.elements, key = { it.elementId }) { c ->
                                ChangeCard(c, state.assets, c.after?.let { e -> captionFor(e, after) })
                                if (c.kind == ChangeKind.ADDED && (c.after?.type == ElementType.IMAGE || c.after?.type == ElementType.TABLE)) {
                                    TextButton(onClick = { editingMedia = added.firstOrNull { it.id == c.elementId } }, modifier = Modifier.testTag("thesis_caption_${c.elementId}")) { Text(stringResource(R.string.thesis_caption_edit)) }
                                }
                            }
                        }
                    }
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    OutlinedButton(onClick = { controller.reject(p.id) }, enabled = state.busy == null, modifier = Modifier.testTag("thesis_reject")) { Text(stringResource(R.string.thesis_reject)) }
                    Button(
                        onClick = { controller.accept(p.id) },
                        enabled = state.busy == null && status == ProposalStatus.PENDING && review.after != null && review.conflicts.isEmpty(),
                        modifier = Modifier.testTag("thesis_accept")
                    ) { Text(stringResource(R.string.thesis_accept)) }
                }
            }
        }
    }
    if (choosing) DestinationPicker(review.before, onDismiss = { choosing = false }) { sectionId -> choosing = false; controller.setDestination(p.id, sectionId, null) }
    editingMedia?.let { n ->
        MediaDataDialog(n.data, isImage = n.type == ElementType.IMAGE, onDismiss = { editingMedia = null }) { data -> editingMedia = null; controller.editProposalElement(p.id, n.id, data) }
    }
}

internal fun captionFor(e: ThesisElement, snapshot: ThesisSnapshot): String? {
    val n = ThesisStructure.numbering(snapshot)
    return when (e.type) {
        ElementType.IMAGE -> ThesisStructure.figureCaption(e, n, snapshot.meta.language)
        ElementType.TABLE -> ThesisStructure.tableCaption(e, n, snapshot.meta.language)
        else -> null
    }
}

@Composable
internal fun DestinationPicker(snapshot: ThesisSnapshot, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val numbering = remember(snapshot) { ThesisStructure.numbering(snapshot) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.thesis_where)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(snapshot.outline(includeDisabled = false).filter { it.first.kind.writable }, key = { it.first.id }) { (s, depth) ->
                    Text(ThesisStructure.displayTitle(s, numbering, snapshot.meta.language), style = contentStyle(),
                        modifier = Modifier.fillMaxWidth().clickable { onPick(s.id) }.padding(start = (depth * 14).dp, top = 10.dp, bottom = 10.dp).testTag("thesis_pick_${s.id}"))
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}

@Composable
internal fun DiffSummary(diff: ThesisDiffResult) {
    val c = diff.counts
    Text(
        stringResource(R.string.thesis_diff_summary, c[ChangeKind.ADDED] ?: 0, c[ChangeKind.DELETED] ?: 0, c[ChangeKind.MODIFIED] ?: 0, c[ChangeKind.MOVED] ?: 0, c[ChangeKind.FORMAT_ONLY] ?: 0) +
            if (diff.sections.isNotEmpty()) " · " + stringResource(R.string.thesis_diff_sections, diff.sections.size) else "",
        style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("thesis_diff_summary")
    )
}

@Composable
internal fun ChangeCard(c: ElementChange, assets: List<ThesisAsset>, caption: String?) {
    val added = Color(0x3334A853)
    val removed = Color(0x33EA4335)
    val label = stringResource(
        when (c.kind) {
            ChangeKind.ADDED -> R.string.thesis_change_added
            ChangeKind.DELETED -> R.string.thesis_change_deleted
            ChangeKind.MODIFIED -> R.string.thesis_change_modified
            ChangeKind.MOVED -> R.string.thesis_change_moved
            ChangeKind.FORMAT_ONLY -> R.string.thesis_change_format
        }
    )
    Card(Modifier.fillMaxWidth().testTag("thesis_change_${c.elementId}"),
        colors = CardDefaults.cardColors(containerColor = when (c.kind) { ChangeKind.ADDED -> added; ChangeKind.DELETED -> removed; else -> MaterialTheme.colorScheme.surfaceVariant })) {
        Column(Modifier.padding(10.dp)) {
            Text(label + if (c.moved && c.kind == ChangeKind.MODIFIED) " · " + stringResource(R.string.thesis_change_moved) else "", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            when {
                c.kind == ChangeKind.MODIFIED && c.segments.isNotEmpty() -> Text(buildAnnotatedString {
                    c.segments.forEachIndexed { i, s ->
                        if (i > 0) append(" ")
                        when (s.kind) {
                            SegmentKind.EQUAL -> append(s.text)
                            SegmentKind.INSERTED -> withStyle(SpanStyle(background = added, fontWeight = FontWeight.SemiBold)) { append(s.text) }
                            SegmentKind.DELETED -> withStyle(SpanStyle(background = removed, textDecoration = TextDecoration.LineThrough)) { append(s.text) }
                        }
                    }
                }, style = contentStyle().copy(fontSize = 14.sp))
                c.after != null -> ElementBody(c.after, assets, caption)
                c.before != null -> Text(c.before.text, style = contentStyle().copy(fontSize = 14.sp, textDecoration = TextDecoration.LineThrough))
            }
        }
    }
}

/** Revisions that changed the open section; each one can be compared with the revision before it. */
@Composable
internal fun SectionHistoryCard(controller: ThesisController, history: List<ThesisRevisionEntity>, onClose: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(top = 8.dp).testTag("thesis_section_history_list")) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.thesis_section_history), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_close)) }
            }
            if (history.isEmpty()) Text(stringResource(R.string.thesis_section_history_empty), style = MaterialTheme.typography.bodySmall)
            history.forEach { r ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.thesis_revision_short, r.number) + " · " + sourceLabel(r.source), style = MaterialTheme.typography.labelLarge)
                        Text(formatDate(r.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (r.number > 1) TextButton(onClick = { controller.compareWithPrevious(r) }, modifier = Modifier.testTag("thesis_section_rev_${r.number}")) { Text(stringResource(R.string.thesis_changes_made)) }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ history

@Composable
internal fun ThesisHistoryScreen(controller: ThesisController) {
    val state by controller.state.collectAsState()
    val revisions by controller.revisions.collectAsState()
    var restoring by remember { mutableStateOf<ThesisRevisionEntity?>(null) }
    val compare = state.compare
    val current = revisions.firstOrNull()?.number
    LazyColumn(Modifier.fillMaxSize().testTag("thesis_history_list"), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (compare != null) {
            item {
                Text(
                    if (compare.againstPrevious) stringResource(R.string.thesis_changes_in_revision, compare.revision.number)
                    else stringResource(R.string.thesis_compare_title, compare.revision.number),
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("thesis_compare_header")
                )
                Text(stringResource(if (compare.againstPrevious) R.string.thesis_compare_previous_note else R.string.thesis_compare_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                DiffSummary(compare.diff)
                if (compare.diff.elements.isEmpty() && compare.diff.sections.isEmpty()) Text(stringResource(R.string.thesis_compare_same), style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (compare.revision.number != current) {
                        Button(onClick = { restoring = compare.revision }, enabled = state.busy == null, modifier = Modifier.testTag("thesis_restore")) { Text(stringResource(R.string.thesis_restore)) }
                    }
                    OutlinedButton(onClick = { controller.closeCompare() }, modifier = Modifier.testTag("thesis_compare_close")) { Text(stringResource(R.string.action_close)) }
                }
            }
            // Read from the old version toward the newer one.
            items(compare.diff.elements, key = { "c" + it.elementId }) { c -> ChangeCard(c, state.assets, null) }
            item { HorizontalDivider() }
        }
        item {
            Text(stringResource(R.string.thesis_history), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.thesis_history_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(revisions, key = { it.id }) { r ->
            Card(Modifier.fillMaxWidth().testTag("thesis_revision_${r.number}")) {
                Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp)) {
                    Text(stringResource(R.string.thesis_revision_short, r.number) + " · " + sourceLabel(r.source) + if (r.number == current) " · " + stringResource(R.string.thesis_current) else "",
                        style = MaterialTheme.typography.labelLarge)
                    Text(formatDate(r.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    r.restoredFrom?.let { Text(stringResource(R.string.thesis_restored_from, it), style = MaterialTheme.typography.bodySmall) }
                    if (r.summary.isNotBlank()) Text(r.summary.take(160), style = contentStyle().copy(fontSize = 12.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row {
                        if (r.number > 1) TextButton(onClick = { controller.compareWithPrevious(r) }, modifier = Modifier.testTag("thesis_revision_changes_${r.number}")) { Text(stringResource(R.string.thesis_changes_made)) }
                        if (r.number != current) TextButton(onClick = { controller.compareRevision(r) }, modifier = Modifier.testTag("thesis_revision_compare_${r.number}")) { Text(stringResource(R.string.thesis_compare_current)) }
                    }
                }
            }
        }
    }
    restoring?.let { r ->
        AlertDialog(
            onDismissRequest = { restoring = null },
            title = { Text(stringResource(R.string.thesis_restore_title, r.number)) },
            text = { Text(stringResource(R.string.thesis_restore_text)) },
            confirmButton = { Button(onClick = { controller.restore(r); restoring = null }, modifier = Modifier.testTag("thesis_restore_confirm")) { Text(stringResource(R.string.thesis_restore)) } },
            dismissButton = { TextButton(onClick = { restoring = null }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }
}

internal fun pageCount(file: File): Int = try {
    android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> android.graphics.pdf.PdfRenderer(fd).use { it.pageCount } }
} catch (e: Throwable) { 0 }

/** One page rendered on demand. */
@Composable
internal fun PdfPage(file: File, index: Int, zoom: Float) {
    val widthPx = (700 * zoom).toInt()
    val bitmap by produceState<ImageBitmap?>(null, file, index, widthPx) {
        value = withContext(Dispatchers.IO) { TemplateThumbnails.renderPage(file, index, widthPx)?.asImageBitmap() }
    }
    val b = bitmap
    if (b == null) Box(Modifier.width((300 * zoom).dp).height((420 * zoom).dp).background(Color.White), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(20.dp)) }
    else Image(b, contentDescription = stringResource(R.string.thesis_page_n, index + 1), modifier = Modifier.width((340 * zoom).dp).testTag("thesis_page_$index"), contentScale = ContentScale.FillWidth)
}

// ------------------------------------------------------------------ settings

@Composable
internal fun ThesisSettingsScreen(host: ThesisHost) {
    val controller = host.thesis
    val state by controller.state.collectAsState()
    val projects by controller.projects.collectAsState()
    val snapshot = state.snapshot ?: return
    val project = projects.firstOrNull { it.id == state.projectId } ?: return
    var deleting by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val logoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) controller.importLogo({ context.contentResolver.openInputStream(uri) }, "logo", context.contentResolver.getType(uri) ?: "image/png")
    }
    ThesisMetaForm(
        host = host,
        initial = snapshot.meta,
        initialAi = project.aiAssist,
        busy = state.busy != null,
        saveLabel = R.string.action_save,
        onCancel = { controller.show(ThesisScreen.PROJECT) },
        header = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { logoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                    Text(stringResource(if (snapshot.meta.logoAssetId == null) R.string.thesis_add_logo else R.string.thesis_change_logo))
                }
                OutlinedButton(onClick = { deleting = true }, colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error), modifier = Modifier.testTag("thesis_settings_delete")) {
                    Text(stringResource(R.string.thesis_delete_project))
                }
            }
        }
    ) { meta, ai ->
        if (ai != project.aiAssist) controller.setAiAssist(ai)
        // The logo is changed by its own button; keep the stored one.
        val updated = meta.copy(logoAssetId = snapshot.meta.logoAssetId)
        if (updated != snapshot.meta) controller.updateMeta(updated)
        controller.show(ThesisScreen.PROJECT)
    }
    if (deleting) DeleteProjectDialog(onDismiss = { deleting = false }) { deleting = false; controller.deleteProject(project.id) }
}
